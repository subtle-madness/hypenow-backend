package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandCommentRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandHashtagPostRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostMetaRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandSnapshotRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
				         brand_post_comment, author_profile, brand_hashtag_post, brand_hashtag_exclusion,
				         brand_seeded_account
				         RESTART IDENTITY CASCADE
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

	/** 보강 정산이 끝난 게시물(enriched_at 기입) — 목록에 노출되는 정상 상태. tag_detected_at도 채운다(태그 산지). */
	void seedTaggedPost(long brandId, String shortCode, String takenAt) {
		jdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id,
				                               taken_at, comments_collected_count, enriched_at, tag_detected_at)
				VALUES (:brandId, :shortCode, 'influencer_a', 'IG_A', :takenAt::timestamptz, 7, now(), now())
				""")
				.param("brandId", brandId).param("shortCode", shortCode).param("takenAt", takenAt)
				.update();
	}

	/** 보강 정산 전 게시물(enriched_at NULL) — 열거만 되고 게시자·댓글이 아직 안 붙은 상태. */
	void seedUnenrichedTaggedPost(long brandId, String shortCode, String takenAt) {
		jdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id,
				                               taken_at, comments_collected_count, enriched_at, tag_detected_at)
				VALUES (:brandId, :shortCode, 'influencer_a', 'IG_A', :takenAt::timestamptz, 0, NULL, now())
				""")
				.param("brandId", brandId).param("shortCode", shortCode).param("takenAt", takenAt)
				.update();
	}

	/**
	 * direct 등록만 된 게시물(2026-08-18 direct 통합) — tag_detected_at 명시 NULL(컬럼 자체의
	 * DEFAULT now()를 무력화, 열 목록 생략만으로는 DEFAULT가 채워진다), direct_registered_at 채움.
	 */
	void seedDirectPost(long brandId, String shortCode, String takenAt, String registeredAt) {
		jdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id,
				                               taken_at, comments_collected_count, enriched_at,
				                               tag_detected_at, direct_registered_at)
				VALUES (:brandId, :shortCode, 'influencer_a', 'IG_A', :takenAt::timestamptz, 0, now(),
				        NULL, :registeredAt::timestamptz)
				""")
				.param("brandId", brandId).param("shortCode", shortCode).param("takenAt", takenAt)
				.param("registeredAt", registeredAt)
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
		// 커버리지 2컬럼(수집 상한 v2 §7-1) — 시드가 명시하지 않은 행은 DDL 기본값(완주 = 창 전체 커버).
		assertThat(row.collectionCapped()).isFalse();
		assertThat(row.coveredUntil()).isNull();
	}

	/** 상한 도달로 끊긴 백필은 capped=true + covered_until(실수집 깊이)이 그대로 읽힌다(§7-1). */
	@Test
	void 상한_도달_계정은_커버리지_컬럼이_읽힌다() {
		long brandId = jdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id, collection_capped, covered_until)
				VALUES ('brand_capped', 'IG_CAPPED', true, '2026-05-02T12:00:00+09:00')
				RETURNING id
				""").query(Long.class).single();

		BrandAccountRow row = repository.findAccount(brandId).orElseThrow();

		assertThat(row.collectionCapped()).isTrue();
		// 드라이버가 돌려주는 오프셋(UTC/세션 존)에 흔들리지 않게 순간(Instant)으로 비교한다.
		assertThat(row.coveredUntil().toInstant()).isEqualTo(Instant.parse("2026-05-02T03:00:00Z"));
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
		assertThat(row.collectionMonths()).isEqualTo(12);   // DDL 기본값(스펙 2026-08-12)
		// collection_started_at 미기입 행은 registered_at으로 접힌다(COALESCE) — 폴링 앵커가 비지 않는다.
		assertThat(row.collectionStartedAt()).isEqualTo(row.registeredAt());
	}

	@Test
	void 윈도우_조회는_컷만_적용하고_상한_없이_최신순이다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(brandId, "OLD", now.minusDays(400).toString());   // 컷 밖
		seedTaggedPost(brandId, "MID", now.minusDays(100).toString());   // 구 90일 컷이면 잘렸을 행
		seedTaggedPost(brandId, "NEW", now.minusDays(1).toString());

		List<BrandTaggedPostRow> rows = repository.findBrandPostsInWindow(brandId, now.minusDays(365), false);

		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(BrandTaggedPostRow::shortCode).containsExactly("NEW", "MID");
		assertThat(rows.get(0).takenAt()).isAfter(rows.get(1).takenAt());
		assertThat(rows.get(0).authorUsername()).isEqualTo("influencer_a");
		assertThat(rows.get(0).authorIgUserId()).isEqualTo("IG_A");
		assertThat(rows.get(0).commentsCollectedCount()).isEqualTo(7L);
		assertThat(rows.get(0).firstSeenAt()).isNotNull();
		assertThat(rows.get(0).tagDetectedAt()).isNotNull();
		assertThat(rows.get(0).directRegisteredAt()).isNull();
	}

	@Test
	void 윈도우_조회는_unavailable_at을_함께_읽는다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(brandId, "GONE", now.minusDays(2).toString());
		seedTaggedPost(brandId, "ALIVE", now.minusDays(1).toString());
		jdbc.sql("UPDATE brand_tagged_post SET unavailable_at = now() WHERE short_code = 'GONE'").update();

		List<BrandTaggedPostRow> rows = repository.findBrandPostsInWindow(brandId, now.minusDays(365), false);

		assertThat(rows).extracting(BrandTaggedPostRow::shortCode).containsExactly("ALIVE", "GONE");
		assertThat(rows.get(0).unavailableAt()).isNull();
		assertThat(rows.get(1).unavailableAt()).isNotNull();
	}

	@Test
	void 윈도우_조회는_다른_브랜드_행을_섞지_않는다() {
		long mine = seedBrand("brand_mine");
		long other = seedBrand("brand_other");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(mine, "MINE1", now.minusDays(2).toString());
		seedTaggedPost(other, "OTHER1", now.minusDays(1).toString());

		List<BrandTaggedPostRow> rows = repository.findBrandPostsInWindow(mine, now.minusDays(365), false);

		assertThat(rows).extracting(BrandTaggedPostRow::shortCode).containsExactly("MINE1");
	}

	/**
	 * 표시 경로(정산분 조회)는 보강 정산 전 게시물을 내보내지 않는다(2026-08-13 완결 배치 서빙) —
	 * 게시자·댓글이 붙기 전의 반쯤 빈 카드를 FE에 싣지 않기 위한 게이트다. 목록·상세·meta.counts가
	 * 전부 이 메서드를 경유하므로 세 표면이 동시에 정산분만 본다.
	 */
	@Test
	void 정산분_조회는_정산되지_않은_게시물을_제외한다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(brandId, "DONE_A", now.minusDays(3).toString());
		seedUnenrichedTaggedPost(brandId, "PENDING_B", now.minusDays(1).toString());   // 더 최신이지만 미정산

		List<BrandTaggedPostRow> rows =
				repository.findBrandPostsInWindow(brandId, now.minusDays(365), true);

		assertThat(rows).extracting(BrandTaggedPostRow::shortCode).containsExactly("DONE_A");
	}

	/**
	 * 전량 경로는 미정산분도 준다 — 존재 판정(캠페인 연결)·중복 판정(직접 등록)·지표 집계(성과
	 * 대시보드)는 "수집 중"을 "없음"으로 답하면 안 된다(2026-08-13 리뷰 결정). 정산 게이트는 표시
	 * 계약이지 존재 계약이 아니다.
	 */
	@Test
	void 전량_조회는_정산되지_않은_게시물도_포함한다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(brandId, "DONE_A", now.minusDays(3).toString());
		seedUnenrichedTaggedPost(brandId, "PENDING_B", now.minusDays(1).toString());

		List<BrandTaggedPostRow> rows = repository.findBrandPostsInWindow(brandId, now.minusDays(365), false);

		assertThat(rows).extracting(BrandTaggedPostRow::shortCode)
				.containsExactly("PENDING_B", "DONE_A");   // 최신순 — 미정산분이 정렬에서도 빠지지 않는다
	}

	/**
	 * direct 등록 행은 창(365일 컷) 예외다(2026-08-18 direct 통합 §3-3) — 등록 시점이 아무리 오래돼도
	 * 표시된다. tagged-only 창 밖 행은 여전히 제외된다(대조군).
	 */
	@Test
	void direct_등록_행은_창_밖이어도_포함된다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(brandId, "OLD_TAGGED", now.minusDays(400).toString());   // 창 밖 tagged — 제외 대조군
		seedDirectPost(brandId, "OLD_DIRECT", now.minusDays(400).toString(), now.minusDays(1).toString());

		List<BrandTaggedPostRow> rows = repository.findBrandPostsInWindow(brandId, now.minusDays(365), false);

		assertThat(rows).extracting(BrandTaggedPostRow::shortCode).containsExactly("OLD_DIRECT");
		assertThat(rows.get(0).directRegisteredAt()).isNotNull();
		assertThat(rows.get(0).tagDetectedAt()).isNull();
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

	/**
	 * 인덱스 프로젝션은 단일 브랜드 창 스코프 조인이어야 한다(2026-08-27 스테이징 실측 — 만 건대
	 * 브랜드에서 행×컬럼 매핑이 지배 비용이라 쿼리 왕복·컬럼을 최소화). 창·정산 술어는
	 * findBrandPostsInWindow와 동형이고, 메타 없는 행도 LEFT JOIN으로 모수에 남아야 한다.
	 */
	@Test
	void 인덱스_프로젝션은_브랜드_창_스코프로_판정_입력만_읽는다() {
		long brandId = seedBrand("brand");
		long otherBrand = seedBrand("other_brand");
		seedTaggedPost(brandId, "SHORT1", "2026-08-01T12:00:00+09:00");
		seedTaggedPost(brandId, "NOMETA", "2026-08-03T12:00:00+09:00");      // 메타 미보강 — LEFT JOIN 유지
		seedTaggedPost(brandId, "OLD1", "2024-01-01T12:00:00+09:00");        // 창 밖 — 제외
		seedUnenrichedTaggedPost(brandId, "RAW1", "2026-08-02T12:00:00+09:00"); // 미정산 — enrichedOnly 제외
		seedTaggedPost(otherBrand, "SHORT9", "2026-08-01T12:00:00+09:00");   // 남의 브랜드 — 제외
		jdbc.sql("""
				INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption,
				                             thumbnail_url, video_url, video_duration, is_paid_partnership)
				VALUES ('SHORT1', 'influencer_a', 'REELS', '2026-08-01', '#협찬 후기', 'http://cdn/1.jpg',
				        NULL, NULL, NULL),
				       ('OLD1', 'influencer_a', 'REELS', '2024-01-01', '', NULL, NULL, NULL, true),
				       ('RAW1', 'influencer_a', 'REELS', '2026-08-02', '', NULL, NULL, NULL, true),
				       ('SHORT9', 'influencer_b', 'FEED', '2026-08-02', '', NULL, NULL, NULL, true)
				""").update();

		List<BrandReadRepository.BrandPostIndexRow> rows = repository.findBrandPostIndex(
				brandId, OffsetDateTime.parse("2025-08-27T00:00:00+09:00"), true);

		assertThat(rows).hasSize(2);
		BrandReadRepository.BrandPostIndexRow enriched = rows.stream()
				.filter(r -> r.shortCode().equals("SHORT1")).findFirst().orElseThrow();
		assertThat(enriched.caption()).isEqualTo("#협찬 후기");
		assertThat(enriched.isPaidPartnership()).isNull();   // null = 키 부재(판정 unknown) 보존
		assertThat(enriched.tagDetectedAt()).isNotNull();
		assertThat(enriched.directRegisteredAt()).isNull();
		assertThat(enriched.takenAt()).isNotNull();
		BrandReadRepository.BrandPostIndexRow noMeta = rows.stream()
				.filter(r -> r.shortCode().equals("NOMETA")).findFirst().orElseThrow();
		assertThat(noMeta.caption()).isNull();               // 메타 없음 → 판정 입력 null(unknown)
		assertThat(noMeta.isPaidPartnership()).isNull();
	}

	/** direct 등록 행은 창 밖이어도 인덱스에 포함된다 — findBrandPostsInWindow의 창 예외와 동형. */
	@Test
	void 인덱스_프로젝션은_direct_등록_행을_창_밖이어도_포함한다() {
		long brandId = seedBrand("brand");
		seedDirectPost(brandId, "OLDDIRECT", "2024-01-01T12:00:00+09:00", "2026-08-01T12:00:00+09:00");
		jdbc.sql("""
				INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption,
				                             thumbnail_url, video_url, video_duration, is_paid_partnership)
				VALUES ('OLDDIRECT', 'influencer_a', 'REELS', '2024-01-01', '', NULL, NULL, NULL, true)
				""").update();

		List<BrandReadRepository.BrandPostIndexRow> rows = repository.findBrandPostIndex(
				brandId, OffsetDateTime.parse("2025-08-27T00:00:00+09:00"), false);

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).isPaidPartnership()).isTrue();
		assertThat(rows.get(0).directRegisteredAt()).isNotNull();
	}

	/** 하이드레이트용 풀 행 배치 조회 — 브랜드+shortcode 스코프, 다른 브랜드 행은 섞이지 않는다. */
	@Test
	void 풀_행_배치_조회는_요청한_shortcode만_돌려준다() {
		long brandId = seedBrand("brand");
		long otherBrand = seedBrand("other_brand");
		seedTaggedPost(brandId, "SHORT1", "2026-08-01T12:00:00+09:00");
		seedTaggedPost(brandId, "SHORT2", "2026-08-02T12:00:00+09:00");
		seedTaggedPost(otherBrand, "SHORT1", "2026-08-01T12:00:00+09:00");   // 같은 코드, 남의 브랜드

		List<BrandTaggedPostRow> rows = repository.findBrandPostsByShortCodes(brandId,
				List.of("SHORT1", "MISSING"));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).shortCode()).isEqualTo("SHORT1");
		assertThat(rows.get(0).authorUsername()).isEqualTo("influencer_a");
		assertThat(repository.findBrandPostsByShortCodes(brandId, List.of())).isEmpty();   // IN () 선처리
	}

	/** 최신 1행 판정은 captured_on 기준이어야 한다 — 물리 삽입 순서로 새는 것을 역순 삽입으로 잡는다. */
	@Test
	void 최신_스냅샷_프로젝션은_브랜드_창_스코프로_게시물당_마지막_1행이다() {
		long brandId = seedBrand("brand");
		long otherBrand = seedBrand("other_brand");
		seedTaggedPost(brandId, "SHORT1", "2026-08-01T12:00:00+09:00");
		seedTaggedPost(brandId, "SHORT2", "2026-08-02T12:00:00+09:00");
		seedTaggedPost(otherBrand, "SHORT9", "2026-08-01T12:00:00+09:00");   // 남의 브랜드 — 제외
		jdbc.sql("""
				INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type,
				                                 likes, likes_hidden, comments, views, fb_plays,
				                                 saves, shares, shares_hidden, reposts)
				VALUES ('influencer_a', 'SHORT1', '2026-08-03', 'REELS', 20, false, 2, 500, 100, 3, 4, false, 1),
				       ('influencer_a', 'SHORT1', '2026-08-02', 'REELS', 10, false, 1, 300, 50, 2, 3, false, 0),
				       ('influencer_b', 'SHORT2', '2026-08-02', 'FEED', NULL, true, 5, NULL, NULL, 1, NULL, true, NULL),
				       ('influencer_c', 'SHORT9', '2026-08-02', 'REELS', 10, false, 1, 999, 50, 2, 3, false, 0)
				""").update();

		List<BrandReadRepository.LatestViewsRow> rows = repository.findLatestViewsForBrand(
				brandId, OffsetDateTime.parse("2025-08-27T00:00:00+09:00"), true);

		assertThat(rows).hasSize(2);
		BrandReadRepository.LatestViewsRow reels = rows.stream()
				.filter(r -> r.shortCode().equals("SHORT1")).findFirst().orElseThrow();
		assertThat(reels.views()).isEqualTo(500L);   // 08-03 행 — captured_on 최신
		assertThat(reels.contentType()).isEqualTo("REELS");
		BrandReadRepository.LatestViewsRow feed = rows.stream()
				.filter(r -> r.shortCode().equals("SHORT2")).findFirst().orElseThrow();
		assertThat(feed.views()).isNull();
		assertThat(feed.contentType()).isEqualTo("FEED");
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

	// ---------- 해시태그 발견 게시물(스펙 2026-08-11 §5) ----------

	void seedHashtagPost(long brandId, String shortCode, String verdict, String takenAt) {
		jdbc.sql("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username,
				                                author_full_name, author_profile_pic_url, taken_at, caption,
				                                content_type, thumbnail_url, likes, comments, verdict,
				                                verdict_source)
				VALUES (:brandId, :shortCode, '#브랜드명', 'influencer_h', '인플루언서 H', 'http://cdn/h.jpg',
				        :takenAt::timestamptz, '해시태그 캡션', 'REELS', 'http://cdn/h-thumb.jpg', 20, 3,
				        :verdict, 'LLM')
				""")
				.param("brandId", brandId).param("shortCode", shortCode).param("takenAt", takenAt)
				.param("verdict", verdict)
				.update();
	}

	@Test
	void 해시태그_발견_게시물은_RELEVANT만_컷_이후_최신순으로_읽힌다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedHashtagPost(brandId, "OLD", "RELEVANT", now.minusDays(400).toString());   // 컷 밖
		seedHashtagPost(brandId, "IRR", "IRRELEVANT", now.minusDays(1).toString());   // 관련 없음 — 제외
		seedHashtagPost(brandId, "UNC", "UNCERTAIN", now.minusDays(1).toString());    // 불확실 — 제외
		seedHashtagPost(brandId, "MID", "RELEVANT", now.minusDays(10).toString());
		seedHashtagPost(brandId, "NEW", "RELEVANT", now.minusDays(1).toString());

		List<BrandHashtagPostRow> rows = repository.findHashtagPosts(brandId, now.minusDays(365), 2000);

		assertThat(rows).extracting(BrandHashtagPostRow::shortCode).containsExactly("NEW", "MID");
		assertThat(rows.get(0).matchedTag()).isEqualTo("#브랜드명");
		assertThat(rows.get(0).authorUsername()).isEqualTo("influencer_h");
		assertThat(rows.get(0).authorFullName()).isEqualTo("인플루언서 H");
		assertThat(rows.get(0).caption()).isEqualTo("해시태그 캡션");
		assertThat(rows.get(0).contentType()).isEqualTo("REELS");
		assertThat(rows.get(0).likes()).isEqualTo(20L);
		assertThat(rows.get(0).comments()).isEqualTo(3L);
		assertThat(rows.get(0).firstSeenAt()).isNotNull();
	}

	@Test
	void 해시태그_발견_게시물_조회는_상한을_넘으면_최신_쪽만_남긴다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedHashtagPost(brandId, "A", "RELEVANT", now.minusDays(1).toString());
		seedHashtagPost(brandId, "B", "RELEVANT", now.minusDays(2).toString());
		seedHashtagPost(brandId, "C", "RELEVANT", now.minusDays(3).toString());

		List<BrandHashtagPostRow> rows = repository.findHashtagPosts(brandId, now.minusDays(365), 2);

		assertThat(rows).extracting(BrandHashtagPostRow::shortCode).containsExactly("A", "B");
	}

	@Test
	void 해시태그_발견_게시물_조회는_다른_브랜드_행을_섞지_않는다() {
		long mine = seedBrand("brand_mine");
		long other = seedBrand("brand_other");
		OffsetDateTime now = OffsetDateTime.now();
		seedHashtagPost(mine, "MINE1", "RELEVANT", now.minusDays(1).toString());
		seedHashtagPost(other, "OTHER1", "RELEVANT", now.minusDays(1).toString());

		List<BrandHashtagPostRow> rows = repository.findHashtagPosts(mine, now.minusDays(365), 2000);

		assertThat(rows).extracting(BrandHashtagPostRow::shortCode).containsExactly("MINE1");
	}

	/** 작성자 프로필 사진 아카이브 결과(2026-08-17 신설)까지 읽는다 — null이면 미아카이브. */
	@Test
	void 해시태그_발견_게시물은_작성자_이미지_아카이브_경로도_읽는다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedHashtagPost(brandId, "ARCHIVED", "RELEVANT", now.minusDays(1).toString());
		jdbc.sql("""
				UPDATE brand_hashtag_post SET author_image_object_path = :path
				WHERE brand_id = :brandId AND short_code = :shortCode
				""")
				.param("path", "monitor-hashtag-author/influencer_h.jpg")
				.param("brandId", brandId).param("shortCode", "ARCHIVED")
				.update();
		seedHashtagPost(brandId, "UNARCHIVED", "RELEVANT", now.minusDays(2).toString());

		List<BrandHashtagPostRow> rows = repository.findHashtagPosts(brandId, now.minusDays(365), 2000);

		BrandHashtagPostRow archived = rows.stream().filter(r -> r.shortCode().equals("ARCHIVED")).findFirst()
				.orElseThrow();
		BrandHashtagPostRow unarchived = rows.stream().filter(r -> r.shortCode().equals("UNARCHIVED")).findFirst()
				.orElseThrow();
		assertThat(archived.authorImageObjectPath()).isEqualTo("monitor-hashtag-author/influencer_h.jpg");
		assertThat(unarchived.authorImageObjectPath()).isNull();
	}

	// ---------- 매칭 태그 전체(2026-08-19, was 사용자 스코프 필터 지원) ----------

	@Test
	void findMatchedTags는_shortcode당_매칭된_태그_전부를_돌려준다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		// brand_post_matched_tag의 FK는 통합 풀(brand_tagged_post)을 향한다(2026-08-27 산지 교체) — 매칭 태그를
		// 붙이려면 먼저 그 short_code가 풀에 있어야 한다.
		seedTaggedPost(brandId, "MULTI", now.minusDays(1).toString());
		jdbc.sql("""
				INSERT INTO brand_post_matched_tag (brand_id, short_code, tag)
				VALUES (:brandId, 'MULTI', 'cclime'), (:brandId, 'MULTI', '끌리메')
				""")
				.param("brandId", brandId).update();

		List<BrandReadRepository.MatchedTagRow> rows = repository.findMatchedTags(brandId, List.of("MULTI"));

		assertThat(rows).extracting(BrandReadRepository.MatchedTagRow::tag)
				.containsExactlyInAnyOrder("cclime", "끌리메");
	}

	@Test
	void findMatchedTags는_다른_브랜드_행을_섞지_않는다() {
		long mine = seedBrand("brand_mine");
		long other = seedBrand("brand_other");
		OffsetDateTime now = OffsetDateTime.now();
		// brand_post_matched_tag의 FK는 통합 풀(brand_tagged_post)을 향한다(2026-08-27 산지 교체).
		seedTaggedPost(other, "SAME", now.minusDays(1).toString());
		jdbc.sql("INSERT INTO brand_post_matched_tag (brand_id, short_code, tag) VALUES (:brandId, 'SAME', 'other')")
				.param("brandId", other).update();

		List<BrandReadRepository.MatchedTagRow> rows = repository.findMatchedTags(mine, List.of("SAME"));

		assertThat(rows).isEmpty();
	}

	@Test
	void findMatchedTags는_빈_shortcode_목록에_빈_결과를_돌려준다() {
		long brandId = seedBrand("brand_official");

		assertThat(repository.findMatchedTags(brandId, List.of())).isEmpty();
	}

	// ---------- 승격 상태 필드용 tagged 존재 판정(2026-08-17) ----------

	@Test
	void tagged_존재_판정은_후보_shortcode_중_실재하는_것만_돌려준다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(brandId, "EXISTS", now.minusDays(1).toString());

		Set<String> found = repository.findExistingTaggedShortCodes(brandId, List.of("EXISTS", "MISSING"));

		assertThat(found).containsExactly("EXISTS");
	}

	/** 윈도우 제한이 없다는 계약 — 365일 컷보다 오래된 tagged 게시물도 존재로 잡혀야 한다. */
	@Test
	void tagged_존재_판정은_윈도우_제한이_없다() {
		long brandId = seedBrand("brand_official");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(brandId, "OLD", now.minusDays(400).toString());

		Set<String> found = repository.findExistingTaggedShortCodes(brandId, List.of("OLD"));

		assertThat(found).containsExactly("OLD");
	}

	@Test
	void tagged_존재_판정은_다른_브랜드_행을_섞지_않는다() {
		long mine = seedBrand("brand_mine");
		long other = seedBrand("brand_other");
		OffsetDateTime now = OffsetDateTime.now();
		seedTaggedPost(other, "OTHER1", now.minusDays(1).toString());

		Set<String> found = repository.findExistingTaggedShortCodes(mine, List.of("OTHER1"));

		assertThat(found).isEmpty();
	}

	@Test
	void tagged_존재_판정은_빈_입력이면_빈_결과다() {
		long brandId = seedBrand("brand_official");
		assertThat(repository.findExistingTaggedShortCodes(brandId, List.of())).isEmpty();
	}
}
