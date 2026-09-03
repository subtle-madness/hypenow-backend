package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
 * gp    — findGroupPurchaseCounts 전용(계정·창 픽스처 없이 contents 14행만) — posted_at DESC 상위
 *         12개 컷·group_purchase_judgments 카운트 검증 재료(gp2·gp4 창 안 verdict=true 2건,
 *         gp6 창 안 verdict NULL 제외, gp8 창 안 판정 행 없음 제외, gp13 창 밖 verdict=true 제외)
 * 유효 팔로워는 Java(EffectiveFollowers)가 계산 — 여기선 SQL 조회 자체(findEngagements)만 검증한다.
 * minComments·maxComments 계산 자체는 어셈블러 단위 테스트(V1InfluencerDiscoveryAssemblerTest)가 맡는다.
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
				contact, sort, limit, offset, null);
	}

	private static V1InfluencerDiscoveryQuery all() {
		return query(null, null, null, null, null, null, null, null, null, null, null);
	}

	@BeforeEach
	void setUpTables() {
		// 분석 DB 형상 DDL 사본(필요 컬럼만) — V1·V10·V20·V30·V37·V45·V20260827045100 참조
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_beauty_ratio");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_category_share");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_sponsored_counts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_summaries");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_content_series");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS contents");
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS beauty_taxonomy");
		jdbcTemplate.execute("DROP TABLE IF EXISTS image_assets");
		jdbcTemplate.execute("DROP TABLE IF EXISTS group_purchase_judgments");
		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint,
				    beauty            boolean,
				    fnb               boolean
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_summaries (
				    handle                  text PRIMARY KEY,
				    followers               bigint,
				    follows_count           bigint,
				    posts_count             bigint,
				    biography               text,
				    avg_views               bigint,
				    views_per_follower      numeric,
				    avg_er_pct              numeric,
				    avg_likes               bigint,
				    avg_comments            bigint,
				    avg_hype_score          bigint,
				    last_posted_at          timestamptz,
				    email                   text,
				    avg_hype_raw            numeric,
				    avg_hype_score_precise  numeric
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
		// account_handle·posted_at은 findGroupPurchaseCounts(공동구매 재료, contents 테이블 posted_at DESC 컷)가
		// findRecentCards(6.4)와 같은 모수를 쓰기 위해 필요 — 운영 DDL(V1__serving_tables.sql)과 동일 컬럼.
		jdbcTemplate.execute("""
				CREATE TABLE contents (
				    short_code     text PRIMARY KEY,
				    account_handle text,
				    posted_at      timestamptz,
				    caption        text,
				    thumbnail_url  text
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
		jdbcTemplate.execute("""
				CREATE TABLE image_assets (
				    kind        text NOT NULL,
				    key         text NOT NULL,
				    object_path text NOT NULL,
				    PRIMARY KEY (kind, key)
				)""");
		// analytics V20260903110541 그대로(컬럼만 — Task 1이 세운 판정 결과 테이블 사본).
		// findGroupPurchaseCounts가 여기 verdict=true만 센다(스펙 §6 신뢰성 우선).
		jdbcTemplate.execute("""
				CREATE TABLE group_purchase_judgments (
				    short_code          text PRIMARY KEY,
				    verdict             boolean,
				    tier                text NOT NULL,
				    reason              text,
				    judged_caption_hash text NOT NULL,
				    judged_at           timestamptz NOT NULL,
				    model               text
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
		// analytics V20260827045100 정의 그대로(운영은 matview, 테스트는 뷰 — 리포지토리 SQL은 구분 없음).
		// mainCategory 게이트·sp 조인이 참조한다.
		jdbcTemplate.execute("""
				CREATE VIEW account_category_share AS
				SELECT s.account_handle, an.main_category,
				       round(100.0 * count(*)
				             / sum(count(*)) OVER (PARTITION BY s.account_handle, t.axis))::int AS pct
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code
				JOIN (SELECT DISTINCT main_value, axis FROM beauty_taxonomy) t
				     ON t.main_value = an.main_category
				WHERE an.main_category IS NOT NULL
				GROUP BY s.account_handle, an.main_category, t.axis
				""");
		jdbcTemplate.execute("""
				CREATE VIEW account_sponsored_counts AS
				SELECT s.account_handle, count(*) AS cnt
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
				GROUP BY s.account_handle
				""");

		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers, beauty, fnb) VALUES
				  ('glow', '글로우', 'https://cdn/glow.jpg', 20000, true, false),
				  ('calm', '카암', 'https://cdn/calm.jpg', 30000, NULL, NULL),
				  ('mute', '뮤트', NULL, 40000, true, false),
				  ('tiny', '타이니', NULL, 1000, true, false),
				  ('fbfood', '푸드핏', NULL, 12000, false, true)""");
		// avg_hype_raw는 avg_hype_score(정수 반올림)를 만드는 반올림 전 평균 — 2026-07-30까지는
		// 정렬 키였다(스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §9 하위절).
		// avg_hype_score_precise(2026-07-30, 스펙 §10 — 콘텐츠 출력 매핑 반영 소수 표시값) 도입 후
		// 정렬은 이 컬럼으로 다시 옮겼다 — 표시=정렬 일원화라 avg_hype_raw 우회가 더는 필요 없다.
		// 여기서는 각 계정의 표시 점수와 같은 상대 순서를 갖는 근사값을 넣어 기존 정렬 기대값
		// (calm>glow>tiny>mute)이 유지된다 — 동점 시나리오는 tieAlphaLo/tieZetaHi 전용 픽스처로
		// 별도 검증한다.
		jdbcTemplate.update("""
				INSERT INTO account_summaries (handle, followers, follows_count, posts_count,
				  biography, avg_views, views_per_follower, avg_er_pct, avg_likes, avg_comments,
				  avg_hype_score, avg_hype_raw, last_posted_at, email, avg_hype_score_precise) VALUES
				  ('glow', 20000, 380, 214, E'수분크림 기록\\n문의는 DM', 50000, 12.42, 4.0, 3000, 150,
				   72, 71.6, now() - interval '1 day', 'contact@glow.co', 71.6000),
				  ('calm', 30000, 100, 90, '차분한 후기', 30000, 5.0, 2.0, 500, 30,
				   80, 79.8, now() - interval '10 days', NULL, 79.8000),
				  ('mute', 40000, 50, 40, NULL, NULL, NULL, 1.0, 300, 10,
				   NULL, NULL, now() - interval '40 days', NULL, NULL),
				  ('tiny', 1000, 10, 20, '새싹', 2000, 2.0, 3.0, 25, 3,
				   45, 44.7, now() - interval '5 days', NULL, 44.7000),
				  -- F&B 단독 계정 — reach 2위 값이라 축 게이트가 없으면 무필터 상위에 섞인다
				  ('fbfood', 12000, 80, 60, '밀키트 리뷰', 40000, 10.0, 3.5, 1500, 90,
				   60, 59.5, now() - interval '2 days', NULL, 59.5000)""");
		// fbfood 창 8개: 분석 8건 전부 비뷰티(뷰티 비율 0%) — F&B 필터의 뷰티비율 게이트 스킵 검증
		// 재료. 그중 convenience 분류 2건 → F&B 축 분모 2, 비중 100% ≥ 20% 게이트 통과.
		for (int i = 1; i <= 8; i++) {
			jdbcTemplate.update("""
					INSERT INTO account_content_series (short_code, account_handle, posted_at,
					  content_type, views, likes, comments, sponsored)
					VALUES (?, 'fbfood', now() - (? || ' days')::interval, 'reels', 10000, 500, 40, false)""",
					"fb_p" + i, i);
			jdbcTemplate.update("""
					INSERT INTO content_analyses (short_code, is_beauty, main_category, sub_categories,
					  ad_type, detected_brands)
					VALUES (?, false, ?, NULL, 'organic', NULL)""",
					"fb_p" + i, i <= 2 ? "convenience" : null);
		}
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
				INSERT INTO contents (short_code, account_handle, posted_at, caption, thumbnail_url) VALUES
				  ('g1', 'glow', now() - interval '1 day',  '수분크림 일주일 후기', 'https://cdn/g1.jpg'),
				  ('g2', 'glow', now() - interval '2 days', '롬앤 신상', 'https://cdn/g2.jpg'),
				  ('g3', 'glow', now() - interval '3 days', '루틴 공유', 'https://cdn/g3.jpg'),
				  ('g4', 'glow', now() - interval '4 days', '립 리뷰', 'https://cdn/g4.jpg'),
				  ('g5', 'glow', now() - interval '5 days', '일상', 'https://cdn/g5.jpg'),
				  ('m1', 'mute', now() - interval '40 days', '피드 글', 'https://cdn/m1.jpg')""");
		// findGroupPurchaseCounts 전용 픽스처 — account_content_series와 무관하게 contents.posted_at
		// DESC로 직접 12개를 자른다(findRecentCards와 같은 모수). 판정은 캡션 정규식이 아니라
		// group_purchase_judgments 테이블이 정본(analytics GROUP_PURCHASE_JUDGE 잡의 산출물 사본) —
		// 캡션 문구는 사람이 읽는 힌트일 뿐 세는 근거가 아니다.
		for (int i = 1; i <= 14; i++) {
			jdbcTemplate.update("""
					INSERT INTO contents (short_code, account_handle, posted_at, caption, thumbnail_url)
					VALUES (?, 'gp', now() - (? || ' days')::interval, '일상 기록 ' || ?, NULL)""",
					"gp" + i, i, i);
		}
		// gp2·gp4는 창 안(2·4일 전)에서 verdict=true → 카운트 2건 기대.
		// gp6은 창 안이지만 verdict NULL(미판정 — LLM 실패·잡 대기, 30분 재시도) → 제외.
		// gp8은 창 안이지만 판정 행 자체가 없음(잡이 아직 못 본 신규 게시물) → 제외.
		// gp13은 verdict=true지만 rn=13으로 창 밖(윈도우 경계 검증) → 제외.
		jdbcTemplate.update("""
				INSERT INTO group_purchase_judgments (short_code, verdict, tier, reason,
				  judged_caption_hash, judged_at) VALUES
				  ('gp2', true, 'RULE', '#공구', 'h2', now()),
				  ('gp4', true, 'RULE', '공동구매', 'h4', now()),
				  ('gp6', NULL, 'LLM', NULL, 'h6', now()),
				  ('gp13', true, 'RULE', '#공구', 'h13', now())""");
		// 태그라인 이력 — 최신 행이 이겨야 한다
		jdbcTemplate.update("""
				INSERT INTO account_analyses (handle, analyzed_at, tagline) VALUES
				  ('glow', now() - interval '10 days', '옛 태그라인'),
				  ('glow', now() - interval '1 day', '저자극 스킨케어 리뷰 톤')""");
		jdbcTemplate.update("""
				INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label,
				  main_order, mid_order, sub_order, axis) VALUES
				  ('makeup', '메이크업', '립메이크업', '립틴트', 3, 1, 1, 'beauty'),
				  ('makeup', '메이크업', '립메이크업', '립스틱', 3, 1, 2, 'beauty'),
				  ('skincare', '스킨케어', '크림', '크림', 1, 3, 1, 'beauty'),
				  ('convenience', '가공/간편식', '가공/간편식', '밀키트', 10, 1, 2, 'fnb')""");
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
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_category_share");
		jdbcTemplate.execute("DROP VIEW IF EXISTS account_sponsored_counts");
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
		assertThat(glow.avgHypeScorePrecise()).isEqualByComparingTo("71.6000"); // 소수점 노출(스펙 §10)
		assertThat(glow.email()).isEqualTo("contact@glow.co"); // account_summaries.email(V46) 그대로 노출

		CardRow mute = rows.get(3);
		assertThat(mute.avgViews()).isNull(); // 릴스 없는 계정
		assertThat(mute.profileImageUrl()).isNull();
		assertThat(mute.avgHypeScore()).isNull(); // 점수 가능 콘텐츠 없는 계정
		assertThat(mute.avgHypeScorePrecise()).isNull();
		assertThat(mute.email()).isNull(); // biography 매치 없음(또는 미보유)
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

	// sp(협찬 수) 서브쿼리는 핸들 지정 경로에서 :handles로 좁혀 집계한다(전체 계정 집계 금지 —
	// 2026-07-30 실측 158ms 중 121ms). 좁힌 뒤에도 계정별 값이 전체 집계와 같아야 한다.
	@Test
	void 핸들_지정_조회는_요청_부분집합에서도_협찬_수가_전체집계와_같다() {
		// glow만 협찬 2건(g2·g4). 요청 집합을 좁혀도 glow는 2, 미보유 계정은 COALESCE로 0.
		assertThat(repository.findCardsByHandles(List.of("glow", "calm")))
				.extracting(CardRow::handle, CardRow::sponsoredCount)
				.containsExactlyInAnyOrder(tuple("glow", 2L), tuple("calm", 0L));
		// 단건(가장 좁은 집합)에서도 동일 — 푸시다운이 자기 계정 집계를 깎지 않는다
		assertThat(repository.findCardsByHandles(List.of("glow")))
				.singleElement()
				.extracting(CardRow::sponsoredCount).isEqualTo(2L);
		// 협찬 보유 계정(glow)을 뺀 요청에 그 값이 새어들지 않는다
		assertThat(repository.findCardsByHandles(List.of("calm", "mute", "tiny")))
				.extracting(CardRow::sponsoredCount).containsOnly(0L);
	}

	@Test
	void 핸들_지정_조회는_필터_없이_존재하는_핸들만_카드_필드째로_반환한다() {
		// 발굴 목록과 같은 FROM(ip·cp·sp·br)을 공유하는지 — 보강 필드까지 그대로 채워져야 한다.
		List<CardRow> rows = repository.findCardsByHandles(List.of("glow", "__없는핸들__"));
		assertThat(rows).extracting(CardRow::handle).containsExactly("glow");
		CardRow glow = rows.get(0);
		assertThat(glow.profileImageUrl()).isEqualTo("/img/p/glow.jpg"); // ip 조인
		assertThat(glow.tagline()).isEqualTo("저자극 스킨케어 리뷰 톤"); // cp LATERAL(최신 1행)
		assertThat(glow.sponsoredCount()).isEqualTo(2L); // sp 조인
		assertThat(glow.avgHypeScorePrecise()).isEqualByComparingTo("71.6000");
		// 뷰티 비율 게이트·정렬은 이 경로에 적용되지 않는다(호출부가 순서 복원)
		assertThat(repository.findCardsByHandles(List.of("mute"))).hasSize(1);
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
	void 무필터_발굴에_FnB_계정은_안_나온다() {
		// 기본 화면 불변(서빙 개방 §6-3) — COALESCE(a.beauty, true)가 지킨다.
		// calm(축 NULL — 롤링 창 재현)은 뷰티 취급으로 그대로 나온다.
		assertThat(repository.findCards(all())).extracting(CardRow::handle)
				.containsExactly("glow", "calm", "tiny", "mute");
	}

	@Test
	void FnB_대분류_필터는_FnB_계정만_내고_뷰티비율_게이트를_안_문다() {
		// fbfood는 분석 8건 전부 비뷰티(뷰티 비율 0%) — 뷰티비율 게이트를 물면 전멸한다(§3).
		// 오판 방어는 F&B 비중 게이트(convenience 100% ≥ 20%)가 같은 역할.
		var fnb = query(null, "convenience", null, null, null, null, null, null, null, null, null);
		assertThat(repository.findCards(fnb)).extracting(CardRow::handle)
				.containsExactly("fbfood");
	}

	@Test
	void vertical_fnb는_FnB_계정_전체를_비중_게이트_없이_낸다() {
		// 2026-09-01 FE 피드백 #1 — 대분류 없이 축 전체. 비중 게이트(EXISTS)는 mainCategory
		// 블록 소속이라 안 붙고, COALESCE(a.fnb, false)만 적용된다.
		var fnb = V1InfluencerDiscoveryQuery.of(null, null, null, null, null, null, null, null,
				null, null, null, "fnb");
		assertThat(repository.findCards(fnb)).extracting(CardRow::handle)
				.containsExactly("fbfood");
		assertThat(repository.countCards(fnb)).isEqualTo(1);
	}

	@Test
	void vertical_beauty는_무필터와_동치다() {
		var beauty = V1InfluencerDiscoveryQuery.of(null, null, null, null, null, null, null, null,
				null, null, null, "beauty");
		assertThat(repository.findCards(beauty)).extracting(CardRow::handle)
				.containsExactly("glow", "calm", "tiny", "mute");
	}

	@Test
	void 뷰티_대분류_필터에_FnB_계정은_안_섞인다() {
		// 축 조건(COALESCE(a.beauty, true))이 뷰티 필터 경로에도 걸린다
		var makeup = query(null, "makeup", null, null, null, null, null, null, null, null, null);
		assertThat(repository.findCards(makeup)).extracting(CardRow::handle)
				.containsExactly("glow");
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
	void contact_open은_이메일_보유_계정만() {
		// 시드 중 email이 채워진 건 glow뿐(biography 정규식 파싱, V46) — calm·mute·tiny는 NULL이라 제외.
		var open = query(null, null, null, null, null, null, null, "open", null, null, null);
		assertThat(repository.findCards(open)).extracting(CardRow::handle).containsExactly("glow");
		assertThat(repository.countCards(open)).isEqualTo(1);
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
	void 정렬_hype는_avg_hype_score_precise_내림차순_NULL_마지막() {
		// 2026-07-30부터 정렬 키는 avg_hype_score_precise(소수, 콘텐츠 출력 매핑 반영 — 스펙
		// 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10)다. 표시값도 이 컬럼이라
		// 표시=정렬이 일원화됐다 — 시드 순서는 기존과 동일한 상대 순서(calm>glow>tiny>mute)를 유지.
		var hype = query(null, null, null, null, null, null, null, null, "hype", null, null);
		assertThat(repository.findCards(hype)).extracting(CardRow::handle)
				.containsExactly("calm", "glow", "tiny", "mute");
	}

	@Test
	void hype_정렬은_avg_hype_score_정수가_같아도_precise로_순서를_가른다() {
		// 회귀 재현(스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §9 하위절·§10):
		// 정수 반올림이 상위권에서 동점을 대량으로 만들어 정렬이 사실상 handle 알파벳순에 지배됐다.
		// tieAlphaLo(avg_hype_score=88, avg_hype_score_precise=87.6000)와 tieZetaHi(avg_hype_score=88,
		// avg_hype_score_precise=88.4000) — 표시 정수는 동점이지만 소수는 zeta가 더 크다. 핸들
		// 알파벳순은 alpha가 zeta보다 앞이라(구코드 ORDER BY avg_hype_score DESC, handle ASC였다면
		// tieAlphaLo가 먼저 나왔을 것 — 알파벳순과 precise 내림차순이 정반대를 가리키도록 이름을
		// 골랐다), precise 정렬은 tieZetaHi가 먼저 나와야 한다.
		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers) VALUES
				  ('tieAlphaLo', '타이알파로', NULL, 15000),
				  ('tieZetaHi', '타이제타하이', NULL, 15000)""");
		jdbcTemplate.update("""
				INSERT INTO account_summaries (handle, followers, follows_count, posts_count,
				  biography, avg_views, views_per_follower, avg_er_pct, avg_likes, avg_comments,
				  avg_hype_score, avg_hype_raw, last_posted_at, avg_hype_score_precise) VALUES
				  ('tieAlphaLo', 15000, 10, 20, NULL, NULL, NULL, NULL, NULL, NULL,
				   88, 87.6, now() - interval '3 days', 87.6000),
				  ('tieZetaHi', 15000, 10, 20, NULL, NULL, NULL, NULL, NULL, NULL,
				   88, 88.4, now() - interval '3 days', 88.4000)""");

		var hype = query(null, null, null, null, null, null, null, null, "hype", null, null);
		List<String> handles = repository.findCards(hype).stream().map(CardRow::handle).toList();
		int hi = handles.indexOf("tieZetaHi");
		int lo = handles.indexOf("tieAlphaLo");
		assertThat(hi).isGreaterThanOrEqualTo(0);
		assertThat(lo).isGreaterThanOrEqualTo(0);
		assertThat(hi).as("tieZetaHi(precise 88.4)가 tieAlphaLo(precise 87.6)보다 앞이어야 함 — handle 알파벳순(구코드)이면 반대")
				.isLessThan(lo);
	}

	@Test
	void 보강_카테고리_비중은_분류_모수_기준_내림차순() {
		// 뷰티 축 — F&B 단독 계정(fbfood)은 행 없음(카드 조립이 축 밖 비중을 안 싣는다)
		var shares = repository.findShares(List.of("glow", "fbfood"), false);
		assertThat(shares)
				.extracting(r -> r.accountHandle() + ":" + r.mainCategory() + ":" + r.pct())
				.containsExactly("glow:skincare:80", "glow:makeup:20");
	}

	@Test
	void 보강_카테고리_비중_FnB축은_FnB_분류분이_분모다() {
		// 혼합 계정 검증 재료 — calm(뷰티 3건)에 F&B 분류 1건 추가. 축별 분모가 분리되지 않으면
		// F&B축 비중이 25%(전체 분류 4건 분모)로 나온다.
		jdbcTemplate.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at,
				  content_type, views, likes, comments, sponsored)
				VALUES ('c4', 'calm', now() - interval '13 days', 'reels', 20000, 400, 20, false)""");
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, is_beauty, main_category, sub_categories,
				  ad_type, detected_brands)
				VALUES ('c4', false, 'convenience', NULL, 'organic', NULL)""");

		var fnbShares = repository.findShares(List.of("glow", "calm", "fbfood"), true);
		// 뷰티 단독 glow는 F&B축에 행 없음, calm·fbfood는 F&B 분류분만 분모(각 100%)
		assertThat(fnbShares)
				.extracting(r -> r.accountHandle() + ":" + r.mainCategory() + ":" + r.pct())
				.containsExactly("calm:convenience:100", "fbfood:convenience:100");

		// 같은 계정의 뷰티축 비중은 F&B 게시물과 무관하게 유지된다
		assertThat(repository.findShares(List.of("calm"), false))
				.extracting(r -> r.mainCategory() + ":" + r.pct())
				.containsExactly("skincare:100");
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

	/**
	 * 분석 여부와 무관하게 썸네일에 담는다(2026-09-04 제품 결정 — #749의 "분석 완료만" 필터 원복,
	 * 인플루언서 상세 recentContents(6.4)와 동일 규칙). #749 당시 우려(6.3 hover 프리페치 404)는
	 * 콘텐츠 분석 2단계 분리로 6.3이 D+1부터 200을 반환하게 되어 해소됐다.
	 */
	@Test
	void 보강_썸네일은_분석_여부와_무관하게_담는다() {
		// g0: 창에는 있지만(미러 직후 최신 게시물) 아직 content_analyses가 없다 — 가장 최신이니 1번으로 담긴다.
		jdbcTemplate.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at,
				  content_type, views, likes, comments, sponsored)
				VALUES ('g0', 'glow', now() - interval '1 hour', 'reels', 100, 5, 1, false)
				""");
		jdbcTemplate.update(
				"INSERT INTO contents (short_code, caption, thumbnail_url) VALUES ('g0', '방금 올림', 'https://cdn/g0.jpg')");

		var glowThumbs = repository.findThumbs(List.of("glow"));

		assertThat(glowThumbs).hasSize(4);
		assertThat(glowThumbs).extracting(V1InfluencerDiscoveryRepository.ThumbRow::shortCode)
				.containsExactly("g0", "g1", "g2", "g3"); // 최신순 4개, g0 포함(미분석)·g4·g5는 상한에 밀림
		V1InfluencerDiscoveryRepository.ThumbRow g0 = glowThumbs.get(0);
		assertThat(g0.thumbnailUrl()).isEqualTo("https://cdn/g0.jpg"); // 아카이브 썸네일 없음 → contents 폴백
		assertThat(g0.mainCategory()).isNull(); // 미분석 → 카테고리 없음
		assertThat(g0.adType()).isEqualTo("organic"); // 미분석 → COALESCE 기본값
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
	void 공동구매_카운트는_contents_posted_at_기준_최근_12개_창_안에서만_verdict_true를_센다() {
		// gp는 contents에 14행이 있지만(gp1~gp14) 창은 posted_at DESC 상위 12개만
		// 본다(findRecentCards·6.4 recentContents와 같은 모수). gp2·gp4(창 안 verdict=true) 2건만
		// 세고, gp6(창 안이지만 verdict NULL — 미판정)·gp8(창 안이지만 판정 행 없음)·
		// gp13(verdict=true지만 rn=13으로 창 밖)은 전부 제외된다.
		var counts = repository.findGroupPurchaseCounts(List.of("gp"));
		assertThat(counts).hasSize(1);
		assertThat(counts.getFirst().accountHandle()).isEqualTo("gp");
		assertThat(counts.getFirst().count()).isEqualTo(2);
	}

	@Test
	void 판정_행이_전혀_없는_핸들은_0건이다() {
		// glow는 contents 창(g1~g5)이 있지만 group_purchase_judgments에는 한 행도 없다 —
		// LEFT JOIN이라 행 자체는 살아남고 FILTER(WHERE j.verdict)가 0을 만든다(신뢰성 우선).
		var counts = repository.findGroupPurchaseCounts(List.of("glow"));
		assertThat(counts).hasSize(1);
		assertThat(counts.getFirst().count()).isZero();
	}

	@Test
	void findCards의_totalCount는_countCards와_일치한다() {
		for (V1InfluencerDiscoveryQuery q : List.of(
				all(),
				query(null, "skincare", null, null, null, null, null, null, null, null, null),
				query(null, null, null, null, "10k-30k", null, null, null, null, null, null),
				query(null, null, null, null, null, null, "1-2", null, null, null, null))) {
			var rows = repository.findCards(q);
			if (!rows.isEmpty()) {
				assertThat(rows.getFirst().totalCount()).isEqualTo(repository.countCards(q));
			}
		}
	}

	@Test
	void totalCount는_LIMIT과_무관하게_필터_전체_건수다() {
		var rows = repository.findCards(query(null, null, null, null, null, null, null, null, null, 1, 0));
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().totalCount()).isEqualTo(repository.countCards(all()));
	}

	@Test
	void findCardsByHandles의_totalCount는_null이다() {
		var rows = repository.findCardsByHandles(List.of("glow"));
		assertThat(rows.getFirst().totalCount()).isNull();
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

	@Test
	void 창_전체가_분석_미판정이면_0으로_나누기_없이_통과한다() {
		// account_beauty_ratio는 content_analyses 조인 행이 1건이라도 있으면 그룹이 생긴다 — 창 내
		// 게시물 전부가 is_beauty IS NULL(캡션·썸네일 둘 다 없어 판정 불가)이면 행은 존재하되
		// analyzed_count=0이 된다. Postgres는 OR 단축 평가를 보장하지 않아 NULLIF 방어가 없으면
		// division by zero로 발굴 목록 전체가 500이 난다 — 예외 없이 조회되고, 표본 부족과 동일하게
		// 통과(포함)되는지 검증한다.
		seedBeautyRatioAccount("unjudged", 10, 0); // beautyCount=0이지만 핵심은 is_beauty 자체가 NULL
		jdbcTemplate.update("""
				UPDATE content_analyses SET is_beauty = NULL
				WHERE short_code IN (SELECT short_code FROM account_content_series
				                     WHERE account_handle = 'unjudged')""");

		List<String> handles = repository.findCards(all()).stream().map(CardRow::handle).toList();
		assertThat(handles).contains("unjudged");
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
				  avg_hype_score, avg_hype_raw, last_posted_at)
				VALUES (?, 5000, 10, 20, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, now())""", handle);
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
