# 업데이트 소식(제품 공지) 백엔드 설계

> 상태: ✅ 구현됨(배포 전) · 정본 요구사항은 프론트 변경요청서(2026-08-03, 프론트엔드팀 작성)

## 요약

운영팀이 어드민에서 작성한 제품 업데이트 소식을 유저 대시보드 사이드바 패널에 서빙한다.
프론트는 목데이터로 완성돼 있고, 응답 타입 정본은 프론트 코드(`src/lib/monitoring/notices.ts`)다.

## 엔드포인트 (요청서 §4 그대로)

| 우선순위 | 엔드포인트 | 비고 |
|---|---|---|
| P0 | `GET /v1/notices` | 인증 유저, 발행분만(미래 publishedAt 제외), publishedAt 내림차순 |
| P0 | `POST /v1/admin/notices` | ADMIN, 201, id·date는 서버 부여/파생 |
| P1 | `GET /v1/admin/notices` | 예약분 포함 |
| P1 | `PATCH /v1/admin/notices/{id}` | items 전체 교체, 항목 id 재발급 허용 |
| P1 | `DELETE /v1/admin/notices/{id}` | 204, hard delete |
| P2 | `GET`/`PUT /v1/notices/seen` | 유저당 lastSeenAt 타임스탬프 1개, PUT 204 |

## 요청서 §6 열린 질문의 채택값

1. **예약 발행: 포함** — 어드민 폼에 시각 입력이 이미 있어 스펙 그대로 구현. 유저 목록 제외 + 어드민 목록 포함.
2. **대상: 전 유저 공통** — 대상 필드 없음. 플랜/그룹 타게팅 요건이 생기면 별도 확장.
3. **경로: `/v1/notices`** — 신규 리소스를 /v2에 모으는 백엔드 정책 없음(기존 /v2는 influencers 일부뿐).

## 핵심 결정

- **`/v1/notifications`(다이제스트) 미확장** — type enum·본문/링크 부재·하루 1건 전제·count 등 4필드가 충돌(요청서 §3). 신규 리소스로 분리해 기존 계약 무변경.
- **데이터 모델**은 요청서 §5 제안 그대로: `app.notices` / `app.notice_items`(CASCADE, position 순서) / `app.notice_seen`(user_id PK). `date` 컬럼 없음 — published_at의 KST 달력 날짜를 응답 시 파생.
- **보안은 기존 게이트 재사용** — `/v1/admin/**`는 SecurityConfig `hasRole("ADMIN")` + `AdminRoleFreshnessFilter`(매 요청 DB role 재확인)가 이미 커버. 유저 표면은 기본 잠금(anyRequest authenticated).
- **envelope·에러 코드**는 `docs/contracts/monitoring-frontend-api-spec.md` §1 공통 규약 준수. nullable(특히 `items[].link`)은 키 생략 없이 명시적 null.
- **seen은 소식별 읽음이 아니다** — 타임스탬프 1개 저장만, 뱃지 계산은 프론트(요청서 4-6).

구현 계획: [docs/superpowers/plans/archive/2026-08-03-product-notices-api.md](../plans/archive/2026-08-03-product-notices-api.md)
