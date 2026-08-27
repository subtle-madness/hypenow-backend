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

	V1BrandAccountService service;

	@BeforeEach
	void setUp() {
		service = new V1BrandAccountService(linkRepository, new BrandLinkTransaction(linkRepository), commandClient,
				brandReadRepository, new BrandAccountAssembler(3), userRepository, hashtagTagRepository);
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Optional.of(link()));
		given(brandReadRepository.findAccount(BRAND_ID)).willReturn(Optional.of(account()));
	}

	// ---------- 링크 생성 시딩(2026-08-27 태그 장부 갭 수정 §4) ----------

	/**
	 * 신규 브랜드 링크를 만들면 그 사용자의 장부에 monitoring 자동 시드와 같은 계정명 유도 태그가
	 * 남아야 한다 — 남지 않으면 해시태그 게시물 격리 필터(내 태그 ∩ 매칭 태그)가 이 사용자에게
	 * 아무것도 통과시키지 못한다(08-27 진단된 갭).
	 */
	@Test
	void 신규_링크_생성은_계정명_유도_태그를_장부에_시딩한다() {
		given(commandClient.registerBrand(USERNAME, null, 12, BrandAccountType.OWN))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(BRAND_ID, USERNAME, 100L, "ACTIVE"));

		service.register(USER_ID, USERNAME, BrandAccountType.OWN, 12);

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of(USERNAME));
	}

	/** 멱등 재-POST(이미 연결된 브랜드)는 시딩하지 않는다 — 사용자가 지운 태그가 되살아나면 안 된다. */
	@Test
	void 멱등_재_POST는_장부를_시딩하지_않는다() {
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(link()));

		service.register(USER_ID, USERNAME, BrandAccountType.OWN, null);

		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt(), anyString());
	}

	/** 계정명이 무효 문자로 시작해 유도 태그가 0개면 원장 호출 자체가 없다(빈 목록 삽입 금지). */
	@Test
	void 유도_태그가_없으면_시딩을_건너뛴다() {
		given(commandClient.registerBrand(".beauty", null, 12, BrandAccountType.OWN))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(BRAND_ID, ".beauty", 100L, "ACTIVE"));

		service.register(USER_ID, ".beauty", BrandAccountType.OWN, 12);

		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
	}

	/**
	 * 시딩 실패 격리 회귀(품질 리뷰 지적) — 링크는 이미 커밋됐으므로 장부 시딩이 던져도 등록 응답은
	 * 그대로 나가야 한다({@code seedLedgerTagsSafely}의 try-catch 격리 대상,
	 * {@code V1BrandAccountsControllerTest.own_link_push_실패는_PATCH_응답에_영향_없다}와 동형).
	 */
	@Test
	void 장부_시딩_실패는_등록_응답에_영향이_없다() {
		given(commandClient.registerBrand(USERNAME, null, 12, BrandAccountType.OWN))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(BRAND_ID, USERNAME, 100L, "ACTIVE"));
		willThrow(new RuntimeException("장부 실패"))
				.given(hashtagTagRepository).addTags(USER_ID, BRAND_ID, List.of(USERNAME));

		BrandAccountResponse response = service.register(USER_ID, USERNAME, BrandAccountType.OWN, 12);

		assertThat(response).isNotNull();
		assertThat(response.id()).isEqualTo(String.valueOf(BRAND_ID));
	}

	// ---------- 조회 ----------

	@Test
	void getHashtagTags는_원장을_그대로_읽고_monitoring을_호출하지_않는다() {
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("리즈다", "lizda")));

		List<String> tags = service.getHashtagTags(USER_ID, BRAND_ID);

		assertThat(tags).containsExactly("리즈다", "lizda");
		then(commandClient).should(never()).getHashtagTags(anyString());
	}

	// ---------- 교체(PUT) — 합집합 계산 ----------

	/**
	 * PUT은 내 태그만 교체한다 — monitoring에는 "다른 유저들의 기존 태그"와 "내 새 태그"의 합집합을
	 * 반영해야, 다른 유저가 의존하는 태그를 내 PUT이 지우지 않는다.
	 */
	@Test
	void putHashtagTags는_다른_유저_태그와의_합집합을_monitoring에_반영한다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);   // 이미 시딩됨
		given(hashtagTagRepository.unionByBrand(BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("공통태그", "내옛태그")));
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("내옛태그")));

		service.putHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		// union = {공통태그, 내옛태그} - {내옛태그} + {새태그} = {공통태그, 새태그}
		then(commandClient).should().putHashtagTags(USERNAME, List.of("공통태그", "새태그"));
		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	/** 원장에 이 브랜드 행이 하나도 없으면(최초 조작) monitoring의 현재 태그를 이 유저에게 시딩한 뒤 진행한다. */
	@Test
	void putHashtagTags는_원장이_비어있으면_monitoring_현재_태그를_먼저_시딩한다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(false);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("레거시태그"));
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("레거시태그"));
		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	@Test
	void putHashtagTags는_원장이_있으면_시딩을_생략한다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(commandClient).should(never()).getHashtagTags(USERNAME);
	}

	/** 입력 정규화(trim → 선행 # 제거 → 소문자 → 중복 제거) — monitoring 정규화 규칙과 동일. */
	@Test
	void putHashtagTags는_입력을_정규화한다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of(" #리즈다 ", "LIZDA", "리즈다"));

		// "#리즈다"·"리즈다"는 정규화 후 같은 값이라 중복 제거된다.
		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of("리즈다", "lizda"));
	}

	@Test
	void putHashtagTags는_tags_null이면_빈_목록으로_교체한다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, null);

		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of());
	}

	// ---------- 추가(POST) ----------

	@Test
	void addHashtagTags는_원장에_추가만_하고_monitoring_ADD로_위임한다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);

		service.addHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(commandClient).should().addHashtagTags(USERNAME, List.of("새태그"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	@Test
	void addHashtagTags는_빈_입력이면_시딩도_monitoring_호출도_없이_그대로_위임한다() {
		service.addHashtagTags(USER_ID, BRAND_ID, List.of());

		then(hashtagTagRepository).should(never()).existsForBrand(anyLong());
		then(commandClient).should().addHashtagTags(USERNAME, List.of());
	}

	// ---------- 단건 삭제 ----------

	/** 다른 유저가 이 태그를 아직 갖고 있으면 monitoring 스윕 대상에서 빼지 않는다(요구사항, 08-19). */
	@Test
	void deleteHashtagTag는_다른_유저가_있으면_monitoring_삭제를_생략한다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.hasOtherUserWithTag(BRAND_ID, "리즈다", USER_ID)).willReturn(true);

		service.deleteHashtagTag(USER_ID, BRAND_ID, "리즈다");

		then(hashtagTagRepository).should().deleteTag(USER_ID, BRAND_ID, "리즈다");
		then(commandClient).should(never()).deleteHashtagTag(anyString(), eq("리즈다"));
	}

	@Test
	void deleteHashtagTag는_다른_유저가_없으면_monitoring에서도_지운다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
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
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
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

	private static BrandLinkRow link() {
		return new BrandLinkRow(1L, USER_ID, BRAND_ID, USERNAME, BrandAccountType.OWN, 12,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null);
	}

	private static BrandAccountRow account() {
		return new BrandAccountRow(BRAND_ID, USERNAME, LocalDate.of(2026, 8, 7),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null,
				30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"), false, null);
	}
}
