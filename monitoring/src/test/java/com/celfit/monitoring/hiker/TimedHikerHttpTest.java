package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.HikerBadRequestException;
import com.celfit.instagram.source.HikerFetchException;
import com.celfit.instagram.source.SubjectNotFoundException;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * 외부 콜 타이머 데코레이터(2026-08-23 계층별 p95 뺄셈 설계) — "관찰만 한다"를 고정한다:
 * 성공 바디·예외는 그대로 통과하고, 지표는 api=hiker + operation(경로 매핑) + outcome
 * (ok|4xx|5xx|error) 태그의 external.call 타이머 하나로만 남는다. 재시도는 delegate
 * (JdkHikerHttp) 내부라 여기서는 논리 콜 1건 = 기록 1건이다.
 */
class TimedHikerHttpTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private Timer timer(String operation, String outcome) {
		return registry.find("external.call")
				.tags("api", "hiker", "operation", operation, "outcome", outcome).timer();
	}

	@Test
	void 성공_콜은_바디를_그대로_돌려주고_outcome_ok로_1건_기록한다() {
		TimedHikerHttp timed = new TimedHikerHttp(path -> "{\"user\":{}}", registry);

		String body = timed.get("/v2/user/by/username?username=hypenow");

		assertThat(body).isEqualTo("{\"user\":{}}");
		Timer timer = timer("profile", "ok");
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void 경로별_operation_매핑() {
		TimedHikerHttp timed = new TimedHikerHttp(path -> "{}", registry);

		timed.get("/v2/user/by/id?id=123");
		timed.get("/v2/user/medias?user_id=123");
		timed.get("/v2/user/clips?user_id=123&page_id=abc");
		timed.get("/v2/user/tag/medias?user_id=123");
		timed.get("/v2/hashtag/medias/recent?name=celfit");
		timed.get("/v2/media/comments?id=456");
		timed.get("/v2/media/info/by/code?code=DEF");
		timed.get("/v2/media/info/by/url?url=x");

		assertThat(timer("author_profile", "ok").count()).isEqualTo(1);
		assertThat(timer("user_medias", "ok").count()).isEqualTo(1);
		assertThat(timer("user_clips", "ok").count()).isEqualTo(1);
		assertThat(timer("tagged_feed", "ok").count()).isEqualTo(1);
		assertThat(timer("hashtag_recent", "ok").count()).isEqualTo(1);
		assertThat(timer("comments", "ok").count()).isEqualTo(1);
		assertThat(timer("media_info", "ok").count()).isEqualTo(1);
		assertThat(timer("media_by_url", "ok").count()).isEqualTo(1);
	}

	@Test
	void 미지의_경로는_operation_other로_기록한다() {
		TimedHikerHttp timed = new TimedHikerHttp(path -> "{}", registry);

		timed.get("/v2/some/new/endpoint?x=1");

		assertThat(timer("other", "ok").count()).isEqualTo(1);
	}

	@Test
	void 대상_부재_404는_outcome_4xx로_기록하고_예외를_그대로_전파한다() {
		TimedHikerHttp timed = new TimedHikerHttp(path -> {
			throw new SubjectNotFoundException("Hiker 404");
		}, registry);

		assertThatThrownBy(() -> timed.get("/v2/user/tag/medias?user_id=1"))
				.isInstanceOf(SubjectNotFoundException.class);
		assertThat(timer("tagged_feed", "4xx").count()).isEqualTo(1);
	}

	@Test
	void 요청_불량_400은_outcome_4xx로_기록한다() {
		TimedHikerHttp timed = new TimedHikerHttp(path -> {
			throw new HikerBadRequestException("Hiker 400");
		}, registry);

		assertThatThrownBy(() -> timed.get("/v2/media/info/by/url?url=x"))
				.isInstanceOf(HikerBadRequestException.class);
		assertThat(timer("media_by_url", "4xx").count()).isEqualTo(1);
	}

	@Test
	void 상태코드가_실린_5xx_실패는_outcome_5xx로_기록한다() {
		TimedHikerHttp timed = new TimedHikerHttp(path -> {
			throw new HikerFetchException("Hiker HTTP 502: bad gateway", 502);
		}, registry);

		assertThatThrownBy(() -> timed.get("/v2/user/by/username?username=x"))
				.isInstanceOf(HikerFetchException.class);
		assertThat(timer("profile", "5xx").count()).isEqualTo(1);
	}

	@Test
	void 상태코드가_실린_4xx_실패는_outcome_4xx로_기록한다() {
		// 402(잔액 소진)·429 등 — Hiker는 4xx도 과금하므로 4xx를 별도 축으로 남긴다
		TimedHikerHttp timed = new TimedHikerHttp(path -> {
			throw new HikerFetchException("Hiker HTTP 402: payment required", 402);
		}, registry);

		assertThatThrownBy(() -> timed.get("/v2/user/by/username?username=x"))
				.isInstanceOf(HikerFetchException.class);
		assertThat(timer("profile", "4xx").count()).isEqualTo(1);
	}

	@Test
	void 상태코드_없는_실패는_outcome_error로_기록한다() {
		// IO·타임아웃·키 미설정 등 — HTTP 교환 자체가 없던 실패
		TimedHikerHttp timed = new TimedHikerHttp(path -> {
			throw new HikerFetchException("Hiker 요청 실패: connect timeout");
		}, registry);

		assertThatThrownBy(() -> timed.get("/v2/user/medias?user_id=1"))
				.isInstanceOf(HikerFetchException.class);
		assertThat(timer("user_medias", "error").count()).isEqualTo(1);
	}

	@Test
	void 지표_기록_실패는_콜을_죽이지_않는다() {
		SimpleMeterRegistry dying = new SimpleMeterRegistry() {
			@Override
			protected io.micrometer.core.instrument.Timer newTimer(
					io.micrometer.core.instrument.Meter.Id id,
					io.micrometer.core.instrument.distribution.DistributionStatisticConfig config,
					io.micrometer.core.instrument.distribution.pause.PauseDetector detector) {
				throw new IllegalStateException("레지스트리 죽음 주입");
			}
		};
		TimedHikerHttp timed = new TimedHikerHttp(path -> "body", dying);

		assertThat(timed.get("/v2/user/by/username?username=x")).isEqualTo("body");
	}
}
