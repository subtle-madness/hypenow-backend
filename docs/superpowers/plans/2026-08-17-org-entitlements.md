# 조직·엔터프라이즈 entitlement 구현 계획

> 상태: 🟢 활성 · 작성 2026-08-17
> **For agentic workers:** 스펙 정본은 [specs/2026-08-17-org-entitlements-design.md](../specs/2026-08-17-org-entitlements-design.md). 태스크 순서대로 구현하고 태스크마다 커밋한다.

**Goal:** 기업 단위 엔터프라이즈 계약을 위한 조직 모델 + 등급/오버라이드 entitlement 판정 인프라 + 어드민·조직 셀프서비스 API.

**Architecture:** `users.role` 불변. 신규 3테이블(organizations, organization_members, organization_feature_overrides) + `EntitlementService` 단일 판정 지점 + `/v1/admin/organizations`(운영) + `/v1/org`(고객사 셀프서비스) + `/v1/me/entitlements`(프론트 소비).

**Tech Stack:** Spring Boot 4.1 / Java 21 / JdbcClient / Flyway / Testcontainers 2.x (`org.testcontainers.postgresql.PostgreSQLContainer`).

**공통 규칙:**
- 기존 was 컨벤션을 먼저 읽고 따른다: `was/src/main/java/com/celfit/was/v1/admin/AdminUsersController.java`(어드민 컨트롤러·페이징·Swagger 문서화), `was/src/main/java/com/celfit/was/auth/UserRepository.java`(JdbcClient 관용구), 기존 에러 응답/예외 핸들러 컨벤션(`@RestControllerAdvice` 검색), 통합 테스트 베이스 클래스(기존 admin API 테스트 참조).
- DTO는 record + 정적 `from()`. 주석·커밋 메시지 한국어, prefix `feat(was):`.
- 테스트 실행 전 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.
- 모듈 테스트만: `./gradlew :was:test --tests "com.celfit.was.entitlement.*"` 식으로 좁혀 실행, 태스크 완료 시 `./gradlew :was:test`는 마지막에 1회.

---

### Task 1: 마이그레이션 + Entitlement 코어

**Files:**
- Create: `was/src/main/resources/db/migration/app/V<UTC now>__organizations.sql` (채번은 생성 시점 UTC, 예 `V20260817113000__organizations.sql`)
- Create: `was/src/main/java/com/celfit/was/entitlement/Plan.java` (enum FREE, ENTERPRISE)
- Create: `was/src/main/java/com/celfit/was/entitlement/FeatureKey.java` (enum, 제품 키 0개로 시작 — 빈 enum 허용. javadoc으로 "기능 키 정본 레지스트리, 배선 시 추가" 명시)
- Create: `was/src/main/java/com/celfit/was/entitlement/Entitlements.java` (record: `Plan plan`, `Set<FeatureKey> enabled`, `Map<FeatureKey, JsonNode> params`; `boolean has(FeatureKey)`)
- Create: `was/src/main/java/com/celfit/was/entitlement/PlanDefaults.java` (static: `Set<FeatureKey> enabledFor(Plan)`, `Map<FeatureKey, JsonNode> paramsFor(Plan)` — 현재는 빈 세트/맵 반환, 배선 시 채움)
- Create: `was/src/main/java/com/celfit/was/entitlement/EntitlementRepository.java` (JdbcClient: 유저의 멤버십+조직 조인 단건 조회, 조직 오버라이드 목록 조회)
- Create: `was/src/main/java/com/celfit/was/entitlement/EntitlementService.java`
- Test: `was/src/test/java/com/celfit/was/entitlement/EntitlementServiceIT.java`

**마이그레이션 SQL (전문):**

```sql
-- 조직·엔터프라이즈 entitlement: 기업 단위 계약 모델 (스펙: docs/superpowers/specs/2026-08-17-org-entitlements-design.md)
CREATE TABLE app.organizations (
    id             bigserial PRIMARY KEY,
    name           text NOT NULL,
    plan           text NOT NULL DEFAULT 'FREE' CHECK (plan IN ('FREE', 'ENTERPRISE')),
    contract_start date,
    contract_end   date,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.organization_members (
    org_id     bigint NOT NULL REFERENCES app.organizations(id),
    user_id    bigint NOT NULL UNIQUE REFERENCES app.users(id),
    org_role   text NOT NULL DEFAULT 'MEMBER' CHECK (org_role IN ('MEMBER', 'ORG_ADMIN')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);

CREATE TABLE app.organization_feature_overrides (
    org_id      bigint NOT NULL REFERENCES app.organizations(id),
    feature_key text NOT NULL,
    enabled     boolean NOT NULL,
    value       jsonb,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, feature_key)
);
```

**EntitlementService 판정 규칙 (정본):**
1. `organization_members ⨝ organizations`를 user_id로 단건 조회. 없으면 → `Entitlements(FREE, PlanDefaults.enabledFor(FREE), paramsFor(FREE))`.
2. `contract_end`가 NOT NULL이고 `contract_end < LocalDate.now()`(서버 기준일)이면 → FREE 폴백 (1과 동일 결과).
3. 아니면 plan 기본값 위에 오버라이드 합성: `enabled=true`면 키 추가, `false`면 제거. `value`가 NOT NULL이면 params에 put(기본값 덮어씀). DB의 feature_key가 `FeatureKey` enum에 없으면 **무시하고 warn 로그** (키 삭제 후 잔재 행 대비).

**테스트 케이스 (Testcontainers, 기존 IT 베이스 관용구 재사용):**
- 무소속 유저 → plan=FREE
- ENTERPRISE 조직 멤버 → plan=ENTERPRISE
- contract_end가 어제인 조직 멤버 → FREE 폴백
- contract_end NULL → ENTERPRISE 유지
- 오버라이드 합성은 FeatureKey가 현재 빈 enum이라 IT에서 직접 못 만든다 → 합성 로직은 repository가 반환한 행을 합성하는 **순수 함수로 분리**(`EntitlementService.compose(plan, overrides)` package-private)하고, 단위 테스트에서 가짜 오버라이드 행(record)으로 검증: on 추가/off 제거/value 덮어쓰기/enum에 없는 키 무시.

**Steps:** 실패 테스트 작성 → 실행(FAIL 확인) → 구현 → `./gradlew :was:test --tests "com.celfit.was.entitlement.*"` PASS → 커밋 `feat(was): 조직·entitlement 스키마 및 판정 코어`.

### Task 2: GET /v1/me/entitlements

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/account/MeEntitlementsController.java` (기존 `/v1/me` 컨트롤러 패턴·인증 principal 획득 관용구를 따름. 기존 Me 컨트롤러에 핸들러 추가가 컨벤션에 더 맞으면 Modify로 대체)
- Create: `was/src/main/java/com/celfit/was/v1/account/MeEntitlementsResponse.java` (record: `String plan`(소문자), `List<String> features`(활성 키), `Map<String, JsonNode> params`; 정적 `from(Entitlements)`)
- Test: 기존 Me API IT 클래스 패턴으로 `MeEntitlementsIT` — 인증 유저 200 + `plan:"free"`·빈 배열, 비인증 401.

**Steps:** 실패 테스트 → 구현 → PASS → 커밋 `feat(was): /v1/me/entitlements 조회 API`.

### Task 3: 어드민 조직 API

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/admin/AdminOrganizationsController.java`
- Create: `was/src/main/java/com/celfit/was/v1/admin/OrganizationAdminService.java` + 요청/응답 record들 (같은 패키지, 기존 admin DTO 파일 배치 컨벤션 따름)
- Create: `was/src/main/java/com/celfit/was/entitlement/OrganizationRepository.java` (조직 CRUD·멤버·오버라이드 쓰기. EntitlementRepository는 판정 읽기 전용으로 유지)
- Test: `AdminOrganizationsIT`

**엔드포인트 계약 (스펙 §어드민 API):**
- `POST /v1/admin/organizations` {name, plan, contractStart?, contractEnd?} → 201 + 생성 조직
- `GET /v1/admin/organizations` (기존 admin 목록 페이징 컨벤션) / `GET /{id}` → 조직 + 멤버 목록 + 오버라이드 목록
- `PATCH /{id}` {plan?, contractStart?, contractEnd?} — null 허용 필드 구분은 기존 PATCH 컨벤션 따름
- `POST /{id}/members` {userId, orgRole} → 201. 대상 유저 없으면 404, 이미 타 조직 소속이면 409
- `PATCH /{id}/members/{userId}` {orgRole} / `DELETE /{id}/members/{userId}` → 404 if 미소속
- `PUT /{id}/overrides/{featureKey}` {enabled, value?} — featureKey가 enum에 없으면 400. `DELETE /{id}/overrides/{featureKey}`
- plan/orgRole 문자열은 enum 검증, 실패 시 400.

**테스트 케이스:** ADMIN 계정으로 전체 happy path(생성→멤버 배정→오버라이드→상세 조회 반영 확인), 비ADMIN 403(기존 admin IT의 접근 통제 테스트 관용구), 타 조직 소속 멤버 추가 409, 없는 featureKey 400.
(주의: FeatureKey enum이 비어 있으므로 오버라이드 happy path IT는 배선 전엔 불가 — 없는 키 400 케이스만 IT로, 저장 로직은 repository 단위 IT에서 임의 문자열 키로 검증.)

**Steps:** 실패 테스트 → 구현 → PASS → 커밋 `feat(was): 어드민 조직 관리 API`.

### Task 4: 조직 셀프서비스 API

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/org/OrgController.java` + `OrgService.java` + 응답 record들
- Modify: `was/src/main/java/com/celfit/was/config/SecurityConfig.java` — `/v1/org/**`가 기존 `anyRequest().authenticated()`에 포섭되는지 확인만, 이미 포섭이면 무수정
- Test: `OrgControllerIT`

**계약 (스펙 §조직 셀프서비스):**
- 모든 핸들러 진입 시 요청자 멤버십을 **DB에서 조회**(세션 불신). 무소속 → 404.
- `GET /v1/org` 멤버 누구나: 조직명·plan·계약기간·자기 orgRole
- `GET /v1/org/members` 멤버 누구나: 멤버 목록(userId, email/nickname 등 기존 노출 컨벤션, orgRole)
- `POST /v1/org/members` {email, orgRole} ORG_ADMIN만: email 정확 일치 기존 계정 → 추가. 계정 없음 404, 타 조직 소속 409. MEMBER가 호출 → 403 `NOT_ORG_ADMIN`
- `PATCH /v1/org/members/{userId}` {orgRole} / `DELETE /v1/org/members/{userId}` ORG_ADMIN만. 대상이 자기 조직 소속 아니면 404. 마지막 ORG_ADMIN이 자기 자신을 강등/제거하는 것 허용.

**테스트 케이스:** ORG_ADMIN happy path(조회·추가·역할변경·제거), MEMBER의 관리 행위 403, 무소속 404, 타 조직 유저 관리 시도 404(자기 조직 스코프 밖), 이미 소속된 유저 추가 409, 비인증 401.

**Steps:** 실패 테스트 → 구현 → PASS → 커밋 `feat(was): 조직 셀프서비스 API(/v1/org)`.

### Task 5: 마무리 검증

- [ ] `./gradlew :was:test` 전체 1회 (DOCKER_HOST 확인)
- [ ] `deploy/scripts/check-migration-safety.sh` 통과 확인(전부 additive라 통과해야 정상)
- [ ] 커밋 `docs: 조직 entitlement 스펙·플랜 문서` (스펙+플랜, 아직 미커밋이면)
