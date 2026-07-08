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
                "category", "category_keyword", "collection_rule",
                "account", "content", "crawl_run",
                "raw_discovery_post", "raw_post_detail", "raw_comment", "raw_profile");
    }

    @Test
    void generated_column이_동작한다() {
        jdbc.update("insert into category(name) values ('테스트')");
        Long catId = jdbc.queryForObject("select id from category where name='테스트'", Long.class);
        jdbc.update("""
                insert into content(short_code, content_type, owner_username, uploaded_at,
                                    category_id, discovery_keyword, status, first_seen_at)
                values ('abc123', 'REELS', 'tester', now(), ?, '테스트', 'PENDING', now())""", catId);
        Long contentId = jdbc.queryForObject("select id from content where short_code='abc123'", Long.class);
        jdbc.update("""
                insert into crawl_run(job, trigger_type, actor_id, status, started_at)
                values ('DISCOVER', 'MANUAL', 'a', 'RUNNING', now())""");
        Long runId = jdbc.queryForObject("select max(id) from crawl_run", Long.class);
        jdbc.update("""
                insert into raw_comment(content_id, crawl_run_id, payload, captured_at)
                values (?, ?, '{"ownerUsername":"kim","text":"좋아요!"}'::jsonb, now())""",
                contentId, runId);
        String writer = jdbc.queryForObject(
                "select writer from raw_comment where content_id = ?", String.class, contentId);
        assertThat(writer).isEqualTo("kim");
    }
}
