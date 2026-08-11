package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.brandmonitoring.V1BrandAccountService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 탈퇴 시 브랜드 모니터링 정리 훅(2026-08-07 스펙 §5-3 — Task 3 이월 지적) 검증.
 * 핵심은 <b>순서</b>다: users 하드 삭제가 brand_monitorings를 CASCADE로 지우기 전에 정리해야
 * "마지막 사용자면 monitoring 탈퇴" 판정이 가능하다. 순서가 뒤집히면 고아 브랜드가 매일 수집을 계속한다.
 */
@ExtendWith(MockitoExtension.class)
class AccountDeletionBrandHookTest {

	@Mock
	UserRepository userRepository;
	@Mock
	MonitoringItemRepository itemRepository;
	@Mock
	V1BrandAccountService brandAccountService;

	private AccountDeletionService service() {
		return new AccountDeletionService(userRepository, itemRepository,
				Optional.<MonitoringCommandClient>empty(), Optional.of(brandAccountService));
	}

	@Test
	void 브랜드_정리는_users_하드_삭제보다_먼저_돈다() {
		service().deleteAccount(7L);

		InOrder order = Mockito.inOrder(brandAccountService, userRepository);
		order.verify(brandAccountService).cleanupForAccountDeletion(7L);
		order.verify(userRepository).deleteAccount(7L);
	}

	@Test
	void 브랜드_정리가_실패해도_탈퇴는_진행된다() {
		willThrow(new IllegalStateException("DB 실패")).given(brandAccountService).cleanupForAccountDeletion(7L);

		assertThatCode(() -> service().deleteAccount(7L)).doesNotThrowAnyException();

		then(userRepository).should().deleteAccount(7L);
	}

	@Test
	void 브랜드_서비스_빈이_없으면_훅을_통째로_건너뛴다() {
		// monitoring.enabled=false 환경 — 빈이 아예 없어도 탈퇴가 정상 완료돼야 한다.
		AccountDeletionService disabled = new AccountDeletionService(userRepository, itemRepository,
				Optional.<MonitoringCommandClient>empty(), Optional.<V1BrandAccountService>empty());

		assertThatCode(() -> disabled.deleteAccount(7L)).doesNotThrowAnyException();

		then(userRepository).should().deleteAccount(7L);
		then(brandAccountService).shouldHaveNoInteractions();
	}
}
