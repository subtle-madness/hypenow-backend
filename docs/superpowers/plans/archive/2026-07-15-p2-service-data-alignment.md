# P2: 서비스 데이터 정렬 (트랙 G 확장) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 구현/실행/반영됨 · 근거 스펙: [specs/2026-07-15-hypenow-api-spec-alignment-design.md §5](../../specs/2026-07-15-hypenow-api-spec-alignment-design.md)

**Goal:** 기존 트랙 G(Spring Security 인증·저장 2종)를 프론트 스펙 v1 계약(6.6~6.16, 6.19)으로 확장한다 — Spring Session JDBC 세션(목록·개별 로그아웃·`hypenow-session` 30일 슬라이딩), users 프로필 필드 15종, `/v1/auth·me·saved-*·events` envelope 표면, P1 응답의 개인화 필드 활성화.

**Architecture:** 인증 파이프라인(Spring Security·BCrypt·CSRF)은 그대로 두고 세션 저장소만 HttpSession(인메모리)→Spring Session JDBC(`app` 스키마)로 교체. v1 표면은 기존 `v1.common` envelope 재사용, 구 `/api/auth`·`/api/saved`는 병존. 저장 목록의 Content 카드는 app 스키마 조회 → analysis 조회 → **Java 조합**(SQL 교차 조인 금지, §4-4). 배포 구도가 Vercel rewrite 동일 오리진이므로 쿠키에 Domain 속성 불필요, SameSite=Lax.

**Tech Stack:** Spring Session JDBC / Spring Security(기존) / JdbcClient / Flyway(app 스키마, was 소유) / MockMvc·Testcontainers

**작업 위치:** worktree `.worktrees/api-spec`, 브랜치 `feat/p2-service-data` (origin/develop = PR #20 머지 후 기준 — 이미 생성됨).

**전역 규칙(P1과 동일):** 한국어 주석·커밋, record DTO, JdbcClient, Jackson 3(ObjectMapper는 `tools.jackson.*`, 어노테이션은 `com.fasterxml.jackson.annotation`), 텍스트 블록 조각 결합은 빈 첫 줄로, `@WebMvcTest`는 기존 v1 테스트의 @Import(SecurityConfig + `was.cors.allowed-origins` 프로퍼티) 구성. 에러는 스펙 3.2 표(코드·한국어 message).

**스펙 제외 항목:** 6.17 이메일 인증(스펙 [TBD] — 라우트도 만들지 않음), 프론트 개발용 마스터 비밀번호 백도어(승계 금지).

---

### Task 1: Spring Session JDBC 전환 — 세션 영속화 + `hypenow-session` 쿠키

**Files:**
- Modify: `was/build.gradle` (+`implementation 'org.springframework.session:spring-session-jdbc'`)
- Create: `was/src/main/resources/db/migration/app/V2__spring_session.sql`
- Modify: `was/src/main/resources/application.yml`
- Modify: `was/src/main/resources/application-prod.yml` (Secure 쿠키)
- Modify: `was/src/main/java/com/celfit/was/auth/AuthController.java` (로그인 시 UA 파싱 세션 attribute)
- Create: `was/src/main/java/com/celfit/was/auth/UserAgentParser.java`
- Test: `was/src/test/java/com/celfit/was/auth/UserAgentParserTest.java`
- Test: 기존 인증 통합 테스트(CsrfCookieFlowIntegrationTest 등)가 Spring Session 위에서도 통과하는지 확인·필요 시 수정

- [ ] **Step 1: 의존성·설정 추가**

```yaml
# application.yml에 추가
spring:
  session:
    jdbc:
      initialize-schema: never          # DDL은 Flyway(app 스키마)가 소유
      table-name: app.spring_session
    timeout: 30d                        # 비활동 30일 = 슬라이딩 만료 (스펙 4절 합의)
server:
  servlet:
    session:
      cookie:
        name: hypenow-session           # 스펙 4절 — 프론트 현재 동작과 일치
        http-only: true
        same-site: lax                  # Vercel rewrite 동일 오리진 구도(07-15 배포 결정) — Domain 불필요
        max-age: 30d                    # 영속 쿠키 (없으면 브라우저 종료 시 소멸)
        path: /
```

`application-prod.yml`에 `server.servlet.session.cookie.secure: true` 추가(기존 prod 설정 형식 유지).

- [ ] **Step 2: V2 마이그레이션 작성** — spring-session-jdbc 배포 jar의 `schema-postgresql.sql`을 app 스키마로 옮긴 표준 DDL:

```sql
-- was/src/main/resources/db/migration/app/V2__spring_session.sql
-- Spring Session JDBC 표준 스키마(schema-postgresql.sql)의 app 스키마 사본.
-- initialize-schema=never — 이 파일이 유일한 DDL 원천. 테이블명은 spring.session.jdbc.table-name과 일치.
CREATE TABLE app.spring_session (
    primary_id            CHAR(36) NOT NULL,
    session_id            CHAR(36) NOT NULL,
    creation_time         BIGINT NOT NULL,
    last_access_time      BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time           BIGINT NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON app.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON app.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON app.spring_session (principal_name);

CREATE TABLE app.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES app.spring_session (primary_id) ON DELETE CASCADE
);
```

주의: 구현 시 실제 사용하는 spring-session-jdbc 버전의 `schema-postgresql.sql`과 대조해 컬럼이 다르면 **jar 쪽을 정본**으로 맞춘다.

- [ ] **Step 3: UA 파싱 + 로그인 attribute** — 세션 목록 화면(6.14) 재료. 로그인 성공 직후 세션에 저장:

```java
/** User-Agent 1회 파싱 — 주요 패턴 매칭만(라이브러리 불요, 스펙 6.14의 browser/os 표기용). */
public final class UserAgentParser {

	private UserAgentParser() {
	}

	public static String browser(String ua) {
		if (ua == null) return "기타";
		if (ua.contains("Edg/")) return "Edge";
		if (ua.contains("Chrome/") && !ua.contains("Chromium")) return "Chrome";
		if (ua.contains("Firefox/")) return "Firefox";
		if (ua.contains("Safari/") && ua.contains("Version/")) return "Safari";
		return "기타";
	}

	public static String os(String ua) {
		if (ua == null) return "기타";
		if (ua.contains("Windows")) return "Windows";
		if (ua.contains("Mac OS X") && !ua.contains("iPhone") && !ua.contains("iPad")) return "Mac OS X";
		if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
		if (ua.contains("Android")) return "Android";
		if (ua.contains("Linux")) return "Linux";
		return "기타";
	}
}
```

`AuthController.login()` 성공 경로에 (v1 컨트롤러 신설 전이지만 세션 attribute 규약은 여기서 확정):

```java
HttpSession session = httpRequest.getSession(true);
String ua = httpRequest.getHeader("User-Agent");
session.setAttribute("session.browser", UserAgentParser.browser(ua));
session.setAttribute("session.os", UserAgentParser.os(ua));
```

- [ ] **Step 4: UserAgentParser 단위 테스트** — Chrome(Mac)/Safari(iPhone)/Edge(Windows)/null → 기대 라벨. 실행 PASS 확인.
- [ ] **Step 5: 기존 인증 흐름 회귀 확인** — `./gradlew :was:test` 전체. Spring Session 전환으로 기존 auth/saved 테스트(특히 세션 기반 통합 테스트)가 깨지면: Testcontainers 컨텍스트에 V2가 적용되는지(테스트 DDL 사본 방식이면 사본에 V2 추가), `@WebMvcTest` 슬라이스에는 세션 자동구성이 안 붙는지 확인 후 수정. 세션이 DB에 실제로 쌓이는지 통합 테스트 1건 추가(로그인 → `app.spring_session` 1행 + principal_name=email).
- [ ] **Step 6: Commit** — `feat(was): Spring Session JDBC 전환 — hypenow-session 쿠키·30일 슬라이딩·세션 영속화`

---

### Task 2: users 프로필 필드 + `/v1/auth` 표면 (스펙 6.15/6.16)

**Files:**
- Create: `was/src/main/resources/db/migration/app/V3__users_profile_fields.sql`
- Create: `was/src/main/java/com/celfit/was/v1/account/V1AuthController.java`
- Create: `was/src/main/java/com/celfit/was/v1/account/SignupRequest.java` (v1용 — 기존 auth.SignupRequest와 별개)
- Create: `was/src/main/java/com/celfit/was/v1/account/UserSummary.java`
- Create: `was/src/main/java/com/celfit/was/v1/account/SignupValidator.java`
- Create: `was/src/main/java/com/celfit/was/v1/account/RateLimiter.java`
- Modify: `was/src/main/java/com/celfit/was/auth/UserRepository.java` (프로필 insert/select 확장)
- Test: `was/src/test/java/com/celfit/was/v1/account/SignupValidatorTest.java`
- Test: `was/src/test/java/com/celfit/was/v1/account/V1AuthControllerTest.java`
- Test: `was/src/test/java/com/celfit/was/v1/account/RateLimiterTest.java`

- [ ] **Step 1: V3 마이그레이션**

```sql
-- 스펙 6.12/6.15 프로필·동의 필드. 기존 행(dev 테스트 계정뿐)은 기본값 백필.
ALTER TABLE app.users
    ADD COLUMN name                text        NOT NULL DEFAULT '',
    ADD COLUMN nickname            text,
    ADD COLUMN user_type           text        NOT NULL DEFAULT 'brand'
        CHECK (user_type IN ('brand', 'agency', 'distributor', 'influencer')),
    ADD COLUMN signup_route        text        NOT NULL DEFAULT 'other'
        CHECK (signup_route IN ('portal_search','blog_community','pr_article','social_media','offline_event','referral','other')),
    ADD COLUMN phone_country_code  text        NOT NULL DEFAULT '+82'
        CHECK (phone_country_code IN ('+82','+1','+81','+86')),
    ADD COLUMN phone_number        text        NOT NULL DEFAULT '',
    ADD COLUMN company_name        text        NOT NULL DEFAULT '',
    ADD COLUMN company_size        text        NOT NULL DEFAULT '2-10'
        CHECK (company_size IN ('2-10','11-50','51-200','201-500','501-1000','1001+')),
    ADD COLUMN industry            text        NOT NULL DEFAULT 'beauty'
        CHECK (industry IN ('fashion','beauty','fnb','home_living','baby_kids')),
    ADD COLUMN job_title           text        NOT NULL DEFAULT 'other'
        CHECK (job_title IN ('representative','executive','team_lead','staff','other')),
    ADD COLUMN agreed_terms        boolean     NOT NULL DEFAULT true,
    ADD COLUMN agreed_privacy      boolean     NOT NULL DEFAULT true,
    ADD COLUMN agreed_age14        boolean     NOT NULL DEFAULT true,
    ADD COLUMN agreed_marketing    boolean     NOT NULL DEFAULT false,
    ADD COLUMN marketing_updated_at timestamptz,
    ADD COLUMN profile_image_url   text;
```

- [ ] **Step 2: 검증기 테스트 → 구현 (TDD)** — `SignupValidator.validate(SignupRequest)`:
  - 이메일 형식(기존 EMAIL_PATTERN 재사용) → "올바른 이메일 형식을 입력해 주세요."
  - 비밀번호 정책 **8자 이상 + 영대문자·소문자·숫자·특수문자 각 1자 이상**(스펙 6.14/6.15 — 기존 8자 최소보다 강화) → VALIDATION_FAILED
  - enum 5종(userType/signupRoute/companySize/industry/jobTitle — V3 CHECK와 동일 Set), phoneCountryCode
  - agreedTerms/Privacy/Age14 **모두 true 필수**, agreedMarketing은 자유
  - 실패는 전부 `V1ApiException.validation(한국어 메시지)`
  테스트: 정상 통과 1건 + 위반 유형별 1건씩(비번은 대문자 누락/특수문자 누락 분리).

- [ ] **Step 3: RateLimiter 테스트 → 구현** — 고정 윈도우 인메모리(단일 인스턴스 전제, 설계 문서 §5):

```java
/** 로그인·가입·이벤트 레이트리밋 — 키(예: "login:email|ip")당 분당 상한. 초과 시 429 RATE_LIMITED. */
@Component
public class RateLimiter {

	private record Window(long epochMinute, AtomicInteger count) {
	}

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
	private final Clock clock;
	private final int perMinute;

	public RateLimiter(Clock clock, @Value("${was.rate-limit.per-minute:10}") int perMinute) {
		this.clock = clock;
		this.perMinute = perMinute;
	}

	/** 허용되면 true. 분이 바뀌면 카운터 리셋(고정 윈도우). */
	public boolean tryAcquire(String key) {
		long minute = clock.instant().getEpochSecond() / 60;
		Window w = windows.compute(key, (k, old) ->
				(old == null || old.epochMinute() != minute) ? new Window(minute, new AtomicInteger()) : old);
		return w.count().incrementAndGet() <= perMinute;
	}
}
```

테스트: 고정 Clock으로 상한 내 허용/초과 거부/분 경과 후 리셋.

- [ ] **Step 4: `/v1/auth` 컨트롤러 테스트 → 구현** — 기존 `auth.AuthController`의 인증 관용구(AuthenticationManager·SecurityContextRepository·세션 저장)를 그대로 쓰되 v1 계약으로:
  - `POST /v1/auth/signup` — SignupValidator → UserRepository.insertProfile(전 필드, email lower 정규화, BCrypt) → 세션 생성+SecurityContext 저장+UA attribute(Task 1 규약) → **201** `ApiResponse.ok(UserSummary)`. 중복 이메일 DuplicateKeyException → **409 EMAIL_ALREADY_EXISTS** "이미 가입된 이메일이에요. 로그인해 주세요." (`V1ApiException`에 conflict 팩토리 추가)
  - `POST /v1/auth/login` — RateLimiter(키 `login:이메일|IP`, 초과 시 **429 RATE_LIMITED** "요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.") → 인증 실패는 이메일 존재 여부 무관 **401 INVALID_CREDENTIALS** "이메일 또는 비밀번호를 확인해 주세요." → 성공 시 UserSummary + 세션.
  - `POST /v1/auth/logout` — 세션 무효화, **204**(envelope 없음 — 스펙 3.1 예외).
  - `UserSummary(String id, String email, String name, String userType)` — 스펙 6.15 응답 형태(id는 `String.valueOf(users.id)`).
  - SecurityConfig `authorizeHttpRequests`에 v1 보호 경로 추가: `/v1/me/**`·`/v1/saved-contents/**`·`/v1/saved-influencers/**` authenticated (auth·events·읽기 4종은 permitAll 유지). 미인증 401은 기존 HttpStatusEntryPoint — 단 v1 경로는 envelope로 내려가도록 entryPoint에서 `{"success":false,...,"error":{"code":"UNAUTHORIZED","message":"로그인이 필요합니다."}}` JSON을 직접 쓰는 V1AwareEntryPoint로 교체(경로가 `/v1/`로 시작할 때만 body, 그 외 기존 401 빈 응답 유지).
  - 컨트롤러 테스트: 가입 201+쿠키, 검증 위반 400, 중복 409, 로그인 성공/실패 401, 미인증 `/v1/me` 401 envelope, 레이트리밋 429.

- [ ] **Step 5: 실행** — `./gradlew :was:test` 전체 PASS.
- [ ] **Step 6: Commit** — `feat(was): /v1/auth 가입·로그인·로그아웃 — 프로필 필드 15종·레이트리밋 (스펙 6.15/6.16)`

---

### Task 3: `/v1/me` 계정 관리 (스펙 6.12~6.14)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/account/V1MeController.java`
- Create: `was/src/main/java/com/celfit/was/v1/account/MeResponse.java`
- Create: `was/src/main/java/com/celfit/was/v1/account/SessionService.java`
- Create: `was/src/main/java/com/celfit/was/v1/account/ProfileImageStore.java`
- Modify: `was/src/main/java/com/celfit/was/auth/UserRepository.java` (patch·password·delete 지원)
- Test: `V1MeControllerTest`, `SessionServiceTest`, `ProfileImageStoreTest` (같은 패키지 test 소스셋)

**계약 요점:**
- `GET /v1/me` — 스펙 6.12 필드 전부(MeResponse record — id는 문자열, camelCase 그대로).
- `PATCH /v1/me` — 부분 업데이트. **키 존재/부재 구분이 필요**하므로 기존 SavedController 관용구대로 `Map<String,Object>`로 받는다. 허용 키: name(1자 이상)/nickname(빈 문자열→null)/jobTitle/phoneCountryCode/phoneNumber(숫자·하이픈)/companyName(1자 이상)/agreedMarketing(boolean). agreedMarketing **값이 실제로 바뀔 때만** marketing_updated_at=now(). 갱신 후 전체 MeResponse 반환.
- `PUT /v1/me/password` — body {currentPassword, newPassword}. 현재 비번 불일치 → **400 CURRENT_PASSWORD_MISMATCH** "현재 비밀번호가 일치하지 않습니다.", 새 비번은 SignupValidator의 비밀번호 정책 재사용. 성공 **204** + **현재 세션 제외 전 세션 무효화**(설계 확정, 미결 #14 회신).
- `GET /v1/me/sessions` — 스펙 6.14: `[{id, browser, os, loginAt, current}]`. SessionService가 `FindByIndexNameSessionRepository.findByPrincipalName(email)`로 조회. **노출 id는 실제 세션 id가 아니라 alias**(sha-256 hex 앞 16자 — XSS로 목록을 읽혀도 세션 탈취 불가). browser/os는 Task 1 attribute(부재 시 "기타"), loginAt=creationTime ISO, current=현재 요청 세션과 동일 여부.
- `DELETE /v1/me/sessions/{sessionId}` — alias를 본인 세션 목록에서 매칭해 `deleteById`. 매칭 없으면 404 NOT_FOUND(타 유저 세션 추측 차단 — 403 대신 404로 존재 은닉, 스펙 403은 "타 유저 리소스"인데 alias 방식에선 구분 불가·404가 안전). 현재 세션 지정 시 로그아웃과 동일. **204**.
- `PUT /v1/me/profile-image` — multipart `image`, **PNG/JPEG + 1MB 이하**(초과 413 PAYLOAD_TOO_LARGE "이미지는 1MB 이하만 업로드할 수 있어요.", 형식 415 UNSUPPORTED_MEDIA_TYPE "PNG, JPEG 형식만 업로드할 수 있어요." — content-type이 아니라 **매직 바이트로 판별**). 저장은 `ProfileImageStore`: 설정 `was.profile-image.dir`(기본 `./data/profile-images`) 아래 `user-{id}.{png|jpg}` 파일로 저장하고, 정적 서빙 경로 `/profile-images/**`를 그 디렉토리에 매핑(WebMvcConfigurer addResourceHandlers). `users.profile_image_url`에는 `/profile-images/user-{id}.{ext}?v={epochSecond}`(캐시 무효화용 v)를 저장, 확장자가 바뀌면 이전 파일 정리. 응답 200 `{"profileImageUrl": <저장된 URL>}`.
- `DELETE /v1/me/profile-image` — 파일 삭제+컬럼 null, 멱등 204.
- `DELETE /v1/me` — body {password} 본인 확인(불일치 CURRENT_PASSWORD_MISMATCH). 한 트랜잭션으로 saved 2종·유저 삭제(FK가 CASCADE 아님 — 순서 삭제), 전 세션 무효화(`findByPrincipalName` 전부 deleteById), 프로필 이미지 파일 삭제. **204**.

**멀티파트 한도 설정**: `spring.servlet.multipart.max-file-size: 2MB`(컨테이너 컷보다 앱 검증이 먼저 걸리도록 여유), 앱에서 1MB 검증이 정본.

- [ ] **Step 1: SessionService 테스트 → 구현** (alias 생성·current 판정·개별 삭제·비번 변경 시 타 세션 삭제 — `FindByIndexNameSessionRepository`는 인터페이스 목으로 단위 테스트)
- [ ] **Step 2: ProfileImageStore 테스트 → 구현** (임시 디렉토리, PNG/JPEG 매직 바이트 판별 — PNG `89 50 4E 47`, JPEG `FF D8 FF`, 1MB 초과 거부, 삭제 멱등)
- [ ] **Step 3: V1MeController 테스트 → 구현** (GET/PATCH/password/sessions/탈퇴 — 리포지토리·서비스 목, 스펙 에러 코드 전수)
- [ ] **Step 4: 실행** — `./gradlew :was:test` PASS.
- [ ] **Step 5: Commit** — `feat(was): /v1/me 계정 관리 — 프로필·비밀번호·세션 목록/개별 로그아웃·이미지·탈퇴 (스펙 6.12~6.14)`

---

### Task 4: 저장 2종 `/v1` 계약화 (스펙 6.6~6.11)

**Files:**
- Create: `was/src/main/resources/db/migration/app/V4__saved_contents_memo.sql` (`ALTER TABLE app.saved_contents ADD COLUMN memo text;`)
- Create: `was/src/main/java/com/celfit/was/v1/saved/V1SavedContentsController.java`
- Create: `was/src/main/java/com/celfit/was/v1/saved/V1SavedInfluencersController.java`
- Create: `was/src/main/java/com/celfit/was/v1/saved/V1SavedRepository.java`
- Create: `was/src/main/java/com/celfit/was/v1/saved/V1SavedAssembler.java`
- Test: `V1Saved*Test` (컨트롤러 2종 + 어셈블러)

**계약 요점:**
- **memo 정규화**: 공백뿐/빈 문자열 → null (스펙 6.7 normalizeMemo 규칙 — 어셈블러 헬퍼로).
- `POST /v1/saved-contents` — body {contentId, memo?}. **콘텐츠 존재 확인**(analysis `contents`에 별도 SELECT — 조인 아님) 없으면 404 NOT_FOUND. upsert: 신규면 **201**, 기존이면 memo 갱신 **200** (신규 여부는 `INSERT ... ON CONFLICT ... RETURNING (xmax = 0) AS inserted` 관용구). 응답 = 목록 항목 1건 {content(카드, isContentsSaved=true), memo, savedAt}.
- `GET /v1/saved-contents` — 최근 저장 순. **app 조회 → short_code IN (...)로 analysis 카드 조회 → Java 조합**(§4-4). 원본 콘텐츠가 analysis에서 사라졌으면 항목 제외(스펙 6.6 [제안] 수용). meta {total(제외 전 저장 건수 아님 — **조합 후 노출 건수**로 통일, 주석 명시), limit 100}. 카드 조회는 `ContentCardRow.SELECT` 재사용 + `WHERE c.short_code IN (:codes)`.
- `DELETE /v1/saved-contents/{contentId}` — 멱등 204 (기존 deleteContent 재사용).
- `/v1/saved-influencers` 3종 동형 — 응답 influencer 객체는 {id, handle, displayName, profileImageUrl, followers}(accounts 조회, 부재 시 handle만 채우고 나머지 null — 저장은 논리 참조라 계정 미러 부재 가능). **status 컬럼은 v1 응답에 노출하지 않음**(스펙에 없음 — 컬럼·구 API는 유지).
- POST의 body 필드명은 스펙 그대로 contentId/influencerId(값은 shortCode/handle).
- 카드의 isContentsSaved는 Task 5의 카드 확장 필드를 true로 채움(Task 5와 같은 PR 흐름이므로 순서 주의 — **Task 5를 먼저 구현하면 필드가 준비됨. 실행 순서는 4↔5 바꿔도 무방하나 계획상 5의 카드 필드 추가분을 이 태스크가 사용**한다. 구현 시점에 필드가 없으면 Task 5의 Step 1(카드 필드 추가)만 먼저 수행).

- [ ] **Step 1: V4 마이그레이션 + 리포지토리 테스트 → 구현** (Testcontainers — upsert 신규/갱신 판별, memo null 정규화 저장, IN 조회)
- [ ] **Step 2: 컨트롤러 테스트 → 구현** (201/200 구분, 404, 멱등 204, 목록 조합·제외, meta)
- [ ] **Step 3: 실행** — `./gradlew :was:test` PASS.
- [ ] **Step 4: Commit** — `feat(was): /v1 저장 2종 — memo upsert·카드 조합 목록 (스펙 6.6~6.11)`

---

### Task 5: P1 응답 개인화 필드 활성화 (isContentsSaved / isInfluencerSaved)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/content/ContentCard.java` (+필드)
- Modify: `was/src/main/java/com/celfit/was/v1/content/ContentCardAssembler.java` (toCard 오버로드)
- Modify: `was/src/main/java/com/celfit/was/v1/content/V1ContentController.java`
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerController.java` + `InfluencerProfileResponse.java`
- Create: `was/src/main/java/com/celfit/was/v1/common/SavedLookup.java`
- Test: 기존 컨트롤러 테스트에 케이스 추가

**규약**: 스펙 2절 — Optional 엔드포인트는 로그인 시에만 개인화 필드 포함, 비로그인이면 **필드 자체가 없음**.

- [ ] **Step 1: 카드 필드 추가** — `ContentCard`에 마지막 컴포넌트로 `@JsonInclude(JsonInclude.Include.NON_NULL) Boolean isContentsSaved` 추가(null=비로그인=직렬화 생략, true/false=로그인). `toCard(row)`는 null로 위임하는 기존 시그니처 유지 + `toCard(row, Boolean saved)` 오버로드. 기존 호출부·테스트 컴파일 수정.
- [ ] **Step 2: SavedLookup** — 요청 유저의 저장 상태 일괄 조회(개인화 마킹용):

```java
/** 로그인 유저의 저장 셋 일괄 조회 — P1 Optional 응답의 개인화 마킹 재료 (비로그인이면 호출하지 않음). */
@Component
public class SavedLookup {

	private final JdbcClient jdbcClient;

	public SavedLookup(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Set<String> savedShortCodes(long userId) {
		return Set.copyOf(jdbcClient.sql(
				"SELECT short_code FROM app.saved_contents WHERE user_id = :u")
				.param("u", userId).query(String.class).list());
	}

	public boolean isInfluencerSaved(long userId, String handle) {
		return jdbcClient.sql(
				"SELECT count(*) FROM app.saved_influencers WHERE user_id = :u AND handle = :h")
				.param("u", userId).param("h", handle).query(Long.class).single() > 0;
	}
}
```

- [ ] **Step 3: 컨트롤러 연결** — `@AuthenticationPrincipal(required=false) AppUserDetails principal` 주입(Optional): principal null이면 기존 경로(카드 saved=null), 있으면 savedShortCodes 1회 조회 후 `toCard(row, set.contains(shortCode))`. `GET /v1/contents`·`GET /v1/influencers/{id}`(recentContents 카드 + `isInfluencerSaved` 필드 — InfluencerProfileResponse에 `@JsonInclude(NON_NULL) Boolean isInfluencerSaved` 추가)에 적용. 6.3/6.5 리포트는 개인화 필드 없음(스펙).
- [ ] **Step 4: 테스트** — 비로그인: `$.data[0].isContentsSaved` doesNotExist / 로그인(목 principal + SavedLookup 목): true·false 마킹. 인플루언서 동형.
- [ ] **Step 5: 실행** — `./gradlew :was:test` PASS.
- [ ] **Step 6: Commit** — `feat(was): P1 응답 개인화 필드 활성화 — isContentsSaved·isInfluencerSaved (스펙 2절 Optional 규약)`

---

### Task 6: 게이트 이벤트 (스펙 6.19)

**Files:**
- Create: `was/src/main/resources/db/migration/app/V5__gate_events.sql`
- Create: `was/src/main/java/com/celfit/was/v1/events/V1GateEventController.java`
- Create: `was/src/main/java/com/celfit/was/v1/events/GateEventRepository.java`
- Test: `V1GateEventControllerTest`

- [ ] **Step 1: V5 마이그레이션**

```sql
-- 게이트/잠금 클릭 측정 이벤트 (스펙 6.19) — fire-and-forget 기록 전용.
CREATE TABLE app.gate_events (
    id         bigserial PRIMARY KEY,
    user_id    bigint,                          -- 익명 허용 (users 논리 참조 — 탈퇴 후에도 이벤트 보존)
    event_type text NOT NULL,
    payload    jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gate_events_ix1 ON app.gate_events (event_type, created_at);
```

- [ ] **Step 2: 컨트롤러 테스트 → 구현** — `POST /v1/events/gate` body {eventType, payload?}. 인증 Optional(principal 있으면 user_id 연결). **204**. eventType 누락 등 오류도 스펙상 "4xx 남발 금지" — eventType 없으면 기록 스킵하고 **204**(주석으로 의도 명시). RateLimiter(키 `gate:IP`) 초과 시만 429. payload는 jsonb 그대로 저장(`::jsonb` 캐스트, 직렬화는 ObjectMapper).
- [ ] **Step 3: 실행·Commit** — `feat(was): POST /v1/events/gate 게이트 이벤트 (스펙 6.19)`

---

### Task 7: 마무리 — 전체 검증·쿠키 E2E·문서·PR

- [ ] **Step 1: 전체 테스트** — `./gradlew cleanTest test`. Expected: 전 모듈 PASS.
- [ ] **Step 2: 실 DB 쿠키 플로우 E2E** — was 기동 후 curl로(쿠키 jar + XSRF 헤더 왕복):

```bash
J=/tmp/p2-cookies.txt
# CSRF 토큰 확보
curl -s -c $J localhost:8081/v1/contents?startDate=2026-06-01\&endDate=2026-07-15 > /dev/null
X=$(grep XSRF-TOKEN $J | awk '{print $7}')
# 가입 → 쿠키 확인(hypenow-session)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $X" -H 'Content-Type: application/json' \
  -d '{"email":"p2@test.io","password":"Aa1!aaaa","userType":"brand","signupRoute":"other","name":"테스트","phoneCountryCode":"+82","phoneNumber":"010-0000-0000","companyName":"셀핏","companySize":"2-10","industry":"beauty","jobTitle":"staff","agreedTerms":true,"agreedPrivacy":true,"agreedAge14":true,"agreedMarketing":false}' \
  localhost:8081/v1/auth/signup
grep hypenow-session $J   # 쿠키명 검증
# me → 저장 → 리더보드 개인화 → 세션 목록 → 로그아웃
curl -s -b $J localhost:8081/v1/me
curl -s -b $J -H "X-XSRF-TOKEN: $X" -H 'Content-Type: application/json' -d '{"contentId":"<실제 short_code>","memo":"테스트 메모"}' localhost:8081/v1/saved-contents
curl -s -b $J 'localhost:8081/v1/contents?startDate=2026-06-01&endDate=2026-07-15' | grep -o isContentsSaved | head -1
curl -s -b $J localhost:8081/v1/me/sessions
curl -s -b $J -H "X-XSRF-TOKEN: $X" -X POST localhost:8081/v1/auth/logout -w '%{http_code}'
```

검증 포인트: 쿠키명 `hypenow-session`, 세션 목록에 browser/os/current, 비로그인 응답에 개인화 필드 부재 ↔ 로그인 후 존재, was 재시작 후에도 세션 유지(Spring Session). 테스트 유저·저장 행은 확인 후 DELETE(탈퇴 API로 — 탈퇴 검증 겸용).
- [ ] **Step 3: 스펙 대조** — 6.6~6.16·6.19 예시 JSON과 필드명·상태코드·에러 코드 대조표 보고(불일치는 수정 말고 보고).
- [ ] **Step 4: 문서** — ARCHITECTURE §5 P2 행 ✅("07-15 개통"), §3 analysis DB 절의 app 스키마 테이블 목록에 spring_session·gate_events·memo 추가 반영, §7 결정 1행(Spring Session 전환·세션 alias 노출·404 은닉 결정·이미지 로컬 저장 포함). 이 계획 문서 상태 ✅ + `plans/archive/` 이동.
- [ ] **Step 5: PR** — push + `gh pr create --base develop --title "feat: P2 서비스 데이터 정렬 — /v1 인증·계정·저장·이벤트 + 개인화 필드"`. 본문: 요약/스펙 대조/검증(E2E 플로우)/리뷰 이력/미결(이메일 인증 TBD·프로필 이미지 서버 로컬 저장의 배포 볼륨 주의). 푸터 🤖.

---

## Self-Review 노트

- 스펙 커버: 6.6~6.11(T4), 6.12~6.14(T3), 6.15~6.16(T2), 6.19(T6), 2절 Optional 개인화(T5), 4절 세션 합의(T1). 6.17은 [TBD]로 제외, 6.18 fit 보류, 6.20은 P3.
- 계약 확정 2건(스펙 침묵 지점, PR에 명시): 세션 노출 id는 alias(sha-256 앞 16자) — 실 세션 id 비노출 / 타 유저 세션 삭제 시도는 403 대신 404(alias 존재 은닉).
- Flyway 번호: app 스키마는 V1뿐이므로 V2~V5 연속 사용(§4-5 번호대 충돌 없음 — analysis 쪽과 이력 분리).
- 기존 구 표면(/api/auth·/api/saved)은 손대지 않음 — 프론트 전환 후 일괄 제거.
