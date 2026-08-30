package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.brandmonitoring.BrandInfluencerAggregator.InfluencerPost;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 인플루언서 집계 순수 함수 단위 테스트 — celfit-front {@code src/lib/monitoring/brand-influencers.ts}
 * 대조. 각 테스트에 대응 FE 함수명을 주석으로 남겨 이식 근거를 고정한다.
 */
class BrandInfluencerAggregatorTest {

	// ---------- dedupeByShortcode (FE mergeBrandPosts) ----------

	@Test
	void 같은_shortcode는_먼저_온_것만_남는다() {
		// FE mergeBrandPosts — 두 계정을 태그한 한 게시물이 양쪽 목록에 오면 성과가 두 배로 접힌다
		List<InfluencerPost> deduped = BrandInfluencerAggregator.dedupeByShortcode(List.of(
				post("AAA", "ella", "2026-08-20T10:00:00", 100L),
				post("BBB", "ella", "2026-08-21T10:00:00", 200L),
				post("AAA", "ella", "2026-08-20T10:00:00", 999L)));

		assertThat(deduped).hasSize(2);
		assertThat(deduped.get(0).shortcode()).isEqualTo("AAA");
		assertThat(deduped.get(0).likes()).isEqualTo(100L); // 먼저 온 것 유지(뒤 값 999로 덮이지 않음)
		assertThat(deduped.get(1).shortcode()).isEqualTo("BBB");
	}

	// ---------- summarize (FE summarizeBrandInfluencers) ----------

	@Test
	void 작성자별_누적_규칙() {
		// FE summarizeBrandInfluencers — likesKnown = !likesHidden && likes !== null
		List<BrandInfluencerResponse> rows = BrandInfluencerAggregator.summarize(List.of(
				new InfluencerPost("A1", "ella", "엘라", "pic1", 1_000L, "2026-08-20T10:00:00", true,
						null, 100L, false, 10L),
				new InfluencerPost("A2", "ella", "엘라", "pic1", 1_000L, "2026-08-21T10:00:00", false,
						500L, 50L, false, 5L),
				// 좋아요 숨김 — likes 값이 있어도 합산에서 빠지고 likesKnownCount도 안 오른다
				new InfluencerPost("A3", "ella", "엘라", "pic1", 1_000L, "2026-08-19T10:00:00", true,
						300L, 777L, true, null)));

		assertThat(rows).hasSize(1);
		BrandInfluencerResponse r = rows.get(0);
		assertThat(r.username()).isEqualTo("ella");
		assertThat(r.postCount()).isEqualTo(3L);
		assertThat(r.likes()).isEqualTo(150L); // 숨김 777 제외
		assertThat(r.likesKnownCount()).isEqualTo(2L);
		assertThat(r.views()).isEqualTo(800L); // 피드 null은 0 기여
		assertThat(r.comments()).isEqualTo(15L); // null은 0 기여
		assertThat(r.sponsoredCount()).isEqualTo(2L);
		assertThat(r.latestPostAt()).isEqualTo("2026-08-21T10:00:00");
		assertThat(r.profileUrl()).isEqualTo("https://www.instagram.com/ella/");
	}

	@Test
	void likes가_null이면_숨김이_아니어도_모름으로_센다() {
		// FE summarizeBrandInfluencers — likesKnown = !likesHidden && post.likes !== null
		List<BrandInfluencerResponse> rows = BrandInfluencerAggregator.summarize(List.of(
				new InfluencerPost("A1", "ella", null, null, null, "2026-08-20T10:00:00", false,
						null, null, false, null)));

		assertThat(rows.get(0).likes()).isZero();
		assertThat(rows.get(0).likesKnownCount()).isZero();
	}

	@Test
	void 프로필_메타는_최신_게시물_값이되_팔로워만_null_가드() {
		// FE summarizeBrandInfluencers — fullName/profilePicUrl은 isNewer면 무조건 교체,
		// followers만 isNewer && 새 값 != null일 때 교체
		List<BrandInfluencerResponse> rows = BrandInfluencerAggregator.summarize(List.of(
				new InfluencerPost("A1", "ella", "옛이름", "old.jpg", 1_000L, "2026-08-19T10:00:00",
						false, null, 10L, false, 1L),
				new InfluencerPost("A2", "ella", null, null, null, "2026-08-25T10:00:00",
						false, null, 20L, false, 2L),
				new InfluencerPost("A3", "ella", "중간이름", "mid.jpg", 2_000L, "2026-08-22T10:00:00",
						false, null, 30L, false, 3L)));

		BrandInfluencerResponse r = rows.get(0);
		assertThat(r.fullName()).isNull(); // 최신(A2) 값이 null이어도 교체 — FE와 동일
		assertThat(r.profilePicUrl()).isNull();
		assertThat(r.followers()).isEqualTo(1_000L); // 최신 값이 null이라 직전 값 유지(A3은 최신이 아님)
		assertThat(r.latestPostAt()).isEqualTo("2026-08-25T10:00:00");
	}

	@Test
	void 팔로워는_최신_게시물_값으로_교체된다() {
		List<BrandInfluencerResponse> rows = BrandInfluencerAggregator.summarize(List.of(
				new InfluencerPost("A1", "ella", "옛이름", "old.jpg", 1_000L, "2026-08-19T10:00:00",
						false, null, 10L, false, 1L),
				new InfluencerPost("A2", "ella", "새이름", "new.jpg", 3_000L, "2026-08-25T10:00:00",
						false, null, 20L, false, 2L)));

		BrandInfluencerResponse r = rows.get(0);
		assertThat(r.followers()).isEqualTo(3_000L);
		assertThat(r.fullName()).isEqualTo("새이름");
		assertThat(r.profilePicUrl()).isEqualTo("new.jpg");
	}

	@Test
	void 작성자_미상_게시물은_집계에서_제외한다() {
		// 서버 한정 방어 — FE는 authorUsername이 항상 문자열이라 이 분기가 없다
		List<BrandInfluencerResponse> rows = BrandInfluencerAggregator.summarize(List.of(
				new InfluencerPost("A1", null, "이름", null, 100L, "2026-08-20T10:00:00", false,
						null, 10L, false, 1L),
				new InfluencerPost("A2", "  ", "이름", null, 100L, "2026-08-20T10:00:00", false,
						null, 10L, false, 1L),
				post("A3", "ella", "2026-08-20T10:00:00", 10L)));

		assertThat(rows).extracting(BrandInfluencerResponse::username).containsExactly("ella");
	}

	@Test
	void summarize_기본_순서는_게시물수_좋아요_조회수_계정명() {
		// FE summarizeBrandInfluencers 말미 sort — postCount desc || likes desc || views desc || username asc
		List<BrandInfluencerResponse> rows = BrandInfluencerAggregator.summarize(List.of(
				post("A1", "bob", "2026-08-20T10:00:00", 10L),
				post("B1", "amy", "2026-08-20T10:00:00", 10L),
				post("C1", "zoe", "2026-08-20T10:00:00", 50L),
				post("C2", "zoe", "2026-08-21T10:00:00", 50L)));

		assertThat(rows).extracting(BrandInfluencerResponse::username)
				.containsExactly("zoe", "amy", "bob");
	}

	// ---------- sort (FE sortBrandInfluencers) ----------

	@Test
	void sort_posts는_게시물수_좋아요_계정명_순() {
		// FE sortBrandInfluencers case "posts" — b.postCount - a.postCount || b.likes - a.likes || tieBreak
		List<BrandInfluencerResponse> sorted = BrandInfluencerAggregator.sort(List.of(
				row("bob", 1_000L, 2, 0, 0, 100, 0, "2026-08-20T10:00:00"),
				row("amy", 1_000L, 2, 0, 0, 300, 0, "2026-08-20T10:00:00"),
				row("cat", 1_000L, 2, 0, 0, 300, 0, "2026-08-20T10:00:00"),
				row("dan", 1_000L, 5, 0, 0, 1, 0, "2026-08-20T10:00:00")), "posts");

		assertThat(sorted).extracting(BrandInfluencerResponse::username)
				.containsExactly("dan", "amy", "cat", "bob");
	}

	@Test
	void sort_likes는_좋아요_모름을_맨_뒤로() {
		// FE sortBrandInfluencers case "likes" — nullsLast(isLikesUnavailable ? null : likes)
		List<BrandInfluencerResponse> sorted = BrandInfluencerAggregator.sort(List.of(
				// likesKnownCount 0 + postCount>0 → 좋아요 모름. likes 값이 커도 맨 뒤
				row("zed", 1_000L, 3, 0, 0, 9_999, 0, "2026-08-20T10:00:00"),
				row("bob", 1_000L, 1, 0, 0, 10, 1, "2026-08-20T10:00:00"),
				row("amy", 1_000L, 1, 0, 0, 500, 1, "2026-08-20T10:00:00"),
				row("aaa", 1_000L, 3, 0, 0, 8_888, 0, "2026-08-20T10:00:00")), "likes");

		assertThat(sorted).extracting(BrandInfluencerResponse::username)
				.containsExactly("amy", "bob", "aaa", "zed"); // 모름 2인은 계정명 오름차순
	}

	@Test
	void sort_engagement는_참여율_내림차순_계산불가는_맨_뒤() {
		// FE sortBrandInfluencers case "engagement" — (likes+comments)/(followers*postCount)*100
		List<BrandInfluencerResponse> sorted = BrandInfluencerAggregator.sort(List.of(
				row("noFollowers", null, 2, 0, 0, 100, 2, "2026-08-20T10:00:00"),
				row("zeroFollowers", 0L, 2, 0, 0, 100, 2, "2026-08-20T10:00:00"),
				row("hidden", 1_000L, 2, 0, 0, 100, 0, "2026-08-20T10:00:00"),
				row("low", 10_000L, 1, 0, 0, 100, 1, "2026-08-20T10:00:00"), // 1.0%
				row("high", 1_000L, 2, 0, 20, 100, 2, "2026-08-20T10:00:00")), "engagement"); // 6.0%

		assertThat(sorted).extracting(BrandInfluencerResponse::username)
				.containsExactly("high", "low", "hidden", "noFollowers", "zeroFollowers");
	}

	@Test
	void 참여율_계산식과_null_규칙() {
		// FE influencerEngagementRate
		assertThat(BrandInfluencerAggregator
				.engagementRate(row("a", 1_000L, 2, 0, 20, 100, 2, "2026-08-20T10:00:00")))
				.isEqualTo(6.0d);
		assertThat(BrandInfluencerAggregator
				.engagementRate(row("a", null, 2, 0, 20, 100, 2, "2026-08-20T10:00:00"))).isNull();
		assertThat(BrandInfluencerAggregator
				.engagementRate(row("a", 0L, 2, 0, 20, 100, 2, "2026-08-20T10:00:00"))).isNull();
		assertThat(BrandInfluencerAggregator
				.engagementRate(row("a", 1_000L, 0, 0, 20, 100, 0, "2026-08-20T10:00:00"))).isNull();
		// likesKnownCount 0 & postCount>0 → 분자가 댓글뿐이라 계산하지 않는다
		assertThat(BrandInfluencerAggregator
				.engagementRate(row("a", 1_000L, 2, 0, 20, 100, 0, "2026-08-20T10:00:00"))).isNull();
	}

	@Test
	void sort_avg_views는_건당_평균_조회수_내림차순() {
		// FE sortBrandInfluencers case "avgViews" — Math.round(views / postCount)
		List<BrandInfluencerResponse> sorted = BrandInfluencerAggregator.sort(List.of(
				row("total", 1_000L, 15, 295_000, 0, 0, 15, "2026-08-20T10:00:00"), // 19,667
				row("perPost", 1_000L, 3, 1_470_000, 0, 0, 3, "2026-08-20T10:00:00")), "avg_views");

		assertThat(sorted).extracting(BrandInfluencerResponse::username)
				.containsExactly("perPost", "total");
		assertThat(BrandInfluencerAggregator
				.averageViews(row("a", 1_000L, 3, 10, 0, 0, 3, "2026-08-20T10:00:00")))
				.isEqualTo(3L); // 3.33 → 3
		assertThat(BrandInfluencerAggregator
				.averageViews(row("a", 1_000L, 2, 5, 0, 0, 2, "2026-08-20T10:00:00")))
				.isEqualTo(3L); // 2.5 → 3(JS Math.round와 같은 half-up)
		assertThat(BrandInfluencerAggregator
				.averageViews(row("a", 1_000L, 0, 10, 0, 0, 0, "2026-08-20T10:00:00"))).isZero();
	}

	@Test
	void sort_views_followers_recent() {
		List<BrandInfluencerResponse> byViews = BrandInfluencerAggregator.sort(List.of(
				row("amy", 1_000L, 1, 10, 0, 0, 1, "2026-08-20T10:00:00"),
				row("bob", 1_000L, 1, 90, 0, 0, 1, "2026-08-20T10:00:00")), "views");
		assertThat(byViews).extracting(BrandInfluencerResponse::username).containsExactly("bob", "amy");

		// FE sortBrandInfluencers case "followers" — nullsLast
		List<BrandInfluencerResponse> byFollowers = BrandInfluencerAggregator.sort(List.of(
				row("unknown", null, 1, 0, 0, 0, 1, "2026-08-20T10:00:00"),
				row("small", 100L, 1, 0, 0, 0, 1, "2026-08-20T10:00:00"),
				row("big", 90_000L, 1, 0, 0, 0, 1, "2026-08-20T10:00:00")), "followers");
		assertThat(byFollowers).extracting(BrandInfluencerResponse::username)
				.containsExactly("big", "small", "unknown");

		// FE sortBrandInfluencers case "recent" — latestPostAtKst 문자열 내림차순(ISO 사전순 = 시간순)
		List<BrandInfluencerResponse> byRecent = BrandInfluencerAggregator.sort(List.of(
				row("old", 1_000L, 1, 0, 0, 0, 1, "2026-07-31T23:59:59"),
				row("new", 1_000L, 1, 0, 0, 0, 1, "2026-08-01T00:00:00")), "recent");
		assertThat(byRecent).extracting(BrandInfluencerResponse::username).containsExactly("new", "old");
	}

	@Test
	void sort_기본값은_posts이고_원본_리스트는_보존된다() {
		List<BrandInfluencerResponse> source = List.of(
				row("amy", 1_000L, 1, 0, 0, 0, 1, "2026-08-20T10:00:00"),
				row("bob", 1_000L, 9, 0, 0, 0, 1, "2026-08-20T10:00:00"));

		assertThat(BrandInfluencerAggregator.sort(source, null))
				.extracting(BrandInfluencerResponse::username).containsExactly("bob", "amy");
		assertThat(source).extracting(BrandInfluencerResponse::username).containsExactly("amy", "bob");
	}

	// ---------- 필터 (FE filterBrandInfluencers / matchesFollowerRange / matchesSponsorship) ----------

	@Test
	void matchesKeyword는_trim_소문자_부분일치() {
		// FE filterBrandInfluencers
		BrandInfluencerResponse ella = new BrandInfluencerResponse("ellabeauty", "엘라 뷰티", null,
				null, 1_000L, 1, 0, 0, 0, 0, 1, "2026-08-20T10:00:00");
		assertThat(BrandInfluencerAggregator.matchesKeyword(ella, "ella")).isTrue();
		assertThat(BrandInfluencerAggregator.matchesKeyword(ella, " ELLA ")).isTrue();
		assertThat(BrandInfluencerAggregator.matchesKeyword(ella, "엘라")).isTrue();
		assertThat(BrandInfluencerAggregator.matchesKeyword(ella, "zed")).isFalse();
		assertThat(BrandInfluencerAggregator.matchesKeyword(ella, "  ")).isTrue(); // 빈 검색어는 필터 없음
		assertThat(BrandInfluencerAggregator.matchesKeyword(ella, null)).isTrue();

		// fullName null은 username만 검사(서버 한정 — FE는 항상 문자열)
		BrandInfluencerResponse noName = new BrandInfluencerResponse("ellabeauty", null, null, null,
				1_000L, 1, 0, 0, 0, 0, 1, "2026-08-20T10:00:00");
		assertThat(BrandInfluencerAggregator.matchesKeyword(noName, "ella")).isTrue();
		assertThat(BrandInfluencerAggregator.matchesKeyword(noName, "엘라")).isFalse();
	}

	@Test
	void matchesFollower는_하한포함_상한미포함이고_미상은_제외() {
		// FE matchesFollowerRange — 팔로워 미상은 어느 구간에도 넣지 않는다
		var band = V1BrandPostsController.parseFollower("3k-10k");
		assertThat(BrandInfluencerAggregator.matchesFollower(withFollowers(3_000L), band)).isTrue();
		assertThat(BrandInfluencerAggregator.matchesFollower(withFollowers(9_999L), band)).isTrue();
		assertThat(BrandInfluencerAggregator.matchesFollower(withFollowers(10_000L), band)).isFalse();
		assertThat(BrandInfluencerAggregator.matchesFollower(withFollowers(2_999L), band)).isFalse();
		assertThat(BrandInfluencerAggregator.matchesFollower(withFollowers(null), band)).isFalse();

		var top = V1BrandPostsController.parseFollower("50k+");
		assertThat(BrandInfluencerAggregator.matchesFollower(withFollowers(1_000_000L), top)).isTrue();
		assertThat(BrandInfluencerAggregator.matchesFollower(withFollowers(49_999L), top)).isFalse();

		// all(=null 밴드)은 필터 없음 — 미상도 통과
		assertThat(BrandInfluencerAggregator.matchesFollower(withFollowers(null), null)).isTrue();
	}

	@Test
	void matchesSponsorship는_이력_유무로_거른다() {
		// FE matchesSponsorship
		BrandInfluencerResponse sponsored = row("a", 1_000L, 3, 0, 0, 0, 3, "2026-08-20T10:00:00", 1);
		BrandInfluencerResponse organic = row("b", 1_000L, 3, 0, 0, 0, 3, "2026-08-20T10:00:00", 0);

		assertThat(BrandInfluencerAggregator.matchesSponsorship(sponsored, "sponsored")).isTrue();
		assertThat(BrandInfluencerAggregator.matchesSponsorship(organic, "sponsored")).isFalse();
		assertThat(BrandInfluencerAggregator.matchesSponsorship(sponsored, "organic")).isFalse();
		assertThat(BrandInfluencerAggregator.matchesSponsorship(organic, "organic")).isTrue();
		assertThat(BrandInfluencerAggregator.matchesSponsorship(sponsored, "all")).isTrue();
		assertThat(BrandInfluencerAggregator.matchesSponsorship(sponsored, null)).isTrue();
	}

	// ---------- 픽스처 ----------

	private static InfluencerPost post(String shortcode, String username, String takenAtKst,
			Long likes) {
		return new InfluencerPost(shortcode, username, username + " 님", "pic.jpg", 1_000L, takenAtKst,
				false, null, likes, false, 0L);
	}

	private static BrandInfluencerResponse row(String username, Long followers, long postCount,
			long views, long comments, long likes, long likesKnownCount, String latestPostAt) {
		return row(username, followers, postCount, views, comments, likes, likesKnownCount,
				latestPostAt, 0);
	}

	private static BrandInfluencerResponse row(String username, Long followers, long postCount,
			long views, long comments, long likes, long likesKnownCount, String latestPostAt,
			long sponsoredCount) {
		return new BrandInfluencerResponse(username, username + " 님", "pic.jpg",
				"https://www.instagram.com/" + username + "/", followers, postCount, sponsoredCount,
				views, likes, comments, likesKnownCount, latestPostAt);
	}

	private static BrandInfluencerResponse withFollowers(Long followers) {
		return row("a", followers, 1, 0, 0, 0, 1, "2026-08-20T10:00:00");
	}
}
