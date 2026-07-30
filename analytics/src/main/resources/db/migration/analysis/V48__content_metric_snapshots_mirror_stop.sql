-- content_metric_snapshots 미러 중단(2026-07-30) — 소비자 부재·미러 시간(12분 30초) 중 6~7분을
-- 이 테이블(약 70만 행) 하나가 차지했다. 신선도 신호는 contents.metric_captured_at으로 대체
-- (실측: MAX(contents.metric_captured_at) = MAX(content_metric_snapshots.captured_at), 마이크로초까지
-- 일치 — PipelineStatsService.health() 참조). 지표 이력 조회는 raw DB의
-- analytics.v_content_metric_snapshots를 쓴다.
--
-- expand-contract: 테이블·컬럼은 DROP하지 않는다(DROP은 CI migration-guard가 차단 + 참조 코드가
-- 완전히 끊긴 다음 릴리스에서만). TRUNCATE는 가드의 명시적 블라인드스팟(check-migration-safety.sh
-- DESTRUCTIVE 패턴 목록 주석 참조)이라 별도 allow-destructive 없이 통과한다. 여기에 그 주석을
-- 달아두지 않는 이유: 이 파일에 나중에 DROP이 추가될 때 가드가 조용히 통과시켜 버린다.
-- 리뷰 확인 사항 — 가드가 기계로 못 잡는 데이터 정리이므로 사람이 봐야 한다.
TRUNCATE TABLE content_metric_snapshots;

COMMENT ON TABLE content_metric_snapshots IS
  '미러 중단(2026-07-30, 소비자 부재·미러 시간 절반 차지) — 지표 이력 조회는 raw DB의 '
  'analytics.v_content_metric_snapshots를 쓴다. DDL은 expand-contract 원칙에 따라 다음 릴리스에서 DROP 예정.';
