# was /posts/{shortCode} — 게시물 상세 드로어 데모 페이지 설계

> 상태: 🟢 활성 · ✅ 구현/실행/반영됨

## 목적

"게시물 하나를 찍으면 상세 분석이 화면에 뜬다"를 실데이터로 증명한다.
게시물 드로어 v3 기획(2026-07-10 확정안)의 섹션 구성을 따르되, 프론트 연동 전
시스템 완성도 검증용 내부 화면이다. 태스크 D(`GET /api/posts/{shortCode}` JSON API)와 별개 —
D가 머지되면 이 페이지는 같은 데이터의 사람용 뷰로 남는다.

## 구조

`com.celfit.was.post` 평탄 패키지:

- `PostDetailRepository` — contents ⟕ accounts ⟕ content_analyses 단건 + 댓글 분류
  집계(`comment_classifications`) + 대표 댓글(`content_comments` 좋아요순 5).
  jsonb 컬럼은 `::text`로 받아 컨트롤러에서 Jackson 파싱.
- `PostDetailController` — `GET /posts/{shortCode}`, 없으면 404. 섹션: 헤더(계정·배지) /
  성과(피드 조회수 NULL 표기) / 벤치마크 / 왜 잘됐나(AI 종합·패턴) / 감지(브랜드·광고
  시그널·VLM 화면 속성) / 댓글 분석(분류 막대·진정성·인사이트·대표 댓글) / 캡션 원문.
- 진입점: `/coverage` 하단 "분석 완료 게시물" 목록.

미분석 게시물도 죽지 않는다 — 분석 섹션은 숨고 "LLM 분석 없음" 배지.

## 데모 데이터 채우기 기록 (2026-07-14)

- 분류 배치 7건 → 분석 배치(VLM on) 4건 성공: CQqIty4lr1A·CU2HcR4FYzW·CbDMixAlBy1·Cf-q5wCrpF-.
  기존 분석분(DYE2SisT-jE 등)과 합쳐 상세 데모 5건 이상 확보. 배치 한도는 실행 후 기본(10)으로 원복.

## 발견 — VLM 이미지 전달 (분석 층 수정 포함)

1. **Anthropic URL 이미지 소스는 인스타 CDN에서 항상 실패** — API가 대상 사이트
   robots.txt를 존중해 400 거절. `AnthropicVisionAnalyzer`를 직접 다운로드 + base64 전송으로 수정.
2. **인스타 CDN 썸네일은 서명 만료** — 크롤링 후 시간이 지나면 403 (122건 중 118건 만료).
   VLM 분석은 크롤링 직후 실행해야 한다. 파이프라인 자동화 시 순서 제약으로 반영할 것.

## 함정 (재발 주의)

- JDBC `timestamptz` → `java.sql.Timestamp` — Thymeleaf에서 `#temporals`가 아니라
  `#dates`로 포맷해야 한다 (`#temporals`는 java.time 전용, dashboard.html과 동일 관용구).
