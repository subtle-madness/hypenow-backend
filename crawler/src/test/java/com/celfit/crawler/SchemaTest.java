package com.celfit.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional  // 직접 insert가 롤백되도록 — 다른 테스트 클래스와 DB 공유(싱글턴 컨테이너)
class SchemaTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void 전체_테이블이_생성된다() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);
        assertThat(tables).contains(
                "search_keyword", "influencer", "influencer_discovery",
                "content", "crawl_run", "raw_discovery_post",
                "raw_comment", "raw_profile", "raw_media_page", "app_setting", "raw_run_item",
                "content_caption");
        // V8에서 인플루언서 중심으로 개편되며 카테고리 체계는 완전히 걷어냈다.
        assertThat(tables).doesNotContain("category", "category_keyword", "collection_rule", "account");
        // raw_post_detail은 구 파이프라인 상세 payload — 07-22 접근 코드 삭제 후 V24에서 제거했다.
        assertThat(tables).doesNotContain("raw_post_detail");
    }

    /**
     * V8 전까지 raw_comment.writer/text/written_at 등은 payload에서 뽑아내는 generated column이었다.
     * V8에서 실컬럼으로 바뀌어 애플리케이션이 직접 채워야 한다 — payload만 넣으면 NULL로 남고,
     * (generated 컬럼과 달리) UPDATE로 직접 값을 넣을 수 있음을 확인한다.
     */
    @Test
    void raw_comment의_writer는_더이상_generated_컬럼이_아니라_실컬럼이다() {
        jdbc.update("insert into influencer(username) values ('owner')");
        Long influencerId = jdbc.queryForObject("select id from influencer where username='owner'", Long.class);
        jdbc.update("""
                insert into content(short_code, content_type, owner_username, influencer_id,
                                    uploaded_at, status, first_seen_at, origin)
                values ('abc123', 'REELS', 'owner', ?, now(), 'PENDING', now(), 'ENUMERATION')""", influencerId);
        Long contentId = jdbc.queryForObject("select id from content where short_code='abc123'", Long.class);
        jdbc.update("""
                insert into crawl_run(job, trigger_type, actor_id, status, started_at)
                values ('COLLECT', 'MANUAL', 'a', 'RUNNING', now())""");
        Long runId = jdbc.queryForObject("select max(id) from crawl_run", Long.class);

        jdbc.update("""
                insert into raw_comment(content_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'APIFY_ACTOR', '{"ownerUsername":"kim","text":"좋아요!"}'::jsonb, now())""",
                contentId, runId);
        Long commentId = jdbc.queryForObject(
                "select id from raw_comment where content_id = ?", Long.class, contentId);
        String writerAfterInsert = jdbc.queryForObject(
                "select writer from raw_comment where id = ?", String.class, commentId);
        assertThat(writerAfterInsert).isNull();  // payload에서 자동 추출되지 않는다 — 애플리케이션이 채워야 함

        jdbc.update("update raw_comment set writer = ? where id = ?", "kim", commentId);
        String writerAfterUpdate = jdbc.queryForObject(
                "select writer from raw_comment where id = ?", String.class, commentId);
        assertThat(writerAfterUpdate).isEqualTo("kim");  // generated 컬럼이면 이 UPDATE 자체가 실패한다
    }
}
