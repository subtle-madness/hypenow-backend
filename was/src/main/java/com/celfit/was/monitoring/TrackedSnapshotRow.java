package com.celfit.was.monitoring;

import java.time.LocalDate;

/**
 * post_snapshot 1행 + shortCode(배치 조회용) — {@link PostSnapshotRow}는 단일 target 조회
 * (postTimeseries)용이라 shortCode가 없다. 6.26 어셈블러의 목록 조회는 여러 게시물을 한 SQL
 * 왕복으로 가져온 뒤 shortCode로 그룹핑해야 해서 별도 레코드를 둔다.
 */
public record TrackedSnapshotRow(String shortCode, LocalDate capturedOn, String contentType, Long likes,
		boolean likesHidden, Long comments, Long views, Long saves, Long shares, Long reposts) {
}
