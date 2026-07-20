# 가입 코드↔유저 조회(관리자) 설계

> 상태: ✅ 구현됨(2026-07-19) · 설계 확정(2026-07-19)

## 목적

"어떤 유저가 어떤 코드로 회원가입했는지"를 관리자가 브라우저에서 반복 열람할 수 있게 한다.
데이터는 이미 `app.signup_codes`(V8)에 저장되고 있으므로 **저장을 추가하는 작업이 아니라
기존 매핑을 관리자 표면으로 노출**하는 작업이다.

## 배경·현황

- 가입 트랜잭션은 [`SignupService.register`](../../../was/src/main/java/com/celfit/was/v1/account/SignupService.java)에서
  `signupCodeRepository.claim(code, userId)`로 코드를 원자 선점하며, 이때
  `app.signup_codes.used_by`(=`users.id`)와 `used_at`을 스탬프한다. 즉 코드↔유저 매핑은 이미 정본으로 존재한다.
- `app.signup_codes` 스키마(V8):
  `code`(PK) · `channel`(발급 채널 THREADS/DM/LANDING) · `used_by`(→`app.users.id`, `ON DELETE SET NULL`) ·
  `used_at`(소진 시각) · `created_at`.
- **권한 인프라는 이미 있다**(swagger-admin-gate, 2026-07-19 머지):
  - `V11__users_role.sql` — `app.users.role`(USER/ADMIN).
  - `AppUserDetails.getAuthorities()` → `ROLE_<role>`.
  - `SecurityConfig`의 `@Order(1)` 체인이 `hasRole("ADMIN")` + HTTP Basic(STATELESS, 매 요청 재인증)으로
    스웨거 경로를 잠근다. 이 체인을 그대로 재사용한다 — **별도 크리덴셜·필터 체인을 새로 만들지 않는다.**

## 결정 사항

- **인증**: 기존 `@Order(1)` ADMIN Basic 체인의 `securityMatcher`에 `/admin/**`를 추가하는 것으로 끝.
  새 엔드포인트는 자동으로 `hasRole("ADMIN")` + Basic 팝업으로 잠긴다. 매 요청 Basic 재인증이라
  강등이 즉시 반영되는 STATELESS 신선도 불변식을 그대로 물려받는다.
  - 이 체인은 이제 스웨거만이 아니라 **모든 admin Basic 표면**을 담당하므로 bean 이름을
    `swaggerFilterChain` → `adminBasicFilterChain`으로 바꾸고 주석을 일반화한다(작업 중인 코드의 국소 정리).
  - 별도 app_setting 크리덴셜(초안의 A안)은 **폐기** — 기존 role 게이트와 중복이라 채택하지 않는다.
- **표면**: `GET /admin/signups` — raw JSON 배열(/v1 envelope 아님, 관리자 전용 내부 표면).
- **응답 범위**: 소진 코드(누가 썼는지)와 미소진 코드(아직 빈 코드)를 **한 조회로** 반환.
  `signup_codes LEFT JOIN users`로 미소진·탈퇴(FK가 SET NULL) 행은 `email`/`userId`가 null.
  정렬은 `used_at DESC NULLS LAST`(최근 가입이 위, 미사용 코드가 아래).
- **경계 준수(CLAUDE.md)**: was는 app 스키마만 읽는다. 조회는 JdbcClient, DTO는 record + 정적 `from()`.

## 설계

### 1. 인증 — SecurityConfig 한 줄 확장

`@Order(1)` 체인의 매처에 `/admin/**` 추가:

```java
.securityMatcher("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/v3/api-docs.yaml", "/admin/**")
```

- `authorizeHttpRequests(anyRequest().hasRole("ADMIN"))`, `httpBasic`, CSRF off, STATELESS는 무변경(그대로 상속).
- bean 이름·주석만 admin 일반 표면으로 갱신. 메인 `@Order(2)` 체인은 무변경(/admin은 Order(1)이 먼저 매칭).

### 2. 조회 — `com.celfit.was.admin` 새 패키지

- **`SignupUsageRow`** (record):
  `code` · `channel` · `email`(nullable) · `userId`(nullable Long) · `usedAt`(nullable OffsetDateTime).
  정적 `from()`은 JdbcClient row 매핑용(또는 `query(SignupUsageRow.class)` 매핑 규약에 맞춰 생성자 매핑).
- **`AdminSignupRepository`** (JdbcClient):

  ```sql
  SELECT sc.code, sc.channel, u.email, sc.used_by AS user_id, sc.used_at
  FROM app.signup_codes sc
  LEFT JOIN app.users u ON u.id = sc.used_by
  ORDER BY sc.used_at DESC NULLS LAST, sc.code
  ```

  `List<SignupUsageRow>` 반환.
- **`AdminSignupController`** — `@GetMapping("/admin/signups")` → repository 결과를 그대로 반환(Jackson 직렬화).

### 3. 운영 세팅

- 새 크리덴셜 시드 **없음.** 기존 유저 하나를 승격:
  `UPDATE app.users SET role='ADMIN' WHERE email='<관리자 이메일>';`
  (swagger-admin-gate가 이미 쓰는 관용구 — 운영 users가 0건이면 가입 코드로 가입 후 승격.)
- 열람: `https://api.hypenow.io/admin/signups` 접속 → 브라우저 Basic 팝업에 ADMIN 유저 이메일/비번 입력.
  HTTPS 전제라 Basic 전송 안전.

### 4. 테스트

기존 `SignupCodeIntegrationTest`(Testcontainers) 패턴을 따른다:

- Basic 없음 → 401.
- ADMIN 아닌 유저 Basic → 403(또는 체인 정책에 따른 401/403 — 실제 응답으로 확정).
- ADMIN 유저 Basic → 200 + 소진 코드가 이메일·userId 채워 반환.
- 미소진 코드는 `email`/`userId` null로 함께 반환되고, 정렬이 `used_at DESC NULLS LAST`인지 확인.

## 스코프에서 뺀 것 (YAGNI)

- app_setting 별도 admin 크리덴셜(초안 A안) — 기존 role 게이트와 중복.
- HTML 화면·채널별 집계·페이지네이션 — 닫힌 베타 규모라 raw JSON으로 충분.
- role 체계 신설 — 이미 존재(V11).

## 파일 변경 요약

| 파일 | 변경 |
|---|---|
| `was/.../config/SecurityConfig.java` | `@Order(1)` 매처에 `/admin/**` 추가, bean명·주석 일반화 |
| `was/.../admin/AdminSignupController.java` | 신설 — `GET /admin/signups` |
| `was/.../admin/AdminSignupRepository.java` | 신설 — JdbcClient LEFT JOIN 조회 |
| `was/.../admin/SignupUsageRow.java` | 신설 — 응답 record |
| `was/src/test/java/.../AdminSignupIntegrationTest.java` | 신설 — 인증·정렬·null 케이스 |
