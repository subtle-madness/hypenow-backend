package com.celfit.was.v1.brandmonitoring.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
 * 60초 응답 계약 안에서 이 호출이 5초 넘게 물고 있으면 안 된다) - {@code brandAiChatExecutor}(2 스레드,
 * 이미 이번 요청이 하나를 쓰고 있다)를 쓰지 않고 기본 {@link CompletableFuture} 풀(ForkJoinPool
 * commonPool)에서 돌린다 - 그 좁은 풀을 다시 물면 동시 요청 처리량이 줄어든다.
 */
public class BrandAiFollowUpGenerator {

	private static final Logger log = LoggerFactory.getLogger(BrandAiFollowUpGenerator.class);

	private static final int TIMEOUT_SECONDS = 5;
	private static final int MAX_OUTPUT_TOKENS = 512;
	/** 입력 답변 절단 길이(설계 §요구) - 후속 질문 재료로는 이 정도면 충분하고 토큰을 아낀다. */
	private static final int ANSWER_TRUNCATE_LENGTH = 3_000;

	private static final String SYSTEM = """
			당신은 하입나우 브랜드 모니터링 어시스턴트의 답변을 보고 후속 질문 2개를 제안하는 보조 도우미입니다.

			규칙:
			1. 정확히 2개를 만듭니다. 첫 번째는 kind="deepen"(답변에 나온 구체적인 대상을 더 파고드는 질문), 두 번째는
			   kind="action"(DM 초안 작성·보고 문장 만들기·광고 소재 선별·사용권 요청 중 하나로 이어지는 질문)입니다.
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

	private final GeminiChatClient client;
	private final ObjectMapper objectMapper;
	private final JsonNode responseSchema;

	public BrandAiFollowUpGenerator(GeminiChatClient client, ObjectMapper objectMapper) {
		this.client = client;
		this.objectMapper = objectMapper;
		this.responseSchema = objectMapper.readTree(RESPONSE_SCHEMA_JSON);
	}

	/** 실패·타임아웃·파싱 불가 시 빈 배열(기능 저해 금지, 설계 §요구). 호출부가 이미 "실제 답변"임을 검증한 뒤에만 부른다. */
	public List<AiMessagesResponse.FollowUp> generate(String question, String answer) {
		String userText = "질문: " + question + "\n답변: " + truncate(answer, ANSWER_TRUNCATE_LENGTH);
		try {
			CompletableFuture<String> future = CompletableFuture.supplyAsync(
					() -> client.generateStructured(SYSTEM, userText, responseSchema, MAX_OUTPUT_TOKENS));
			String raw = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
			String itemText = item.path("text").asString(null);
			String kind = item.path("kind").asString(null);
			if (itemText != null && !itemText.isBlank() && kind != null) {
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
