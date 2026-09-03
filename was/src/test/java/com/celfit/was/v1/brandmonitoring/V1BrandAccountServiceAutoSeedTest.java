package com.celfit.was.v1.brandmonitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.monitoring.BrandHashtagSeedRepository;
import com.celfit.was.monitoring.BrandHashtagSeedRepository.SeedRow;
import com.celfit.was.monitoring.BrandHashtagTagRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringCommandClient.HashtagSuggestionBody;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 자동 시드 훅(2026-09-03 자동 시드 재설계 §4-2) — was가 유일한 작성자다. 분기 전부를 고정한다:
 * 링크 이미 반영됨 / 백필 미완 / 이미 사용자 태그 있음(SKIP) / 신규 계산 / 기존 시드 복사 /
 * push 실패 격리 / 동시 호출 경합. 훅은 어떤 실패에서도 예외를 밖으로 내지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class V1BrandAccountServiceAutoSeedTest {

	private static final long USER_ID = 7L;
	private static final long BRAND_ID = 100L;
	private static final long LINK_ID = 1L;
	private static final String USERNAME = "dr.piel_official";
	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-03T00:00:00Z");

	@Mock
	BrandLinkRepository linkRepository;
	@Mock
	MonitoringCommandClient commandClient;
	@Mock
	BrandReadRepository brandReadRepository;
	@Mock
	UserRepository userRepository;
	@Mock
	BrandHashtagTagRepository hashtagTagRepository;
	@Mock
	BrandHashtagSeedRepository seedRepository;

	V1BrandAccountService service;

	@BeforeEach
	void setUp() {
		service = new V1BrandAccountService(linkRepository, new BrandLinkTransaction(linkRepository),
				commandClient, brandReadRepository, new BrandAccountAssembler(3), userRepository,
				hashtagTagRepository, seedRepository);
	}

	private static BrandLinkRow link(OffsetDateTime hashtagSeededAt) {
		return new BrandLinkRow(LINK_ID, USER_ID, BRAND_ID, USERNAME, BrandAccountType.OWN, 12,
				NOW, null, hashtagSeededAt);
	}

	/** 이 테스트가 실제로 읽는 필드만 의미 있게 채운다(username·backfillCompletedAt). */
	private static BrandAccountRow account(OffsetDateTime backfillCompletedAt) {
		return new BrandAccountRow(BRAND_ID, USERNAME, LocalDate.of(2026, 9, 2), NOW, NOW,
				backfillCompletedAt, null, 100L, 10L, 5L, "소개", "닥터피엘 Dr.PIEL",
				"https://p", false, null, "ACTIVE", null, 12, NOW, false, null);
	}

	private void stubLink(OffsetDateTime hashtagSeededAt) {
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(Optional.of(link(hashtagSeededAt)));
	}

	private void stubAccount(OffsetDateTime backfillCompletedAt) {
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(account(backfillCompletedAt)));
	}

	// ---------- 게이트 ----------

	@Test
	void 링크가_이미_반영됐으면_아무것도_하지_않는다() {
		stubLink(NOW);

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should(never()).find(anyLong());
		then(commandClient).should(never()).getHashtagSuggestion(anyString());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
	}

	@Test
	void 미소유_브랜드는_아무것도_하지_않는다() {
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Optional.empty());

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should(never()).find(anyLong());
	}

	@Test
	void 초기_백필이_미완이면_계산하지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty());
		stubAccount(null);

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(commandClient).should(never()).getHashtagSuggestion(anyString());
		then(linkRepository).should(never()).markHashtagSeeded(anyLong());
	}

	// ---------- 신규 계산 ----------

	@Test
	void 태그가_없으면_제안을_받아_기록하고_push하고_장부에_넣고_표식을_찍는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty(),
				Optional.of(new SeedRow(BRAND_ID, "AI", "닥터피엘", NOW)));
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(commandClient.getHashtagSuggestion(USERNAME))
				.willReturn(new HashtagSuggestionBody("AI", "닥터피엘", 3, 40));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should().insertIgnore(BRAND_ID, "AI", "닥터피엘");
		then(commandClient).should().addHashtagTags(USERNAME, List.of("닥터피엘"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("닥터피엘"));
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	/** 이미 사용자 관리 태그가 있는 브랜드 — 자동 태그를 얹지 않고 SKIP만 기록한다. */
	@Test
	void monitoring에_태그가_있으면_SKIP을_기록하고_장부는_건드리지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty(),
				Optional.of(new SeedRow(BRAND_ID, "SKIP", null, NOW)));
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("사용자태그"));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should().insertIgnore(BRAND_ID, "SKIP", null);
		then(commandClient).should(never()).getHashtagSuggestion(anyString());
		then(commandClient).should(never()).addHashtagTags(anyString(), any());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		// 이 링크에 대한 판정은 끝났다 — 다음 조회가 같은 결론을 다시 계산하면 안 된다.
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	// ---------- 기존 시드 재사용 ----------

	@Test
	void 시드가_이미_있으면_계산_없이_복사한다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID))
				.willReturn(Optional.of(new SeedRow(BRAND_ID, "FREQ", "닥피", NOW)));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(commandClient).should(never()).getHashtagSuggestion(anyString());
		then(commandClient).should(never()).getHashtagTags(anyString());
		then(brandReadRepository).should(never()).findAccount(anyLong());
		then(commandClient).should().addHashtagTags(USERNAME, List.of("닥피"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("닥피"));
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	@Test
	void SKIP_시드는_장부_삽입_없이_표식만_찍는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID))
				.willReturn(Optional.of(new SeedRow(BRAND_ID, "SKIP", null, NOW)));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(commandClient).should(never()).addHashtagTags(anyString(), any());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	/** 동시 호출 경합 — 내 INSERT가 지면 재조회로 이긴 쪽의 값을 쓴다(계산 결과가 아니라). */
	@Test
	void 동시_호출은_먼저_커밋된_시드를_따른다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty(),
				Optional.of(new SeedRow(BRAND_ID, "FREQ", "먼저값", NOW)));
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(commandClient.getHashtagSuggestion(USERNAME))
				.willReturn(new HashtagSuggestionBody("AI", "내값", 1, 5));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("먼저값"));
	}

	// ---------- 격리 ----------

	/** monitoring push가 실패해도 장부는 진행한다 — 여기서 멈추면 그 사용자 장부가 영구히 빈다. */
	@Test
	void push_실패는_장부_삽입과_표식을_막지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID))
				.willReturn(Optional.of(new SeedRow(BRAND_ID, "FREQ", "닥피", NOW)));
		willThrow(new RuntimeException("monitoring 순단"))
				.given(commandClient).addHashtagTags(USERNAME, List.of("닥피"));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("닥피"));
		then(linkRepository).should().markHashtagSeeded(LINK_ID);
	}

	@Test
	void 제안_조회_실패는_예외를_밖으로_내지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty());
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(commandClient.getHashtagSuggestion(USERNAME)).willThrow(new RuntimeException("monitoring 순단"));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should(never()).insertIgnore(anyLong(), anyString(), any());
		then(linkRepository).should(never()).markHashtagSeeded(anyLong());
	}

	@Test
	void 링크_조회_실패도_예외를_밖으로_내지_않는다() {
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID))
				.willThrow(new RuntimeException("DB 장애"));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(linkRepository).should(never()).markHashtagSeeded(anyLong());
	}

	/** 제안 tag가 비어 오는 건 monitoring 계약 위반이지만, 방어적으로 심지 않는다. */
	@Test
	void 제안_tag가_비면_아무것도_심지_않는다() {
		stubLink(null);
		given(seedRepository.find(BRAND_ID)).willReturn(Optional.empty());
		stubAccount(NOW);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(commandClient.getHashtagSuggestion(USERNAME))
				.willReturn(new HashtagSuggestionBody("FALLBACK", "  ", 0, 0));

		service.ensureAutoSeeded(USER_ID, BRAND_ID);

		then(seedRepository).should(never()).insertIgnore(anyLong(), anyString(), any());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		then(linkRepository).should(never()).markHashtagSeeded(anyLong());
	}

	// ---------- 등록은 훅을 동기로 태우지 않는다(2026-09-03 팔로업) ----------

	/**
	 * 이미 백필이 끝난 브랜드에 두 번째 사용자가 멱등 재-POST로 연결해도, 등록 응답 조립은 훅을
	 * 태우지 않는다({@code getWithoutAutoSeed}) — 태우면 monitoring 제안 계산(AI 호출 포함)이
	 * 등록 요청 처리 안에서 동기로 실행된다. 시드는 등록 직후 FE의 단건 폴링(get)에서 반영된다.
	 */
	@Test
	void 이미_연결된_브랜드_재등록은_자동_시드_훅을_동기로_태우지_않는다() {
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(link(null)));
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Optional.of(link(null)));
		stubAccount(NOW);   // 백필 완료 브랜드 — 훅이 살아 있었다면 여기서 계산이 돌았을 상황.

		service.register(USER_ID, USERNAME, BrandAccountType.OWN, null);

		then(seedRepository).should(never()).find(anyLong());
		then(commandClient).should(never()).getHashtagSuggestion(anyString());
		then(commandClient).should(never()).getHashtagTags(anyString());
	}
}
