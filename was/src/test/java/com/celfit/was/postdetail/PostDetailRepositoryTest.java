package com.celfit.was.postdetail;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PostDetailRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PostDetailRepository repository;

	@BeforeEach
	void setUpTables() {
		// analytics의 V1__serving_tables.sql과 동일 형상 (컬럼 계약: 뷰 = DDL = record)
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS contents");
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_comments");
		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE contents (
				    short_code     text PRIMARY KEY,
				    account_handle text NOT NULL,
				    thumbnail_url  text,
				    caption        text,
				    posted_at      timestamptz,
				    content_type   text,
				    video_duration numeric,
				    original_url   text,
				    views          bigint,
				    likes          bigint,
				    comments       bigint,
				    hype_score     bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE content_comments (
				    id            bigint PRIMARY KEY,
				    short_code    text NOT NULL,
				    author_masked text,
				    body          text,
				    like_count    bigint
				)""");
		jdbcTemplate.update("""
				INSERT INTO accounts VALUES ('marimood', '마리 MARI', 'https://pic/mari.jpg', 16586)
				""");
		jdbcTemplate.update("""
				INSERT INTO contents VALUES
				 ('mari01', 'marimood', 'https://thumb/mari01.jpg', '쿨톤 여름 침착 조합',
				  '2026-06-28T00:00:00Z', 'reels', 18.0, 'https://www.instagram.com/p/mari01/',
				  1911943, 32969, 488, 1911943),
				 ('mari02', 'marimood', 'https://thumb/mari02.jpg', '피드 게시물',
				  '2026-07-01T00:00:00Z', 'feed', NULL, 'https://www.instagram.com/p/mari02/',
				  NULL, 2000, 100, 2100)
				""");
		jdbcTemplate.update("""
				INSERT INTO content_comments VALUES
				 (1, 'mari01', 'hye***', '이거 어디서 살 수 있어요??', 342),
				 (2, 'mari01', 'min***', '건성인데 자극 없을까요??', 214),
				 (3, 'mari01', 'seo***', '언니 피부 미쳤다', 289)
				""");
	}

	@Test
	void shortCode로_콘텐츠_1건을_계약_record로_읽는다() {
		Optional<Content> found = repository.findContent("mari01");

		assertThat(found).isPresent();
		Content content = found.get();
		assertThat(content.accountHandle()).isEqualTo("marimood");
		assertThat(content.views()).isEqualTo(1911943L);
		assertThat(content.postedAt()).isNotNull();
		assertThat(content.hypeScore()).isEqualTo(1911943L);
	}

	@Test
	void 없는_shortCode면_empty를_반환한다() {
		assertThat(repository.findContent("nope")).isEmpty();
	}

	@Test
	void handle로_계정을_읽는다() {
		Optional<Account> found = repository.findAccount("marimood");

		assertThat(found).isPresent();
		assertThat(found.get().displayName()).isEqualTo("마리 MARI");
		assertThat(found.get().followers()).isEqualTo(16586L);
	}

	@Test
	void 댓글은_좋아요_내림차순으로_전부_읽는다() {
		List<ContentComment> comments = repository.findComments("mari01");

		assertThat(comments).hasSize(3);
		assertThat(comments).extracting(ContentComment::likeCount)
				.containsExactly(342L, 289L, 214L);
		assertThat(comments.getFirst().authorMasked()).isEqualTo("hye***");
	}

	@Test
	void 미러_테이블이_없으면_빈_값으로_저하한다() {
		jdbcTemplate.execute("DROP TABLE contents");
		jdbcTemplate.execute("DROP TABLE accounts");
		jdbcTemplate.execute("DROP TABLE content_comments");

		assertThat(repository.findContent("mari01")).isEmpty();
		assertThat(repository.findAccount("marimood")).isEmpty();
		assertThat(repository.findComments("mari01")).isEmpty();
	}
}
