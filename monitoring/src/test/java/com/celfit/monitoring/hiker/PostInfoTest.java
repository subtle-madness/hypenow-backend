package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 사본 생성자 3종(withFbPlays·mergedMetrics·mergedWith)이 브랜드 was 계약 표시 메타
 * (videoUrl·videoDuration·isPaidPartnership — 2026-08-07 스펙 §3-2)를 관통시키는지.
 *
 * <p>파싱 테스트(HikerClientTest)만으로는 이 경로가 안 덮인다: 스윕은 파싱 직후 재시도·머지
 * 사본을 만들어 저장하므로, 사본 생성자에서 필드가 하나 빠지면 파싱은 멀쩡한데 저장값만
 * 조용히 null이 된다(레코드 필드가 23개라 자리 밀림·누락이 눈으로 안 잡힌다).
 */
class PostInfoTest {

	private static final String VIDEO_URL = "https://cdn.example/reel.mp4";

	/** 표시 메타를 실값으로 채운 릴스 — 사본을 떠도 이 3값이 살아남아야 한다. */
	private static PostInfo reel(String videoUrl, Double videoDuration, Boolean paid) {
		return new PostInfo("ReelA", "acct", null, null, "999", "REELS", "캡션", "https://cdn/thumb.jpg",
				1_700_000_000L, 10L, 2L, 222L, null, null, null, null,
				videoUrl, videoDuration, paid,
				"{}", true, false, false);
	}

	@Test
	void withFbPlays는_표시_메타를_보존한다() {
		PostInfo copy = reel(VIDEO_URL, 12.119, false).withFbPlays(83L);

		assertThat(copy.fbPlays()).isEqualTo(83L);
		assertThat(copy.videoUrl()).isEqualTo(VIDEO_URL);
		assertThat(copy.videoDuration()).isEqualTo(12.119);
		assertThat(copy.isPaidPartnership()).isFalse();
	}

	@Test
	void mergedMetrics는_표시_메타를_보존한다() {
		PostInfo copy = reel(VIDEO_URL, 12.119, true).mergedMetrics(10L, 20L, 30L);

		assertThat(copy.saves()).isEqualTo(10L);
		assertThat(copy.videoUrl()).isEqualTo(VIDEO_URL);
		assertThat(copy.videoDuration()).isEqualTo(12.119);
		assertThat(copy.isPaidPartnership()).isTrue();
	}

	@Test
	void mergedWith는_정본의_표시_메타를_보존한다() {
		PostInfo primary = reel(VIDEO_URL, 12.119, true);
		PostInfo fallback = reel("https://cdn.example/other.mp4", 99.9, false);

		PostInfo merged = primary.mergedWith(fallback);

		assertThat(merged.videoUrl()).isEqualTo(VIDEO_URL);   // 정본이 있으면 정본이 이긴다
		assertThat(merged.videoDuration()).isEqualTo(12.119);
		assertThat(merged.isPaidPartnership()).isTrue();
	}

	/**
	 * 방향성 — 정본에 없고 폴백에만 있으면 폴백을 채택한다(캡션·썸네일과 동일 취급).
	 * 표시 메타는 응답 경로에 따라 한쪽에만 실려서, 정본이 폴백을 null로 덮으면
	 * 같은 스윕에서 방금 관측한 값을 유실한다.
	 */
	@Test
	void mergedWith는_정본에_없는_표시_메타를_폴백에서_가져온다() {
		PostInfo primary = reel(null, null, null);
		PostInfo fallback = reel(VIDEO_URL, 12.119, true);

		PostInfo merged = primary.mergedWith(fallback);

		assertThat(merged.videoUrl()).isEqualTo(VIDEO_URL);
		assertThat(merged.videoDuration()).isEqualTo(12.119);
		// 유료협찬도 coalesce — 관측된 판정이 unknown(null)보다 낫다
		assertThat(merged.isPaidPartnership()).isTrue();
	}
}
