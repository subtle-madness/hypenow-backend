package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BrandPost 조립 규칙 단위 고정(2026-08-18 direct 통합 §3-3) — 리포지토리 없이 row record를 손으로
 * 만들어 순수 변환만 검증한다. 배선(배치 조회·과도기 폴백)은 컨트롤러 슬라이스 테스트가 덮는다.
 */
class BrandPostAssemblerTest {

	private static final OffsetDateTime SWEPT_AT = OffsetDateTime.parse("2026-08-07T18:00:00Z");

	// ---------- 댓글 생략 배선(08-12 성과 대시보드 고정 지연 대응) ----------

	@Test
	void withComments_false면_댓글_배치_조회를_돌리지_않는다() {
		// 이 파일의 다른 테스트와 달리 배선 검증이라 mock을 쓴다 — 댓글 쿼리는 조립 시간의 절반
		// 이상이라(08-12 운영 덤프 실측) 슬림 경로에서 되살아나면 고정 지연이 재발한다.
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var account = accountRow();
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(taggedRow("ABC")));

		var assembler = new BrandPostAssembler(repository, campaignRepository, directRepository, trackingAssembler);
		var posts = assembler.assembleBrandPosts(account, false, BrandPostAssembler.BrandPostScope.ALL);

		verify(repository, never()).findComments(org.mockito.ArgumentMatchers.anyCollection(),
				org.mockito.ArgumentMatchers.anyInt());
		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.recentComments()).isEmpty();
			assertThat(post.commentsCollectedCount()).isEqualTo(0);
		});
	}

	// ---------- 정산 게이트 분기(2026-08-13 완결 배치 서빙) ----------

	/**
	 * 표시 표면(ENRICHED_ONLY)만 정산분 조회로 가고, 판정·집계 표면(ALL)은 전량 조회로 간다 — 조회의
	 * enrichedOnly 인자로 구분된다. 뒤바뀌면 캠페인 존재 판정이 수집 중인 게시물을 NOT_FOUND로
	 * 떨구거나(ALL→ENRICHED_ONLY), 목록에 반쯤 빈 카드가 실린다(반대 방향).
	 */
	@Test
	void scope가_enrichedOnly_인자를_가른다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var account = accountRow();
		var assembler = new BrandPostAssembler(repository, campaignRepository, directRepository, trackingAssembler);

		assembler.assembleBrandPosts(account, false, BrandPostAssembler.BrandPostScope.ENRICHED_ONLY);
		verify(repository).findBrandPostsInWindow(eq(42L), any(), eq(true));

		org.mockito.Mockito.clearInvocations(repository);
		assembler.assembleBrandPosts(account, false, BrandPostAssembler.BrandPostScope.ALL);
		verify(repository).findBrandPostsInWindow(eq(42L), any(), eq(false));
	}

	/** 브랜드 화면 진입점(목록·상세·counts)은 표시 표면이라 정산분 조회로만 간다. */
	@Test
	void 브랜드_화면_조립은_정산분만_읽는다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var account = accountRow();
		var assembler = new BrandPostAssembler(repository, campaignRepository, directRepository, trackingAssembler);

		assembler.assembleForBrand(7L, account);

		verify(repository).findBrandPostsInWindow(eq(42L), any(), eq(true));
	}

	// ---------- 윈도우 컷 ----------

	@Test
	void 윈도우_컷은_KST_자정_기준_365일이다() {
		// 크롤링 정책 v1(08-09) — 수집 편입 컷(브랜드별 수집 창 collection_months 최대치)과 같은 깊이.
		var expected = LocalDate.now(KstTimestamps.KST).minusDays(365)
				.atStartOfDay(KstTimestamps.KST).toOffsetDateTime();

		assertThat(BrandPostAssembler.windowCutoff()).isEqualTo(expected);
	}

	// ---------- 스냅샷 ----------

	@Test
	void FEED_스냅샷은_views_shares_reposts를_null로_강제한다() {
		var row = new BrandReadRepository.BrandSnapshotRow("ABC", LocalDate.of(2026, 8, 6), "FEED",
				10L, false, 2L, 999L, 5L, 7L, false, 3L);

		var s = BrandPostAssembler.snapshotOf(row);

		assertThat(s.views()).isNull();
		assertThat(s.shares()).isNull();
		assertThat(s.reposts()).isNull();
		assertThat(s.likes()).isEqualTo(10L);
		assertThat(s.saves()).isEqualTo(5L);
		assertThat(s.date()).isEqualTo("2026-08-06");
	}

	@Test
	void FEED_스냅샷은_sharesHidden도_접는다() {
		// 피드는 공유 자체가 미지원 — "비공개"와 "미지원"은 다른 상태라 숨김 신호까지 false로 접는다.
		var row = new BrandReadRepository.BrandSnapshotRow("ABC", LocalDate.of(2026, 8, 6), "FEED",
				null, true, 2L, null, 5L, null, true, null);

		var s = BrandPostAssembler.snapshotOf(row);

		assertThat(s.sharesHidden()).isFalse();
		assertThat(s.likesHidden()).isTrue();
	}

	@Test
	void REELS_스냅샷은_영상_지표와_숨김_신호를_그대로_통과시킨다() {
		var row = new BrandReadRepository.BrandSnapshotRow("ABC", LocalDate.of(2026, 8, 6), "REELS",
				10L, false, 2L, 999L, 5L, 7L, true, 3L);

		var s = BrandPostAssembler.snapshotOf(row);

		assertThat(s.views()).isEqualTo(999L);
		assertThat(s.shares()).isEqualTo(7L);
		assertThat(s.reposts()).isEqualTo(3L);
		assertThat(s.sharesHidden()).isTrue();
	}

	@Test
	void contentType_불명_스냅샷은_피드로_접는다() {
		// 0·거짓 지표 노출 방지가 우선(레거시 toSnapshotResponse와 같은 방향).
		var row = new BrandReadRepository.BrandSnapshotRow("ABC", LocalDate.of(2026, 8, 6), null,
				10L, false, 2L, 999L, 5L, 7L, false, 3L);

		assertThat(BrandPostAssembler.snapshotOf(row).views()).isNull();
	}

	@Test
	void 스냅샷은_오름차순이고_latestSnapshot은_마지막_원소다() {
		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(snapshotRow("ABC", 5, 100L), snapshotRow("ABC", 6, 180L)), List.of(), SWEPT_AT,
				List.of());

		assertThat(post.snapshots()).extracting(TrackingItemResponse.SnapshotResponse::date)
				.containsExactly("2026-08-05", "2026-08-06");
		assertThat(post.latestSnapshot()).isEqualTo(post.snapshots().get(1));
		assertThat(post.latestSnapshot().views()).isEqualTo(180L);
	}

	// ---------- brandPost 조립 ----------

	@Test
	void REELS면_reel_URL_FEED면_p_URL이다() {
		var reels = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(), List.of(), SWEPT_AT, List.of());
		var feed = BrandPostAssembler.brandPost(100L, taggedRow("DEF"), meta("DEF", "FEED", null),
				null, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(reels.postUrl()).isEqualTo("https://www.instagram.com/reel/ABC/");
		assertThat(reels.contentType()).isEqualTo("reels");
		assertThat(feed.postUrl()).isEqualTo("https://www.instagram.com/p/DEF/");
		assertThat(feed.contentType()).isEqualTo("feed");
	}

	@Test
	void tagged의_기본_필드는_열거행과_계정_스윕_시각에서_온다() {
		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", true),
				null, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(post.id()).isEqualTo("ABC");
		assertThat(post.shortcode()).isEqualTo("ABC");
		assertThat(post.brandAccountId()).isEqualTo("100");
		assertThat(post.source()).isEqualTo("tagged");
		assertThat(post.trackingStatus()).isEqualTo("tracking");
		// tagged는 trackingStartedAt=COALESCE(direct_registered_at, first_seen_at)=first_seen_at.
		assertThat(post.trackingStartedAt()).isEqualTo("2026-08-06T11:00:00+09:00");
		assertThat(post.trackingEndedAt()).isNull();
		assertThat(post.takenAt()).isEqualTo("2026-08-06T10:00:00+09:00");
		assertThat(post.createdAt()).isEqualTo("2026-08-06T11:00:00+09:00");
		assertThat(post.campaignIds()).isEmpty();
	}

	/** source 파생 규칙 3종(설계 §3-3) — direct_registered_at 유무만으로 갈린다. */
	@Test
	void source는_direct_registered_at_유무로_파생된다() {
		var taggedOnly = BrandPostAssembler.brandPost(100L, row("ABC", null, "2026-08-06T02:00:00Z", null),
				null, null, List.of(), List.of(), SWEPT_AT, List.of());
		var directOnly = BrandPostAssembler.brandPost(100L,
				row("DEF", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z"),
				null, null, List.of(), List.of(), SWEPT_AT, List.of());
		var overlap = BrandPostAssembler.brandPost(100L,
				row("GHI", "2026-08-06T02:00:00Z", "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z"),
				null, null, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(taggedOnly.source()).isEqualTo("tagged");
		assertThat(directOnly.source()).isEqualTo("direct");
		// 겹침(tagged+direct 둘 다 값이 있음) → direct가 이긴다(현행 mergeByShortcode 규칙 승계).
		assertThat(overlap.source()).isEqualTo("direct");
	}

	/** trackingStartedAt = COALESCE(direct_registered_at, first_seen_at) — direct는 등록 시점부터. */
	@Test
	void direct_행의_trackingStartedAt은_등록_시점이다() {
		var post = BrandPostAssembler.brandPost(100L,
				row("DEF", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z"),
				meta("DEF", "REELS", null), null, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(post.trackingStartedAt()).isEqualTo("2026-08-07T11:00:00+09:00");
		// createdAt은 등록 경로와 무관하게 항상 first_seen_at이다 — direct 등록이 발견 이력을 덮지 않는다.
		assertThat(post.createdAt()).isEqualTo("2026-08-06T11:00:00+09:00");
	}

	/**
	 * updatedAt = GREATEST(계정 마지막 스윕, 행 마지막 크롤) — direct 등록 직후 카드가 "어젯밤 스윕"으로
	 * 보이지 않게 행 단위 값을 함께 본다(설계 §3-3).
	 */
	@Test
	void updatedAt은_계정_스윕과_행_크롤_중_늦은_값이다() {
		OffsetDateTime accountSwept = OffsetDateTime.parse("2026-08-07T18:00:00Z");
		OffsetDateTime rowCrawledLater = OffsetDateTime.parse("2026-08-08T09:00:00Z");
		OffsetDateTime rowCrawledEarlier = OffsetDateTime.parse("2026-08-01T09:00:00Z");

		var rowWins = BrandPostAssembler.brandPost(100L,
				new BrandReadRepository.BrandTaggedPostRow("ABC", "glowdeep_92", "9001",
						OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
						7L, rowCrawledLater, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null),
				null, null, List.of(), List.of(), accountSwept, List.of());
		var accountWins = BrandPostAssembler.brandPost(100L,
				new BrandReadRepository.BrandTaggedPostRow("ABC", "glowdeep_92", "9001",
						OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
						7L, rowCrawledEarlier, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null),
				null, null, List.of(), List.of(), accountSwept, List.of());
		var rowNull = BrandPostAssembler.brandPost(100L,
				taggedRow("ABC"), null, null, List.of(), List.of(), accountSwept, List.of());

		assertThat(rowWins.updatedAt()).isEqualTo(KstTimestamps.toKstIso(rowCrawledLater));
		assertThat(accountWins.updatedAt()).isEqualTo(KstTimestamps.toKstIso(accountSwept));
		assertThat(rowNull.updatedAt()).isEqualTo(KstTimestamps.toKstIso(accountSwept));
	}

	@Test
	void campaignIds가_채워진다() {
		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(), List.of(), SWEPT_AT, List.of("55", "56"));

		assertThat(post.campaignIds()).containsExactly("55", "56");
	}

	@Test
	void 협찬은_유료협찬_플래그와_캡션으로_판정한다() {
		var paid = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", true),
				null, List.of(), List.of(), SWEPT_AT, List.of());
		var organic = BrandPostAssembler.brandPost(100L, taggedRow("DEF"), meta("DEF", "REELS", false),
				null, List.of(), List.of(), SWEPT_AT, List.of());
		var unknown = BrandPostAssembler.brandPost(100L, taggedRow("GHI"), meta("GHI", "REELS", null),
				null, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(paid.sponsorship()).isEqualTo("sponsored");
		assertThat(paid.isPaidPartnership()).isTrue();
		assertThat(organic.sponsorship()).isEqualTo("organic");
		assertThat(unknown.sponsorship()).isEqualTo("unknown");
		assertThat(unknown.isPaidPartnership()).isNull();
	}

	@Test
	void 메타가_없으면_피드로_접고_표시필드는_null이다() {
		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), null, null, List.of(), List.of(), SWEPT_AT,
				List.of());

		assertThat(post.contentType()).isEqualTo("feed");
		assertThat(post.postUrl()).isEqualTo("https://www.instagram.com/p/ABC/");
		assertThat(post.caption()).isNull();
		assertThat(post.thumbnailUrl()).isNull();
		assertThat(post.videoUrl()).isNull();
		assertThat(post.sponsorship()).isEqualTo("unknown");
	}

	@Test
	void 게시자_프로필_부재면_author_필드는_열거_관측값으로_폴백한다() {
		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(post.authorUsername()).isEqualTo("glowdeep_92");
		assertThat(post.authorProfileUrl()).isEqualTo("https://www.instagram.com/glowdeep_92/");
		assertThat(post.authorFullName()).isNull();
		assertThat(post.authorProfilePicUrl()).isNull();
		assertThat(post.authorFollowers()).isNull();
		assertThat(post.authorIsVerified()).isFalse();
	}

	@Test
	void 게시자_프로필이_있으면_프로필_값을_쓴다() {
		var author = new BrandReadRepository.AuthorRow("9001", "glowdeep_92", "글로우딥",
				12345L, "https://cdn/author.jpg", true, null);

		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				author, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(post.authorFullName()).isEqualTo("글로우딥");
		assertThat(post.authorFollowers()).isEqualTo(12345L);
		assertThat(post.authorProfilePicUrl()).isEqualTo("https://cdn/author.jpg");
		assertThat(post.authorIsVerified()).isTrue();
	}

	/** image_object_path(monitoring 자체 아카이브 결과)가 있으면 원본 CDN URL보다 그걸 우선 서빙한다. */
	@Test
	void 아카이브된_썸네일과_게시자_프로필은_img_상대경로를_우선_서빙한다() {
		var author = new BrandReadRepository.AuthorRow("9001", "glowdeep_92", "글로우딥",
				12345L, "https://cdn/author.jpg", true, "monitor-author/9001.jpg");
		var meta = new BrandReadRepository.BrandPostMetaRow("ABC", "glowdeep_92", "REELS",
				LocalDate.of(2026, 8, 6), "캡션", "https://cdn/thumb.jpg", null, null, null,
				"monitor-brand-post/ABC.jpg");

		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta, author, List.of(), List.of(), SWEPT_AT,
				List.of());

		assertThat(post.thumbnailUrl()).isEqualTo("/img/monitor-brand-post/ABC.jpg");
		assertThat(post.authorProfilePicUrl()).isEqualTo("/img/monitor-author/9001.jpg");
	}

	@Test
	void 무효_스킴_이미지_URL은_null로_강등한다() {
		var author = new BrandReadRepository.AuthorRow("9001", "glowdeep_92", "글로우딥",
				12345L, "javascript:alert(1)", true, null);
		var meta = new BrandReadRepository.BrandPostMetaRow("ABC", "glowdeep_92", "REELS",
				LocalDate.of(2026, 8, 6), "캡션", "data:image/png;base64,AAAA", null, null, null, null);

		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta, author, List.of(), List.of(), SWEPT_AT,
				List.of());

		assertThat(post.authorProfilePicUrl()).isNull();
		assertThat(post.thumbnailUrl()).isNull();
	}

	// ---------- 댓글 ----------

	@Test
	void 댓글_author는_마스킹되고_결손행은_제외된다() {
		var ok = new BrandReadRepository.BrandCommentRow("ABC", "c1", "glowdeep_92", "좋아요", 3L,
				OffsetDateTime.parse("2026-08-06T05:00:00Z"), "감사합니다");
		var broken = new BrandReadRepository.BrandCommentRow("ABC", "c2", null, "본문", 0L,
				OffsetDateTime.parse("2026-08-06T05:00:00Z"), null);

		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(), List.of(ok, broken), SWEPT_AT, List.of());

		assertThat(post.recentComments()).hasSize(1);
		assertThat(post.recentComments().get(0).author()).isEqualTo("gl***92");
		assertThat(post.recentComments().get(0).reply().text()).isEqualTo("감사합니다");
		assertThat(post.commentsCollectedCount()).isEqualTo(1L);
	}

	@Test
	void commentsTotal은_최신_스냅샷_댓글수고_null이면_숨김이다() {
		var hidden = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null), null,
				List.of(new BrandReadRepository.BrandSnapshotRow("ABC", LocalDate.of(2026, 8, 6), "REELS",
						10L, false, null, 100L, 1L, 2L, false, 3L)),
				List.of(), SWEPT_AT, List.of());
		var shown = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(snapshotRow("ABC", 6, 100L)), List.of(), SWEPT_AT, List.of());
		var noSnapshot = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(hidden.commentsTotal()).isNull();
		assertThat(hidden.commentsHidden()).isTrue();
		assertThat(shown.commentsTotal()).isEqualTo(12L);
		assertThat(shown.commentsHidden()).isFalse();
		// 스냅샷 자체가 없으면 "숨김"이 아니라 "아직 모름"이다.
		assertThat(noSnapshot.commentsHidden()).isFalse();
	}

	// ---------- 비대칭 해소 회귀 방지(설계 §1, 08-18 결정 1) ----------

	/**
	 * direct 등록 행도 tagged와 완전히 같은 필드 셋을 갖는다 — videoUrl·videoDuration·
	 * authorIsVerified·isPaidPartnership이 더 이상 null·false로 고정되지 않는다. 셰이프가 하나가 된
	 * 것(설계 §결정 1)이 이 테스트로 고정된다: 같은 meta·author·snapshot을 direct 행에 물려도 tagged와
	 * 동일하게 채워져야 한다.
	 */
	@Test
	void direct_행도_tagged와_동일한_필드셋이_채워진다_비대칭_해소_회귀방지() {
		var meta = new BrandReadRepository.BrandPostMetaRow("XYZ", "glowdeep_92", "REELS",
				LocalDate.of(2026, 8, 6), "오늘의 #협찬 후기", "https://cdn/thumb.jpg", "https://cdn/video.mp4",
				15.5, true, null);
		var author = new BrandReadRepository.AuthorRow("9001", "glowdeep_92", "글로우딥",
				12345L, "https://cdn/author.jpg", true, null);
		var directRow = row("XYZ", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z");

		var post = BrandPostAssembler.brandPost(100L, directRow, meta, author, List.of(), List.of(), SWEPT_AT,
				List.of());

		assertThat(post.source()).isEqualTo("direct");
		assertThat(post.videoUrl()).isEqualTo("https://cdn/video.mp4");
		assertThat(post.videoDuration()).isEqualTo(15.5);
		assertThat(post.authorIsVerified()).isTrue();
		assertThat(post.isPaidPartnership()).isTrue();
		assertThat(post.sponsorship()).isEqualTo("sponsored");
	}

	// ---------- 정렬 ----------

	@Test
	void uploadedOn은_takenAt_앞_10자를_KST_날짜로_파싱한다() {
		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC", "2026-08-06T01:00:00Z"),
				null, null, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(BrandPostAssembler.uploadedOn(post)).isEqualTo(LocalDate.of(2026, 8, 6));
	}

	@Test
	void takenAt_미상은_uploadedOn이_null이다() {
		var post = BrandPostAssembler.brandPost(100L,
				new BrandReadRepository.BrandTaggedPostRow("ABC", "glowdeep_92", "9001", null,
						OffsetDateTime.parse("2026-08-06T02:00:00Z"), 0L, null,
						OffsetDateTime.parse("2026-08-06T02:00:00Z"), null),
				null, null, List.of(), List.of(), SWEPT_AT, List.of());

		assertThat(BrandPostAssembler.uploadedOn(post)).isNull();
	}

	// ---------- 픽스처 ----------

	private static BrandReadRepository.BrandAccountRow accountRow() {
		return new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT);
	}

	private static BrandReadRepository.BrandTaggedPostRow taggedRow(String code) {
		return taggedRow(code, "2026-08-06T01:00:00Z");
	}

	private static BrandReadRepository.BrandTaggedPostRow taggedRow(String code, String takenAt) {
		return row(code, "2026-08-06T02:00:00Z", takenAt, null);
	}

	/** 범용 row 빌더 — tagDetectedAt·directRegisteredAt을 직접 지정해 source 파생을 검증한다. */
	private static BrandReadRepository.BrandTaggedPostRow row(String code, String tagDetectedAt, String takenAt,
			String directRegisteredAt) {
		return new BrandReadRepository.BrandTaggedPostRow(code, "glowdeep_92", "9001",
				OffsetDateTime.parse(takenAt), OffsetDateTime.parse("2026-08-06T02:00:00Z"), 7L, null,
				tagDetectedAt == null ? null : OffsetDateTime.parse(tagDetectedAt),
				directRegisteredAt == null ? null : OffsetDateTime.parse(directRegisteredAt));
	}

	private static BrandReadRepository.BrandPostMetaRow meta(String code, String contentType, Boolean paid) {
		return new BrandReadRepository.BrandPostMetaRow(code, "glowdeep_92", contentType,
				LocalDate.of(2026, 8, 6), "캡션 원문", "https://cdn/thumb.jpg",
				"https://cdn/video.mp4", 15.5, paid, null);
	}

	private static BrandReadRepository.BrandSnapshotRow snapshotRow(String code, int day, Long views) {
		return new BrandReadRepository.BrandSnapshotRow(code, LocalDate.of(2026, 8, day), "REELS",
				10L, false, 12L, views, 5L, 7L, false, 3L);
	}
}
