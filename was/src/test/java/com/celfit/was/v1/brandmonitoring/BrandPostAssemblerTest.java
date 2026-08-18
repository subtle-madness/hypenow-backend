package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BrandPost 조립 규칙 단위 고정(2026-08-18 direct 통합 §3-3 + 광고 표기 판정 §9) — 리포지토리 없이
 * row record를 손으로 만들어 순수 변환만 검증한다. 배선(배치 조회·과도기 폴백)은 컨트롤러 슬라이스
 * 테스트가 덮는다.
 *
 * <p>tagged·direct는 더 이상 별도 조립 함수·병합 단계를 갖지 않는다 — 둘 다 {@code brand_tagged_post}
 * 같은 행의 파생값이라({@link BrandPostAssembler} 클래스 주석) 광고 표기 판정 4필드도 source와 무관하게
 * 그 행의 meta에서 직접 채워진다. 구 모델의 "tagged 값을 direct로 승격" 병합 로직은 소멸했다 — 아래
 * {@code 겹침_행도_광고_판정_필드가_채워진다} 테스트가 그 회귀를 새 모델로 고정한다.
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
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(taggedRow("ABC")));

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, false);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL);

		verify(repository, never()).findComments(anyCollection(), anyInt());
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
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, false);

		assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ENRICHED_ONLY);
		verify(repository).findBrandPostsInWindow(eq(42L), any(), eq(true));

		clearInvocations(repository);
		assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL);
		verify(repository).findBrandPostsInWindow(eq(42L), any(), eq(false));
	}

	/** 브랜드 화면 진입점(목록·상세·counts)은 표시 표면이라 정산분 조회로만 간다. */
	@Test
	void 브랜드_화면_조립은_정산분만_읽는다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, false);

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
		var post = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null),
				null, List.of(snapshotRow("ABC", 5, 100L), snapshotRow("ABC", 6, 180L)), List.of(), List.of());

		assertThat(post.snapshots()).extracting(TrackingItemResponse.SnapshotResponse::date)
				.containsExactly("2026-08-05", "2026-08-06");
		assertThat(post.latestSnapshot()).isEqualTo(post.snapshots().get(1));
		assertThat(post.latestSnapshot().views()).isEqualTo(180L);
	}

	// ---------- brandPost 조립 ----------

	@Test
	void REELS면_reel_URL_FEED면_p_URL이다() {
		var reels = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null), null, List.of(), List.of(), List.of());
		var feed = brandPost(taggedRow("DEF"), meta("DEF", "FEED", null), null, List.of(), List.of(), List.of());

		assertThat(reels.postUrl()).isEqualTo("https://www.instagram.com/reel/ABC/");
		assertThat(reels.contentType()).isEqualTo("reels");
		assertThat(feed.postUrl()).isEqualTo("https://www.instagram.com/p/DEF/");
		assertThat(feed.contentType()).isEqualTo("feed");
	}

	@Test
	void tagged의_기본_필드는_열거행과_계정_스윕_시각에서_온다() {
		var post = brandPost(taggedRow("ABC"), meta("ABC", "REELS", true), null, List.of(), List.of(), List.of());

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
		var taggedOnly = brandPost(row("ABC", null, "2026-08-06T02:00:00Z", null),
				null, null, List.of(), List.of(), List.of());
		var directOnly = brandPost(row("DEF", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z"),
				null, null, List.of(), List.of(), List.of());
		var overlap = brandPost(row("GHI", "2026-08-06T02:00:00Z", "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z"),
				null, null, List.of(), List.of(), List.of());

		assertThat(taggedOnly.source()).isEqualTo("tagged");
		assertThat(directOnly.source()).isEqualTo("direct");
		// 겹침(tag_detected_at·direct_registered_at 둘 다 값이 있음) → direct가 이긴다(파생 규칙).
		assertThat(overlap.source()).isEqualTo("direct");
	}

	/** trackingStartedAt = COALESCE(direct_registered_at, first_seen_at) — direct는 등록 시점부터. */
	@Test
	void direct_행의_trackingStartedAt은_등록_시점이다() {
		var post = brandPost(row("DEF", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z"),
				meta("DEF", "REELS", null), null, List.of(), List.of(), List.of());

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
				null, null, List.of(), List.of(), accountSwept, List.of(), false, Set.of());
		var accountWins = BrandPostAssembler.brandPost(100L,
				new BrandReadRepository.BrandTaggedPostRow("ABC", "glowdeep_92", "9001",
						OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
						7L, rowCrawledEarlier, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null),
				null, null, List.of(), List.of(), accountSwept, List.of(), false, Set.of());
		var rowNull = BrandPostAssembler.brandPost(100L,
				taggedRow("ABC"), null, null, List.of(), List.of(), accountSwept, List.of(), false, Set.of());

		assertThat(rowWins.updatedAt()).isEqualTo(KstTimestamps.toKstIso(rowCrawledLater));
		assertThat(accountWins.updatedAt()).isEqualTo(KstTimestamps.toKstIso(accountSwept));
		assertThat(rowNull.updatedAt()).isEqualTo(KstTimestamps.toKstIso(accountSwept));
	}

	@Test
	void campaignIds가_채워진다() {
		var post = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null), null, List.of(), List.of(),
				List.of("55", "56"));

		assertThat(post.campaignIds()).containsExactly("55", "56");
	}

	@Test
	void 협찬은_유료협찬_플래그와_캡션으로_판정한다() {
		var paid = brandPost(taggedRow("ABC"), meta("ABC", "REELS", true), null, List.of(), List.of(), List.of());
		var organic = brandPost(taggedRow("DEF"), meta("DEF", "REELS", false), null, List.of(), List.of(), List.of());
		var unknown = brandPost(taggedRow("GHI"), meta("GHI", "REELS", null), null, List.of(), List.of(), List.of());

		assertThat(paid.sponsorship()).isEqualTo("sponsored");
		assertThat(paid.isPaidPartnership()).isTrue();
		assertThat(organic.sponsorship()).isEqualTo("organic");
		assertThat(unknown.sponsorship()).isEqualTo("unknown");
		assertThat(unknown.isPaidPartnership()).isNull();
	}

	@Test
	void 메타가_없으면_피드로_접고_표시필드는_null이다() {
		var post = brandPost(taggedRow("ABC"), null, null, List.of(), List.of(), List.of());

		assertThat(post.contentType()).isEqualTo("feed");
		assertThat(post.postUrl()).isEqualTo("https://www.instagram.com/p/ABC/");
		assertThat(post.caption()).isNull();
		assertThat(post.thumbnailUrl()).isNull();
		assertThat(post.videoUrl()).isNull();
		assertThat(post.sponsorship()).isEqualTo("unknown");
	}

	@Test
	void 게시자_프로필_부재면_author_필드는_열거_관측값으로_폴백한다() {
		var post = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null), null, List.of(), List.of(), List.of());

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

		var post = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null), author, List.of(), List.of(), List.of());

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

		var post = brandPost(taggedRow("ABC"), meta, author, List.of(), List.of(), List.of());

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

		var post = brandPost(taggedRow("ABC"), meta, author, List.of(), List.of(), List.of());

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

		var post = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null), null, List.of(), List.of(ok, broken),
				List.of());

		assertThat(post.recentComments()).hasSize(1);
		assertThat(post.recentComments().get(0).author()).isEqualTo("gl***92");
		assertThat(post.recentComments().get(0).reply().text()).isEqualTo("감사합니다");
		assertThat(post.commentsCollectedCount()).isEqualTo(1L);
	}

	@Test
	void commentsTotal은_최신_스냅샷_댓글수고_null이면_숨김이다() {
		var hidden = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null), null,
				List.of(new BrandReadRepository.BrandSnapshotRow("ABC", LocalDate.of(2026, 8, 6), "REELS",
						10L, false, null, 100L, 1L, 2L, false, 3L)),
				List.of(), List.of());
		var shown = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null), null,
				List.of(snapshotRow("ABC", 6, 100L)), List.of(), List.of());
		var noSnapshot = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null), null, List.of(), List.of(),
				List.of());

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
				15.5, true, null, null, null, null);
		var author = new BrandReadRepository.AuthorRow("9001", "glowdeep_92", "글로우딥",
				12345L, "https://cdn/author.jpg", true, null);
		var directRow = row("XYZ", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z");

		var post = brandPost(directRow, meta, author, List.of(), List.of(), List.of());

		assertThat(post.source()).isEqualTo("direct");
		assertThat(post.videoUrl()).isEqualTo("https://cdn/video.mp4");
		assertThat(post.videoDuration()).isEqualTo(15.5);
		assertThat(post.authorIsVerified()).isTrue();
		assertThat(post.isPaidPartnership()).isTrue();
		assertThat(post.sponsorship()).isEqualTo("sponsored");
	}

	/**
	 * 겹침 행(tag_detected_at·direct_registered_at 둘 다 값이 있어 source="direct"로 파생됨)도 광고
	 * 표기 판정 4필드가 채워진다 — 구 모델은 tagged·direct가 별도 행이라 "tagged 값을 direct로 승격"하는
	 * 병합 단계가 없으면 배지가 조용히 사라졌지만(코디네이터 스펙 리뷰가 지적한 결함), 새 모델은
	 * brand_tagged_post가 shortcode당 행 하나뿐이라 meta도 하나뿐이다 — source가 무엇이든 같은 meta를
	 * 읽으므로 승격 자체가 필요 없다.
	 */
	@Test
	void 겹침_행도_광고_판정_필드가_채워진다() {
		var overlapRow = row("XYZ", "2026-08-06T02:00:00Z", "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z");
		var adMeta = new BrandReadRepository.BrandPostMetaRow("XYZ", "glowdeep_92", "FEED",
				LocalDate.of(2026, 8, 6), "오늘 소개 #광고", null, null, null, null, null,
				"DISCLOSED", "[]", "[{\"phrase\":\"#광고\",\"category\":\"CLEAR\",\"offset\":5}]");

		var post = BrandPostAssembler.brandPost(100L, overlapRow, adMeta, null, List.of(), List.of(), SWEPT_AT,
				List.of(), true, Set.of("glowdeep_92"));

		assertThat(post.source()).isEqualTo("direct");
		assertThat(post.adDisclosure()).isEqualTo("DISCLOSED");
		assertThat(post.adEvidence()).singleElement()
				.satisfies(e -> assertThat(e.phrase()).isEqualTo("#광고"));
		assertThat(post.seededAuthor()).isTrue();
	}

	// ---------- 정렬 ----------

	@Test
	void uploadedOn은_takenAt_앞_10자를_KST_날짜로_파싱한다() {
		var post = brandPost(taggedRow("ABC", "2026-08-06T01:00:00Z"), null, null, List.of(), List.of(), List.of());

		assertThat(BrandPostAssembler.uploadedOn(post)).isEqualTo(LocalDate.of(2026, 8, 6));
	}

	@Test
	void takenAt_미상은_uploadedOn이_null이다() {
		var post = brandPost(
				new BrandReadRepository.BrandTaggedPostRow("ABC", "glowdeep_92", "9001", null,
						OffsetDateTime.parse("2026-08-06T02:00:00Z"), 0L, null,
						OffsetDateTime.parse("2026-08-06T02:00:00Z"), null),
				null, null, List.of(), List.of(), List.of());

		assertThat(BrandPostAssembler.uploadedOn(post)).isNull();
	}

	// ---------- 광고 표기 판정 배선(2026-08-17 스펙 §9) ----------

	@Test
	void 광고_판정_필드가_응답에_실린다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		when(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "creator1", null,
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null)));
		when(repository.findPostMeta(anyCollection()))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("ABC", "creator1", "FEED",
						LocalDate.of(2026, 8, 7), "오늘 소개 #광고", null, null, null, null, null,
						"DISCLOSED", "[]", "[{\"phrase\":\"#광고\",\"category\":\"CLEAR\",\"offset\":5}]")));
		when(itemRepository.findCampaignLinkedAccountHandles(7L)).thenReturn(List.of("creator1"));
		when(campaignRepository.findShortCodesByUser(7L)).thenReturn(List.of());
		when(directRepository.findCampaignLinkedShortCodes(7L)).thenReturn(List.of());

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, true);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.adDisclosure()).isEqualTo("DISCLOSED");
			assertThat(post.adEvidence()).singleElement()
					.satisfies(e -> assertThat(e.phrase()).isEqualTo("#광고"));
			assertThat(post.seededAuthor()).isTrue();
		});
	}

	// ---------- 시딩 판정(캠페인 도출, 2026-08-18 재설계 — 신설 시딩 계정 관리 표면 철회) ----------

	/** 캠페인 연결 계정 추적(mode=account)의 핸들이 시딩 집합에 들어가면 그 작성자의 게시물은 seededAuthor=true다. */
	@Test
	void 캠페인_연결_계정_추적의_작성자는_seededAuthor_true다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		when(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "seed_creator", null,
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null)));
		when(repository.findPostMeta(anyCollection())).thenReturn(List.of());
		when(itemRepository.findCampaignLinkedAccountHandles(7L)).thenReturn(List.of("seed_creator"));
		when(campaignRepository.findShortCodesByUser(7L)).thenReturn(List.of());
		when(directRepository.findCampaignLinkedShortCodes(7L)).thenReturn(List.of());

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, true);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> assertThat(post.seededAuthor()).isTrue());
	}

	/**
	 * 캠페인 연결 게시물(신규 등록·이관 완료분은 app.brand_post_campaigns, 이관 전 레거시 direct
	 * 등록은 monitoring_items 경유)의 게시자(brand_post_meta.username, monitoring DB)가 시딩 집합에
	 * 들어가면, 그 작성자의 <b>다른</b> 게시물에도 seededAuthor=true가 붙는다 — 시딩 여부는 특정
	 * 게시물이 아니라 작성자 단위 신호다. 이 테스트는 신규 산지({@link BrandPostCampaignRepository#
	 * findShortCodesByUser})를 검증한다.
	 */
	@Test
	void 캠페인_연결_게시물_작성자는_다른_게시물에도_seededAuthor_true다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		when(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "direct_creator", null,
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null)));
		// 태그 자체 메타 조회(codes={"ABC"})와 시딩 산출용 조회(codes={"XYZ"})가 같은 메서드를 서로
		// 다른 인자로 호출한다 — exact 매처로 구분해 둘을 뒤섞지 않는다.
		when(repository.findPostMeta(eq(Set.of("ABC")))).thenReturn(List.of());
		when(repository.findPostMeta(eq(Set.of("XYZ"))))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("XYZ", "direct_creator", "FEED",
						LocalDate.of(2026, 8, 7), null, null, null, null, null, null, null, null, null)));
		when(itemRepository.findCampaignLinkedAccountHandles(7L)).thenReturn(List.of());
		when(campaignRepository.findShortCodesByUser(7L)).thenReturn(List.of("XYZ"));
		when(directRepository.findCampaignLinkedShortCodes(7L)).thenReturn(List.of());

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, true);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> assertThat(post.seededAuthor()).isTrue());
	}

	/**
	 * 이관 전(migrated_at IS NULL) 레거시 direct 등록의 캠페인 연결(monitoring_items 경유)도 같은
	 * 방식으로 seededAuthor를 붙인다 — 과도기 소스({@link BrandDirectPostRepository#
	 * findCampaignLinkedShortCodes})가 신규 소스와 합집합으로 동작함을 검증한다.
	 */
	@Test
	void 이관_전_레거시_direct_캠페인_연결_작성자도_seededAuthor_true다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		when(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "legacy_direct_creator", null,
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null)));
		when(repository.findPostMeta(eq(Set.of("ABC")))).thenReturn(List.of());
		when(repository.findPostMeta(eq(Set.of("XYZ"))))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("XYZ", "legacy_direct_creator", "FEED",
						LocalDate.of(2026, 8, 7), null, null, null, null, null, null, null, null, null)));
		when(itemRepository.findCampaignLinkedAccountHandles(7L)).thenReturn(List.of());
		when(campaignRepository.findShortCodesByUser(7L)).thenReturn(List.of());
		when(directRepository.findCampaignLinkedShortCodes(7L)).thenReturn(List.of("XYZ"));

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, true);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> assertThat(post.seededAuthor()).isTrue());
	}

	/** 캠페인 연결이 전혀 없는 작성자는 seededAuthor=false다(캠페인 없는 계정 추적 케이스). */
	@Test
	void 캠페인_연결이_없으면_seededAuthor_false다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		when(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "no_campaign_creator", null,
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null)));
		when(repository.findPostMeta(anyCollection())).thenReturn(List.of());
		when(itemRepository.findCampaignLinkedAccountHandles(7L)).thenReturn(List.of());
		when(campaignRepository.findShortCodesByUser(7L)).thenReturn(List.of());
		when(directRepository.findCampaignLinkedShortCodes(7L)).thenReturn(List.of());

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, true);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> assertThat(post.seededAuthor()).isFalse());
	}

	/**
	 * 손상된 jsonb 텍스트(파싱 실패)가 목록 조회 전체를 500으로 죽이지 않는지 확인(품질 리뷰, 08-18) —
	 * 손상된 필드(adViolations)만 빈 목록으로 격리되고, 같은 게시물의 다른 필드(adDisclosure·
	 * 정상 adEvidence)는 그대로 응답에 실린다.
	 */
	@Test
	void 손상된_jsonb는_해당_필드만_빈_목록으로_격리하고_응답은_정상이다() {
		var meta = new BrandReadRepository.BrandPostMetaRow("ABC", "creator1", "FEED",
				LocalDate.of(2026, 8, 7), "오늘 소개 #광고", null, null, null, null, null,
				"DISCLOSED", "{broken", "[{\"phrase\":\"#광고\",\"category\":\"CLEAR\",\"offset\":5}]");

		var post = BrandPostAssembler.brandPost(100L, taggedRow("ABC"), meta, null, List.of(), List.of(), SWEPT_AT,
				List.of(), true, Set.of());

		assertThat(post.adDisclosure()).isEqualTo("DISCLOSED");
		assertThat(post.adViolations()).isEmpty();
		assertThat(post.adEvidence()).singleElement()
				.satisfies(e -> assertThat(e.phrase()).isEqualTo("#광고"));
	}

	@Test
	void 노출_토글이_꺼지면_광고_필드는_전부_비노출() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		when(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.thenReturn(List.of(new BrandReadRepository.BrandTaggedPostRow("ABC", "creator1", null,
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null)));
		when(repository.findPostMeta(anyCollection()))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("ABC", "creator1", "FEED",
						LocalDate.of(2026, 8, 7), "오늘 소개 #광고", null, null, null, null, null,
						"DISCLOSED", "[]", "[]")));

		// 토글 off — 시딩 산출 조회(findCampaignLinkedAccountHandles·findShortCodesByUser·
		// findCampaignLinkedShortCodes)를 호출조차 하지 않는다(드라이런 중 불필요한 조회 방지).
		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, false);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.adDisclosure()).isNull();
			assertThat(post.adViolations()).isEmpty();
			assertThat(post.adEvidence()).isEmpty();
			assertThat(post.seededAuthor()).isFalse();
		});
		verify(itemRepository, never()).findCampaignLinkedAccountHandles(anyLong());
		verify(campaignRepository, never()).findShortCodesByUser(anyLong());
		verify(directRepository, never()).findCampaignLinkedShortCodes(anyLong());
	}

	// ---------- 픽스처 ----------

	private static BrandPostAssembler newAssembler(BrandReadRepository repository,
			BrandPostCampaignRepository campaignRepository, BrandDirectPostRepository directRepository,
			TrackingItemAssembler trackingAssembler, MonitoringItemRepository itemRepository,
			boolean exposeAdDisclosure) {
		return new BrandPostAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, exposeAdDisclosure);
	}

	/** brandPost() 호출 축약 — 이 파일 대다수 테스트는 brandId=100, lastSweptAt=SWEPT_AT, 노출 off. */
	private static BrandPostResponse brandPost(BrandReadRepository.BrandTaggedPostRow post,
			BrandReadRepository.BrandPostMetaRow meta, BrandReadRepository.AuthorRow author,
			List<BrandReadRepository.BrandSnapshotRow> snapshotRows,
			List<BrandReadRepository.BrandCommentRow> commentRows, List<String> campaignIds) {
		return BrandPostAssembler.brandPost(100L, post, meta, author, snapshotRows, commentRows, SWEPT_AT,
				campaignIds, false, Set.of());
	}

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
				"https://cdn/video.mp4", 15.5, paid, null, null, null, null);
	}

	private static BrandReadRepository.BrandSnapshotRow snapshotRow(String code, int day, Long views) {
		return new BrandReadRepository.BrandSnapshotRow(code, LocalDate.of(2026, 8, day), "REELS",
				10L, false, 12L, views, 5L, 7L, false, 3L);
	}
}
