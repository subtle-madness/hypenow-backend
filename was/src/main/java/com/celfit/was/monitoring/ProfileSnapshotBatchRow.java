package com.celfit.was.monitoring;

import java.time.LocalDate;

/**
 * profile_snapshot의 계정별 최신 1행(배치 조회용) — {@link ProfileSnapshotRow}는 단일 계정 조회
 * (profileTimeseries)용이라 username이 없다. 6.26 어셈블러가 유저의 여러 계정 팔로워를 한 SQL
 * 왕복으로 가져올 때 쓴다.
 */
public record ProfileSnapshotBatchRow(String username, LocalDate capturedOn, Long followers, Long mediaCount) {
}
