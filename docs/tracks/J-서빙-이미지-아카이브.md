# J — 서빙 이미지 아카이브

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: B1(미러), 어드민 I
- **상태**: ✅ (운영 개통 완료 — 버킷 공개·PAR 등록·서버 env, 첫 실행 확인 대기)

## 내용

CDN 만료(~4일) 전 프로필·릴스 썸네일·게시글 썸네일을 OCI `hypenow-images` 버킷에 적재하는 analytics 잡 + `image_assets`(V37, 미러 제외 누적) + was COALESCE `/img/` 상대경로 서빙(Vercel rewrite 엣지 캐시 — 프론트 배포 완료) — [specs/2026-07-21-image-archive-design.md](../superpowers/specs/2026-07-21-image-archive-design.md) [plans/2026-07-21-image-archive.md → archive]
