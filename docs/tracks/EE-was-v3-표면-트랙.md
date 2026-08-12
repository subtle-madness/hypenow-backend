# EE — was v3 표면 트랙

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: S, DD, MON-P2
- **상태**: 🔨 (PR 대기)

## 내용

모니터링 v3 프론트 계약(6.25~6.33) 소비 표면 — was `/v1` API 9종(캠페인 CRUD·모니터링 항목 목록(6.26 완전 어셈블러)/등록/PATCH/취소·알림 설정·알림 목록/읽음) + 크론 2종(다이제스트 생성·따라잡기) + 탈퇴 시 모니터링 해지 루프(배치 대상 즉시 제외). 승인 큐를 monitoring 자동 전환으로 흡수(FE에는 승인 없는 자동 수집으로 보임) · 프론트 상태 6종은 영속화하지 않고 monitoring 원시 상태에서 **조회 시 유도** · 다이제스트는 `alarm_event`를 유저·날짜 단위로 **멱등 재계산**(워터마크 폐지, DD의 id 대장을 단일 원천으로 소비) · 이메일 발송은 monitoring 소유로 확정해 was 발송 경로 완전 제거 · app 스키마 V13 매핑을 **V16**으로 재구성(추적·알림 실사용 테이블만 남기는 재구축) · 어휘 경계 매핑(소문자 프론트 상태 ↔ 대문자 monitoring 상태, `MonitoringEventTypes`)은 was 경계에서만 변환. 팀원 P1 표면 확장(post_meta·hidden/error 신호·sweep_run·matched_keywords, `feat/monitoring-p1` 병합)으로 개통 차단 갭 해소 — [plans/2026-07-30-monitoring-v3-was.md](../superpowers/plans/archive/2026-07-30-monitoring-v3-was.md), 갭 파악·해소 결과 [plans/2026-07-30-monitoring-v3-merge-gaps.md](../superpowers/plans/archive/2026-07-30-monitoring-v3-merge-gaps.md)
