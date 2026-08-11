package com.celfit.was.v2.monitoring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 캠페인 연결 슬림 경로 단위 검증 — 레거시 patch가 하던 두 소유 검증(캠페인·아이템)이 슬림
 * 경로에서도 <b>반드시</b> 남아 있는지가 핵심이다. 검증을 빼면 쿼리 절감이 보안 구멍이 된다.
 */
@ExtendWith(MockitoExtension.class)
class CampaignItemLinkerTest {

	private static final long USER_ID = 7L;
	private static final long CAMPAIGN_ID = 42L;
	private static final long ITEM_ID = 11L;

	@Mock
	CampaignRepository campaignRepository;
	@Mock
	MonitoringItemRepository itemRepository;

	CampaignItemLinker linker;

	@BeforeEach
	void setUp() {
		linker = new CampaignItemLinker(campaignRepository, itemRepository);
	}

	@Test
	void link는_두_소유_검증_후_campaign_id만_갱신한다() {
		givenCampaign();
		givenItem();

		linker.link(USER_ID, CAMPAIGN_ID, ITEM_ID);

		then(itemRepository).should().updateCampaign(ITEM_ID, CAMPAIGN_ID);
	}

	@Test
	void link는_남의_캠페인이면_404고_갱신하지_않는다() {
		given(campaignRepository.findByIdAndUser(CAMPAIGN_ID, USER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> linker.link(USER_ID, CAMPAIGN_ID, ITEM_ID))
				.isInstanceOf(V1ApiException.class)
				.hasMessage("캠페인을 찾을 수 없습니다.");
		then(itemRepository).should(never()).updateCampaign(anyLong(), any());
	}

	@Test
	void link는_남의_아이템이면_404고_갱신하지_않는다() {
		givenCampaign();
		given(itemRepository.findByIdAndUser(ITEM_ID, USER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> linker.link(USER_ID, CAMPAIGN_ID, ITEM_ID))
				.isInstanceOf(V1ApiException.class)
				.hasMessage("대상을 찾을 수 없습니다.");
		then(itemRepository).should(never()).updateCampaign(anyLong(), any());
	}

	@Test
	void unlink는_아이템_검증_후_연결을_해제한다() {
		givenItem();

		linker.unlink(USER_ID, ITEM_ID);

		then(itemRepository).should().updateCampaign(ITEM_ID, null);
	}

	@Test
	void unlink는_남의_아이템이면_404고_갱신하지_않는다() {
		given(itemRepository.findByIdAndUser(ITEM_ID, USER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> linker.unlink(USER_ID, ITEM_ID))
				.isInstanceOf(V1ApiException.class)
				.hasMessage("대상을 찾을 수 없습니다.");
		then(itemRepository).should(never()).updateCampaign(anyLong(), any());
	}

	// ---------- 픽스처 ----------

	private void givenCampaign() {
		given(campaignRepository.findByIdAndUser(CAMPAIGN_ID, USER_ID)).willReturn(Optional.of(
				new CampaignRow(CAMPAIGN_ID, USER_ID, "여름 캠페인", null, null, null, null, null, null,
						OffsetDateTime.parse("2026-08-01T00:00:00Z"))));
	}

	private void givenItem() {
		given(itemRepository.findByIdAndUser(ITEM_ID, USER_ID)).willReturn(Optional.of(
				new MonitoringItemRow(ITEM_ID, USER_ID, "url", null, null, null,
						"https://www.instagram.com/reel/ABC/", "https://www.instagram.com/reel/ABC/",
						null, 30, LocalDate.parse("2026-08-01"), null, null,
						OffsetDateTime.parse("2026-08-01T00:00:00Z"))));
	}
}
