# 가입 코드 발송 여부(is_sent) 칼럼 + 변경 API 설계

> 상태: 🟢 활성

## 목적

어드민 FE에서 각 가입 코드가 대상자에게 **발송(전달)됐는지**를 표시·변경할 수 있게 한다.
`app.signup_codes`에 `is_sent` 칼럼을 추가하고, FE가 체크/해제하는 PATCH 엔드포인트를 제공한다.

## 배경·현황

- `app.signup_codes`(V8): `code`(PK) · `channel` · `used_by` · `used_at`(소진 정본) · `created_at`.
  발송 추적 칼럼은 없다 — 소진(`used_at`)은 가입에 쓰였는지이지, 코드를 전달했는지가 아니다.
- 어드민 API 인증 체인 2종: `@Order(0)` 토큰 체인은 매처가 정확히 `/admin/signup-codes`
  (하위 경로 미포함, 기계용 일괄 적재), `@Order(1)` ADMIN Basic 체인이 `/admin/**` 전체(사람용).
  → `PATCH /admin/signup-codes/{code}`는 **SecurityConfig 무수정으로 Basic 체인**에 떨어진다.
- Swagger(springdoc)는 `paths-to-match: /v1/**`라 어드민 API가 문서화되지 않는 상태.

## 결정 사항

- 칼럼 형태: **`is_sent boolean NOT NULL DEFAULT false`** (타임스탬프 아님 — 발송 시각 추적은 YAGNI).
- API 동작: **양방향 설정**(멱등 PATCH) — 체크/해제 모두 가능, 실수 복구 허용.
- 인증: **ADMIN Basic** (어드민 대시보드 FE가 호출).
- Swagger: `paths-to-match`에 `/admin/**` 추가 — 어드민 API 전체 노출(접근은 기존 ADMIN 게이트가 통제).

## 변경 내역

### 1. DB 마이그레이션 — `was/src/main/resources/db/migration/app/V12__signup_codes_is_sent.sql`

```sql
ALTER TABLE app.signup_codes ADD COLUMN is_sent boolean NOT NULL DEFAULT false;
```

기존 행은 전부 `false`(미발송)로 시작.

### 2. 변경 API — `PATCH /admin/signup-codes/{code}`

- 요청 바디: `{"isSent": true}` (boolean 필수)
- 응답: `200 {"code": "...", "isSent": true}` / 코드 없으면 `404 {"error": "..."}`
- 위치: 사람용 `AdminSignupController`에 추가(기계용 `AdminSignupCodeController`와 분리 유지).
- 리포지토리: `AdminSignupRepository.updateIsSent(code, isSent)` —
  `UPDATE app.signup_codes SET is_sent = :isSent WHERE code = :code`, 반환 0이면 404.
- 404는 기존 `AdminApiException`/`AdminApiExceptionAdvice` 경로 재사용 —
  단, 어드바이스가 `assignableTypes = AdminSignupCodeController.class`로 잠겨 있으므로
  `AdminSignupController`를 assignableTypes에 추가한다(둘 다 어드민 쓰기 표면).

### 3. 조회 반영 — `GET /admin/signups`

`SignupUsageRow`에 `isSent` 필드 추가, `AdminSignupRepository.findAll()` SELECT에 `sc.is_sent` 포함.
FE가 현재 발송 상태를 표시하는 데 사용.

### 4. Swagger — `was/src/main/resources/application.yml`

`springdoc.paths-to-match`를 `/v1/**, /admin/**`로 확장. Swagger UI·스키마 접근은
이미 ADMIN Basic 체인으로 잠겨 있어 보안 변화 없음. try-it-out도 Basic 팝업으로 동작.

## 에러 처리

- 존재하지 않는 코드 → 404 (UPDATE 반환 0 판정).
- `isSent` 누락/비boolean 바디 → 400 (Jackson 역직렬화 실패, 기존 어드바이스 경로).
- 미인증 → 401, ADMIN 아님 → 403 (기존 Basic 체인 동작 그대로).

## 테스트

기존 `AdminSignupIntegrationTest` 패턴(Testcontainers + Basic 인증)을 따른다:

- PATCH로 `true` 설정 → `GET /admin/signups` 응답에 `isSent: true` 반영 확인, 다시 `false`로 되돌리기(멱등·양방향).
- 없는 코드 PATCH → 404.
- 미인증 PATCH → 401 (토큰 체인이 아닌 Basic 체인에 떨어지는지 확인).

## 범위 밖

- 발송 시각·발송자 추적(칼럼은 boolean만).
- 일괄 발송 표시 API(단건 PATCH만 — 필요해지면 후속).
- FE 구현(이 스펙은 백엔드만).
