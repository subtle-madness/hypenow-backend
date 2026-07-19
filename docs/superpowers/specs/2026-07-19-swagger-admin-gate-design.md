# Swagger admin 게이트 설계

> 상태: 🟢 활성 · 설계 확정(2026-07-19)

## 목적

운영(prod)에서 꺼져 있는 Swagger(springdoc)를 다시 켜되, admin 계정으로 로그인한
사용자만 볼 수 있게 한다. 2026-07-17의 "운영 미노출" 결정을 "운영 노출 + admin 게이트"로 대체한다.

## 배경·현황

- prod는 `was/src/main/resources/application-prod.yml`에서 springdoc를 비활성화해 문서 자체가 생성되지 않는다.
- `SecurityConfig`는 `/swagger-ui/**`, `/v3/api-docs/**`를 `permitAll`로 열어둔 상태(로컬·개발 전제).
- 권한 체계가 없다 — `users` 테이블에 role 컬럼이 없고 `AppUserDetails.getAuthorities()`는 항상 빈 컬렉션.
- 프론트 로그인 세션은 www.hypenow.io 경유(Vercel rewrite)라 `api.hypenow.io/swagger-ui`에
  직접 접속하면 세션 쿠키가 붙지 않는다 → 스웨거는 자체 인증 진입(HTTP Basic)이 필요.

## 결정 사항

- **인증 방식**: HTTP Basic 팝업. 별도 로그인 페이지 없이 브라우저 기본 팝업으로 ID/PW를 받고,
  기존 DaoAuthenticationProvider(users 테이블 + BCrypt)를 그대로 탄다. HTTPS 전제라 전송 안전.
- **admin 계정**: `users.role` 컬럼(USER/ADMIN) 기반 정식 권한 체계. 향후 어드민 기능에도 재사용.
- **적용 범위**: 모든 환경 공통 게이트. prod만 분기하면 로컬에서 안 잡히는 갭이 생기므로
  로컬·개발도 admin 계정으로 접근한다(로컬 DB에 admin 시드 1회 필요).

## 설계

### 1. 권한 체계

- Flyway `V7__users_role.sql` (app 스키마):
  `ALTER TABLE users ADD COLUMN role text NOT NULL DEFAULT 'USER'` + `CHECK (role IN ('USER','ADMIN'))`.
- `AppUserDetails.getAuthorities()`가 `ROLE_<role>` 권한 하나를 반환하도록 변경
  (조회 쿼리·record에 role 필드 추가).
- 승격은 수동 SQL: `UPDATE users SET role='ADMIN' WHERE email=...`.
  현재 운영 users 0건·가입 차단 중 — 가입 코드 개통 후 가입→승격하거나 bcrypt 해시로 직접 INSERT.

### 2. 스웨거 전용 SecurityFilterChain

- `@Order(1)` 체인 신설: `securityMatcher("/swagger-ui/**", "/v3/api-docs/**")`.
  - 전 요청 `hasRole("ADMIN")`.
  - `httpBasic` 활성 — 미인증 시 `WWW-Authenticate`로 브라우저 팝업.
  - CSRF 비활성(GET 전용 문서 표면).
- 기존 메인 체인(세션 쿠키 + 401 JSON envelope)은 스웨거 경로 `permitAll` 한 줄 제거 외 무변경.

### 3. prod 노출

- `application-prod.yml`의 springdoc 비활성 블록 제거 → prod에서도 문서 생성, 접근은 게이트가 통제.
- `OpenApiConfig`·prod yml의 "prod 미노출" 주석 갱신.

### 4. 테스트

- `OpenApiDocsIntegrationTest` 갱신:
  - 익명 → 401 (+ `WWW-Authenticate` 헤더).
  - 일반 USER Basic → 403.
  - ADMIN Basic → 200 + 기존 paths-to-match(/v1 표면만) 검증 유지.
- 테스트용 USER/ADMIN 계정은 통합 테스트 픽스처로 시드.

## 에러 처리

- 스웨거 체인의 미인증은 Basic 기본 401(팝업 유도) — /v1 JSON envelope 규칙은 이 표면에 적용하지 않는다.
- 인증됐지만 ADMIN이 아니면 403.

## 운영 반영 절차 (코드 외)

1. 배포 후 Flyway V7 자동 적용 확인.
2. admin 계정 준비(가입→승격 또는 직접 INSERT).
3. `https://api.hypenow.io/swagger-ui` 접속 → Basic 팝업 → admin 로그인 → 문서 확인.
