package com.celfit.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class InfluencerMigrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateWithFixture() {
        DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        jdbc = new JdbcTemplate(ds);

        // 1) V7까지 적용 (구스키마)
        Flyway.configure().dataSource(ds).target("7").load().migrate();

        // 2) 구스키마 픽스처 — 카테고리/키워드/계정/콘텐츠/프로필
        jdbc.update("INSERT INTO category(id, name, enabled) VALUES (2, '뷰티', true)");
        jdbc.update("""
            INSERT INTO category_keyword(category_id, keyword, enabled, subcategory, main_group)
            VALUES (2, '쿠션', true, '베이스메이크업', '메이크업'),
                   (2, '립밤', false, '립메이크업', '메이크업')""");
        jdbc.update("INSERT INTO collection_rule(category_id, min_followers, max_followers, content_types) VALUES (2, 3000, 50000, 'ALL')");
        jdbc.update("INSERT INTO account(id, username, last_profiled_at) VALUES (10, 'alice', now()), (11, 'bob', NULL)");
        jdbc.update("""
            INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id,
                                discovery_keyword, subcategory, main_group, status, first_seen_at,
                                aggregated_at, aggregate_attempts, ad_marked)
            VALUES (100, 'SC_A', 'REELS', 'alice', now(), 2, '쿠션', '베이스메이크업', '메이크업',
                    'AGGREGATED', now(), now(), 1, true),
                   (101, 'SC_B', 'FEED', 'bob', now(), 2, '립밤', '립메이크업', '메이크업',
                    'EXCLUDED', now(), NULL, 0, false)""");
        jdbc.update("""
            INSERT INTO crawl_run(id, job, trigger_type, category_id, keyword, actor_id, status, started_at)
            VALUES (500, 'QUALIFY', 'MANUAL', 2, NULL, 'test-actor', 'SUCCEEDED', now())""");
        jdbc.update("""
            INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at)
            VALUES (10, 500, '{"username":"alice","followersCount":12345,"userId":"999"}'::jsonb, now())""");

        // 3) V9까지 적용 후 신스키마 픽스처 — SELF_GQL 프로필(bob). V10의 ig_user_id 백필이
        //    LEGACY_ENVELOPE(alice)·SELF_GQL(bob) 양쪽 추출 경로를 타는지 보기 위함.
        Flyway.configure().dataSource(ds).target("9").load().migrate();
        jdbc.update("""
            INSERT INTO raw_profile(influencer_id, crawl_run_id, payload, captured_at, source)
            VALUES (11, 500, '{"data":{"user":{"id":"777","username":"bob"}}}'::jsonb, now(), 'SELF_GQL')""");

        // 4) 최신까지 적용
        Flyway.configure().dataSource(ds).load().migrate();
    }

    @Test
    void 키워드가_평탄화되어_이관된다() {
        assertThat(jdbc.queryForList("SELECT keyword FROM search_keyword ORDER BY keyword", String.class))
                .containsExactly("립밤", "쿠션");
        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM search_keyword WHERE keyword='립밤'", Boolean.class)).isFalse();
    }

    @Test
    void account가_influencer로_개명되고_팔로워가_복사된다() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM influencer", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM influencer WHERE username='alice'", String.class)).isEqualTo("DISCOVERED");
        assertThat(jdbc.queryForObject(
                "SELECT followers FROM influencer WHERE username='alice'", Long.class)).isEqualTo(12345L);
        assertThat(jdbc.queryForObject(
                "SELECT followers FROM influencer WHERE username='bob'", Long.class)).isNull();
    }

    @Test
    void 발굴_출처가_content에서_역산된다() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM influencer_discovery", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT keyword FROM influencer_discovery d
                JOIN influencer i ON i.id = d.influencer_id WHERE i.username='alice'""", String.class))
                .isEqualTo("쿠션");
    }

    @Test
    void content가_인플루언서_소유로_재편되고_상태가_재매핑된다() {
        assertThat(jdbc.queryForObject(
                "SELECT status FROM content WHERE short_code='SC_A'", String.class)).isEqualTo("COLLECTED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM content WHERE short_code='SC_B'", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT i.username FROM content c JOIN influencer i ON i.id = c.influencer_id
                WHERE c.short_code='SC_A'""", String.class)).isEqualTo("alice");
        // 분류 컬럼·ad_marked 제거 확인
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name='content' AND column_name IN
                ('category_id','discovery_keyword','subcategory','main_group','ad_marked','qualified_at')""",
                Long.class)).isZero();
    }

    @Test
    void raw_테이블에_source가_붙고_generated가_실컬럼이_된다() {
        assertThat(jdbc.queryForObject(
                "SELECT source FROM raw_profile LIMIT 1", String.class)).isEqualTo("LEGACY_ENVELOPE");
        assertThat(jdbc.queryForObject(
                "SELECT followers FROM raw_profile LIMIT 1", Long.class)).isEqualTo(12345L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name='raw_profile' AND column_name='followers' AND is_generated='ALWAYS'""",
                Long.class)).isZero();
    }

    @Test
    void content_origin이_이관행은_DISCOVERY로_채워지고_컬럼은_NOT_NULL_default_없음이다() {
        assertThat(jdbc.queryForObject(
                "SELECT origin FROM content WHERE short_code='SC_A'", String.class)).isEqualTo("DISCOVERY");
        assertThat(jdbc.queryForObject(
                "SELECT origin FROM content WHERE short_code='SC_B'", String.class)).isEqualTo("DISCOVERY");
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable = 'NO' AND column_default IS NULL
                FROM information_schema.columns
                WHERE table_name='content' AND column_name='origin'""", Boolean.class)).isTrue();
    }

    @Test
    void ig_user_id가_최신_raw_profile_원형에서_소스별_경로로_백필된다() {
        assertThat(jdbc.queryForObject(
                "SELECT ig_user_id FROM influencer WHERE username='alice'", String.class))
                .isEqualTo("999");   // LEGACY_ENVELOPE: payload->>'userId'
        assertThat(jdbc.queryForObject(
                "SELECT ig_user_id FROM influencer WHERE username='bob'", String.class))
                .isEqualTo("777");   // SELF_GQL: data.user.id
    }

    @Test
    void 구_테이블은_삭제되고_raw_media_page가_생긴다() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_name IN ('category','category_keyword','collection_rule')""", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables WHERE table_name='raw_media_page'""",
                Long.class)).isEqualTo(1);
    }
}
