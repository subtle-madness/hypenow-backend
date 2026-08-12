# DD — 모니터링 알람 모듈

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: S
- **상태**: 🔨 (EE 브랜치에 병합돼 함께 PR)

## 내용

알람 소유를 monitoring으로 이동 — `alarm_event` 대장(워터마크 없음)·적재 5지점·발송 크론(디바운스·옵트아웃·유저당 1통) + 승인 플로우 제거(첫 감지 자동 추적) + 일시 오류 당일 재시도 + `target.user_id`(V3)·app 옵트아웃(was V15) + 계약 **v2.0**. PR②(was 클라이언트 정렬)·프론트 알림 API는 EE에서 흡수 — [specs/2026-07-30-monitoring-alarm-module-design.md](../superpowers/specs/archive/2026-07-30-monitoring-alarm-module-design.md)
