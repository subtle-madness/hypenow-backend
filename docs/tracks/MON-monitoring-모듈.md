# MON — monitoring 모듈

- **소속 트랙군**: 모니터링 트랙 — 2026-07-28 설계 확정: [specs/2026-07-28-monitoring-module-design.md](../superpowers/specs/2026-07-28-monitoring-module-design.md)
- **의존**: —
- **상태**: ✅ (구현 완료 07-29 — 개통 ops([deploy/README §13](../../deploy/README.md))·머지 대기 · [plans/archive/2026-07-28-monitoring-module.md](../superpowers/plans/archive/2026-07-28-monitoring-module.md) · Hiker 매핑 [plans/2026-07-28-monitoring-hiker-findings.md](../superpowers/plans/2026-07-28-monitoring-hiker-findings.md))

## 내용

신규 4번째 모듈 — 시딩 캠페인 모니터링(계정 키워드 감시→후보 감지→FE 승인→게시물 추적 상태 기계, target=캠페인 단위·스냅샷=관측 대상 단위, Hiker-only 수집, 사설 monitoring DB 2스키마 raw/public — was는 public 읽기 전용, 명령은 내부 API + `/v1/monitoring`)
