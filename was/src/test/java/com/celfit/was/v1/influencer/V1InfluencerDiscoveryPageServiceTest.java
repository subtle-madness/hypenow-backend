package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.celfit.was.v1.influencer.V1InfluencerDiscoveryPageService.DiscoveryPage;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 조립 배선 가드 — page()가 본 쿼리 핸들을 보강 쿼리 4개(findShares/findBrands/findThumbs/
 * findEngagements)에 동일하게 전달하는지, total이 countCards를 그대로 싣는지. 컨트롤러 테스트에서
 * 삭제된 findThumbs 캡처 단언의 대체(배선이 컨트롤러 → 서비스로 이동했으니 가드도 따라온다).
 */
class V1InfluencerDiscoveryPageServiceTest {

	private static CardRow row(String handle) {
		return new CardRow(handle, "글로우", "/img/p/" + handle + ".jpg", 20000L, 214L, 380L,
				"소개", "저자극 톤", new BigDecimal("12.4"), new BigDecimal("3.8"),
				413200L, 10370L, 152L, 72L, 3L, null, new BigDecimal("72.5000"), 42L);
	}

	@Test
	void 보강_4쿼리가_모두_같은_handles로_호출되고_total은_첫_행_totalCount다() {
		V1InfluencerDiscoveryRepository repository = mock(V1InfluencerDiscoveryRepository.class);
		V1InfluencerDiscoveryQuery query = V1InfluencerDiscoveryQuery.of(null, null, null, null, null,
				null, null, null, null, 50, 0, null);

		given(repository.findCards(query)).willReturn(List.of(row("a"), row("b")));
		given(repository.findShares(anyList())).willReturn(List.of());
		given(repository.findBrands(anyList())).willReturn(List.of());
		given(repository.findThumbs(anyList())).willReturn(List.of());
		given(repository.findEngagements(anyList())).willReturn(List.of());

		V1InfluencerDiscoveryPageService service =
				new V1InfluencerDiscoveryPageService(repository, new V1InfluencerDiscoveryAssembler());
		DiscoveryPage page = service.page(query);

		// total은 본 쿼리 윈도우(row 픽스처 totalCount=42) — countCards 재실행 없음(2026-08-27 count 통합)
		assertThat(page.total()).isEqualTo(42L);
		verify(repository, org.mockito.Mockito.never()).countCards(query);
		assertThat(page.cards()).hasSize(2);

		ArgumentCaptor<List<String>> shares = ArgumentCaptor.captor();
		ArgumentCaptor<List<String>> brands = ArgumentCaptor.captor();
		ArgumentCaptor<List<String>> thumbs = ArgumentCaptor.captor();
		ArgumentCaptor<List<String>> engagements = ArgumentCaptor.captor();
		verify(repository).findShares(shares.capture());
		verify(repository).findBrands(brands.capture());
		verify(repository).findThumbs(thumbs.capture());
		verify(repository).findEngagements(engagements.capture());

		List<String> expected = List.of("a", "b");
		assertThat(shares.getValue()).isEqualTo(expected);
		assertThat(brands.getValue()).isEqualTo(expected);
		assertThat(thumbs.getValue()).isEqualTo(expected);
		assertThat(engagements.getValue()).isEqualTo(expected);
	}

	@Test
	void 빈_페이지는_countCards로_폴백한다() {
		// offset 초과·공집합이면 첫 행이 없어 totalCount를 모른다 — 이때만 count 쿼리를 다시 친다.
		V1InfluencerDiscoveryRepository repository = mock(V1InfluencerDiscoveryRepository.class);
		V1InfluencerDiscoveryQuery query = V1InfluencerDiscoveryQuery.of(null, null, null, null, null,
				null, null, null, null, 50, 500, null);

		given(repository.findCards(query)).willReturn(List.of());
		given(repository.countCards(query)).willReturn(7L);

		V1InfluencerDiscoveryPageService service =
				new V1InfluencerDiscoveryPageService(repository, new V1InfluencerDiscoveryAssembler());
		DiscoveryPage page = service.page(query);

		assertThat(page.total()).isEqualTo(7L);
		assertThat(page.cards()).isEmpty();
	}
}
