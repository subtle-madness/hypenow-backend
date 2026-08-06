package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.monitoring.RegistrationRow;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.MonitoringRegistrationResponse;
import com.celfit.was.v1.monitoring.V1MonitoringRegistrationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 직접 등록(스펙 §6-4) 단위 검증 — 브랜드 목록에 이미 있는 게시물은 레거시를 건드리지 않고,
 * 신규는 레거시 등록 파이프라인에 위임한 뒤 그 결과로 direct 매핑을 만든다는 계약을 고정한다.
 * 레거시(V1MonitoringRegistrationService·RegistrationRepository)는 mock이다 — 이 태스크는
 * 레거시를 한 줄도 바꾸지 않고 호출만 하기 때문에 위임 셰이프(입력 순서 = entry seq)가 검증 대상이다.
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
	MonitoringItemRepository itemRepository;
	@Mock
	V1MonitoringRegistrationService legacyRegistration;
	@Mock
	RegistrationRepository registrationRepository;

	@InjectMocks
	V1BrandDirectPostService service;

	@Captor
	ArgumentCaptor<Map<String, Object>> bodyCaptor;

	// ---------- 위임 제외(브랜드 목록 중복) ----------

	@Test
	void 이미_태그_게시물로_있으면_레거시_위임_없이_duplicate다() {
		ownedBrand();
		tagged("ABC");
		directMappings();

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_ABC), 30, null);

		assertThat(response.registrationId()).isNull();
		assertThat(response.entries()).hasSize(1);
		assertThat(response.entries().get(0).result()).isEqualTo("duplicate");
		assertThat(response.entries().get(0).brandPostId()).isEqualTo("ABC");
		assertThat(response.entries().get(0).reasonCode()).isEqualTo("duplicate");
		then(legacyRegistration).should(never()).register(anyLong(), any());
	}

	@Test
	void 이미_직접_등록된_게시물도_duplicate다() {
		ownedBrand();
		tagged();
		directMappings("ABC");

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_ABC), 30, null);

		assertThat(response.entries().get(0).result()).isEqualTo("duplicate");
		then(legacyRegistration).should(never()).register(anyLong(), any());
		then(directPostRepository).should(never()).upsert(anyLong(), anyLong(), anyString(), anyLong());
	}

	// ---------- 신규 위임 ----------

	@Test
	void 신규_URL은_레거시_등록에_위임하고_매핑을_만든다() {
		ownedBrand();
		tagged();
		directMappings();
		given(legacyRegistration.register(eq(7L), anyMap()))
				.willReturn(new MonitoringRegistrationResponse("55", List.of(), null));
		given(registrationRepository.findById(55L))
				.willReturn(Optional.of(registration(7L, entry(0, URL_DEF, "pending", null, null, 301L))));

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_DEF), 30, null);

		then(directPostRepository).should().upsert(7L, 100L, "DEF", 301L);
		assertThat(response.registrationId()).isEqualTo("55");
		assertThat(response.entries()).hasSize(1);
		assertThat(response.entries().get(0).result()).isEqualTo("pending");
		assertThat(response.entries().get(0).brandPostId()).isEqualTo("DEF");
		assertThat(response.entries().get(0).monitoringItemId()).isEqualTo("301");
	}

	@Test
	void 위임_본문은_레거시_계약대로_posts_trackingDays_campaignId다() {
		ownedBrand();
		tagged();
		directMappings();
		given(legacyRegistration.register(eq(7L), anyMap()))
				.willReturn(new MonitoringRegistrationResponse("55", List.of(), null));
		given(registrationRepository.findById(55L))
				.willReturn(Optional.of(registration(7L, entry(0, URL_DEF, "pending", null, null, 301L))));

		service.register(7L, 100L, List.of(URL_DEF), 30, "9");

		then(legacyRegistration).should().register(eq(7L), bodyCaptor.capture());
		Map<String, Object> body = bodyCaptor.getValue();
		assertThat(body.get("posts")).isEqualTo(List.of(URL_DEF));
		assertThat(body.get("trackingDays")).isEqualTo(30);
		assertThat(body.get("campaignId")).isEqualTo("9");
		// accounts는 아예 넣지 않는다 — entry seq가 posts 입력 순서와 1:1이어야 매칭이 성립한다.
		assertThat(body).doesNotContainKey("accounts");
	}

	@Test
	void 이미_레거시_추적_중이면_매핑만_추가하고_success다() {
		ownedBrand();
		tagged();
		directMappings();
		given(legacyRegistration.register(eq(7L), anyMap()))
				.willReturn(new MonitoringRegistrationResponse("55", List.of(), null));
		// 레거시는 이미 추적 중인 입력을 duplicate로 확정하고 item_id를 남기지 않는다 —
		// 매핑에 필요한 아이템 id는 우리가 다시 조회해야 한다.
		given(registrationRepository.findById(55L)).willReturn(Optional.of(registration(7L,
				entry(0, URL_GHI, "duplicate", "duplicate", "이미 모니터링 중인 대상이에요.", null))));
		given(itemRepository.findActiveByInput(7L, "url", "GHI")).willReturn(List.of(item(900L)));

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_GHI), 30, null);

		then(directPostRepository).should().upsert(7L, 100L, "GHI", 900L);
		assertThat(response.entries().get(0).result()).isEqualTo("success");
		assertThat(response.entries().get(0).monitoringItemId()).isEqualTo("900");
		assertThat(response.entries().get(0).reasonCode()).isNull();
	}

	@Test
	void 레거시_아이템을_못_찾은_duplicate는_매핑_없이_duplicate로_남는다() {
		ownedBrand();
		tagged();
		directMappings();
		given(legacyRegistration.register(eq(7L), anyMap()))
				.willReturn(new MonitoringRegistrationResponse("55", List.of(), null));
		given(registrationRepository.findById(55L)).willReturn(Optional.of(registration(7L,
				entry(0, URL_GHI, "duplicate", "duplicate", "이미 모니터링 중인 대상이에요.", null))));
		given(itemRepository.findActiveByInput(7L, "url", "GHI")).willReturn(List.of());

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of(URL_GHI), 30, null);

		then(directPostRepository).should(never()).upsert(anyLong(), anyLong(), anyString(), anyLong());
		assertThat(response.entries().get(0).result()).isEqualTo("duplicate");
		assertThat(response.entries().get(0).monitoringItemId()).isNull();
	}

	// ---------- 부분 성공·순서 ----------

	@Test
	void 잘못된_링크는_레거시_reasonCode로_failed고_위임되지_않는다() {
		ownedBrand();
		tagged();
		directMappings();

		BrandDirectRegistrationResponse response = service.register(7L, 100L, List.of("도와줘"), 30, null);

		assertThat(response.entries().get(0).result()).isEqualTo("failed");
		assertThat(response.entries().get(0).reasonCode()).isEqualTo("invalid_format");
		assertThat(response.entries().get(0).brandPostId()).isNull();
		then(legacyRegistration).should(never()).register(anyLong(), any());
	}

	@Test
	void 부분_성공은_입력_순서를_보존하고_위임분만_seq로_매칭한다() {
		ownedBrand();
		tagged("ABC");
		directMappings();
		given(legacyRegistration.register(eq(7L), anyMap()))
				.willReturn(new MonitoringRegistrationResponse("55", List.of(), null));
		// 위임 목록은 [DEF, GHI] — seq 0·1이 그대로 대응한다.
		given(registrationRepository.findById(55L)).willReturn(Optional.of(registration(7L,
				entry(0, URL_DEF, "pending", null, null, 301L),
				entry(1, URL_GHI, "failed", "not_found", "게시물을 찾을 수 없어요.", null))));

		BrandDirectRegistrationResponse response =
				service.register(7L, 100L, List.of("도와줘", URL_ABC, URL_DEF, URL_GHI), 30, null);

		assertThat(response.entries()).extracting(BrandDirectRegistrationResponse.Entry::input)
				.containsExactly("도와줘", URL_ABC, URL_DEF, URL_GHI);
		assertThat(response.entries()).extracting(BrandDirectRegistrationResponse.Entry::result)
				.containsExactly("failed", "duplicate", "pending", "failed");
		then(legacyRegistration).should().register(eq(7L), bodyCaptor.capture());
		assertThat(bodyCaptor.getValue().get("posts")).isEqualTo(List.of(URL_DEF, URL_GHI));
		then(directPostRepository).should().upsert(7L, 100L, "DEF", 301L);
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
	void 등록_상태_조회는_레거시_entry를_같은_셰이프로_재조립한다() {
		given(registrationRepository.findById(55L)).willReturn(Optional.of(registration(7L,
				entry(0, URL_DEF, "success", null, null, 301L),
				entry(1, "도와줘", "failed", "invalid_format", "링크 형식이 올바르지 않아요.", null))));

		BrandDirectRegistrationResponse response = service.get(7L, "55");

		assertThat(response.registrationId()).isEqualTo("55");
		assertThat(response.entries()).hasSize(2);
		assertThat(response.entries().get(0).brandPostId()).isEqualTo("DEF");
		assertThat(response.entries().get(0).monitoringItemId()).isEqualTo("301");
		assertThat(response.entries().get(1).brandPostId()).isNull();
		assertThat(response.entries().get(1).reasonCode()).isEqualTo("invalid_format");
	}

	@Test
	void 취소된_entry는_계약_4종에_맞춰_failed로_접는다() {
		given(registrationRepository.findById(55L)).willReturn(Optional.of(registration(7L,
				entry(0, URL_DEF, "canceled", "canceled", "등록을 취소했어요.", 301L))));

		BrandDirectRegistrationResponse response = service.get(7L, "55");

		assertThat(response.entries().get(0).result()).isEqualTo("failed");
		assertThat(response.entries().get(0).reasonCode()).isEqualTo("canceled");
	}

	@Test
	void 남의_등록_상태는_존재를_흘리지_않고_404다() {
		given(registrationRepository.findById(55L)).willReturn(Optional.of(registration(8L,
				entry(0, URL_DEF, "pending", null, null, 301L))));

		assertThatThrownBy(() -> service.get(7L, "55")).isInstanceOf(V1ApiException.class);
	}

	@Test
	void 숫자가_아닌_registrationId는_404다() {
		assertThatThrownBy(() -> service.get(7L, "abc")).isInstanceOf(V1ApiException.class);
	}

	// ---------- fixtures ----------

	private void ownedBrand() {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(new BrandLinkRow(
				1L, 7L, 100L, "lizda_official", OffsetDateTime.parse("2026-08-07T00:00:00Z"), null)));
	}

	private void tagged(String... shortCodes) {
		given(brandReadRepository.findTaggedPostsInWindow(eq(100L), any(), anyInt()))
				.willReturn(Arrays.stream(shortCodes)
						.map(code -> new BrandTaggedPostRow(code, "creator", "1",
								OffsetDateTime.parse("2026-08-01T00:00:00Z"),
								OffsetDateTime.parse("2026-08-01T00:00:00Z"), 0L))
						.toList());
	}

	private void directMappings(String... shortCodes) {
		given(directPostRepository.shortCodesByUser(7L)).willReturn(new LinkedHashSet<>(List.of(shortCodes)));
	}

	private static RegistrationRow registration(long userId, RegistrationEntryRow... entries) {
		return new RegistrationRow(55L, userId, OffsetDateTime.parse("2026-08-07T01:00:00Z"), null,
				30, null, null, List.of(entries));
	}

	private static RegistrationEntryRow entry(int seq, String input, String result, String reasonCode,
			String reason, Long itemId) {
		return new RegistrationEntryRow(55L, seq, input, "post", result, reasonCode, reason, null, itemId);
	}

	private static MonitoringItemRow item(long id) {
		return new MonitoringItemRow(id, 7L, "url", UUID.randomUUID(), null, null, "GHI",
				"https://www.instagram.com/p/GHI/", null, 30, LocalDate.of(2026, 8, 1), null, null,
				OffsetDateTime.parse("2026-08-01T00:00:00Z"));
	}
}
