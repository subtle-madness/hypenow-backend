# J — 서빙 이미지 아카이브

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: B1(미러), 어드민 I
- **상태**: ✅ (운영 개통 완료 — 버킷 공개·PAR 등록·서버 env. 08-14 GCS 컷오버 후에도 정상 가동
  확인(08-17 실측: 버킷 `thumb/` 155,217 = `image_assets` thumbnail 155,217 정확 일치).
  08-17: 영구 무효 URL 제외 추가 — `exception://` 센티널 5건 + `rsrc.php/null.jpg` 플레이스홀더
  10건이 매일 재시도·실패하며 어드민 카드를 상시 FAILED로 만들던 것을 만료 제외와 같은 "제외"로
  분리(`permanentlyInvalid`). 잔여: 매 실행 동일 2건의 커넥트 타임아웃은 실체 미확정 — 재발 시 추적)

## 내용

CDN 만료(~4일) 전 프로필·릴스 썸네일·게시글 썸네일을 OCI `hypenow-images` 버킷에 적재하는 analytics 잡 + `image_assets`(V37, 미러 제외 누적) + was COALESCE `/img/` 상대경로 서빙(Vercel rewrite 엣지 캐시 — 프론트 배포 완료) — [specs/2026-07-21-image-archive-design.md](../superpowers/specs/2026-07-21-image-archive-design.md) [plans/2026-07-21-image-archive.md → archive]
