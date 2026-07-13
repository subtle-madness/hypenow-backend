package com.celfit.was.postdetail;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.was.IntegrationTest;
import java.math.BigDecimal;
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
				 (3, 'mari01', 'seo***', '언니 피부 미쳤다', 289),
				 (4, 'mari01', 'nul***', '좋아요 수 없음', NULL),
				 (5, 'mari01', 'tie***', '동률 테스트', 289)
				""");
		jdbcTemplate.execute("DROP TABLE IF EXISTS comment_classifications");
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("""
				CREATE TABLE comment_classifications (
				    id            bigint PRIMARY KEY,
				    short_code    text   NOT NULL,
				    ai_category   text   NOT NULL,
				    model         text   NOT NULL,
				    classified_at timestamptz NOT NULL DEFAULT now()
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE content_analyses (
				    short_code                   text PRIMARY KEY,
				    analyzed_at                  timestamptz NOT NULL DEFAULT now(),
				    model                        text NOT NULL,
				    ai_content_summary           text,
				    contents_pattern             text,
				    ai_comment_insight           text,
				    recent_reels_avg_views       bigint,
				    rank_in_recent_reels         smallint,
				    recent_reels_count           smallint,
				    recent_contents_count        smallint,
				    recent12_avg_engagement_rate numeric,
				    recent12_avg_like_count      bigint,
				    recent12_avg_comment_count   bigint,
				    category_top_percentile      smallint,
				    category_avg_views           bigint,
				    category_sample_size         bigint,
				    detected_brands              jsonb,
				    sponsored_signal_level       text,
				    sponsored_signal_reasons     jsonb,
				    ad_disclosure                text,
				    detected_product_categories  jsonb,
				    vlm_attributes               jsonb,
				    main_category                text,
				    sub_categories               jsonb,
				    ad_type                      text,
				    comment_authenticity_grade   text,
				    comment_authenticity_note    text
				)""");
		jdbcTemplate.update("""
				INSERT INTO comment_classifications(id, short_code, ai_category, model) VALUES
				 (1, 'mari01', 'purchase', 'test-model'),
				 (3, 'mari01', 'positive', 'test-model')
				""");
		jdbcTemplate.update("""
				INSERT INTO content_analyses(short_code, model, ai_content_summary, contents_pattern,
				  ai_comment_insight, recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				  recent_contents_count, recent12_avg_engagement_rate, recent12_avg_like_count,
				  recent12_avg_comment_count, category_top_percentile, category_avg_views,
				  category_sample_size, detected_brands, sponsored_signal_level, sponsored_signal_reasons,
				  ad_disclosure, detected_product_categories, vlm_attributes, main_category,
				  sub_categories, ad_type, comment_authenticity_grade, comment_authenticity_note) VALUES
				 ('mari01', 'test-model', '본인 평균 대비 3.1배 터진 콘텐츠', '실연형에서 터지는 패턴',
				  '구매 전환형 반응', 608899, 1, 7,
				  12, 0.0380, 25574,
				  3653, 2, 340000,
				  880, '[{"name":"Thelavicos","evidence":"라벨 정면 반복 노출"}]'::jsonb, 'high',
				  '["단일 브랜드 반복 클로즈업"]'::jsonb,
				  '캡션 #협찬 표기 있음', '["클렌징"]'::jsonb,
				  '[{"label":"후킹 요소","value":"문제 제기형 자막"}]'::jsonb, '클렌징',
				  '["필링·각질"]'::jsonb, 'sponsored', 'high', '진정성 높음')
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
	void 댓글은_좋아요_내림차순으로_전부_읽고_분류를_함께_싣는다() {
		List<CommentRow> comments = repository.findComments("mari01");

		assertThat(comments).hasSize(5);
		assertThat(comments).extracting(CommentRow::likeCount)
				.containsExactly(342L, 289L, 289L, 214L, null);
		// like_count 동률(289)은 id 오름차순: 3번(seo***) → 5번(tie***)
		assertThat(comments).extracting(CommentRow::id)
				.containsExactly(1L, 3L, 5L, 2L, 4L);
		assertThat(comments.getFirst().authorMasked()).isEqualTo("hye***");
		// 분류: id 1=purchase, 3=positive, 나머지는 미분류 null
		assertThat(comments).extracting(CommentRow::aiCategory)
				.containsExactly("purchase", "positive", null, null, null);
	}

	@Test
	void shortCode로_분석_1행을_읽는다() {
		Optional<ContentAnalysisRow> found = repository.findAnalysis("mari01");

		assertThat(found).isPresent();
		ContentAnalysisRow row = found.get();
		assertThat(row.aiContentSummary()).isEqualTo("본인 평균 대비 3.1배 터진 콘텐츠");
		assertThat(row.recentReelsAvgViews()).isEqualTo(608899L);
		assertThat(row.rankInRecentReels()).isEqualTo(1);
		assertThat(row.recent12AvgEngagementRate()).isEqualByComparingTo(new BigDecimal("0.0380"));
		assertThat(row.categoryTopPercentile()).isEqualTo(2);
		assertThat(row.detectedBrandsJson()).contains("Thelavicos");
		assertThat(row.vlmAttributesJson()).contains("후킹 요소");
		assertThat(row.adType()).isEqualTo("sponsored");
		assertThat(row.commentAuthenticityGrade()).isEqualTo("high");
		assertThat(row.analyzedAt()).isNotNull();
	}

	@Test
	void 미분석_콘텐츠는_분석이_empty다() {
		assertThat(repository.findAnalysis("mari02")).isEmpty();
	}

	@Test
	void 피드는_views가_null로_매핑된다() {
		Optional<Content> found = repository.findContent("mari02");

		assertThat(found).isPresent();
		assertThat(found.get().views()).isNull();
		assertThat(found.get().contentType()).isEqualTo("feed");
		assertThat(found.get().hypeScore()).isEqualTo(2100L);
	}

	@Test
	void 미러_테이블이_없으면_빈_값으로_저하한다() {
		jdbcTemplate.execute("DROP TABLE contents");
		jdbcTemplate.execute("DROP TABLE accounts");
		jdbcTemplate.execute("DROP TABLE content_comments");
		jdbcTemplate.execute("DROP TABLE comment_classifications");
		jdbcTemplate.execute("DROP TABLE content_analyses");

		assertThat(repository.findContent("mari01")).isEmpty();
		assertThat(repository.findAccount("marimood")).isEmpty();
		assertThat(repository.findComments("mari01")).isEmpty();
		assertThat(repository.findAnalysis("mari01")).isEmpty();
	}
}
