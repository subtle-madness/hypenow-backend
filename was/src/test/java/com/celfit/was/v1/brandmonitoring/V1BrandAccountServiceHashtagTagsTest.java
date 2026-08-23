package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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
