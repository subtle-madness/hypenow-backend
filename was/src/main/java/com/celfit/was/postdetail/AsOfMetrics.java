package com.celfit.was.postdetail;

import com.celfit.contract.analysis.ContentMetricSnapshot;
import java.time.OffsetDateTime;

/**
 * as-of 조립 입력 — 선택된 스냅샷과 기준 시각(집계 기간 끝의 KST 다음날 0시).
 * reference는 경과일 계산의 "지금" 역할을 한다.
 */
public record AsOfMetrics(ContentMetricSnapshot snapshot, OffsetDateTime reference) {
}
