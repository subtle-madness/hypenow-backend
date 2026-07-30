# BB — 인플루언서 이메일(bio 정규식 파싱)

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: C1, P4
- **상태**: 🔨 (구현 완료 — PR 대기)

## 내용

biography에서 정규식 `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`로 이메일 파싱해 발굴 목록에 노출. 운영 실측(계정 7,033 중 biography 보유 6,808·정규식 매치 2,553=37.5%, 샘플 30건 오탐 0건)으로 LLM 없이 정규식만 채택. `v_account_summaries.email` 신설(POSIX substring leftmost match로 "첫 매치만" 자연 성립, `lower()` 정규화) + analysis Flyway V46(`account_summaries.email` ADD COLUMN, record 끝 위치) + was 발굴 목록(`GET /v1/influencers`) 카드 `email` 배선(구 "크롤러 미수집(V31)이라 null" 스텁 제거) + `contactOpen` 필터를 죽은 `AND false`에서 `AND su.email IS NOT NULL`로 교체. 뷰티 필터는 기존 `v_recent_content`(QUALIFIED ∧ beauty ∧ ¬beauty_company)가 이미 적용 중이라 무변경 — [specs/2026-07-30-influencer-email-from-bio-design.md](../superpowers/specs/2026-07-30-influencer-email-from-bio-design.md)
