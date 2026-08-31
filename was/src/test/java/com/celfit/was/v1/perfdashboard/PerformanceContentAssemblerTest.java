package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostIndexRow;
import com.celfit.was.monitoring.BrandReadRepository.LatestSnapshotRow;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler.BrandPostScope;
import com.celfit.was.v1.brandmonitoring.BrandPostResponse;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import com.celfit.was.v1.monitoring.TrackingItemResponse.PostCommentResponse;
import com.celfit.was.v1.monitoring.TrackingItemResponse.SnapshotResponse;
import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardRef;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 성과 대시보드 통합 조립(스펙 §7-1, 2026-08-18 direct 통합 §결정 3로 2계열 재편) 단위 고정 —
 * 레거시(individual)와 브랜드 풀(tagged ∪ direct)을 shortcode로 합치는 규칙, 지표별 스냅샷 병합,
 * 브랜드 풀 전용 합성 아이템을 검증한다. 하부(레거시 어셈블러·브랜드 어셈블러·캠페인 리포지토리)는
 * 전부 mock이다 — 이 클래스가 지는 책임은 "이미 조립된 두 계열을 어떻게 겹치느냐"뿐이라 DB 왕복
 * 없이 고정할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class PerformanceContentAssemblerTest {

	private static final long USER_ID = 7L;
	private static final long BRAND_ID = 42L;
	private static final OffsetDateTime LAST_COLLECTED = OffsetDateTime.parse("2026-08-07T02:00:00+09:00");
	private static final OffsetDateTime BRAND_SWEPT_AT = OffsetDateTime.parse("2026-08-07T03:00:00+09:00");
	private static final BrandLinkRow OWN_LINK =
			new BrandLinkRow(1L, USER_ID, BRAND_ID, "brand", "own", 12, LAST_COLLECTED, null);
	private static final BrandAccountRow OWN_ACCOUNT = brandAccount(BRAND_ID, "brand");
	/** 아카이브 사본이 없는 작성자의 원본 CDN 프로필 URL(POOL1) — 폴백 경로 판별용. */
	private static final String POOL1_PROFILE_PIC_URL = "https://cdn.example.com/creator.jpg";
	/** 아카이브된 작성자 프로필 오브젝트 경로(POOL2) — {@code /img/} 우선 경로 판별용. */
	private static final String POOL2_IMAGE_OBJECT_PATH = "authors/ig-1.jpg";

	@Mock
	private TrackingItemAssembler trackingItemAssembler;
	@Mock
	private BrandLinkRepository linkRepository;
	@Mock
	private CampaignRepository campaignRepository;
	@Mock
	private BrandReadRepository brandReadRepository;
	@Mock
	private BrandPostAssembler brandPostAssembler;

	private PerformanceContentAssembler assembler() {
		return new PerformanceContentAssembler(trackingItemAssembler, linkRepository, campaignRepository,
				Optional.of(brandReadRepository), Optional.of(brandPostAssembler));
	}

	// ---------- 스냅샷 병합(순수 함수) ----------

	@Test
	void 같은_날짜는_지표별_non_null_우선_둘다_값이면_브랜드값이다() {
		var legacy = List.of(snapshot("2026-08-06", 100L, null, 5L));
		var brand = List.of(snapshot("2026-08-06", 120L, 8L, null));

		var merged = PerformanceContentAssembler.mergeSnapshots(legacy, brand);

		assertThat(merged).hasSize(1);
		assertThat(merged.get(0).views()).isEqualTo(120L);   // 둘 다 값 → 브랜드
		assertThat(merged.get(0).likes()).isEqualTo(8L);     // 레거시 null → 브랜드
		assertThat(merged.get(0).comments()).isEqualTo(5L);  // 브랜드 null → 레거시
	}

	@Test
	void 겹치지_않는_날짜는_양쪽_전부_날짜_오름차순으로_남는다() {
		var legacy = List.of(snapshot("2026-08-05", 10L, 1L, 1L), snapshot("2026-08-07", 30L, 3L, 3L));
		var brand = List.of(snapshot("2026-08-06", 20L, 2L, 2L));

		var merged = PerformanceContentAssembler.mergeSnapshots(legacy, brand);

		assertThat(merged).extracting(SnapshotResponse::date)
				.containsExactly("2026-08-05", "2026-08-06", "2026-08-07");
	}

	@Test
	void 숨김_불리언은_어느_쪽이든_켜져_있으면_켜진다() {
		var legacy = List.of(new SnapshotResponse("2026-08-06", 100L, null, true, 5L, null, null, false, null));
		var brand = List.of(new SnapshotResponse("2026-08-06", 120L, 8L, false, 6L, 2L, 3L, true, 4L));

		var merged = PerformanceContentAssembler.mergeSnapshots(legacy, brand);

		assertThat(merged.get(0).likesHidden()).isTrue();
		assertThat(merged.get(0).sharesHidden()).isTrue();
		assertThat(merged.get(0).saves()).isEqualTo(2L);
		assertThat(merged.get(0).reposts()).isEqualTo(4L);
	}

	/**
	 * 단일 산지 계약(숨김이면 값 null — {@link SnapshotResponse})을 병합 결과도 지킨다 — 한 산지는
	 * 값을 관측하고 다른 산지는 숨김을 관측하면 값은 접는다. 값과 숨김이 같이 실리면
	 * {@code /growth}(숨김이면 합산 제외)와 {@code /comparison}(무게이트 합산)의 합이 갈린다.
	 */
	@Test
	void 한_산지가_값을_주고_다른_산지가_숨김을_관측하면_값은_접힌다() {
		// 레거시는 shares 값·likes 숨김, 브랜드는 likes 값·shares 숨김 — 양방향 다 접혀야 한다.
		var legacy = List.of(new SnapshotResponse("2026-08-06", 100L, null, true, 5L, null, 3L, false, null));
		var brand = List.of(new SnapshotResponse("2026-08-06", 120L, 8L, false, null, null, null, true, null));

		var merged = PerformanceContentAssembler.mergeSnapshots(legacy, brand);

		assertThat(merged.get(0).likesHidden()).isTrue();
		assertThat(merged.get(0).likes()).isNull();
		assertThat(merged.get(0).sharesHidden()).isTrue();
		assertThat(merged.get(0).shares()).isNull();
	}

	@Test
	void 한쪽이_비면_다른_쪽을_그대로_돌려준다() {
		var only = List.of(snapshot("2026-08-06", 100L, 1L, 5L));

		assertThat(PerformanceContentAssembler.mergeSnapshots(only, List.of())).isEqualTo(only);
		assertThat(PerformanceContentAssembler.mergeSnapshots(List.of(), only)).isEqualTo(only);
		assertThat(PerformanceContentAssembler.mergeSnapshots(List.of(), List.of())).isEmpty();
	}

	@Test
	void 날짜_키는_앞_10자다() {
		// 브랜드 풀 산지의 date가 타임스탬프로 들어와도 같은 날짜로 접힌다(takenAt 타입 혼재 방어).
		var legacy = List.of(snapshot("2026-08-06", 100L, null, 5L));
		var brand = List.of(snapshot("2026-08-06T03:00:00+09:00", null, 8L, null));

		var merged = PerformanceContentAssembler.mergeSnapshots(legacy, brand);

		assertThat(merged).hasSize(1);
		assertThat(merged.get(0).date()).isEqualTo("2026-08-06");
		assertThat(merged.get(0).views()).isEqualTo(100L);
		assertThat(merged.get(0).likes()).isEqualTo(8L);
	}

	// ---------- 중복 제거·분류 ----------

	/**
	 * 2026-08-18 direct 통합 후 2계열 재편(설계 §결정 3) — individual = 브랜드 풀에 없는 레거시
	 * 아이템, 겹치면 그 콘텐츠의 source는 브랜드 풀 관측(overlap)의 source를 따른다.
	 */
	@Test
	void 레거시_아이템이_브랜드_풀과_겹치면_source는_브랜드_풀_관측을_따른다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 100L, null, 5L))));
		givenBrand(taggedPost("ABC", List.of(snapshot("2026-08-06", 120L, 8L, null))));

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents).hasSize(1);
		var content = contents.get(0);
		assertThat(content.source()).isEqualTo("tagged");
		assertThat(content.additionalSources()).containsExactly("tagged");
		assertThat(content.item().id()).isEqualTo("900");
		assertThat(content.canonicalPostId()).isEqualTo("ABC");
		assertThat(content.brandAccountId()).isEqualTo("42");
		// 겹친 날짜는 지표별 병합 결과가 실린다(mergeSnapshots 유지 — 설계 §결정 3).
		assertThat(content.item().post().snapshots()).hasSize(1);
		assertThat(content.item().post().snapshots().get(0).views()).isEqualTo(120L);
		assertThat(content.item().post().snapshots().get(0).comments()).isEqualTo(5L);
	}

	@Test
	void 브랜드_풀에_direct_행으로_겹치면_source는_direct다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		givenBrand(directPost("ABC", List.of()));

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents).hasSize(1);
		assertThat(contents.get(0).source()).isEqualTo("direct");
		assertThat(contents.get(0).additionalSources()).containsExactly("direct");
		assertThat(contents.get(0).brandAccountId()).isEqualTo("42");
	}

	@Test
	void individual은_brandAccountId가_null이다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		givenNoBrand();

		assertThat(assembler().assemble(USER_ID).contents().get(0).brandAccountId()).isNull();
	}

	@Test
	void 브랜드_풀_전용은_bt_접두_합성_아이템이다() {
		givenLegacy();
		givenBrand(taggedPost("ABC", List.of(snapshot("2026-08-06", 120L, 8L, 3L))));

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents).hasSize(1);
		var content = contents.get(0);
		assertThat(content.source()).isEqualTo("tagged");
		assertThat(content.canonicalPostId()).isEqualTo("ABC");
		assertThat(content.brandAccountId()).isEqualTo("42");
		var item = content.item();
		assertThat(item.id()).isEqualTo("bt_ABC");
		assertThat(item.mode()).isEqualTo("url");
		assertThat(item.status()).isEqualTo("tracking");
		assertThat(item.trackingDays()).isEqualTo(90);
		assertThat(item.campaignId()).isNull();
		assertThat(item.keywords()).isNull();
		assertThat(item.handle()).isEqualTo("creator");
		assertThat(item.followers()).isEqualTo(1000L);
		assertThat(item.registeredAt()).isEqualTo("2026-08-06");
		assertThat(item.post()).isNotNull();
		assertThat(item.post().shortcode()).isEqualTo("ABC");
		assertThat(item.post().snapshots()).hasSize(1);
		assertThat(item.post().commentsTotal()).isEqualTo(3L);
	}

	@Test
	void 브랜드_풀_hidden_게시물은_합성_아이템도_hidden이다() {
		givenLegacy();
		givenBrand(directPostHidden("ABC"));

		var item = assembler().assemble(USER_ID).contents().get(0).item();

		assertThat(item.status()).isEqualTo("hidden");
	}

	/** campaignId/campaignName = campaignIds 헤드(설계 §결정 3) — 캠페인 이름은 CampaignRepository 조회로 채운다. */
	@Test
	void 브랜드_풀_전용_아이템의_campaignId_campaignName은_campaignIds_헤드에서_온다() {
		givenLegacy();
		givenBrand(taggedPostWithCampaigns("ABC", List.of("7", "8")));
		given(campaignRepository.findByUser(USER_ID)).willReturn(
				List.of(new CampaignRow(7L, USER_ID, "여름 캠페인", null, null, null, null, null, null, null)));

		var item = assembler().assemble(USER_ID).contents().get(0).item();

		assertThat(item.campaignId()).isEqualTo("7");
		assertThat(item.campaignName()).isEqualTo("여름 캠페인");
	}

	@Test
	void campaignIds가_비면_campaignId_campaignName은_null이다() {
		givenLegacy();
		givenBrand(taggedPost("ABC", List.of()));

		var item = assembler().assemble(USER_ID).contents().get(0).item();

		assertThat(item.campaignId()).isNull();
		assertThat(item.campaignName()).isNull();
	}

	@Test
	void 게시물_없는_detecting은_canonicalPostId가_null이다() {
		givenLegacy(legacyItem("901", "detecting", null, null));
		givenNoBrand();

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents).hasSize(1);
		assertThat(contents.get(0).canonicalPostId()).isNull();
		assertThat(contents.get(0).item().post()).isNull();
		assertThat(contents.get(0).source()).isEqualTo("individual");
		assertThat(contents.get(0).sponsorship()).isEqualTo("unknown");
	}

	@Test
	void 협찬_판정은_브랜드_풀_유료협찬_관측으로_승격한다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		givenBrand(taggedPost("ABC", List.of()).withSponsorship("sponsored", true));

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents.get(0).sponsorship()).isEqualTo("sponsored");
	}

	@Test
	void 댓글_숨김은_병합된_최신_스냅샷에서_유도한다() {
		// 레거시가 센 댓글 5건이 병합 결과에 남는데 브랜드 관측만 보고 hidden=true를 내면 모순이다.
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 100L, null, 5L))));
		givenBrand(taggedPostWithCommentsHidden("ABC", List.of(snapshot("2026-08-06", 120L, 8L, null))));

		var post = assembler().assemble(USER_ID).contents().get(0).item().post();

		assertThat(post.commentsTotal()).isEqualTo(5L);
		assertThat(post.commentsHidden()).isFalse();
	}

	@Test
	void 양쪽_모두_댓글을_못_본_날은_숨김이다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 100L, null, null))));
		givenBrand(taggedPostWithCommentsHidden("ABC", List.of(snapshot("2026-08-06", 120L, 8L, null))));

		var post = assembler().assemble(USER_ID).contents().get(0).item().post();

		assertThat(post.commentsTotal()).isNull();
		assertThat(post.commentsHidden()).isTrue();
	}

	@Test
	void 레거시_캡션이_비면_브랜드_풀_캡션으로_협찬을_판정한다() {
		// 레거시는 메타 미수집 시 캡션이 빈 문자열이다 — 그 경우에만 브랜드 풀 캡션으로 폴백한다.
		givenLegacy(legacyItemWithCaption("900", "https://www.instagram.com/p/ABC/", ""));
		givenBrand(taggedPostWithCaption("ABC", "가을 신상 #협찬"));

		var content = assembler().assemble(USER_ID).contents().get(0);

		// isPaidPartnership 관측은 없고(null) 브랜드 풀 캡션의 확정 키워드만으로 승격된다.
		assertThat(content.sponsorship()).isEqualTo("sponsored");
	}

	@Test
	void 레거시_단독_협찬은_캡션_키워드로만_판정한다() {
		givenLegacy(legacyItemWithCaption("900", "https://www.instagram.com/p/DEF/", "신상 추천 #광고"));
		givenNoBrand();

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents.get(0).sponsorship()).isEqualTo("sponsored");
	}

	@Test
	void 활성_브랜드가_없으면_브랜드_조회를_아예_하지_않는다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of());

		var assembled = assembler().assemble(USER_ID);

		assertThat(assembled.contents()).hasSize(1);
		assertThat(assembled.lastCollectedAt()).isEqualTo(LAST_COLLECTED);
		then(brandPostAssembler).should(never())
				.assembleBrandPosts(anyLong(), any(), anyBoolean(), any(), anyBoolean(), any());
	}

	@Test
	void lastCollectedAt은_레거시와_브랜드_스윕_중_늦은_쪽이다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		// 두 시각은 "늦은 쪽이 이긴다"만 보려는 임의 픽스처다(실제 스윕 크론 시각과 무관).
		// 여기선 브랜드 쪽이 더 늦으므로 브랜드 시각이 "마지막 수집"이 된다.
		givenBrandSweptAt(BRAND_SWEPT_AT, taggedPost("ABC", List.of()));

		assertThat(assembler().assemble(USER_ID).lastCollectedAt()).isEqualTo(BRAND_SWEPT_AT);
	}

	@Test
	void 브랜드_스윕이_더_이르면_레거시_시각을_쓴다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		givenBrandSweptAt(OffsetDateTime.parse("2026-08-06T03:00:00+09:00"), taggedPost("ABC", List.of()));

		assertThat(assembler().assemble(USER_ID).lastCollectedAt()).isEqualTo(LAST_COLLECTED);
	}

	@Test
	void monitoring_비활성이면_브랜드_풀_계열만_건너뛰고_링크는_읽는다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		givenNoBrand();

		var disabled = new PerformanceContentAssembler(trackingItemAssembler, linkRepository, campaignRepository,
				Optional.empty(), Optional.empty());
		var contents = disabled.assemble(USER_ID).contents();

		assertThat(contents).hasSize(1);
		assertThat(contents.get(0).source()).isEqualTo("individual");
		// 링크는 읽는다(08-12) — 구독 타입 판정은 app DataSource만으로 성립한다. 브랜드 풀 계열만 건너뛴다.
		then(linkRepository).should().findAllActiveByUser(USER_ID);
	}

	@Test
	void 경쟁사_집합은_브랜드_풀_콘텐츠의_brandAccountId와_같은_값_공간이다() {
		givenLegacy();
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(2L, USER_ID, 99L, "rival", "competitor", 12, LAST_COLLECTED, null)));
		BrandAccountRow rival = brandAccount(99L, "rival");
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.of(rival));
		given(brandPostAssembler.assembleBrandPosts(USER_ID, rival, true, BrandPostScope.ALL, true, "competitor"))
				.willReturn(List.of(taggedPostOf("ABC", 99L)));

		var assembled = assembler().assemble(USER_ID);

		// 집합 원소와 콘텐츠 필드가 문자열로 같아야 컨트롤러의 contains 판정이 성립한다.
		assertThat(assembled.contents()).singleElement()
				.extracting(PerformanceContentResponse::brandAccountId).isEqualTo("99");
		assertThat(assembled.competitorBrandAccountIds())
				.containsExactly(assembled.contents().get(0).brandAccountId());
	}

	@Test
	void 계정_행이_없는_경쟁사_링크도_집합에_남는다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(1L, USER_ID, BRAND_ID, "brand", "own", 12, LAST_COLLECTED, null),
				new BrandLinkRow(2L, USER_ID, 99L, "rival", "competitor", 12, LAST_COLLECTED, null)));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.empty());
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.empty());

		var assembled = assembler().assemble(USER_ID);

		// monitoring 계정 행이 없어 브랜드 풀 조립은 건너뛰어도 구독 타입 판정은 링크만으로 성립한다.
		assertThat(assembled.competitorBrandAccountIds()).containsExactly("99");
	}

	@Test
	void 같은_게시물이_내_브랜드와_경쟁사에_동시_태그되면_내_브랜드로_귀속된다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		// 경쟁사 연결이 더 오래됐다(목록 앞) — 그래도 own 귀속이 이겨야 한다. 귀속이 이제 표시가
		// 아니라 범위를 정하기 때문에, 연결 순서가 "내 게시물이 내 요약에 보이는지"를 정하면 안 된다.
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(1L, USER_ID, 99L, "rival", "competitor", 12, LAST_COLLECTED, null),
				new BrandLinkRow(2L, USER_ID, BRAND_ID, "brand", "own", 12, LAST_COLLECTED, null)));
		BrandAccountRow rival = brandAccount(99L, "rival");
		BrandAccountRow mine = brandAccount(BRAND_ID, "brand");
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.of(rival));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(mine));
		given(brandPostAssembler.assembleBrandPosts(USER_ID, rival, true, BrandPostScope.ALL, true, "competitor"))
				.willReturn(List.of(taggedPostOf("ABC", 99L)));
		given(brandPostAssembler.assembleBrandPosts(USER_ID, mine, true, BrandPostScope.ALL, true, "own"))
				.willReturn(List.of(taggedPostOf("ABC", BRAND_ID)));

		var assembled = assembler().assemble(USER_ID);

		var content = assembled.contents().get(0);
		assertThat(content.brandAccountId()).isEqualTo(String.valueOf(BRAND_ID));
		// 기본 범위 판정(컨트롤러 술어)에서 살아남는다.
		assertThat(assembled.competitorBrandAccountIds()).doesNotContain(content.brandAccountId());
	}

	/**
	 * 2026-08-18 direct 통합 후 "direct(own) 우선" 예외가 삭제됐다(설계 §결정 3) — direct와 tagged가
	 * 이제 한 행이라 별도 예외 없이도 {@link #ownFirst}(own 링크를 먼저 순회)만으로 own 귀속이
	 * 자연히 유지된다: own 브랜드 풀에 이 shortcode가 direct로 먼저 담기고(putIfAbsent), 경쟁사
	 * 브랜드 풀의 tagged 관측은 같은 키라 무시된다.
	 */
	@Test
	void own_브랜드_direct_등록은_ownFirst로_경쟁사_tagged_관측에_귀속을_뺏기지_않는다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(1L, USER_ID, BRAND_ID, "brand", "own", 12, LAST_COLLECTED, null),
				new BrandLinkRow(2L, USER_ID, 99L, "rival", "competitor", 12, LAST_COLLECTED, null)));
		BrandAccountRow mine = brandAccount(BRAND_ID, "brand");
		BrandAccountRow rival = brandAccount(99L, "rival");
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(mine));
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.of(rival));
		given(brandPostAssembler.assembleBrandPosts(USER_ID, mine, true, BrandPostScope.ALL, true, "own"))
				.willReturn(List.of(directPostOf("ABC", BRAND_ID)));
		given(brandPostAssembler.assembleBrandPosts(USER_ID, rival, true, BrandPostScope.ALL, true, "competitor"))
				.willReturn(List.of(taggedPostOf("ABC", 99L)));

		var assembled = assembler().assemble(USER_ID);

		var content = assembled.contents().get(0);
		assertThat(content.source()).isEqualTo("direct");
		assertThat(content.brandAccountId()).isEqualTo(String.valueOf(BRAND_ID));
		assertThat(assembled.competitorBrandAccountIds()).doesNotContain(content.brandAccountId());
	}

	@Test
	void 활성_브랜드가_없으면_경쟁사_집합도_비어_있다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		givenNoBrand();

		assertThat(assembler().assemble(USER_ID).competitorBrandAccountIds()).isEmpty();
	}

	@Test
	void 결과는_업로드_최신순이고_업로드일_미상은_마지막이다() {
		givenLegacy(
				legacyItem("900", "tracking", "https://www.instagram.com/reel/AAA/", List.of(), "2026-08-01"),
				legacyItem("901", "detecting", null, null),
				legacyItem("902", "tracking", "https://www.instagram.com/reel/BBB/", List.of(), "2026-08-06"));
		givenNoBrand();

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents).extracting(c -> c.item().id()).containsExactly("902", "900", "901");
	}

	// ---------- 슬림 조립(08-12 목록·비교 표면) ----------

	@Test
	void 슬림_조립은_브랜드_풀을_댓글_없이_조립한다() {
		givenLegacy();
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(new BrandLinkRow(1L, USER_ID,
				BRAND_ID, "brand", "own", 12, LAST_COLLECTED, null)));
		BrandAccountRow account = brandAccount(BRAND_ID, "brand");
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(account));
		given(brandPostAssembler.assembleBrandPosts(USER_ID, account, false, BrandPostScope.ALL, true, "own"))
				.willReturn(List.of(taggedPost("ABC", List.of())));

		var contents = assembler().assembleSlim(USER_ID).contents();

		// 댓글 배치 조회가 목록 경로에 되살아나면 고정 지연이 재발한다(08-12 실측: 조립 시간의 절반 이상).
		then(brandPostAssembler).should(never())
				.assembleBrandPosts(USER_ID, account, true, BrandPostScope.ALL, true, "own");
		assertThat(contents).hasSize(1);
	}

	@Test
	void 슬림_조립은_레거시_댓글을_싣지_않고_스냅샷_유래_지표는_그대로다() {
		var comments = List.of(new PostCommentResponse("c1", "au***", "좋아요", 3L, "2026-08-06T10:00:00+09:00", null));
		var item = new TrackingItemResponse("900", "url", "tracking", "creator", "크리에이터", null, 500L,
				null, null, null, "https://www.instagram.com/reel/ABC/", "2026-08-01", 30, null,
				new TrackingItemResponse.TrackedPostResponse("https://www.instagram.com/reel/ABC/", "reels",
						"2026-08-01", "캡션", List.of(), null, null,
						List.of(snapshot("2026-08-06", 20L, 2L, 9L)), comments),
				null);
		givenLegacy(item);
		givenNoBrand();

		var post = assembler().assembleSlim(USER_ID).contents().get(0).item().post();

		assertThat(post.recentComments()).isEmpty();
		assertThat(post.commentsCollectedCount()).isEqualTo(0L);
		// 스냅샷 유래 지표는 슬림과 무관하게 유지된다 — FE 목록 집계의 산지다.
		assertThat(post.commentsTotal()).isEqualTo(9L);
		assertThat(post.commentsHidden()).isFalse();
		assertThat(post.snapshots()).hasSize(1);
	}

	// ---------- 레거시 게시물 변환 ----------

	@Test
	void 레거시_게시물의_댓글_집계는_최신_스냅샷과_수집분이다() {
		var comments = List.of(new PostCommentResponse("c1", "au***", "좋아요", 3L, "2026-08-06T10:00:00+09:00", null));
		var item = new TrackingItemResponse("900", "url", "tracking", "creator", "크리에이터", null, 500L,
				null, null, null, "https://www.instagram.com/reel/ABC/", "2026-08-01", 30, null,
				new TrackingItemResponse.TrackedPostResponse("https://www.instagram.com/reel/ABC/", "reels",
						"2026-08-01", "캡션", List.of(), null, null,
						List.of(snapshot("2026-08-05", 10L, 1L, 1L), snapshot("2026-08-06", 20L, 2L, 9L)),
						comments),
				null);
		givenLegacy(item);
		givenNoBrand();

		var post = assembler().assemble(USER_ID).contents().get(0).item().post();

		assertThat(post.commentsTotal()).isEqualTo(9L);
		assertThat(post.commentsCollectedCount()).isEqualTo(1L);
		assertThat(post.commentsHidden()).isFalse();
		assertThat(post.shortcode()).isEqualTo("ABC");
		assertThat(post.recentComments()).isEqualTo(comments);
	}

	// ---------- previousDayValues·withLatestSnapshotOnly(2026-08-27 목록 최적화) ----------

	@Test
	void 직전_스냅샷이_있으면_previousDayValues가_그_값이다() {
		givenLegacy(twoSnapshotItem());
		givenNoBrand();

		PerformanceContentResponse content = assembler().assemble(USER_ID).contents().get(0);

		assertThat(content.item().post().previousDayValues())
				.isEqualTo(new PerformanceContentResponse.PreviousDayValues(100L, 10L, 1L));
	}

	@Test
	void 스냅샷이_1개면_previousDayValues는_null이다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 200L, 20L, 2L))));
		givenNoBrand();

		PerformanceContentResponse content = assembler().assemble(USER_ID).contents().get(0);

		assertThat(content.item().post().previousDayValues()).isNull();
	}

	/** 레거시 계열은 <b>병합 후</b> 스냅샷이 기준이다 — 브랜드 관측이 앞 날짜를 더하면 그게 직전 값이 된다. */
	@Test
	void 레거시_previousDayValues는_브랜드_병합_후_스냅샷_기준이다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 200L, 20L, 2L))));
		givenBrand(taggedPost("ABC", List.of(snapshot("2026-08-05", 100L, 10L, 1L))));

		var post = assembler().assemble(USER_ID).contents().get(0).item().post();

		assertThat(post.snapshots()).hasSize(2);
		assertThat(post.previousDayValues())
				.isEqualTo(new PerformanceContentResponse.PreviousDayValues(100L, 10L, 1L));
	}

	/** 브랜드 풀 전용(합성 아이템) 경로도 같은 규칙이다 — 조립 경로가 둘이라 각각 고정한다. */
	@Test
	void 브랜드_풀_전용_콘텐츠도_previousDayValues가_채워진다() {
		givenLegacy();
		givenBrand(taggedPost("ABC",
				List.of(snapshot("2026-08-05", 100L, 10L, 1L), snapshot("2026-08-06", 200L, 20L, 2L))));

		var post = assembler().assemble(USER_ID).contents().get(0).item().post();

		assertThat(post.previousDayValues())
				.isEqualTo(new PerformanceContentResponse.PreviousDayValues(100L, 10L, 1L));
	}

	@Test
	void withLatestSnapshotOnly는_최신_1개만_남기고_previousDayValues를_보존한다() {
		givenLegacy(twoSnapshotItem());
		givenNoBrand();
		PerformanceContentResponse full = assembler().assemble(USER_ID).contents().get(0);

		PerformanceContentResponse trimmed = full.withLatestSnapshotOnly();

		assertThat(trimmed.item().post().snapshots()).hasSize(1);
		assertThat(trimmed.item().post().snapshots().get(0).date()).isEqualTo("2026-08-06");
		assertThat(trimmed.item().post().previousDayValues().views()).isEqualTo(100L);
		// 스냅샷 외 나머지는 원본 그대로다 — 잘라내기가 다른 필드를 건드리면 목록 계약이 바뀐다.
		assertThat(trimmed.item().post())
				.usingRecursiveComparison().ignoringFields("snapshots").isEqualTo(full.item().post());
		assertThat(trimmed.item())
				.usingRecursiveComparison().ignoringFields("post").isEqualTo(full.item());
		assertThat(trimmed).usingRecursiveComparison().ignoringFields("item").isEqualTo(full);
	}

	@Test
	void withLatestSnapshotOnly는_스냅샷이_1개_이하거나_게시물이_없으면_자기_자신이다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 200L, 20L, 2L))), legacyItem("901", "detecting", null, null));
		givenNoBrand();
		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents.get(0).withLatestSnapshotOnly()).isSameAs(contents.get(0));
		assertThat(contents.get(1).withLatestSnapshotOnly()).isSameAs(contents.get(1));
	}

	@Test
	void shortcode는_url_경로에서_뽑고_형식이_다르면_null이다() {
		assertThat(PerformanceContentAssembler.shortcodeOf("https://www.instagram.com/reel/ABC-1_2/")).isEqualTo("ABC-1_2");
		assertThat(PerformanceContentAssembler.shortcodeOf("https://www.instagram.com/p/DEF/?img_index=1")).isEqualTo("DEF");
		assertThat(PerformanceContentAssembler.shortcodeOf("https://www.instagram.com/reels/GHI/")).isEqualTo("GHI");
		assertThat(PerformanceContentAssembler.shortcodeOf("https://www.instagram.com/share/XYZ/")).isNull();
		assertThat(PerformanceContentAssembler.shortcodeOf(null)).isNull();
	}

	// ---------- 인덱스 패스(2026-08-27 목록 최적화 §1-2) ----------

	/**
	 * 동치성 기준선 — 경량 인덱스의 판정값(source·협찬·상태·업로드일·귀속·캠페인·작성자·최신 지표)이
	 * 전량 조립({@link PerformanceContentAssembler#assembleSlim})의 결과와 콘텐츠 단위로 같아야 한다.
	 * 이 단정이 깨지면 ref 위에서 세는 statusCounts·필터·정렬이 전량 조립과 다른 답을 낸다.
	 */
	@Test
	void index의_ref는_전량_조립_결과와_판정값이_일치한다() {
		givenIndexFixture();
		givenFullAssemblyBaseline();
		var assembler = assembler();

		var index = assembler.index(USER_ID);
		var slim = assembler.assembleSlim(USER_ID);   // 구 전량 조립 — 동치성 기준선(프로덕션 미사용)

		assertThat(index.refs()).hasSameSizeAs(slim.contents());
		for (int i = 0; i < slim.contents().size(); i++) {
			var content = slim.contents().get(i);
			var ref = index.refs().get(i);   // index도 업로드 최신순 + contentKey 타이브레이크 정렬 계약
			assertThat(ref.contentKey()).isEqualTo(content.item().id());
			assertThat(ref.shortcode()).isEqualTo(content.canonicalPostId());
			assertThat(ref.source()).isEqualTo(content.source());
			assertThat(ref.sponsorship()).isEqualTo(content.sponsorship());
			assertThat(ref.status()).isEqualTo(content.item().status());
			assertThat(ref.brandAccountId()).isEqualTo(content.brandAccountId());
			assertThat(ref.campaignId()).isEqualTo(content.item().campaignId());
			assertThat(ref.uploadedOn()).isEqualTo(PerformanceContentAssembler.uploadedOn(content));
			assertThat(ref.handle()).isEqualTo(content.item().handle());
			assertThat(ref.displayName()).isEqualTo(content.item().displayName());
			assertThat(ref.profileImageUrl()).isEqualTo(content.item().profileImageUrl());
			assertThat(ref.followers()).isEqualTo(content.item().followers());
			var snaps = content.item().post() == null ? List.<SnapshotResponse>of()
					: content.item().post().snapshots();
			if (snaps.isEmpty()) {
				assertThat(ref.hasSnapshots()).isFalse();
			} else {
				var latest = snaps.get(snaps.size() - 1);
				assertThat(ref.hasSnapshots()).isTrue();
				assertThat(ref.latestViews()).isEqualTo(latest.views());
				assertThat(ref.latestLikes()).isEqualTo(latest.likes());
				assertThat(ref.latestLikesHidden()).isEqualTo(latest.likesHidden());
				assertThat(ref.latestComments()).isEqualTo(latest.comments());
			}
		}
		assertThat(index.lastCollectedAt()).isEqualTo(slim.lastCollectedAt());
		assertThat(index.competitorBrandAccountIds()).isEqualTo(slim.competitorBrandAccountIds());
	}

	/**
	 * 겹침 콘텐츠의 최신 지표는 <b>병합 후</b> 스냅샷에서 온다 — 풀 인덱스의 최신 스냅샷 행만 보면
	 * 브랜드가 못 본 댓글 수(레거시 관측)가 사라진다. 동치성 루프가 잡아내는 회귀를 명시 고정한다.
	 */
	@Test
	void 겹침_콘텐츠의_ref_지표는_병합_카드에서_유도된다() {
		givenIndexFixture();

		var overlap = assembler().index(USER_ID).refs().stream()
				.filter(r -> "ABC".equals(r.shortcode())).findFirst().orElseThrow();

		assertThat(overlap.contentKey()).isEqualTo("900");
		assertThat(overlap.latestViews()).isEqualTo(120L);      // 둘 다 값 → 브랜드
		assertThat(overlap.latestLikes()).isEqualTo(8L);        // 레거시 null → 브랜드
		assertThat(overlap.latestComments()).isEqualTo(5L);     // 브랜드 null → 레거시
		assertThat(overlap.source()).isEqualTo("tagged");
		assertThat(overlap.brandAccountId()).isEqualTo("42");
	}

	/**
	 * 병합 산지 정합(2026-08-28) — 한 산지가 값을, 다른 산지가 숨김을 관측한 콘텐츠의 ref는
	 * {@code likes=null·likesHidden=true}로 접혀야 {@code /growth}(숨김이면 합산 제외)와
	 * {@code /comparison}(무게이트 합산)의 좋아요 합이 같다. {@code mergeOne}이 값을 접지 않으면
	 * 같은 refs를 받은 두 표면이 다른 합을 낸다 — 이 단정이 그 회귀를 고정한다.
	 */
	@Test
	void 병합_콘텐츠의_likes_합산은_growth와_comparison이_같다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 100L, 40L, 5L))));
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(OWN_LINK));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(OWN_ACCOUNT));
		given(brandReadRepository.findBrandPostIndex(eq(BRAND_ID), any(), eq(false), any())).willReturn(List.of(
				taggedRow("ABC", "2026-08-06T09:00:00+09:00"),
				taggedRow("POOL1", "2026-08-06T09:00:00+09:00")));
		given(brandReadRepository.findLatestSnapshotsForBrand(eq(BRAND_ID), any(), eq(false)))
				.willReturn(List.of(latestSnapshot("POOL1", 200L, 20L, 2L)));
		// 브랜드 산지는 좋아요 숨김을 관측했다(값 null — 단일 산지 계약). 레거시 값 40과의 병합이 시험대다.
		given(brandPostAssembler.hydrate(eq(USER_ID), eq(OWN_ACCOUNT), eq("own"), any(), eq(List.of("ABC")),
				eq(false))).willReturn(List.of(taggedPost("ABC",
						List.of(new SnapshotResponse("2026-08-06", 120L, null, true, null, null, null, false,
								null)))));

		var refs = assembler().index(USER_ID).refs();

		LocalDate today = LocalDate.parse("2026-08-06");
		var growthPoint = PerformanceGrowthAggregator.aggregate(refs,
				PerformanceGrowthAggregator.Granularity.MONTH, null, null, List.of()).points().get(0);
		var comparisonBucket = PerformanceComparisonAssembler.compare(OWN_ACCOUNT, "own", refs,
				PerformanceComparisonAssembler.bucketRanges(today), today).buckets().get(0);

		// 숨김 관측 콘텐츠(ABC)는 양쪽 다 합산 제외 — POOL1의 20만 남고, 숨김은 카운트로 남는다.
		assertThat(growthPoint.likes()).isEqualTo(20L);
		assertThat(comparisonBucket.likes()).isEqualTo(20L);
		assertThat(growthPoint.likesHiddenCount()).isEqualTo(1);
		assertThat(comparisonBucket.likesHiddenCount()).isEqualTo(1);
	}

	/**
	 * P0의 비용 절감 자체를 고정한다 — 무거운 조립(스냅샷 시계열·표시 메타·댓글 배치)은 전부
	 * {@link BrandPostAssembler} 안에서 일어나므로, 이 클래스가 지킬 수 있는 계약은 <b>그 경계로
	 * 무엇을 넘기느냐</b>다: 하이드레이트 입력은 겹침 코드({@code ABC})뿐이고 — 풀 전용 코드가
	 * 섞이면 그 코드의 시계열·메타 조회가 되살아난다 — 전량 조립({@code assembleBrandPosts})은
	 * 아예 타지 않는다.
	 */
	@Test
	void index는_겹침_코드만_하이드레이트하고_전량_조립은_타지_않는다() {
		givenIndexFixture();

		assembler().index(USER_ID);

		then(brandPostAssembler).should()
				.hydrate(eq(USER_ID), any(), any(), any(), eq(List.of("ABC")), eq(false));
		then(brandPostAssembler).should(never())
				.assembleBrandPosts(anyLong(), any(), anyBoolean(), any(), anyBoolean(), any());
	}

	/** 후속 하이드레이트(Task 5) 재료 — 인덱스가 이미 읽은 것을 다시 읽지 않게 실어 나른다. */
	@Test
	void index는_하이드레이트_재료를_함께_싣는다() {
		givenIndexFixture();

		var index = assembler().index(USER_ID);

		assertThat(index.userId()).isEqualTo(USER_ID);
		// 레거시 계열은 이미 조립된 카드를 그대로 재사용한다(겹침 병합분 포함).
		assertThat(index.legacyCards()).containsOnlyKeys("900", "901");
		assertThat(index.legacyCards().get("900").brandAccountId()).isEqualTo("42");
		// 겹침 코드(ABC)는 레거시 카드가 정본이라 풀 전용 매핑에 들지 않는다.
		assertThat(index.brandByCode()).containsOnly(entry("POOL1", "42"), entry("POOL2", "42"));
		assertThat(index.brandsById()).containsOnlyKeys("42");
		assertThat(index.brandsById().get("42").account()).isEqualTo(OWN_ACCOUNT);
		assertThat(index.brandsById().get("42").accountType()).isEqualTo("own");
		assertThat(index.campaignsById()).isEmpty();
	}

	/**
	 * 커버리지 클램프(수집 상한 v2 §7-1) — coveredUntil의 KST 달력일보다 앞선 tagged 행은 집계
	 * 모수에서 빠지고, direct 등록 행은 상한 밖이라 면제된다({@code assembleBrandPosts} 술어 승계).
	 */
	@Test
	void index는_커버리지_컷보다_앞선_tagged_행을_빼고_직접_등록은_면제한다() {
		givenLegacy();
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(OWN_LINK));
		given(brandReadRepository.findAccount(BRAND_ID))
				.willReturn(Optional.of(accountCoveredUntil(OffsetDateTime.parse("2026-08-05T00:00:00+09:00"))));
		given(brandReadRepository.findBrandPostIndex(eq(BRAND_ID), any(), eq(false), any())).willReturn(List.of(
				taggedRow("OLD", "2026-08-03T09:00:00+09:00"),
				taggedRow("NEW", "2026-08-06T09:00:00+09:00"),
				directRow("DIR", "2026-08-01T09:00:00+09:00")));
		given(brandPostAssembler.directRegisteredShortCodes(USER_ID)).willReturn(Set.of("DIR"));
		given(brandReadRepository.findLatestSnapshotsForBrand(eq(BRAND_ID), any(), eq(false)))
				.willReturn(List.of());

		var refs = assembler().index(USER_ID).refs();

		assertThat(refs).extracting(DashboardRef::contentKey).containsExactlyInAnyOrder("bt_NEW", "bt_DIR");
	}

	/** 노출 필터(등록자 전용 노출, 08-19) — direct-only 행은 등록한 유저에게만 보인다. */
	@Test
	void index는_남이_등록한_direct_전용_게시물을_노출하지_않는다() {
		givenLegacy();
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(OWN_LINK));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(OWN_ACCOUNT));
		given(brandReadRepository.findBrandPostIndex(eq(BRAND_ID), any(), eq(false), any())).willReturn(List.of(
				taggedRow("T1", "2026-08-06T09:00:00+09:00"),
				directRow("D1", "2026-08-06T09:00:00+09:00")));
		given(brandPostAssembler.directRegisteredShortCodes(USER_ID)).willReturn(Set.of());   // 내 등록이 아니다
		given(brandReadRepository.findLatestSnapshotsForBrand(eq(BRAND_ID), any(), eq(false)))
				.willReturn(List.of());

		var refs = assembler().index(USER_ID).refs();

		assertThat(refs).extracting(DashboardRef::contentKey).containsExactly("bt_T1");
	}

	/** own-first 다계정 병합 — 같은 shortcode가 두 브랜드에 있으면 내 브랜드 귀속이 이긴다(08-12 규칙). */
	@Test
	void index의_겹침_shortcode는_own_브랜드에_귀속된다() {
		givenLegacy();
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(1L, USER_ID, 99L, "rival", "competitor", 12, LAST_COLLECTED, null),
				OWN_LINK));
		BrandAccountRow rival = brandAccount(99L, "rival");
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.of(rival));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(OWN_ACCOUNT));
		given(brandReadRepository.findBrandPostIndex(eq(99L), any(), eq(false), any()))
				.willReturn(List.of(taggedRow("ABC", "2026-08-06T09:00:00+09:00")));
		given(brandReadRepository.findBrandPostIndex(eq(BRAND_ID), any(), eq(false), any()))
				.willReturn(List.of(taggedRow("ABC", "2026-08-06T09:00:00+09:00")));
		given(brandReadRepository.findLatestSnapshotsForBrand(eq(99L), any(), eq(false))).willReturn(List.of());
		given(brandReadRepository.findLatestSnapshotsForBrand(eq(BRAND_ID), any(), eq(false)))
				.willReturn(List.of());

		var index = assembler().index(USER_ID);

		assertThat(index.refs()).singleElement()
				.extracting(DashboardRef::brandAccountId).isEqualTo("42");
		assertThat(index.brandByCode()).containsExactly(entry("ABC", "42"));
		assertThat(index.competitorBrandAccountIds()).containsExactly("99");
	}

	@Test
	void monitoring_비활성이면_index도_레거시_계열만_돌려준다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(OWN_LINK));

		var disabled = new PerformanceContentAssembler(trackingItemAssembler, linkRepository, campaignRepository,
				Optional.empty(), Optional.empty());
		var index = disabled.index(USER_ID);

		assertThat(index.refs()).singleElement().extracting(DashboardRef::source).isEqualTo("individual");
		assertThat(index.brandByCode()).isEmpty();
		assertThat(index.brandsById()).isEmpty();
	}

	@Test
	void 활성_브랜드가_없으면_index도_브랜드_조회를_아예_하지_않는다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		givenNoBrand();

		var index = assembler().index(USER_ID);

		assertThat(index.refs()).hasSize(1);
		assertThat(index.lastCollectedAt()).isEqualTo(LAST_COLLECTED);
		then(brandReadRepository).should(never()).findBrandPostIndex(anyLong(), any(), anyBoolean(), any());
	}

	// ---------- 하이드레이트 패스(2026-08-27 목록 최적화 §1-2) ----------

	/**
	 * 동치성 계약 — 인덱스 전량을 페이지로 넘기면 전량 조립({@link
	 * PerformanceContentAssembler#assembleSlim})과 <b>응답 자체가</b> 같아야 한다. ref 동치성(판정값)
	 * 위에 얹히는 두 번째 기준선이다: 카드 본문(스냅샷 시계열·게시자·캠페인·댓글 셰이프)까지 같아야
	 * 목록 표면을 인덱스+하이드레이트로 갈아끼워도 FE 계약이 바뀌지 않는다.
	 */
	@Test
	void hydratePage_전량은_assembleSlim과_같은_응답을_만든다() {
		givenIndexFixture();
		givenPagePoolHydration();
		givenFullAssemblyBaseline();
		var assembler = assembler();

		var index = assembler.index(USER_ID);
		List<PerformanceContentResponse> hydrated = assembler.hydratePage(index, index.refs());

		assertThat(hydrated).isEqualTo(assembler.assembleSlim(USER_ID).contents());   // 기준선(프로덕션 미사용)
	}

	/**
	 * P0의 절감이 실제로 페이지 크기에 비례하는지 — 무거운 조회(스냅샷 시계열·표시 메타·게시자 배치)는
	 * 전부 {@link BrandPostAssembler#hydrate} 안에서 일어나므로 이 클래스가 지킬 수 있는 계약은
	 * <b>그 경계로 넘기는 코드 목록</b>이다: 페이지에 실린 코드 하나뿐이어야 하고, 페이지 밖 풀 코드가
	 * 섞이면 그 코드의 시계열·메타 조회가 되살아난다.
	 */
	@Test
	void hydratePage는_페이지_코드만_무거운_조회를_한다() {
		givenIndexFixture();
		var assembler = assembler();
		var index = assembler.index(USER_ID);
		// 풀 전용 ref 1건만 페이지로 — 나머지 풀 코드(POOL2)는 하이드레이트 경계를 넘지 말아야 한다.
		var page = index.refs().stream()
				.filter(r -> index.brandByCode().containsKey(r.shortcode())).limit(1).toList();

		assembler.hydratePage(index, page);

		then(brandPostAssembler).should()
				.hydrate(eq(USER_ID), any(), any(), any(), eq(List.of("POOL1")), eq(false));
		then(brandPostAssembler).should(never())
				.hydrate(anyLong(), any(), any(), any(), argThat(codes -> codes.contains("POOL2")), anyBoolean());
	}

	// ---------- 픽스처 ----------

	/**
	 * 경량 인덱스 시나리오 — 레거시 2건(ABC 겹침 · ZZZ individual)과 브랜드 풀 2코드(ABC 겹침 ·
	 * POOL1 풀 전용). 겹침 코드만 하이드레이트되고 풀 전용은 인덱스 행 + 최신 스냅샷 1행으로 직조된다.
	 */
	private void givenIndexFixture() {
		// 풀 전용 두 코드는 작성자 표시값의 두 축을 갈라 준다 — POOL1은 fullName 공백(→ handle 폴백) +
		// 아카이브 사본 없음(→ 원본 CDN URL 폴백), POOL2는 fullName 존재 + 아카이브 오브젝트(→ /img/ 우선).
		AuthorRow poolOneAuthor =
				new AuthorRow("ig-1", "creator", "  ", 1000L, POOL1_PROFILE_PIC_URL, false, null);
		AuthorRow poolTwoAuthor = new AuthorRow("ig-1", "creator", "크리에이터", 1000L,
				"https://cdn.example.com/creator-expired.jpg", false, POOL2_IMAGE_OBJECT_PATH);
		givenLegacy(
				legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
						List.of(snapshot("2026-08-06", 100L, null, 5L))),
				legacyItem("901", "tracking", "https://www.instagram.com/reel/ZZZ/",
						List.of(snapshot("2026-08-06", 10L, 1L, 1L))));
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(OWN_LINK));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(OWN_ACCOUNT));
		given(brandReadRepository.findBrandPostIndex(eq(BRAND_ID), any(), eq(false), any())).willReturn(List.of(
				taggedRow("ABC", "2026-08-06T09:00:00+09:00"), taggedRow("POOL1", "2026-08-06T09:00:00+09:00"),
				hiddenSponsoredRow("POOL2", "2026-08-04T09:00:00+09:00")));
		given(brandReadRepository.findLatestSnapshotsForBrand(eq(BRAND_ID), any(), eq(false))).willReturn(List.of(
				latestSnapshot("ABC", 120L, 8L, null), latestSnapshot("POOL1", 200L, 20L, 2L),
				latestSnapshot("POOL2", 300L, 30L, 3L)));
		given(brandPostAssembler.hydrate(eq(USER_ID), eq(OWN_ACCOUNT), eq("own"), any(), eq(List.of("ABC")),
				eq(false))).willReturn(List.of(overlapPost()));
		given(brandPostAssembler.resolveAuthorsByKeys(any()))
				.willReturn(Map.of("POOL1", poolOneAuthor, "POOL2", poolTwoAuthor));
		// 캠페인 다중 부착 — 응답 필드가 단수라 head("7")만 실린다(설계 §결정 3). POOL1은 매핑 없음.
		given(brandPostAssembler.campaignIdsByCode(eq(BRAND_ID), any()))
				.willReturn(Map.of("POOL2", List.of("7", "8")));
	}

	/**
	 * 같은 시나리오의 <b>페이지 하이드레이트</b> 스텁 — 풀 전용 코드(POOL1·POOL2)는 브랜드당 한 번에
	 * 넘어가고, 돌아오는 카드는 전량 조립 기준선이 쓰는 것과 같은 픽스처다(동치성 비교의 전제).
	 */
	private void givenPagePoolHydration() {
		given(brandPostAssembler.hydrate(eq(USER_ID), eq(OWN_ACCOUNT), eq("own"), any(),
				eq(List.of("POOL1", "POOL2")), eq(false)))
				.willReturn(List.of(poolOnlyPost(), hiddenSponsoredPoolPost()));
	}

	/** 같은 시나리오를 전량 조립(슬림) 경로로도 스텁한다 — 동치성 비교의 기준선. */
	private void givenFullAssemblyBaseline() {
		given(brandPostAssembler.assembleBrandPosts(USER_ID, OWN_ACCOUNT, false, BrandPostScope.ALL, true, "own"))
				.willReturn(List.of(overlapPost(), poolOnlyPost(), hiddenSponsoredPoolPost()));
	}

	private static BrandPostResponse overlapPost() {
		return taggedPost("ABC", List.of(snapshot("2026-08-06", 120L, 8L, null)));
	}

	/**
	 * 풀 전용 카드 — 작성자 fullName이 공백이라 displayName은 handle로 폴백하고, 프로필 이미지는
	 * 아카이브 사본이 없어 원본 CDN URL로 폴백한다({@link #givenIndexFixture}의 POOL1 author 대응).
	 */
	private static BrandPostResponse poolOnlyPost() {
		return brandPost("POOL1", "tagged",
				List.of(snapshot("2026-08-05", 100L, 10L, 1L), snapshot("2026-08-06", 200L, 20L, 2L)),
				"브랜드 태그 캡션", false, BRAND_ID, List.of(), "tracking", "  ", POOL1_PROFILE_PIC_URL);
	}

	/**
	 * 풀 전용 카드 — 삭제·비공개 감지(hidden) + 유료협찬 관측(sponsored) + 캠페인 2건 부착.
	 * 나머지 픽스처는 세 값이 전부 상수(tracking·unknown·null)라 {@link
	 * PerformanceContentAssembler} 경량 ref의 상태·협찬·캠페인 head 파생을 동치성 비교가
	 * 판별하지 못한다 — 이 행이 그 셋을 실제 판별 대상으로 만든다.
	 *
	 * <p>프로필 이미지는 아카이브 사본 경로({@code /img/...})다 — 원본 CDN URL로 폴백하는 POOL1과
	 * 짝을 이뤄 ref의 이미지 산지(아카이브 우선) 파생을 판별한다.
	 */
	private static BrandPostResponse hiddenSponsoredPoolPost() {
		SnapshotResponse latest = snapshot("2026-08-04", 300L, 30L, 3L);
		return new BrandPostResponse("POOL2", String.valueOf(BRAND_ID), "tagged", null,
				"https://www.instagram.com/reel/POOL2/", "POOL2", "reels",
				"2026-08-04T09:00:00+09:00", "브랜드 태그 캡션", null, null, null,
				"https://www.instagram.com/creator/", "creator", "크리에이터",
				"/img/" + POOL2_IMAGE_OBJECT_PATH, false, 1000L,
				"sponsored", true, "hidden", "2026-08-04T09:30:00+09:00", null, latest, List.of(latest),
				latest.comments(), false, 0L, List.of(), List.of("7", "8"),
				"2026-08-04T09:30:00+09:00", "2026-08-07T03:00:00+09:00",
				null, List.of(), List.of(), false);
	}

	/** 태그 감지 행(전원 노출) — 캡션·유료협찬 관측은 브랜드 풀 픽스처와 같은 값(협찬 unknown). */
	private static BrandPostIndexRow taggedRow(String shortCode, String takenAt) {
		return new BrandPostIndexRow(shortCode, OffsetDateTime.parse(takenAt),
				OffsetDateTime.parse("2026-08-06T09:30:00+09:00"), null, null, null, "creator", "ig-1",
				null, false, null, null, null, null, null, null, null);
	}

	/** 삭제·비공개 감지 + 유료협찬 관측 행 — status(hidden)·sponsorship(sponsored) 파생 판별용. */
	private static BrandPostIndexRow hiddenSponsoredRow(String shortCode, String takenAt) {
		return new BrandPostIndexRow(shortCode, OffsetDateTime.parse(takenAt),
				OffsetDateTime.parse("2026-08-04T09:30:00+09:00"), null, null,
				OffsetDateTime.parse("2026-08-07T01:00:00+09:00"), "creator", "ig-1",
				true, false, null, null, null, null, null, null, null);
	}

	/** 직접 등록 전용 행(tag_detected_at 없음) — 등록자에게만 보이고 커버리지 클램프 면제 대상이다. */
	private static BrandPostIndexRow directRow(String shortCode, String takenAt) {
		return new BrandPostIndexRow(shortCode, OffsetDateTime.parse(takenAt), null,
				OffsetDateTime.parse("2026-08-06T09:30:00+09:00"), null, null, "creator", "ig-1",
				null, false, null, null, null, null, null, null, null);
	}

	private static LatestSnapshotRow latestSnapshot(String shortCode, Long views, Long likes, Long comments) {
		return new LatestSnapshotRow(shortCode, LocalDate.of(2026, 8, 6), "REELS", views, likes, false, comments);
	}

	/** 실수집 상한(coveredUntil)이 걸린 계정 — 클램프 술어 검증용. */
	private static BrandAccountRow accountCoveredUntil(OffsetDateTime coveredUntil) {
		return new BrandAccountRow(BRAND_ID, "brand", LocalDate.of(2026, 8, 7), LAST_COLLECTED,
				LAST_COLLECTED, LAST_COLLECTED, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, LAST_COLLECTED, false, coveredUntil);
	}

	private void givenLegacy(TrackingItemResponse... items) {
		given(trackingItemAssembler.assembleList(USER_ID)).willReturn(new TrackingItemAssembler.AssembledList(
				List.of(items), LAST_COLLECTED, LocalDate.of(2026, 8, 7)));
	}

	private void givenNoBrand() {
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of());
	}

	private void givenBrand(BrandPostResponse... brandPosts) {
		givenBrandSweptAt(LAST_COLLECTED, brandPosts);
	}

	private void givenBrandSweptAt(OffsetDateTime lastSweptAt, BrandPostResponse... brandPosts) {
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(new BrandLinkRow(1L, USER_ID,
				BRAND_ID, "brand", "own", 12, LAST_COLLECTED, null)));
		BrandAccountRow account = new BrandAccountRow(BRAND_ID, "brand", LocalDate.of(2026, 8, 7), lastSweptAt,
				LAST_COLLECTED, LAST_COLLECTED, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, LAST_COLLECTED, false, null);
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(account));
		given(brandPostAssembler.assembleBrandPosts(USER_ID, account, true, BrandPostScope.ALL, true, "own"))
				.willReturn(List.of(brandPosts));
	}

	private static SnapshotResponse snapshot(String date, Long views, Long likes, Long comments) {
		return new SnapshotResponse(date, views, likes, false, comments, null, null, false, null);
	}

	private static TrackingItemResponse legacyItem(String id, String status, String postUrl,
			List<SnapshotResponse> snapshots) {
		return legacyItem(id, status, postUrl, snapshots, "2026-08-01");
	}

	private static TrackingItemResponse legacyItem(String id, String status, String postUrl,
			List<SnapshotResponse> snapshots, String uploadedAt) {
		TrackingItemResponse.TrackedPostResponse post = postUrl == null ? null
				: new TrackingItemResponse.TrackedPostResponse(postUrl, "reels", uploadedAt, "", List.of(),
						null, null, snapshots, List.of());
		return new TrackingItemResponse(id, "url", status, "creator", "크리에이터", null, 500L, null, null, null,
				postUrl, "2026-08-01", 30, null, post, null);
	}

	/** 스냅샷 2건(8-05 → 8-06) 레거시 아이템 — 직전 스냅샷 지표 검증용 픽스처. */
	private static TrackingItemResponse twoSnapshotItem() {
		return legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-05", 100L, 10L, 1L), snapshot("2026-08-06", 200L, 20L, 2L)));
	}

	private static TrackingItemResponse legacyItemWithCaption(String id, String postUrl, String caption) {
		return new TrackingItemResponse(id, "url", "tracking", "creator", "크리에이터", null, 500L, null, null,
				null, postUrl, "2026-08-01", 30, null,
				new TrackingItemResponse.TrackedPostResponse(postUrl, "feed", "2026-08-01", caption, List.of(),
						null, null, List.of(), List.of()),
				null);
	}

	private static BrandPostResponse taggedPost(String shortcode, List<SnapshotResponse> snapshots) {
		return brandPost(shortcode, "tagged", snapshots, "브랜드 태그 캡션", false, BRAND_ID, List.of());
	}

	private static BrandPostResponse taggedPostWithCampaigns(String shortcode, List<String> campaignIds) {
		return brandPost(shortcode, "tagged", List.of(), "브랜드 태그 캡션", false, BRAND_ID, campaignIds);
	}

	private static BrandPostResponse directPost(String shortcode, List<SnapshotResponse> snapshots) {
		return brandPost(shortcode, "direct", snapshots, "브랜드 태그 캡션", false, BRAND_ID, List.of());
	}

	private static BrandPostResponse taggedPostWithCaption(String shortcode, String caption) {
		return brandPost(shortcode, "tagged", List.of(), caption, false, BRAND_ID, List.of());
	}

	/** 브랜드 스윕이 "댓글 숨김"을 관측한 게시물(최신 스냅샷의 comments가 null). */
	private static BrandPostResponse taggedPostWithCommentsHidden(String shortcode,
			List<SnapshotResponse> snapshots) {
		return brandPost(shortcode, "tagged", snapshots, "브랜드 태그 캡션", true, BRAND_ID, List.of());
	}

	/** 특정 브랜드에 귀속된 tagged 관측 — 같은 게시물의 다계정 귀속 우선순위 검증용. */
	private static BrandPostResponse taggedPostOf(String shortcode, long brandId) {
		return brandPost(shortcode, "tagged", List.of(), "브랜드 태그 캡션", false, brandId, List.of());
	}

	/** 특정 브랜드에 direct 등록된 관측 — ownFirst 귀속 검증용. */
	private static BrandPostResponse directPostOf(String shortcode, long brandId) {
		return brandPost(shortcode, "direct", List.of(), "브랜드 태그 캡션", false, brandId, List.of());
	}

	/** monitoring brand_account 1행 — 스윕 완주 상태(lastSweptAt 존재). */
	private static BrandAccountRow brandAccount(long id, String username) {
		return new BrandAccountRow(id, username, LocalDate.of(2026, 8, 7), LAST_COLLECTED,
				LAST_COLLECTED, LAST_COLLECTED, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, LAST_COLLECTED, false, null);
	}

	private static BrandPostResponse brandPost(String shortcode, String source, List<SnapshotResponse> snapshots,
			String caption, boolean commentsHidden, long brandId, List<String> campaignIds) {
		return brandPost(shortcode, source, snapshots, caption, commentsHidden, brandId, campaignIds, "tracking");
	}

	/** 삭제·비공개 감지(hidden)된 direct 게시물 — 합성 아이템의 status 승계 검증용(2026-08-25 설계). */
	private static BrandPostResponse directPostHidden(String shortcode) {
		return brandPost(shortcode, "direct", List.of(), "브랜드 태그 캡션", false, BRAND_ID, List.of(), "hidden");
	}

	private static BrandPostResponse brandPost(String shortcode, String source, List<SnapshotResponse> snapshots,
			String caption, boolean commentsHidden, long brandId, List<String> campaignIds, String trackingStatus) {
		return brandPost(shortcode, source, snapshots, caption, commentsHidden, brandId, campaignIds,
				trackingStatus, "크리에이터", null);
	}

	/** 작성자 표시 필드까지 지정하는 산지 — displayName 폴백·프로필 이미지 산지 판별 픽스처가 쓴다. */
	private static BrandPostResponse brandPost(String shortcode, String source, List<SnapshotResponse> snapshots,
			String caption, boolean commentsHidden, long brandId, List<String> campaignIds, String trackingStatus,
			String authorFullName, String authorProfilePicUrl) {
		SnapshotResponse latest = snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
		return new BrandPostResponse(shortcode, String.valueOf(brandId), source, null,
				"https://www.instagram.com/reel/" + shortcode + "/", shortcode, "reels",
				"2026-08-06T09:00:00+09:00", caption, null, null, null,
				"https://www.instagram.com/creator/", "creator", authorFullName, authorProfilePicUrl, false, 1000L,
				"unknown", null, trackingStatus, "2026-08-06T09:30:00+09:00", null, latest, snapshots,
				latest == null ? null : latest.comments(), commentsHidden, 0L, List.of(), campaignIds,
				"2026-08-06T09:30:00+09:00", "2026-08-07T03:00:00+09:00",
				null, List.of(), List.of(), false);
	}
}
