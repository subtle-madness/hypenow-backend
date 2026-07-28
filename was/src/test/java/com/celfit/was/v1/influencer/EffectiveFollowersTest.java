package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.influencer.EffectiveFollowers.Post;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 유효 팔로워 실반응 산식(07-28 확정) 계약 — 산식 정의는 EffectiveFollowers javadoc. */
class EffectiveFollowersTest {

	@Test
	void 기본_케이스는_평균_인정_반응_비율() {
		// followers 10000, 2게시물: (100+10)=110, (200+20)=220 — 앵커(39×(댓글+1)) 미달이라 그대로.
		// r = (110+220)/2/10000 = 0.0165, 지수 = max(1, 2×0.25) = 1 → 10000×0.0165 = 165
		Long result = EffectiveFollowers.estimate(10_000L,
				List.of(new Post(null, 100L, 10L), new Post(null, 200L, 20L)));
		assertThat(result).isEqualTo(165L);
	}

	@Test
	void 조회수가_팔로워를_넘으면_안분() {
		// views 40000 > followers 10000 → engaged 400×(10000/40000) = 100, 앵커 39×11=429 미달
		// r = 100/10000 = 0.01, 지수 1 → 100
		Long result = EffectiveFollowers.estimate(10_000L,
				List.of(new Post(40_000L, 390L, 10L)));
		assertThat(result).isEqualTo(100L);
	}

	@Test
	void 비정상_좋아요는_댓글_앵커로_컷() {
		// 좋아요 10000·댓글 1 → engaged 10001, 앵커 39×2=78 → 78 채택. r=78/10000, 지수 1 → 78
		Long result = EffectiveFollowers.estimate(10_000L,
				List.of(new Post(null, 10_000L, 1L)));
		assertThat(result).isEqualTo(78L);
	}

	@Test
	void 게시물_12개면_지수_3으로_확장() {
		// 12게시물 전부 r_post=0.01 → r=0.01, 지수 = 12×0.25 = 3 → 1-(1-0.01)^3 = 0.029701 → 297
		List<Post> posts = java.util.Collections.nCopies(12, new Post(null, 90L, 10L));
		assertThat(EffectiveFollowers.estimate(10_000L, posts)).isEqualTo(297L);
	}

	@Test
	void 근거_없으면_null() {
		assertThat(EffectiveFollowers.estimate(null, List.of(new Post(null, 1L, 1L)))).isNull();
		assertThat(EffectiveFollowers.estimate(0L, List.of(new Post(null, 1L, 1L)))).isNull();
		assertThat(EffectiveFollowers.estimate(10_000L, List.of())).isNull();
	}

	@Test
	void 음수_센티널은_0으로_클램프() {
		// likes -1(비공개 센티널) → 0, 댓글 10 → engaged 10, 앵커 429 미달. r=10/10000 → 10
		Long result = EffectiveFollowers.estimate(10_000L, List.of(new Post(null, -1L, 10L)));
		assertThat(result).isEqualTo(10L);
	}
}
