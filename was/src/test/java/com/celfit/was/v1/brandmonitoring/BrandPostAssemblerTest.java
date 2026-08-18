package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BrandPost 조립 규칙 단위 고정(스펙 §6-1) — 리포지토리 없이 row record를 손으로 만들어
 * 순수 변환만 검증한다. 배선(배치 조회·direct 필터)은 컨트롤러 슬라이스 테스트가 덮는다.
 */
class BrandPostAssemblerTest {

	private static final OffsetDateTime SWEPT_AT = OffsetDateTime.parse("2026-08-07T18:00:00Z");

	// ---------- 댓글 생략 배선(08-12 성과 대시보드 고정 지연 대응) ----------

	@Test
	void withComments_false면_댓글_배치_조회를_돌리지_않는다() {
		// 이 파일의 다른 테스트와 달리 배선 검증이라 mock을 쓴다 — 댓글 쿼리는 조립 시간의 절반
		// 이상이라(08-12 운영 덤프 실측) 슬림 경로에서 되살아나면 고정 지연이 재발한다.
		var repository = org.mockito.Mockito.mock(BrandReadRepository.class);
		var directRepository = org.mockito.Mockito.mock(com.celfit.was.monitoring.BrandDirectPostRepository.class);
		var trackingAssembler = org.mockito.Mockito.mock(com.celfit.was.v1.monitoring.TrackingItemAssembler.class);
		var account = new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT);
		org.mockito.Mockito.when(repository.findTaggedPostsInWindow(org.mockito.ArgumentMatchers.eq(42L),
						org.mockito.ArgumentMatchers.any()))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "creator", null,
						SWEPT_AT, SWEPT_AT, 0L)));

		var assembler = new BrandPostAssembler(repository, directRepository, trackingAssembler, false);
		var posts = assembler.assembleTagged(account, false, BrandPostAssembler.TaggedScope.ALL);

		org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
				.findComments(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.anyInt());
		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.recentComments()).isEmpty();
			assertThat(post.commentsCollectedCount()).isEqualTo(0);
		});
	}

	// ---------- 정산 게이트 분기(2026-08-13 완결 배치 서빙) ----------

	/**
	 * 표시 표면(ENRICHED_ONLY)만 정산분 조회로 가고, 판정·집계 표면(ALL)은 전량 조회로 간다 —
	 * 두 소비자의 성격이 달라 조회 자체가 갈린다. 여기서 뒤바뀌면 캠페인 존재 판정이 수집 중인
	 * 게시물을 NOT_FOUND로 떨구거나(ALL→ENRICHED_ONLY), 목록에 반쯤 빈 카드가 실린다(반대 방향).
	 */
	@Test
	void scope가_조회_경로를_가른다() {
		var repository = org.mockito.Mockito.mock(BrandReadRepository.class);
		var directRepository = org.mockito.Mockito.mock(com.celfit.was.monitoring.BrandDirectPostRepository.class);
		var trackingAssembler = org.mockito.Mockito.mock(com.celfit.was.v1.monitoring.TrackingItemAssembler.class);
		var account = new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT);
		var assembler = new BrandPostAssembler(repository, directRepository, trackingAssembler, false);

		assembler.assembleTagged(account, false, BrandPostAssembler.TaggedScope.ENRICHED_ONLY);
		org.mockito.Mockito.verify(repository)
				.findEnrichedTaggedPostsInWindow(org.mockito.ArgumentMatchers.eq(42L),
						org.mockito.ArgumentMatchers.any());
		org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
				.findTaggedPostsInWindow(org.mockito.ArgumentMatchers.anyLong(),
						org.mockito.ArgumentMatchers.any());

		org.mockito.Mockito.clearInvocations(repository);
		assembler.assembleTagged(account, false, BrandPostAssembler.TaggedScope.ALL);
		org.mockito.Mockito.verify(repository)
				.findTaggedPostsInWindow(org.mockito.ArgumentMatchers.eq(42L),
						org.mockito.ArgumentMatchers.any());
		org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
				.findEnrichedTaggedPostsInWindow(org.mockito.ArgumentMatchers.anyLong(),
						org.mockito.ArgumentMatchers.any());
	}

	/** 브랜드 화면 진입점(목록·상세·counts)은 표시 표면이라 정산분 조회로만 간다. */
	@Test
	void 브랜드_화면_조립은_정산분만_읽는다() {
		var repository = org.mockito.Mockito.mock(BrandReadRepository.class);
		var directRepository = org.mockito.Mockito.mock(com.celfit.was.monitoring.BrandDirectPostRepository.class);
		var trackingAssembler = org.mockito.Mockito.mock(com.celfit.was.v1.monitoring.TrackingItemAssembler.class);
		var account = new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT);
		var assembler = new BrandPostAssembler(repository, directRepository, trackingAssembler, false);

		assembler.assembleForBrand(7L, account);

		org.mockito.Mockito.verify(repository)
				.findEnrichedTaggedPostsInWindow(org.mockito.ArgumentMatchers.eq(42L),
						org.mockito.ArgumentMatchers.any());
		org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
				.findTaggedPostsInWindow(org.mockito.ArgumentMatchers.anyLong(),
						org.mockito.ArgumentMatchers.any());
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
		var post = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(snapshotRow("ABC", 5, 100L), snapshotRow("ABC", 6, 180L)), List.of(), SWEPT_AT);

		assertThat(post.snapshots()).extracting(TrackingItemResponse.SnapshotResponse::date)
				.containsExactly("2026-08-05", "2026-08-06");
		assertThat(post.latestSnapshot()).isEqualTo(post.snapshots().get(1));
		assertThat(post.latestSnapshot().views()).isEqualTo(180L);
	}

	// ---------- tagged 조립 ----------

	@Test
	void tagged는_REELS면_reel_URL_FEED면_p_URL이다() {
		var reels = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(), List.of(), SWEPT_AT);
		var feed = BrandPostAssembler.taggedPost(100L, taggedRow("DEF"), meta("DEF", "FEED", null),
				null, List.of(), List.of(), SWEPT_AT);

		assertThat(reels.postUrl()).isEqualTo("https://www.instagram.com/reel/ABC/");
		assertThat(reels.contentType()).isEqualTo("reels");
		assertThat(feed.postUrl()).isEqualTo("https://www.instagram.com/p/DEF/");
		assertThat(feed.contentType()).isEqualTo("feed");
	}

	@Test
	void tagged의_기본_필드는_열거행과_계정_스윕_시각에서_온다() {
		var post = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", true),
				null, List.of(), List.of(), SWEPT_AT);

		assertThat(post.id()).isEqualTo("ABC");
		assertThat(post.shortcode()).isEqualTo("ABC");
		assertThat(post.brandAccountId()).isEqualTo("100");
		assertThat(post.source()).isEqualTo("tagged");
		assertThat(post.trackingStatus()).isEqualTo("tracking");
		assertThat(post.trackingStartedAt()).isEqualTo("2026-08-06T11:00:00+09:00");
		assertThat(post.trackingEndedAt()).isNull();
		assertThat(post.takenAt()).isEqualTo("2026-08-06T10:00:00+09:00");
		assertThat(post.createdAt()).isEqualTo("2026-08-06T11:00:00+09:00");
		assertThat(post.updatedAt()).isEqualTo("2026-08-08T03:00:00+09:00");
		assertThat(post.campaignIds()).isEmpty();
	}

	@Test
	void tagged_협찬은_유료협찬_플래그와_캡션으로_판정한다() {
		var paid = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", true),
				null, List.of(), List.of(), SWEPT_AT);
		var organic = BrandPostAssembler.taggedPost(100L, taggedRow("DEF"), meta("DEF", "REELS", false),
				null, List.of(), List.of(), SWEPT_AT);
		var unknown = BrandPostAssembler.taggedPost(100L, taggedRow("GHI"), meta("GHI", "REELS", null),
				null, List.of(), List.of(), SWEPT_AT);

		assertThat(paid.sponsorship()).isEqualTo("sponsored");
		assertThat(paid.isPaidPartnership()).isTrue();
		assertThat(organic.sponsorship()).isEqualTo("organic");
		assertThat(unknown.sponsorship()).isEqualTo("unknown");
		assertThat(unknown.isPaidPartnership()).isNull();
	}

	@Test
	void 메타가_없으면_피드로_접고_표시필드는_null이다() {
		var post = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), null, null, List.of(), List.of(), SWEPT_AT);

		assertThat(post.contentType()).isEqualTo("feed");
		assertThat(post.postUrl()).isEqualTo("https://www.instagram.com/p/ABC/");
		assertThat(post.caption()).isNull();
		assertThat(post.thumbnailUrl()).isNull();
		assertThat(post.videoUrl()).isNull();
		assertThat(post.sponsorship()).isEqualTo("unknown");
	}

	@Test
	void 게시자_프로필_부재면_author_필드는_열거_관측값으로_폴백한다() {
		var post = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(), List.of(), SWEPT_AT);

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

		var post = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				author, List.of(), List.of(), SWEPT_AT);

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
				"monitor-brand-post/ABC.jpg", null, null, null);

		var post = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta, author, List.of(), List.of(), SWEPT_AT);

		assertThat(post.thumbnailUrl()).isEqualTo("/img/monitor-brand-post/ABC.jpg");
		assertThat(post.authorProfilePicUrl()).isEqualTo("/img/monitor-author/9001.jpg");
	}

	@Test
	void 무효_스킴_이미지_URL은_null로_강등한다() {
		var author = new BrandReadRepository.AuthorRow("9001", "glowdeep_92", "글로우딥",
				12345L, "javascript:alert(1)", true, null);
		var meta = new BrandReadRepository.BrandPostMetaRow("ABC", "glowdeep_92", "REELS",
				LocalDate.of(2026, 8, 6), "캡션", "data:image/png;base64,AAAA", null, null, null, null,
				null, null, null);

		var post = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta, author, List.of(), List.of(), SWEPT_AT);

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

		var post = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(), List.of(ok, broken), SWEPT_AT);

		assertThat(post.recentComments()).hasSize(1);
		assertThat(post.recentComments().get(0).author()).isEqualTo("gl***92");
		assertThat(post.recentComments().get(0).reply().text()).isEqualTo("감사합니다");
		assertThat(post.commentsCollectedCount()).isEqualTo(1L);
	}

	@Test
	void commentsTotal은_최신_스냅샷_댓글수고_null이면_숨김이다() {
		var hidden = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null), null,
				List.of(new BrandReadRepository.BrandSnapshotRow("ABC", LocalDate.of(2026, 8, 6), "REELS",
						10L, false, null, 100L, 1L, 2L, false, 3L)),
				List.of(), SWEPT_AT);
		var shown = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(snapshotRow("ABC", 6, 100L)), List.of(), SWEPT_AT);
		var noSnapshot = BrandPostAssembler.taggedPost(100L, taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(), List.of(), SWEPT_AT);

		assertThat(hidden.commentsTotal()).isNull();
		assertThat(hidden.commentsHidden()).isTrue();
		assertThat(shown.commentsTotal()).isEqualTo(12L);
		assertThat(shown.commentsHidden()).isFalse();
		// 스냅샷 자체가 없으면 "숨김"이 아니라 "아직 모름"이다.
		assertThat(noSnapshot.commentsHidden()).isFalse();
	}

	// ---------- direct 변환 ----------

	@Test
	void direct는_매핑행_shortcode와_레거시_아이템_상태를_쓴다() {
		var post = BrandPostAssembler.directPost(100L, "XYZ", trackingItem("ended", 55L),
				OffsetDateTime.parse("2026-08-07T17:00:00Z"));

		assertThat(post.id()).isEqualTo("XYZ");
		assertThat(post.shortcode()).isEqualTo("XYZ");
		assertThat(post.source()).isEqualTo("direct");
		assertThat(post.trackingStatus()).isEqualTo("ended");
		assertThat(post.brandAccountId()).isEqualTo("100");
		assertThat(post.contentType()).isEqualTo("reels");
		assertThat(post.postUrl()).isEqualTo("https://www.instagram.com/reel/XYZ/");
		assertThat(post.campaignIds()).containsExactly("55");
		assertThat(post.authorUsername()).isEqualTo("glowdeep_92");
		assertThat(post.authorProfileUrl()).isEqualTo("https://www.instagram.com/glowdeep_92/");
		assertThat(post.updatedAt()).isEqualTo("2026-08-08T02:00:00+09:00");
		assertThat(post.snapshots()).hasSize(1);
		assertThat(post.latestSnapshot().views()).isEqualTo(300L);
		assertThat(post.commentsTotal()).isEqualTo(9L);
		assertThat(post.isPaidPartnership()).isNull();
		assertThat(post.sponsorship()).isEqualTo("unknown");
	}

	@Test
	void direct는_캡션_키워드로_협찬을_판정한다() {
		var post = BrandPostAssembler.directPost(100L, "XYZ", trackingItemWithCaption("오늘의 #협찬 후기"), null);

		assertThat(post.sponsorship()).isEqualTo("sponsored");
	}

	@Test
	void direct는_게시물_미확정이면_빈_시계열과_p_URL로_접는다() {
		var item = TrackingItemResponse.full(42L, "url", "collecting", "", "", null, null, null, null, null,
				null, LocalDate.of(2026, 8, 1), 30, null, null, null);

		var post = BrandPostAssembler.directPost(100L, "XYZ", item, null);

		assertThat(post.contentType()).isNull();
		assertThat(post.postUrl()).isEqualTo("https://www.instagram.com/p/XYZ/");
		assertThat(post.snapshots()).isEmpty();
		assertThat(post.latestSnapshot()).isNull();
		assertThat(post.recentComments()).isEmpty();
		assertThat(post.takenAt()).isNull();
		assertThat(post.commentsHidden()).isFalse();
	}

	// ---------- 병합 ----------

	@Test
	void 같은_shortcode가_tagged와_direct_양쪽이면_direct_한_건이다() {
		var tagged = BrandPostAssembler.taggedPost(100L, taggedRow("XYZ"), meta("XYZ", "FEED", null),
				null, List.of(), List.of(), SWEPT_AT);
		var direct = BrandPostAssembler.directPost(100L, "XYZ", trackingItem("tracking", null), null);

		var merged = BrandPostAssembler.mergeByShortcode(List.of(direct), List.of(tagged));

		assertThat(merged).hasSize(1);
		assertThat(merged.get(0).source()).isEqualTo("direct");
		assertThat(merged.get(0).trackingStatus()).isEqualTo("tracking");
	}

	@Test
	void 병합은_tagged의_유료협찬_관측으로_direct_판정을_승격한다() {
		var tagged = BrandPostAssembler.taggedPost(100L, taggedRow("XYZ"), meta("XYZ", "FEED", true),
				null, List.of(), List.of(), SWEPT_AT);
		var direct = BrandPostAssembler.directPost(100L, "XYZ", trackingItem("tracking", null), null);

		var merged = BrandPostAssembler.mergeByShortcode(List.of(direct), List.of(tagged));

		assertThat(merged.get(0).source()).isEqualTo("direct");
		assertThat(merged.get(0).sponsorship()).isEqualTo("sponsored");
		assertThat(merged.get(0).isPaidPartnership()).isTrue();
	}

	@Test
	void 병합은_tagged_관측이_없으면_direct_판정을_유지한다() {
		var tagged = BrandPostAssembler.taggedPost(100L, taggedRow("XYZ"), meta("XYZ", "FEED", null),
				null, List.of(), List.of(), SWEPT_AT);
		var direct = BrandPostAssembler.directPost(100L, "XYZ", trackingItemWithCaption("오늘의 #협찬 후기"), null);

		var merged = BrandPostAssembler.mergeByShortcode(List.of(direct), List.of(tagged));

		assertThat(merged.get(0).sponsorship()).isEqualTo("sponsored");
		assertThat(merged.get(0).isPaidPartnership()).isNull();
	}

	/**
	 * 시딩+직접등록 중복 게시물 배지 은닉 방지(코디네이터 스펙 리뷰, 08-18) — direct 본체가 이기는
	 * 병합 규칙 때문에 tagged가 들고 있는 광고 판정 4필드가 조용히 버려지던 결함의 회귀 테스트.
	 */
	@Test
	void 병합은_tagged의_광고_판정_필드를_direct로_승격한다() {
		var adMeta = new BrandReadRepository.BrandPostMetaRow("XYZ", "glowdeep_92", "FEED",
				LocalDate.of(2026, 8, 6), "오늘 소개 #광고", null, null, null, null, null,
				"DISCLOSED", "[]", "[{\"phrase\":\"#광고\",\"category\":\"CLEAR\",\"offset\":5}]");
		var tagged = BrandPostAssembler.taggedPost(100L, taggedRow("XYZ"), adMeta, null, List.of(), List.of(),
				SWEPT_AT, true, Set.of("glowdeep_92"));
		var direct = BrandPostAssembler.directPost(100L, "XYZ", trackingItem("tracking", null), null);

		var merged = BrandPostAssembler.mergeByShortcode(List.of(direct), List.of(tagged));

		assertThat(merged.get(0).source()).isEqualTo("direct");
		assertThat(merged.get(0).adDisclosure()).isEqualTo("DISCLOSED");
		assertThat(merged.get(0).adEvidence()).singleElement()
				.satisfies(e -> assertThat(e.phrase()).isEqualTo("#광고"));
		assertThat(merged.get(0).seededAuthor()).isTrue();
	}

	@Test
	void 병합_결과는_업로드_최신순이고_takenAt_없는_건이_마지막이다() {
		var older = BrandPostAssembler.taggedPost(100L, taggedRow("OLD", "2026-08-01T10:00:00Z"),
				meta("OLD", "FEED", null), null, List.of(), List.of(), SWEPT_AT);
		var newer = BrandPostAssembler.taggedPost(100L, taggedRow("NEW", "2026-08-06T10:00:00Z"),
				meta("NEW", "FEED", null), null, List.of(), List.of(), SWEPT_AT);
		var pending = BrandPostAssembler.directPost(100L, "PEND",
				TrackingItemResponse.full(42L, "url", "collecting", "", "", null, null, null, null, null,
						null, LocalDate.of(2026, 8, 1), 30, null, null, null), null);

		var merged = BrandPostAssembler.mergeByShortcode(List.of(pending), List.of(older, newer));

		assertThat(merged).extracting(BrandPostResponse::shortcode).containsExactly("NEW", "OLD", "PEND");
	}

	// ---------- 광고 표기 판정 배선(2026-08-17 스펙 §9) ----------

	@Test
	void 광고_판정_필드가_응답에_실린다() {
		var repository = org.mockito.Mockito.mock(BrandReadRepository.class);
		var directRepository = org.mockito.Mockito.mock(com.celfit.was.monitoring.BrandDirectPostRepository.class);
		var trackingAssembler = org.mockito.Mockito.mock(com.celfit.was.v1.monitoring.TrackingItemAssembler.class);
		var account = new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT);
		org.mockito.Mockito.when(repository.findTaggedPostsInWindow(org.mockito.ArgumentMatchers.eq(42L),
						org.mockito.ArgumentMatchers.any()))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "creator1", null,
						SWEPT_AT, SWEPT_AT, 0L)));
		org.mockito.Mockito.when(repository.findPostMeta(org.mockito.ArgumentMatchers.anyCollection()))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("ABC", "creator1", "FEED",
						LocalDate.of(2026, 8, 7), "오늘 소개 #광고", null, null, null, null, null,
						"DISCLOSED", "[]", "[{\"phrase\":\"#광고\",\"category\":\"CLEAR\",\"offset\":5}]")));
		org.mockito.Mockito.when(repository.findSeededUsernames(42L)).thenReturn(List.of("creator1"));

		var assembler = new BrandPostAssembler(repository, directRepository, trackingAssembler, true);
		var posts = assembler.assembleTagged(account, false, BrandPostAssembler.TaggedScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.adDisclosure()).isEqualTo("DISCLOSED");
			assertThat(post.adEvidence()).singleElement()
					.satisfies(e -> assertThat(e.phrase()).isEqualTo("#광고"));
			assertThat(post.seededAuthor()).isTrue();
		});
	}

	@Test
	void 노출_토글이_꺼지면_광고_필드는_전부_비노출() {
		var repository = org.mockito.Mockito.mock(BrandReadRepository.class);
		var directRepository = org.mockito.Mockito.mock(com.celfit.was.monitoring.BrandDirectPostRepository.class);
		var trackingAssembler = org.mockito.Mockito.mock(com.celfit.was.v1.monitoring.TrackingItemAssembler.class);
		var account = new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT);
		org.mockito.Mockito.when(repository.findTaggedPostsInWindow(org.mockito.ArgumentMatchers.eq(42L),
						org.mockito.ArgumentMatchers.any()))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "creator1", null,
						SWEPT_AT, SWEPT_AT, 0L)));
		org.mockito.Mockito.when(repository.findPostMeta(org.mockito.ArgumentMatchers.anyCollection()))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("ABC", "creator1", "FEED",
						LocalDate.of(2026, 8, 7), "오늘 소개 #광고", null, null, null, null, null,
						"DISCLOSED", "[]", "[]")));

		// 토글 off — findSeededUsernames를 호출조차 하지 않는다(드라이런 중 불필요한 조회 방지)
		var assembler = new BrandPostAssembler(repository, directRepository, trackingAssembler, false);
		var posts = assembler.assembleTagged(account, false, BrandPostAssembler.TaggedScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.adDisclosure()).isNull();
			assertThat(post.adViolations()).isEmpty();
			assertThat(post.adEvidence()).isEmpty();
			assertThat(post.seededAuthor()).isFalse();
		});
		org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
				.findSeededUsernames(org.mockito.ArgumentMatchers.anyLong());
	}

	// ---------- 픽스처 ----------

	private static BrandReadRepository.BrandTaggedPostRow taggedRow(String code) {
		return taggedRow(code, "2026-08-06T01:00:00Z");
	}

	private static BrandReadRepository.BrandTaggedPostRow taggedRow(String code, String takenAt) {
		return new BrandReadRepository.BrandTaggedPostRow(code, "glowdeep_92", "9001",
				OffsetDateTime.parse(takenAt), OffsetDateTime.parse("2026-08-06T02:00:00Z"), 7L);
	}

	private static BrandReadRepository.BrandPostMetaRow meta(String code, String contentType, Boolean paid) {
		return new BrandReadRepository.BrandPostMetaRow(code, "glowdeep_92", contentType,
				LocalDate.of(2026, 8, 6), "캡션 원문", "https://cdn/thumb.jpg",
				"https://cdn/video.mp4", 15.5, paid, null, null, null, null);
	}

	private static BrandReadRepository.BrandSnapshotRow snapshotRow(String code, int day, Long views) {
		return new BrandReadRepository.BrandSnapshotRow(code, LocalDate.of(2026, 8, day), "REELS",
				10L, false, 12L, views, 5L, 7L, false, 3L);
	}

	private static TrackingItemResponse trackingItem(String status, Long campaignId) {
		return trackingItem(status, campaignId, "일상 기록");
	}

	private static TrackingItemResponse trackingItemWithCaption(String caption) {
		return trackingItem("tracking", null, caption);
	}

	private static TrackingItemResponse trackingItem(String status, Long campaignId, String caption) {
		var snapshot = new TrackingItemResponse.SnapshotResponse("2026-08-06", 300L, 20L, false, 9L,
				4L, 2L, false, 1L);
		var comment = new TrackingItemResponse.PostCommentResponse("c1", "gl***92", "좋아요", 3L,
				"2026-08-06T14:00:00+09:00", null);
		var post = new TrackingItemResponse.TrackedPostResponse("https://www.instagram.com/reel/XYZ/", "reels",
				"2026-08-06", caption, List.of(), "https://cdn/legacy-thumb.jpg", null,
				List.of(snapshot), List.of(comment));
		return TrackingItemResponse.full(42L, "url", status, "glowdeep_92", "글로우딥",
				"https://cdn/author.jpg", 12345L, "2026-08-06", campaignId, campaignId == null ? null : "캠페인",
				"https://www.instagram.com/reel/XYZ/", LocalDate.of(2026, 8, 1), 30, null, post, null);
	}
}
