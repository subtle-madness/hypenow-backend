package com.celfit.monitoring.llm;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * IG 표시명 → 브랜드 상호 해시태그 1개(2026-09-03 자동 시드 재설계 §3-3) — 태그된 게시물 캡션
 * 집계(FREQ)가 임계에 못 미칠 때 쓰는 2순위다.
 *
 * <p>입력은 <b>표시명(full_name)과 계정명뿐</b>이다. 회사명(was {@code users.company_name})·
 * 바이오는 넣지 않는다 — 회사명은 등록자가 자기 소속을 적은 값이라 경쟁사 브랜드에 남의 이름을
 * 붙일 수 있고, 바이오는 잡음이 많다.
 *
 * <p>전송은 광고 표기 판정과 같은 {@link GeminiHttp} 빈·같은 모델 설정을 재사용한다(새 HTTP
 * 클라이언트를 만들지 않는다, {@code AdDisclosureExtractorGemini}와 동형).
 *
 * <p><b>출력은 버리지 않고 정리한다</b> — 선행 {@code #} 제거 → strip → 소문자 → 허용 외 문자
 * ({@code [\p{L}\p{N}_]} 밖) <b>제거</b> → 30자 초과 절단. 그 결과가 비었거나 순수 숫자거나
 * stoplist면 빈 값을 돌려주고, 상위({@code BrandHashtagSuggestionService})가 FALLBACK으로 내린다.
 * "AI가 조금 틀린 형태로 답했다"는 이유로 브랜드를 계정명 안전장치까지 떨어뜨리지 않기 위함이다.
 *
 * <p>{@code AdDisclosureExtractorGemini}와 갈리는 지점: 미설정(enabled=false)일 때 예외를 던지지
 * 않고 조용히 빈 값을 돌려준다. 광고 판정은 결과가 컬럼에 영속화되므로 잘못된 값을 남기느니
 * 실패해야 하지만, 여기는 빈 값이 곧 FALLBACK이라 정상 경로다.
 */
public class BrandHashtagSuggester {

	private static final Logger log = LoggerFactory.getLogger(BrandHashtagSuggester.class);

	private static final String SYSTEM_INSTRUCTION = """
			너는 인스타그램 브랜드 계정의 표시명과 계정명을 받아, 소비자가 그 브랜드를 게시물에
			언급할 때 가장 흔히 쓸 해시태그를 정확히 1개 고르는 도구다.

			규칙:
			- 표시명에 브랜드 상호가 있으면 그 상호를 쓴다. 상호가 한글이면 한글로 쓴다.
			- 표시명이 비어 있거나 상호가 없으면(영문 약자·수식어뿐), 계정명에서 '_official',
			  '.official', '_kr', '_korea' 같은 접미사와 장식을 떼고 남는 브랜드 핵심을 쓴다.
			  점·언더스코어를 살릴지 뺄지는 해시태그로 자연스러운 쪽으로 네가 판단한다.
			- 답은 JSON {"hashtag": "..."} 형태만 낸다. 설명·부연·다른 필드를 넣지 않는다.
			- '#'을 붙이지 않는다. 공백·특수문자·이모지를 넣지 않는다.

			예시:
			- 표시명 "닥터피엘 Dr.PIEL", 계정명 "dr.piel_official" → {"hashtag": "닥터피엘"}
			- 표시명 "", 계정명 "dr.piel_official" → {"hashtag": "drpiel"}
			- 표시명 "", 계정명 "cclime_official" → {"hashtag": "cclime"}
			""";

	/** 허용 문자 — 글자(한글 포함)·숫자·언더스코어. 이 밖은 제거 대상이다. */
	private static final Pattern NOT_ALLOWED = Pattern.compile("[^\\p{L}\\p{N}_]");
	private static final Pattern DIGITS_ONLY = Pattern.compile("\\p{N}+");
	private static final int MAX_LENGTH = 30;

	private final GeminiHttp http;
	private final boolean enabled;
	private final String model;
	private final ObjectMapper om = new ObjectMapper();

	/** enabled는 {@code LlmTransportConfig.LlmEnabled}의 값 — 인증은 주입된 전송이 전담한다. */
	public BrandHashtagSuggester(GeminiHttp http, boolean enabled, String model) {
		this.http = http;
		this.enabled = enabled;
		this.model = model;
	}

	/**
	 * @param fullName IG 표시명(`brand_account.full_name`). null·공백이면 계정명만으로 진행한다.
	 * @param username IG 계정명. null·공백이면 호출 없이 빈 값(도달 불가 — 방어).
	 * @param stoplist 제외 태그(전부 소문자).
	 * @return 정리된 태그(소문자). 미설정·정리 결과 무효는 빈 값. 전송·파싱 실패는 예외.
	 */
	public Optional<String> suggest(String fullName, String username, Set<String> stoplist) {
		if (!enabled) {
			log.debug("Gemini 미설정 — 표시명 해시태그 제안 건너뜀");
			return Optional.empty();
		}
		if (username == null || username.isBlank()) {
			return Optional.empty();
		}
		String responseBody = http.post("/v1beta/models/" + model + ":generateContent",
				requestBody(fullName, username));
		return clean(parse(responseBody), stoplist);
	}

	private String requestBody(String fullName, String username) {
		ObjectNode root = om.createObjectNode();
		root.putObject("systemInstruction").putArray("parts").addObject().put("text", SYSTEM_INSTRUCTION);
		root.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text",
						"표시명: " + (fullName == null ? "" : fullName) + "\n계정명: " + username);
		ObjectNode gen = root.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", responseSchema());
		// 태그 1개만 담으면 되므로 광고 추출(512)보다 훨씬 작다 — 잘림은 파싱 실패로 드러난다.
		gen.put("maxOutputTokens", 64);
		return om.writeValueAsString(root);
	}

	private ObjectNode responseSchema() {
		ObjectNode schema = om.createObjectNode();
		schema.put("type", "object");
		schema.putObject("properties").putObject("hashtag").put("type", "string");
		schema.putArray("required").add("hashtag");
		return schema;
	}

	private String parse(String responseBody) {
		JsonNode root = om.readTree(responseBody);
		JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		if (text.isMissingNode()) {
			throw new IllegalStateException("Gemini 응답에 본문 없음: " + abbreviate(responseBody));
		}
		String textValue = text.asString();
		JsonNode innerRoot;
		try {
			innerRoot = om.readTree(textValue);
		} catch (JacksonException e) {
			throw new IllegalStateException("응답 본문 JSON 파싱 실패: " + abbreviate(textValue), e);
		}
		JsonNode hashtag = innerRoot.path("hashtag");
		if (hashtag.isMissingNode() || hashtag.isNull()) {
			throw new IllegalStateException("Gemini 응답에 hashtag 없음: " + abbreviate(textValue));
		}
		return hashtag.asString();
	}

	/** §3-3 출력 정리 — 제거·절단으로 살려내고, 살릴 수 없을 때만 빈 값이다. */
	private Optional<String> clean(String raw, Set<String> stoplist) {
		String tag = raw == null ? "" : raw.strip();
		if (tag.startsWith("#")) {
			tag = tag.substring(1);
		}
		tag = NOT_ALLOWED.matcher(tag.strip().toLowerCase(Locale.ROOT)).replaceAll("");
		if (tag.length() > MAX_LENGTH) {
			tag = tag.substring(0, MAX_LENGTH);
		}
		if (tag.isEmpty()) {
			log.warn("AI 제안 해시태그 정리 결과 없음 — value={}", abbreviate(raw));
			return Optional.empty();
		}
		if (DIGITS_ONLY.matcher(tag).matches()) {
			log.warn("AI 제안 해시태그 폐기(순수 숫자) — value={}", abbreviate(raw));
			return Optional.empty();
		}
		if (stoplist.contains(tag)) {
			log.warn("AI 제안 해시태그 폐기(stoplist) — value={}", abbreviate(raw));
			return Optional.empty();
		}
		return Optional.of(tag);
	}

	private static String abbreviate(String s) {
		return s == null ? "(없음)" : s.length() > 100 ? s.substring(0, 100) + "…" : s;
	}
}
