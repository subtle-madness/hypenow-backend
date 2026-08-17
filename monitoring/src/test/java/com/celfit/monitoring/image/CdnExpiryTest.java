package com.celfit.monitoring.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.CdnUrls;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * CDN 서명 만료 판정 계약 — 컨테이너 없는 순수 단위 테스트.
 * 핵심은 <b>"만료 미상은 만료로 취급하지 않는다"</b>는 비대칭이다: 오탐은 살아있는 URL을 영구히
 * 버리는 반면, 미탐은 그 건이 한 번 실패할 뿐이다.
 */
class CdnExpiryTest {

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
		// 16자리 이상 — Long 오버플로 방지 상한에 걸려 미상 처리(잘린 값을 쓰면 오탐이 된다)
		assertThat(CdnExpiry.expiryEpoch("https://cdn.example/x_n.jpg?oe=FFFFFFFFFFFFFFFF")).isNull();
		// oe라는 이름의 다른 파라미터에 낚이지 않는다
		assertThat(CdnExpiry.expiryEpoch("https://cdn.example/x_n.jpg?video_oe=6A81E165")).isNull();
	}

	@Test
	void 만료_판정은_미상을_만료로_취급하지_않는다() {
		long now = 1_000_000L;
		assertThat(CdnExpiry.isExpired(CdnUrls.oe("dead_n.jpg", now - 1), now)).isTrue();
		assertThat(CdnExpiry.isExpired(CdnUrls.oe("edge_n.jpg", now), now)).isTrue();   // 경계 = 만료
		assertThat(CdnExpiry.isExpired(CdnUrls.oe("live_n.jpg", now + 1), now)).isFalse();
		assertThat(CdnExpiry.isExpired(CdnUrls.noOe("unknown_n.jpg"), now)).isFalse();
	}

	@Test
	void 만료_임박_순으로_정렬하고_미상은_뒤로_보낸다() {
		String far = CdnUrls.oe("far_n.jpg", 3_000);
		String soon = CdnUrls.oe("soon_n.jpg", 1_000);
		String mid = CdnUrls.oe("mid_n.jpg", 2_000);
		String unknown = CdnUrls.noOe("unknown_n.jpg");

		assertThat(CdnExpiry.soonestExpiryFirst(List.of(far, unknown, soon, mid), url -> url))
				.containsExactly(soon, mid, far, unknown);
	}

	/** 정렬은 후보를 걸러내지 않는다 — 같은 URL이 여러 건이어도 건수가 보존돼야 한다. */
	@Test
	void 중복_URL_후보도_전부_유지된다() {
		String dup = CdnUrls.oe("dup_n.jpg", 1_000);

		assertThat(CdnExpiry.soonestExpiryFirst(List.of(dup, dup, dup), url -> url)).hasSize(3);
	}
}
