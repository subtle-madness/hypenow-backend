package com.celfit.was.logging;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 제출 시점의 MDC를 워커 스레드에 복원한다 — 등록 실행기처럼 웹 스레드가 afterCommit에서
 * submit하는 작업이 원 요청의 requestId를 이어받아, 접수 로그와 백그라운드 처리 로그(및 거기서
 * 나가는 monitoring 호출)가 같은 ID로 묶인다. 워커는 풀에서 재사용되므로 실행 후 반드시
 * 비운다(빈 제출 컨텍스트도 clear로 덮어써 잔류값 누수를 막는다).
 */
public class MdcTaskDecorator implements TaskDecorator {

	@Override
	public Runnable decorate(Runnable runnable) {
		Map<String, String> context = MDC.getCopyOfContextMap();
		return () -> {
			if (context == null) {
				MDC.clear();
			} else {
				MDC.setContextMap(context);
			}
			try {
				runnable.run();
			} finally {
				MDC.clear();
			}
		};
	}
}
