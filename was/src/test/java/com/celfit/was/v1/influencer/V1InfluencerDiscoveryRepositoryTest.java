package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시드 세계관(전부 최근 12창 = account_content_series):
 * glow  — 2만 팔로워·ER 4.0·배율 12.4, 창 5개(스킨케어 4/메이크업 1 = 80%/20%), 협찬 2(롬앤 2·클리오 1),
 *         마지막 업로드 1일 전, 태그라인 이력 2행(최신이 이겨야 함)
 * calm  — 3만·ER 2.0·배율 5.0, 스킨케어 100%, 협찬 0, 10일 전
 * mute  — 4만·ER 1.0·릴스 없음(avg_views NULL), 피드만, 스킨케어 100%, 40일 전
 * tiny  — 1천·ER 3.0, 5일 전
 * 유효 팔로워는 Java(EffectiveFollowers)가 계산 — 여기선 SQL 조회 자체(findEngagements)만 검증한다.
 */
class V1InfluencerDiscoveryRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V1InfluencerDiscoveryRepository repository;

	private static V1InfluencerDiscoveryQuery query(String q, String main, String mid, String sub,
			String follower, String activity, String sponsored, String contact, String sort,
			Integer limit, Integer offset) {
		return V1InfluencerDiscoveryQuery.of(q, main, mid, sub, follower, activity, sponsored,
				contact, sort, limit, offset);
	}

	private static V1InfluencerDiscoveryQuery all() {
		return query(null, null, null, null, null, null, null, null, null, null, null);
	}

	@BeforeEach
	void setUpTables() {
		// 분석 DB 형상 DDL 사본(필요 컬럼만) — V1·V10·V20·V30·V37·V45 참조
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_beauty_ratio");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_summaries");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_content_series");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS contents");
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS beauty_taxonomy");
		jdbcTemplate.execute("DROP TABLE IF EXISTS image_assets");
		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_summaries (
				    handle             text PRIMARY KEY,
				    followers          bigint,
				    follows_count      bigint,
				    posts_count        bigint,
				    biography          text,
				    avg_views          bigint,
				    views_per_follower numeric,
				    avg_er_pct         numeric,
				    avg_likes          bigint,
				    avg_comments       bigint,
				    avg_hype_score     bigint,
				    last_posted_at     timestamptz
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_content_series (
				    short_code     text PRIMARY KEY,
				    account_handle text NOT NULL,
				    posted_at      timestamptz,
				    content_type   text,
				    views          bigint,
				    likes          bigint,
				    comments       bigint,
				    sponsored      boolean
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_analyses (
				    handle      text NOT NULL,
				    analyzed_at timestamptz NOT NULL,
				    tagline     text,
				    PRIMARY KEY (handle, analyzed_at)
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE content_analyses (
				    short_code      text PRIMARY KEY,
				    is_beauty       boolean,
				    main_category   text,
				    sub_categories  jsonb,
				    ad_type         text,
				    detected_brands jsonb
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE contents (
				    short_code    text PRIMARY KEY,
				    caption       text,
				    thumbnail_url text
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE beauty_taxonomy (
				    main_value text NOT NULL,
				    main_label text NOT NULL,
				    mid_label  text NOT NULL,
				    sub_label  text NOT NULL,
				    main_order int  NOT NULL,
				    mid_order  int  NOT NULL,
				    sub_order  int  NOT NULL,
				    PRIMARY KEY (main_value, mid_label, sub_label)
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE image_assets (
				    kind        text NOT NULL,
				    key         text NOT NULL,
				    object_path text NOT NULL,
				    PRIMARY KEY (kind, key)
				)""");
		// analytics V45 그대로 — 뷰티 게시물 비율 게이트가 이 뷰를 조인한다.
		jdbcTemplate.execute("""
				CREATE VIEW account_beauty_ratio AS
				SELECT s.account_handle,
				       count(*) FILTER (WHERE an.is_beauty IS NOT NULL) AS analyzed_count,
				       count(*) FILTER (WHERE an.is_beauty IS TRUE)     AS beauty_count
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code
				GROUP BY s.account_handle
				""");

		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers) VALUES
				  ('glow', '글로우', 'https://cdn/glow.jpg', 20000),
				  ('calm', '카암', 'https://cdn/calm.jpg', 30000),
				  ('mute', '뮤트', NULL, 40000),
				  ('tiny', '타이니', NULL, 1000)""");
		jdbcTemplate.update("""
				INSERT INTO account_summaries (handle, followers, follows_count, posts_count,
				  biography, avg_views, views_per_follower, avg_er_pct, avg_likes, avg_comments,
				  avg_hype_score, last_posted_at) VALUES
				  ('glow', 20000, 380, 214, E'수분크림 기록\\n문의는 DM', 50000, 12.42, 4.0, 3000, 150,
				   72, now() - interval '1 day'),
				  ('calm', 30000, 100, 90, '차분한 후기', 30000, 5.0, 2.0, 500, 30,
				   80, now() - interval '10 days'),
				  ('mute', 40000, 50, 40, NULL, NULL, NULL, 1.0, 300, 10,
				   NULL, now() - interval '40 days'),
				  ('tiny', 1000, 10, 20, '새싹', 2000, 2.0, 3.0, 25, 3,
				   45, now() - interval '5 days')""");
		// glow 창 5개: g1(1일 전)~g5(5일 전). 분류 5개 중 스킨케어 4·메이크업 1, 협찬 g2·g4.
		jdbcTemplate.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at,
				  content_type, views, likes, comments, sponsored) VALUES
				  ('g1', 'glow', now() - interval '1 day',  'reels', 60000, 3500, 160, false),
				  ('g2', 'glow', now() - interval '2 days', 'reels', 40000, 2500, 140, false),
				  ('g3', 'glow', now() - interval '3 days', 'reels', 55000, 3000, 150, false),
				  ('g4', 'glow', now() - interval '4 days', 'reels', 45000, 2800, 130, false),
				  ('g5', 'glow', now() - interval '5 days', 'feed',  NULL,   2000, 100, false),
				  ('c1', 'calm', now() - interval '10 days', 'reels', 30000, 500, 30, false),
				  ('c2', 'calm', now() - interval '11 days', 'reels', 28000, 480, 28, false),
				  ('c3', 'calm', now() - interval '12 days', 'reels', 32000, 520, 32, false),
				  ('m1', 'mute', now() - interval '40 days', 'feed', NULL, 300, 10, false),
				  ('m2', 'mute', now() - interval '41 days', 'feed', NULL, 280, 12, false),
				  ('t1', 'tiny', now() - interval '5 days', 'reels', 2000, 25, 3, false)""");
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, is_beauty, main_category, sub_categories,
				  ad_type, detected_brands) VALUES
				  ('g1', true, 'skincare', '["수분크림"]'::jsonb, 'organic', NULL),
				  ('g2', true, 'skincare', NULL, 'sponsored', '[{"name":"롬앤"}]'::jsonb),
				  ('g3', true, 'skincare', NULL, 'organic', NULL),
				  ('g4', true, 'makeup', '["립틴트"]'::jsonb, 'sponsored',
				   '[{"name":"롬앤"},{"name":"클리오"}]'::jsonb),
				  ('g5', true, 'skincare', NULL, 'organic', NULL),
				  ('c1', true, 'skincare', NULL, 'organic', NULL),
				  ('c2', true, 'skincare', NULL, 'organic', NULL),
				  ('c3', true, 'skincare', NULL, 'organic', NULL),
				  ('m1', true, 'skincare', NULL, 'organic', NULL),
				  ('m2', true, 'skincare', NULL, 'organic', NULL),
				  ('t1', true, 'skincare', NULL, 'organic', NULL)""");
		jdbcTemplate.update("""
				INSERT INTO contents (short_code, caption, thumbnail_url) VALUES
				  ('g1', '수분크림 일주일 후기', 'https://cdn/g1.jpg'),
				  ('g2', '롬앤 신상', 'https://cdn/g2.jpg'),
				  ('g3', '루틴 공유', 'https://cdn/g3.jpg'),
				  ('g4', '립 리뷰', 'https://cdn/g4.jpg'),
				  ('g5', '일상', 'https://cdn/g5.jpg'),
				  ('m1', '피드 글', 'https://cdn/m1.jpg')""");
		// 태그라인 이력 — 최신 행이 이겨야 한다
		jdbcTemplate.update("""
				INSERT INTO account_analyses (handle, analyzed_at, tagline) VALUES
				  ('glow', now() - interval '10 days', '옛 태그라인'),
				  ('glow', now() - interval '1 day', '저자극 스킨케어 리뷰 톤')""");
		jdbcTemplate.update("""
				INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label,
				  main_order, mid_order, sub_order) VALUES
				  ('makeup', '메이크업', '립메이크업', '립틴트', 3, 1, 1),
				  ('makeup', '메이크업', '립메이크업', '립스틱', 3, 1, 2),
				  ('skincare', '스킨케어', '크림', '크림', 1, 3, 1)""");
		jdbcTemplate.update("""
				INSERT INTO image_assets (kind, key, object_path) VALUES
				  ('profile', 'glow', 'p/glow.jpg'),
				  ('thumbnail', 'g1', 't/g1.jpg')""");
	}

	@AfterEach
	void tearDownView() {
		// 컨테이너는 JVM 전체 공유(IntegrationTest static 싱글턴) — 이 클래스가 만든 뷰가 남으면
		// 다른 테스트 클래스의 DROP TABLE content_analyses(CASCADE 없음)가 의존성 오류로 깨진다
		// (V2InfluencerReportRepositoryTest의 account_peer_stats·account_category_stats와 같은 이유).
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_beauty_ratio");
	}

	@Test
	void 기본_reach_정렬과_카드_필드() {
		List<CardRow> rows = repository.findCards(all());
		assertThat(rows).extracting(CardRow::handle)
				.containsExactly("glow", "calm", "tiny", "mute"); // 배율 DESC, NULL 마지막
		assertThat(repository.countCards(all())).isEqualTo(4);

		CardRow glow = rows.get(0);
		assertThat(glow.displayName()).isEqualTo("글로우");
		assertThat(glow.profileImageUrl()).isEqualTo("/img/p/glow.jpg"); // 아카이브 우선
		assertThat(glow.followers()).isEqualTo(20000);
		assertThat(glow.postsCount()).isEqualTo(214);
		assertThat(glow.followsCount()).isEqualTo(380);
		assertThat(glow.biography()).startsWith("수분크림 기록");
		assertThat(glow.tagline()).isEqualTo("저자극 스킨케어 리뷰 톤"); // 이력 중 최신
		assertThat(glow.avgViews()).isEqualTo(50000);
		assertThat(glow.sponsoredCount()).isEqualTo(2); // ad_type 정본 (series.sponsored 아님)
		assertThat(glow.avgHypeScore()).isEqualTo(72);

		CardRow mute = rows.get(3);
		assertThat(mute.avgViews()).isNull(); // 릴스 없는 계정
		assertThat(mute.profileImageUrl()).isNull();
		assertThat(mute.avgHypeScore()).isNull(); // 점수 가능 콘텐츠 없는 계정
	}

	@Test
	void 팔로워_구간은_min이상_max미만() {
		var mid = query(null, null, null, null, "10k-30k", null, null, null, null, null, null);
		assertThat(repository.findCards(mid)).extracting(CardRow::handle)
				.containsExactly("glow"); // calm(3만)은 max 미만 규칙으로 제외
		var upper = query(null, null, null, null, "30k-50k", null, null, null, null, null, null);
		assertThat(repository.findCards(upper)).extracting(CardRow::handle)
				.containsExactly("calm", "mute");
	}

	@Test
	void 협찬_구간은_ad_type_정본_양끝_포함() {
		var none = query(null, null, null, null, null, null, "none", null, null, null, null);
		assertThat(repository.findCards(none)).extracting(CardRow::handle)
				.containsExactlyInAnyOrder("calm", "mute", "tiny");
		var oneTwo = query(null, null, null, null, null, null, "1-2", null, null, null, null);
		assertThat(repository.findCards(oneTwo)).extracting(CardRow::handle)
				.containsExactly("glow");
	}

	@Test
	void 대분류는_비중_20퍼센트_임계값_매칭() {
		// glow의 메이크업 비중은 분류 5개 중 1개 = 딱 20% → 포함(경계 inclusive)
		var makeup = query(null, "makeup", null, null, null, null, null, null, null, null, null);
		assertThat(repository.findCards(makeup)).extracting(CardRow::handle)
				.containsExactly("glow");
		var skincare = query(null, "skincare", null, null, null, null, null, null, null, null, null);
		assertThat(repository.findCards(skincare)).hasSize(4);
	}

	@Test
	void 중분류는_소분류_확장_소분류는_태깅_1개면_매칭() {
		var mid = query(null, "makeup", "립메이크업", null, null, null, null, null, null, null, null);
		assertThat(repository.findCards(mid)).extracting(CardRow::handle).containsExactly("glow");
		var sub = query(null, "makeup", "립메이크업", "립틴트", null, null, null, null, null, null, null);
		assertThat(repository.findCards(sub)).extracting(CardRow::handle).containsExactly("glow");
		var miss = query(null, "makeup", "립메이크업", "립스틱", null, null, null, null, null, null, null);
		assertThat(repository.findCards(miss)).isEmpty();
	}

	@Test
	void 활동성은_마지막_업로드_경과일_이하() {
		var d7 = query(null, null, null, null, null, "7d", null, null, null, null, null);
		assertThat(repository.findCards(d7)).extracting(CardRow::handle)
				.containsExactly("glow", "tiny");
		var d30 = query(null, null, null, null, null, "30d", null, null, null, null, null);
		assertThat(repository.findCards(d30)).extracting(CardRow::handle)
				.containsExactly("glow", "calm", "tiny"); // mute(40일 전) 제외
	}

	@Test
	void 키워드는_전부_AND_부분일치_소스는_바이오와_브랜드까지() {
		// 수분크림(bio·캡션)과 롬앤(협업 브랜드)을 모두 가진 계정은 glow뿐
		var q = query("수분크림,롬앤", null, null, null, null, null, null, null, null, null, null);
		assertThat(repository.findCards(q)).extracting(CardRow::handle).containsExactly("glow");
		// 태그라인 매칭
		var tag = query("저자극", null, null, null, null, null, null, null, null, null, null);
		assertThat(repository.findCards(tag)).extracting(CardRow::handle).containsExactly("glow");
		// 소분류 라벨 매칭
		var sub = query("립틴트", null, null, null, null, null, null, null, null, null, null);
		assertThat(repository.findCards(sub)).extracting(CardRow::handle).containsExactly("glow");
		var miss = query("수분크림,없는말", null, null, null, null, null, null, null, null, null, null);
		assertThat(repository.findCards(miss)).isEmpty();
	}

	@Test
	void contact_open은_이메일_미수집이라_0건() {
		var open = query(null, null, null, null, null, null, null, "open", null, null, null);
		assertThat(repository.findCards(open)).isEmpty();
		assertThat(repository.countCards(open)).isZero();
	}

	@Test
	void 정렬_views_followers와_오프셋_페이지네이션() {
		var views = query(null, null, null, null, null, null, null, null, "views", null, null);
		assertThat(repository.findCards(views)).extracting(CardRow::handle)
				.containsExactly("glow", "calm", "tiny", "mute"); // avg_views DESC NULLS LAST
		var followers = query(null, null, null, null, null, null, null, null, "followers", null, null);
		assertThat(repository.findCards(followers)).extracting(CardRow::handle)
				.containsExactly("mute", "calm", "glow", "tiny");
		var page2 = query(null, null, null, null, null, null, null, null, null, 2, 2);
		assertThat(repository.findCards(page2)).extracting(CardRow::handle)
				.containsExactly("tiny", "mute");
		assertThat(repository.countCards(page2)).isEqualTo(4); // total은 오프셋 무관
	}

	@Test
	void 정렬_hype는_avg_hype_score_내림차순_NULL_마지막() {
		var hype = query(null, null, null, null, null, null, null, null, "hype", null, null);
		assertThat(repository.findCards(hype)).extracting(CardRow::handle)
				.containsExactly("calm", "glow", "tiny", "mute");
	}

	@Test
	void 보강_카테고리_비중은_분류_모수_기준_내림차순() {
		var shares = repository.findShares(List.of("glow"));
		assertThat(shares).extracting(r -> r.mainCategory() + ":" + r.pct())
				.containsExactly("skincare:80", "makeup:20");
	}

	@Test
	void 보강_협업_브랜드는_협찬분_빈도_내림차순() {
		var brands = repository.findBrands(List.of("glow", "calm"));
		assertThat(brands).extracting(V1InfluencerDiscoveryRepository.BrandRow::name)
				.containsExactly("롬앤", "클리오"); // calm은 협찬 없음 → 행 없음
	}

	@Test
	void 보강_썸네일은_최신순_최대_4개_피드는_views_null() {
		var thumbs = repository.findThumbs(List.of("glow", "mute"));
		var glowThumbs = thumbs.stream().filter(t -> t.accountHandle().equals("glow")).toList();
		assertThat(glowThumbs).hasSize(4); // 창 5개 중 최신 4개
		assertThat(glowThumbs.get(0).shortCode()).isEqualTo("g1");
		assertThat(glowThumbs.get(0).thumbnailUrl()).isEqualTo("/img/t/g1.jpg"); // 아카이브 우선
		assertThat(glowThumbs.get(1).thumbnailUrl()).isEqualTo("https://cdn/g2.jpg");
		assertThat(glowThumbs.get(0).adType()).isEqualTo("organic");
		assertThat(glowThumbs.get(1).adType()).isEqualTo("sponsored");
		var muteThumbs = thumbs.stream().filter(t -> t.accountHandle().equals("mute")).toList();
		assertThat(muteThumbs.get(0).views()).isNull(); // 피드 조회수 NULL(3.6)
		assertThat(muteThumbs.get(0).contentType()).isEqualTo("feed");
	}

	@Test
	void 보강_유효팔로워_재료는_시계열_전량을_핸들별로_반환() {
		// glow 5행(g1~g5) + calm 3행(c1~c3) — 순서는 무관(EffectiveFollowers 산식이 평균이라)
		var engagements = repository.findEngagements(List.of("glow", "calm"));
		assertThat(engagements).hasSize(8);
		var glow = engagements.stream()
				.filter(e -> e.accountHandle().equals("glow")).toList();
		assertThat(glow).hasSize(5);
		assertThat(glow).anySatisfy(e -> {
			assertThat(e.views()).isEqualTo(60000L);
			assertThat(e.likes()).isEqualTo(3500L);
			assertThat(e.comments()).isEqualTo(160L);
		});
		var calm = engagements.stream()
				.filter(e -> e.accountHandle().equals("calm")).toList();
		assertThat(calm).hasSize(3);
	}

	@Test
	void 뷰티_비율_게이트_경계값() {
		// MIN_ANALYZED(8) 미만이면 비율과 무관하게 게이트 보류(통과) — 뷰티 0%라도 표본이 얇아 판단 보류.
		seedBeautyRatioAccount("few", 7, 0);
		// 정확한 퍼센트 경계 검증을 위해 분모를 100으로 잡는다(8건 문턱 자체는 위 few가 별도 검증) —
		// 19%는 MIN_BEAUTY_RATIO_PERCENT(20) 미달로 제외, 20%는 경계 포함(>=)으로 통과.
		seedBeautyRatioAccount("low", 100, 19);
		seedBeautyRatioAccount("at", 100, 20);

		List<String> handles = repository.findCards(all()).stream().map(CardRow::handle).toList();
		assertThat(handles).contains("few", "at");
		assertThat(handles).doesNotContain("low");
		assertThat(repository.countCards(all())).isEqualTo(handles.size());
	}

	/** 뷰티 비율 게이트 전용 최소 픽스처 — analyzedCount건 중 앞 beautyCount건만 is_beauty=true. */
	private void seedBeautyRatioAccount(String handle, int analyzedCount, int beautyCount) {
		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers)
				VALUES (?, ?, NULL, 5000)""", handle, handle);
		jdbcTemplate.update("""
				INSERT INTO account_summaries (handle, followers, follows_count, posts_count,
				  biography, avg_views, views_per_follower, avg_er_pct, avg_likes, avg_comments,
				  avg_hype_score, last_posted_at)
				VALUES (?, 5000, 10, 20, NULL, NULL, NULL, NULL, NULL, NULL, NULL, now())""", handle);
		for (int i = 0; i < analyzedCount; i++) {
			String shortCode = handle + "_p" + i;
			jdbcTemplate.update("""
					INSERT INTO account_content_series (short_code, account_handle, posted_at,
					  content_type, views, likes, comments, sponsored)
					VALUES (?, ?, now(), 'feed', NULL, 0, 0, false)""", shortCode, handle);
			jdbcTemplate.update("""
					INSERT INTO content_analyses (short_code, is_beauty, main_category, sub_categories,
					  ad_type, detected_brands)
					VALUES (?, ?, 'skincare', NULL, 'organic', NULL)""", shortCode, i < beautyCount);
		}
	}
}
