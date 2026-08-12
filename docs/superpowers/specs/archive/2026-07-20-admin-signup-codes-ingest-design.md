# 가입 코드 일괄 적재 API 설계 (어드민 → 백엔드)

> 상태: ✅ 구현됨(2026-07-20)

## 목적

어드민이 클라에서 생성한 초대(가입) 코드 배열을 HTTP로 일괄 전송해 `app.signup_codes`에
미사용·활성 상태로 저장한다. 기존 `deploy/scripts/generate-signup-codes.sh`(서버 CLI 생성)를
어드민 UI 발급으로 옮기는 것 — 생성은 클라, 저장만 백엔드.

## 배경·현황

- `app.signup_codes`(V8): `code`(PK) · `channel` **NOT NULL**(발급 채널, 분석·추적용) ·
  `used_by`(→users, ON DELETE SET NULL) · `used_at`(소진 정본) · `created_at`. 만료일·최대횟수 컬럼 **없음** —
  코드는 **1회용**(`claim()`이 `used_at` 스탬프로 원자 소진).
- 기존 생성 스크립트는 `<CHANNEL>-XXXX`(혼동문자 0/O/1/I 제외 32자 알파벳)를
  `INSERT ... (code, channel) ON CONFLICT (code) DO NOTHING`으로 넣는다 — 채널을 CLI 인자로 명시.
- **인증 충돌 주의**: PR #68로 `/admin/**`는 이미 `@Order(1)` **사람용 ADMIN HTTP-Basic 체인**
  (`adminBasicFilterChain`, `hasRole("ADMIN")`)이 잡는다. 정적 `Bearer` 토큰은 이 체인에서 401로 튕긴다.
  → 쓰기 경로만 **더 높은 우선순위(@Order(0)) 토큰 인증 체인**으로 분리해야 한다.
- 어드민이 실제 보내는 계약: `POST /admin/signup-codes`, `Authorization: Bearer <CODES_API_KEY>`,
  body `{ "codes": ["THREADS-7XG3", ...] }`, 성공 `{ "inserted": N }`.

## 결정 사항 (프론트 확답 반영 2026-07-20)

- **경로**: `POST /admin/signup-codes` (어드민 요청 그대로 유지).
- **인증**: 정적 토큰. env `CODES_API_KEY`(다른 시크릿 RESEND/APIFY/GEMINI와 동일하게 env, DB/app_setting 아님).
  `Authorization: Bearer <token>` 상수시간 비교. **fail-closed**: 키 미설정이면 전부 거부.
  사람용 Basic 체인과 별개 — `/admin/signup-codes` 전용 `@Order(0)` 체인.
- **channel**: 코드 접두사에서 유도(`THREADS-7XG3` → `THREADS`). body에 별도 channel 필드 없음.
  **접두사 없는 코드(첫 `-` 앞이 비었거나 `-` 자체가 없음)는 거부(400)** — 추적 무결성.
- **중복 처리**: `ON CONFLICT (code) DO NOTHING` — 기존(소진분 포함) 코드는 스킵, 신규만 저장.
  전체 실패로 막지 않음. **응답 `{ "inserted": N, "skipped": M }`**(skipped = 제출 수 − inserted).
- **코드 정책**: 만료·최대횟수 없음(1회용 유지, YAGNI). `used_by/used_at = NULL`로 저장 = 미사용·활성.
- **배치 상한**: 서버측에서 **≤500 강제**(클라 보장 불신). 빈 배열·빈 코드도 거부.
- **에러 형식**: 4xx/5xx + body `{ "error": "사유" }`(어드민이 본문 그대로 노출).

## 설계

### 1. 인증 — `@Order(0)` 토큰 체인 (SecurityConfig)

새 `SecurityFilterChain` bean(`signupCodeIngestFilterChain`, `@Order(0)`):

- `securityMatcher("/admin/signup-codes")` — Basic 체인(@Order(1) `/admin/**`)보다 먼저 매칭.
- stateless, CSRF off, CORS off(서버 대 서버 호출, 브라우저 오리진 아님).
- 커스텀 `CodesApiKeyAuthFilter`(OncePerRequestFilter)를 `UsernamePasswordAuthenticationFilter` 앞에 추가:
  - `Authorization: Bearer <token>` 파싱 → 설정된 `codes.api-key`와 `MessageDigest.isEqual`(상수시간) 비교.
  - 키 미설정(blank)이면 **503** `{"error":"CODES_API_KEY 미설정"}`(fail-closed, 오설정 구분).
  - 일치하면 인증 토큰을 SecurityContext에 세팅(권한 무관, `authenticated()` 통과용).
  - 불일치·헤더 없음이면 인증 미설정 → 아래 진입점이 401.
- `authorizeHttpRequests(anyRequest().authenticated())`.
- 미인증 진입점: 401 + `{"error":"인증 실패"}`(JSON, Basic 챌린지 없음).

`codes.api-key`는 `application.yml`에 `codes.api-key: ${CODES_API_KEY:}` 추가(빈 기본값 → 로컬·테스트 fail-closed).

### 2. 조회·저장 — `com.celfit.was.admin`

- **`SignupCodeCreateRequest`** (record): `List<String> codes`.
- **`SignupCodeCreateResponse`** (record): `int inserted, int skipped`.
- **`AdminSignupCodeService`** (`@Service`, `@Transactional`):
  1. 검증: `codes` null·빈 → 400; size > 500 → 400; 각 코드 trim 후 빈 → 400;
     각 코드가 `^[^\s-]+-[^\s-]+$`(접두사·서픽스 모두 non-empty, 공백/추가 `-` 불가) 불일치 → 400.
     검증 실패는 `AdminApiException(400, msg)`로 던져 **하나라도 틀리면 0건 저장**(all-or-nothing 검증).
  2. 각 코드에서 channel = 첫 `-` 앞 부분(verbatim, 대소문자 변형 없음).
  3. 리포지토리로 코드별 삽입, `inserted` 합산. `skipped = codes.size() - inserted`.
  4. `SignupCodeCreateResponse` 반환.
- **`AdminSignupCodeRepository`** (JdbcClient):
  `int insert(String code, String channel)` —
  `INSERT INTO app.signup_codes (code, channel) VALUES (:code, :channel) ON CONFLICT (code) DO NOTHING`
  의 `.update()`(0=중복 스킵, 1=신규). 트랜잭션은 서비스가 소유.
  (≤500건 admin 트리거라 코드별 단건 INSERT 루프로 충분 — 동적 다중-VALUES SQL 회피, 가독성 우선.)
- **`AdminSignupCodeController`** (`@RestController`): `@PostMapping("/admin/signup-codes")` →
  서비스 호출, `SignupCodeCreateResponse` 반환(Jackson).

### 3. 에러 렌더링 — `{"error": "..."}`

- `AdminApiException(int status, String message)` + 어드민 패키지 스코프 `@RestControllerAdvice`
  (`@ExceptionHandler(AdminApiException)` → `ResponseEntity.status(status).body(Map.of("error", message))`).
  기존 read 엔드포인트(`AdminSignupController`)엔 영향 없음(예외 안 던짐).
- 필터 단계 401·503은 진입점/필터가 직접 `{"error":...}` 본문을 쓴다(advice는 컨트롤러 도달 후만 작동).

### 4. 테스트 (`IntegrationTest` + `@AutoConfigureMockMvc`, Testcontainers)

메인 클래스는 `@TestPropertySource(properties = "codes.api-key=test-secret-xxx")`로 키 주입:

- 인증: 헤더 없음 → 401 `{"error":...}`; 틀린 토큰 → 401.
- 성공: 올바른 Bearer + `["THREADS-7XG3","DM-Q2MR"]` → 200 `{inserted:2, skipped:0}`,
  DB에 channel=THREADS/DM으로 저장 확인.
- 중복: 기존 코드 1건 시드 후 그 코드+신규 2건 제출 → `{inserted:2, skipped:1}`.
- 검증 400 + 0건 저장: 접두사 없는 코드(`"NOPREFIX"`, `"-XXXX"`) 포함 → 400 `{"error":...}`,
  같은 배치의 정상 코드도 저장 안 됨(all-or-nothing).
- 배치 상한: 501개 → 400. 빈 배열 → 400.
- fail-closed: 별도 클래스 `@TestPropertySource(properties = "codes.api-key=")`(빈 키) → 503 `{"error":...}`.

시드 코드·값은 `IntegrationTest` 싱글턴·무롤백 DB 대비 **UUID로 유니크화**(기존 AdminSignupIntegrationTest 관용구).

## 스코프에서 뺀 것 (YAGNI)

- 만료일·최대 사용횟수 정책·컬럼 — 1회용 모델 유지(요청 시 별도 마이그레이션).
- body의 별도 channel 필드 — 접두사 유도로 충분.
- 코드 생성(백엔드) — 생성은 클라 담당, 백엔드는 저장만. `generate-signup-codes.sh`는 대체 수단으로 잔존.
- 사람용 Basic과의 통합 — 기계 적재는 정적 토큰이 적합, 체인 분리 유지.

## 파일 변경 요약

| 파일 | 변경 |
|---|---|
| `was/.../config/SecurityConfig.java` | `@Order(0)` `signupCodeIngestFilterChain` 신설(토큰 체인) |
| `was/.../admin/CodesApiKeyAuthFilter.java` | 신설 — Bearer 토큰 상수시간 검증·fail-closed |
| `was/.../admin/AdminSignupCodeController.java` | 신설 — `POST /admin/signup-codes` |
| `was/.../admin/AdminSignupCodeService.java` | 신설 — 검증·channel 유도·삽입·집계 |
| `was/.../admin/AdminSignupCodeRepository.java` | 신설 — ON CONFLICT DO NOTHING 단건 삽입 |
| `was/.../admin/SignupCodeCreateRequest.java` / `SignupCodeCreateResponse.java` | 신설 — 요청·응답 record |
| `was/.../admin/AdminApiException.java` + advice | 신설 — `{"error":...}` 렌더링 |
| `was/src/main/resources/application.yml` | `codes.api-key: ${CODES_API_KEY:}` 추가 |
| `was/src/test/java/.../AdminSignupCodeIngestIntegrationTest.java` (+ 빈키 클래스) | 신설 — 인증·검증·중복·상한·fail-closed |

## 운영 반영(배포 시)

1. 랜덤 토큰 발급(예: `openssl rand -hex 32`) → 운영 was 컨테이너 env `CODES_API_KEY`에 설정(재기동).
2. 같은 값을 어드민 env `CODES_API_KEY`에 채우고 `CODES_API_MOCK=false`.
3. 스모크: `curl -H "Authorization: Bearer $CODES_API_KEY" -d '{"codes":["SMOKE-TEST"]}' .../admin/signup-codes` → `{inserted:1,skipped:0}` 후 정리.
