package com.celfit.analytics.llm;

import java.util.List;
import java.util.Map;

/**
 * 고유 trait 값 → 캐노니컬 매핑(2026-07-29 어휘 통제 스펙 §3-3, 1:N 최대 2개).
 * 매핑 불가는 빈 리스트, 응답에서 누락된 raw는 결과 맵에 없음(호출자가 재시도 대상으로 남긴다).
 */
@FunctionalInterface
public interface TraitMappingPort {

	Map<String, List<String>> map(List<String> rawValues);
}
