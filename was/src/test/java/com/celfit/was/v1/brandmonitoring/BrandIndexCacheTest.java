package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.perfdashboard.DashboardVersion;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 브랜드 표면 인덱스 캐시(FE 요청 2026-08-27 ② — 요청당 고정비 2초 제거) — 버전키가 같으면 인덱스
 * 조립(DB 왕복 + 5천 행 매핑)을 건너뛰고, 버전키가 바뀌면(스윕·유저 쓰기·KST 자정) 반드시 재조립하는
 * 계약을 고정한다. 무효화 정확성은 버전키({@link DashboardVersion})의 계약을 그대로 상속한다.
 */
class BrandIndexCacheTest {

	private final DashboardVersion dashboardVersion = mock(DashboardVersion.class);
	private final BrandPostAssembler assembler = mock(BrandPostAssembler.class);
	private final BrandReadRepository brandReadRepository = mock(BrandReadRepository.class);
	private final BrandIndexCache cache =
			new BrandIndexCache(dashboardVersion, assembler, brandReadRepository);

	private static final BrandAccountRow ACCOUNT = new BrandAccountRow(100L, "brand", null,
			OffsetDateTime.parse("2026-08-07T18:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
			null, null, 1000L, 10L, 100L, null, "브랜드", null, true, null, "ACTIVE", null,
			12, null, false, null);

	private static BrandPostAssembler.BrandPostIndex index(String code) {
		return new BrandPostAssembler.BrandPostIndex(List.of(), Set.of(code), Map.of(), Set.of());
	}

	@BeforeEach
	void stubAssembler() {
		given(assembler.indexForBrand(anyLong(), eq(ACCOUNT), anyBoolean()))
				.willReturn(index("A"), index("B"));
	}

	@Test
	void 같은_버전키면_인덱스를_재조립하지_않는다() {
		var first = cache.index("v1", 7L, ACCOUNT, false);
		var second = cache.index("v1", 7L, ACCOUNT, false);

		assertThat(second).isSameAs(first);
		then(assembler).should(times(1)).indexForBrand(7L, ACCOUNT, false);
	}

	@Test
	void 버전키가_바뀌면_재조립한다() {
		var first = cache.index("v1", 7L, ACCOUNT, false);
		var second = cache.index("v2", 7L, ACCOUNT, false);

		assertThat(second).isNotSameAs(first);
		then(assembler).should(times(2)).indexForBrand(7L, ACCOUNT, false);
	}

	@Test
	void withViews_유무는_별도_엔트리다() {
		// performance 정렬용 인덱스(최신 스냅샷 포함)와 기본 인덱스는 조립 결과가 다르다 —
		// 한 엔트리로 접으면 views 없는 인덱스가 performance 정렬에 재사용된다.
		cache.index("v1", 7L, ACCOUNT, false);
		cache.index("v1", 7L, ACCOUNT, true);

		then(assembler).should(times(1)).indexForBrand(7L, ACCOUNT, false);
		then(assembler).should(times(1)).indexForBrand(7L, ACCOUNT, true);
	}

	@Test
	void 유저가_다르면_별도_엔트리다() {
		// 인덱스는 유저 관점 파생값(ownedShortCodes 노출 필터·source)을 담는다 — 유저 간 공유 금지.
		cache.index("v1", 7L, ACCOUNT, false);
		cache.index("v1", 8L, ACCOUNT, false);

		then(assembler).should(times(1)).indexForBrand(7L, ACCOUNT, false);
		then(assembler).should(times(1)).indexForBrand(8L, ACCOUNT, false);
	}

	@Test
	void 최신_스냅샷_프로젝션도_같은_버전키로_캐시한다() {
		given(brandReadRepository.findLatestSnapshotsForBrand(eq(100L), org.mockito.ArgumentMatchers.any(),
				eq(true))).willReturn(List.of());

		var first = cache.latestSnapshots("v1", 100L, true);
		var second = cache.latestSnapshots("v1", 100L, true);
		cache.latestSnapshots("v2", 100L, true);

		assertThat(second).isSameAs(first);
		then(brandReadRepository).should(times(2))
				.findLatestSnapshotsForBrand(eq(100L), org.mockito.ArgumentMatchers.any(), eq(true));
	}

	@Test
	void version은_DashboardVersion_계산을_그대로_쓴다() {
		given(dashboardVersion.compute(7L)).willReturn("dv-7");

		assertThat(cache.version(7L)).isEqualTo("dv-7");
	}
}
