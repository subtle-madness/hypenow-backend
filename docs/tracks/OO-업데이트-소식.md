# OO — 업데이트 소식(제품 공지)

- **소속 트랙군**: 단독 (프론트 변경요청서, 2026-08-03)
- **의존**: —
- **상태**: 구현 완료 · PR 대기 · 배포 미실행

## 내용

운영팀이 어드민에서 작성한 제품 업데이트 소식을 유저 대시보드 사이드바 패널에 서빙한다 —
설계: [specs/2026-08-03-product-notices-design.md](../superpowers/specs/archive/2026-08-03-product-notices-design.md) ·
구현 계획: [plans/archive/2026-08-03-product-notices-api.md](../superpowers/plans/archive/2026-08-03-product-notices-api.md)

## 엔드포인트

| 우선순위 | 엔드포인트 | 비고 |
|---|---|---|
| P0 | `GET /v1/notices` | 인증 유저, 발행분만(미래 publishedAt 제외), publishedAt 내림차순 |
| P0 | `POST /v1/admin/notices` | ADMIN, 201, id·date는 서버 부여/파생 |
| P1 | `GET /v1/admin/notices` | 예약분 포함 |
| P1 | `PATCH /v1/admin/notices/{id}` | items 전체 교체, 항목 id 재발급 허용 |
| P1 | `DELETE /v1/admin/notices/{id}` | 204, hard delete |
| P2 | `GET`/`PUT /v1/notices/seen` | 유저당 lastSeenAt 타임스탬프 1개, PUT 204 |

## 핵심 결정

- **신규 `/v1/notices` — `/v1/notifications`(다이제스트) 미확장**: type enum·본문/링크 부재·
  하루 1건 전제·count 등 4필드가 충돌해(요청서 §3) 신규 리소스로 분리, 기존 계약 무변경.
- **예약 발행 포함**: 어드민 폼에 시각 입력이 이미 있어 스펙 그대로 구현. 유저 목록은 미래
  `publishedAt`을 제외, 어드민 목록은 포함(운영팀이 예약분을 미리 확인·수정 가능).
- **대상은 전 유저 공통**: 대상 필드 없음. 플랜/그룹 타게팅 요건이 생기면 별도 확장.
- **seen은 소식별 읽음이 아니라 유저당 타임스탬프 1개**: `notice_seen(user_id PK, last_seen_at)`.
  뱃지(안 읽은 건수) 계산은 프론트가 `publishedAt > lastSeenAt` 비교로 한다(요청서 4-6) — 백엔드는
  저장·조회만 담당.
- **경로는 `/v1/notices`**: 신규 리소스를 `/v2`에 모으는 백엔드 정책 없음(기존 `/v2`는 influencers
  일부뿐이라 전례로 못 씀).

## 스키마 (3테이블, `V20260803120000__notices.sql`)

- `app.notices(id, title, published_at, created_at, updated_at)` — `date` 컬럼 없음, 응답 시점에
  `published_at`의 KST 달력 날짜를 파생(`KstTimestamps`).
- `app.notice_items(id, notice_id FK CASCADE, position, tag CHECK IN ('new','changed','improved','fixed'),
  summary, body, link_href, link_label)` — `link_href`/`link_label`은 CHECK로 둘 다 null 또는 둘 다 값.
- `app.notice_seen(user_id PK FK, last_seen_at)`.

## 검증 규칙

title 공백 · items 빈 배열 · summary 공백 · tag 4종 밖 · link 있는데 href·label 공백 ·
body null · publishedAt 형식 불량 · **items 배열의 null 원소**(리뷰에서 발견, NPE→500이던 결함을
400 `VALIDATION_FAILED`로 정정) → 전부 400. body 빈 문자열은 허용.

## 관련 문서

- [specs/2026-08-03-product-notices-design.md](../superpowers/specs/archive/2026-08-03-product-notices-design.md)
- [plans/archive/2026-08-03-product-notices-api.md](../superpowers/plans/archive/2026-08-03-product-notices-api.md)
