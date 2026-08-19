package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.celfit.was.monitoring.MonitoringUnavailableException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 회원 탈퇴 훅(스펙 §5-3, 08-07 다계정 개정) 단위 검증 — 삭제 API와 같은 규칙(브랜드별 마지막
 * 사용자만 탈퇴, monitoring 실패는 best-effort)이 brandId 없는 진입점에서 연결 전체에 대해
 * 지켜지는지 고정한다. 실 트랜잭션 경계는 BrandLinkTransaction 몫이라 여기서는 실 객체를 mock
 * 리포지토리 위에 얹는다.
 */
@ExtendWith(MockitoExtension.class)
class V1BrandAccountServiceWithdrawalTest {

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

	private V1BrandAccountService service() {
		return new V1BrandAccountService(linkRepository, new BrandLinkTransaction(linkRepository),
				commandClient, brandReadRepository, new BrandAccountAssembler(3), userRepository,
				hashtagTagRepository);
	}

	private static BrandLinkRow link() {
		return link(100L, "lizda_official");
	}

	private static BrandLinkRow link(long brandId, String username) {
		return new BrandLinkRow(brandId, 7L, brandId, username, BrandAccountType.OWN, 12,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null);
	}

	private static BrandAccountRow account(String username) {
		return account(100L, username);
	}

	private static BrandAccountRow account(long brandId, String username) {
		return new BrandAccountRow(brandId, username, LocalDate.of(2026, 8, 7),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null,
				null, null, null, null, null, null, null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
	}

	@Test
	void 활성_연결이_없으면_아무것도_하지_않는다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of());

		service().cleanupForAccountDeletion(7L);

		then(linkRepository).should(never()).softDeleteAllActiveByUser(7L);
		then(commandClient).should(never()).deregisterBrand(anyString());
	}

	@Test
	void 마지막_사용자면_연결_해제_후_monitoring_탈퇴한다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(account("lizda_official")));

		service().cleanupForAccountDeletion(7L);

		then(linkRepository).should().softDeleteAllActiveByUser(7L);
		then(commandClient).should().deregisterBrand("lizda_official");
	}

	@Test
	void 다른_사용자가_남으면_연결만_해제한다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		given(linkRepository.countActiveByBrand(100L)).willReturn(1);

		service().cleanupForAccountDeletion(7L);

		then(linkRepository).should().softDeleteAllActiveByUser(7L);
		then(commandClient).should(never()).deregisterBrand(anyString());
	}

	@Test
	void 다계정은_브랜드별로_마지막_사용자_판정을_따로_한다() {
		// 브랜드 100은 내가 마지막 사용자(탈퇴), 브랜드 200은 다른 사용자가 남음(유지).
		given(linkRepository.findAllActiveByUser(7L))
				.willReturn(List.of(link(100L, "lizda_official"), link(200L, "other_brand")));
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);
		given(linkRepository.countActiveByBrand(200L)).willReturn(1);
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(account(100L, "lizda_official")));

		service().cleanupForAccountDeletion(7L);

		then(linkRepository).should().softDeleteAllActiveByUser(7L);
		then(commandClient).should().deregisterBrand("lizda_official");
		then(commandClient).should(never()).deregisterBrand("other_brand");
	}

	@Test
	void monitoring_탈퇴_실패는_삼키고_탈퇴를_막지_않는다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(account("lizda_official")));
		willThrow(new MonitoringUnavailableException("연결 실패", null))
				.given(commandClient).deregisterBrand("lizda_official");

		assertThatCode(() -> service().cleanupForAccountDeletion(7L)).doesNotThrowAnyException();
	}

	@Test
	void 탈퇴_키는_brand_account의_username이_정본이고_링크_사본은_폴백이다() {
		// 링크의 username은 등록 시점 사본이다 — brandId로 조회한 monitoring 행의 값이 우선한다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(account("lizda_renamed")));

		service().cleanupForAccountDeletion(7L);

		then(commandClient).should().deregisterBrand("lizda_renamed");
	}

	@Test
	void brand_account_행이_없으면_링크_사본으로_탈퇴한다() {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.empty());

		service().cleanupForAccountDeletion(7L);

		then(commandClient).should().deregisterBrand("lizda_official");
	}
}
