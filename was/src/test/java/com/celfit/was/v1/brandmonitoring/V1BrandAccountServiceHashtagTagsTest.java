package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.monitoring.BrandHashtagSeedRepository;
import com.celfit.was.monitoring.BrandHashtagTagRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.MonitoringCommandClient;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 해시태그 태그 관리(2026-08-12) 사용자 스코프 개정(08-19) 판정 로직 단위 검증 — 합집합 계산·최초
 * 시딩·삭제 시맨틱(다른 유저 소유 태그 보호)을 고정한다. 표면 계약(소유권 403·상태 코드)은
 * {@link V1BrandAccountsControllerTest}가 덮는다({@link V1BrandDirectPostServiceTest}와 같은 분리).
 */
@ExtendWith(MockitoExtension.class)
class V1BrandAccountServiceHashtagTagsTest {

	private static final long USER_ID = 7L;
	private static final long BRAND_ID = 100L;
	private static final String USERNAME = "lizda_official";

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
		service = new V1BrandAccountService(linkRepository, new BrandLinkTransaction(linkRepository), commandClient,
				brandReadRepository, new BrandAccountAssembler(3), userRepository, hashtagTagRepository,
				seedRepository);
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Optional.of(link()));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(account()));
	}

	// ---------- 링크 생성 시딩(2026-08-27 태그 장부 갭 수정 §4, 2026-09-03 자동 시드 재설계로 제거) ----------

	/**
	 * 링크 생성은 더 이상 태그를 심지 않는다(2026-09-03 자동 시드 재설계 §4-3) — 계정명 절삭 유도
	 * 규칙이 삭제됐다. 자동 태그는 초기 백필 완료 뒤 첫 조회에서 훅({@code ensureAutoSeeded})이
	 * monitoring 제안을 받아 심는다.
	 */
	@Test
	void 신규_링크_생성은_태그를_시딩하지_않는다() {
		given(commandClient.registerBrand(USERNAME, null, 12, BrandAccountType.OWN))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(BRAND_ID, USERNAME, 100L, "ACTIVE"));

		service.register(USER_ID, USERNAME, BrandAccountType.OWN, 12);

		then(commandClient).should(never()).addHashtagTags(anyString(), any());
		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
	}

	/** 멱등 재-POST(이미 연결된 브랜드)는 시딩하지 않는다 — 사용자가 지운 태그가 되살아나면 안 된다. */
	@Test
	void 멱등_재_POST는_장부를_시딩하지_않는다() {
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(link()));

		service.register(USER_ID, USERNAME, BrandAccountType.OWN, null);

		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt(), anyString());
		then(commandClient).should(never()).addHashtagTags(anyString(), any());
	}

	// ---------- 조회(2026-08-31 태그별 실행 상태 확장) ----------

	/**
	 * 태그 목록 자체의 정본은 여전히 원장이다(08-19 사용자 스코프 개정 그대로) — 하지만 실행
	 * 상태는 원장이 모르는 정보라 monitoring run-state를 호출해 병합한다(태그 목록 조회
	 * {@code getHashtagTags(String)}는 부르지 않는다 — 별개 엔드포인트 getHashtagRunStates).
	 */
	@Test
	void getHashtagTags는_원장_태그에_monitoring_실행_상태를_병합한다() {
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("리즈다", "lizda")));
		OffsetDateTime finishedAt = OffsetDateTime.parse("2026-08-31T10:00:00Z");
		given(commandClient.getHashtagRunStates(USERNAME)).willReturn(List.of(
				new MonitoringCommandClient.TagRunState("리즈다", "done", finishedAt, 3),
				new MonitoringCommandClient.TagRunState("lizda", "collecting", null, null)));

		List<BrandHashtagTagsResponse.TagStatus> tags = service.getHashtagTags(USER_ID, BRAND_ID);

		assertThat(tags).hasSize(2);
		assertThat(tags.get(0).tag()).isEqualTo("리즈다");
		assertThat(tags.get(0).status()).isEqualTo("done");
		assertThat(tags.get(0).lastFoundCount()).isEqualTo(3);
		assertThat(tags.get(1).tag()).isEqualTo("lizda");
		assertThat(tags.get(1).status()).isEqualTo("collecting");
		assertThat(tags.get(1).lastRunAt()).isNull();
		then(commandClient).should(never()).getHashtagTags(anyString());
	}

	/** 원장에는 있는데 monitoring run-state 응답에 없는 태그(드리프트·tombstone)는 collecting/null로 접는다. */
	@Test
	void getHashtagTags는_monitoring에_없는_태그를_collecting으로_접는다() {
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("리즈다")));
		given(commandClient.getHashtagRunStates(USERNAME)).willReturn(List.of());

		List<BrandHashtagTagsResponse.TagStatus> tags = service.getHashtagTags(USER_ID, BRAND_ID);

		assertThat(tags).containsExactly(new BrandHashtagTagsResponse.TagStatus("리즈다", "collecting", null, null));
	}

	/** monitoring 접속 실패는 best-effort — GET을 500으로 떨구지 않고 전체 collecting으로 접는다. */
	@Test
	void getHashtagTags는_monitoring_실패를_격리하고_collecting으로_폴백한다() {
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("리즈다")));
		willThrow(new RuntimeException("monitoring 접속 실패")).given(commandClient).getHashtagRunStates(USERNAME);

		List<BrandHashtagTagsResponse.TagStatus> tags = service.getHashtagTags(USER_ID, BRAND_ID);

		assertThat(tags).containsExactly(new BrandHashtagTagsResponse.TagStatus("리즈다", "collecting", null, null));
	}

	/** 원장이 비었으면 monitoring 호출조차 하지 않는다(불필요한 왕복 방지 — 기존 관용구). */
	@Test
	void getHashtagTags는_원장이_비면_monitoring을_호출하지_않는다() {
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(new LinkedHashSet<>());

		List<BrandHashtagTagsResponse.TagStatus> tags = service.getHashtagTags(USER_ID, BRAND_ID);

		assertThat(tags).isEmpty();
		then(commandClient).should(never()).getHashtagRunStates(anyString());
	}

	// ---------- 교체(PUT) — 합집합 계산 ----------

	/**
	 * PUT은 내 태그만 교체한다 — monitoring에는 "다른 유저들의 기존 태그"와 "내 새 태그"의 합집합을
	 * 반영해야, 다른 유저가 의존하는 태그를 내 PUT이 지우지 않는다.
	 */
	@Test
	void putHashtagTags는_다른_유저_태그와의_합집합을_monitoring에_반영한다() {
		given(hashtagTagRepository.unionByBrand(BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("공통태그", "내옛태그")));
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("내옛태그")));

		service.putHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		// union = {공통태그, 내옛태그} - {내옛태그} + {새태그} = {공통태그, 새태그}
		then(commandClient).should().putHashtagTags(USERNAME, List.of("공통태그", "새태그"));
		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	// ---------- 무주 태그 승계(2026-08-27 diff 개정) ----------

	/**
	 * 백필(Task 3) 이후에는 모든 브랜드에 원장 행이 있으므로 구 "원장이 완전히 비었을 때만 전량
	 * 승계"는 영영 발동하지 않는다 — 그러면 격리 개정 이전부터 monitoring에만 있던 무주 태그가
	 * PUT 합집합에서 누락되고, PUT은 전체 교체 계약이라 monitoring에서 삭제된다.
	 * 승계 대상은 "아무 사용자에게도 귀속되지 않은 태그"뿐이다.
	 */
	@Test
	void addHashtagTags는_무주_태그만_조작_유저에게_승계한다() {
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("무주태그", "남의태그"));
		given(hashtagTagRepository.unionByBrand(BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("남의태그")));

		service.addHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("무주태그"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	/** 이미 누군가에게 귀속된 태그만 있으면 승계는 없다 — 남의 태그를 내 것으로 만들면 안 된다. */
	@Test
	void addHashtagTags는_전부_귀속된_태그면_승계하지_않는다() {
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("남의태그"));
		given(hashtagTagRepository.unionByBrand(BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("남의태그")));

		service.addHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(hashtagTagRepository).should(never()).addTags(USER_ID, BRAND_ID, List.of("남의태그"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	/** monitoring 태그가 아예 없으면 원장 조회조차 하지 않는다(불필요한 왕복 방지). */
	@Test
	void putHashtagTags는_monitoring_태그가_비면_승계하지_않는다() {
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	/** 입력 정규화(trim → 선행 # 제거 → 소문자 → 중복 제거) — monitoring 정규화 규칙과 동일. */
	@Test
	void putHashtagTags는_입력을_정규화한다() {
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of(" #리즈다 ", "LIZDA", "리즈다"));

		// "#리즈다"·"리즈다"는 정규화 후 같은 값이라 중복 제거된다.
		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of("리즈다", "lizda"));
	}

	@Test
	void putHashtagTags는_tags_null이면_빈_목록으로_교체한다() {
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, null);

		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of());
	}

	// ---------- 추가(POST) ----------

	@Test
	void addHashtagTags는_원장에_추가만_하고_monitoring_ADD로_위임한다() {
		service.addHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(commandClient).should().addHashtagTags(USERNAME, List.of("새태그"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	@Test
	void addHashtagTags는_빈_입력이면_시딩도_monitoring_호출도_없이_그대로_위임한다() {
		service.addHashtagTags(USER_ID, BRAND_ID, List.of());

		then(commandClient).should(never()).getHashtagTags(anyString());
		then(commandClient).should().addHashtagTags(USERNAME, List.of());
	}

	// ---------- 단건 삭제 ----------

	/** 다른 유저가 이 태그를 아직 갖고 있으면 monitoring 스윕 대상에서 빼지 않는다(요구사항, 08-19). */
	@Test
	void deleteHashtagTag는_다른_유저가_있으면_monitoring_삭제를_생략한다() {
		given(hashtagTagRepository.hasOtherUserWithTag(BRAND_ID, "리즈다", USER_ID)).willReturn(true);

		service.deleteHashtagTag(USER_ID, BRAND_ID, "리즈다");

		then(hashtagTagRepository).should().deleteTag(USER_ID, BRAND_ID, "리즈다");
		then(commandClient).should(never()).deleteHashtagTag(anyString(), eq("리즈다"));
	}

	@Test
	void deleteHashtagTag는_다른_유저가_없으면_monitoring에서도_지운다() {
		given(hashtagTagRepository.hasOtherUserWithTag(BRAND_ID, "리즈다", USER_ID)).willReturn(false);

		service.deleteHashtagTag(USER_ID, BRAND_ID, "리즈다");

		then(commandClient).should().deleteHashtagTag(USERNAME, "리즈다");
	}

	@Test
	void deleteHashtagTag는_정규화_후_빈_문자열이면_아무것도_하지_않는다() {
		service.deleteHashtagTag(USER_ID, BRAND_ID, "   ");

		then(hashtagTagRepository).should(never()).deleteTag(anyLong(), anyLong(), any());
		then(commandClient).should(never()).deleteHashtagTag(any(), any());
	}

	// ---------- 전체 삭제 ----------

	/**
	 * 전체 삭제는 이 유저의 태그만 지운다 — 다른 유저가 아직 갖고 있는 태그는 monitoring 스윕
	 * 대상에 그대로 남는다. 구 monitoring "브랜드 전체 삭제" API는 더 이상 호출하지 않는다.
	 */
	@Test
	void deleteAllHashtagTags는_다른_유저_소유_태그는_남기고_내_소유만_없는_태그를_monitoring에서_지운다() {
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("공유태그", "내전용태그")));
		given(hashtagTagRepository.hasOtherUserWithTag(BRAND_ID, "공유태그", USER_ID)).willReturn(true);
		given(hashtagTagRepository.hasOtherUserWithTag(BRAND_ID, "내전용태그", USER_ID)).willReturn(false);

		service.deleteAllHashtagTags(USER_ID, BRAND_ID);

		then(hashtagTagRepository).should().deleteAllTags(USER_ID, BRAND_ID);
		then(commandClient).should(never()).deleteHashtagTag(USERNAME, "공유태그");
		then(commandClient).should().deleteHashtagTag(USERNAME, "내전용태그");
		then(commandClient).should(never()).deleteAllHashtagTags(USERNAME);
	}

	// ---------- 픽스처 ----------

	/**
	 * hashtagSeededAt은 non-null로 채운다 — 이 클래스는 태그 관리 판정 로직을 보는 테스트라 자동
	 * 시드 훅(2026-09-03 §4-2)의 관심사가 아니다. null이면 getHashtagTags 호출마다 훅이 돌아
	 * seedRepository·commandClient에 이 테스트가 스텁하지 않은 호출이 섞여든다.
	 */
	private static BrandLinkRow link() {
		return new BrandLinkRow(1L, USER_ID, BRAND_ID, USERNAME, BrandAccountType.OWN, 12,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"));
	}

	private static BrandAccountRow account() {
		return new BrandAccountRow(BRAND_ID, USERNAME, LocalDate.of(2026, 8, 7),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null,
				30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"), false, null);
	}
}
