package com.celfit.was.v1.saved;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.v1.content.ContentCard;
import com.celfit.was.v1.content.ContentCardRow;
import com.celfit.was.v1.saved.V1SavedRepository.ContentSave;
import com.celfit.was.v1.saved.V1SavedRepository.InfluencerSave;
import com.celfit.was.v1.saved.V1SavedRepository.SavedContentRow;
import com.celfit.was.v1.saved.V1SavedRepository.SavedInfluencerRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * app 저장(memo upsert·inserted 판별·순서)과 analysis 미러 IN 조회를 실측한다. app 테이블은 Flyway가
 * 세운 것(V1~V4)을 쓰고, analysis 미러(accounts·contents·content_analyses)는 Flyway 밖이라 수동 생성한다.
 */
class V1SavedRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V1SavedRepository repository;

	private long userId;

	@BeforeEach
	void setUp() {
		// analysis 미러 — V31 형상 사본(카드 SELECT가 참조하는 컬럼만).
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS contents");
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS image_assets");
		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE contents (
				    short_code         text PRIMARY KEY,
				    account_handle     text NOT NULL,
				    thumbnail_url      text,
				    caption            text,
				    posted_at          timestamptz,
				    content_type       text,
				    video_duration     numeric,
				    original_url       text,
				    views              bigint,
				    likes              bigint,
				    comments           bigint,
				    hype_score         bigint,
				    metric_captured_at timestamptz
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE content_analyses (
				    short_code            text PRIMARY KEY,
				    main_category         text,
				    sub_categories        jsonb,
				    ad_type               text,
				    detected_brands       jsonb,
				    detected_products     jsonb,
				    detected_distributors jsonb
				)""");
		// image_assets 사본 DDL (analytics 아카이브 잡이 채우는 테이블 — Task 2 DDL과 동일 형상)
		jdbcTemplate.execute("""
				CREATE TABLE image_assets (
				    kind        text NOT NULL,
				    key         text NOT NULL,
				    object_path text NOT NULL,
				    source_name text NOT NULL,
				    archived_at timestamptz NOT NULL DEFAULT now(),
				    PRIMARY KEY (kind, key)
				)""");
		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers) VALUES
				 ('alpha', '알파', 'https://pic/alpha.jpg', 5000),
				 ('beta', '베타', 'https://pic/beta.jpg', 20000)
				""");
		// c1·c2 분석 완료(카드), c3 미분석(카드 아님 — 존재 확인에서 제외)
		jdbcTemplate.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, posted_at, content_type,
				  video_duration, original_url, views, likes, comments, hype_score, metric_captured_at) VALUES
				 ('c1', 'alpha', 'https://thumb/c1.jpg', '카드1', '2026-07-02T03:00:00Z', 'reels',
				  20, 'https://ig/c1', 1000, 100, 10, 500, '2026-07-05T03:00:00Z'),
				 ('c2', 'beta', 'https://thumb/c2.jpg', '카드2', '2026-07-03T03:00:00Z', 'reels',
				  25, 'https://ig/c2', 3000, 300, 30, 900, '2026-07-06T03:00:00Z'),
				 ('c3', 'alpha', 'https://thumb/c3.jpg', '미분석', '2026-07-04T03:00:00Z', 'reels',
				  15, 'https://ig/c3', 9999, 999, 99, 999, '2026-07-07T03:00:00Z')
				""");
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, main_category, sub_categories, ad_type,
				  detected_brands, detected_products, detected_distributors) VALUES
				 ('c1', 'makeup', '["아이라이너"]'::jsonb, 'organic', NULL, NULL, NULL),
				 ('c2', 'skincare', '["토너"]'::jsonb, 'organic', NULL, NULL, NULL)
				""");

		// app 서비스 데이터 초기화 + 테스트 사용자 1명
		jdbcTemplate.update("DELETE FROM app.saved_contents");
		jdbcTemplate.update("DELETE FROM app.saved_influencers");
		jdbcTemplate.update("DELETE FROM app.users");
		userId = jdbcTemplate.queryForObject(
				"INSERT INTO app.users (email, password_hash) VALUES ('u@example.com', 'x') RETURNING id", Long.class);
	}

	/**
	 * 순서 단언을 위해 저장 시각을 과거로 명시 고정한다. 저장 간격을 벌리는 방식(sleep)은 쓰지 않는다 —
	 * now()는 벽시계라 컨테이너 시계가 뒤로 튀면(이 머신 실측: 약 118ms 역행이 주기적으로 발생) 나중
	 * 저장이 더 이른 시각으로 박혀 정렬이 뒤집힌다. 시각이 완전 동률일 때도 타이브레이크가
	 * short_code·handle 오름차순이라 "최근 저장 순" 기대와 반대로 나오는데, 1시간 마진은 두 경우를 다 막는다.
	 */
	private void 콘텐츠_저장시각_과거로(String shortCode) {
		jdbcTemplate.update(
				"UPDATE app.saved_contents SET created_at = now() - interval '1 hour' "
						+ "WHERE user_id = ? AND short_code = ?",
				userId, shortCode);
	}

	/** 위와 같은 이유. v1 인플루언서 목록은 updated_at이 아니라 created_at 기준이다. */
	private void 인플루언서_저장시각_과거로(String handle) {
		jdbcTemplate.update(
				"UPDATE app.saved_influencers SET created_at = now() - interval '1 hour' "
						+ "WHERE user_id = ? AND handle = ?",
				userId, handle);
	}

	// --- 콘텐츠 ---

	@Test
	void 신규_저장은_inserted_true고_공백_memo는_null로_저장된다() {
		ContentSave save = repository.upsertContent(userId, "c1", V1SavedAssembler.normalizeMemo("   "));

		assertThat(save.inserted()).isTrue();
		assertThat(save.memo()).isNull();
		assertThat(save.shortCode()).isEqualTo("c1");
	}

	@Test
	void 재저장은_inserted_false고_memo만_갱신하며_created_at은_유지한다() {
		ContentSave first = repository.upsertContent(userId, "c1", "처음");
		ContentSave second = repository.upsertContent(userId, "c1", "수정");

		assertThat(first.inserted()).isTrue();
		assertThat(second.inserted()).isFalse();
		assertThat(second.memo()).isEqualTo("수정");
		assertThat(second.createdAt()).isEqualTo(first.createdAt());
	}

	@Test
	void 저장_목록은_최근_저장_순이다() {
		repository.upsertContent(userId, "c1", null);
		repository.upsertContent(userId, "c2", "메모");
		콘텐츠_저장시각_과거로("c1");

		List<SavedContentRow> rows = repository.findSavedContents(userId);

		// c2가 나중 저장 → created_at DESC로 먼저
		assertThat(rows).extracting(SavedContentRow::shortCode).containsExactly("c2", "c1");
	}

	@Test
	void 카드_단건_조회는_분석_완료만_반환하고_미분석은_empty다() {
		assertThat(repository.findCard("c1")).isPresent();
		assertThat(repository.findCard("c3")).isEmpty(); // 미분석
		assertThat(repository.findCard("nope")).isEmpty();
	}

	@Test
	void 카드_IN_조회는_묶음을_한번에_반환한다() {
		List<ContentCardRow> cards = repository.findCards(List.of("c1", "c2", "c3"));

		// c3(미분석)은 조인에서 빠진다
		assertThat(cards).extracting(ContentCardRow::shortCode).containsExactlyInAnyOrder("c1", "c2");
	}

	@Test
	void 콘텐츠_삭제는_멱등이다() {
		repository.upsertContent(userId, "c1", null);
		repository.deleteContent(userId, "c1");
		repository.deleteContent(userId, "c1"); // 두 번째도 예외 없이 통과

		assertThat(repository.findSavedContents(userId)).isEmpty();
	}

	@Test
	void 콘텐츠_메모_수정은_저장된_행의_memo만_바꾸고_created_at은_유지한다() {
		ContentSave saved = repository.upsertContent(userId, "c1", "처음");

		Optional<SavedContentRow> updated = repository.updateContentMemo(userId, "c1", "수정됨");

		assertThat(updated).isPresent();
		assertThat(updated.get().memo()).isEqualTo("수정됨");
		assertThat(updated.get().createdAt()).isEqualTo(saved.createdAt());
	}

	@Test
	void 콘텐츠_메모_수정은_저장_안_된_행이면_empty다() {
		assertThat(repository.updateContentMemo(userId, "c1", "메모")).isEmpty(); // 저장 이력 없음
	}

	// --- 인플루언서 ---

	@Test
	void 인플루언서_신규_저장은_inserted_true고_재저장은_memo만_갱신한다() {
		InfluencerSave first = repository.upsertInfluencer(userId, "alpha", "1순위");
		InfluencerSave second = repository.upsertInfluencer(userId, "alpha", "보류");

		assertThat(first.inserted()).isTrue();
		assertThat(second.inserted()).isFalse();
		assertThat(second.memo()).isEqualTo("보류");
		assertThat(second.createdAt()).isEqualTo(first.createdAt());
	}

	@Test
	void 인플루언서_upsert는_status를_건드리지_않는다() {
		repository.upsertInfluencer(userId, "alpha", null); // 신규 → DEFAULT 'reviewing'
		jdbcTemplate.update("UPDATE app.saved_influencers SET status = 'collaborating' WHERE user_id = ? AND handle = ?",
				userId, "alpha");

		repository.upsertInfluencer(userId, "alpha", "메모 갱신"); // memo만 갱신, status 유지되어야

		String status = jdbcTemplate.queryForObject(
				"SELECT status FROM app.saved_influencers WHERE user_id = ? AND handle = ?", String.class,
				userId, "alpha");
		assertThat(status).isEqualTo("collaborating");
	}

	@Test
	void 인플루언서_프로필_조회는_존재_시_profile_부재_시_empty다() {
		Optional<ContentCard.Influencer> alpha = repository.findInfluencer("alpha");
		assertThat(alpha).isPresent();
		assertThat(alpha.get().handle()).isEqualTo("alpha");
		assertThat(alpha.get().displayName()).isEqualTo("알파");
		assertThat(alpha.get().followers()).isEqualTo(5000L);

		assertThat(repository.findInfluencer("ghost")).isEmpty();
	}

	@Test
	void 인플루언서_IN_조회는_존재하는_프로필만_반환한다() {
		List<ContentCard.Influencer> profiles = repository.findInfluencers(List.of("alpha", "beta", "ghost"));

		assertThat(profiles).extracting(ContentCard.Influencer::handle)
				.containsExactlyInAnyOrder("alpha", "beta");
	}

	@Test
	void 인플루언서_메모_수정은_저장된_행의_memo만_바꾸고_status와_created_at은_유지한다() {
		InfluencerSave saved = repository.upsertInfluencer(userId, "alpha", "처음");
		jdbcTemplate.update("UPDATE app.saved_influencers SET status = 'collaborating' WHERE user_id = ? AND handle = ?",
				userId, "alpha");

		Optional<SavedInfluencerRow> updated = repository.updateInfluencerMemo(userId, "alpha", "수정됨");

		assertThat(updated).isPresent();
		assertThat(updated.get().memo()).isEqualTo("수정됨");
		assertThat(updated.get().createdAt()).isEqualTo(saved.createdAt());
		String status = jdbcTemplate.queryForObject(
				"SELECT status FROM app.saved_influencers WHERE user_id = ? AND handle = ?", String.class,
				userId, "alpha");
		assertThat(status).isEqualTo("collaborating"); // memo 수정이 status를 건드리지 않는다
	}

	@Test
	void 인플루언서_메모_수정은_저장_안_된_행이면_empty다() {
		assertThat(repository.updateInfluencerMemo(userId, "alpha", "메모")).isEmpty(); // 저장 이력 없음
	}

	@Test
	void 인플루언서_목록은_최근_저장_순이고_삭제는_멱등이다() {
		repository.upsertInfluencer(userId, "alpha", null);
		repository.upsertInfluencer(userId, "beta", null);
		인플루언서_저장시각_과거로("alpha");

		List<SavedInfluencerRow> rows = repository.findSavedInfluencers(userId);
		assertThat(rows).extracting(SavedInfluencerRow::handle).containsExactly("beta", "alpha");

		repository.deleteInfluencer(userId, "beta");
		repository.deleteInfluencer(userId, "beta"); // 멱등
		assertThat(repository.findSavedInfluencers(userId)).extracting(SavedInfluencerRow::handle)
				.containsExactly("alpha");
	}

	@Test
	void 인플루언서_프로필_이미지는_아카이브되면_img_경로_아니면_원본() {
		// findInfluencer 단건: 원본 URL이 설정됨(아카이브 없음)
		ContentCard.Influencer alpha = repository.findInfluencer("alpha").orElseThrow();
		assertThat(alpha.profileImageUrl()).isEqualTo("https://pic/alpha.jpg");

		// alpha의 아카이브 추가 후 재조회 — COALESCE가 /img/ 경로 선택
		jdbcTemplate.update("""
				INSERT INTO image_assets (kind, key, object_path, source_name)
				VALUES ('profile', 'alpha', 'profile/alpha.jpg', 'alpha.jpg')
				""");

		alpha = repository.findInfluencer("alpha").orElseThrow();
		assertThat(alpha.profileImageUrl()).isEqualTo("/img/profile/alpha.jpg");

		// findInfluencers 묶음: alpha는 아카이브 있음, beta는 없음
		List<ContentCard.Influencer> profiles = repository.findInfluencers(List.of("alpha", "beta"));
		ContentCard.Influencer archivedAlpha = profiles.stream()
				.filter(p -> p.handle().equals("alpha")).findFirst().orElseThrow();
		ContentCard.Influencer originalBeta = profiles.stream()
				.filter(p -> p.handle().equals("beta")).findFirst().orElseThrow();

		assertThat(archivedAlpha.profileImageUrl()).isEqualTo("/img/profile/alpha.jpg");
		assertThat(originalBeta.profileImageUrl()).isEqualTo("https://pic/beta.jpg");
	}
}
