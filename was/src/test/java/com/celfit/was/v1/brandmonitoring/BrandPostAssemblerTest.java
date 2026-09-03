package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import java.util.Map;
import java.util.Locale;
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
		// 댓글 행 대신 count(*) 집계로 저장 건수를 채운다(2026-09-03, FE 피드백 #4-B — 이전엔 0)
		given(repository.countComments(anyCollection())).willReturn(Map.of("ABC", 45L));

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, false);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

		verify(repository, never()).findComments(anyCollection(), anyInt());
		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.recentComments()).isEmpty();
			assertThat(post.commentsCollectedCount()).isEqualTo(45L);
		});
	}

	// ---------- 인덱스/하이드레이트 분리(2026-08-27 목록 타임아웃 해소) ----------

	@Test
	void withoutRecentComments는_댓글만_비운다() {
		var comment = new BrandReadRepository.BrandCommentRow("ABC", "c1", "user_a", "본문", 3L,
				OffsetDateTime.parse("2026-08-06T03:00:00Z"), null);
		var full = brandPost(taggedRow("ABC"), meta("ABC", "REELS", true), null,
				List.of(snapshotRow("ABC", 6, 100L)), List.of(comment), List.of("7"));

		var stripped = full.withoutRecentComments();

		assertThat(stripped.recentComments()).isEmpty();
		// 저장 댓글 수는 유지(2026-09-03, FE 피드백 #4-B) — 목록에서도 "수집 댓글 N"을 표기해야 한다.
		assertThat(stripped.commentsCollectedCount()).isEqualTo(1L);
		// 나머지 필드는 전부 그대로 — 스냅샷 유래 지표(commentsTotal)와 표시 필드가 흔들리면 안 된다.
		assertThat(stripped.commentsTotal()).isEqualTo(full.commentsTotal());
		assertThat(stripped.snapshots()).isEqualTo(full.snapshots());
		assertThat(stripped.sponsorship()).isEqualTo(full.sponsorship());
		assertThat(stripped.campaignIds()).isEqualTo(full.campaignIds());
	}

	/**
	 * 인덱스는 <b>조립 입력을 붙잡지 않는다</b>(2026-08-28 힙 실측) — {@code poolCodes}가
	 * {@code LinkedHashMap.keySet()} 뷰면 원시 행 맵({@code BrandPostIndexRow} 전량)이 통째로 함께
	 * 살아남는다. 요청마다 버려지던 시절엔 무해했지만 {@link BrandIndexCache}가 인덱스를 장기 보관하면서
	 * 그 원시 행까지 장기 상주로 승격됐다(로컬 풀GC 후 히스토그램에서 11,438개 잔존 확인).
	 *
	 * <p>불변 복사본인지를 단정하는 것은 "맵 뷰가 아니다"의 관측 가능한 대리 지표다 — 힙 유지를
	 * 단위 테스트로 직접 재긴 어렵고, 회귀 시 되살아나는 형태가 정확히 {@code keySet()} 반환이다.
	 */
	@Test
	void 인덱스의_poolCodes는_맵_뷰가_아니라_복사본이다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("TAG1", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null, null, "일상")));

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), false);

		assertThat(index.poolCodes()).containsExactly("TAG1");
		assertThatThrownBy(() -> index.poolCodes().remove("TAG1"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	/** 인덱스 패스는 counts·정렬·페이지 계산 전용이라 무거운 배치 조회(스냅샷·댓글·게시자·표시 메타)가 없어야 한다. */
	@Test
	void 인덱스는_스냅샷_댓글_게시자_조회를_돌리지_않는다() {
		var repository = mock(BrandReadRepository.class);
		var account = accountRow();
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("TAG1", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null, null, "#협찬 후기"),
				indexRow("BOTH", "2026-08-05T01:00:00Z", "2026-08-05T02:00:00Z", "2026-08-06T00:00:00Z",
						false, "일상")));
		var directRepository = mock(BrandDirectPostRepository.class);
		given(directRepository.shortCodesByUser(7L)).willReturn(Set.of("BOTH"));

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class), directRepository,
				mock(TrackingItemAssembler.class), mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, account, false);

		verify(repository, never()).findSnapshots(anyCollection());
		verify(repository, never()).findComments(anyCollection(), anyInt());
		verify(repository, never()).findAuthors(anyCollection());
		verify(repository, never()).findPostMeta(anyCollection());
		// 풀 행 전량 조회가 인덱스에 되살아나면 안 된다 — 만 건대 브랜드에서 행×컬럼 매핑이 지배 비용(실측).
		verify(repository, never()).findBrandPostsInWindow(anyLong(), any(), anyBoolean());
		assertThat(index.refs()).hasSize(2);
		var tag1 = index.refs().stream().filter(r -> r.shortcode().equals("TAG1")).findFirst().orElseThrow();
		assertThat(tag1.source()).isEqualTo("tagged");
		assertThat(tag1.sponsorship()).isEqualTo("sponsored");        // 캡션 키워드 — 풀 조립과 같은 판정 함수
		assertThat(tag1.uploadedOn()).isEqualTo(LocalDate.of(2026, 8, 6));   // KST 달력일(UTC 08-06T01Z → KST 10시)
		var both = index.refs().stream().filter(r -> r.shortcode().equals("BOTH")).findFirst().orElseThrow();
		assertThat(both.source()).isEqualTo("direct");                // 겹침 행 + 등록자 → direct
		assertThat(both.sponsorship()).isEqualTo("organic");
	}

	/** withViews 인덱스의 정렬 키는 서빙 규칙(피드 views null)과 같아야 performance 정렬이 풀 조립과 일치한다. */
	@Test
	void 인덱스는_performance용_최신지표를_피드면_views_null로_접는다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("REELS1", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null, null, null),
				indexRow("FEED1", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null, null, null)));
		given(repository.findLatestSnapshotsForBrand(anyLong(), any(), anyBoolean())).willReturn(List.of(
				new BrandReadRepository.LatestSnapshotRow("REELS1", LocalDate.parse("2026-08-06"), "REELS",
						500L, 30L, false, 4L),
				new BrandReadRepository.LatestSnapshotRow("FEED1", LocalDate.parse("2026-08-06"), "FEED",
						300L, 20L, false, 2L)));

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), true);

		var reels = index.refs().stream().filter(r -> r.shortcode().equals("REELS1")).findFirst().orElseThrow();
		var feed = index.refs().stream().filter(r -> r.shortcode().equals("FEED1")).findFirst().orElseThrow();
		assertThat(reels.latestViews()).isEqualTo(500L);
		assertThat(feed.latestViews()).isNull();   // 피드 views null 서빙 규칙(snapshotOf) 동형
	}

	/**
	 * 신규 필터·패싯 판정값(2026-08-27 서버 필터 설계) — 인덱스 행의 매체·광고 판정·게시자 표시값이
	 * 그대로 ref로 옮겨진다. 프로필 사진은 풀 카드와 같은 산지 규칙({@code resolveImageUrl} — 아카이브
	 * 우선)을 태워야 목록 카드와 ref가 다른 URL을 말하지 않는다.
	 */
	@Test
	void 인덱스_ref는_매체_광고판정_게시자값을_싣는다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				authorIndexRow("REELS1", "REELS", "DISCLOSED", "glowdeep_92", "글로우딥",
						"https://cdn/author.jpg", "monitor-author/9001.jpg", 12345L),
				authorIndexRow("BARE", null, null, "bare_user", null, null, null, null)));

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), false);

		var reels = index.refs().stream().filter(r -> r.shortcode().equals("REELS1")).findFirst().orElseThrow();
		assertThat(reels.contentType()).isEqualTo("reels");
		assertThat(reels.adVerdict()).isEqualTo("DISCLOSED");
		assertThat(reels.authorUsername()).isEqualTo("glowdeep_92");
		assertThat(reels.authorFullName()).isEqualTo("글로우딥");
		// 아카이브 사본이 원본 CDN보다 우선(resolveImageUrl 동형) — 풀 카드와 같은 값이어야 한다.
		assertThat(reels.authorProfilePicUrl()).isEqualTo("/img/monitor-author/9001.jpg");
		assertThat(reels.authorFollowers()).isEqualTo(12345L);
		assertThat(reels.takenAtKst()).isEqualTo("2026-08-06T10:00:00+09:00");   // 카드 takenAt과 같은 문자열
		// content_type 불명은 피드로 접는다(brandPost·snapshotOf와 같은 방향).
		var bare = index.refs().stream().filter(r -> r.shortcode().equals("BARE")).findFirst().orElseThrow();
		assertThat(bare.contentType()).isEqualTo("feed");
		assertThat(bare.adVerdict()).isNull();
		// 조인이 전부 해결됐으면 폴백 배치 조회 자체를 돌리지 않는다(resolveAuthors 2차 SQL 관용구 동형).
		verify(repository, never()).findAuthorsByUsername(anyCollection());
	}

	/**
	 * 인덱스 hashtags 배선(2026-08-31 캡션 해시태그 탑재 품질 리뷰 반영) — findBrandPostIndex가
	 * withCaptions=true로 실어 온 caption에서 BrandCaptionHashtags.extract가 뽑은 태그가
	 * PostRef.hashtags()에 그대로 실려야 한다. row.caption()이 다른 값으로 바뀌어도 이 테스트가
	 * 잡는다(인자 채움용 테스트만으로는 이 배선 자체의 회귀를 못 잡는다는 리뷰 지적).
	 */
	@Test
	void 인덱스_ref는_캡션에서_추출한_해시태그를_싣는다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("TAG1", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null, null,
						"#세일 안내 #OliveYoung"),
				indexRow("NOMETA", "2026-08-05T01:00:00Z", "2026-08-05T02:00:00Z", null, null, null)));

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), false);

		var tag1 = index.refs().stream().filter(r -> r.shortcode().equals("TAG1")).findFirst().orElseThrow();
		assertThat(tag1.hashtags()).containsExactly("세일", "OliveYoung");
		// 메타 없는(LEFT JOIN 미스 — caption null) 행은 BrandCaptionHashtags.extract(null)과 동형으로 빈 리스트.
		var noMeta = index.refs().stream().filter(r -> r.shortcode().equals("NOMETA")).findFirst().orElseThrow();
		assertThat(noMeta.hashtags()).isEmpty();
	}

	/**
	 * 게시자 조인 미스(author_ig_user_id 부재 — 열거 셰이프에 따라 발생) 폴백 — 미해결 username만
	 * 모아 <b>1회</b> 배치로 해결한다. 끝내 못 찾은 행은 원시 관측 username만 남고 나머지는 null이다
	 * (풀 조립 {@code resolveAuthors} + {@code brandPost}의 폴백 규칙과 같은 방향).
	 */
	@Test
	void 인덱스_게시자_조인_미스는_username_폴백_배치_1회로_해결한다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				authorIndexRow("JOINED", "REELS", null, "joined_user", "조인됨", "https://cdn/j.jpg", null, 100L),
				authorIndexRow("FALLBACK", "REELS", null, null, null, null, null, null),
				authorIndexRow("GHOST", "REELS", null, null, null, null, null, null)));
		given(repository.findAuthorsByUsername(anyCollection())).willAnswer(inv -> List.of(
				new BrandReadRepository.AuthorRow("9002", "fallback_user", "폴백", 777L,
						"https://cdn/f.jpg", false, null)));

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), false);

		// 미해결 username만, 한 번에.
		verify(repository).findAuthorsByUsername(eq(Set.of("fallback_user", "ghost_user")));
		var fallback = index.refs().stream().filter(r -> r.shortcode().equals("FALLBACK")).findFirst().orElseThrow();
		assertThat(fallback.authorUsername()).isEqualTo("fallback_user");
		assertThat(fallback.authorFullName()).isEqualTo("폴백");
		assertThat(fallback.authorProfilePicUrl()).isEqualTo("https://cdn/f.jpg");
		assertThat(fallback.authorFollowers()).isEqualTo(777L);
		// 끝내 미해결 — 원시 관측 username만 남고 표시값은 전부 null(거짓 표시를 만들지 않는다).
		var ghost = index.refs().stream().filter(r -> r.shortcode().equals("GHOST")).findFirst().orElseThrow();
		assertThat(ghost.authorUsername()).isEqualTo("ghost_user");
		assertThat(ghost.authorFullName()).isNull();
		assertThat(ghost.authorProfilePicUrl()).isNull();
		assertThat(ghost.authorFollowers()).isNull();
	}

	/**
	 * 과도기 폴백(레거시 direct) ref도 신규 필드를 채운다 — 풀 ref와 셰이프가 어긋나면 서버 필터가
	 * 레거시 카드만 조용히 떨군다. 산지는 이미 조립된 카드다(contentType 불명은 피드로 접는다).
	 */
	@Test
	void 레거시_폴백_ref도_신규_필드를_채운다() {
		var repository = mock(BrandReadRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of());
		given(directRepository.findPendingByUser(7L)).willReturn(List.of(
				new BrandDirectPostRepository.Row(7L, 42L, "LEG1", 55L),
				new BrandDirectPostRepository.Row(7L, 42L, "LEG2", 56L)));
		given(trackingAssembler.assembleList(7L)).willReturn(new TrackingItemAssembler.AssembledList(
				List.of(legacyItem("55", legacyPost("reels", "2026-08-06T10:00:00+09:00")),
						legacyItem("56", null)),
				SWEPT_AT, LocalDate.of(2026, 8, 8)));

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class), directRepository,
				trackingAssembler, mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), false);

		var withPost = index.refs().stream().filter(r -> r.shortcode().equals("LEG1")).findFirst().orElseThrow();
		assertThat(withPost.contentType()).isEqualTo("reels");
		assertThat(withPost.adVerdict()).isNull();          // 레거시 산지엔 광고 판정이 없다
		assertThat(withPost.authorUsername()).isEqualTo("legacy_handle");
		assertThat(withPost.authorFullName()).isEqualTo("레거시");
		assertThat(withPost.authorProfilePicUrl()).isEqualTo("https://cdn/legacy.jpg");
		assertThat(withPost.authorFollowers()).isEqualTo(999L);
		assertThat(withPost.takenAtKst()).isEqualTo("2026-08-06T10:00:00+09:00");
		// 게시물 미확정(collecting) — 매체를 모르니 피드로 접고 업로드 시각은 없다.
		var pending = index.refs().stream().filter(r -> r.shortcode().equals("LEG2")).findFirst().orElseThrow();
		assertThat(pending.contentType()).isEqualTo("feed");
		assertThat(pending.takenAtKst()).isNull();
	}

	/**
	 * 광고 표기 노출 게이트의 단일 정의(스펙 §10-2 + 2026-08-19 경쟁사 제외) — 서버 필터·패싯이
	 * {@code brandPost}와 같은 판정을 써야 "화면엔 없는 값으로 필터가 걸리는" 불일치가 안 생긴다.
	 */
	@Test
	void adDisclosureExposed는_토글과_조회자_연결로_갈린다() {
		var on = newAssembler(mock(BrandReadRepository.class), mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), true);
		var off = newAssembler(mock(BrandReadRepository.class), mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);

		assertThat(on.adDisclosureExposed(BrandAccountType.OWN)).isTrue();
		assertThat(on.adDisclosureExposed(BrandAccountType.COMPETITOR)).isFalse();
		assertThat(off.adDisclosureExposed(BrandAccountType.OWN)).isFalse();
	}

	@Test
	void 인덱스에서_다른_유저의_direct_전용_행은_빠진다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("TAG1", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null, null, null),
				indexRow("OTHERS", "2026-08-05T01:00:00Z", null, "2026-08-06T00:00:00Z", null, null)));
		var directRepository = mock(BrandDirectPostRepository.class);
		given(directRepository.shortCodesByUser(7L)).willReturn(Set.of());   // 내 등록이 아니다

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class), directRepository,
				mock(TrackingItemAssembler.class), mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), false);

		assertThat(index.refs()).extracting(BrandPostAssembler.PostRef::shortcode).containsExactly("TAG1");
	}

	// ---------- 인덱스 경로 hashtag 성분·격리(2026-08-27 목록 타임아웃 해소 갭 보완) ----------

	/** hashtag-only 인덱스 행도 풀 조립(assembleBrandPosts)과 같은 규칙 — 내 태그와 겹치면 보인다. */
	@Test
	void 인덱스에서_내_태그와_겹치는_해시태그_전용_행은_hashtag로_보인다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("MINE", "2026-08-06T01:00:00Z", null, null, "2026-08-06T03:00:00Z", null, null)));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("끌리메"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("MINE", "끌리메")));

		var index = newAssemblerWithTags(repository, hashtagTagRepository).indexForBrand(7L, accountRow(), false);

		assertThat(index.refs()).singleElement().satisfies(ref -> {
			assertThat(ref.shortcode()).isEqualTo("MINE");
			assertThat(ref.source()).isEqualTo("hashtag");
		});
		assertThat(index.poolCodes()).containsExactly("MINE");
	}

	/** fail-closed(설계 §3) — 매칭 교집합이 없는 hashtag-only 행은 refs·poolCodes 양쪽에서 빠진다. */
	@Test
	void 인덱스에서_교집합_없는_해시태그_전용_행은_빠진다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("ORPHAN", "2026-08-06T01:00:00Z", null, null, "2026-08-06T03:00:00Z", null, null)));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("끌리메"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of());   // 매칭 기록 없음

		var index = newAssemblerWithTags(repository, hashtagTagRepository).indexForBrand(7L, accountRow(), false);

		assertThat(index.refs()).isEmpty();
		assertThat(index.poolCodes()).isEmpty();
	}

	// ---------- matchedTags 배지(2026-08-31, hashtag 감지 태그 노출 — 조회자 장부 교집합) ----------

	/**
	 * 인덱스/하이드레이트 경로 — 게시물이 여러 태그([끌리메, cclime])로 매칭돼도 조회자 장부에 있는
	 * 태그(cclime)만 matchedTags에 실린다("남의 태그" 이름을 노출하지 않는다, isVisible과 같은 관점).
	 */
	@Test
	void 하이드레이트는_source_hashtag_카드에_장부와_겹치는_매칭_태그만_matchedTags로_싣는다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("MATCH", "2026-08-06T01:00:00Z", null, null, "2026-08-06T03:00:00Z", null, null)));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("cclime"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("MATCH", "끌리메"),
				new BrandReadRepository.MatchedTagRow("MATCH", "cclime")));
		given(repository.findBrandPostsByShortCodes(eq(42L), anyCollection()))
				.willReturn(List.of(hashtagRow("MATCH")));
		given(repository.findPostMeta(anyCollection())).willReturn(List.of());
		given(repository.findSnapshots(anyCollection())).willReturn(List.of());
		given(repository.findAuthors(anyCollection())).willReturn(List.of());

		var assembler = newAssemblerWithTags(repository, hashtagTagRepository);
		var index = assembler.indexForBrand(7L, accountRow(), false);
		var posts = assembler.hydrate(7L, accountRow(), BrandAccountType.OWN, index, List.of("MATCH"), false);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.source()).isEqualTo("hashtag");
			assertThat(post.matchedTags()).containsExactly("cclime");
		});
	}

	/** tagged 카드는 hashtag 성분이 없으니 matchedTags가 항상 null이다(하이드레이트 경로). */
	@Test
	void 하이드레이트에서_tagged_카드는_matchedTags가_null이다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("AAA", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null, null, null)));
		given(repository.findBrandPostsByShortCodes(eq(42L), anyCollection()))
				.willReturn(List.of(taggedRow("AAA")));
		given(repository.findPostMeta(anyCollection())).willReturn(List.of());
		given(repository.findSnapshots(anyCollection())).willReturn(List.of());
		given(repository.findAuthors(anyCollection())).willReturn(List.of());

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), false);
		var posts = assembler.hydrate(7L, accountRow(), BrandAccountType.OWN, index, List.of("AAA"), false);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.source()).isEqualTo("tagged");
			assertThat(post.matchedTags()).isNull();
		});
	}

	/**
	 * tagged 성분이 있으면(겹침 행) 격리 필터 없이 전원 노출되고 source는 "tagged"다 — hashtag-only
	 * 후보가 하나도 없으므로 태그 장부·매칭 태그 조회 자체가 생략된다(지연 조회 관용구).
	 */
	@Test
	void 인덱스에서_tagged_겹침_행은_격리_조회_없이_tagged로_보인다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("BOTH", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null,
						"2026-08-06T03:00:00Z", null, null)));

		var index = newAssemblerWithTags(repository, hashtagTagRepository).indexForBrand(7L, accountRow(), false);

		assertThat(index.refs()).singleElement().satisfies(ref -> {
			assertThat(ref.shortcode()).isEqualTo("BOTH");
			assertThat(ref.source()).isEqualTo("tagged");
		});
		verify(hashtagTagRepository, never()).findByUserAndBrand(anyLong(), anyLong());
		verify(repository, never()).findMatchedTags(anyLong(), anyCollection());
	}

	/** 3성분 겹침(direct+hashtag) 행을 등록자 본인이 조회하면 등록자 관점이 이겨 source는 "direct"다. */
	@Test
	void 인덱스에서_direct_hashtag_겹침_행을_등록자가_보면_direct다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("MIX", "2026-08-06T01:00:00Z", null, "2026-08-07T02:00:00Z",
						"2026-08-06T03:00:00Z", null, null)));
		given(directRepository.shortCodesByUser(7L)).willReturn(Set.of("MIX"));   // 내가 등록했다

		var assembler = new BrandPostAssembler(repository, mock(BrandPostCampaignRepository.class),
				directRepository, mock(TrackingItemAssembler.class), mock(MonitoringItemRepository.class),
				hashtagTagRepository, false);
		var index = assembler.indexForBrand(7L, accountRow(), false);

		assertThat(index.refs()).singleElement().satisfies(ref -> {
			assertThat(ref.shortcode()).isEqualTo("MIX");
			assertThat(ref.source()).isEqualTo("direct");
		});
		// 소유 행은 hashtag-only 후보가 아니므로 격리 조회를 타지 않는다.
		verify(hashtagTagRepository, never()).findByUserAndBrand(anyLong(), anyLong());
	}

	@Test
	void 하이드레이트는_지정_코드만_조립하고_입력_순서를_지킨다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("AAA", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null, null, null),
				indexRow("BBB", "2026-08-05T01:00:00Z", "2026-08-05T02:00:00Z", null, null, null),
				indexRow("CCC", "2026-08-04T01:00:00Z", "2026-08-04T02:00:00Z", null, null, null)));
		given(repository.findBrandPostsByShortCodes(eq(42L), anyCollection()))
				.willReturn(List.of(taggedRow("CCC"), taggedRow("AAA")));
		given(repository.findPostMeta(anyCollection())).willReturn(List.of());
		given(repository.findSnapshots(anyCollection())).willReturn(List.of());
		given(repository.findAuthors(anyCollection())).willReturn(List.of());

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), false);
		var posts = assembler.hydrate(7L, accountRow(), BrandAccountType.OWN, index,
				List.of("CCC", "AAA"), false);

		assertThat(posts).extracting(BrandPostResponse::shortcode).containsExactly("CCC", "AAA");
		// 풀 행·배치 조회는 페이지 코드 2건으로만 돈다 — 전량(3건) 조립이 되살아나면 페이지네이션이 무의미하다.
		verify(repository).findBrandPostsByShortCodes(eq(42L), eq(Set.of("CCC", "AAA")));
		verify(repository).findPostMeta(eq(Set.of("CCC", "AAA")));
		verify(repository).findSnapshots(eq(Set.of("CCC", "AAA")));
	}

	@Test
	void 하이드레이트는_withComments_false면_댓글_조회_없이_빈_목록이다() {
		var repository = mock(BrandReadRepository.class);
		given(repository.findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true))).willReturn(List.of(
				indexRow("AAA", "2026-08-06T01:00:00Z", "2026-08-06T02:00:00Z", null, null, null)));
		given(repository.findBrandPostsByShortCodes(eq(42L), anyCollection()))
				.willReturn(List.of(taggedRow("AAA")));
		given(repository.findPostMeta(anyCollection())).willReturn(List.of());
		given(repository.findSnapshots(anyCollection())).willReturn(List.of());
		given(repository.findAuthors(anyCollection())).willReturn(List.of());
		given(repository.findComments(anyCollection(), anyInt())).willReturn(List.of(
				new BrandReadRepository.BrandCommentRow("AAA", "c1", "user_a", "본문", 1L,
						OffsetDateTime.parse("2026-08-06T03:00:00Z"), null)));
		// 목록 경로는 행 대신 집계로 저장 건수를 채운다(2026-09-03, FE 피드백 #4-B) — 상한 없는 누적치.
		given(repository.countComments(anyCollection())).willReturn(Map.of("AAA", 85L));

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);
		var index = assembler.indexForBrand(7L, accountRow(), false);

		var withoutComments = assembler.hydrate(7L, accountRow(), BrandAccountType.OWN, index,
				List.of("AAA"), false);
		verify(repository, never()).findComments(anyCollection(), anyInt());
		assertThat(withoutComments).singleElement().satisfies(post -> {
			assertThat(post.recentComments()).isEmpty();
			assertThat(post.commentsCollectedCount()).isEqualTo(85L);
		});

		var withComments = assembler.hydrate(7L, accountRow(), BrandAccountType.OWN, index,
				List.of("AAA"), true);
		assertThat(withComments).singleElement()
				.satisfies(post -> assertThat(post.commentsCollectedCount()).isEqualTo(1));
	}

	// ---------- source 3원화·해시태그 격리(2026-08-27 설계 §3) ----------

	/** hashtag-only 행은 조회자의 장부 태그와 게시물 매칭 태그의 교집합이 있을 때만 보인다. */
	@Test
	void 내_태그와_겹치는_해시태그_게시물만_보인다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(hashtagRow("MINE"), hashtagRow("THEIRS")));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("끌리메"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("MINE", "끌리메"),
				new BrandReadRepository.MatchedTagRow("THEIRS", "남의태그")));

		var posts = newAssemblerWithTags(repository, hashtagTagRepository)
				.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL, false,
						BrandAccountType.OWN);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.shortcode()).isEqualTo("MINE");
			assertThat(post.source()).isEqualTo("hashtag");
		});
	}

	/**
	 * fail-open 폐기(설계 §3) — 매칭 기록이 없는 행은 숨긴다. 구 감지 목록은 "매칭 기록이 없으면
	 * 전원 노출"이었지만, 태그 장부 백필 이후 모든 사용자에게 최소 태그가 있으므로 그 완화가
	 * 필요 없어졌고, 남겨 두면 남의 태그로 잡힌 게시물이 전원에게 새어 나간다.
	 */
	@Test
	void 매칭_기록이_없는_해시태그_게시물은_숨긴다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(hashtagRow("ORPHAN")));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("끌리메"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of());

		var posts = newAssemblerWithTags(repository, hashtagTagRepository)
				.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL, false,
						BrandAccountType.OWN);

		assertThat(posts).isEmpty();
	}

	/** 장부가 비어 있으면 아무것도 안 보인다 — 구 fail-open(전원 노출)의 회귀 방지. */
	@Test
	void 장부가_비면_해시태그_게시물은_보이지_않는다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(hashtagRow("MINE")));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of());
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("MINE", "끌리메")));

		var posts = newAssemblerWithTags(repository, hashtagTagRepository)
				.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL, false,
						BrandAccountType.OWN);

		assertThat(posts).isEmpty();
	}

	/**
	 * assembleBrandPosts(레거시 전량 조립) 경로 — 게시물이 여러 태그([끌리메, cclime])로 매칭돼도
	 * 조회자 장부에 있는 태그(cclime)만 matchedTags에 실린다(하이드레이트 경로와 같은 계약).
	 */
	@Test
	void assembleBrandPosts는_source_hashtag_카드에_장부와_겹치는_매칭_태그만_matchedTags로_싣는다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(hashtagRow("MATCH")));
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("cclime"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("MATCH", "끌리메"),
				new BrandReadRepository.MatchedTagRow("MATCH", "cclime")));

		var posts = newAssemblerWithTags(repository, hashtagTagRepository)
				.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL, false,
						BrandAccountType.OWN);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.source()).isEqualTo("hashtag");
			assertThat(post.matchedTags()).containsExactly("cclime");
		});
	}

	/** direct 카드는 hashtag 성분이 없으니 matchedTags가 항상 null이다(assembleBrandPosts 경로). */
	@Test
	void assembleBrandPosts에서_direct_카드는_matchedTags가_null이다() {
		var repository = mock(BrandReadRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(row("XYZ", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z")));
		given(directRepository.shortCodesByUser(7L)).willReturn(Set.of("XYZ"));

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class), directRepository,
				mock(TrackingItemAssembler.class), mock(MonitoringItemRepository.class), false);
		var posts = assembler.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL,
				false, BrandAccountType.OWN);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.source()).isEqualTo("direct");
			assertThat(post.matchedTags()).isNull();
		});
	}

	/**
	 * tagged 성분이 있으면 브랜드 공유(기존 규칙) — 격리 필터도 태그 장부 조회도 타지 않는다.
	 * source 우선순위는 direct(등록자 관점) > tagged > hashtag다.
	 */
	@Test
	void tagged_성분이_있으면_해시태그_겹침이어도_tagged로_전원_노출된다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		var row = new BrandReadRepository.BrandTaggedPostRow("BOTH", "glowdeep_92", "9001",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				7L, null, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null,
				OffsetDateTime.parse("2026-08-06T03:00:00Z"));
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false))).willReturn(List.of(row));

		var posts = newAssemblerWithTags(repository, hashtagTagRepository)
				.assembleBrandPosts(7L, accountRow(), false, BrandPostAssembler.BrandPostScope.ALL, false,
						BrandAccountType.OWN);

		assertThat(posts).singleElement().satisfies(post -> assertThat(post.source()).isEqualTo("tagged"));
		verify(hashtagTagRepository, never()).findByUserAndBrand(anyLong(), anyLong());
	}

	/** hashtag 성분이 함께 있으면, 남이 등록한 direct 행도 내 태그로 보인다 — 관점은 hashtag다. */
	@Test
	void 남이_등록한_direct에_hashtag_성분이_있으면_hashtag로_보인다() {
		var repository = mock(BrandReadRepository.class);
		var hashtagTagRepository = mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var row = new BrandReadRepository.BrandTaggedPostRow("MIX", "glowdeep_92", "9001",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				7L, null, null, OffsetDateTime.parse("2026-08-07T02:00:00Z"), null,
				OffsetDateTime.parse("2026-08-06T03:00:00Z"));
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false))).willReturn(List.of(row));
		given(directRepository.shortCodesByUser(7L)).willReturn(Set.of());   // 내가 등록한 게 아니다
		given(hashtagTagRepository.findByUserAndBrand(7L, 42L)).willReturn(Set.of("끌리메"));
		given(repository.findMatchedTags(eq(42L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("MIX", "끌리메")));

		var assembler = new BrandPostAssembler(repository, mock(BrandPostCampaignRepository.class),
				directRepository, mock(TrackingItemAssembler.class), mock(MonitoringItemRepository.class),
				hashtagTagRepository, false);
		var posts = assembler.assembleBrandPosts(7L, accountRow(), false,
				BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

		assertThat(posts).singleElement().satisfies(post -> assertThat(post.source()).isEqualTo("hashtag"));
	}

	/** hashtag-only 픽스처 — tag_detected_at·direct_registered_at 없이 hashtag_detected_at만 채워진 행. */
	private static BrandReadRepository.BrandTaggedPostRow hashtagRow(String code) {
		return new BrandReadRepository.BrandTaggedPostRow(code, "glowdeep_92", "9001",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				0L, null, null, null, null, OffsetDateTime.parse("2026-08-06T03:00:00Z"));
	}

	private static BrandPostAssembler newAssemblerWithTags(BrandReadRepository repository,
			com.celfit.was.monitoring.BrandHashtagTagRepository hashtagTagRepository) {
		return new BrandPostAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), hashtagTagRepository, false);
	}

	// ---------- 노출 필터(등록자 전용, 08-19) ----------

	/**
	 * direct-only(tag_detected_at IS NULL) 게시물은 등록자(app.brand_direct_posts 원장) 유저에게만
	 * 보인다 — 같은 브랜드를 보는 다른 유저 화면에 노출되던 버그의 회귀 방지(요구사항 §2).
	 */
	@Test
	void 다른_유저가_등록한_direct_전용_게시물은_결과에서_빠진다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(taggedRow("ABC"), row("XYZ", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z")));
		// 원장에 이 유저(7L)의 등록 기록이 없다 — 다른 유저가 등록한 게시물이라는 뜻.
		given(directRepository.shortCodesByUser(7L)).willReturn(Set.of());

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, false);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

		assertThat(posts).extracting(BrandPostResponse::shortcode).containsExactly("ABC");
	}

	/** 등록자 본인에게는 direct-only 게시물이 그대로 보이고 source도 "direct"다. */
	@Test
	void 등록자_본인에게는_direct_전용_게시물이_보인다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(row("XYZ", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z")));
		given(directRepository.shortCodesByUser(7L)).willReturn(Set.of("XYZ"));

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, false);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

		assertThat(posts).singleElement().satisfies(post -> {
			assertThat(post.shortcode()).isEqualTo("XYZ");
			assertThat(post.source()).isEqualTo("direct");
		});
	}

	/**
	 * 해시태그로 감지된 게시물(tag_detected_at IS NOT NULL)은 direct 등록 여부·등록자와 무관하게
	 * 항상 전원에게 보인다 — 노출 필터는 direct-only에만 적용된다(요구사항 §2).
	 */
	@Test
	void 해시태그로_감지된_게시물은_등록_여부와_무관하게_전원에게_보인다() {
		var repository = mock(BrandReadRepository.class);
		var campaignRepository = mock(BrandPostCampaignRepository.class);
		var directRepository = mock(BrandDirectPostRepository.class);
		var trackingAssembler = mock(TrackingItemAssembler.class);
		var itemRepository = mock(MonitoringItemRepository.class);
		var account = accountRow();
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false)))
				.willReturn(List.of(taggedRow("ABC"),
						// 겹침 행 — 해시태그 감지 + 다른 유저의 direct 등록.
						row("GHI", "2026-08-06T02:00:00Z", "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z")));
		given(directRepository.shortCodesByUser(7L)).willReturn(Set.of());

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, false);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

		assertThat(posts).extracting(BrandPostResponse::shortcode).containsExactlyInAnyOrder("ABC", "GHI");
		assertThat(posts).filteredOn(p -> p.shortcode().equals("GHI")).singleElement()
				.satisfies(post -> assertThat(post.source()).isEqualTo("tagged"));
	}

	/** direct 등록 행이 하나도 없으면 원장(shortCodesByUser) 조회 자체를 생략한다(불필요한 조회 방지). */
	@Test
	void direct_등록_행이_없으면_원장_조회를_생략한다() {
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
		assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

		verify(directRepository, never()).shortCodesByUser(anyLong());
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

		assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ENRICHED_ONLY, false, BrandAccountType.OWN);
		verify(repository).findBrandPostsInWindow(eq(42L), any(), eq(true));

		clearInvocations(repository);
		assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);
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

		assembler.indexForBrand(7L, account, false);

		verify(repository).findBrandPostIndex(eq(42L), any(), eq(true), any(), eq(true));
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

	/**
	 * source 파생 규칙(설계 §3-3, 3원화 2026-08-27 설계 §3) — tagged-only·direct-only는
	 * direct_registered_at 유무만으로 갈린다. tagged-only 픽스처는 tag_detected_at을 채워 둔다 —
	 * 셋 다 null(성분 없음)은 이제 hashtag-only로 해석되는 별개 케이스라서다({@link #resolveSource}).
	 */
	@Test
	void source는_direct_registered_at_유무로_파생된다() {
		var taggedOnly = brandPost(row("ABC", "2026-08-06T02:00:00Z", "2026-08-06T02:00:00Z", null),
				null, null, List.of(), List.of(), List.of());
		var directOnly = brandPost(row("DEF", null, "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z"),
				null, null, List.of(), List.of(), List.of());

		assertThat(taggedOnly.source()).isEqualTo("tagged");
		assertThat(directOnly.source()).isEqualTo("direct");
	}

	/**
	 * 겹침 행(tag_detected_at·direct_registered_at 둘 다 값이 있음)의 source는 등록자 전용 노출
	 * 요구사항(08-19)에 따라 조회자 관점으로 갈린다 — 등록자에겐 "direct", 그 외 유저에겐 "tagged".
	 * 겹침 행은 tag_detected_at이 있어 노출 필터를 무조건 통과하므로(전원 노출), 여기서 갈리는 건
	 * source 표시뿐이다.
	 */
	@Test
	void 겹침_행의_source는_등록자_관점으로_갈린다() {
		var overlapRow = row("GHI", "2026-08-06T02:00:00Z", "2026-08-06T02:00:00Z", "2026-08-07T02:00:00Z");

		var forRegistrant = brandPost(overlapRow, null, null, List.of(), List.of(), List.of(), true);
		var forOtherUser = brandPost(overlapRow, null, null, List.of(), List.of(), List.of(), false);

		assertThat(forRegistrant.source()).isEqualTo("direct");
		assertThat(forOtherUser.source()).isEqualTo("tagged");
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
						7L, rowCrawledLater, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null, null),
				null, null, List.of(), List.of(), accountSwept, List.of(), false, Set.of(), false, BrandAccountType.OWN);
		var accountWins = BrandPostAssembler.brandPost(100L,
				new BrandReadRepository.BrandTaggedPostRow("ABC", "glowdeep_92", "9001",
						OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
						7L, rowCrawledEarlier, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null, null),
				null, null, List.of(), List.of(), accountSwept, List.of(), false, Set.of(), false, BrandAccountType.OWN);
		var rowNull = BrandPostAssembler.brandPost(100L,
				taggedRow("ABC"), null, null, List.of(), List.of(), accountSwept, List.of(), false, Set.of(), false,
				BrandAccountType.OWN);

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

	/** brandPost() hashtags 배선(2026-08-31 품질 리뷰 반영) — meta.caption()에서 추출한 태그가 실린다. */
	@Test
	void brandPost는_메타_캡션에서_추출한_해시태그를_싣는다() {
		var metaWithTags = new BrandReadRepository.BrandPostMetaRow("ABC", "glowdeep_92", "REELS",
				LocalDate.of(2026, 8, 6), "#세일 안내 #OliveYoung", "https://cdn/thumb.jpg",
				"https://cdn/video.mp4", 15.5, null, null, null, null, null);

		var post = brandPost(taggedRow("ABC"), metaWithTags, null, List.of(), List.of(), List.of());

		assertThat(post.hashtags()).containsExactly("세일", "OliveYoung");
	}

	/** meta 자체가 없으면(미보강) hashtags도 BrandCaptionHashtags.extract(null)과 동형으로 빈 리스트다. */
	@Test
	void brandPost는_메타가_없으면_해시태그가_빈_리스트다() {
		var post = brandPost(taggedRow("ABC"), null, null, List.of(), List.of(), List.of());

		assertThat(post.hashtags()).isEmpty();
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

	/** 답글 선행 @핸들 누출 차단(2026-09-03, FE 피드백 #4-D) — author 마스킹과 같은 규칙으로 본문도 가린다. */
	@Test
	void 댓글_답글의_선행_멘션도_마스킹된다() {
		var row = new BrandReadRepository.BrandCommentRow("ABC", "c1", "nunu.zip_", "좋아요", 3L,
				OffsetDateTime.parse("2026-08-06T05:00:00Z"), "@nunu.zip_ 감사합니다");

		var post = brandPost(taggedRow("ABC"), meta("ABC", "REELS", null), null, List.of(), List.of(row), List.of());

		assertThat(post.recentComments().get(0).author()).isEqualTo("nu***p_");
		assertThat(post.recentComments().get(0).reply().text()).isEqualTo("@nu***p_ 감사합니다");
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

		// 등록자 관점(registeredByUser=true)으로 조회 — source="direct" 자체는 별도 테스트
		// (겹침_행의_source는_등록자_관점으로_갈린다)가 고정한다. 여기는 광고 판정 필드가 source와
		// 무관하게 채워짐을 검증한다.
		var post = BrandPostAssembler.brandPost(100L, overlapRow, adMeta, null, List.of(), List.of(), SWEPT_AT,
				List.of(), true, Set.of("glowdeep_92"), true, BrandAccountType.OWN);

		assertThat(post.source()).isEqualTo("direct");
		assertThat(post.adDisclosure()).isEqualTo("DISCLOSED");
		assertThat(post.adEvidence()).singleElement()
				.satisfies(e -> assertThat(e.phrase()).isEqualTo("#광고"));
		assertThat(post.seededAuthor()).isTrue();
	}

	/**
	 * 경쟁사 조회자 노출 제거(2026-08-19 경쟁사 판정 제거 설계 §4) — exposeAdDisclosure 토글이 켜져
	 * 있고 meta도 있어도, 조회 유저의 이 브랜드 연결이 competitor면 광고 표기 4필드가 전부 비노출된다.
	 * seededAuthor는 광고 판정과 무관한 필드라 영향받지 않는다(연결 accountType이 아니라 캠페인 연결
	 * 여부로 결정 — 클래스 주석 참조).
	 */
	@Test
	void 경쟁사_조회자에게는_광고_필드가_비노출된다() {
		var adMeta = new BrandReadRepository.BrandPostMetaRow("XYZ", "glowdeep_92", "FEED",
				LocalDate.of(2026, 8, 6), "오늘 소개 #광고", null, null, null, null, null,
				"DISCLOSED", "[]", "[{\"phrase\":\"#광고\",\"category\":\"CLEAR\",\"offset\":5}]");

		var post = BrandPostAssembler.brandPost(100L, taggedRow("XYZ"), adMeta, null, List.of(), List.of(), SWEPT_AT,
				List.of(), true, Set.of("glowdeep_92"), false, BrandAccountType.COMPETITOR);

		assertThat(post.adDisclosure()).isNull();
		assertThat(post.adViolations()).isEmpty();
		assertThat(post.adEvidence()).isEmpty();
		// 같은 브랜드를 own으로 보는 유저는 그대로 노출된다 — 비노출은 연결 단위 판정이지 브랜드 단위가 아니다.
		var ownerView = BrandPostAssembler.brandPost(100L, taggedRow("XYZ"), adMeta, null, List.of(), List.of(),
				SWEPT_AT, List.of(), true, Set.of("glowdeep_92"), false, BrandAccountType.OWN);
		assertThat(ownerView.adDisclosure()).isEqualTo("DISCLOSED");
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
						OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null, null),
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
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null, null, null)));
		when(repository.findPostMeta(anyCollection()))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("ABC", "creator1", "FEED",
						LocalDate.of(2026, 8, 7), "오늘 소개 #광고", null, null, null, null, null,
						"DISCLOSED", "[]", "[{\"phrase\":\"#광고\",\"category\":\"CLEAR\",\"offset\":5}]")));
		when(itemRepository.findCampaignLinkedAccountHandles(7L)).thenReturn(List.of("creator1"));
		when(campaignRepository.findShortCodesByUser(7L)).thenReturn(List.of());
		when(directRepository.findCampaignLinkedShortCodes(7L)).thenReturn(List.of());

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, true);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

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
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null, null, null)));
		when(repository.findPostMeta(anyCollection())).thenReturn(List.of());
		when(itemRepository.findCampaignLinkedAccountHandles(7L)).thenReturn(List.of("seed_creator"));
		when(campaignRepository.findShortCodesByUser(7L)).thenReturn(List.of());
		when(directRepository.findCampaignLinkedShortCodes(7L)).thenReturn(List.of());

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, true);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

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
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null, null, null)));
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
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

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
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null, null, null)));
		when(repository.findPostMeta(eq(Set.of("ABC")))).thenReturn(List.of());
		when(repository.findPostMeta(eq(Set.of("XYZ"))))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("XYZ", "legacy_direct_creator", "FEED",
						LocalDate.of(2026, 8, 7), null, null, null, null, null, null, null, null, null)));
		when(itemRepository.findCampaignLinkedAccountHandles(7L)).thenReturn(List.of());
		when(campaignRepository.findShortCodesByUser(7L)).thenReturn(List.of());
		when(directRepository.findCampaignLinkedShortCodes(7L)).thenReturn(List.of("XYZ"));

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, true);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

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
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null, null, null)));
		when(repository.findPostMeta(anyCollection())).thenReturn(List.of());
		when(itemRepository.findCampaignLinkedAccountHandles(7L)).thenReturn(List.of());
		when(campaignRepository.findShortCodesByUser(7L)).thenReturn(List.of());
		when(directRepository.findCampaignLinkedShortCodes(7L)).thenReturn(List.of());

		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, true);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

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
				List.of(), true, Set.of(), false, BrandAccountType.OWN);

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
						SWEPT_AT, SWEPT_AT, 0L, null, SWEPT_AT, null, null, null)));
		when(repository.findPostMeta(anyCollection()))
				.thenReturn(List.of(new BrandReadRepository.BrandPostMetaRow("ABC", "creator1", "FEED",
						LocalDate.of(2026, 8, 7), "오늘 소개 #광고", null, null, null, null, null,
						"DISCLOSED", "[]", "[]")));

		// 토글 off — 시딩 산출 조회(findCampaignLinkedAccountHandles·findShortCodesByUser·
		// findCampaignLinkedShortCodes)를 호출조차 하지 않는다(드라이런 중 불필요한 조회 방지).
		var assembler = newAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, false);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);

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

	// ---------- 커버리지 클램프(수집 상한 v2 §7-1·§7-3, 2026-08-20) ----------

	@Test
	void 커버리지_클램프는_coveredUntil보다_깊은_tagged_행을_거른다() {
		// 성과 대시보드 집계를 실수집 범위로 제한한다(사용자 결정 2026-08-20) — 상한 이전 초과
		// 수집분(marynmay_global의 컷 밖 ~8천 행)이 요약·버킷 집계에 섞이지 않게. direct 등록
		// 행은 상한 밖(§7-3 — 컷 밖이어도 계속 실수집되는 살아있는 추적 대상)이라 면제고,
		// 경계일(coveredUntil의 KST 달력일 당일)은 covered 버킷 판정과 같은 규칙으로 포함이다.
		var repository = mock(BrandReadRepository.class);
		// coveredUntil 2026-05-02T03:00:00Z = KST 05-02 12:00 → 실수집 하한 KST 달력일 05-02.
		var account = cappedAccountRow("2026-05-02T03:00:00Z");
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false))).willReturn(List.of(
				taggedRow("NEW", "2026-08-06T01:00:00Z"),                                    // 컷 안 — 유지
				taggedRow("EDGE", "2026-05-01T20:00:00Z"),                                   // KST 05-02 경계일 — 포함
				taggedRow("OLD", "2026-01-01T00:00:00Z"),                                    // 컷 밖 tagged — 제외
				row("OLD_DIRECT", "2026-08-06T02:00:00Z", "2026-01-01T00:00:00Z",
						"2026-06-01T00:00:00Z")));                                           // 컷 밖 direct 겹침 — 면제

		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);
		var posts = assembler.assembleBrandPosts(7L, account, false, BrandPostAssembler.BrandPostScope.ALL, true,
				BrandAccountType.OWN);

		assertThat(posts).extracting(BrandPostResponse::shortcode)
				.containsExactly("NEW", "EDGE", "OLD_DIRECT");
	}

	@Test
	void 클램프_off거나_coveredUntil이_없으면_전량이다() {
		var repository = mock(BrandReadRepository.class);
		List<BrandReadRepository.BrandTaggedPostRow> rows = List.of(
				taggedRow("NEW", "2026-08-06T01:00:00Z"),
				taggedRow("OLD", "2026-01-01T00:00:00Z"));
		given(repository.findBrandPostsInWindow(eq(42L), any(), eq(false))).willReturn(rows);
		var assembler = newAssembler(repository, mock(BrandPostCampaignRepository.class),
				mock(BrandDirectPostRepository.class), mock(TrackingItemAssembler.class),
				mock(MonitoringItemRepository.class), false);

		// 클램프 off(목록·캠페인 판정 소비자) — capped 계정이어도 전량.
		var uncapped = assembler.assembleBrandPosts(7L, cappedAccountRow("2026-05-02T03:00:00Z"),
				false, BrandPostAssembler.BrandPostScope.ALL, false, BrandAccountType.OWN);
		// 클램프 on이지만 coveredUntil null(완주 계정) — 자를 것이 없다.
		var completed = assembler.assembleBrandPosts(7L, accountRow(), false,
				BrandPostAssembler.BrandPostScope.ALL, true, BrandAccountType.OWN);

		assertThat(uncapped).hasSize(2);
		assertThat(completed).hasSize(2);
	}

	// ---------- 픽스처 ----------

	private static BrandPostAssembler newAssembler(BrandReadRepository repository,
			BrandPostCampaignRepository campaignRepository, BrandDirectPostRepository directRepository,
			TrackingItemAssembler trackingAssembler, MonitoringItemRepository itemRepository,
			boolean exposeAdDisclosure) {
		return new BrandPostAssembler(repository, campaignRepository, directRepository, trackingAssembler,
				itemRepository, mock(com.celfit.was.monitoring.BrandHashtagTagRepository.class),
				exposeAdDisclosure);
	}

	/**
	 * brandPost() 호출 축약 — 이 파일 대다수 테스트는 brandId=100, lastSweptAt=SWEPT_AT, 노출 off,
	 * registeredByUser=false(대다수 테스트는 direct_registered_at이 null이라 무관하다).
	 */
	private static BrandPostResponse brandPost(BrandReadRepository.BrandTaggedPostRow post,
			BrandReadRepository.BrandPostMetaRow meta, BrandReadRepository.AuthorRow author,
			List<BrandReadRepository.BrandSnapshotRow> snapshotRows,
			List<BrandReadRepository.BrandCommentRow> commentRows, List<String> campaignIds) {
		return brandPost(post, meta, author, snapshotRows, commentRows, campaignIds, false);
	}

	/** registeredByUser를 명시하는 축약 — 겹침 행의 source 파생(등록자 관점)을 검증하는 테스트 전용. */
	private static BrandPostResponse brandPost(BrandReadRepository.BrandTaggedPostRow post,
			BrandReadRepository.BrandPostMetaRow meta, BrandReadRepository.AuthorRow author,
			List<BrandReadRepository.BrandSnapshotRow> snapshotRows,
			List<BrandReadRepository.BrandCommentRow> commentRows, List<String> campaignIds,
			boolean registeredByUser) {
		// viewerAccountType="own" 고정 — 이 축약을 쓰는 테스트는 경쟁사 노출 게이트(2026-08-19)와
		// 무관한 필드(source·트래킹 시각 등)만 본다. 경쟁사 게이트 자체는 BrandPostAssembler.brandPost를
		// 직접 호출하는 전용 테스트가 검증한다(아래 광고_판정_필드 절 참조).
		return BrandPostAssembler.brandPost(100L, post, meta, author, snapshotRows, commentRows, SWEPT_AT,
				campaignIds, false, Set.of(), registeredByUser, BrandAccountType.OWN);
	}

	private static BrandReadRepository.BrandAccountRow accountRow() {
		return new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT, false, null);
	}

	/** 상한 컷 계정(수집 상한 v2 §7-1) — covered_until까지만 실수집된 상태. */
	private static BrandReadRepository.BrandAccountRow cappedAccountRow(String coveredUntil) {
		return new BrandReadRepository.BrandAccountRow(42L, "brand", LocalDate.of(2026, 8, 7),
				SWEPT_AT, SWEPT_AT, SWEPT_AT, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, SWEPT_AT, true, OffsetDateTime.parse(coveredUntil));
	}

	@Test
	void unavailable_마킹된_행은_hidden으로_내려간다() {
		var row = new BrandReadRepository.BrandTaggedPostRow("ABC", "glowdeep_92", "9001",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				7L, null, null, OffsetDateTime.parse("2026-08-06T03:00:00Z"),
				OffsetDateTime.parse("2026-08-20T18:00:00Z"), null);

		var post = BrandPostAssembler.brandPost(100L, row, null, null, List.of(), List.of(),
				OffsetDateTime.parse("2026-08-07T18:00:00Z"), List.of(), false, Set.of(), false,
				BrandAccountType.OWN);

		assertThat(post.trackingStatus()).isEqualTo("hidden");
	}

	@Test
	void unavailable_null이면_기존대로_tracking이다() {
		var post = BrandPostAssembler.brandPost(100L,
				row("ABC", "2026-08-06T01:00:00Z", "2026-08-06T00:00:00Z", null),
				null, null, List.of(), List.of(), OffsetDateTime.parse("2026-08-07T18:00:00Z"),
				List.of(), false, Set.of(), false, BrandAccountType.OWN);

		assertThat(post.trackingStatus()).isEqualTo("tracking");
	}

	private static BrandReadRepository.BrandTaggedPostRow taggedRow(String code) {
		return taggedRow(code, "2026-08-06T01:00:00Z");
	}

	private static BrandReadRepository.BrandTaggedPostRow taggedRow(String code, String takenAt) {
		return row(code, "2026-08-06T02:00:00Z", takenAt, null);
	}

	/**
	 * 인덱스 행 빌더 — 슬림 인덱스 셰이프(2026-08-27 P0, findBrandPostIndex) + hashtag 성분
	 * (2026-08-27 해시태그 격리 인덱스 경로 보완). 캡션 원문 대신 SQL이 계산한 마커 매치
	 * (captionMarker)를 받으므로, 여기서는 캡션을 자바 판정기로 한 번 접어 SQL과 같은 값을 만든다 —
	 * 시드 문구를 그대로 쓰면서도 record 셰이프는 새 계약을 따른다. 필터·패싯·작성자 컬럼과
	 * 대시보드 전용 컬럼(unavailableAt·authorIgUserId)은 이 테스트가 소비하지 않아 기본값(null)으로
	 * 채운다.
	 */
	private static BrandReadRepository.BrandPostIndexRow indexRow(String code, String takenAt,
			String tagDetectedAt, String directRegisteredAt, Boolean paid, String caption) {
		return indexRow(code, takenAt, tagDetectedAt, directRegisteredAt, null, paid, caption);
	}

	/** 인덱스 행 빌더(hashtag 성분 포함, 2026-08-27 해시태그 격리 인덱스 경로 보완). */
	private static BrandReadRepository.BrandPostIndexRow indexRow(String code, String takenAt,
			String tagDetectedAt, String directRegisteredAt, String hashtagDetectedAt, Boolean paid,
			String caption) {
		return new BrandReadRepository.BrandPostIndexRow(code, OffsetDateTime.parse(takenAt),
				tagDetectedAt == null ? null : OffsetDateTime.parse(tagDetectedAt),
				directRegisteredAt == null ? null : OffsetDateTime.parse(directRegisteredAt),
				hashtagDetectedAt == null ? null : OffsetDateTime.parse(hashtagDetectedAt),
				null, "glowdeep_92", null, paid,
				caption != null && BrandSponsorshipClassifier.containsSponsorshipMarker(caption),
				null, null, null, null, null, null, null, caption);
	}

	/**
	 * 필터·패싯·게시자 컬럼까지 지정하는 인덱스 행 빌더 — tagged 행 고정(감지 시각만 채움)이고
	 * {@code authorUsername}을 null로 주면 조인 미스(= username 폴백 대상)를 뜻한다. 그때 원시 관측
	 * username은 shortcode 기반으로 만들어(FALLBACK→fallback_user) 폴백 배치 인자를 눈으로 읽게 한다.
	 */
	private static BrandReadRepository.BrandPostIndexRow authorIndexRow(String code, String contentType,
			String adVerdict, String authorUsername, String authorFullName, String authorProfilePicUrl,
			String authorImageObjectPath, Long authorFollowers) {
		return new BrandReadRepository.BrandPostIndexRow(code,
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				null, null, null, code.toLowerCase(Locale.ROOT) + "_user", null, null, false, contentType,
				adVerdict, authorUsername, authorFullName, authorProfilePicUrl, authorImageObjectPath,
				authorFollowers, null);
	}

	/** 과도기 폴백 원본(레거시 TrackingItem) — 브랜드 ref가 읽는 게시자·매체 필드만 채운다. */
	private static TrackingItemResponse legacyItem(String id, TrackingItemResponse.TrackedPostResponse post) {
		return new TrackingItemResponse(id, "url", "tracking", "legacy_handle", "레거시",
				"https://cdn/legacy.jpg", 999L, null, null, null, null, "2026-08-05T10:00:00+09:00",
				30, null, post, null);
	}

	private static TrackingItemResponse.TrackedPostResponse legacyPost(String contentType, String uploadedAt) {
		return new TrackingItemResponse.TrackedPostResponse("https://www.instagram.com/reel/LEG1/", contentType,
				uploadedAt, "캡션", List.of(), "https://cdn/legacy-thumb.jpg", null, List.of(), List.of());
	}

	/** 범용 row 빌더 — tagDetectedAt·directRegisteredAt을 직접 지정해 source 파생을 검증한다. */
	private static BrandReadRepository.BrandTaggedPostRow row(String code, String tagDetectedAt, String takenAt,
			String directRegisteredAt) {
		return new BrandReadRepository.BrandTaggedPostRow(code, "glowdeep_92", "9001",
				OffsetDateTime.parse(takenAt), OffsetDateTime.parse("2026-08-06T02:00:00Z"), 7L, null,
				tagDetectedAt == null ? null : OffsetDateTime.parse(tagDetectedAt),
				directRegisteredAt == null ? null : OffsetDateTime.parse(directRegisteredAt), null, null);
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
