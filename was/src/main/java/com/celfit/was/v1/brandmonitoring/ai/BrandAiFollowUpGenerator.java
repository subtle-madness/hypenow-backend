package com.celfit.was.v1.brandmonitoring.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 답변 뒤에 붙는 후속 질문 제안 생성(FE 변경요청서 2026-08-28 §3.3) - 답변 생성과 별개인 후속 1회
 * LLM 호출이다. {@link BrandAiAgent}의 툴 콜링 루프와 무관하게 동작한다(툴 없음, 구조화 출력만) -
 * 그래서 그 루프의 정지 조건·재시도 로직을 공유하지 않고 이 클래스가 자체 예산(5초)을 든다.
 *
 * <p><b>실패 관용</b>(설계 §요구 "기능 저해 금지") - 타임아웃·전송 실패·파싱 불가는 전부 빈 배열로
 * 접는다. 후속 질문은 답변 자체의 품질과 무관한 부가 기능이라, 여기서 예외가 나서 이미 완성된 답변
 * 응답 전체를 실패시키는 것은 손익이 맞지 않는다.
 *
 * <p>타임아웃은 {@link CompletableFuture#get(long, java.util.concurrent.TimeUnit)}로 강제한다(컨트롤러의
 * 90초 응답 계약(한계 재도출 2026-08-31, 스펙 §4) 안에서 이 호출이 5초 넘게 물고 있으면 안 된다) - {@code brandAiChatExecutor}(2 스레드,
 * 이미 이번 요청이 하나를 쓰고 있다)를 쓰지 않는다.
 *
 * <p><b>F3(2026-08-30 리뷰) 전용 실행기 + 예산 인지 타임아웃</b> - 기본 {@link CompletableFuture} 풀
 * (ForkJoinPool commonPool)은 쓰지 않는다 - 타임아웃이 나도 {@code future.cancel}이 실행 중인 스레드를
 * 인터럽트하지 않아(BrandAiAgent 관용구와 동일한 JDK 명세) 최악 45초(Vertex 요청 타임아웃)까지 스레드가
 * 물려 있을 수 있는데, 그 대가를 앱 전역에서 공유하는 commonPool이 아니라 이 클래스 전용 데몬 스레드가
 * 지도록 생성자로 {@link Executor}를 주입받는다({@link BrandAiConfig}가 전용 1스레드 빈을 배선한다).
 * 컨트롤러가 90초 응답 예산 중 남은 시간을 {@link #generate(String, String, long)}에 넘기면
 * {@code min(5초, 남은 예산)}만큼만 기다린다 - followUps 하나 때문에 이미 다 쓴 응답 예산을 더 잠식하지
 * 않는다(2-인자 {@link #generate(String, String)}는 기존 관용구 그대로 5초 전액을 쓴다).
 */
public class BrandAiFollowUpGenerator {

	private static final Logger log = LoggerFactory.getLogger(BrandAiFollowUpGenerator.class);

	private static final int TIMEOUT_SECONDS = 5;
	private static final long TIMEOUT_MILLIS = TIMEOUT_SECONDS * 1_000L;
	private static final int MAX_OUTPUT_TOKENS = 512;
	/** 입력 답변 절단 길이(설계 §요구) - 후속 질문 재료로는 이 정도면 충분하고 토큰을 아낀다. */
	private static final int ANSWER_TRUNCATE_LENGTH = 3_000;
	/** F4(2026-08-30 리뷰) 서버측 계약 강제 - kind는 이 값만 허용, 그 외는 파싱 단계에서 버린다. */
	private static final Set<String> ALLOWED_KINDS = Set.of("deepen", "action");
	/** F4 - 모델이 규칙을 어기고 3개 이상 만들어도 응답 계약(FE 변경요청서 §3.3, 정확히 2개)을 넘지
	 * 않도록 서버가 최종적으로 자른다. */
	private static final int MAX_FOLLOW_UPS = 2;

	private static final String SYSTEM = """
			당신은 하입나우 브랜드 모니터링 어시스턴트의 답변을 보고 후속 질문 2개를 제안하는 보조 도우미입니다.

			규칙:
			1. 정확히 2개를 만듭니다. 첫 번째는 kind="deepen"(답변에 나온 구체적인 대상을 더 파고드는 질문), 두 번째는
			   kind="action"(보고용 문장으로 정리하기·시딩 후보 더 찾기·특정 크리에이터의 최근 게시물 보기·지난
			   기간과 비교하기·광고 소재 후보 더 보기·부정 댓글이 달린 게시물 자세히 보기 중 하나로 이어지는
			   질문)입니다.
			2. 각 질문은 80자 이내의 한국어 평문입니다.
			3. 주어진 질문·답변에 나온 브랜드 모니터링 데이터(게시물·게시자·지표 등)만으로 답할 수 있는 질문만 만듭니다.
			   영상 내용(장면·구성 등)이나 수집되지 않은 지표를 요구하는 질문은 만들지 않습니다.
			4. 질문과 답변에 실질적인 내용이 없거나(예: 도메인 밖 질문 거절 답변) 후속 질문을 만들 근거가 부족하면
			   빈 배열을 돌려줍니다.
			""";

	private static final String RESPONSE_SCHEMA_JSON = """
			{"type":"array","items":{"type":"object","properties":{
			  "text":{"type":"string"},
			  "kind":{"type":"string","enum":["deepen","action"]}
			},"required":["text","kind"]}}
			""";

	/** 2-인자 생성자 전용 데몬 스레드 팩토리(F3, 2026-08-30 리뷰) - 실 배선({@link BrandAiConfig})은
	 * 전용 빈을 명시적으로 주입하지만, 이 2-인자 생성자로 만들어지는 인스턴스(단발 테스트 등)도 절대
	 * ForkJoinPool commonPool을 쓰지 않도록 같은 원칙(전용 데몬 스레드)을 지킨다. */
	private static final ThreadFactory DAEMON_THREAD_FACTORY = runnable -> {
		Thread thread = new Thread(runnable, "brand-ai-followup-standalone");
		thread.setDaemon(true);
		return thread;
	};

	private final GeminiChatClient client;
	private final ObjectMapper objectMapper;
	private final JsonNode responseSchema;
	private final Executor executor;

	/** 기존 2-인자 관용구 유지(호환, 단발 테스트 전용) - 전용 데몬 스레드를 인스턴스마다 새로 만든다. */
	public BrandAiFollowUpGenerator(GeminiChatClient client, ObjectMapper objectMapper) {
		this(client, objectMapper, Executors.newSingleThreadExecutor(DAEMON_THREAD_FACTORY));
	}

	public BrandAiFollowUpGenerator(GeminiChatClient client, ObjectMapper objectMapper, Executor executor) {
		this.client = client;
		this.objectMapper = objectMapper;
		this.responseSchema = objectMapper.readTree(RESPONSE_SCHEMA_JSON);
		this.executor = executor;
	}

	/** 기존 2-인자 관용구 유지(호환) - 예산 제한 없이(F3) 5초 전액을 쓴다. 실패·타임아웃·파싱 불가 시
	 * 빈 배열(기능 저해 금지, 설계 §요구). 호출부가 이미 "실제 답변"임을 검증한 뒤에만 부른다. */
	public List<AiMessagesResponse.FollowUp> generate(String question, String answer) {
		return generate(question, answer, TIMEOUT_MILLIS);
	}

	/**
	 * @param remainingBudgetMillis 컨트롤러의 90초 응답 예산(한계 재도출 2026-08-31, 스펙 §4) 중 이 호출 시점에 남은 시간(F3, 2026-08-30
	 *                              리뷰) - {@code min(5초, remainingBudgetMillis)}만큼만 기다린다. 이미
	 *                              5초보다 적게 남았으면 그만큼만, 5초보다 많이 남았어도 5초를 넘지
	 *                              않는다. 호출부(컨트롤러)가 1초 미만이면 아예 호출하지 않는 판단을
	 *                              맡는다 - 이 메서드는 "얼마나 기다릴지"만 책임진다.
	 */
	public List<AiMessagesResponse.FollowUp> generate(String question, String answer, long remainingBudgetMillis) {
		long waitMillis = Math.min(TIMEOUT_MILLIS, Math.max(0, remainingBudgetMillis));
		String userText = "질문: " + question + "\n답변: " + truncate(answer, ANSWER_TRUNCATE_LENGTH);
		try {
			CompletableFuture<String> future = CompletableFuture.supplyAsync(
					() -> client.generateStructured(SYSTEM, userText, responseSchema, MAX_OUTPUT_TOKENS), executor);
			String raw = future.get(waitMillis, TimeUnit.MILLISECONDS);
			return parse(raw);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("후속 질문 생성 인터럽트 - 빈 배열로 대체", e);
			return List.of();
		} catch (ExecutionException | TimeoutException | RuntimeException e) {
			log.warn("후속 질문 생성 실패(무시, 빈 배열로 대체)", e);
			return List.of();
		}
	}

	/**
	 * F4(2026-08-30 리뷰) 서버측 계약 강제 - 모델이 프롬프트 규칙(정확히 2개, kind는 deepen/action)을
	 * 어겨도 응답 계약을 지킨다: kind가 {@value #ALLOWED_KINDS}가 아니면 그 항목을 버리고, text가
	 * 비어있으면 버리고, 최종 결과를 {@value #MAX_FOLLOW_UPS}개로 자른다(그 이상은 앞에서부터만 남긴다).
	 * 구조화 출력 스키마({@link #RESPONSE_SCHEMA_JSON})가 kind enum을 이미 제한하지만, Vertex 구조화
	 * 출력이 스키마를 100% 강제하는지는 프로바이더 신뢰 경계 밖이라 서버가 한 번 더 검증한다.
	 */
	private List<AiMessagesResponse.FollowUp> parse(String raw) {
		JsonNode root = objectMapper.readTree(raw);
		String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text")
				.asString(null);
		if (text == null) {
			return List.of();
		}
		JsonNode array = objectMapper.readTree(text);
		if (!array.isArray()) {
			return List.of();
		}
		List<AiMessagesResponse.FollowUp> out = new ArrayList<>();
		for (JsonNode item : array) {
			if (out.size() >= MAX_FOLLOW_UPS) {
				break;
			}
			String itemText = item.path("text").asString(null);
			String kind = item.path("kind").asString(null);
			if (itemText != null && !itemText.isBlank() && kind != null && ALLOWED_KINDS.contains(kind)) {
				out.add(new AiMessagesResponse.FollowUp(itemText, kind));
			}
		}
		return out;
	}

	private static String truncate(String text, int max) {
		if (text == null) {
			return "";
		}
		if (text.codePointCount(0, text.length()) <= max) {
			return text;
		}
		int cut = text.offsetByCodePoints(0, max);
		return text.substring(0, cut) + "...";
	}
}
