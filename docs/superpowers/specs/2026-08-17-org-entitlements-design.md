# 조직·엔터프라이즈 entitlement 설계

> 상태: 🟢 활성 · 작성 2026-08-17

## 배경·목표

엔터프라이즈 계약 기업에 대해 기능 on/off와 데이터 범위를 차등 제공한다. 계약은 **기업 단위**이며, 이미 **개별협상이 진행 중**이라 등급 하나로는 부족하다 → **등급(plan) + 기업별 오버라이드** 방식(절충안 C)으로 확정.

핵심 결정: **기존 `users.role`(USER/ADMIN)은 손대지 않는다.** role은 하입나우 운영진 인가 축이고, 계약 등급은 상품(entitlement) 축이다. 이를 한 컬럼에 섞으면 (1) 조합 폭발, (2) 어드민 인가 코드(`hasRole("ADMIN")`, `AdminRoleFreshnessFilter`) 재점검이라는 보안 리스크, (3) 회사 속성을 유저 컬럼에 중복 저장하는 문제가 생긴다.

## 세 개의 축

| 축 | 저장 위치 | 답하는 질문 | 대상 표면 |
|---|---|---|---|
| `users.role` (USER/ADMIN) | 유저 | 하입나우 시스템 운영자인가 | `/v1/admin/**` (기존, 무변경) |
| `organizations.plan` + 오버라이드 | 조직 | 이 회사가 뭘 샀나 | 기능/데이터 차등 |
| `organization_members.org_role` (MEMBER/ORG_ADMIN) | 멤버십 | 자기 회사 안에서 관리자인가 | `/v1/org/**` (신규, 자기 조직 한정) |

## 스키마 (was Flyway, `app` 스키마, 전부 additive)

마이그레이션 1개, UTC 채번(예: `V20260817113000__organizations.sql`):

- `app.organizations`
  - `id bigserial PK`, `name text NOT NULL`
  - `plan text NOT NULL DEFAULT 'FREE' CHECK (plan IN ('FREE','ENTERPRISE'))`
  - `contract_start date`, `contract_end date` (nullable — NULL은 무기한)
  - `created_at timestamptz NOT NULL DEFAULT now()`
- `app.organization_members`
  - `org_id bigint NOT NULL REFERENCES app.organizations(id)`
  - `user_id bigint NOT NULL REFERENCES app.users(id)`, **`UNIQUE(user_id)`** — 당분간 1유저 1조직. 이 제약이 "다른 조직 소속 유저를 빼앗는" 문제도 구조적으로 차단한다.
  - `org_role text NOT NULL DEFAULT 'MEMBER' CHECK (org_role IN ('MEMBER','ORG_ADMIN'))`
  - `created_at timestamptz NOT NULL DEFAULT now()`, `PRIMARY KEY(org_id, user_id)`
- `app.organization_feature_overrides`
  - `org_id bigint NOT NULL REFERENCES app.organizations(id)`
  - `feature_key text NOT NULL`, `enabled boolean NOT NULL`
  - `value jsonb` (데이터 범위 파라미터, 예: `{"depth": 100}`)
  - `updated_at timestamptz NOT NULL DEFAULT now()`, `PRIMARY KEY(org_id, feature_key)`

**FREE는 DB에 저장되지 않는다** — 무소속 유저의 폴백값. 기존 유저 백필 불필요.

## 판정 로직 (단일 지점)

`com.celfit.was.entitlement` 패키지:

- `Plan` enum(FREE/ENTERPRISE), `FeatureKey` enum(기능 키 정본 레지스트리).
- `PlanDefaults`: plan → 기본 활성 키 집합 + 기본 파라미터. 코드 상수.
- `Entitlements` record: `plan`, 활성 `FeatureKey` 집합, 키별 파라미터 map. `has(FeatureKey)` 제공.
- `EntitlementService.entitlementsFor(userId)`:
  1. 멤버십+조직 단일 조인 조회 (JdbcClient)
  2. 없거나 **`contract_end < 오늘`이면 FREE 폴백** (만료 배치 불필요, 판정 시점 계산)
  3. plan 기본값 위에 오버라이드 합성
- 판정은 세션이 아닌 **매번 DB 기준** (어드민 role 신선도 선례와 동일). 캐시는 성능 문제가 실측되면 후속.

게이트 사용법: 기능 on/off는 컨트롤러/서비스에서 `entitlements.has(X)` 체크 후 미보유 시 403 + 에러코드 `FEATURE_NOT_AVAILABLE`. 데이터 차등은 서비스가 `Entitlements` 파라미터를 쿼리 인자로 주입 — SQL/뷰에 plan 분기를 심지 않는다.

**1차 범위는 인프라까지.** `FeatureKey`의 제품 키 목록·등급별 기본값은 계약 내용 확정 시 배선한다(스코프 경계, 이 스펙의 산출물 아님). 프론트가 소비할 수 있게 `GET /v1/me/entitlements`(plan + 활성 키 + 파라미터)는 1차에 포함.

## 어드민 API (하입나우 운영진, `/v1/admin/organizations`)

기존 `/v1/admin/**` 표면 소속 → `hasRole("ADMIN")` + `AdminRoleFreshnessFilter` 자동 적용.

- `POST /v1/admin/organizations` 조직 생성(name, plan, 계약기간)
- `GET /v1/admin/organizations`, `GET /v1/admin/organizations/{id}` (멤버·오버라이드 포함)
- `PATCH /v1/admin/organizations/{id}` plan·계약기간 수정
- `POST /v1/admin/organizations/{id}/members` {userId, orgRole} / `DELETE .../members/{userId}` / `PATCH .../members/{userId}` orgRole 변경
- `PUT /v1/admin/organizations/{id}/overrides/{featureKey}` {enabled, value} / `DELETE` — featureKey는 `FeatureKey` enum 검증

## 조직 셀프서비스 (`/v1/org/**`, 신규)

인증 필수(세션), role 무관. **요청자의 org_id를 매 요청 DB에서 해석**해 쿼리 조건에 박는다 — 남의 조직 접근이 구조적으로 불가능.

- `GET /v1/org` — 자기 조직 정보(멤버면 누구나). 무소속이면 404.
- `GET /v1/org/members` — 멤버 목록(멤버면 누구나)
- `POST /v1/org/members` {email, orgRole} — **ORG_ADMIN만**. 기존 가입 계정의 email 정확 일치로 추가. 대상이 이미 타 조직 소속이면 UNIQUE 제약으로 409.
- `DELETE /v1/org/members/{userId}`, `PATCH /v1/org/members/{userId}`(orgRole) — **ORG_ADMIN만**. 자기 자신의 ORG_ADMIN 해제/탈퇴로 조직에 ORG_ADMIN이 0명이 되는 것은 허용(운영진이 어드민 API로 복구 가능).

ORG_ADMIN 판정도 세션이 아닌 요청 시점 DB 멤버십 기준. 위반 시 403 `NOT_ORG_ADMIN`.

## 에러 계약

- 403 `FEATURE_NOT_AVAILABLE` — 기능 게이트 미보유
- 403 `NOT_ORG_ADMIN` — org 관리 행위를 MEMBER가 시도
- 404 — 무소속 유저의 `/v1/org` 접근, 없는 조직/멤버
- 409 — 이미 타 조직 소속 유저 추가 시도

## 테스트

Testcontainers 통합 테스트 (`:was:test`):
- EntitlementService: 무소속 FREE 폴백 / ENTERPRISE 기본값 / 오버라이드 합성(on·off·파라미터) / 계약 만료 시 FREE 폴백
- 어드민 API: CRUD + 멤버 배정 + 오버라이드, 비ADMIN 403
- org API: MEMBER 조회 가능·관리 403, ORG_ADMIN 관리 가능, 무소속 404, 타 조직 소속 추가 409
- `/v1/me/entitlements` 응답 형상

## 이후 확장 (이번 스코프 아님)

- 조직 초대코드(기존 signup-code 인프라 재사용)로 가입 즉시 자동 배정
- 멤버 추가 시 대상 동의 플로우
- entitlement 판정 캐시(성능 실측 후)
- 조직 감사 로그(org 측 행위 기록)
