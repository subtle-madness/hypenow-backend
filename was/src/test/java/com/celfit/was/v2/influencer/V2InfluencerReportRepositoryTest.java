package com.celfit.was.v2.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.BrandCollabRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CategoryRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SeriesRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SummaryRow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
		// 분석 DB 형상 DDL 사본(필요 컬럼만) — V1·V3·V10·V20·V30·V34·V35·V39·V40·V45 참조
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_peer_stats");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_peer_axis_stats");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_category_stats");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_beauty_ratio");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_content_series");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_summaries");
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS beauty_taxonomy");

		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle    text PRIMARY KEY,
				    followers bigint,
				    beauty    boolean,
				    fnb       boolean
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
				    axis       text NOT NULL DEFAULT 'beauty',
				    PRIMARY KEY (main_value, mid_label, sub_label)
				)""");
		// 아래 두 뷰 DDL은 analytics V35 계열(account_category_stats)·V<UTC>(account_peer_axis_stats,
		// 2026-09-01 축 인지화) 마이그레이션의 사본이다 — 원본이 바뀌면 같이 갱신할 것. 실뷰의
		// 퍼센타일·중앙값 컬럼은 was 미소비라 생략(기존 사본 관례).
		jdbcTemplate.execute("""
				CREATE VIEW account_category_stats AS
				SELECT s.account_handle,
				       COALESCE(t.main_label, a.main_category) AS main_group,
				       count(*)                                AS content_count,
				       COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END) AS axis
				FROM account_content_series s
				JOIN content_analyses a ON a.short_code = s.short_code
				LEFT JOIN (SELECT DISTINCT main_value, main_label, axis FROM beauty_taxonomy) t
				       ON t.main_value = a.main_category
				WHERE a.main_category IS NOT NULL
				GROUP BY s.account_handle, COALESCE(t.main_label, a.main_category),
				         COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END)
				""");
		jdbcTemplate.execute("""
				CREATE VIEW account_peer_axis_stats AS
				WITH cat AS (
				  SELECT DISTINCT ON (account_handle, axis) account_handle, axis, main_group
				  FROM account_category_stats
				  ORDER BY account_handle, axis, content_count DESC, main_group
				),
				base AS (
				  SELECT su.handle, ax.axis,
				         COALESCE(c.main_group, '미분류') AS peer_category,
				         CASE WHEN su.followers IS NULL   THEN '미상'
				              WHEN su.followers >= 500000 THEN '50만+'
				              WHEN su.followers >= 100000 THEN '10만-50만'
				              WHEN su.followers >=  50000 THEN '5만-10만'
				              WHEN su.followers >=  10000 THEN '1만-5만'
				              ELSE '1만 미만' END          AS follower_bucket
				  FROM account_summaries su
				  CROSS JOIN (VALUES ('beauty'), ('fnb')) AS ax(axis)
				  LEFT JOIN cat c ON c.account_handle = su.handle AND c.axis = ax.axis
				)
				SELECT handle, axis, peer_category, follower_bucket FROM base
				""");
		// V45 그대로 — 유사 인플루언서 후보 게이트가 이 뷰를 조인한다(findSimilarHandles).
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
				INSERT INTO account_summaries (handle, followers, last_posted_at) VALUES
				  ('sim_me', 10000, now()), ('sim_true', 10500, now()), ('sim_mid', 50000, now()),
				  ('sim_dup', 10001, now()), ('sim_far_tie', 90000, now()),
				  ('sim_other_cat', 10200, now())""");
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

	@AfterEach
	void tearDownViews() {
		// 컨테이너는 JVM 전체 공유(IntegrationTest static 싱글턴)라 이 클래스가 만든 뷰를 남겨두면,
		// 다른 클래스의 DROP TABLE content_analyses 등(CASCADE 없음)이 의존성 오류로 깨진다 —
		// 클래스 실행 순서가 비결정적이라 간헐 실패로만 드러난다. peer가 category에 의존하므로 역순 드랍.
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_peer_stats");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_peer_axis_stats");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_category_stats");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_beauty_ratio");
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
	void 유사_핸들은_교집합_내림차순_팔로워_근접_카테고리_필터() {
		// 07-28 유사도 v2: 넷 다 sim_me와 같은 카테고리(스킨케어) 100% 단일이라 믹스 기여(+0.4)가
		// 균일하게 붙어 상대 순위는 여전히 Jaccard(×0.6)로만 갈린다 — 이 픽스처는 LIMIT(이제 10)
		// 경계는 검증하지 않는다(최대_10명까지만_반환한다가 담당).
		// sim_true(overlap 3, diff 500) > sim_mid(overlap 2, diff 40000)
		//   > sim_dup(overlap 1 — distinct라 raw 4가 아님, diff 1) > sim_far_tie(overlap 1, diff 80000)
		// sim_other_cat은 traits 완전 일치지만 카테고리(메이크업)가 달라 제외. sim_me 자신도 제외.
		assertThat(repository.findSimilarHandles("sim_me", false))
				.containsExactly("sim_true", "sim_mid", "sim_dup", "sim_far_tie");
	}

	/**
	 * 유사도 v2 1단계(07-28) 혼합 점수 테스트 — 이 클래스의 공유 @BeforeEach 픽스처(sim_*·basic·
	 * dupe·o3~o6)와 충돌하지 않도록 전용 카테고리 어휘(탄력케어·모발케어·향케어·향수케어·홈케어)를
	 * 쓴다: 기존 픽스처는 전부 skincare/makeup(스킨케어/메이크업)이라, 이 라벨들을 재사용하면
	 * sim_other_cat(메이크업 100%)·sim_*(스킨케어 100%)이 예상치 못한 후보로 섞여 들어온다.
	 *
	 * peer_category(V39)는 account_category_stats(V35)의 건수 최다 main_group에서 파생되므로(동률은
	 * 라벨 오름차순) 옛 V1 테스트처럼 피어 카테고리를 믹스와 독립적으로 지정할 수 없다 — mixCounts
	 * 배분 자체가 지배 카테고리를 결정한다. 실게시물 행을 통해 통계를 만든다.
	 */
	private void seedSimAccount(String handle, long followers, String traitsJson, String... mixCounts) {
		jdbcTemplate.update("INSERT INTO accounts (handle, followers) VALUES (?, ?)", handle, followers);
		// 기본은 활동 계정(방금 업로드) — 휴면 케이스는 각 테스트가 last_posted_at을 덮어쓴다.
		jdbcTemplate.update("INSERT INTO account_summaries (handle, followers, last_posted_at) VALUES (?, ?, now())",
				handle, followers);
		jdbcTemplate.update(
				"INSERT INTO account_analyses (handle, analyzed_at, traits) VALUES (?, now(), ?::jsonb)",
				handle, traitsJson);
		int post = 0;
		for (int i = 0; i < mixCounts.length; i += 2) {
			String category = mixCounts[i];
			int count = Integer.parseInt(mixCounts[i + 1]);
			for (int j = 0; j < count; j++) {
				String shortCode = handle + "_p" + (post++);
				jdbcTemplate.update("""
						INSERT INTO account_content_series (short_code, account_handle, posted_at,
						  content_type, views, likes, comments, sponsored) VALUES
						  (?, ?, now(), 'reels', 1000, 10, 1, false)""", shortCode, handle);
				jdbcTemplate.update("""
						INSERT INTO content_analyses (short_code, is_beauty, main_category, ad_type,
						  detected_brands) VALUES (?, true, ?, 'organic', NULL)""", shortCode, category);
			}
		}
	}

	@Test
	void 태그_겹침이_높을수록_상위() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\",\"성분 분석\",\"릴스 중심\"]", "탄력케어", "10");
		// full: Jaccard 1.0, 믹스 1.0(둘 다 탄력케어 단일) → 0.6+0.4 = 1.0
		seedSimAccount("full", 20_000, "[\"정보형 리뷰\",\"성분 분석\",\"릴스 중심\"]", "탄력케어", "10");
		// partial: 교집합 1/합집합 5 = 0.2 → 0.12+0.4 = 0.52
		seedSimAccount("partial", 20_000, "[\"정보형 리뷰\",\"감성 콘텐츠\",\"일상 브이로그\"]", "탄력케어", "10");

		assertThat(repository.findSimilarHandles("me", false)).containsExactly("full", "partial");
	}

	@Test
	void 믹스만_같아도_컷은_넘고_태그_겹침보다는_아래() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		// tagged: Jaccard 1.0 → 1.0 / mixonly: Jaccard 0, 믹스 1.0 → 0.40(컷 0.30 통과)
		seedSimAccount("tagged", 99_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		seedSimAccount("mixonly", 11_000, "[\"감성 콘텐츠\"]", "탄력케어", "10");

		assertThat(repository.findSimilarHandles("me", false)).containsExactly("tagged", "mixonly");
	}

	@Test
	void 컷_미달이면_제외된다() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		// faroff 지배 카테고리는 탄력케어(6>4)라 me와 같은 피어 카테고리지만, me는 탄력케어 100%뿐이라
		// 겹치는 성분은 min(1.0, 0.6)=0.6 → 0.4×0.6=0.24 + 태그 무겹침 = 0.24 < 0.30.
		seedSimAccount("faroff", 10_000, "[\"감성 콘텐츠\"]", "탄력케어", "6", "모발케어", "4");

		assertThat(repository.findSimilarHandles("me", false)).isEmpty();
	}

	@Test
	void 최대_10명까지만_반환한다() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		for (int i = 0; i < 12; i++) {
			seedSimAccount("cand" + i, 10_000 + i, "[\"감성 콘텐츠\"]", "탄력케어", "10");
		}

		assertThat(repository.findSimilarHandles("me", false)).hasSize(10);
	}

	@Test
	void 동점이면_팔로워_근접_우선() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		// 둘 다 점수 0.40 동점(탄력케어 100%, 태그 무겹침) — 팔로워 차 5,000 < 50,000
		seedSimAccount("near", 15_000, "[\"감성 콘텐츠\"]", "탄력케어", "10");
		seedSimAccount("far", 60_000, "[\"일상 브이로그\"]", "탄력케어", "10");

		assertThat(repository.findSimilarHandles("me", false)).containsExactly("near", "far");
	}

	@Test
	void 믹스_결측_후보는_태그_성분만으로_판정() {
		// 기준 계정(me) 자체가 카테고리 통계가 전혀 없어(계정 통계 미기록) peer_category='미분류' —
		// my_shares가 빈 집합이 되어 모든 후보의 믹스 기여가 0으로 고정된다(V1 테스트의 "후보 믹스
		// 결측"을 이 실뷰 기반 스키마에 맞게 재해석: peer_category 자체가 account_category_stats에서
		// 파생되므로, 후보만 결측인데 피어 카테고리는 일치하는 조합은 만들 수 없다 — 일치하려면
		// 후보도 그 카테고리 통계를 반드시 가져야 하기 때문).
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]"); // mixCounts 없음 → peer_category='미분류'
		// nomixTagged: 0.6×1.0 = 0.60 ≥ 컷 / nomixBare: 0 → 제외
		seedSimAccount("nomixTagged", 12_000, "[\"정보형 리뷰\"]");
		seedSimAccount("nomixBare", 12_000, "[\"감성 콘텐츠\"]");

		assertThat(repository.findSimilarHandles("me", false)).containsExactly("nomixTagged");
	}

	@Test
	void 기준_계정이_없으면_빈_목록() {
		assertThat(repository.findSimilarHandles("ghost", false)).isEmpty();
	}

	@Test
	void 다른_피어_카테고리_후보는_제외된다() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		// traits·믹스가 완전 일치해도 피어 카테고리(모발케어)가 다르면 후보 자체가 아니다(스펙 ② 경계 유지).
		seedSimAccount("other", 10_000, "[\"정보형 리뷰\"]", "모발케어", "10");

		assertThat(repository.findSimilarHandles("me", false)).isEmpty();
	}

	@Test
	void 컷_경계_0_30은_포함된다() {
		// 믹스 교집합 정확히 0.75(0.4×0.75=0.300), 태그 무겹침 → 점수 정확히 0.30 → >= 컷 포함.
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "3", "모발케어", "1");
		seedSimAccount("edge", 10_000, "[\"감성 콘텐츠\"]", "탄력케어", "3", "향케어", "1");

		assertThat(repository.findSimilarHandles("me", false)).containsExactly("edge");
	}

	@Test
	void 최신_분석의_traits만_쓴다() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		// stale: 지배 카테고리는 me와 같은 탄력케어(6>4)라 믹스 0.4×0.6=0.24만으로는 컷 미달.
		// 옛 분석(now-1h)은 태그가 겹쳐(0.6) 합치면 0.84로 컷을 넘지만, 최신 분석(now)은 무겹침이라
		// 최신 기준 점수 0.24 < 0.30 → 제외돼야 한다(최신 분석만 쓴다는 걸 증명).
		seedSimAccount("stale", 12_000, "[\"감성 콘텐츠\"]", "탄력케어", "6", "모발케어", "4");
		jdbcTemplate.update("""
				INSERT INTO account_analyses (handle, analyzed_at, traits)
				VALUES ('stale', now() - interval '1 hour', '["정보형 리뷰"]'::jsonb)""");

		assertThat(repository.findSimilarHandles("me", false)).isEmpty();
	}

	@Test
	void 동점_동거리면_handle_순으로_고정된다() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		// 점수 0.40 동점 + 팔로워 거리 동일 → handle ASC 최종 타이브레이크(호출 간 순서 고정).
		seedSimAccount("bb", 12_000, "[\"감성 콘텐츠\"]", "탄력케어", "10");
		seedSimAccount("aa", 12_000, "[\"일상 브이로그\"]", "탄력케어", "10");

		assertThat(repository.findSimilarHandles("me", false)).containsExactly("aa", "bb");
	}

	@Test
	void 기준_계정_셰어가_교집합을_구속한다() {
		// me는 4개 카테고리(탄력케어·향수케어·허브케어·홈케어)에 1건씩 — 전부 동률(1건)이라
		// peer_category는 라벨 오름차순 최솟값인 탄력케어로 정해지고(코드포인트 비교로 탄력케어
		// 0xd0c4가 향수케어 0xd5a5·허브케어 0xd5c8·홈케어 0xd648보다 작아 넷 중 가장 앞섬 — 실행
		// 전 python3 sorted()로 검증됨), 탄력케어 셰어는 정확히 0.25(1/4)다.
		// skewed는 탄력케어 100%(4건) — 정상 계산이면 교집합 min(0.25, 1.0)=0.25 → 0.4×0.25=0.10 <
		// 컷. my_shares가 PARTITION BY main_group으로 잘못 정규화되면(리뷰 변이) me의 모든 카테고리
		// 셰어가 1.0으로 뭉개져 교집합이 min(1.0,1.0)=1.0 → 0.40으로 컷을 넘어버린다 — 그 변이를 잡는 그물.
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]",
				"탄력케어", "1", "향수케어", "1", "허브케어", "1", "홈케어", "1");
		seedSimAccount("skewed", 10_000, "[\"감성 콘텐츠\"]", "탄력케어", "4");

		assertThat(repository.findSimilarHandles("me", false)).isEmpty();
	}

	@Test
	void 휴면_후보는_제외된다() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		// 점수는 둘 다 1.0(완전 일치)이지만 dormant는 최근 업로드가 3개월 밖 → 휴면 제외.
		seedSimAccount("active", 12_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		seedSimAccount("dormant", 11_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		jdbcTemplate.update(
				"UPDATE account_summaries SET last_posted_at = now() - interval '4 months' WHERE handle = 'dormant'");

		assertThat(repository.findSimilarHandles("me", false)).containsExactly("active");
	}

	@Test
	void 최근_업로드일_미확인_후보는_제외된다() {
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		// last_posted_at NULL = 활동 확인 불가 → 휴면과 동일하게 제외.
		seedSimAccount("unknown", 11_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		jdbcTemplate.update("UPDATE account_summaries SET last_posted_at = NULL WHERE handle = 'unknown'");

		assertThat(repository.findSimilarHandles("me", false)).isEmpty();
	}

	@Test
	void 요약_행_없는_후보는_제외된다() {
		// account_peer_axis_stats가 account_summaries를 base로 파생되므로 요약 행이 없는 계정은
		// 후보 풀 자체에 없다 — 휴면 필터 도입 전에도 성립하던 불변식의 회귀 그물.
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		seedSimAccount("nosummary", 11_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		jdbcTemplate.update("DELETE FROM account_summaries WHERE handle = 'nosummary'");

		assertThat(repository.findSimilarHandles("me", false)).isEmpty();
	}

	@Test
	void 기준_계정이_휴면이어도_유사_목록은_반환된다() {
		// 휴면 필터는 후보에게만 적용된다 — 휴면 계정의 리포트 페이지에서도 유사 목록은 정상 동작.
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		seedSimAccount("active", 12_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		jdbcTemplate.update(
				"UPDATE account_summaries SET last_posted_at = now() - interval '4 months' WHERE handle = 'me'");

		assertThat(repository.findSimilarHandles("me", false)).containsExactly("active");
	}

	@Test
	void 뷰티_비율_게이트_미달_후보는_점수가_같아도_제외된다() {
		// 07-30 뷰티 비율 게이트 — 발굴 목록과 동일 기준(분석 8건 이상 & 20% 미만 제외)을 유사 인플루언서
		// 후보 단계에도 적용한다. goodcand·badcand는 traits·팔로워·카테고리가 완전히 동일해 게이트가
		// 없다면 둘 다 점수 1.0으로 동률 후보다 — badcand만 뷰티 비율(15%)이 문턱(20%) 미달이라 게이트가
		// 실제로 결과를 바꾼다는 걸 증명한다.
		seedSimAccount("me", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		seedSimAccountWithBeautyRatio("goodcand", 12_000, "[\"정보형 리뷰\"]", 100, 25, "탄력케어");
		seedSimAccountWithBeautyRatio("badcand", 12_000, "[\"정보형 리뷰\"]", 100, 15, "탄력케어");

		assertThat(repository.findSimilarHandles("me", false)).containsExactly("goodcand");
	}

	/** 뷰티 비율 게이트 검증 전용 — analyzedCount건 중 앞 beautyCount건만 is_beauty=true, 나머지는 false. */
	private void seedSimAccountWithBeautyRatio(String handle, long followers, String traitsJson,
			int analyzedCount, int beautyCount, String category) {
		jdbcTemplate.update("INSERT INTO accounts (handle, followers) VALUES (?, ?)", handle, followers);
		jdbcTemplate.update("INSERT INTO account_summaries (handle, followers, last_posted_at) VALUES (?, ?, now())",
				handle, followers);
		jdbcTemplate.update(
				"INSERT INTO account_analyses (handle, analyzed_at, traits) VALUES (?, now(), ?::jsonb)",
				handle, traitsJson);
		for (int i = 0; i < analyzedCount; i++) {
			String shortCode = handle + "_bp" + i;
			jdbcTemplate.update("""
					INSERT INTO account_content_series (short_code, account_handle, posted_at,
					  content_type, views, likes, comments, sponsored) VALUES
					  (?, ?, now(), 'reels', 1000, 10, 1, false)""", shortCode, handle);
			jdbcTemplate.update("""
					INSERT INTO content_analyses (short_code, is_beauty, main_category, ad_type,
					  detected_brands) VALUES (?, ?, ?, 'organic', NULL)""",
					shortCode, i < beautyCount, category);
		}
	}

	/** F&B 유사 시드 — is_beauty=false + F&B 대분류(snack 등), accounts.fnb=true·beauty=false. */
	private void seedFnbSimAccount(String handle, long followers, String traitsJson, String... mixCounts) {
		jdbcTemplate.update("INSERT INTO accounts (handle, followers, beauty, fnb) VALUES (?, ?, false, true)",
				handle, followers);
		jdbcTemplate.update("INSERT INTO account_summaries (handle, followers, last_posted_at) VALUES (?, ?, now())",
				handle, followers);
		jdbcTemplate.update(
				"INSERT INTO account_analyses (handle, analyzed_at, traits) VALUES (?, now(), ?::jsonb)",
				handle, traitsJson);
		int post = 0;
		for (int i = 0; i < mixCounts.length; i += 2) {
			String category = mixCounts[i];
			int count = Integer.parseInt(mixCounts[i + 1]);
			for (int j = 0; j < count; j++) {
				String shortCode = handle + "_f" + (post++);
				jdbcTemplate.update("""
						INSERT INTO account_content_series (short_code, account_handle, posted_at,
						  content_type, views, likes, comments, sponsored) VALUES
						  (?, ?, now(), 'reels', 1000, 10, 1, false)""", shortCode, handle);
				jdbcTemplate.update("""
						INSERT INTO content_analyses (short_code, is_beauty, main_category, ad_type,
						  detected_brands) VALUES (?, false, ?, 'organic', NULL)""", shortCode, category);
			}
		}
	}

	@Test
	void FnB_축_유사는_FnB_계정끼리_뷰티_비율_게이트_없이_동작한다() {
		// beauty_taxonomy에 fnb 어휘 시드(운영 V20260831032411의 축약)
		jdbcTemplate.update("""
				INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label,
				  main_order, mid_order, sub_order, axis) VALUES
				  ('snack', '간식류', '간식류', '과자', 11, 1, 1, 'fnb')""");
		seedFnbSimAccount("fme", 10_000, "[\"정보형 리뷰\"]", "snack", "10");
		// fcand: traits 완전 일치 + 같은 fnb 피어(간식류). 뷰티 비율 0%지만 F&B 축은 그 게이트를
		// 안 문다 — 걸리면 전멸(스펙 §4). 분석은 10건이라 표본 부족 보류도 아니다.
		seedFnbSimAccount("fcand", 12_000, "[\"정보형 리뷰\"]", "snack", "10");
		// bcand: 뷰티 계정(간식류 아님) — traits가 같아도 축이 달라 후보 자체가 아니어야 한다.
		seedSimAccount("bcand", 12_000, "[\"정보형 리뷰\"]", "skincare", "10");

		assertThat(repository.findSimilarHandles("fme", true)).containsExactly("fcand");
	}

	@Test
	void 뷰티_축_유사에_FnB_계정은_섞이지_않는다() {
		jdbcTemplate.update("""
				INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label,
				  main_order, mid_order, sub_order, axis) VALUES
				  ('snack', '간식류', '간식류', '과자', 11, 1, 1, 'fnb')""");
		// 뷰티 쪽 어휘는 공유 픽스처와 겹치지 않는 전용 라벨(탄력케어) — skincare를 쓰면 @BeforeEach의
		// sim_*(스킨케어 100%·활동 중·accounts 등재)가 믹스 1.0(=0.40)으로 컷을 넘어 함께 딸려온다.
		seedSimAccount("bme", 10_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		seedSimAccount("bcand", 12_000, "[\"정보형 리뷰\"]", "탄력케어", "10");
		// F&B 계정 — 뷰티 축 피어는 '미분류'라 탄력케어 풀과 안 겹치고, 뷰티 비율 게이트로도 걸러진다.
		seedFnbSimAccount("fnoise", 12_000, "[\"정보형 리뷰\"]", "snack", "10");

		assertThat(repository.findSimilarHandles("bme", false)).containsExactly("bcand");
	}

	@Test
	void findFnbAxis는_FnB_단독만_true다() {
		jdbcTemplate.update("""
				INSERT INTO accounts (handle, followers, beauty, fnb) VALUES
				  ('fnb_only', 1000, false, true),
				  ('mixed_axis', 1000, true, true),
				  ('beauty_only', 1000, true, false),
				  ('legacy', 1000, NULL, NULL)""");
		assertThat(repository.findFnbAxis("fnb_only")).isTrue();
		assertThat(repository.findFnbAxis("mixed_axis")).isFalse(); // 혼합은 beauty(기존 화면 불변)
		assertThat(repository.findFnbAxis("beauty_only")).isFalse();
		assertThat(repository.findFnbAxis("legacy")).isFalse();     // 레거시 null은 뷰티 모수 출신
		assertThat(repository.findFnbAxis("ghost")).isFalse();      // 행 부재 → 기본 beauty
	}
}
