# G — 서비스 데이터

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: 독립
- **상태**: ✅

## 내용

`app` 스키마 신설(was 소유 Flyway) + 이메일+비밀번호 로그인(Spring Security 세션 쿠키·CSRF) + 이메일 소유권 인증(6.17 — 07-19 구현, **07-29 제거**: [specs/2026-07-18-email-verification-design.md](../superpowers/specs/archive/2026-07-18-email-verification-design.md) 🗄) + 저장 2종(`/api/saved/influencers` 상태·메모, `/api/saved/contents` 북마크)
