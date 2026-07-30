package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.v1.common.V1ApiException;
import org.junit.jupiter.api.Test;

/** 캠페인 이름 정규화·검증(스펙 6.25 Campaign) 순수 단위 테스트. */
class CampaignNameTest {

	@Test
	void 앞뒤_공백을_제거한다() {
		assertThat(CampaignName.normalize("  여름 캠페인  ")).isEqualTo("여름 캠페인");
	}

	@Test
	void 연속_공백을_1칸으로_축약한다() {
		assertThat(CampaignName.normalize("여름   캠페인")).isEqualTo("여름 캠페인");
	}

	@Test
	void 정확히_40자는_통과한다() {
		String name = "가".repeat(40);
		assertThat(CampaignName.normalize(name)).hasSize(40);
	}

	@Test
	void 사십일자는_거부한다() {
		String name = "가".repeat(41);
		assertThatThrownBy(() -> CampaignName.normalize(name))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void 슬래시는_금지_문자다() {
		assertThatThrownBy(() -> CampaignName.normalize("여름/캠페인"))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void 퍼센트는_금지_문자다() {
		assertThatThrownBy(() -> CampaignName.normalize("여름%캠페인"))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void 개행은_금지_문자다() {
		assertThatThrownBy(() -> CampaignName.normalize("여름\n캠페인"))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void 캐리지리턴_단독도_금지_문자다() {
		assertThatThrownBy(() -> CampaignName.normalize("여름\r캠페인"))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void CRLF_개행도_금지_문자다() {
		assertThatThrownBy(() -> CampaignName.normalize("여름\r\n캠페인"))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void 빈_문자열은_거부한다() {
		assertThatThrownBy(() -> CampaignName.normalize("   "))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void null은_거부한다() {
		assertThatThrownBy(() -> CampaignName.normalize(null))
				.isInstanceOf(V1ApiException.class);
	}
}
