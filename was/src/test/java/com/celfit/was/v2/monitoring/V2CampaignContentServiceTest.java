package com.celfit.was.v2.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler.BrandPostScope;
import com.celfit.was.v1.brandmonitoring.BrandPostResponse;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.ItemStatus;
import com.celfit.was.v1.monitoring.MonitoringRegistrationResponse;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import com.celfit.was.v1.monitoring.V1MonitoringRegistrationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 캠페인 v2 콘텐츠 관계(스펙 §8) 단위 검증 — 레거시(등록 서비스)·슬림 링커는 mock이다.
 * 검증 대상은 <b>분기 판정</b>(연결/중복/이동 금지/위임/미존재)과 <b>위임 셰이프</b>
 * (posts·trackingDays·campaignId)다. 캠페인 연결은 레거시 patch가 아니라 슬림 경로
 * ({@link CampaignItemLinker})로 간다 — 소유 검증은 링커 단위 테스트가 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class V2CampaignContentServiceTest {

	private static final long USER_ID = 7L;
	private static final long CAMPAIGN_ID = 42L;
	private static final long BRAND_ID = 900L;
	private static final long RIVAL_BRAND_ID = 901L;

	@Mock
	CampaignRepository campaignRepository;
	@Mock
	TrackingItemAssembler trackingItemAssembler;
	@Mock
	CampaignItemLinker linker;
	@Mock
	V1MonitoringRegistrationService registrationService;
	@Mock
	BrandLinkRepository linkRepository;
	@Mock
	MonitoringItemRepository itemRepository;
	@Mock
	BrandReadRepository brandReadRepository;
	@Mock
	BrandPostAssembler brandPostAssembler;

	@Captor
	ArgumentCaptor<Map<String, Object>> bodyCaptor;

	V2CampaignContentService service;

	@BeforeEach
	void setUp() {
		service = new V2CampaignContentService(campaignRepository, trackingItemAssembler, linker,
				registrationService, linkRepository, itemRepository, Optional.of(brandReadRepository),
				Optional.of(brandPostAssembler));
	}

	// ---------- 추가: 기존 아이템 ----------

	@Test
	void 기존_아이템은_캠페인만_연결한다() {
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, null, null, "https://www.instagram.com/reel/ABC/"));

		V2CampaignContentService.Added added = service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30);

		// 레거시 patch(전량 재조립)가 아니라 슬림 경로로 campaign_id만 갱신한다.
		then(linker).should().link(USER_ID, CAMPAIGN_ID, 11L);
		// 등록을 만들지 않았으므로 동기 완결(200)이다.
		assertThat(added.accepted()).isFalse();
		assertThat(added.body().campaignId()).isEqualTo("42");
		V2CampaignContentsResponse.Result result = added.body().results().get(0);
		assertThat(result.contentId()).isEqualTo("ABC");
		assertThat(result.result()).isEqualTo("success");
		assertThat(result.monitoringItemId()).isEqualTo("11");
		assertThat(result.reasonCode()).isNull();
		assertThat(result.reason()).isNull();
		then(registrationService).should(never()).register(anyLong(), anyMap());
	}

	@Test
	void 같은_캠페인_소속이면_duplicate다() {
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, "42", "여름 캠페인", "https://www.instagram.com/reel/ABC/"));

		V2CampaignContentsResponse.Result result =
				service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30).body().results().get(0);

		assertThat(result.result()).isEqualTo("duplicate");
		assertThat(result.reasonCode()).isEqualTo("CAMPAIGN_CONTENT_ALREADY_EXISTS");
		assertThat(result.reason()).isEqualTo("이미 이 캠페인에 추가된 콘텐츠입니다.");
		assertThat(result.monitoringItemId()).isEqualTo("11");
		then(linker).should(never()).link(anyLong(), anyLong(), anyLong());
	}

	@Test
	void 다른_캠페인_소속이면_duplicate고_이동하지_않는다() {
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, "77", "봄 캠페인", "https://www.instagram.com/reel/ABC/"));

		V2CampaignContentsResponse.Result result =
				service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30).body().results().get(0);

		assertThat(result.result()).isEqualTo("duplicate");
		assertThat(result.reasonCode()).isEqualTo("CAMPAIGN_CONTENT_ALREADY_EXISTS");
		// 캠페인 1:1 유지 — 이동은 레거시 PATCH(콘텐츠 수정)의 몫이라 여기서는 손대지 않는다.
		assertThat(result.reason()).contains("봄 캠페인");
		then(linker).should(never()).link(anyLong(), anyLong(), anyLong());
	}

	@Test
	void 게시물_미확정_아이템도_sourceUrl로_찾는다() {
		// collecting(첫 수집 전) 아이템은 post가 null이라 post.url로는 shortcode를 못 뽑는다 —
		// 등록 원문(sourceUrl) 폴백이 없으면 "미존재"로 오판해 중복 아이템을 만든다.
		givenCampaign();
		givenItems(new TrackingItemResponse("11", "url", ItemStatus.COLLECTING, "", "", null, null, null,
				null, null, "https://www.instagram.com/p/XYZ/", "2026-08-01", 30, null, null, null));

		V2CampaignContentsResponse.Result result =
				service.add(USER_ID, CAMPAIGN_ID, List.of("XYZ"), 30).body().results().get(0);

		assertThat(result.result()).isEqualTo("success");
		assertThat(result.monitoringItemId()).isEqualTo("11");
		then(registrationService).should(never()).register(anyLong(), anyMap());
	}

	@Test
	void 동일_shortcode가_여럿이면_활성_최신을_고른다() {
		givenCampaign();
		givenItems(
				item("30", ItemStatus.ENDED, null, null, "https://www.instagram.com/reel/ABC/"),
				item("11", ItemStatus.TRACKING, null, null, "https://www.instagram.com/reel/ABC/"),
				item("9", ItemStatus.TRACKING, null, null, "https://www.instagram.com/reel/ABC/"));
		givenCanceled();

		V2CampaignContentsResponse.Result result =
				service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30).body().results().get(0);

		// 종결(id 30)보다 활성이 우선, 활성끼리는 id 최대(11).
		assertThat(result.monitoringItemId()).isEqualTo("11");
		then(linker).should().link(USER_ID, CAMPAIGN_ID, 11L);
	}

	@Test
	void 취소된_아이템은_기존_아이템으로_보지_않는다() {
		// 취소 아이템(status는 ended/not_uploaded로 유도)에 캠페인만 붙이면 "success인데 수집은 영영
		// 없음"이 된다 — 직접 등록(§6-4 activeItemId)과 같은 기준으로 재등록 경로에 태운다.
		givenCampaign();
		givenItems(item("11", ItemStatus.ENDED, null, null, "https://www.instagram.com/reel/ABC/"));
		givenCanceled(11L);
		givenTagged(taggedPost("ABC", "https://www.instagram.com/reel/ABC/"));
		given(registrationService.register(eq(USER_ID), anyMap())).willReturn(
				new MonitoringRegistrationResponse("500", List.of(
						TrackingItemResponse.pendingPost(12L, "https://www.instagram.com/reel/ABC/", CAMPAIGN_ID,
								"여름 캠페인", LocalDate.parse("2026-08-07"), 30,
								OffsetDateTime.parse("2026-08-07T00:00:00Z"))),
						null));

		V2CampaignContentsResponse.Result result =
				service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30).body().results().get(0);

		assertThat(result.result()).isEqualTo("pending");
		assertThat(result.monitoringItemId()).isEqualTo("12");
		then(linker).should(never()).link(anyLong(), anyLong(), anyLong());
	}

	@Test
	void 취소_아닌_자연_종결_아이템은_그대로_연결한다() {
		// 자연 종결(ended)은 이미 수집이 끝난 콘텐츠다 — 캠페인에 담는 건 "과거 콘텐츠 묶기"로
		// 정당하고, 재등록(재수집 비용)을 태우지 않는다. 취소 제외와 구분되는 결정이라 명시 고정.
		givenCampaign();
		givenItems(item("11", ItemStatus.ENDED, null, null, "https://www.instagram.com/reel/ABC/"));
		givenCanceled();

		V2CampaignContentsResponse.Result result =
				service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30).body().results().get(0);

		assertThat(result.result()).isEqualTo("success");
		assertThat(result.monitoringItemId()).isEqualTo("11");
		then(linker).should().link(USER_ID, CAMPAIGN_ID, 11L);
		then(registrationService).should(never()).register(anyLong(), anyMap());
	}

	@Test
	void 전원_활성이면_취소_조회를_생략한다() {
		// 취소 여부는 종결 status 후보가 있을 때만 궁금하다 — 활성뿐인 평시 경로에 DB 왕복을 더하지 않는다.
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, null, null, "https://www.instagram.com/reel/ABC/"));

		service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30);

		then(itemRepository).should(never()).findByUser(anyLong());
	}

	@Test
	void 같은_contentId가_두_번_오면_두_번째는_duplicate다() {
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, null, null, "https://www.instagram.com/reel/ABC/"));

		List<V2CampaignContentsResponse.Result> results =
				service.add(USER_ID, CAMPAIGN_ID, List.of("ABC", "ABC"), 30).body().results();

		assertThat(results).hasSize(2);
		assertThat(results.get(0).result()).isEqualTo("success");
		assertThat(results.get(1).result()).isEqualTo("duplicate");
		assertThat(results.get(1).reasonCode()).isEqualTo("CAMPAIGN_CONTENT_ALREADY_EXISTS");
		// 같은 아이템에 두 번 연결하지 않는다.
		then(linker).should().link(anyLong(), anyLong(), anyLong());
	}

	@Test
	void 반복된_contentId는_앞_판정이_실패면_실패를_잇는다() {
		// 반복분을 무조건 "이미 이 캠페인에 있음"으로 접으면 앞 항목이 failed였을 때 거짓말이 된다.
		givenCampaign();
		givenItems();
		givenTagged();

		List<V2CampaignContentsResponse.Result> results =
				service.add(USER_ID, CAMPAIGN_ID, List.of("NOPE", "NOPE"), 30).body().results();

		assertThat(results).hasSize(2);
		assertThat(results.get(0).result()).isEqualTo("failed");
		assertThat(results.get(1).result()).isEqualTo("failed");
		assertThat(results.get(1).reasonCode()).isEqualTo("NOT_FOUND");
	}

	// ---------- 추가: 미존재 → 레거시 등록 위임 ----------

	@Test
	void 미존재_콘텐츠는_레거시_등록으로_아이템을_만든다() {
		givenCampaign();
		givenItems();
		givenTagged(taggedPost("ABC", "https://www.instagram.com/reel/ABC/"));
		given(registrationService.register(eq(USER_ID), anyMap())).willReturn(
				new MonitoringRegistrationResponse("500", List.of(
						TrackingItemResponse.pendingPost(11L, "https://www.instagram.com/reel/ABC/", CAMPAIGN_ID,
								"여름 캠페인", LocalDate.parse("2026-08-07"), 30,
								OffsetDateTime.parse("2026-08-07T00:00:00Z"))),
						null));

		V2CampaignContentService.Added added = service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30);

		then(registrationService).should().register(eq(USER_ID), bodyCaptor.capture());
		Map<String, Object> body = bodyCaptor.getValue();
		assertThat(body).containsEntry("posts", List.of("https://www.instagram.com/reel/ABC/"))
				.containsEntry("trackingDays", 30)
				.containsEntry("campaignId", "42");
		// 아이템 생성이 섞였으므로 202다.
		assertThat(added.accepted()).isTrue();
		V2CampaignContentsResponse.Result result = added.body().results().get(0);
		assertThat(result.result()).isEqualTo("pending");
		assertThat(result.monitoringItemId()).isEqualTo("11");
		then(linker).should(never()).link(anyLong(), anyLong(), anyLong());
	}

	@Test
	void 위임했지만_아이템이_안_만들어지면_200이다() {
		// 레거시가 전건을 duplicate/실패로 접어 새 아이템이 0개면 폴링할 등록 대상이 없다 — 202가 아니다.
		givenCampaign();
		givenItems();
		givenTagged(taggedPost("ABC", "https://www.instagram.com/reel/ABC/"));
		given(registrationService.register(eq(USER_ID), anyMap())).willReturn(
				new MonitoringRegistrationResponse("500", List.of(), null));

		V2CampaignContentService.Added added = service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30);

		assertThat(added.accepted()).isFalse();
	}

	@Test
	void contentId는_trim해서_판정한다() {
		// FE가 공백 섞인 id를 보내면 조용한 NOT_FOUND가 된다 — 정규화 한 번으로 막는다.
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, null, null, "https://www.instagram.com/reel/ABC/"));

		V2CampaignContentsResponse.Result result =
				service.add(USER_ID, CAMPAIGN_ID, List.of(" ABC "), 30).body().results().get(0);

		assertThat(result.contentId()).isEqualTo("ABC");
		assertThat(result.result()).isEqualTo("success");
	}

	@Test
	void 어디에도_없는_콘텐츠는_failed다() {
		givenCampaign();
		givenItems();
		givenTagged();

		V2CampaignContentService.Added added = service.add(USER_ID, CAMPAIGN_ID, List.of("NOPE"), 30);

		V2CampaignContentsResponse.Result result = added.body().results().get(0);
		assertThat(result.result()).isEqualTo("failed");
		assertThat(result.reasonCode()).isEqualTo("NOT_FOUND");
		assertThat(result.reason()).isEqualTo("게시물을 찾을 수 없습니다.");
		assertThat(result.monitoringItemId()).isNull();
		assertThat(added.accepted()).isFalse();
		then(registrationService).should(never()).register(anyLong(), anyMap());
	}

	@Test
	void 브랜드_연결이_없으면_tagged_조회_없이_failed다() {
		givenCampaign();
		givenItems();
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of());

		V2CampaignContentsResponse.Result result =
				service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30).body().results().get(0);

		assertThat(result.result()).isEqualTo("failed");
		then(brandPostAssembler).should(never())
				.assembleBrandPosts(anyLong(), any(), anyBoolean(), any(), anyBoolean(), any());
	}

	@Test
	void 부분_성공이_섞여도_entry_단위로_돌려준다() {
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, null, null, "https://www.instagram.com/reel/ABC/"),
				item("12", ItemStatus.TRACKING, "42", "여름 캠페인", "https://www.instagram.com/p/DEF/"));
		givenTagged();

		List<V2CampaignContentsResponse.Result> results =
				service.add(USER_ID, CAMPAIGN_ID, List.of("ABC", "DEF", "GHI"), 30).body().results();

		assertThat(results).hasSize(3);
		assertThat(results.get(0).result()).isEqualTo("success");
		assertThat(results.get(1).result()).isEqualTo("duplicate");
		assertThat(results.get(2).result()).isEqualTo("failed");
	}

	// ---------- 추가: 경쟁사 구독 차단(08-12) ----------

	@Test
	void 경쟁사_태그_게시물은_건별_실패고_같은_요청의_정상_콘텐츠는_성공한다() {
		// 100건 배치의 부분 성공이 이 API의 계약이다 — 경쟁사 1건 때문에 나머지 99건을 400으로
		// 떨구지 않는다(요청 형태 문제만 400).
		givenCampaign();
		givenItems();
		givenLinks(link(1L, BRAND_ID, "own"), link(2L, RIVAL_BRAND_ID, "competitor"));
		givenTaggedPosts(BRAND_ID, taggedPost("OWNPOST1", "https://www.instagram.com/reel/OWNPOST1/", BRAND_ID));
		givenTaggedPosts(RIVAL_BRAND_ID,
				taggedPost("RIVALPOST1", "https://www.instagram.com/p/RIVALPOST1/", RIVAL_BRAND_ID));
		given(registrationService.register(eq(USER_ID), anyMap())).willReturn(
				new MonitoringRegistrationResponse("500", List.of(
						TrackingItemResponse.pendingPost(11L, "https://www.instagram.com/reel/OWNPOST1/",
								CAMPAIGN_ID, "여름 캠페인", LocalDate.parse("2026-08-07"), 30,
								OffsetDateTime.parse("2026-08-07T00:00:00Z"))),
						null));

		List<V2CampaignContentsResponse.Result> results =
				service.add(USER_ID, CAMPAIGN_ID, List.of("OWNPOST1", "RIVALPOST1"), 30).body().results();

		assertThat(results.get(0).contentId()).isEqualTo("OWNPOST1");
		assertThat(results.get(0).result()).isEqualTo("pending");
		assertThat(results.get(1).contentId()).isEqualTo("RIVALPOST1");
		assertThat(results.get(1).result()).isEqualTo("failed");
		assertThat(results.get(1).reasonCode()).isEqualTo("COMPETITOR_CONTENT_NOT_ALLOWED");
		assertThat(results.get(1).reason()).isEqualTo("경쟁사 계정의 게시물은 캠페인에 연결할 수 없어요.");
		assertThat(results.get(1).monitoringItemId()).isNull();
		// 경쟁사 게시물은 위임 목록에 실리지 않는다(아이템이 만들어지면 차단이 무의미하다).
		then(registrationService).should().register(eq(USER_ID), bodyCaptor.capture());
		assertThat(bodyCaptor.getValue())
				.containsEntry("posts", List.of("https://www.instagram.com/reel/OWNPOST1/"));
	}

	@Test
	void 경쟁사_게시물은_NOT_FOUND가_아니다() {
		// 태그 맵에서 경쟁사를 빼버리면 NOT_FOUND로 떨어져 "보이는 게시물을 없다"고 말하게 된다 —
		// 나중에 수집 누락을 의심하며 디버깅하는 사람을 속인다. 담아 두고 전용 사유로 거절한다.
		givenCampaign();
		givenItems();
		givenLinks(link(2L, RIVAL_BRAND_ID, "competitor"));
		givenTaggedPosts(RIVAL_BRAND_ID,
				taggedPost("RIVALPOST1", "https://www.instagram.com/p/RIVALPOST1/", RIVAL_BRAND_ID));

		V2CampaignContentService.Added added =
				service.add(USER_ID, CAMPAIGN_ID, List.of("RIVALPOST1"), 30);

		V2CampaignContentsResponse.Result result = added.body().results().get(0);
		assertThat(result.result()).isEqualTo("failed");
		assertThat(result.reasonCode()).isNotEqualTo("NOT_FOUND");
		assertThat(result.reasonCode()).isEqualTo("COMPETITOR_CONTENT_NOT_ALLOWED");
		assertThat(added.accepted()).isFalse();
		then(registrationService).should(never()).register(anyLong(), anyMap());
	}

	@Test
	void 내_브랜드와_경쟁사에_동시_태그된_게시물은_허용한다() {
		// 귀속은 이제 표시가 아니라 권한이다 — 내 게시물이 경쟁사에도 태그돼 있고 경쟁사 연결이
		// 더 오래됐다는 이유만으로 내 캠페인에 못 담기면, 연결 순서가 권한을 정하게 된다.
		givenCampaign();
		givenItems();
		givenLinks(link(2L, RIVAL_BRAND_ID, "competitor"), link(1L, BRAND_ID, "own"));
		givenTaggedPosts(RIVAL_BRAND_ID, taggedPost("ABC", "https://www.instagram.com/reel/ABC/", RIVAL_BRAND_ID));
		givenTaggedPosts(BRAND_ID, taggedPost("ABC", "https://www.instagram.com/reel/ABC/", BRAND_ID));
		given(registrationService.register(eq(USER_ID), anyMap())).willReturn(
				new MonitoringRegistrationResponse("500", List.of(
						TrackingItemResponse.pendingPost(11L, "https://www.instagram.com/reel/ABC/", CAMPAIGN_ID,
								"여름 캠페인", LocalDate.parse("2026-08-07"), 30,
								OffsetDateTime.parse("2026-08-07T00:00:00Z"))),
						null));

		V2CampaignContentsResponse.Result result =
				service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30).body().results().get(0);

		assertThat(result.result()).isEqualTo("pending");
		assertThat(result.reasonCode()).isNull();
		assertThat(result.monitoringItemId()).isEqualTo("11");
	}

	// ---------- 검증 ----------

	@Test
	void 남의_캠페인은_404다() {
		given(campaignRepository.findByIdAndUser(CAMPAIGN_ID, USER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 30))
				.isInstanceOf(V1ApiException.class)
				.hasMessage("캠페인을 찾을 수 없습니다.");
		// 소유 검증 전에 전량 조립을 돌리지 않는다(레거시·monitoring DB 왕복 방지).
		then(trackingItemAssembler).should(never()).assembleList(anyLong());
	}

	@Test
	void 콘텐츠가_비면_400이다() {
		givenCampaign();

		assertThatThrownBy(() -> service.add(USER_ID, CAMPAIGN_ID, List.of(), 30))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void 기간이_범위_밖이면_400이다() {
		givenCampaign();

		assertThatThrownBy(() -> service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), 91))
				.isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> service.add(USER_ID, CAMPAIGN_ID, List.of("ABC"), null))
				.isInstanceOf(V1ApiException.class);
	}

	// ---------- 제거 ----------

	@Test
	void 제거는_campaign_연결만_끊는다() {
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, "42", "여름 캠페인", "https://www.instagram.com/reel/ABC/"));

		service.remove(USER_ID, CAMPAIGN_ID, "ABC");

		// 모니터링은 계속된다 — 캠페인 연결만 해제한다(취소 호출 없음).
		then(linker).should().unlink(USER_ID, 11L);
	}

	@Test
	void 제거는_같은_shortcode의_소속_전건을_해제한다() {
		// 같은 shortcode 아이템이 캠페인에 여럿이면(종결 후 재등록 등) 대표 1건만 끊으면 성과
		// 대시보드에 콘텐츠가 그대로 남는다 — 204를 줬으면 전건이 빠져야 한다.
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, "42", "여름 캠페인", "https://www.instagram.com/reel/ABC/"),
				item("30", ItemStatus.ENDED, "42", "여름 캠페인", "https://www.instagram.com/reel/ABC/"));

		service.remove(USER_ID, CAMPAIGN_ID, "ABC");

		then(linker).should().unlink(USER_ID, 11L);
		then(linker).should().unlink(USER_ID, 30L);
	}

	@Test
	void 다른_캠페인_소속_콘텐츠_제거는_404다() {
		givenCampaign();
		givenItems(item("11", ItemStatus.TRACKING, "77", "봄 캠페인", "https://www.instagram.com/reel/ABC/"));

		assertThatThrownBy(() -> service.remove(USER_ID, CAMPAIGN_ID, "ABC"))
				.isInstanceOf(V1ApiException.class);
		then(linker).should(never()).unlink(anyLong(), anyLong());
	}

	@Test
	void 남의_캠페인에서_제거하면_404다() {
		given(campaignRepository.findByIdAndUser(CAMPAIGN_ID, USER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.remove(USER_ID, CAMPAIGN_ID, "ABC"))
				.isInstanceOf(V1ApiException.class)
				.hasMessage("캠페인을 찾을 수 없습니다.");
	}

	// ---------- 픽스처 ----------

	private void givenCampaign() {
		given(campaignRepository.findByIdAndUser(CAMPAIGN_ID, USER_ID)).willReturn(Optional.of(
				new CampaignRow(CAMPAIGN_ID, USER_ID, "여름 캠페인", null, null, null, null, null, null,
						OffsetDateTime.parse("2026-08-01T00:00:00Z"))));
	}

	private void givenItems(TrackingItemResponse... items) {
		given(trackingItemAssembler.assembleList(USER_ID)).willReturn(
				new TrackingItemAssembler.AssembledList(Arrays.asList(items), null,
						LocalDate.parse("2026-08-07")));
	}

	/** 취소된 아이템 행 — 서비스는 canceled_at != null만 본다(나머지 필드는 판정에 무관). */
	private void givenCanceled(Long... canceledIds) {
		given(itemRepository.findByUser(USER_ID)).willReturn(Arrays.stream(canceledIds)
				.map(id -> new MonitoringItemRow(id, USER_ID, "url", null, null, null, "ABC", null, null,
						30, LocalDate.parse("2026-08-01"), OffsetDateTime.parse("2026-08-06T00:00:00Z"),
						"tracking", OffsetDateTime.parse("2026-08-01T00:00:00Z")))
				.toList());
	}

	private void givenTagged(BrandPostResponse... posts) {
		givenLinks(link(1L, BRAND_ID, "own"));
		givenTaggedPosts(BRAND_ID, posts);
	}

	/** 활성 브랜드 연결 목록 — 인자 순서가 곧 조회 순서다(귀속 우선순위 검증에 쓴다). */
	private void givenLinks(BrandLinkRow... links) {
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(links));
	}

	/**
	 * 브랜드 1개의 태그 목록 — 계정 행 조회까지 함께 물린다. viewerAccountType은 이 테스트 파일이
	 * 쓰는 두 브랜드 상수({@code BRAND_ID}=own, {@code RIVAL_BRAND_ID}=competitor)로 고정 유도한다 —
	 * 모든 호출부가 실제로 그 짝({@link #givenLinks}의 link accountType)과 일치한다.
	 */
	private void givenTaggedPosts(long brandId, BrandPostResponse... posts) {
		BrandAccountRow account = account(brandId);
		given(brandReadRepository.findAccount(brandId)).willReturn(Optional.of(account));
		String viewerAccountType = brandId == RIVAL_BRAND_ID ? "competitor" : "own";
		given(brandPostAssembler.assembleBrandPosts(USER_ID, account, true, BrandPostScope.ALL, false,
				viewerAccountType))
				.willReturn(List.of(posts));
	}

	/** 브랜드 연결 1건 — 이 테스트가 보는 필드는 brandId·accountType뿐이다. */
	private static BrandLinkRow link(long linkId, long brandId, String accountType) {
		return new BrandLinkRow(linkId, USER_ID, brandId, "brand" + brandId, accountType, 12, null, null, null);
	}

	/** record라 값이 같으면 동등하다 — 스텁 매칭에 같은 인스턴스를 들고 다닐 필요가 없다. */
	private static BrandAccountRow account(long brandId) {
		return new BrandAccountRow(brandId, "brand", null, null, null, null, null,
				null, null, null, null, null, null, null, null, "ACTIVE", null,
				12, null, false, null);
	}

	/** 레거시 추적 아이템 — 이 테스트가 보는 필드는 id·status·campaign·post.url·sourceUrl뿐이다. */
	private static TrackingItemResponse item(String id, String status, String campaignId, String campaignName,
			String postUrl) {
		TrackingItemResponse.TrackedPostResponse post = new TrackingItemResponse.TrackedPostResponse(
				postUrl, "reels", "2026-08-05", "", List.of(), null, null, List.of(), List.of());
		return new TrackingItemResponse(id, "url", status, "handle", "handle", null, null, null,
				campaignId, campaignName, postUrl, "2026-08-01", 30, null, post, null);
	}

	/** tagged 게시물 — 이 테스트가 보는 필드는 shortcode·postUrl뿐이다. */
	private static BrandPostResponse taggedPost(String shortcode, String postUrl) {
		return taggedPost(shortcode, postUrl, BRAND_ID);
	}

	private static BrandPostResponse taggedPost(String shortcode, String postUrl, long brandId) {
		return new BrandPostResponse(shortcode, String.valueOf(brandId), "tagged", null, postUrl, shortcode,
				"reels", "2026-08-05T00:00:00+09:00", null, null, null, null, null, null, null, null,
				false, null, "unknown", null, "tracking", null, null, null, List.of(), null, false, 0,
				List.of(), List.of(), null, null,
				null, List.of(), List.of(), List.of(), false, null);
	}
}
