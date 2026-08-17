package com.celfit.monitoring.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.CdnUrls;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * CDN 서명 만료 판정 계약 — 컨테이너 없는 순수 단위 테스트.
 * 핵심은 <b>"만료 미상은 만료로 취급하지 않는다"</b>는 비대칭이다: 오탐은 살아있는 URL을 영구히
 * 버리는 반면, 미탐은 그 건이 한 번 실패할 뿐이다. 미상에는 파싱 불가뿐 아니라
 * <b>개연성 하한(2015년) 미만으로 파싱되는 쓰레기 값</b>도 포함된다(oe=abc → 1970년 전례).
 */
class CdnExpiryTest {

	// 하한(2015)보다 넉넉히 큰 기준 시각 — 2027-01-15 근방.
	private static final long NOW = 1_800_000_000L;

	@Test
	void oe를_hex_unix초로_읽는다() {
		// 운영 로그에서 실측한 형태(oe가 마지막 파라미터)
		assertThat(CdnExpiry.expiryEpoch("https://cdn.example/x_n.jpg?_nc_ht=a&oe=6A81E165"))
				.isEqualTo(0x6A81E165L);
		// 중간에 끼인 형태
		assertThat(CdnExpiry.expiryEpoch(CdnUrls.oe("x_n.jpg", 1786979685L)))
				.isEqualTo(1786979685L);
	}

	@Test
	void oe가_없거나_파싱_불가면_만료_미상이다() {
		assertThat(CdnExpiry.expiryEpoch(CdnUrls.noOe("x_n.jpg"))).isNull();
		assertThat(CdnExpiry.expiryEpoch("https://cdn.example/x_n.jpg")).isNull();
		assertThat(CdnExpiry.expiryEpoch(null)).isNull();
		// 16자리 이상 — Long 오버플로 방지 상한에 걸려 미상 처리(잘린 값을 쓰면 오탐이 된다)
		assertThat(CdnExpiry.expiryEpoch("https://cdn.example/x_n.jpg?oe=FFFFFFFFFFFFFFFF")).isNull();
		// oe라는 이름의 다른 파라미터에 낚이지 않는다
		assertThat(CdnExpiry.expiryEpoch("https://cdn.example/x_n.jpg?video_oe=6A81E165")).isNull();
	}

	/** oe=abc(1970년) 전례 — 파싱은 되지만 서명일 수 없는 값은 "확정 만료"가 아니라 미상이다. */
	@Test
	void 개연성_하한_미만으로_파싱되는_oe는_만료_미상이다() {
		assertThat(CdnExpiry.expiryEpoch("https://cdn.example/x_n.jpg?oe=abc")).isNull();   // 1970년
		assertThat(CdnExpiry.expiryEpoch(
				CdnUrls.oe("x_n.jpg", CdnExpiry.MIN_PLAUSIBLE_EPOCH - 1))).isNull();        // 하한 직전
		assertThat(CdnExpiry.expiryEpoch(
				CdnUrls.oe("x_n.jpg", CdnExpiry.MIN_PLAUSIBLE_EPOCH)))
				.isEqualTo(CdnExpiry.MIN_PLAUSIBLE_EPOCH);                                  // 하한 = 유효
	}

	@Test
	void 만료_판정은_미상을_만료로_취급하지_않는다() {
		assertThat(rank(CdnUrls.oe("dead_n.jpg", NOW - 1)).expired(NOW)).isTrue();
		assertThat(rank(CdnUrls.oe("edge_n.jpg", NOW)).expired(NOW)).isTrue();      // 경계 = 만료
		assertThat(rank(CdnUrls.oe("live_n.jpg", NOW + 1)).expired(NOW)).isFalse();
		assertThat(rank(CdnUrls.noOe("unknown_n.jpg")).expired(NOW)).isFalse();
	}

	@Test
	void 만료_임박_순으로_정렬하고_미상은_뒤로_보낸다() {
		String far = CdnUrls.oe("far_n.jpg", NOW + 3_000);
		String soon = CdnUrls.oe("soon_n.jpg", NOW + 1_000);
		String mid = CdnUrls.oe("mid_n.jpg", NOW + 2_000);
		String unknown = CdnUrls.noOe("unknown_n.jpg");

		assertThat(CdnExpiry.soonestExpiryFirst(List.of(far, unknown, soon, mid), url -> url))
				.extracting(CdnExpiry.Ranked::item)
				.containsExactly(soon, mid, far, unknown);
	}

	/** 정렬이 실은 만료 시각을 판정도 그대로 읽는다 — 계산 지점이 하나임을 고정하는 계약. */
	@Test
	void 정렬_결과의_Ranked가_만료_시각을_실어_나른다() {
		List<CdnExpiry.Ranked<String>> ranked = CdnExpiry.soonestExpiryFirst(
				List.of(CdnUrls.oe("dead_n.jpg", NOW - 100), CdnUrls.noOe("unknown_n.jpg")),
				url -> url);

		assertThat(ranked.get(0).oe()).isEqualTo(NOW - 100);
		assertThat(ranked.get(0).expired(NOW)).isTrue();
		assertThat(ranked.get(1).oe()).isNull();
		assertThat(ranked.get(1).expired(NOW)).isFalse();
	}

	private static CdnExpiry.Ranked<String> rank(String url) {
		return new CdnExpiry.Ranked<>(url, CdnExpiry.expiryEpoch(url));
	}
}
