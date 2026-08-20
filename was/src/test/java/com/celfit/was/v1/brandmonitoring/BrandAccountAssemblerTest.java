package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * 다음 스윕 예정 시각 계산(스펙 §5-2 nextScheduledAt) — 경계(정각 전후·자정 넘김) 고정
 * + 상태 유도(collecting|ready|error 3값) 고정.
 */
class BrandAccountAssemblerTest {

	private final BrandAccountAssembler assembler = new BrandAccountAssembler(2);

	private static ZonedDateTime kst(String iso) {
		return ZonedDateTime.parse(iso);
	}

	/** 상태 유도에 쓰이는 네 컬럼만 파라미터로 열어 둔 brand_account 행 — 나머지는 유도와 무관하다. */
	private static BrandAccountRow row(LocalDate lastSweptOn, OffsetDateTime lastSweptAt,
			OffsetDateTime backfillCompletedAt, String backfillError) {
		return row(lastSweptOn, lastSweptAt, backfillCompletedAt, backfillError, false, null);
	}

	/** 커버리지 2컬럼(수집 상한 v2 §7-1)까지 연 변형 — 상태 유도와는 독립이다. */
	private static BrandAccountRow row(LocalDate lastSweptOn, OffsetDateTime lastSweptAt,
			OffsetDateTime backfillCompletedAt, String backfillError, boolean capped,
			OffsetDateTime coveredUntil) {
		return new BrandAccountRow(100L, "lizda_official", lastSweptOn, lastSweptAt,
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), backfillCompletedAt, backfillError,
				30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-12T10:00:00Z"), capped, coveredUntil);
	}

	/**
	 * 확장 중(last_swept_on null · backfill_completed_at null · last_swept_at 있음)은 ready다
	 * (2026-08-13) — 08-12의 "확장 중 collecting" 분기는 완주 시각 리셋으로 도달 불가가 됐다.
	 * 진행 여부 판정은 FE가 collectionCompletedAt == null로 한다.
	 */
	@Test
	void 확장_중_계정은_ready다() {
		var response = assembler.toResponse(
				row(null, OffsetDateTime.parse("2026-08-07T00:00:00Z"), null, null), "own", 12);

		assertThat(response.collectionStatus()).isEqualTo("ready");
		assertThat(response.collectionCompletedAt()).isNull();
	}

	/** 재가입·첫 등록 직후(전부 null) 백필 실패는 여전히 error다 — 보여줄 데이터가 없는 상태. */
	@Test
	void 보여줄_데이터가_없는_백필_실패는_error다() {
		var response = assembler.toResponse(row(null, null, null, "초기 수집에 실패했어요."), "own", 12);

		assertThat(response.collectionStatus()).isEqualTo("error");
		assertThat(response.collectionError().code()).isEqualTo("BACKFILL_FAILED");
	}

	/** 첫 수집 진행 중(전부 null + 오류 없음)만 collecting이다 — 남은 유일한 collecting 분기. */
	@Test
	void 첫_수집_진행_중만_collecting이다() {
		assertThat(assembler.toResponse(row(null, null, null, null), "own", 12).collectionStatus())
				.isEqualTo("collecting");
	}

	/** 이번 창 기준 완주(last_swept_on 보유)는 ready — 완주 시각도 응답에 실린다. */
	@Test
	void 완주한_계정은_ready이고_완주_시각을_싣는다() {
		var response = assembler.toResponse(row(LocalDate.of(2026, 8, 7),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null), "own", 12);

		assertThat(response.collectionStatus()).isEqualTo("ready");
		assertThat(response.collectionCompletedAt()).isEqualTo("2026-08-01T10:00:00+09:00");
	}

	@Test
	void 스윕_시각_전이면_오늘_그_시각이다() {
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T01:30:00+09:00"), 3))
				.isEqualTo("2026-08-07T03:00:00+09:00");
	}

	@Test
	void 스윕_시각_후면_내일_그_시각이다() {
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T03:00:01+09:00"), 3))
				.isEqualTo("2026-08-08T03:00:00+09:00");
	}

	@Test
	void 정각_동시각은_이미_지난_것으로_보고_내일로_민다() {
		// 03:00:00 정각에 조회하면 그 스윕은 이미 시작된 것으로 본다 — "곧 온다"가 아니라 "다음"이 정답.
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T03:00:00+09:00"), 3))
				.isEqualTo("2026-08-08T03:00:00+09:00");
	}

	@Test
	void 자정_직전이면_다음_날_스윕이다() {
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T23:59:59+09:00"), 3))
				.isEqualTo("2026-08-08T03:00:00+09:00");
	}

	@Test
	void UTC_입력도_KST_기준으로_환산된다() {
		// UTC 2026-08-07T18:30Z = KST 2026-08-08T03:30 → 그날 스윕은 지났다.
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T18:30:00Z"), 3))
				.isEqualTo("2026-08-09T03:00:00+09:00");
	}
}
