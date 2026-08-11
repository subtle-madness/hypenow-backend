package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandCommentRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostMetaRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandSnapshotRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 브랜드 조회 계층 검증 — monitoring DB 픽스처(monitoring-brand-schema.sql)를 테스트 DB에 적용하고
 * 직접 시드한다. 레거시 MonitoringReadRepositoryTest와 같은 관용구(전체 컨텍스트 DataSource +
 * ScriptUtils)를 따르되, 픽스처 스크립트만 브랜드 전용으로 분리했다.
 */
class BrandReadRepositoryTest extends IntegrationTest {

	@Autowired
	DataSource dataSource;

	JdbcClient jdbc;
	BrandReadRepository repository;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("""
				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
				         brand_post_comment, author_profile RESTART IDENTITY CASCADE
				""")
				.update();
		repository = new BrandReadRepository(jdbc);
	}

	long seedBrand(String username) {
		return jdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id, followers, following, media_count,
				                           biography, full_name, profile_pic_url, is_verified, external_url,
				                           status, last_swept_on, last_swept_at, backfill_completed_at)
				VALUES (:username, '17841400000000000', 12345, 42, 300, '브랜드 소개', '브랜드 공식',
				        'http://cdn/brand.jpg', true, 'https://brand.example', 'ACTIVE',
				        '2026-08-06', '2026-08-06T09:00:00+09:00', '2026-08-01T09:00:00+09:00')
				RETURNING id
				""")
				.param("username", username)
				.query(Long.class).single();
	}

	void seedTaggedPost(long brandId, String shortCode, String takenAt) {
		jdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id,
				                               taken_at, comments_collected_count)
				VALUES (:brandId, :shortCode, 'influencer_a', 'IG_A', :takenAt::timestamptz, 7)
				""")
				.param("brandId", brandId).param("shortCode", shortCode).param("takenAt", takenAt)
				.update();
	}

	@Test
	void 브랜드_계정은_계약_필드를_그대로_돌려준다() {
		long brandId = seedBrand("brand_official");

		Optional<BrandAccountRow> found = repository.findAccount(brandId);

		assertThat(found).isPresent();
		BrandAccountRow row = found.get();
		assertThat(row.id()).isEqualTo(brandId);
		assertThat(row.username()).isEqualTo("brand_official");
		assertThat(row.followers()).isEqualTo(12345L);
		assertThat(row.following()).isEqualTo(42L);
		assertThat(row.mediaCount()).isEqualTo(300L);
		assertThat(row.biography()).isEqualTo("브랜드 소개");
		assertThat(row.fullName()).isEqualTo("브랜드 공식");
		assertThat(row.profilePicUrl()).isEqualTo("http://cdn/brand.jpg");
		assertThat(row.isVerified()).isTrue();
		assertThat(row.externalUrl()).isEqualTo("https://brand.example");
		assertThat(row.status()).isEqualTo("ACTIVE");
		assertThat(row.lastSweptOn()).isEqualTo(LocalDate.of(2026, 8, 6));
		assertThat(row.lastSweptAt()).isNotNull();
		assertThat(row.registeredAt()).isNotNull();
		assertThat(row.backfillCompletedAt()).isNotNull();
		assertThat(row.backfillError()).isNull();
	}

	@Test
	void 없는_브랜드는_empty() {
		assertThat(repository.findAccount(999_999L)).isEmpty();
	}

	@Test
	void 백필_미완_계정은_완료시각_null과_오류문구가_같이_읽힌다() {
		long brandId = jdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id, backfill_error)
				VALUES ('brand_pending', 'IG_PENDING', '프로필 조회 실패: 429')
				RETURNING id
				""").query(Long.class).single();

		BrandAccountRow row = repository.findAccount(brandId).orElseThrow();

		assertThat(row.backfillCompletedAt()).isNull();
		assertThat(row.backfillError()).isEqualTo("프로필 조회 실패: 429");
		assertThat(row.lastSweptOn()).isNull();
		assertThat(row.status()).isEqualTo("ACTIVE");   // DDL 기본값
	}

	@Test
	void 윈도우_조회는_컷만_적용하고_상한_없이_최신순이다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(brandId, "OLD", now.minusDays(400).toString());   // 컷 밖
		seedTaggedPost(brandId, "MID", now.minusDays(100).toString());   // 구 90일 컷이면 잘렸을 행
		seedTaggedPost(brandId, "NEW", now.minusDays(1).toString());

		List<BrandTaggedPostRow> rows = repository.findTaggedPostsInWindow(brandId, now.minusDays(365));

		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(BrandTaggedPostRow::shortCode).containsExactly("NEW", "MID");
		assertThat(rows.get(0).takenAt()).isAfter(rows.get(1).takenAt());
		assertThat(rows.get(0).authorUsername()).isEqualTo("influencer_a");
		assertThat(rows.get(0).authorIgUserId()).isEqualTo("IG_A");
		assertThat(rows.get(0).commentsCollectedCount()).isEqualTo(7L);
		assertThat(rows.get(0).firstSeenAt()).isNotNull();
	}

	@Test
	void 윈도우_조회는_다른_브랜드_행을_섞지_않는다() {
		long mine = seedBrand("brand_mine");
		long other = seedBrand("brand_other");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(mine, "MINE1", now.minusDays(2).toString());
		seedTaggedPost(other, "OTHER1", now.minusDays(1).toString());

		List<BrandTaggedPostRow> rows = repository.findTaggedPostsInWindow(mine, now.minusDays(365));

		assertThat(rows).extracting(BrandTaggedPostRow::shortCode).containsExactly("MINE1");
	}

	@Test
	void 게시물_메타는_영상_협찬_필드까지_읽는다() {
		jdbc.sql("""
				INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption,
				                             thumbnail_url, video_url, video_duration, is_paid_partnership)
				VALUES ('SHORT1', 'influencer_a', 'REELS', '2026-08-01', '캡션1', 'http://cdn/1.jpg',
				        'http://cdn/1.mp4', 12.5, true),
				       ('SHORT2', 'influencer_b', 'FEED', '2026-08-02', '', NULL, NULL, NULL, NULL)
				""").update();

		List<BrandPostMetaRow> rows = repository.findPostMeta(List.of("SHORT1", "SHORT2", "MISSING"));

		assertThat(rows).hasSize(2);
		BrandPostMetaRow reels = rows.stream().filter(r -> r.shortCode().equals("SHORT1")).findFirst().orElseThrow();
		assertThat(reels.username()).isEqualTo("influencer_a");
		assertThat(reels.contentType()).isEqualTo("REELS");
		assertThat(reels.uploadedAt()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(reels.caption()).isEqualTo("캡션1");
		assertThat(reels.thumbnailUrl()).isEqualTo("http://cdn/1.jpg");
		assertThat(reels.videoUrl()).isEqualTo("http://cdn/1.mp4");
		assertThat(reels.videoDuration()).isEqualTo(12.5);
		assertThat(reels.isPaidPartnership()).isTrue();

		BrandPostMetaRow feed = rows.stream().filter(r -> r.shortCode().equals("SHORT2")).findFirst().orElseThrow();
		assertThat(feed.isPaidPartnership()).isNull();   // null = 키 부재(판정 unknown)
		assertThat(feed.videoDuration()).isNull();
	}

	@Test
	void 스냅샷은_날짜_오름차순이고_숨김_플래그와_null_지표가_보존된다() {
		// 역순 삽입 — ORDER BY가 없으면 물리 순서로 새는 것을 잡는다
		jdbc.sql("""
				INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type,
				                                 likes, likes_hidden, comments, views, fb_plays,
				                                 saves, shares, shares_hidden, reposts)
				VALUES ('influencer_a', 'SHORT1', '2026-08-03', 'REELS', 20, false, 2, 500, 100, 3, 4, false, 1),
				       ('influencer_a', 'SHORT1', '2026-08-02', 'REELS', 10, false, 1, 300, 50, 2, 3, false, 0),
				       ('influencer_b', 'SHORT2', '2026-08-02', 'FEED', NULL, true, 5, NULL, NULL, 1, NULL, true, NULL)
				""").update();

		List<BrandSnapshotRow> rows = repository.findSnapshots(List.of("SHORT1", "SHORT2"));

		assertThat(rows).hasSize(3);
		List<BrandSnapshotRow> short1 = rows.stream().filter(r -> r.shortCode().equals("SHORT1")).toList();
		assertThat(short1).extracting(BrandSnapshotRow::capturedOn)
				.containsExactly(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3));
		assertThat(short1.get(0).likes()).isEqualTo(10L);
		assertThat(short1.get(0).views()).isEqualTo(300L);   // DDL상 이미 IG+FB 합산값 — fb_plays 별도 조회 없음

		BrandSnapshotRow hidden = rows.stream().filter(r -> r.shortCode().equals("SHORT2")).findFirst().orElseThrow();
		assertThat(hidden.likes()).isNull();
		assertThat(hidden.likesHidden()).isTrue();
		assertThat(hidden.shares()).isNull();
		assertThat(hidden.sharesHidden()).isTrue();
		assertThat(hidden.views()).isNull();   // 피드 조회수 NULL 규칙
	}

	@Test
	void 댓글은_게시물별_최신순이다() {
		jdbc.sql("""
				INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at, owner_reply_text)
				VALUES ('SHORT1', 'c1', 'user_a', '본문1', 1, '2026-08-01T00:00:00+09:00', NULL),
				       ('SHORT1', 'c2', 'user_b', '본문2', 2, '2026-08-02T00:00:00+09:00', '브랜드 답글'),
				       ('SHORT1', 'c3', 'user_c', '본문3', 3, '2026-08-03T00:00:00+09:00', NULL)
				""").update();

		List<BrandCommentRow> rows = repository.findComments(List.of("SHORT1"), 8);

		assertThat(rows).extracting(BrandCommentRow::id).containsExactly("c3", "c2", "c1");
		assertThat(rows.get(1).ownerReplyText()).isEqualTo("브랜드 답글");
		assertThat(rows.get(0).likeCount()).isEqualTo(3L);
	}

	/**
	 * 댓글은 삭제 없는 누적 합집합이라 인기 게시물은 수천 행까지 간다 — 상한은 SQL 단계에서 잘려야
	 * 하고(힙에 올리지 않는다), 잘리는 쪽은 항상 오래된 쪽이어야 한다.
	 */
	@Test
	void 댓글은_게시물별_상한을_넘으면_오래된_쪽부터_잘린다() {
		// 게시물 2개 × 상한(2)+1건 — 상한이 게시물별로 독립 적용되는지까지 같이 잡는다
		jdbc.sql("""
				INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at)
				VALUES ('SHORT1', 'a1', 'user_a', '본문', 1, '2026-08-01T00:00:00+09:00'),
				       ('SHORT1', 'a2', 'user_b', '본문', 1, '2026-08-02T00:00:00+09:00'),
				       ('SHORT1', 'a3', 'user_c', '본문', 1, '2026-08-03T00:00:00+09:00'),
				       ('SHORT2', 'b1', 'user_d', '본문', 1, '2026-08-01T00:00:00+09:00'),
				       ('SHORT2', 'b2', 'user_e', '본문', 1, '2026-08-02T00:00:00+09:00'),
				       ('SHORT2', 'b3', 'user_f', '본문', 1, '2026-08-03T00:00:00+09:00')
				""").update();

		List<BrandCommentRow> rows = repository.findComments(List.of("SHORT1", "SHORT2"), 2);

		assertThat(rows).hasSize(4);   // 게시물당 2건씩 — 상한은 전체가 아니라 게시물별
		assertThat(rows).extracting(BrandCommentRow::id).containsExactly("a3", "a2", "b3", "b2");
	}

	@Test
	void 게시자_프로필은_id와_username_두_경로로_조회된다() {
		jdbc.sql("""
				INSERT INTO author_profile (ig_user_id, username, full_name, followers, profile_pic_url,
				                            is_verified, fetched_at)
				VALUES ('IG_A', 'influencer_a', '인플루언서 A', 5000, 'http://cdn/a.jpg', true, now()),
				       ('IG_B', 'influencer_b', NULL, NULL, NULL, NULL, now())
				""").update();

		List<AuthorRow> byId = repository.findAuthors(List.of("IG_A", "IG_MISSING"));
		assertThat(byId).hasSize(1);
		assertThat(byId.get(0).username()).isEqualTo("influencer_a");
		assertThat(byId.get(0).fullName()).isEqualTo("인플루언서 A");
		assertThat(byId.get(0).followers()).isEqualTo(5000L);
		assertThat(byId.get(0).isVerified()).isTrue();

		List<AuthorRow> byUsername = repository.findAuthorsByUsername(List.of("influencer_b"));
		assertThat(byUsername).hasSize(1);
		assertThat(byUsername.get(0).igUserId()).isEqualTo("IG_B");
		assertThat(byUsername.get(0).followers()).isNull();
	}

	/** username은 author_profile의 유니크 키가 아니다(PK는 ig_user_id) — 폴백 조회는 계정당 1행이어야 한다. */
	@Test
	void username_폴백_조회는_같은_이름이_여러_행이어도_최신_1행만_준다() {
		jdbc.sql("""
				INSERT INTO author_profile (ig_user_id, username, followers, fetched_at)
				VALUES ('IG_OLD', 'influencer_a', 100, '2026-07-01T00:00:00+09:00'),
				       ('IG_NEW', 'influencer_a', 900, '2026-08-01T00:00:00+09:00')
				""").update();

		List<AuthorRow> rows = repository.findAuthorsByUsername(List.of("influencer_a"));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).igUserId()).isEqualTo("IG_NEW");
		assertThat(rows.get(0).followers()).isEqualTo(900L);
	}

	/**
	 * fetched_at 동점 타이브레이크 — 같은 스윕 트랜잭션에서 적재된 행은 now()가 불변이라 시각이
	 * 같을 수 있다. 그때도 어느 행이 남는지가 결정적이어야 한다(ig_user_id DESC 고정).
	 */
	@Test
	void username_폴백_조회는_fetched_at_동점에도_결정적이다() {
		jdbc.sql("""
				INSERT INTO author_profile (ig_user_id, username, followers, fetched_at)
				VALUES ('IG_1', 'influencer_a', 100, '2026-08-01T00:00:00+09:00'),
				       ('IG_2', 'influencer_a', 900, '2026-08-01T00:00:00+09:00')
				""").update();

		List<AuthorRow> rows = repository.findAuthorsByUsername(List.of("influencer_a"));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).igUserId()).isEqualTo("IG_2");   // 동점 시 ig_user_id DESC
	}

	@Test
	void 빈_컬렉션은_빈_결과다() {
		assertThat(repository.findPostMeta(List.of())).isEmpty();
		assertThat(repository.findSnapshots(List.of())).isEmpty();
		assertThat(repository.findComments(List.of(), 8)).isEmpty();
		assertThat(repository.findAuthors(List.of())).isEmpty();
		assertThat(repository.findAuthorsByUsername(List.of())).isEmpty();
	}
}
