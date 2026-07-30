# T — trait 어휘 통제(유사도 v2 2단계)

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: R
- **상태**: ✅ (구현·배포·운영 매핑 잡 실행 전부 완료, 2026-07-30 — 잔여는 유사도 컷 재점검뿐)

## 내용

07-29 리포트 개편 카피 백필 완결로 traits 전량이 새 프롬프트 산출로 교체됐는데도 난수화 지속(고유 4,242/25,818, 싱글톤 62.6%)이 실측돼 보류 해제. 운영 빈도 데이터 주도로 **고정 어휘 172개·13축**(V41 `trait_taxonomy` 시드, 사용자 확정) + 합성 프롬프트 어휘 주입(`instructions(vocab)` — Anthropic static 캡처 해소) + 저장 sanitize(어휘 밖 드롭·중복 제거, 전부 드롭 시 빈 배열) + 기존 데이터 이행은 어드민 원샷 잡 `TRAIT_CANON_DRY/APPLY`(LLM 배치 매핑 → `trait_canon_log` 감사 → traits in-place UPDATE). **1:N 분해 매핑 채택**(복합 trait→원자 태그 최대 2, "감성 브이로그"→[브이로그, 감성 무드]) — [specs/2026-07-29-trait-vocabulary-control-design.md](../superpowers/specs/2026-07-29-trait-vocabulary-control-design.md) [plans/2026-07-29-trait-vocabulary-control.md](../superpowers/plans/2026-07-29-trait-vocabulary-control.md)

**07-30 운영 이행 완료**: 운영 `trait_canon_log`는 0행이었고 dev 스택(`deploy-test-postgres-1`)에
10,269행(매핑 9,461)이 쌓여 있었다 — 센티널(`canon_value=''`) 제외 9,461행을 스테이징 테이블
경유(`ON CONFLICT DO NOTHING`)로 운영에 복사. 복사 후 운영 미매핑 델타는 972건뿐이라
`TRAIT_CANON_DRY` 1회로 소진(Vertex `gemini-3.1-flash-lite`, LLM 호출 약 10회). 이어
`TRAIT_CANON_APPLY` 실행(14초): 고유 trait **8,826종 → 173종**, 14,817행 중 **14,298행 변경**.
**APPLY 직전 `traits_backup_20260730`(14,817행, PK `handle`+`analyzed_at`) 테이블로 원본 스냅샷을
떠 뒀다** — 잡 코드에 행 단위 롤백 경로가 없어 운영 측에서 보완한 안전장치(테이블 `COMMENT`에도
명시). **검증 완료 후 삭제 가능** — 계속 남겨둘 이유가 없는 임시 스냅샷이니 다음 세션이 정리해도
된다. 부수 실측: traits가 빈 배열이 된 행 3건(전량 어휘 밖 trait만 갖고 있어 드롭된 계정).
**잔여**: 유사도 컷 0.30 재점검(별도 진행 중, 이 트랙 범위 밖).
