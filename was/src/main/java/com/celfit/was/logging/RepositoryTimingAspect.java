package com.celfit.was.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 모든 {@code *Repository} 빈의 공개 메서드 호출을 자동 타이밍해 {@link RequestStageTimings}에
 * 쌓는다(2026-08-27 느린 요청 단계 분해). was의 요청 시간 본체가 "어느 쿼리 + 행 매핑"이라는 게
 * 08-25 분석으로 실측돼 있어(9초 중 DB 실행 ~350ms, 나머지가 JDBC 행 매핑·조립) 리포지토리 메서드
 * 단위가 곧 의미 있는 단계다 — 여기서 재는 나노는 SQL 실행 + 행 매핑을 포함한 벽시계다.
 *
 * <p>요청 스레드 밖(스케줄 잡 등) 호출은 {@code RequestStageTimings.record}가 무시하므로 이 aspect는
 * 무조건 감싼다(분기 없음 — 나노 타임스탬프 2회가 오버헤드의 전부). 리포지토리가 리포지토리를
 * 부르는 중첩은 현재 코드베이스에 없다는 전제로 겹침 보정을 하지 않는다 — 생기면 두 단계에 이중
 * 계상돼 합계가 총시간을 넘을 수 있다({@link SlowRequestStageLogFilter}의 기타 단계는 0으로 클램프).
 */
@Aspect
@Component
public class RepositoryTimingAspect {

	@Around("bean(*Repository)")
	public Object time(ProceedingJoinPoint pjp) throws Throwable {
		long start = System.nanoTime();
		try {
			return pjp.proceed();
		} finally {
			RequestStageTimings.record(
					pjp.getSignature().getDeclaringType().getSimpleName() + "." + pjp.getSignature().getName(),
					System.nanoTime() - start);
		}
	}
}
