package com.celfit.monitoring.hiker;

import com.celfit.monitoring.store.RawPayloadRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 원형 적재 데코레이터 — 성공 응답을 콜 단위로 raw.fetch_payload에 남긴다.
 *
 * <p>파싱 계층이 아니라 전송 계층에서 저장하는 이유: 열거 한 번은 clips·medias를 페이지마다 부르는데
 * 파싱 결과({@link PostInfo#rawJson()})에는 body 하나만 실려서, 호출자가 그걸 저장하면 clips 응답과
 * 2페이지 이후가 통째로 감사에서 사라진다. 여기서 감싸면 콜 수와 적재 행 수가 1:1로 맞는다.
 *
 * <p>스프링 빈이 아니다 — {@code HikerHttp} 타입 자기참조(자기가 자기 delegate가 되는 배선)를 피하려고
 * {@code HikerConfig}에서 delegate를 명시해 조립한다.
 */
public class RecordingHikerHttp implements HikerHttp {

	private static final Logger log = LoggerFactory.getLogger(RecordingHikerHttp.class);

	private final HikerHttp delegate;
	private final RawPayloadRepository rawPayloads;

	public RecordingHikerHttp(HikerHttp delegate, RawPayloadRepository rawPayloads) {
		this.delegate = delegate;
		this.rawPayloads = rawPayloads;
	}

	@Override
	public String get(String path) {
		String body = delegate.get(path);   // 실패(4xx·5xx)는 예외로 나가므로 성공분만 적재된다
		record(path, body);
		return body;
	}

	private void record(String path, String body) {
		// payload는 jsonb 컬럼이라 JSON이 아닌 body(프록시 HTML 오류 페이지 등)를 넣으면 INSERT가 터진다.
		// 수집 실패의 원인이 파싱이 아니라 적재로 보이면 진단이 꼬이므로, 여기서는 건너뛰고 경고만 남긴다.
		if (body == null || !looksLikeJson(body)) {
			log.warn("JSON이 아닌 응답 — 원형 적재 생략: {}", path);
			return;
		}
		rawPayloads.save(kindOf(path), subjectOf(path), 200, body);
	}

	private static boolean looksLikeJson(String body) {
		String trimmed = body.stripLeading();
		return !trimmed.isEmpty() && (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[');
	}

	/** kind는 경로로 판정한다 — 열거 2종(medias·clips)은 같은 POSTS다. */
	private static String kindOf(String path) {
		if (path.startsWith("/v2/user/by/username")) {
			return "PROFILE";
		}
		if (path.startsWith("/v2/user/medias") || path.startsWith("/v2/user/clips")) {
			return "POSTS";
		}
		if (path.startsWith("/v2/media/by/code")) {
			return "POST";
		}
		if (path.startsWith("/v2/media/comments")) {
			return "COMMENTS";
		}
		if (path.startsWith("/v2/media/info/by/url")) {
			return "MEDIA_INFO";
		}
		return "OTHER";
	}

	/** subject는 조회 키 — 프로필은 username, 열거는 user_id, 단건은 code, 댓글은 media id, share 해소는 url. */
	private static String subjectOf(String path) {
		String key = switch (kindOf(path)) {
			case "PROFILE" -> "username";
			case "POSTS" -> "user_id";
			case "POST" -> "code";
			case "COMMENTS" -> "id";
			case "MEDIA_INFO" -> "url";
			default -> null;
		};
		String value = key == null ? null : param(path, key);
		return value == null ? path : value;   // 판정 불가여도 행은 남긴다(경로가 곧 단서)
	}

	private static String param(String path, String name) {
		int q = path.indexOf('?');
		if (q < 0) {
			return null;
		}
		for (String pair : path.substring(q + 1).split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(name)) {
				return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}
}
