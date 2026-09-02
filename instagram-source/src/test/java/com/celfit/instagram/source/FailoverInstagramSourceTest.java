package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FailoverInstagramSourceTest {

	/** path→body 픽스처가 필요 없는 최소 백엔드: HikerBackend에 fake HikerHttp를 주입해 위임을 관찰. */
	private static HikerBackend hikerBackendReturning(String profileBody) {
		return new HikerBackend(path -> profileBody);
	}

	@Test
	void fetchProfile는_hiker_백엔드로_위임한다() {
		String body = "{\"user\":{\"pk\":123,\"follower_count\":10,\"following_count\":5,"
				+ "\"media_count\":2,\"full_name\":\"n\",\"is_private\":false}}";
		FailoverInstagramSource source = new FailoverInstagramSource(hikerBackendReturning(body));
		ProfileInfo p = source.fetchProfile("acct");
		assertThat(p.userId()).isEqualTo("123");
		assertThat(p.followers()).isEqualTo(10L);
	}

	@Test
	void fetchComments_3arg는_4arg로_위임되지_않고_백엔드_계약을_그대로_노출한다() {
		// 위임 존재만 확인(빈 knownIds) — 실제 파싱은 HikerBackendTest가 검증.
		FailoverInstagramSource source = new FailoverInstagramSource(
				hikerBackendReturning("{\"response\":{\"comments\":[]}}"));
		CommentsFetch fetch = source.fetchComments("ABC", "owner", 1);
		assertThat(fetch.comments()).isEqualTo(List.of());
		assertThat(fetch.complete()).isTrue();
	}
}
