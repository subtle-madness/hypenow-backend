package com.celfit.was.logging;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 요청 1건의 단계별 소요 누적기(2026-08-27 느린 요청 단계 분해 — 08-25 posts 9초 지연 분석에서
 * "어느 단계가 몇 초인지" 볼 표면이 전무했던 계측 공백의 해소). MVC 동기 요청 전제의 ThreadLocal —
 * 시작({@link #begin})은 {@link SlowRequestStageLogFilter}가, 기록({@link #record})은
 * {@link RepositoryTimingAspect}가 한다. begin() 없이 기록되는 호출(스케줄 잡·부팅 등 요청 밖
 * 스레드)은 조용히 무시된다 — 요청 스레드에서만 활성.
 *
 * <p>스테이지 키는 "클래스단순명.메서드명"(예: BrandReadRepository.findComments) — 같은 키의 반복
 * 호출은 합산하고 호출 수를 센다(N+1 패턴이 calls로 드러난다).
 */
final class RequestStageTimings {

	/** value: [0]=누적 나노, [1]=호출 수. LinkedHashMap — 첫 호출 순서 보존(로그 가독). */
	private static final ThreadLocal<Map<String, long[]>> STAGES = new ThreadLocal<>();

	private RequestStageTimings() {
	}

	/** 요청 시작 — 누적기 활성화(스레드 재사용 대비 항상 새 맵). */
	static void begin() {
		STAGES.set(new LinkedHashMap<>());
	}

	/** 단계 1회 기록 — 요청 밖(begin 전) 호출은 무시. */
	static void record(String stage, long nanos) {
		Map<String, long[]> stages = STAGES.get();
		if (stages == null) {
			return;
		}
		long[] agg = stages.computeIfAbsent(stage, k -> new long[2]);
		agg[0] += nanos;
		agg[1]++;
	}

	/** 요청 종료 — 누적분을 회수하고 비활성화(반드시 finally에서 호출 — 톰캣 스레드 재사용 오염 방지). */
	static Map<String, long[]> end() {
		Map<String, long[]> stages = STAGES.get();
		STAGES.remove();
		return stages == null ? Map.of() : stages;
	}
}
