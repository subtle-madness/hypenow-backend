# MON-P2 — monitoring v3 P2 표면

- **소속 트랙군**: 모니터링 트랙 — 2026-07-28 설계 확정: [specs/2026-07-28-monitoring-module-design.md](../superpowers/specs/2026-07-28-monitoring-module-design.md)
- **의존**: MON
- **상태**: 🔵 (07-30 구현 완료 — PR #195 리뷰 대기. Flyway V4 — 알람 트랙 V3 선행 머지 전제, 어긋나면 머지 직전 재번호)

## 내용

프론트 v3 확장 요구의 P2 4종 — 댓글 수집(`post_comment` — 추적 게시물당 일 1콜 15건 교체 갱신, 작성자 본인 답글은 동봉 미리보기로 판정해 추가 콜 0)·계정 표시 메타(`profile_meta`)·감지 매칭 키워드(`detected_candidate.matched_keywords`)·공유 단축 링크 해소(`POST /api/share/resolve` — 등록 API와 분리된 전처리, 병행 알람 트랙과의 파일 충돌 회피). 계약 [v1.1](../contracts/monitoring-was-contract.md)·Hiker 실측 [findings §10](../superpowers/plans/2026-07-28-monitoring-hiker-findings.md)(media pk는 shortcode base64url 산술 유도 — 저장 불필요). P1 4종·승인 제거·이벤트 대장은 알람 트랙 몫
