-- 주간 다이제스트 배치 조회 성능(2026-08-28 품질 리뷰 I3) — WeeklyDigestJob은 매주 월요일 09:00
-- KST + 요일 제한 없는 따라잡기 틱(매일 09:10~23:50, 매 10분)마다
-- SELECT ... FROM alarm_event WHERE occurred_at >= :from AND occurred_at < :to 를 실행한다
-- (MonitoringReadRepository#findAlarmEventsBetween). 기존 alarm_event_user_idx(user_id,
-- occurred_at DESC, V3)는 선행 컬럼이 user_id라 occurred_at 단독 범위 조회를 태우지 못해
-- 틱마다 전수 스캔이 된다.
CREATE INDEX alarm_event_occurred_at_idx ON alarm_event (occurred_at);
