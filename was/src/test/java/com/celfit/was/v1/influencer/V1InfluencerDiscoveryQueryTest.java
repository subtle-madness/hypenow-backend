package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.v1.common.V1ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class V1InfluencerDiscoveryQueryTest {

	private static V1InfluencerDiscoveryQuery of(String q, String main, String mid, String sub,
			String follower, String activity, String sponsored, String contact, String sort,
			Integer limit, Integer offset) {
		return V1InfluencerDiscoveryQuery.of(q, main, mid, sub, follower, activity, sponsored,
				contact, sort, limit, offset, null);
	}

	@Test
	void 기본값은_필터없음_reach_100건_0offset() {
		var query = of(null, null, null, null, null, null, null, null, null, null, null);
		assertThat(query.keywords()).isEmpty();
		assertThat(query.mainCategory()).isNull();
		assertThat(query.activityDays()).isNull();
		assertThat(query.contactOpen()).isFalse();
		assertThat(query.sort()).isEqualTo("reach");
		assertThat(query.limit()).isEqualTo(100);
		assertThat(query.offset()).isZero();
	}

	@Test
	void q는_쉼표분리_트림_빈조각_제거() {
		var query = of(" 수분크림 , 올리브영 ,, ", "all", null, null, null, null, null, null,
				null, null, null);
		assertThat(query.keywords()).isEqualTo(List.of("수분크림", "올리브영"));
	}

	@Test
	void main이_all이면_mid는_무시된다() {
		var query = of(null, "all", "립메이크업", null, null, null, null, null, null, null, null);
		assertThat(query.midCategory()).isNull();
	}

	@Test
	void 상위없는_subCategory는_400이다() {
		// 2026-09-01 FE 피드백 #4 — 조용한 무시 대신 400. mid=all·main=all로 상위가 지워진 경우 포함.
		assertThatThrownBy(() -> of(null, "all", "립메이크업", "립틴트", null, null, null, null, null,
				null, null)).isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, "makeup", "all", "립틴트", null, null, null, null, null,
				null, null)).isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, null, null, "립틴트", null, null, null, null, null,
				null, null)).isInstanceOf(V1ApiException.class);
	}

	@Test
	void vertical은_축_전체_조회_파라미터다() {
		// 2026-09-01 FE 피드백 #1 — 대분류 없이 축 전체를 하나의 쿼리로.
		var fnb = V1InfluencerDiscoveryQuery.of(null, null, null, null, null, null, null, null,
				null, null, null, "fnb");
		assertThat(fnb.vertical()).isEqualTo("fnb");
		var none = V1InfluencerDiscoveryQuery.of(null, null, null, null, null, null, null, null,
				null, null, null, null);
		assertThat(fnb.cacheKey()).isNotEqualTo(none.cacheKey());
		// vertical=all은 생략과 동치
		assertThat(V1InfluencerDiscoveryQuery.of(null, null, null, null, null, null, null, null,
				null, null, null, "all").vertical()).isNull();
		assertThatThrownBy(() -> V1InfluencerDiscoveryQuery.of(null, null, null, null, null, null,
				null, null, null, null, null, "home")).isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> V1InfluencerDiscoveryQuery.of(null, "snack", null, null, null,
				null, null, null, null, null, null, "fnb")).isInstanceOf(V1ApiException.class);
	}

	@Test
	void activity는_일수로_변환된다() {
		var query = of(null, null, null, null, null, "14d", null, null, null, null, null);
		assertThat(query.activityDays()).isEqualTo(14);
	}

	@Test
	void contact_open은_불리언으로() {
		assertThat(of(null, null, null, null, null, null, null, "open", null, null, null)
				.contactOpen()).isTrue();
		assertThat(of(null, null, null, null, null, null, null, "all", null, null, null)
				.contactOpen()).isFalse();
	}

	@Test
	void 같은_조건은_같은_캐시_키_페이지가_다르면_다른_키() {
		V1InfluencerDiscoveryQuery a = V1InfluencerDiscoveryQuery.of(null, null, null, null, null,
				null, null, null, null, 50, 0, null);
		V1InfluencerDiscoveryQuery b = V1InfluencerDiscoveryQuery.of(null, null, null, null, null,
				null, null, null, null, 50, 0, null);
		assertThat(a.cacheKey()).isEqualTo(b.cacheKey());
		assertThat(a.cacheKey()).isNotEqualTo(a.next().cacheKey());
	}

	@Test
	void next는_offset만_limit만큼_전진() {
		V1InfluencerDiscoveryQuery next = V1InfluencerDiscoveryQuery.of(null, null, null, null, null,
				null, null, null, null, 50, 100, null).next();
		assertThat(next.offset()).isEqualTo(150);
		assertThat(next.limit()).isEqualTo(50);
	}

	@Test
	void 자유입력_리터럴_null은_필터_생략과_다른_키() {
		// midCategory="null"(자유입력 리터럴)과 midCategory 생략(실제 null)은 다른 조건이어야 한다.
		V1InfluencerDiscoveryQuery literalNull = V1InfluencerDiscoveryQuery.of(null, "makeup", "null",
				null, null, null, null, null, null, 50, 0, null);
		V1InfluencerDiscoveryQuery omitted = V1InfluencerDiscoveryQuery.of(null, "makeup", null, null,
				null, null, null, null, null, 50, 0, null);
		assertThat(literalNull.cacheKey()).isNotEqualTo(omitted.cacheKey());
	}

	@Test
	void 기본값_명시와_all은_생략과_같은_키() {
		// main=all이면 mid·sub는 연쇄 무시(스펙 6.21) — 명시한 값과 상관없이 생략과 동일 조건·동일 키.
		V1InfluencerDiscoveryQuery explicit = V1InfluencerDiscoveryQuery.of(null, "all", "립메이크업",
				"all", "all", null, "all", null, "reach", 100, 0, "all");
		V1InfluencerDiscoveryQuery omitted = V1InfluencerDiscoveryQuery.of(null, null, null, null,
				null, null, null, null, null, null, null, null);
		assertThat(explicit.cacheKey()).isEqualTo(omitted.cacheKey());
	}

	@Test
	void 잘못된_enum과_음수_경계는_400() {
		assertThatThrownBy(() -> of(null, "nail", null, null, null, null, null, null, null, null, null))
				.isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, null, null, null, "1m-2m", null, null, null, null, null, null))
				.isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, null, null, null, null, "90d", null, null, null, null, null))
				.isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, null, null, null, null, null, "7plus", null, null, null, null))
				.isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, null, null, null, null, null, null, "closed", null, null, null))
				.isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, null, null, null, null, null, null, null, "zzz", null, null))
				.isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, null, null, null, null, null, null, null, null, 101, null))
				.isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, null, null, null, null, null, null, null, null, 0, null))
				.isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> of(null, null, null, null, null, null, null, null, null, null, -1))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void 에스테틱_대분류는_검증을_통과한다() {
		// V20260809063533 시드로 추가된 어휘 — allowlist 누락 시 400 회귀 방지
		V1InfluencerDiscoveryQuery query = V1InfluencerDiscoveryQuery.of(null, "esthetic", null,
				null, null, null, null, null, null, null, null, null);
		assertThat(query.mainCategory()).isEqualTo("esthetic");
	}

	@Test
	void sort_hype는_허용된다() {
		assertThat(of(null, null, null, null, null, null, null, null, "hype", null, null)
				.sort()).isEqualTo("hype");
	}

	@org.junit.jupiter.api.Test
	void FnB_대분류_6종은_검증을_통과하고_축이_판정된다() {
		// 2026-08-31 F&B 서빙 개방 — 발굴은 축에 따라 게이트가 갈리므로(무필터·뷰티=뷰티 계정,
		// F&B=F&B 계정 + 뷰티비율 게이트 스킵) 쿼리 홀더가 축 판정을 함께 제공한다.
		for (String main : java.util.List.of("beverage", "alcohol", "convenience", "snack",
				"health-food", "recipe")) {
			V1InfluencerDiscoveryQuery query = V1InfluencerDiscoveryQuery.of(null, main, null, null,
					null, null, null, null, null, null, null, null);
			org.assertj.core.api.Assertions.assertThat(query.mainCategory()).isEqualTo(main);
			org.assertj.core.api.Assertions.assertThat(
					com.celfit.was.v1.common.MainCategories.isFnb(main)).isTrue();
		}
		org.assertj.core.api.Assertions.assertThat(
				com.celfit.was.v1.common.MainCategories.isFnb("skincare")).isFalse();
		org.assertj.core.api.Assertions.assertThat(
				com.celfit.was.v1.common.MainCategories.isFnb(null)).isFalse();
	}
}
