package com.celfit.was.v1.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * GET /v1/admin/campaigns status 유도 순수 함수 단위 테스트(프론트 변경요청서 4-2-6절, 08-02) —
 * DB 없이 4종 판정과 경계값(오늘==start_date, 오늘==end_date는 둘 다 active)을 고정한다.
 */
class AdminCampaignStatusTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 2);

	@Test
	void start_end_둘_다_null이면_no_date다() {
		assertThat(AdminCampaignStatus.deriveStatus(null, null, TODAY)).isEqualTo(AdminCampaignStatus.NO_DATE);
	}

	@Test
	void 오늘이_start_date_이전이면_pending이고_경계값_오늘_start_date는_active다() {
		assertThat(AdminCampaignStatus.deriveStatus(TODAY.plusDays(1), null, TODAY))
				.isEqualTo(AdminCampaignStatus.PENDING);
		assertThat(AdminCampaignStatus.deriveStatus(TODAY, null, TODAY)).isEqualTo(AdminCampaignStatus.ACTIVE);
	}

	@Test
	void 오늘이_end_date_이후면_ended이고_경계값_오늘_end_date는_active다() {
		assertThat(AdminCampaignStatus.deriveStatus(null, TODAY.minusDays(1), TODAY))
				.isEqualTo(AdminCampaignStatus.ENDED);
		assertThat(AdminCampaignStatus.deriveStatus(null, TODAY, TODAY)).isEqualTo(AdminCampaignStatus.ACTIVE);
	}

	@Test
	void 기간_안이면_active다() {
		assertThat(AdminCampaignStatus.deriveStatus(TODAY.minusDays(3), TODAY.plusDays(3), TODAY))
				.isEqualTo(AdminCampaignStatus.ACTIVE);
	}

	@Test
	void start만_설정돼_오늘이_start_이후면_active다() {
		assertThat(AdminCampaignStatus.deriveStatus(TODAY.minusDays(1), null, TODAY))
				.isEqualTo(AdminCampaignStatus.ACTIVE);
	}

	@Test
	void end만_설정돼_오늘이_end_이전이면_active다() {
		assertThat(AdminCampaignStatus.deriveStatus(null, TODAY.plusDays(1), TODAY))
				.isEqualTo(AdminCampaignStatus.ACTIVE);
	}
}
