# 태스크 G: 서비스 데이터 — app 스키마 + 인증 + 후보 저장 Implementation Plan

> 상태: ✅ 구현/실행/반영됨
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development 또는 superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** was의 첫 쓰기 기능 — `app` 스키마 신설(was 소유 Flyway), 이메일+비밀번호 인증(Spring Security 풀설정, 세션 쿠키), 저장 기능 2종(콘텐츠 북마크·인플루언서 후보: 상태 검토중/컨택 예정/협업 중 + 메모).

**설계 (2026-07-14 사용자 확정: 이메일+비밀번호 로그인 · 저장 대상 둘 다 · Spring Security 풀설정):**

- **스키마**: analysis DB 내 `app` 스키마, was 소유 Flyway 별도 이력(§3 — analytics의 FlywayConfig 패턴). 분석 결과와 FK·SQL 조인 금지(§4-4) — handle·short_code는 논리 참조.
- **인증**: Spring Security — DaoAuthenticationProvider(+UserDetailsService, BCrypt), 세션 쿠키(HttpSession, MVP 인메모리 — 확장점: spring-session-jdbc), CSRF = `CookieCsrfTokenRepository.withHttpOnlyFalse()`(SPA가 XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더), 미인증 401(리다이렉트 금지). 크로스 오리진 쿠키라 CORS `allowCredentials(true)` + 쓰기 메서드 허용 — **기존 GET-only CORS를 CorsConfigurationSource 빈으로 일원화**(운영 배포 시 HTTPS + `Secure; SameSite=None` 쿠키 설정 필요 — 문서화만, 코드는 기본).
- **상태 어휘는 was가 생산자로서 확정**(서비스 데이터 소유자): `reviewing | contact_planned | collaborating` (프론트 라벨: 검토중/컨택 예정/협업 중).
- **저장 기술**: JdbcClient 유지(§4-4는 JPA 허용이나 기존 관용구·최소 의존 선택 — 결정 기록에 명시).

**API 계약 (was → front):**

| 엔드포인트 | 요청 | 응답 |
|---|---|---|
| `POST /api/auth/signup` | `{email, password}` | 201 `{id, email}` / 400(형식: email 형식·password 8자↑) / 409(중복 — email은 lower 정규화 저장) |
| `POST /api/auth/login` | `{email, password}` | 200 `{id, email}` + 세션 성립 / 401 |
| `POST /api/auth/logout` | — | 204 (세션 무효화, 미로그인이어도 204) |
| `GET /api/me` | — | 200 `{id, email}` / 401 |
| `GET /api/saved/influencers` | — | 200 `{items: [{handle, status, memo, createdAt, updatedAt}]}` (updatedAt DESC) |
| `PUT /api/saved/influencers/{handle}` | `{status?, memo?}` | 200 upsert 결과 1행. 신규 기본 status=reviewing·memo=null, 지정 필드만 갱신(미지정 유지). status 어휘 밖 → 400 |
| `DELETE /api/saved/influencers/{handle}` | — | 204 (멱등) |
| `GET /api/saved/contents` | — | 200 `{items: [{shortCode, createdAt}]}` (createdAt DESC) |
| `PUT /api/saved/contents/{shortCode}` | — | 200 `{shortCode, createdAt}` (멱등 upsert) |
| `DELETE /api/saved/contents/{shortCode}` | — | 204 (멱등) |

- `/api/auth/**`·기존 GET API(`/api/**` GET)·`/health`·대시보드(`/`) = permitAll. `/api/me`·`/api/saved/**` = authenticated(아니면 401).
- 쓰기 요청은 CSRF 토큰 필수(403 아닌 경우 없도록 프론트 계약 문서화). 저장 대상 존재 검증은 하지 않는다(분석 결과 조회·조인 금지 — 프론트가 화면에서 온 handle/short_code만 보냄).

## File Structure

```
was/build.gradle                                       [수정] security·flyway·security-test 의존성
was/src/main/resources/db/migration/app/V1__users_and_saved.sql   [신규]
was/src/main/java/com/celfit/was/config/AppFlywayConfig.java      [신규] app 스키마 전용 Flyway 빈
was/src/main/java/com/celfit/was/config/SecurityConfig.java       [신규] 필터체인·인코더·AuthenticationManager·CORS 소스
was/src/main/java/com/celfit/was/config/WebConfig.java            [수정] CORS를 CorsConfigurationSource로 일원화
was/src/main/java/com/celfit/was/auth/
  AppUser.java (record) · AppUserDetails.java · AppUserDetailsService.java
  UserRepository.java · AuthController.java (signup/login/logout/me)
was/src/main/java/com/celfit/was/saved/
  SavedInfluencer.java · SavedContent.java (record)
  SavedRepository.java · SavedController.java
was/src/test/java/com/celfit/was/auth/UserRepositoryTest.java     [신규] Testcontainers
was/src/test/java/com/celfit/was/saved/SavedRepositoryTest.java   [신규] Testcontainers
was/src/test/java/com/celfit/was/AuthFlowIntegrationTest.java     [신규] @SpringBootTest+MockMvc 시나리오
기존 @WebMvcTest 3벌                                    [수정 가능] Security 도입 영향 — 아래 Task 3 주의
```

**DDL (V1__users_and_saved.sql):**

```sql
-- 서비스 데이터 (app 스키마 — was 소유, 분석 결과와 FK·조인 없음 §4-4)
CREATE TABLE users (
    id            bigserial PRIMARY KEY,
    email         text NOT NULL UNIQUE,          -- Java에서 lower 정규화 후 저장
    password_hash text NOT NULL,                 -- BCrypt
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- 인플루언서 후보 (상태 어휘는 was가 확정: 검토중/컨택 예정/협업 중)
CREATE TABLE saved_influencers (
    user_id    bigint NOT NULL REFERENCES users(id),
    handle     text   NOT NULL,                  -- accounts.handle 논리 참조 (FK 금지)
    status     text   NOT NULL DEFAULT 'reviewing'
               CHECK (status IN ('reviewing', 'contact_planned', 'collaborating')),
    memo       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, handle)
);

-- 콘텐츠 북마크
CREATE TABLE saved_contents (
    user_id    bigint NOT NULL REFERENCES users(id),
    short_code text   NOT NULL,                  -- contents.short_code 논리 참조
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, short_code)
);
```

**AppFlywayConfig** (analytics FlywayConfig 패턴 — schemas/createSchemas/defaultSchema로 `app` 격리):

```java
	@Bean(initMethod = "migrate")
	public Flyway appFlyway(DataSource dataSource) {
		return Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration/app")
				.schemas("app")
				.createSchemas(true)
				.defaultSchema("app")
				.load();
	}
```

(이력 테이블도 app 스키마에 생김 — 분석 결과 이력(analytics 소유, public)과 분리. was 쿼리는 `app.users`처럼 스키마 명시.)

**SecurityConfig 핵심 (완성 코드는 구현 시 — 아래 구성 요소 필수):**

- `BCryptPasswordEncoder` 빈, `AuthenticationManager` = DaoAuthenticationProvider(AppUserDetailsService + encoder)
- `SecurityFilterChain`: csrf → `CookieCsrfTokenRepository.withHttpOnlyFalse()`, cors → 아래 소스, authorizeHttpRequests → `/api/me`·`/api/saved/**` authenticated, 그 외 permitAll, `exceptionHandling().authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))`, formLogin·httpBasic·logout(기본) disable — logout은 AuthController가 직접 세션 무효화
- `CorsConfigurationSource` 빈: 기존 `was.cors.allowed-origins` 프로퍼티 재사용, `allowedMethods(GET, POST, PUT, DELETE)`, `allowedHeaders("*")`, `allowCredentials(true)`, `/api/**` 등록. **WebConfig의 addCorsMappings 제거**(이원화 방지 — 컨트롤러 CORS 테스트가 계속 통과해야 함)
- 로그인 처리: AuthController가 `authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password))` → `SecurityContextHolder` 설정 + `securityContextRepository.saveContext(...)`(HttpSessionSecurityContextRepository) — Spring Security 표준 SPA 로그인 관용구

---

### Task 1: 스키마 + 리포지토리 (TDD)

- [ ] build.gradle: `implementation 'org.springframework.boot:spring-boot-starter-security'`, `implementation 'org.springframework.boot:spring-boot-starter-flyway'`(또는 flyway-core+flyway-database-postgresql — analytics와 동일 좌표), `testImplementation 'org.springframework.security:spring-security-test'`
- [ ] V1 DDL + AppFlywayConfig 작성
- [ ] **UserRepositoryTest** (Testcontainers, Flyway가 app 스키마 실생성 — DDL 하드코딩 금지): insert(email lower 정규화·중복 시 DuplicateKeyException)·findByEmail·findById 4테스트
- [ ] **SavedRepositoryTest**: 인플루언서 upsert(신규 기본값 reviewing/null → 부분 갱신: status만·memo만·유지 확인·updated_at 갱신), 목록 정렬(updatedAt DESC), delete 멱등, 콘텐츠 upsert 멱등(created_at 유지)·목록·delete — 7테스트. upsert는 `INSERT ... ON CONFLICT (user_id, handle) DO UPDATE SET status = COALESCE(:status, saved_influencers.status), memo = CASE WHEN :memoGiven THEN :memo ELSE saved_influencers.memo END, updated_at = now() RETURNING ...` 패턴(부분 갱신 시맨틱 — memo는 null 지정과 미지정을 구분해야 하므로 memoGiven 플래그)
- [ ] Commit — `feat(was): app 스키마 신설 + 사용자·저장 리포지토리 (was 소유 Flyway)`

### Task 2: Security + 인증 API (TDD)

- [ ] SecurityConfig·AppUserDetails(Service)·AuthController(signup 400/409 검증 포함)·WebConfig CORS 일원화
- [ ] **AuthFlowIntegrationTest** (`@SpringBootTest`+`@AutoConfigureMockMvc`+IntegrationTest 상속, spring-security-test의 csrf() 후처리기 사용): ①signup 201→중복 409→형식 400 ②login 200(세션)→잘못된 비밀번호 401 ③me: 로그인 후 200·미로그인 401 ④logout 204 후 me 401 ⑤CSRF 없는 쓰기 403 — 5테스트
- [ ] Commit — `feat(was): Spring Security 인증 — signup/login/logout/me (세션 쿠키·CSRF·401)`

### Task 3: 저장 API + 기존 테스트 보전 (TDD)

- [ ] SavedController — 인증 주체(`@AuthenticationPrincipal AppUserDetails`)의 userId로 위 계약 6엔드포인트
- [ ] AuthFlowIntegrationTest 확장 또는 SavedApiIntegrationTest: 로그인 상태에서 인플루언서 PUT(기본값→상태 변경→어휘 밖 400)·GET 목록·DELETE, 콘텐츠 PUT/GET/DELETE, 미로그인 401 — 6테스트
- [ ] **기존 @WebMvcTest 3벌(PostDetail·ContentList·InfluencerDetail Controller) 그린 유지** — security starter 도입으로 기본 시큐리티가 끼면 401로 깨질 수 있다. 대응: 각 테스트에 `@Import(SecurityConfig.class)`(우리 체인은 GET /api/** permitAll) 또는 Boot 4 관용구 확인 후 최소 수정. **기존 62개 + 신규 전부 그린이 커밋 조건.**
- [ ] Commit — `feat(was): 후보·북마크 저장 API — 상태 전이·메모 (인증 필수)`

### Task 4: E2E + 문서

- [ ] `./gradlew test` 전 모듈 그린
- [ ] worktree `:was:bootRun --args='--server.port=8082'`(8081은 다른 세션 사용 중일 수 있음) 후 curl 쿠키 플로우: signup→login(쿠키 저장)→XSRF 쿠키로 saved PUT→GET 목록→logout→401. app 스키마 실생성 확인(`\dt app.*`)
- [ ] ARCHITECTURE: §3 analysis DB 절의 app 스키마 상세화(테이블 3종), §5 G 행 ✅, §7 결정 기록(인증 방식·상태 어휘·JdbcClient 선택·CORS 일원화·세션 저장 확장점), 계획 헤더 ✅
- [ ] Commit — `docs: G 완료 반영 (서비스 데이터·인증 계약)` → 최종 리뷰 → PR

## DoD

- 신규 테스트 22개(리포 11·인증 플로우 5·저장 API 6) + **기존 62개 그린 유지** + 전 모듈
- 실 DB E2E 쿠키 플로우 통과, app 스키마 Flyway 실생성
- 상태 어휘·CSRF·401 규약이 계약 문서·테스트로 고정

## 다루지 않는 것

- 소셜 로그인·비밀번호 재설정·이메일 인증, spring-session-jdbc(확장점 문서화만), 후보 목록에 분석 데이터 조합 서빙(프론트가 기존 API 조합 — 필요 시 후속), 운영 HTTPS 쿠키 설정(배포 시)
