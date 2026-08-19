package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandPostRegistrationRepository;
import com.celfit.was.monitoring.BrandPostRegistrationRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandPoolStatusRow;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringApiException;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringUnavailableException;
import com.celfit.was.v1.common.V1ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 직접 등록(2026-08-18 direct 통합 §T9) 단위 검증 — 브랜드 풀(brand_tagged_post) 기준 중복 판정,
 * 전용 등록 저장소·실행기 위임, 취소 3분기를 고정한다. 레거시 위임(V1MonitoringRegistrationService 등)은
 * 완전히 사라졌으므로 이 서비스가 직접 entry를 만들고 실행기를 트리거하는지가 검증 대상이다.
 */
@ExtendWith(MockitoExtension.class)
class V1BrandDirectPostServiceTest {

	private static final String URL_ABC = "https://www.instagram.com/reel/ABC/";
	private static final String URL_DEF = "https://www.instagram.com/reel/DEF/";
	private static final String URL_GHI = "https://www.instagram.com/p/GHI/";

	@Mock
	BrandLinkRepository linkRepository;
	@Mock
	BrandReadRepository brandReadRepository;
	@Mock
	BrandDirectPostRepository directPostRepository;
	@Mock
	BrandPostRegistrationRepository registrationRepository;
	@Mock
	CampaignRepository campaignRepository;
	@Mock
	BrandPostCampaignRepository postCampaignRepository;
	@Mock
	MonitoringCommandClient commandClient;
	@Mock
	BrandDirectRegistrationExecutor executor;

	V1BrandDirectPostService service;

	@BeforeEach
	void setUp() {
		// 중복 게이트의 창 컷 기준 시각 고정 — KST 2026-08-08 21:00.
		service = new V1BrandDirectPostService(linkRepository, brandReadRepository, directPostRepository,
				registrationRepository, campaignRepository, postCampaignRepository, commandClient, executor,
				Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC));
	}

	// ---------- 중복 판정 ----------

	@Test
	void 이미_direct_등록된_게시물은_duplicate다() {
		ownedBrand();
		poolStatus("ABC", true, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
		given(registrationRepository.insert(7L, 100L, null)).willReturn(inserted(55L));

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_ABC), 30, null);

		assertThat(response.entries().get(0).result()).isEqualTo("duplicate");
		assertThat(response.entries().get(0).brandPostId()).isEqualTo("ABC");
		assertThat(response.entries().get(0).reasonCode()).isEqualTo("duplicate");
		assertThat(response.entries().get(0).monitoringItemId()).isNull();
		then(registrationRepository).should().insertEntry(55L, 0, URL_ABC, "ABC", "duplicate", "duplicate",
				"이미 브랜드 목록에 있는 게시물입니다.");
	}

	@Test
	void 링크_창_안의_tagged_게시물도_duplicate다() {
		ownedBrand();
		poolStatus("ABC", false, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
		given(registrationRepository.insert(7L, 100L, null)).willReturn(inserted(55L));

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_ABC), 30, null);

		assertThat(response.entries().get(0).result()).isEqualTo("duplicate");
	}

	@Test
	void 링크_창_밖_tagged_게시물의_직접_등록은_중복이_아니라_위임된다() {
		// 3개월 링크 유저에겐 5개월 전 tagged가 목록·상세 어디에도 없다(2026-08-17 표시 창) — direct로
		// 등록하면 direct_registered_at이 채워지고 direct 행은 창 예외라 그 자리에서 보이기 시작한다
		// (08-17 데드엔드 우회가 대가 없이 해소된다).
		ownedBrand(3);
		poolStatus("ABC", false, false, OffsetDateTime.parse("2026-03-08T00:00:00Z"));
		given(registrationRepository.insert(7L, 100L, null)).willReturn(inserted(55L));

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_ABC), 30, null);

		assertThat(response.entries().get(0).result()).isEqualTo("pending");
		then(executor).should().submit(55L);
	}

	@Test
	void 링크_창_안_5개월_이내_tagged_게시물은_여전히_중복이다() {
		ownedBrand(3);
		poolStatus("ABC", false, false, OffsetDateTime.parse("2026-07-08T00:00:00Z"));
		given(registrationRepository.insert(7L, 100L, null)).willReturn(inserted(55L));

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_ABC), 30, null);

		assertThat(response.entries().get(0).result()).isEqualTo("duplicate");
	}

	@Test
	void 다른_브랜드에_등록된_shortcode를_이_브랜드에_등록하면_duplicate가_아니다() {
		// 판정이 brandId 스코프 쿼리라 다른 브랜드 행은 애초에 후보에 없다(크로스 브랜드 누수 제거).
		ownedBrand();
		given(brandReadRepository.findBrandPoolStatus(eq(100L), any())).willReturn(List.of());
		given(registrationRepository.insert(7L, 100L, null)).willReturn(inserted(55L));

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_ABC), 30, null);

		assertThat(response.entries().get(0).result()).isEqualTo("pending");
	}

	// ---------- 신규 위임 ----------

	@Test
	void 신규_URL은_pending으로_등록되고_실행기가_트리거된다() {
		ownedBrand();
		given(brandReadRepository.findBrandPoolStatus(eq(100L), any())).willReturn(List.of());
		given(registrationRepository.insert(7L, 100L, null)).willReturn(inserted(55L));

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_DEF), 30, null);

		then(registrationRepository).should().insertEntry(55L, 0, URL_DEF, "DEF", "pending", null, null);
		then(executor).should().submit(55L);
		assertThat(response.registrationId()).isEqualTo("55");
		assertThat(response.entries()).hasSize(1);
		assertThat(response.entries().get(0).result()).isEqualTo("pending");
		assertThat(response.entries().get(0).brandPostId()).isEqualTo("DEF");
		assertThat(response.entries().get(0).monitoringItemId()).isNull();
	}

	@Test
	void share_단축_링크는_shortCode_미상으로_pending_등록된다() {
		ownedBrand();
		given(registrationRepository.insert(7L, 100L, null)).willReturn(inserted(55L));
		String shareUrl = "https://www.instagram.com/share/reel/xyz123";

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(shareUrl), 30, null);

		then(registrationRepository).should().insertEntry(55L, 0, shareUrl, null, "pending", null, null);
		assertThat(response.entries().get(0).result()).isEqualTo("pending");
		assertThat(response.entries().get(0).brandPostId()).isNull();
	}

	@Test
	void campaignId가_있으면_등록에_실려_저장된다() {
		ownedBrand();
		given(campaignRepository.findByIdAndUser(9L, 7L))
				.willReturn(Optional.of(new CampaignRow(9L, 7L, "캠페인", null, null, null, null, null, null, null)));
		given(brandReadRepository.findBrandPoolStatus(eq(100L), any())).willReturn(List.of());
		given(registrationRepository.insert(7L, 100L, 9L)).willReturn(inserted(55L));

		service.register(7L, 100L, List.of(URL_DEF), 30, "9");

		then(registrationRepository).should().insert(7L, 100L, 9L);
	}

	@Test
	void 존재하지_않는_캠페인이면_404다() {
		ownedBrand();
		given(campaignRepository.findByIdAndUser(9L, 7L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.register(7L, 100L, List.of(URL_DEF), 30, "9"))
				.isInstanceOf(V1ApiException.class);
		then(registrationRepository).should(never()).insert(anyLong(), anyLong(), any());
	}

	// ---------- 부분 성공·순서 ----------

	@Test
	void 잘못된_링크는_즉시_failed고_위임되지_않는다() {
		ownedBrand();
		given(brandReadRepository.findBrandPoolStatus(eq(100L), any())).willReturn(List.of());
		given(registrationRepository.insert(7L, 100L, null)).willReturn(inserted(55L));

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of("도와줘"), 30, null);

		assertThat(response.entries().get(0).result()).isEqualTo("failed");
		assertThat(response.entries().get(0).reasonCode()).isEqualTo("invalid_format");
		assertThat(response.entries().get(0).brandPostId()).isNull();
	}

	@Test
	void 부분_성공은_입력_순서를_보존한다() {
		ownedBrand();
		poolStatus("ABC", true, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
		given(registrationRepository.insert(7L, 100L, null)).willReturn(inserted(55L));

		BrandDirectRegistrationResponse response =
				service.register(7L, 100L, List.of("도와줘", URL_ABC, URL_DEF), 30, null);

		assertThat(response.entries()).extracting(BrandDirectRegistrationResponse.Entry::input)
				.containsExactly("도와줘", URL_ABC, URL_DEF);
		assertThat(response.entries()).extracting(BrandDirectRegistrationResponse.Entry::result)
				.containsExactly("failed", "duplicate", "pending");
		then(registrationRepository).should().insertEntry(55L, 2, URL_DEF, "DEF", "pending", null, null);
	}

	// ---------- 검증·권한 ----------

	@Test
	void 남의_브랜드_계정이면_403이다() {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.register(7L, 999L, List.of(URL_ABC), 30, null))
				.isInstanceOf(V1ApiException.class)
				.hasMessageContaining("접근 권한");
	}

	@Test
	void 경쟁사_구독_브랜드에는_직접_등록할_수_없다() {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(link(BrandAccountType.COMPETITOR)));

		assertThatThrownBy(() -> service.register(7L, 100L, List.of(URL_ABC), 30, null))
				.isInstanceOf(V1ApiException.class)
				.hasMessage("경쟁사 계정의 게시물은 추적 등록할 수 없어요.")
				.extracting(e -> ((V1ApiException) e).code())
				.isEqualTo("COMPETITOR_ACCOUNT_NOT_ALLOWED");
		then(registrationRepository).should(never()).insert(anyLong(), anyLong(), any());
	}

	@Test
	void 게시물이_비면_400이다() {
		ownedBrand();

		assertThatThrownBy(() -> service.register(7L, 100L, List.of(), 30, null))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void 모니터링_기간이_없으면_400이다() {
		ownedBrand();

		assertThatThrownBy(() -> service.register(7L, 100L, List.of(URL_ABC), null, null))
				.isInstanceOf(V1ApiException.class);
	}

	// ---------- 상태 조회 ----------

	@Test
	void 등록_상태_조회는_저장소_entry를_그대로_옮긴다() {
		given(registrationRepository.findById(55L)).willReturn(Optional.of(registration(7L,
				entry(0, URL_DEF, "success", null, null, "DEF"),
				entry(1, "도와줘", "failed", "invalid_format", "링크 형식이 올바르지 않아요.", null))));

		BrandDirectRegistrationResponse response = service.get(7L, "55");

		assertThat(response.registrationId()).isEqualTo("55");
		assertThat(response.entries()).hasSize(2);
		assertThat(response.entries().get(0).brandPostId()).isEqualTo("DEF");
		assertThat(response.entries().get(0).monitoringItemId()).isNull();
		assertThat(response.entries().get(1).brandPostId()).isNull();
		assertThat(response.entries().get(1).reasonCode()).isEqualTo("invalid_format");
	}

	@Test
	void 남의_등록_상태는_존재를_흘리지_않고_404다() {
		given(registrationRepository.findById(55L)).willReturn(Optional.of(registration(8L,
				entry(0, URL_DEF, "pending", null, null, null))));

		assertThatThrownBy(() -> service.get(7L, "55")).isInstanceOf(V1ApiException.class);
	}

	@Test
	void 숫자가_아닌_registrationId는_404다() {
		assertThatThrownBy(() -> service.get(7L, "abc")).isInstanceOf(V1ApiException.class);
	}

	// ---------- 취소(설계 §2-4, 등록자 한정 취소 08-19 개정) ----------

	@Test
	void 매핑이_있고_direct_registered면_원격_취소_후_원장을_지운다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		poolStatus("DEF", true, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
		ownRegistration(7L, "DEF", 100L);
		// hasOtherRegistrant 미스텁 — Mockito 기본값 false(다른 등록자 없음) = 마지막 등록자 경로.

		service.cancel(7L, "DEF");

		then(commandClient).should().deleteDirectPost(100L, "DEF");
		then(directPostRepository).should().delete(7L, "DEF");
		then(postCampaignRepository).should().deleteByBrandAndShortCodeAndUser(100L, "DEF", 7L);
		then(registrationRepository).should().settlePendingAsSuccessForCancel(100L, "DEF", 7L);
	}

	@Test
	void 원격_취소_실패는_삼키지_않고_전파된다() {
		// 원장만 지우면 monitoring은 계속 수집하는데 화면에서만 사라지는 불일치가 생긴다 —
		// 레거시 cancelLegacyIfPossible의 판단과 반대로, 여기서는 삼키지 않는 게 계약이다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		poolStatus("DEF", true, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
		ownRegistration(7L, "DEF", 100L);
		willThrow(new MonitoringUnavailableException("접속 실패", null))
				.given(commandClient).deleteDirectPost(100L, "DEF");

		assertThatThrownBy(() -> service.cancel(7L, "DEF")).isInstanceOf(MonitoringUnavailableException.class);
		then(directPostRepository).should(never()).delete(anyLong(), anyString());
	}

	@Test
	void 매핑이_없고_tagged_풀에_있으면_400_TAGGED_POST_NOT_CANCELABLE다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		poolStatus("ABC", false, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));

		assertThatThrownBy(() -> service.cancel(7L, "ABC"))
				.isInstanceOfSatisfying(V1ApiException.class,
						e -> assertThat(e.code()).isEqualTo("TAGGED_POST_NOT_CANCELABLE"));
		then(commandClient).should(never()).deleteDirectPost(anyLong(), anyString());
		then(directPostRepository).should(never()).delete(anyLong(), anyString());
	}

	@Test
	void 매핑도_tagged도_없으면_404다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		given(brandReadRepository.findBrandPoolStatus(100L, Set.of("ZZZ"))).willReturn(List.of());

		assertThatThrownBy(() -> service.cancel(7L, "ZZZ")).isInstanceOf(V1ApiException.class);
	}

	/**
	 * 등록자 전용 취소(요구사항, 08-19) — direct-only 게시물을 내가 등록하지 않았으면(원장에 내 행이
	 * 없으면) 존재를 흘리지 않고 404다. 등록자 전용 노출 필터로 이 유저 화면에는 애초에 보이지도
	 * 않는 게시물이라 등록 상태 폴링 get()의 "남의 등록은 404" 관용구와 맞춘다.
	 */
	@Test
	void 등록자가_아니면_direct_전용_게시물_취소는_404다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		poolStatus("XYZ", true, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
		// findByUserAndShortCode 미스텁 — Mockito 기본값 empty(내 원장에 없음) = 등록자 아님.

		assertThatThrownBy(() -> service.cancel(7L, "XYZ"))
				.isInstanceOfSatisfying(V1ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
		then(commandClient).should(never()).deleteDirectPost(anyLong(), anyString());
		then(directPostRepository).should(never()).delete(anyLong(), anyString());
	}

	/**
	 * 등록자 전용 취소(요구사항, 08-19) — 해시태그로도 감지된 겹침 게시물을 내가 등록하지 않았으면
	 * 이 유저 시점엔 "태그 게시물"로 보이므로(BrandPostAssembler.resolveSource와 같은 관점) tagged
	 * 취소 시도와 같은 400 TAGGED_POST_NOT_CANCELABLE이다 — 겹침 여부를 노출하지 않는다.
	 */
	@Test
	void 등록자가_아니면_겹침_게시물_취소도_400_TAGGED_POST_NOT_CANCELABLE다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		poolStatus("GHI", true, true, OffsetDateTime.parse("2026-08-01T00:00:00Z"));

		assertThatThrownBy(() -> service.cancel(7L, "GHI"))
				.isInstanceOfSatisfying(V1ApiException.class,
						e -> assertThat(e.code()).isEqualTo("TAGGED_POST_NOT_CANCELABLE"));
		then(directPostRepository).should(never()).delete(anyLong(), anyString());
	}

	/**
	 * 공동 등록 방어(요구사항, 08-19) — 동시 등록 레이스로 다른 유저가 독립적으로 같은
	 * (brand, shortcode)를 등록했을 수 있다(BrandDirectPostRepository#hasOtherRegistrant 참조). 그
	 * 상태에서 내가 취소해도 브랜드 풀의 direct 표식(monitoring 호출)은 건드리지 않는다 — 내 취소가
	 * 다른 등록자의 화면에서 게시물을 지우면 안 된다. 내 원장·내 캠페인 링크는 그래도 정리한다.
	 */
	@Test
	void 다른_등록자가_남아있으면_원격_해제_없이_내_원장만_지운다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		poolStatus("DEF", true, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
		ownRegistration(7L, "DEF", 100L);
		given(directPostRepository.hasOtherRegistrant(100L, "DEF", 7L)).willReturn(true);

		service.cancel(7L, "DEF");

		then(commandClient).should(never()).deleteDirectPost(anyLong(), anyString());
		then(directPostRepository).should().delete(7L, "DEF");
		then(postCampaignRepository).should().deleteByBrandAndShortCodeAndUser(100L, "DEF", 7L);
	}

	/**
	 * 취소-복구 경합 겹침(요구사항, 08-19) — 등록 명령의 HTTP 응답이 유실돼 monitoring은 실제로
	 * 등록을 완료했는데 {@code app.brand_direct_posts} 원장은 아직 없고 entry가 pending으로 남은
	 * 창에서도, 그 등록을 넣은 본인은 취소할 수 있어야 한다(원장 부재만으로 남의 등록으로 오판하면
	 * 안 된다).
	 */
	@Test
	void 원장이_없어도_내_pending_등록이면_취소할_수_있다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		poolStatus("DEF", true, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
		// findByUserAndShortCode 미스텁 — 원장 아직 없음(HTTP 응답 유실 창).
		given(registrationRepository.hasPendingEntry(100L, "DEF", 7L)).willReturn(true);

		service.cancel(7L, "DEF");

		then(commandClient).should().deleteDirectPost(100L, "DEF");
		then(directPostRepository).should().delete(7L, "DEF");
	}

	/** 원장에 다른 브랜드(brandId 불일치) 행만 있으면 이 브랜드에서는 등록자가 아니다 — 404. */
	@Test
	void 다른_브랜드에_등록한_같은_shortcode는_이_브랜드_취소_권한이_아니다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		poolStatus("DEF", true, false, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
		ownRegistration(7L, "DEF", 999L);   // 다른 브랜드(999L)에 등록한 행

		assertThatThrownBy(() -> service.cancel(7L, "DEF"))
				.isInstanceOfSatisfying(V1ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
		then(directPostRepository).should(never()).delete(anyLong(), anyString());
	}

	// ---------- fixtures ----------

	private void ownedBrand() {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link()));
	}

	/** 표시 창(링크 collection_months)이 다른 연결 — 중복 게이트의 창 판정 검증용. */
	private void ownedBrand(int collectionMonths) {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(link(BrandAccountType.OWN, collectionMonths)));
	}

	private static BrandLinkRow link() {
		return link(BrandAccountType.OWN);
	}

	private static BrandLinkRow link(String accountType) {
		return link(accountType, 12);
	}

	private static BrandLinkRow link(String accountType, int collectionMonths) {
		return new BrandLinkRow(1L, 7L, 100L, "lizda_official", accountType, collectionMonths,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null);
	}

	private void poolStatus(String shortCode, boolean directRegistered, boolean tagDetected, OffsetDateTime takenAt) {
		given(brandReadRepository.findBrandPoolStatus(eq(100L), any()))
				.willReturn(List.of(new BrandPoolStatusRow(shortCode, tagDetected, directRegistered, takenAt)));
	}

	/** 취소 등록자 검증용 — app.brand_direct_posts에 (userId, shortCode) 원장 행이 있는 상태를 스텁. */
	private void ownRegistration(long userId, String shortCode, long brandId) {
		given(directPostRepository.findByUserAndShortCode(userId, shortCode))
				.willReturn(Optional.of(new BrandDirectPostRepository.Row(userId, brandId, shortCode, null)));
	}

	private static BrandPostRegistrationRepository.InsertedRegistration inserted(long id) {
		return new BrandPostRegistrationRepository.InsertedRegistration(id,
				OffsetDateTime.parse("2026-08-07T01:00:00Z"));
	}

	private static BrandPostRegistrationRow registration(long userId,
			com.celfit.was.monitoring.BrandPostRegistrationEntryRow... entries) {
		return new BrandPostRegistrationRow(55L, userId, 100L, null, OffsetDateTime.parse("2026-08-07T01:00:00Z"),
				null, List.of(entries));
	}

	private static com.celfit.was.monitoring.BrandPostRegistrationEntryRow entry(int seq, String input,
			String result, String reasonCode, String reason, String shortCode) {
		return new com.celfit.was.monitoring.BrandPostRegistrationEntryRow(55L, seq, input, shortCode, result,
				reasonCode, reason, null);
	}
}
