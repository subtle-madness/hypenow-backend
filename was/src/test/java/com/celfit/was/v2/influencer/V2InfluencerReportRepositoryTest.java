package com.celfit.was.v2.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.BrandCollabRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CategoryRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SeriesRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SummaryRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 시드 세계관(계정별로 관심사 분리):
 * basic — findSummary·findSeries·findCategories 기본값 검증용, 창 3개(스킨케어 2·메이크업 1).
 * oldonly — 구 스키마 카피(perf_summary NULL)만 있는 계정 → findLatestCopy empty.
 * mixed — 구행(perf_summary 有, 오래됨) + 신행처럼 보이는 최신 NULL행 → 구행이 이겨야 함.
 * dupe — 게시물 1개, detected_brands에 같은 브랜드 2회 기재 → cnt는 게시물 수(1)여야 함.
 * o3~o6 — dupe와 같은 브랜드(롬앤) 협찬 계정, 협업 수 3~6(4명뿐이라 5번째 슬롯은 원래 비어야 함).
 *   자기 제외(<> :h)가 없으면 dupe(cnt 1)가 그 빈 슬롯을 채워 5번째로 들어온다 — othersJson 단언은
 *   정확히 4개(o6~o3)이고 dupe가 없음을 함께 확인해 자기 제외 절이 실제로 결과를 바꾼다는 걸 증명한다.
 *   o3의 게시물 하나(o3_1)는 detected_brands에 롬앤을 2회 중복 기재 — count(*)였다면 o3 cnt가
 *   4로 부풀어 o4(진짜 4)와 동률→최신순 tie-break로 o3가 o4를 앞지르지만, count(DISTINCT
 *   short_code)면 o3는 실게시물 수 3을 유지해 o4 뒤 순위(o6,o5,o4,o3)가 그대로다.
 * sim_me/sim_true/sim_mid/sim_dup/sim_far_tie/sim_other_cat — 유사 핸들 Jaccard·팔로워 근접·
 *   카테고리 필터·DISTINCT 교집합 검증(sim_dup은 trait 중복 기재로 raw count면 부풀려짐).
 */
class V2InfluencerReportRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V2InfluencerReportRepository repository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUpTables() {
		// 분석 DB 형상 DDL 사본(필요 컬럼만) — V1·V3·V10·V20·V30·V34·V35·V39·V40 참조
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_peer_stats");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_category_stats");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_content_series");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_summaries");
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS beauty_taxonomy");

		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle    text PRIMARY KEY,
				    followers bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_summaries (
				    handle             text PRIMARY KEY,
				    followers          bigint,
				    analyzed_count     bigint,
				    posts_count        bigint,
				    avg_views          bigint,
				    views_per_follower numeric,
				    avg_er_pct         numeric,
				    avg_likes          bigint,
				    avg_comments       bigint,
				    last_posted_at     timestamptz,
				    avg_interval_days  numeric
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
				CREATE TABLE content_analyses (
				    short_code      text PRIMARY KEY,
				    is_beauty       boolean,
				    main_category   text,
				    ad_type         text,
				    detected_brands jsonb
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_analyses (
				    handle          text NOT NULL,
				    analyzed_at     timestamptz NOT NULL,
				    tagline         text,
				    traits          jsonb,
				    perf_summary    text,
				    content_summary text,
				    ad_summary      text,
				    PRIMARY KEY (handle, analyzed_at)
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
		// 아래 두 뷰 DDL은 analytics V35(account_category_stats)·V39(account_peer_stats) 마이그레이션의
		// verbatim 사본이다 — 원본 마이그레이션이 바뀌면 이 사본도 같이 갱신할 것. 어긋나면 이 테스트는
		// 실DB와 다른 로직을 검증하게 된다.
		// V35 그대로 — account_peer_stats(V39)가 이 뷰에 의존한다.
		jdbcTemplate.execute("""
				CREATE VIEW account_category_stats AS
				SELECT s.account_handle,
				       COALESCE(t.main_label, a.main_category) AS main_group,
				       count(*)                                AS content_count
				FROM account_content_series s
				JOIN content_analyses a ON a.short_code = s.short_code
				LEFT JOIN (SELECT DISTINCT main_value, main_label FROM beauty_taxonomy) t
				       ON t.main_value = a.main_category
				WHERE a.is_beauty IS TRUE
				  AND a.main_category IS NOT NULL
				GROUP BY s.account_handle, COALESCE(t.main_label, a.main_category)
				""");
		// V39 그대로.
		jdbcTemplate.execute("""
				CREATE VIEW account_peer_stats AS
				WITH cat AS (
				  SELECT DISTINCT ON (account_handle) account_handle, main_group
				  FROM account_category_stats
				  ORDER BY account_handle, content_count DESC, main_group
				),
				ad AS (
				  SELECT s.account_handle,
				         round(avg(s.views) FILTER (WHERE s.views > 0))::bigint                            AS ad_avg_views,
				         round(avg((s.likes + s.comments)::numeric / NULLIF(su.followers, 0)) * 100, 1)    AS ad_avg_er_pct,
				         round(avg(s.likes))::bigint                                                        AS ad_avg_likes,
				         round(avg(s.comments))::bigint                                                     AS ad_avg_comments
				  FROM account_content_series s
				  JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
				  JOIN account_summaries su ON su.handle = s.account_handle
				  GROUP BY s.account_handle
				),
				base AS (
				  SELECT su.handle,
				         COALESCE(c.main_group, '미분류') AS peer_category,
				         CASE WHEN su.followers IS NULL   THEN '미상'
				              WHEN su.followers >= 500000 THEN '50만+'
				              WHEN su.followers >= 100000 THEN '10만-50만'
				              WHEN su.followers >=  50000 THEN '5만-10만'
				              WHEN su.followers >=  10000 THEN '1만-5만'
				              ELSE '1만 미만' END          AS follower_bucket,
				         su.avg_views, su.avg_er_pct, su.avg_likes, su.avg_comments,
				         ad.ad_avg_views, ad.ad_avg_er_pct, ad.ad_avg_likes, ad.ad_avg_comments
				  FROM account_summaries su
				  LEFT JOIN cat c ON c.account_handle = su.handle
				  LEFT JOIN ad   ON ad.account_handle = su.handle
				),
				med AS (
				  SELECT peer_category, follower_bucket,
				         percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS peer_median_er_pct
				  FROM base
				  GROUP BY peer_category, follower_bucket
				),
				gmed AS (
				  SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS global_median_er_pct FROM base
				)
				SELECT b.handle, b.peer_category, b.follower_bucket,
				       count(*) OVER peer AS peer_size,
				       round(m.peer_median_er_pct::numeric, 1)   AS peer_median_er_pct,
				       round(g.global_median_er_pct::numeric, 1) AS global_median_er_pct
				FROM base b
				JOIN med m ON m.peer_category = b.peer_category AND m.follower_bucket = b.follower_bucket
				CROSS JOIN gmed g
				WINDOW peer AS (PARTITION BY b.peer_category, b.follower_bucket)
				""");

		jdbcTemplate.update("""
				INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label,
				  main_order, mid_order, sub_order) VALUES
				  ('skincare', '스킨케어', '크림', '크림', 1, 3, 1),
				  ('makeup', '메이크업', '립메이크업', '립틴트', 3, 1, 1)""");

		// basic — findSummary·findSeries·findCategories 기본값 검증
		jdbcTemplate.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, posts_count,
				  avg_views, views_per_follower, avg_er_pct, avg_likes, avg_comments,
				  last_posted_at, avg_interval_days) VALUES
				  ('basic', 15000, 5, 50, 20000, 1.33, 3.5, 800, 40,
				   now() - interval '2 days', 2.5)""");
		jdbcTemplate.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at,
				  content_type, views, likes, comments, sponsored) VALUES
				  ('b3', 'basic', now() - interval '5 days', 'reels', 18000, 600, 20, false),
				  ('b1', 'basic', now() - interval '3 days', 'reels', 25000, 900, 50, false),
				  ('b2', 'basic', now() - interval '1 day',  'feed',  NULL,  700, 30, false)""");
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, is_beauty, main_category, ad_type,
				  detected_brands) VALUES
				  ('b3', true, 'skincare', 'organic', NULL),
				  ('b1', true, 'skincare', 'organic', NULL),
				  ('b2', true, 'makeup',   'organic', NULL)""");

		// oldonly — 구 스키마 카피만 존재
		jdbcTemplate.update("""
				INSERT INTO account_analyses (handle, analyzed_at, tagline, traits, perf_summary,
				  content_summary, ad_summary) VALUES
				  ('oldonly', now() - interval '1 day', '구행 태그라인', NULL, NULL, NULL, NULL)""");

		// mixed — 구행(perf_summary 有) + 더 최신인 신 스키마 미기록 행(NULL)
		jdbcTemplate.update("""
				INSERT INTO account_analyses (handle, analyzed_at, tagline, traits, perf_summary,
				  content_summary, ad_summary) VALUES
				  ('mixed', now() - interval '2 days', '과거 태그라인', '["a","b"]'::jsonb,
				   '과거 성과 요약', '과거 콘텐츠 요약', NULL),
				  ('mixed', now() - interval '1 day', '최신 태그라인(무시되어야 함)', NULL, NULL, NULL, NULL)""");

		// dupe — 게시물 1개(d1)의 detected_brands에 롬앤이 2회 기재 → cnt는 1
		jdbcTemplate.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at,
				  content_type, views, likes, comments, sponsored) VALUES
				  ('d1', 'dupe', now() - interval '3 days', 'reels', 30000, 1000, 60, true)""");
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, is_beauty, main_category, ad_type,
				  detected_brands) VALUES
				  ('d1', true, 'skincare', 'sponsored', '[{"name":"롬앤"},{"name":"롬앤"}]'::jsonb)""");

		// o3~o6 — 롬앤 협찬 계정, 협업 수 3~6(브랜드 하나당 게시물 하나, 중복 기재 없음). 딱 4명이라
		// 정상적으로도(자기 제외 있어도) 5번째 슬롯은 비어야 한다 — 자기 제외 검증의 핵심 조건.
		jdbcTemplate.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at,
				  content_type, views, likes, comments, sponsored)
				SELECT 'o' || acct || '_' || n, 'o' || acct,
				       now() - ((acct * 10 + n) || ' days')::interval, 'reels', 5000, 100, 5, true
				FROM generate_series(3, 6) AS acct, generate_series(1, 6) AS n
				WHERE n <= acct""");
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, is_beauty, main_category, ad_type,
				  detected_brands)
				SELECT 'o' || acct || '_' || n, true, 'skincare', 'sponsored', '[{"name":"롬앤"}]'::jsonb
				FROM generate_series(3, 6) AS acct, generate_series(1, 6) AS n
				WHERE n <= acct""");
		// o3_1만 롬앤을 2회 중복 기재 — o3의 실게시물 수는 여전히 3, 하지만 count(*)라면 4로 부풀어
		// o4(실게시물 4)와 동률이 되고, o3가 o4보다 최신이라 tie-break로 순위가 역전된다(others 판별 재료).
		jdbcTemplate.update("""
				UPDATE content_analyses SET detected_brands = '[{"name":"롬앤"},{"name":"롬앤"}]'::jsonb
				WHERE short_code = 'o3_1'""");

		// 유사 핸들 재료 — 전부 스킨케어(sim_other_cat만 메이크업), 팔로워는 sim_me=10000 기준.
		jdbcTemplate.update("""
				INSERT INTO accounts (handle, followers) VALUES
				  ('sim_me', 10000), ('sim_true', 10500), ('sim_mid', 50000),
				  ('sim_dup', 10001), ('sim_far_tie', 90000), ('sim_other_cat', 10200)""");
		jdbcTemplate.update("""
				INSERT INTO account_summaries (handle, followers) VALUES
				  ('sim_me', 10000), ('sim_true', 10500), ('sim_mid', 50000),
				  ('sim_dup', 10001), ('sim_far_tie', 90000), ('sim_other_cat', 10200)""");
		jdbcTemplate.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at,
				  content_type, views, likes, comments, sponsored) VALUES
				  ('sm1', 'sim_me', now(), 'reels', 1000, 10, 1, false),
				  ('st1', 'sim_true', now(), 'reels', 1000, 10, 1, false),
				  ('smi1', 'sim_mid', now(), 'reels', 1000, 10, 1, false),
				  ('sd1', 'sim_dup', now(), 'reels', 1000, 10, 1, false),
				  ('sf1', 'sim_far_tie', now(), 'reels', 1000, 10, 1, false),
				  ('so1', 'sim_other_cat', now(), 'reels', 1000, 10, 1, false)""");
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, is_beauty, main_category, ad_type,
				  detected_brands) VALUES
				  ('sm1', true, 'skincare', 'organic', NULL),
				  ('st1', true, 'skincare', 'organic', NULL),
				  ('smi1', true, 'skincare', 'organic', NULL),
				  ('sd1', true, 'skincare', 'organic', NULL),
				  ('sf1', true, 'skincare', 'organic', NULL),
				  ('so1', true, 'makeup',   'organic', NULL)""");
		jdbcTemplate.update("""
				INSERT INTO account_analyses (handle, analyzed_at, traits) VALUES
				  ('sim_me', now(), '["a","b","c"]'::jsonb),
				  ('sim_true', now(), '["a","b","c"]'::jsonb),
				  ('sim_mid', now(), '["a","b"]'::jsonb),
				  ('sim_dup', now(), '["a","a","a","a"]'::jsonb),
				  ('sim_far_tie', now(), '["a"]'::jsonb),
				  ('sim_other_cat', now(), '["a","b","c"]'::jsonb)""");
	}

	@Test
	void findSummary_기본값() {
		SummaryRow row = repository.findSummary("basic").orElseThrow();
		assertThat(row.followers()).isEqualTo(15000);
		assertThat(row.analyzedCount()).isEqualTo(5);
		assertThat(row.postsCount()).isEqualTo(50);
		assertThat(row.avgViews()).isEqualTo(20000);
		assertThat(row.viewsPerFollower()).isEqualByComparingTo("1.33");
		assertThat(row.avgErPct()).isEqualByComparingTo("3.5");
		assertThat(row.avgLikes()).isEqualTo(800);
		assertThat(row.avgComments()).isEqualTo(40);
		assertThat(row.avgIntervalDays()).isEqualByComparingTo("2.5");
		assertThat(row.lastPostedAt()).isNotNull();

		assertThat(repository.findSummary("없는계정")).isEmpty();
	}

	@Test
	void findSeries_올린_순_정렬과_피드_조회수_NULL() {
		List<SeriesRow> rows = repository.findSeries("basic");
		assertThat(rows).hasSize(3);
		assertThat(rows).extracting(SeriesRow::views)
				.containsExactly(18000L, 25000L, null); // posted_at 오름차순(오래된 것부터)
		assertThat(rows.get(2).contentType()).isEqualTo("feed");
		assertThat(rows.get(2).likes()).isEqualTo(700);
		assertThat(rows.get(0).sponsored()).isFalse();
	}

	@Test
	void findCategories_대분류_건수_내림차순() {
		List<CategoryRow> rows = repository.findCategories("basic");
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).label()).isEqualTo("스킨케어");
		assertThat(rows.get(0).cnt()).isEqualTo(2);
		assertThat(rows.get(1).label()).isEqualTo("메이크업");
		assertThat(rows.get(1).cnt()).isEqualTo(1);
	}

	@Test
	void 구_스키마_카피는_리포트_미생성으로_취급() {
		assertThat(repository.findLatestCopy("oldonly")).isEmpty();
	}

	@Test
	void 구행과_최신_NULL신행이_섞이면_구행을_반환() throws Exception {
		var copy = repository.findLatestCopy("mixed").orElseThrow();
		assertThat(copy.tagline()).isEqualTo("과거 태그라인"); // 최신 NULL행 무시
		assertThat(copy.perfSummary()).isEqualTo("과거 성과 요약");
		assertThat(copy.contentSummary()).isEqualTo("과거 콘텐츠 요약");
		assertThat(copy.adSummary()).isNull();

		List<String> traits = objectMapper.readValue(copy.traitsJson(),
				new TypeReference<List<String>>() {
				});
		assertThat(traits).containsExactly("a", "b");
	}

	@Test
	void 브랜드_협업은_중복_기재를_한_번만_센다() throws Exception {
		List<BrandCollabRow> rows = repository.findBrandCollabs("dupe");
		assertThat(rows).hasSize(1);
		BrandCollabRow row = rows.get(0);
		assertThat(row.name()).isEqualTo("롬앤");
		assertThat(row.cnt()).isEqualTo(1); // 같은 게시물 내 2회 기재라도 게시물 수는 1

		List<String> contentIds = objectMapper.readValue(row.contentIdsJson(),
				new TypeReference<List<String>>() {
				});
		assertThat(contentIds).containsExactly("d1");

		List<String> others = objectMapper.readValue(row.othersJson(),
				new TypeReference<List<String>>() {
				});
		// 후보는 o3~o6(4명)뿐이라 5번째 슬롯이 원래 비어야 한다. 자기 제외(<> :h) 절이 없으면
		// dupe(cnt 1) 자신이 그 슬롯을 채워 5개가 되고 dupe가 포함된다 — 정확히 4개+dupe 부재로
		// 자기 제외가 실제로 결과를 바꾼다는 걸 함께 증명한다.
		// o3_1의 중복 기재도 여기서 함께 걸린다: others의 cnt가 count(*)라면 o3=4로 o4와 동률→
		// tie-break(최신순)로 o3가 o4를 앞질러 순서가 o6,o5,o3,o4가 된다. count(DISTINCT
		// short_code)면 o3는 실게시물 수 3을 유지해 아래 순서(o6,o5,o4,o3)가 그대로 성립한다.
		assertThat(others).containsExactly("o6", "o5", "o4", "o3"); // 협업 수 내림차순
		assertThat(others).doesNotContain("dupe");
	}

	@Test
	void 유사_핸들은_교집합_내림차순_팔로워_근접_카테고리_필터_최대_9() {
		// sim_true(overlap 3, diff 500) > sim_mid(overlap 2, diff 40000)
		//   > sim_dup(overlap 1 — distinct라 raw 4가 아님, diff 1) > sim_far_tie(overlap 1, diff 80000)
		// sim_other_cat은 traits 완전 일치지만 카테고리(메이크업)가 달라 제외. sim_me 자신도 제외.
		assertThat(repository.findSimilarHandles("sim_me"))
				.containsExactly("sim_true", "sim_mid", "sim_dup", "sim_far_tie");
	}
}
