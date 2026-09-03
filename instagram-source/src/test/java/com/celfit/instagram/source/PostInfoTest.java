package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 사본 생성자 3종(withFbPlays·mergedMetrics·mergedWith)이 브랜드 was 계약 표시 메타
 * (videoUrl·videoDuration·isPaidPartnership — 2026-08-07 스펙 §3-2)를 관통시키는지.
 *
 * <p>파싱 테스트(HikerBackendTest)만으로는 이 경로가 안 덮인다: 스윕은 파싱 직후 재시도·머지
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
				true, false, false);
	}

	/**
	 * self 셰이프 보강 전용(2026-09-03) — DB taken_at으로 채운 사본이 나머지 필드(표시 메타 포함)를
	 * 그대로 보존하는지. BrandDirectCollectService.collectOne이 fetch 결과의 takenAt이 null일 때
	 * 이 사본을 저장 경로에 흘려보낸다.
	 */
	@Test
	void withTakenAt은_나머지_필드를_보존한다() {
		PostInfo copy = reel(VIDEO_URL, 12.119, true).withTakenAt(1_650_000_000L);

		assertThat(copy.takenAt()).isEqualTo(1_650_000_000L);
		assertThat(copy.videoUrl()).isEqualTo(VIDEO_URL);
		assertThat(copy.videoDuration()).isEqualTo(12.119);
		assertThat(copy.isPaidPartnership()).isTrue();
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

	private static PostInfo withContentType(String contentType) {
		return new PostInfo("ReelA", "acct", null, null, "999", contentType, "캡션", "https://cdn/thumb.jpg",
				1_700_000_000L, 10L, 2L, 222L, null, null, null, null,
				null, null, null, true, false, false);
	}

	/**
	 * S4 — self(embed/feed-user)가 콘텐츠 타입을 확정하지 못하면(구조적 신호 부재) contentType이
	 * null로 온다(EmbedPostFetcher·FeedUserPostsFetcher 참조). 과거 mergedWith는 정본의 contentType을
	 * 무조건 채택해 정본이 null이면 폴백(Hiker)이 이미 확정한 값까지 버렸다 — 다른 표시 메타 필드
	 * (캡션·썸네일·videoUrl 등)와 동일한 non-null 우선 규칙으로 통일한다.
	 */
	@Test
	void mergedWith는_정본에_없는_콘텐츠_타입을_폴백에서_가져온다() {
		PostInfo primary = withContentType(null);
		PostInfo fallback = withContentType("REELS");

		PostInfo merged = primary.mergedWith(fallback);

		assertThat(merged.contentType()).isEqualTo("REELS");
	}

	/** 정본이 이미 확정 콘텐츠 타입(REELS/FEED)을 가지면 폴백과 무관하게 정본이 이긴다(non-null 우선). */
	@Test
	void mergedWith는_정본의_확정된_콘텐츠_타입을_보존한다() {
		PostInfo primary = withContentType("FEED");
		PostInfo fallback = withContentType("REELS");

		PostInfo merged = primary.mergedWith(fallback);

		assertThat(merged.contentType()).isEqualTo("FEED");
	}

	// ── S9(2026-09-03 감사 수정) — likesHidden·sharesHidden 3상태 병합 ─────────

	private static PostInfo withHidden(Long likes, Boolean likesHidden, Boolean sharesHidden) {
		return new PostInfo("ReelA", "acct", null, null, "999", "REELS", "캡션", null,
				1_700_000_000L, likes, 2L, 222L, null, null, null, null, null, null, null,
				true, likesHidden, sharesHidden);
	}

	/**
	 * 과거 버그(self 단독 채택) — 정본(self)이 likesHidden 미확정(null)이고 폴백(Hiker)이 이미
	 * 확정 true를 들고 있으면, 정본 값만 채택하는 옛 규칙은 폴백의 확정을 버렸다. sharesHidden과
	 * 같은 병합 규칙(둘 중 하나라도 확정 true면 true)을 적용해야 한다.
	 */
	@Test
	void mergedWith는_정본이_미확정이고_폴백이_확정_숨김이면_숨김을_채택한다() {
		PostInfo primary = withHidden(83L, null, null);      // self — 미확정
		PostInfo fallback = withHidden(null, true, null);    // Hiker — 확정 숨김(likes도 마스킹 null)

		PostInfo merged = primary.mergedWith(fallback);

		assertThat(merged.likesHidden()).isTrue();
		assertThat(merged.likes()).isNull();   // 합친 판정이 숨김이므로 likes는 마스킹 취급
	}

	/** 둘 다 미확정(self만 관측)이면 병합 결과도 미확정으로 남는다 — 근거 없이 false로 단정하지 않는다. */
	@Test
	void mergedWith는_양쪽_다_미확정이면_미확정을_유지한다() {
		PostInfo primary = withHidden(83L, null, null);
		PostInfo fallback = withHidden(83L, null, null);

		PostInfo merged = primary.mergedWith(fallback);

		assertThat(merged.likesHidden()).isNull();
		assertThat(merged.sharesHidden()).isNull();
		assertThat(merged.likes()).isEqualTo(83L);   // 미확정이므로 마스킹하지 않는다
	}

	/** mergedMetrics(3-arg)는 새 숨김 정보가 없다는 뜻 — 기존 sharesHidden(미확정 포함)을 그대로 보존한다. */
	@Test
	void mergedMetrics_3항은_기존_공유_숨김_상태를_보존한다() {
		PostInfo unconfirmed = withHidden(83L, null, null).mergedMetrics(5L, null, 7L);

		assertThat(unconfirmed.sharesHidden()).isNull();
		assertThat(unconfirmed.saves()).isEqualTo(5L);
	}

	/**
	 * S9 핵심 — 단건 재시도(항상 Hiker 직결)가 새로 관측한 공유 숨김 확정을 mergedMetrics가
	 * 되싣어야 한다. 과거엔 이 정보가 버려져 self 기원 미확정 게시물이 매일 재시도 상한까지
	 * 헛돌고 소진 시 공유가 0으로 잘못 기록됐다.
	 */
	@Test
	void mergedMetrics_4항은_새로_관측된_공유_숨김_확정을_되싣는다() {
		PostInfo merged = withHidden(83L, null, null).mergedMetrics(5L, null, 7L, true);

		assertThat(merged.sharesHidden()).isTrue();
		assertThat(merged.saves()).isEqualTo(5L);
		assertThat(merged.reposts()).isEqualTo(7L);
	}

	/** 새로 관측된 값이 확정 false여도 이미 확정 true였다면 true가 이긴다(숨김은 관측되면 참). */
	@Test
	void mergedMetrics_4항은_이미_확정된_숨김을_비숨김_관측으로_덮지_않는다() {
		PostInfo merged = withHidden(83L, null, true).mergedMetrics(5L, null, 7L, false);

		assertThat(merged.sharesHidden()).isTrue();
	}
}
