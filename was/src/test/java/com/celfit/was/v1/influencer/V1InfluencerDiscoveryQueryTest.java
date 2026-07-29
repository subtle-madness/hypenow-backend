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
				contact, sort, limit, offset);
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
	void main이_all이면_mid_sub는_무시된다() {
		var query = of(null, "all", "립메이크업", "립틴트", null, null, null, null, null, null, null);
		assertThat(query.midCategory()).isNull();
		assertThat(query.subCategory()).isNull();

		var query2 = of(null, "makeup", "all", "립틴트", null, null, null, null, null, null, null);
		assertThat(query2.subCategory()).isNull();
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
	void sort_hype는_허용된다() {
		assertThat(of(null, null, null, null, null, null, null, null, "hype", null, null)
				.sort()).isEqualTo("hype");
	}
}
