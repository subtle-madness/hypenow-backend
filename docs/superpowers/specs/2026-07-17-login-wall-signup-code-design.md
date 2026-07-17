# 로그인 월 + 가입 코드 설계

> 상태: 🟢 활성

2026-07-17 제품 구조 변경(사용자 확정): **모든 조회는 로그인 사용자만**, **가입은 코드가 있어야만**.
베타 단계에서 데이터 노출을 닫고 초대 기반으로 가입을 통제한다.

사용자 결정 4건(07-17 세션): 단일 공용 코드 / 전부 잠금(구 /api·내부 페이지 포함) /
gate 이벤트 익명 유지 / 프론트 계약은 기본값(`signupCode`·403 `INVALID_SIGNUP_CODE`).
추가 확정: 레거시 `/api/auth/signup` 폐쇄, dev 한정 내부 페이지 예외는 넣지 않음.

## 1. 로그인 월 — SecurityConfig 화이트리스트 전환

현행 블랙리스트(보호 경로 나열 + `anyRequest().permitAll()`)를 뒤집어
**기본 `authenticated()` + 열린 경로만 나열**로 바꾼다. 새 엔드포인트가 생겨도 기본이 잠김이라
실수로 새는 일이 없다.

**열린 경로(전체 목록 — 이 외는 전부 인증 필수)**:

| 경로 | 이유 |
|---|---|
| `/v1/auth/**` | 가입·로그인·로그아웃 — 인증의 입구 |
| `/v1/events/gate` | 익명 게이트 측정 유지(원 목적이 비로그인 사용자 측정). CSRF 면제도 그대로 |
| `/health` | 배포 헬스체크가 익명 curl |
| `/swagger-ui/**`, `/v3/api-docs/**` | 로컬·개발 전용 문서(prod에선 springdoc 자체 비활성 — PR #26) |

**잠기는 경로(대표)**: /v1 읽기 4종(contents·ai-report·influencers·ai-report), 구 `/api/*` 전부,
내부 페이지(`/` 대시보드·`/posts/*`·`/coverage`), `/profile-images/**`(프론트는 Vercel rewrite
동일 오리진이라 쿠키가 실려 문제없음).

**401 계약은 기존 그대로** — `/v1/*`는 envelope JSON(`UNAUTHORIZED` "로그인이 필요합니다.",
`V1AwareAuthenticationEntryPoint`), 그 외는 빈 401.

**레거시 `/api/auth/signup` 폐쇄** — email+password만으로 계정을 만드는 뒷문이 되므로 엔드포인트를
제거한다(프론트는 /v1 전환 완료). 레거시 login·logout은 남긴다(잠금과 무관, 무해).
단 잠금 기본값 때문에 레거시 login도 열어두려면 화이트리스트에 넣어야 하는데 — **넣지 않는다**:
레거시 표면은 전부 잠그고, 인증 입구는 /v1/auth로 일원화한다.

**개인화 필드**: 읽기 API가 인증 필수가 되면 P1의 Optional 필드(isContentsSaved 등)는 항상 채워진다
— 스펙 2절 Optional 규약이라 계약 변경은 아니고, 별도 코드 수정 없음.

**로컬 개발 영향(감수)**: 대시보드·coverage를 열 때도 로그인 필요. curl로 세션 쿠키를 받아
브라우저에 붙이거나 프론트 로컬에서 로그인해 쓴다. dev 프로파일 예외는 두지 않는다(단순 유지).

## 2. 가입 코드 — `app.app_setting` 단일 공용 코드

- **저장**: was 소유 Flyway `V6__app_setting.sql` — `app.app_setting(key text PK, value text NOT NULL)`
  신설 + `signup.code` 행 시드. 초기값은 placeholder로 넣고 **배포 후 운영자가 UPDATE로 실코드 설정**.
  raw DB의 `app_setting`과 이름은 같지만 was는 raw 접근 금지라 app 스키마에 별도로 두는 것 —
  키 컨벤션(런타임 설정 = app_setting)을 was 층에 재적용한 것이지 경계 위반이 아니다.
- **검증 흐름**: `POST /v1/auth/signup`에서 레이트리밋 통과 후 **가장 먼저** 코드 대조
  (양쪽 trim 후 정확 일치, 대소문자 구분). 불일치 → **403 `INVALID_SIGNUP_CODE`
  "가입 코드를 확인해 주세요."** 매 요청 DB SELECT — 캐시 없이 항상 최신이라 교체 즉시 반영.
- **Fail-closed**: `signup.code` 행이 없거나 값이 비면 가입 전면 차단(같은 403) + 에러 로그.
  설정 실수로 검증이 무력화되는 쪽보다 차단이 안전.
- **요청 계약**: `SignupRequest`에 `signupCode` 필드 추가. 기존 15필드 검증은 그대로.
  검증 순서: 레이트리밋(429) → 가입 코드(403) → 필드 검증(400) → 중복 이메일(409).

## 3. 테스트

- **로그인 월 계약 테스트**: 잠긴 경로 대표(읽기 4종 중 1 + 내부 페이지 1 + /api 1) 익명 401
  (/v1은 envelope 검증), 열린 경로 4종 익명 통과.
- **가입 코드 테스트**: 정상 코드 201 / 불일치 403 INVALID_SIGNUP_CODE / 미설정(행 삭제) 403.
- **기존 테스트 보강**: 익명으로 읽기 API를 치던 테스트들이 401을 맞는다 — `@WithMockUser` 또는
  세션 로그인 셋업 일괄 추가. 이번 작업에서 잔손이 가장 많은 부분.
- 레거시 signup 테스트는 폐쇄에 맞춰 삭제/수정.

## 4. 운영 절차

1. 머지·배포 후 Flyway V6가 `app.app_setting`을 만든다(placeholder 코드).
2. 운영 DB에서 `UPDATE app.app_setting SET value='<실코드>' WHERE key='signup.code';`
3. 코드 로테이션도 같은 UPDATE — 재기동 불필요(매 요청 조회).
4. 프론트가 가입 폼에 `signupCode` 필드를 실어 보내야 가입 가능 — 프론트 배포와 순서 조율
   (백엔드 먼저 배포하면 그 시점부터 코드 없는 가입은 403).

## 5. 영향 범위

- `SecurityConfig`(화이트리스트 전환), `V1AuthController`·`SignupRequest`·`SignupValidator`(코드 검증),
  `AuthController`(레거시 signup 제거), Flyway `V6`, 신규 `AppSettingRepository`(JdbcClient 관용구).
- ARCHITECTURE §3(app 스키마 표에 app_setting)·§7(결정 기록) 갱신.
- PR #25(Error Prone)·#26(Swagger)와 브랜치 병존 — SecurityConfig 접점은 서로 다른 라인이라
  충돌 없거나 사소함.
