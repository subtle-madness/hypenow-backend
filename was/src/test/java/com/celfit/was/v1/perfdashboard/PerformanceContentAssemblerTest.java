package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandPostResponse;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import com.celfit.was.v1.monitoring.TrackingItemResponse.PostCommentResponse;
import com.celfit.was.v1.monitoring.TrackingItemResponse.SnapshotResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 성과 대시보드 통합 조립(스펙 §7-1) 단위 고정 — 레거시(individual·direct)와 브랜드 tagged를
 * shortcode로 합치는 규칙, 지표별 스냅샷 병합, tagged-only 합성 아이템을 검증한다.
 * 하부(레거시 어셈블러·브랜드 어셈블러·매핑 리포지토리)는 전부 mock이다 — 이 클래스가 지는
 * 책임은 "이미 조립된 두 계열을 어떻게 겹치느냐"뿐이라 DB 왕복 없이 고정할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class PerformanceContentAssemblerTest {

	private static final long USER_ID = 7L;
	private static final long BRAND_ID = 42L;
	private static final OffsetDateTime LAST_COLLECTED = OffsetDateTime.parse("2026-08-07T02:00:00+09:00");
	private static final OffsetDateTime BRAND_SWEPT_AT = OffsetDateTime.parse("2026-08-07T03:00:00+09:00");

	@Mock
	private TrackingItemAssembler trackingItemAssembler;
	@Mock
	private BrandDirectPostRepository directPostRepository;
	@Mock
	private BrandLinkRepository linkRepository;
	@Mock
	private BrandReadRepository brandReadRepository;
	@Mock
	private BrandPostAssembler brandPostAssembler;

	private PerformanceContentAssembler assembler() {
		return new PerformanceContentAssembler(trackingItemAssembler, directPostRepository, linkRepository,
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

	@Test
	void 한쪽이_비면_다른_쪽을_그대로_돌려준다() {
		var only = List.of(snapshot("2026-08-06", 100L, 1L, 5L));

		assertThat(PerformanceContentAssembler.mergeSnapshots(only, List.of())).isEqualTo(only);
		assertThat(PerformanceContentAssembler.mergeSnapshots(List.of(), only)).isEqualTo(only);
		assertThat(PerformanceContentAssembler.mergeSnapshots(List.of(), List.of())).isEmpty();
	}

	@Test
	void 날짜_키는_앞_10자다() {
		// tagged 산지의 date가 타임스탬프로 들어와도 같은 날짜로 접힌다(takenAt 타입 혼재 방어).
		var legacy = List.of(snapshot("2026-08-06", 100L, null, 5L));
		var brand = List.of(snapshot("2026-08-06T03:00:00+09:00", null, 8L, null));

		var merged = PerformanceContentAssembler.mergeSnapshots(legacy, brand);

		assertThat(merged).hasSize(1);
		assertThat(merged.get(0).date()).isEqualTo("2026-08-06");
		assertThat(merged.get(0).views()).isEqualTo(100L);
		assertThat(merged.get(0).likes()).isEqualTo(8L);
	}

	// ---------- 중복 제거 ----------

	@Test
	void 중복_제거는_individual_direct_tagged_우선순위다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 100L, null, 5L))));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenBrand(taggedPost("ABC", List.of(snapshot("2026-08-06", 120L, 8L, null))));

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents).hasSize(1);
		var content = contents.get(0);
		assertThat(content.source()).isEqualTo("individual");
		assertThat(content.additionalSources()).containsExactly("tagged");
		assertThat(content.item().id()).isEqualTo("900");
		assertThat(content.canonicalPostId()).isEqualTo("ABC");
		assertThat(content.brandAccountId()).isEqualTo("42");
		// 겹친 날짜는 지표별 병합 결과가 실린다.
		assertThat(content.item().post().snapshots()).hasSize(1);
		assertThat(content.item().post().snapshots().get(0).views()).isEqualTo(120L);
		assertThat(content.item().post().snapshots().get(0).comments()).isEqualTo(5L);
	}

	@Test
	void 매핑된_레거시_아이템은_direct다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of(directRow("ABC", 900L)));
		givenNoBrand();

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents).hasSize(1);
		assertThat(contents.get(0).source()).isEqualTo("direct");
		assertThat(contents.get(0).additionalSources()).isEmpty();
		// tagged 관측이 없어도 브랜드 소속은 매핑으로 확정된다(Task 10 brandAccountId 필터 대상).
		assertThat(contents.get(0).brandAccountId()).isEqualTo("42");
	}

	@Test
	void 게시물_확정_전_직접_등록분도_아이템_id로_direct다() {
		// collecting은 post가 없어 shortcode를 만들 수 없다 — shortcode만 보면 첫 수집까지 individual로 샌다.
		givenLegacy(legacyItem("900", "collecting", null, null));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of(directRow("ABC", 900L)));
		givenNoBrand();

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents.get(0).source()).isEqualTo("direct");
		assertThat(contents.get(0).canonicalPostId()).isNull();
		assertThat(contents.get(0).brandAccountId()).isEqualTo("42");
	}

	@Test
	void individual은_brandAccountId가_null이다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenNoBrand();

		assertThat(assembler().assemble(USER_ID).contents().get(0).brandAccountId()).isNull();
	}

	@Test
	void tagged_only는_bt_접두_합성_아이템이다() {
		givenLegacy();
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
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
	void 게시물_없는_detecting은_canonicalPostId가_null이다() {
		givenLegacy(legacyItem("901", "detecting", null, null));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenNoBrand();

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents).hasSize(1);
		assertThat(contents.get(0).canonicalPostId()).isNull();
		assertThat(contents.get(0).item().post()).isNull();
		assertThat(contents.get(0).source()).isEqualTo("individual");
		assertThat(contents.get(0).sponsorship()).isEqualTo("unknown");
	}

	@Test
	void 협찬_판정은_tagged_유료협찬_관측으로_승격한다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenBrand(taggedPost("ABC", List.of()).withSponsorship("sponsored", true));

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents.get(0).sponsorship()).isEqualTo("sponsored");
	}

	@Test
	void 댓글_숨김은_병합된_최신_스냅샷에서_유도한다() {
		// 레거시가 센 댓글 5건이 병합 결과에 남는데 브랜드 관측만 보고 hidden=true를 내면 모순이다.
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 100L, null, 5L))));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenBrand(taggedPostWithCommentsHidden("ABC", List.of(snapshot("2026-08-06", 120L, 8L, null))));

		var post = assembler().assemble(USER_ID).contents().get(0).item().post();

		assertThat(post.commentsTotal()).isEqualTo(5L);
		assertThat(post.commentsHidden()).isFalse();
	}

	@Test
	void 양쪽_모두_댓글을_못_본_날은_숨김이다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/",
				List.of(snapshot("2026-08-06", 100L, null, null))));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenBrand(taggedPostWithCommentsHidden("ABC", List.of(snapshot("2026-08-06", 120L, 8L, null))));

		var post = assembler().assemble(USER_ID).contents().get(0).item().post();

		assertThat(post.commentsTotal()).isNull();
		assertThat(post.commentsHidden()).isTrue();
	}

	@Test
	void 레거시_캡션이_비면_tagged_캡션으로_협찬을_판정한다() {
		// 레거시는 메타 미수집 시 캡션이 빈 문자열이다 — 그 경우에만 tagged 캡션으로 폴백한다.
		givenLegacy(legacyItemWithCaption("900", "https://www.instagram.com/p/ABC/", ""));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenBrand(taggedPostWithCaption("ABC", "가을 신상 #협찬"));

		var content = assembler().assemble(USER_ID).contents().get(0);

		// isPaidPartnership 관측은 없고(null) tagged 캡션의 확정 키워드만으로 승격된다.
		assertThat(content.sponsorship()).isEqualTo("sponsored");
	}

	@Test
	void 레거시_단독_협찬은_캡션_키워드로만_판정한다() {
		givenLegacy(legacyItemWithCaption("900", "https://www.instagram.com/p/DEF/", "신상 추천 #광고"));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenNoBrand();

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents.get(0).sponsorship()).isEqualTo("sponsored");
	}

	@Test
	void 활성_브랜드가_없으면_브랜드_조회를_아예_하지_않는다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of());

		var assembled = assembler().assemble(USER_ID);

		assertThat(assembled.contents()).hasSize(1);
		assertThat(assembled.lastCollectedAt()).isEqualTo(LAST_COLLECTED);
		then(brandPostAssembler).should(never()).assembleTagged(any());
	}

	@Test
	void lastCollectedAt은_레거시와_브랜드_스윕_중_늦은_쪽이다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		// 두 시각은 "늦은 쪽이 이긴다"만 보려는 임의 픽스처다(실제 스윕 크론 시각과 무관).
		// 여기선 브랜드 쪽이 더 늦으므로 브랜드 시각이 "마지막 수집"이 된다.
		givenBrandSweptAt(BRAND_SWEPT_AT, taggedPost("ABC", List.of()));

		assertThat(assembler().assemble(USER_ID).lastCollectedAt()).isEqualTo(BRAND_SWEPT_AT);
	}

	@Test
	void 브랜드_스윕이_더_이르면_레거시_시각을_쓴다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenBrandSweptAt(OffsetDateTime.parse("2026-08-06T03:00:00+09:00"), taggedPost("ABC", List.of()));

		assertThat(assembler().assemble(USER_ID).lastCollectedAt()).isEqualTo(LAST_COLLECTED);
	}

	@Test
	void monitoring_비활성이면_tagged_계열만_건너뛰고_링크는_읽는다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenNoBrand();

		var disabled = new PerformanceContentAssembler(trackingItemAssembler, directPostRepository,
				linkRepository, Optional.empty(), Optional.empty());
		var contents = disabled.assemble(USER_ID).contents();

		assertThat(contents).hasSize(1);
		assertThat(contents.get(0).source()).isEqualTo("individual");
		// 링크는 읽는다(08-12) — 구독 타입 판정은 app DataSource만으로 성립하고, 안 읽으면
		// 경쟁사 direct 콘텐츠가 기본 범위에 샌다. tagged 계열만 건너뛴다.
		then(linkRepository).should().findAllActiveByUser(USER_ID);
	}

	@Test
	void monitoring_비활성이어도_경쟁사_direct_콘텐츠가_기본_범위에서_걸러진다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		// 직접 등록 매핑은 monitoring 게이트 밖(app DataSource)이라 비활성 환경에서도 브랜드에 귀속된다.
		given(directPostRepository.findByUser(USER_ID))
				.willReturn(List.of(new BrandDirectPostRepository.Row(USER_ID, 99L, "ABC", 900L)));
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(2L, USER_ID, 99L, "rival", "competitor", LAST_COLLECTED, null)));

		var disabled = new PerformanceContentAssembler(trackingItemAssembler, directPostRepository,
				linkRepository, Optional.empty(), Optional.empty());
		var assembled = disabled.assemble(USER_ID);

		// 운영 기본값이 MONITORING_ENABLED=false다 — 컨트롤러 술어가 쓰는 두 값(콘텐츠의
		// brandAccountId·경쟁사 집합)이 이 환경에서도 맞물려야 기본 범위에서 빠진다.
		var content = assembled.contents().get(0);
		assertThat(content.source()).isEqualTo("direct");
		assertThat(content.brandAccountId()).isEqualTo("99");
		assertThat(assembled.competitorBrandAccountIds()).contains(content.brandAccountId());
	}

	@Test
	void 경쟁사_집합은_tagged_콘텐츠의_brandAccountId와_같은_값_공간이다() {
		givenLegacy();
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(2L, USER_ID, 99L, "rival", "competitor", LAST_COLLECTED, null)));
		BrandAccountRow rival = brandAccount(99L, "rival");
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.of(rival));
		given(brandPostAssembler.assembleTagged(rival)).willReturn(List.of(taggedPostOf("ABC", 99L)));

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
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(1L, USER_ID, BRAND_ID, "brand", "own", LAST_COLLECTED, null),
				new BrandLinkRow(2L, USER_ID, 99L, "rival", "competitor", LAST_COLLECTED, null)));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.empty());
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.empty());

		var assembled = assembler().assemble(USER_ID);

		// monitoring 계정 행이 없어 tagged 조립은 건너뛰어도 구독 타입 판정은 링크만으로 성립한다.
		assertThat(assembled.competitorBrandAccountIds()).containsExactly("99");
	}

	@Test
	void 같은_게시물이_내_브랜드와_경쟁사에_동시_태그되면_내_브랜드로_귀속된다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		// 경쟁사 연결이 더 오래됐다(목록 앞) — 그래도 own 귀속이 이겨야 한다. 귀속이 이제 표시가
		// 아니라 범위를 정하기 때문에, 연결 순서가 "내 게시물이 내 요약에 보이는지"를 정하면 안 된다.
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(1L, USER_ID, 99L, "rival", "competitor", LAST_COLLECTED, null),
				new BrandLinkRow(2L, USER_ID, BRAND_ID, "brand", "own", LAST_COLLECTED, null)));
		BrandAccountRow rival = brandAccount(99L, "rival");
		BrandAccountRow mine = brandAccount(BRAND_ID, "brand");
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.of(rival));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(mine));
		given(brandPostAssembler.assembleTagged(rival)).willReturn(List.of(taggedPostOf("ABC", 99L)));
		given(brandPostAssembler.assembleTagged(mine)).willReturn(List.of(taggedPostOf("ABC", BRAND_ID)));

		var assembled = assembler().assemble(USER_ID);

		var content = assembled.contents().get(0);
		assertThat(content.brandAccountId()).isEqualTo(String.valueOf(BRAND_ID));
		// 기본 범위 판정(컨트롤러 술어)에서 살아남는다.
		assertThat(assembled.competitorBrandAccountIds()).doesNotContain(content.brandAccountId());
	}

	@Test
	void 내_브랜드에_직접_등록한_게시물은_경쟁사_태그_관측에_귀속을_뺏기지_않는다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		// 내 브랜드로 직접 등록된 게시물인데, 태그는 경쟁사 계정에만 달렸다.
		given(directPostRepository.findByUser(USER_ID))
				.willReturn(List.of(new BrandDirectPostRepository.Row(USER_ID, BRAND_ID, "ABC", 900L)));
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(1L, USER_ID, BRAND_ID, "brand", "own", LAST_COLLECTED, null),
				new BrandLinkRow(2L, USER_ID, 99L, "rival", "competitor", LAST_COLLECTED, null)));
		BrandAccountRow mine = brandAccount(BRAND_ID, "brand");
		BrandAccountRow rival = brandAccount(99L, "rival");
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(mine));
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.of(rival));
		given(brandPostAssembler.assembleTagged(mine)).willReturn(List.of());
		given(brandPostAssembler.assembleTagged(rival)).willReturn(List.of(taggedPostOf("ABC", 99L)));

		var assembled = assembler().assemble(USER_ID);

		var content = assembled.contents().get(0);
		assertThat(content.source()).isEqualTo("direct");
		// 관측값(경쟁사) 우선 규칙의 유일한 예외 — own direct 귀속을 지킨다(스펙 §5 기본 범위).
		assertThat(content.brandAccountId()).isEqualTo(String.valueOf(BRAND_ID));
		// 기본 범위엔 남고 accountType=competitor에는 안 잡힌다(컨트롤러 술어와 같은 판정).
		assertThat(assembled.competitorBrandAccountIds()).doesNotContain(content.brandAccountId());
		// tagged 관측 자체는 버리지 않는다 — 스냅샷 병합·추가 산지 표기는 그대로다.
		assertThat(content.additionalSources()).containsExactly("tagged");
	}

	@Test
	void 경쟁사에_직접_등록한_게시물은_경쟁사_귀속_그대로다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID))
				.willReturn(List.of(new BrandDirectPostRepository.Row(USER_ID, 99L, "ABC", 900L)));
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(
				new BrandLinkRow(2L, USER_ID, 99L, "rival", "competitor", LAST_COLLECTED, null)));
		BrandAccountRow rival = brandAccount(99L, "rival");
		given(brandReadRepository.findAccount(99L)).willReturn(Optional.of(rival));
		given(brandPostAssembler.assembleTagged(rival)).willReturn(List.of(taggedPostOf("ABC", 99L)));

		var assembled = assembler().assemble(USER_ID);

		// 양쪽이 같은 타입이면 예외가 발동하지 않는다 — 기존 "관측값 우선" 그대로.
		assertThat(assembled.contents().get(0).brandAccountId()).isEqualTo("99");
		assertThat(assembled.competitorBrandAccountIds()).containsExactly("99");
	}

	@Test
	void 활성_브랜드가_없으면_경쟁사_집합도_비어_있다() {
		givenLegacy(legacyItem("900", "tracking", "https://www.instagram.com/reel/ABC/", List.of()));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenNoBrand();

		assertThat(assembler().assemble(USER_ID).competitorBrandAccountIds()).isEmpty();
	}

	@Test
	void 결과는_업로드_최신순이고_업로드일_미상은_마지막이다() {
		givenLegacy(
				legacyItem("900", "tracking", "https://www.instagram.com/reel/AAA/", List.of(), "2026-08-01"),
				legacyItem("901", "detecting", null, null),
				legacyItem("902", "tracking", "https://www.instagram.com/reel/BBB/", List.of(), "2026-08-06"));
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenNoBrand();

		var contents = assembler().assemble(USER_ID).contents();

		assertThat(contents).extracting(c -> c.item().id()).containsExactly("902", "900", "901");
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
		given(directPostRepository.findByUser(USER_ID)).willReturn(List.of());
		givenNoBrand();

		var post = assembler().assemble(USER_ID).contents().get(0).item().post();

		assertThat(post.commentsTotal()).isEqualTo(9L);
		assertThat(post.commentsCollectedCount()).isEqualTo(1L);
		assertThat(post.commentsHidden()).isFalse();
		assertThat(post.shortcode()).isEqualTo("ABC");
		assertThat(post.recentComments()).isEqualTo(comments);
	}

	@Test
	void shortcode는_url_경로에서_뽑고_형식이_다르면_null이다() {
		assertThat(PerformanceContentAssembler.shortcodeOf("https://www.instagram.com/reel/ABC-1_2/")).isEqualTo("ABC-1_2");
		assertThat(PerformanceContentAssembler.shortcodeOf("https://www.instagram.com/p/DEF/?img_index=1")).isEqualTo("DEF");
		assertThat(PerformanceContentAssembler.shortcodeOf("https://www.instagram.com/reels/GHI/")).isEqualTo("GHI");
		assertThat(PerformanceContentAssembler.shortcodeOf("https://www.instagram.com/share/XYZ/")).isNull();
		assertThat(PerformanceContentAssembler.shortcodeOf(null)).isNull();
	}

	// ---------- 픽스처 ----------

	private void givenLegacy(TrackingItemResponse... items) {
		given(trackingItemAssembler.assembleList(USER_ID)).willReturn(new TrackingItemAssembler.AssembledList(
				List.of(items), LAST_COLLECTED, LocalDate.of(2026, 8, 7)));
	}

	private void givenNoBrand() {
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of());
	}

	private void givenBrand(BrandPostResponse... taggedPosts) {
		givenBrandSweptAt(LAST_COLLECTED, taggedPosts);
	}

	private void givenBrandSweptAt(OffsetDateTime lastSweptAt, BrandPostResponse... taggedPosts) {
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(new BrandLinkRow(1L, USER_ID,
				BRAND_ID, "brand", "own", LAST_COLLECTED, null)));
		BrandAccountRow account = new BrandAccountRow(BRAND_ID, "brand", LocalDate.of(2026, 8, 7), lastSweptAt,
				LAST_COLLECTED, LAST_COLLECTED, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, LAST_COLLECTED);
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(account));
		given(brandPostAssembler.assembleTagged(account)).willReturn(List.of(taggedPosts));
	}

	private static BrandDirectPostRepository.Row directRow(String shortCode, long itemId) {
		return new BrandDirectPostRepository.Row(USER_ID, BRAND_ID, shortCode, itemId);
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

	private static TrackingItemResponse legacyItemWithCaption(String id, String postUrl, String caption) {
		return new TrackingItemResponse(id, "url", "tracking", "creator", "크리에이터", null, 500L, null, null,
				null, postUrl, "2026-08-01", 30, null,
				new TrackingItemResponse.TrackedPostResponse(postUrl, "feed", "2026-08-01", caption, List.of(),
						null, null, List.of(), List.of()),
				null);
	}

	private static BrandPostResponse taggedPost(String shortcode, List<SnapshotResponse> snapshots) {
		return taggedPost(shortcode, snapshots, "브랜드 태그 캡션", false);
	}

	private static BrandPostResponse taggedPostWithCaption(String shortcode, String caption) {
		return taggedPost(shortcode, List.of(), caption, false);
	}

	/** 브랜드 스윕이 "댓글 숨김"을 관측한 게시물(최신 스냅샷의 comments가 null). */
	private static BrandPostResponse taggedPostWithCommentsHidden(String shortcode,
			List<SnapshotResponse> snapshots) {
		return taggedPost(shortcode, snapshots, "브랜드 태그 캡션", true);
	}

	/** 특정 브랜드에 귀속된 tagged 관측 — 같은 게시물의 다계정 귀속 우선순위 검증용. */
	private static BrandPostResponse taggedPostOf(String shortcode, long brandId) {
		return taggedPost(shortcode, List.of(), "브랜드 태그 캡션", false, brandId);
	}

	/** monitoring brand_account 1행 — 스윕 완주 상태(lastSweptAt 존재). */
	private static BrandAccountRow brandAccount(long id, String username) {
		return new BrandAccountRow(id, username, LocalDate.of(2026, 8, 7), LAST_COLLECTED,
				LAST_COLLECTED, LAST_COLLECTED, null, 10L, 1L, 2L, null, "브랜드", null, true, null, "active", null,
				12, LAST_COLLECTED);
	}

	private static BrandPostResponse taggedPost(String shortcode, List<SnapshotResponse> snapshots,
			String caption, boolean commentsHidden) {
		return taggedPost(shortcode, snapshots, caption, commentsHidden, BRAND_ID);
	}

	private static BrandPostResponse taggedPost(String shortcode, List<SnapshotResponse> snapshots,
			String caption, boolean commentsHidden, long brandId) {
		SnapshotResponse latest = snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
		return new BrandPostResponse(shortcode, String.valueOf(brandId), "tagged",
				"https://www.instagram.com/reel/" + shortcode + "/", shortcode, "reels",
				"2026-08-06T09:00:00+09:00", caption, null, null, null,
				"https://www.instagram.com/creator/", "creator", "크리에이터", null, false, 1000L,
				"unknown", null, "tracking", "2026-08-06T09:30:00+09:00", null, latest, snapshots,
				latest == null ? null : latest.comments(), commentsHidden, 0L, List.of(), List.of(),
				"2026-08-06T09:30:00+09:00", "2026-08-07T03:00:00+09:00");
	}
}
