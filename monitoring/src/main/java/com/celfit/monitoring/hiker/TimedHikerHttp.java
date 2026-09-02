package com.celfit.monitoring.hiker;

import com.celfit.instagram.source.HikerBadRequestException;
import com.celfit.instagram.source.HikerFetchException;
import com.celfit.instagram.source.HikerHttp;
import com.celfit.instagram.source.SubjectNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 외부 콜 타이머 데코레이터(2026-08-23 대시보드 진단 설계 — "계층별 p95 뺄셈"의 외부 API 구간).
 * 지표는 {@code external.call} 타이머 하나에 태그(api=hiker, operation, outcome)로만 남긴다
 * (기능별 지표 남발 금지 — 2026-08-22 태그 방식 확정). 재시도·백오프는 delegate(JdkHikerHttp)
 * 내부에 있어 여기 기록은 "호출자가 본 논리 콜 1건"의 총 소요다 — 재시도로 살아난 콜은 ok로
 * 접히고, 그 대기 시간까지 지연에 포함된다(뺄셈 진단에 필요한 관점).
 *
 * <p>과금 길목이라 철저히 관찰만 한다: 바디·예외는 그대로 통과하고, 지표 기록 실패는 삼킨다.
 * 실패 콜도 outcome(4xx|5xx|error)으로 남긴다 — Hiker는 4xx도 과금하므로(08-14 실측) 4xx를
 * 별도 축으로 둔다. 조립은 {@code HikerConfig}가 체인 최내곽(전송 바로 바깥)에 끼운다 —
 * Recording·Counting의 DB 쓰기 시간이 외부 구간 지표에 섞이지 않게.
 */
public class TimedHikerHttp implements HikerHttp {

	private static final Logger log = LoggerFactory.getLogger(TimedHikerHttp.class);
	static final String METRIC = "external.call";

	private final HikerHttp delegate;
	private final MeterRegistry registry;

	public TimedHikerHttp(HikerHttp delegate, MeterRegistry registry) {
		this.delegate = delegate;
		this.registry = registry;
	}

	@Override
	public String get(String path) {
		long start = System.nanoTime();
		String outcome = "error";
		try {
			String body = delegate.get(path);
			outcome = "ok";
			return body;
		} catch (RuntimeException e) {
			outcome = outcomeOf(e);
			throw e;
		} finally {
			record(operationOf(path), outcome, System.nanoTime() - start);
		}
	}

	/** 쿼리를 뗀 경로의 정확 일치 매핑 — 태그 카디널리티를 유한 집합으로 못박는다(미지 경로는 other). */
	static String operationOf(String path) {
		int query = path.indexOf('?');
		String p = query >= 0 ? path.substring(0, query) : path;
		return switch (p) {
			case "/v2/user/by/username" -> "profile";
			case "/v2/user/by/id" -> "author_profile";
			case "/v2/user/medias" -> "user_medias";
			case "/v2/user/clips" -> "user_clips";
			case "/v2/user/tag/medias" -> "tagged_feed";
			case "/v2/hashtag/medias/recent" -> "hashtag_recent";
			case "/v2/media/comments" -> "comments";
			case "/v2/media/info/by/code" -> "media_info";
			case "/v2/media/info/by/url" -> "media_by_url";
			default -> "other";
		};
	}

	/** 예외 타입(404·400)과 실린 상태코드로 분류 — 상태코드 없는 실패(IO·타임아웃·키 미설정)는 error. */
	private static String outcomeOf(RuntimeException e) {
		if (e instanceof SubjectNotFoundException || e instanceof HikerBadRequestException) {
			return "4xx";
		}
		if (e instanceof HikerFetchException fe && fe.statusCode() != null) {
			if (fe.statusCode() >= 500) {
				return "5xx";
			}
			if (fe.statusCode() >= 400) {
				return "4xx";
			}
		}
		return "error";
	}

	private void record(String operation, String outcome, long elapsedNanos) {
		try {
			Timer.builder(METRIC)
					.tag("api", "hiker").tag("operation", operation).tag("outcome", outcome)
					.register(registry)
					.record(Duration.ofNanos(elapsedNanos));
		} catch (RuntimeException e) {
			// 관측이 수집을 죽이면 안 된다(CountingHikerHttp와 같은 원칙) — 기록 실패는 로그만
			log.warn("외부 콜 지표 기록 실패(무시) — {} {}: {}", operation, outcome, e.toString());
		}
	}
}
