package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.service.BrandHashtagRunStateResolver.RunState;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 태그별 스윕 실행 상태 판정(FE 요청, 2026-08-31) — 순수 함수라 DB 없이 경계값만 고정한다.
 * BrandHashtagCollectServiceTest는 이 판정의 <b>입력</b>(started_at·finished_at·found_count·
 * failed)이 doSweep에서 올바르게 기록되는지를 보고, 여기는 그 입력을 status로 바꾸는 규칙 자체를 본다.
 */
class BrandHashtagRunStateResolverTest {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-31T12:00:00Z");

	@Test
	void 한_번도_안_돈_태그는_collecting이고_lastRunAt은_null이다() {
		RunState state = BrandHashtagRunStateResolver.resolve(null, null, null, false, NOW);

		assertThat(state.status()).isEqualTo(BrandHashtagRunStateResolver.COLLECTING);
		assertThat(state.lastRunAt()).isNull();
		assertThat(state.lastFoundCount()).isNull();
	}

	@Test
	void 방금_시작해_아직_안_끝난_첫_실행은_collecting이다() {
		OffsetDateTime startedAt = NOW.minusMinutes(2);

		RunState state = BrandHashtagRunStateResolver.resolve(startedAt, null, null, false, NOW);

		assertThat(state.status()).isEqualTo(BrandHashtagRunStateResolver.COLLECTING);
		assertThat(state.lastRunAt()).isNull();
	}

	@Test
	void 직전_완료_후_재실행_중이면_collecting이고_직전_완료값을_그대로_보여준다() {
		OffsetDateTime finishedAt = NOW.minusHours(1);
		OffsetDateTime startedAt = NOW.minusMinutes(1);   // finishedAt보다 뒤 — 재실행 중

		RunState state = BrandHashtagRunStateResolver.resolve(startedAt, finishedAt, 4, false, NOW);

		assertThat(state.status()).isEqualTo(BrandHashtagRunStateResolver.COLLECTING);
		assertThat(state.lastRunAt()).isEqualTo(finishedAt);
		assertThat(state.lastFoundCount()).isEqualTo(4);
	}

	@Test
	void 정상_종료된_성공_0건은_done이고_lastFoundCount는_0이다() {
		OffsetDateTime finishedAt = NOW.minusMinutes(5);
		OffsetDateTime startedAt = NOW.minusMinutes(6);

		RunState state = BrandHashtagRunStateResolver.resolve(startedAt, finishedAt, 0, false, NOW);

		assertThat(state.status()).isEqualTo(BrandHashtagRunStateResolver.DONE);
		assertThat(state.lastRunAt()).isEqualTo(finishedAt);
		assertThat(state.lastFoundCount()).isEqualTo(0);
	}

	@Test
	void 정상_종료된_실패는_failed다() {
		OffsetDateTime finishedAt = NOW.minusMinutes(5);
		OffsetDateTime startedAt = NOW.minusMinutes(6);

		RunState state = BrandHashtagRunStateResolver.resolve(startedAt, finishedAt, 0, true, NOW);

		assertThat(state.status()).isEqualTo(BrandHashtagRunStateResolver.FAILED);
		assertThat(state.lastRunAt()).isEqualTo(finishedAt);
	}

	/** 크래시 잔류 방지 — started_at이 10분 이상 과거인데 아직 안 끝났으면 in-flight로 보지 않는다. */
	@Test
	void 십분_이상_멈춰있는_재실행은_크래시로_보고_이전_종료_상태로_폴백한다() {
		OffsetDateTime finishedAt = NOW.minusHours(2);
		OffsetDateTime startedAt = NOW.minusMinutes(11);   // 10분 초과 — 크래시 잔류 의심

		RunState state = BrandHashtagRunStateResolver.resolve(startedAt, finishedAt, 7, false, NOW);

		assertThat(state.status()).isEqualTo(BrandHashtagRunStateResolver.DONE);
		assertThat(state.lastRunAt()).isEqualTo(finishedAt);
		assertThat(state.lastFoundCount()).isEqualTo(7);
	}

	/** 크래시 잔류 + 이전 완료 이력 자체가 없음(첫 실행이 크래시로 끝남) — failed, lastRunAt은 null. */
	@Test
	void 십분_이상_멈춘_첫_실행이면서_이전_완료가_없으면_failed다() {
		OffsetDateTime startedAt = NOW.minusMinutes(15);

		RunState state = BrandHashtagRunStateResolver.resolve(startedAt, null, null, false, NOW);

		assertThat(state.status()).isEqualTo(BrandHashtagRunStateResolver.FAILED);
		assertThat(state.lastRunAt()).isNull();
		assertThat(state.lastFoundCount()).isNull();
	}

	/** 경계값 — 정확히 10분이면 이미 stale(>= 임계값)로 판정한다. */
	@Test
	void 정확히_10분_경과는_stale로_판정한다() {
		OffsetDateTime finishedAt = NOW.minusHours(2);
		OffsetDateTime startedAt = NOW.minusMinutes(10);

		RunState state = BrandHashtagRunStateResolver.resolve(startedAt, finishedAt, 3, false, NOW);

		assertThat(state.status()).isEqualTo(BrandHashtagRunStateResolver.DONE);
	}

	/** 경계값 — 10분 미만이면 아직 in-flight로 본다. */
	@Test
	void 십분_직전_경과는_아직_collecting이다() {
		OffsetDateTime finishedAt = NOW.minusHours(2);
		OffsetDateTime startedAt = NOW.minusMinutes(10).plusSeconds(1);

		RunState state = BrandHashtagRunStateResolver.resolve(startedAt, finishedAt, 3, false, NOW);

		assertThat(state.status()).isEqualTo(BrandHashtagRunStateResolver.COLLECTING);
	}
}
