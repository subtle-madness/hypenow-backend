# 수동 발굴 등록 API + 크롬 익스텐션 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 구현·실행됨 (07-22) · 스펙: `docs/superpowers/specs/2026-07-22-manual-discovery-extension-design.md`

**Goal:** 크롬 익스텐션에서 보고 있는 인스타 프로필을 crawler DB의 발굴 단계(DISCOVERED)로 한 클릭 등록한다.

**Architecture:** crawler에 토큰 인증 REST API(`POST /api/manual-discoveries`)를 추가하고 Caddy가 그 경로만 외부 공개. 익스텐션(MV3 팝업)은 별도 저장소로, 현재 탭 URL에서 username을 추출해 POST한다. 이후 판정·뷰티판정은 기존 QualifyJob·BeautyJob이 그대로 이어받는다.

**Tech Stack:** Java 21 · Spring Boot 4.1 (crawler 모듈) · Caddy · Chrome Extension MV3 (순수 JS, 빌드 도구 없음) · node --test

## Global Constraints

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix는 `feat(crawler):`/`docs:` 식.
- Spring Boot 4: `@WebMvcTest`는 `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`.
- crawler는 헥사고날: 컨트롤러는 `crawling/adapter/in/web/`, 서비스는 `crawling/application/service/`.
- 발굴 출처 keyword는 정확히 `수동:크롬`.
- 토큰 프로퍼티는 `crawler.manual-discovery.token`(환경변수 `MANUAL_DISCOVERY_TOKEN`), 미설정 시 API는 503 (fail-closed).
- 익스텐션은 이 레포 밖: `/Users/dongju/project/current/soma/hypenow/hypenow-extension` (독립 git 저장소). 백엔드 레포에는 익스텐션 코드를 넣지 않는다.
- 백엔드 작업 디렉토리(워크트리): `/Users/dongju/project/current/soma/hypenow/hypenow-backend/.claude/worktrees/instagram-influencer-chrome-extension-486661`

---

### Task 1: ManualDiscoveryService (crawler 서비스 계층)

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/ManualDiscoveryService.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/ManualDiscoveryServiceTest.java`

**Interfaces:**
- Consumes: 기존 `InfluencerRepository.findByUsername(String)` / `save(Influencer)`, `InfluencerDiscoveryRepository.save(InfluencerDiscovery)`, `Clock` 빈(이미 등록돼 있음 — DiscoverJob이 주입받음).
- Produces: `ManualDiscoveryService.register(String rawUsername)` → `ManualDiscoveryService.Result(String username, boolean created, InfluencerStatus status, BeautyClass beautyClass)`. 형식 불량 username은 `IllegalArgumentException`. 상수 `ManualDiscoveryService.MANUAL_KEYWORD = "수동:크롬"`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 수동 발굴 등록 — 신규만 생성·기존 불변·정규화·형식 검증. */
class ManualDiscoveryServiceTest {

    static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    InfluencerDiscoveryRepository discoveries = mock(InfluencerDiscoveryRepository.class);
    ManualDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new ManualDiscoveryService(influencers, discoveries, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 신규_username은_DISCOVERED로_생성하고_수동_출처를_기록한다() {
        when(influencers.findByUsername("new.user")).thenReturn(Optional.empty());
        when(influencers.save(any())).thenAnswer(inv -> {
            Influencer i = inv.getArgument(0);
            i.setId(11L);
            return i;
        });

        var result = service.register("new.user");

        assertThat(result.created()).isTrue();
        assertThat(result.username()).isEqualTo("new.user");
        assertThat(result.status()).isEqualTo(InfluencerStatus.DISCOVERED);
        assertThat(result.beautyClass()).isNull();

        var captor = ArgumentCaptor.forClass(InfluencerDiscovery.class);
        verify(discoveries).save(captor.capture());
        InfluencerDiscovery d = captor.getValue();
        assertThat(d.getInfluencerId()).isEqualTo(11L);
        assertThat(d.getKeyword()).isEqualTo("수동:크롬");
        assertThat(d.getDiscoveredPostShortCode()).isNull();
        assertThat(d.getDiscoveredAt()).isEqualTo(NOW);
    }

    @Test
    void 기존_username은_아무것도_바꾸지_않고_현재_상태만_돌려준다() {
        Influencer existing = new Influencer("known.user");
        existing.setId(7L);
        existing.setStatus(InfluencerStatus.QUALIFIED);
        existing.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "근거");
        when(influencers.findByUsername("known.user")).thenReturn(Optional.of(existing));

        var result = service.register("known.user");

        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo(InfluencerStatus.QUALIFIED);
        assertThat(result.beautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        verify(influencers, never()).save(any());
        verify(discoveries, never()).save(any());
    }

    @Test
    void 공백과_앳을_벗기고_소문자로_정규화한다() {
        when(influencers.findByUsername("some.user")).thenReturn(Optional.empty());
        when(influencers.save(any())).thenAnswer(inv -> {
            Influencer i = inv.getArgument(0);
            i.setId(1L);
            return i;
        });

        var result = service.register("  @Some.User ");

        assertThat(result.username()).isEqualTo("some.user");
    }

    @Test
    void 형식_불량_username은_IllegalArgumentException이고_아무것도_저장하지_않는다() {
        assertThatThrownBy(() -> service.register("no way!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register(null)).isInstanceOf(IllegalArgumentException.class);
        verify(influencers, never()).save(any());
        verify(discoveries, never()).save(any());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.application.service.ManualDiscoveryServiceTest'`
Expected: 컴파일 실패 — `ManualDiscoveryService` 심볼 없음.

- [ ] **Step 3: 최소 구현**

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수동 발굴 등록 — 크롬 익스텐션 등 외부에서 username 하나를 발굴 단계(DISCOVERED)로 넣는다.
 * 신규만 생성하고 기존 계정은 건드리지 않는다(반복 클릭으로 discovery 이력이 쌓이는 것 방지).
 * 이후 판정·뷰티판정은 기존 QualifyJob·BeautyJob이 그대로 이어받는다.
 */
@Service
public class ManualDiscoveryService {

    /** 발굴 출처 스냅샷 — influencer_discovery.keyword에 남는 값. */
    public static final String MANUAL_KEYWORD = "수동:크롬";

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._]{1,30}$");

    public record Result(String username, boolean created, InfluencerStatus status, BeautyClass beautyClass) {}

    private final InfluencerRepository influencers;
    private final InfluencerDiscoveryRepository discoveries;
    private final Clock clock;

    public ManualDiscoveryService(InfluencerRepository influencers,
                                  InfluencerDiscoveryRepository discoveries, Clock clock) {
        this.influencers = influencers;
        this.discoveries = discoveries;
        this.clock = clock;
    }

    @Transactional
    public Result register(String rawUsername) {
        String username = normalize(rawUsername);
        var existing = influencers.findByUsername(username);
        if (existing.isPresent()) {
            Influencer inf = existing.get();
            return new Result(username, false, inf.getStatus(), inf.getBeautyClass());
        }
        Influencer inf = influencers.save(new Influencer(username));
        discoveries.save(new InfluencerDiscovery(inf.getId(), MANUAL_KEYWORD, null, clock.instant()));
        return new Result(username, true, inf.getStatus(), inf.getBeautyClass());
    }

    /** 앞뒤 공백·@ 제거 후 소문자화. 인스타 username 형식(영숫자·._ 1~30자)이 아니면 예외. */
    private static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("username이 비어 있음");
        }
        String u = raw.trim().toLowerCase();
        if (u.startsWith("@")) {
            u = u.substring(1);
        }
        if (!USERNAME.matcher(u).matches()) {
            throw new IllegalArgumentException("username 형식 불량: " + raw);
        }
        return u;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.application.service.ManualDiscoveryServiceTest'`
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/ManualDiscoveryService.java \
        crawler/src/test/java/com/celfit/crawler/crawling/application/service/ManualDiscoveryServiceTest.java
git commit -m "feat(crawler): 수동 발굴 등록 서비스 — 신규만 DISCOVERED 생성, 출처 '수동:크롬'"
```

---

### Task 2: ManualDiscoveryController (토큰 인증 REST API)

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/ManualDiscoveryController.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/ManualDiscoveryControllerTest.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/ManualDiscoveryControllerNoTokenTest.java`

**Interfaces:**
- Consumes: Task 1의 `ManualDiscoveryService.register(String)` → `Result(username, created, status, beautyClass)` / `IllegalArgumentException`.
- Produces: `POST /api/manual-discoveries`, body `{"username": "..."}`, 헤더 `X-Api-Token`. 응답 200 `{"username","created","status","beautyClass"}` / 400 형식 불량 / 401 토큰 불일치·부재 / 503 토큰 미설정.

- [ ] **Step 1: 실패하는 테스트 작성 (토큰 설정 케이스)**

```java
package com.celfit.crawler.crawling.adapter.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.crawling.application.service.ManualDiscoveryService;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 수동 발굴 등록 API — 토큰 인증·정상 등록·중복·형식 불량. */
@WebMvcTest(controllers = ManualDiscoveryController.class,
        properties = "crawler.manual-discovery.token=test-token")
class ManualDiscoveryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ManualDiscoveryService service;

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(post("/api/manual-discoveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰이_틀리면_401() throws Exception {
        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 신규_등록이면_created_true와_상태를_돌려준다() throws Exception {
        given(service.register("new.user")).willReturn(new ManualDiscoveryService.Result(
                "new.user", true, InfluencerStatus.DISCOVERED, null));

        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new.user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("new.user"))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.status").value("DISCOVERED"))
                .andExpect(jsonPath("$.beautyClass").doesNotExist());
    }

    @Test
    void 기존_계정이면_created_false와_뷰티분류를_돌려준다() throws Exception {
        given(service.register("known.user")).willReturn(new ManualDiscoveryService.Result(
                "known.user", false, InfluencerStatus.QUALIFIED, BeautyClass.INFLUENCER));

        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"known.user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.status").value("QUALIFIED"))
                .andExpect(jsonPath("$.beautyClass").value("INFLUENCER"));
    }

    @Test
    void 형식_불량이면_400() throws Exception {
        given(service.register(anyString())).willThrow(new IllegalArgumentException("username 형식 불량: !!"));

        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"!!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (토큰 미설정 fail-closed)**

```java
package com.celfit.crawler.crawling.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.crawling.application.service.ManualDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 토큰 미설정이면 올바른 토큰을 보내도 API 전체 비활성(503) — fail-closed. */
@WebMvcTest(controllers = ManualDiscoveryController.class)
class ManualDiscoveryControllerNoTokenTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ManualDiscoveryService service;

    @Test
    void 토큰_미설정이면_503() throws Exception {
        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.adapter.in.web.*'`
Expected: 컴파일 실패 — `ManualDiscoveryController` 심볼 없음.

- [ ] **Step 4: 최소 구현**

```java
package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.crawling.application.service.ManualDiscoveryService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수동 발굴 등록 API — 크롬 익스텐션 전용. Caddy가 이 경로만 외부에 공개하고(/crawler 프리픽스
 * strip), 사전 공유 토큰(X-Api-Token)으로 인증한다. 토큰 미설정이면 전체 비활성(fail-closed 503).
 */
@RestController
@RequestMapping("/api/manual-discoveries")
public class ManualDiscoveryController {

    public record Request(String username) {}
    public record Response(String username, boolean created, String status, String beautyClass) {}

    private final ManualDiscoveryService service;
    private final String token;

    public ManualDiscoveryController(ManualDiscoveryService service,
                                     @Value("${crawler.manual-discovery.token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping
    public ResponseEntity<?> register(
            @RequestHeader(value = "X-Api-Token", required = false) String apiToken,
            @RequestBody Request request) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "manual-discovery 토큰 미설정"));
        }
        if (!token.equals(apiToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "토큰 불일치"));
        }
        try {
            var r = service.register(request.username());
            return ResponseEntity.ok(new Response(r.username(), r.created(),
                    r.status().name(), r.beautyClass() == null ? null : r.beautyClass().name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.adapter.in.web.*'`
Expected: BUILD SUCCESSFUL, 6 tests passed.

- [ ] **Step 6: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/adapter/in/web/ManualDiscoveryController.java \
        crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/
git commit -m "feat(crawler): 수동 발굴 등록 API — X-Api-Token 인증, 토큰 미설정 시 fail-closed"
```

---

### Task 3: 설정·배포 연결 (application.yml, compose, Caddy, 문서)

**Files:**
- Modify: `crawler/src/main/resources/application.yml` (crawler: 블록 끝, `schedule:` 앞)
- Modify: `deploy/compose.yaml` (crawler 서비스 environment)
- Modify: `deploy/Caddyfile`
- Modify: `deploy/README.md`
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 표, §7 결정 기록)

**Interfaces:**
- Consumes: Task 2의 프로퍼티 키 `crawler.manual-discovery.token`, 경로 `/api/manual-discoveries`.
- Produces: 외부 URL `https://<API_DOMAIN>/crawler/api/manual-discoveries`, 서버 `.env` 키 `MANUAL_DISCOVERY_TOKEN`.

- [ ] **Step 1: application.yml에 토큰 프로퍼티 추가**

`crawler:` 블록의 `reels:` 항목 뒤, `schedule:` 앞에 추가:

```yaml
  manual-discovery:
    token: ${MANUAL_DISCOVERY_TOKEN:}  # 크롬 익스텐션 수동 등록 API 토큰 — 미설정 시 API 비활성(503)
```

- [ ] **Step 2: compose crawler 서비스에 환경변수 추가**

`deploy/compose.yaml`의 crawler 서비스 `environment:`에서 `JAVA_OPTS` 줄 위에 추가:

```yaml
      # 크롬 익스텐션 수동 발굴 등록 API 토큰 — 미설정 시 해당 API 비활성(fail-closed)
      MANUAL_DISCOVERY_TOKEN: ${MANUAL_DISCOVERY_TOKEN:-}
```

- [ ] **Step 3: Caddyfile에 등록 API 경로만 공개**

`deploy/Caddyfile`의 `handle /internal/ons-relay/*` 블록 뒤, 마지막 `handle` 블록 앞에 추가:

```
	# 크롬 익스텐션 수동 발굴 등록 — crawler에서 이 경로만 외부 공개(토큰은 crawler가 검증)
	handle /crawler/api/manual-discoveries {
		uri strip_prefix /crawler
		reverse_proxy crawler:8080
	}
```

(탭 들여쓰기 — 기존 블록과 동일하게.)

- [ ] **Step 4: deploy/README.md에 운영 항목 추가**

§4-1 스타일에 맞춰 crawler 관련 서술 근처(문서 하단 운영 섹션)에 추가:

```markdown
## 수동 발굴 등록 API (크롬 익스텐션, 07-22~)
- `POST https://api.hypenow.io/crawler/api/manual-discoveries` — Caddy가 crawler의 이 경로만 공개.
  헤더 `X-Api-Token` 필요, 서버 `.env`에 `MANUAL_DISCOVERY_TOKEN`(강한 랜덤 값) 설정 후 crawler 재기동.
  토큰 미설정이면 API는 503(fail-closed). 등록된 계정은 DISCOVERED로 들어가 기존 qualify→beauty가 처리.
- 익스텐션은 별도 저장소 `hypenow-extension` — 옵션에 엔드포인트 URL·토큰을 넣어 사용.
```

- [ ] **Step 5: ARCHITECTURE.md 갱신**

- §5 작업 트랙 표에 행 추가 (표 형식은 기존 행을 따른다): 수동 발굴 등록 API + 크롬 익스텐션(별도 저장소) — 완료 상태로.
- §7 결정 기록에 한 줄 추가:

```markdown
- 07-22 — 수동 발굴 등록 API(`POST /api/manual-discoveries`, X-Api-Token) 신설, Caddy로 해당 경로만 공개.
  크롬 익스텐션은 레포 밖 별도 저장소(`hypenow-extension`). 수동 등록도 기존 qualify→beauty 파이프라인 동일 적용,
  출처는 influencer_discovery keyword `수동:크롬`.
```

- [ ] **Step 6: 전체 crawler 테스트 통과 확인**

Run: `./gradlew :crawler:test`
Expected: BUILD SUCCESSFUL (Testcontainers 기반 테스트가 있으므로 Docker 데몬 필요).

- [ ] **Step 7: 커밋**

```bash
git add crawler/src/main/resources/application.yml deploy/compose.yaml deploy/Caddyfile deploy/README.md ARCHITECTURE.md
git commit -m "feat(deploy): 수동 발굴 등록 API 외부 공개 — Caddy 경로 라우팅 + MANUAL_DISCOVERY_TOKEN 주입"
```

---

### Task 4: 익스텐션 저장소 스캐폴드 + URL 파서 (별도 저장소, TDD)

**Files:** (모두 `/Users/dongju/project/current/soma/hypenow/hypenow-extension` — 새 git 저장소)
- Create: `lib/parse.js`
- Test: `test/parse.test.js`
- Create: `.gitignore`

**Interfaces:**
- Produces: ES 모듈 `parseInstagramUsername(url: string) → string | null` — 인스타그램 프로필 URL이면 소문자 username, 아니면 null. Task 5의 popup.js가 import.

- [ ] **Step 1: 저장소 초기화**

```bash
mkdir -p /Users/dongju/project/current/soma/hypenow/hypenow-extension
cd /Users/dongju/project/current/soma/hypenow/hypenow-extension
git init
printf '.DS_Store\n' > .gitignore
```

- [ ] **Step 2: 실패하는 테스트 작성**

`test/parse.test.js`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseInstagramUsername } from '../lib/parse.js';

test('프로필 URL에서 username을 추출한다', () => {
  assert.equal(parseInstagramUsername('https://www.instagram.com/some.user/'), 'some.user');
  assert.equal(parseInstagramUsername('https://instagram.com/Some_User'), 'some_user');
  assert.equal(parseInstagramUsername('https://www.instagram.com/some.user/?igsh=abc'), 'some.user');
});

test('프로필 하위 탭(reels·tagged)도 username을 추출한다', () => {
  assert.equal(parseInstagramUsername('https://www.instagram.com/some.user/reels/'), 'some.user');
  assert.equal(parseInstagramUsername('https://www.instagram.com/some.user/tagged/'), 'some.user');
});

test('스토리 URL은 두 번째 세그먼트가 username이다', () => {
  assert.equal(parseInstagramUsername('https://www.instagram.com/stories/some.user/123456/'), 'some.user');
});

test('프로필이 아닌 경로는 null', () => {
  assert.equal(parseInstagramUsername('https://www.instagram.com/p/DAbCdEf/'), null);
  assert.equal(parseInstagramUsername('https://www.instagram.com/reel/DAbCdEf/'), null);
  assert.equal(parseInstagramUsername('https://www.instagram.com/explore/'), null);
  assert.equal(parseInstagramUsername('https://www.instagram.com/'), null);
});

test('인스타그램이 아닌 URL·불량 입력은 null', () => {
  assert.equal(parseInstagramUsername('https://example.com/some.user/'), null);
  assert.equal(parseInstagramUsername('chrome://extensions'), null);
  assert.equal(parseInstagramUsername(''), null);
  assert.equal(parseInstagramUsername('not a url'), null);
});
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd /Users/dongju/project/current/soma/hypenow/hypenow-extension && node --test`
Expected: FAIL — `lib/parse.js` 모듈 없음(ERR_MODULE_NOT_FOUND).

- [ ] **Step 4: 최소 구현**

`lib/parse.js`:

```js
// 인스타그램 URL에서 프로필 username을 추출한다. 프로필 페이지가 아니면 null.
const RESERVED = new Set([
  'p', 'reel', 'reels', 'tv', 'explore', 'stories', 'accounts', 'direct',
  'about', 'developer', 'legal', 'directory', 'lite', 'graphql', 'session',
]);
const USERNAME = /^[a-zA-Z0-9._]{1,30}$/;

export function parseInstagramUsername(url) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return null;
  }
  if (!/(^|\.)instagram\.com$/.test(u.hostname)) return null;
  const segs = u.pathname.split('/').filter(Boolean);
  if (segs.length === 0) return null;
  const first = segs[0];
  if (RESERVED.has(first)) {
    // 스토리 뷰어(/stories/{username}/...)에서는 두 번째 세그먼트가 username
    if (first === 'stories' && segs[1] && USERNAME.test(segs[1])) return segs[1].toLowerCase();
    return null;
  }
  if (!USERNAME.test(first)) return null;
  return first.toLowerCase();
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd /Users/dongju/project/current/soma/hypenow/hypenow-extension && node --test`
Expected: pass 5, fail 0.

- [ ] **Step 6: 커밋**

```bash
cd /Users/dongju/project/current/soma/hypenow/hypenow-extension
git add .gitignore lib/parse.js test/parse.test.js
git commit -m "feat: 인스타그램 URL → username 파서 (node --test)"
```

---

### Task 5: 익스텐션 팝업·옵션·manifest

**Files:** (모두 `/Users/dongju/project/current/soma/hypenow/hypenow-extension`)
- Create: `manifest.json`
- Create: `popup.html`, `popup.js`
- Create: `options.html`, `options.js`
- Create: `README.md`

**Interfaces:**
- Consumes: Task 4의 `parseInstagramUsername`, Task 3의 외부 URL 규약(전체 엔드포인트 URL을 옵션에 저장 — 예: `https://api.hypenow.io/crawler/api/manual-discoveries`), 헤더 `X-Api-Token`, 응답 `{username, created, status, beautyClass}` / `{error}`.
- Produces: `chrome.storage.sync` 키 `endpointUrl`, `apiToken`.

- [ ] **Step 1: manifest.json 작성**

```json
{
  "manifest_version": 3,
  "name": "hypenow 발굴 등록",
  "version": "0.1.0",
  "description": "보고 있는 인스타그램 프로필을 hypenow 발굴 파이프라인에 등록",
  "action": {
    "default_popup": "popup.html",
    "default_title": "hypenow 발굴 등록"
  },
  "options_page": "options.html",
  "permissions": ["activeTab", "storage"],
  "host_permissions": ["https://*/*", "http://localhost/*", "http://127.0.0.1/*"]
}
```

- [ ] **Step 2: popup.html 작성**

```html
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <style>
    body { font-family: system-ui, sans-serif; width: 260px; padding: 12px; margin: 0; }
    h1 { font-size: 14px; margin: 0 0 8px; }
    #username { font-weight: 600; }
    #state { margin-top: 8px; font-size: 13px; white-space: pre-line; }
    #state.error { color: #c0392b; }
    #state.ok { color: #1e824c; }
    button { margin-top: 8px; width: 100%; padding: 6px 0; cursor: pointer; }
    a { font-size: 12px; }
  </style>
</head>
<body>
  <h1>hypenow 발굴 등록</h1>
  <div id="username"></div>
  <button id="register" hidden>발굴 등록</button>
  <div id="state"></div>
  <a id="open-options" href="#" hidden>옵션 열기</a>
  <script type="module" src="popup.js"></script>
</body>
</html>
```

- [ ] **Step 3: popup.js 작성**

```js
import { parseInstagramUsername } from './lib/parse.js';

const stateEl = document.getElementById('state');
const btn = document.getElementById('register');
const optionsLink = document.getElementById('open-options');

optionsLink.onclick = (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
};

function show(text, cls) {
  stateEl.textContent = text;
  stateEl.className = cls ?? '';
}

async function register(endpointUrl, apiToken, username) {
  btn.disabled = true;
  show('등록 중…');
  try {
    const res = await fetch(endpointUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Api-Token': apiToken },
      body: JSON.stringify({ username }),
    });
    const body = await res.json().catch(() => ({}));
    if (res.ok) {
      if (body.created) {
        show('신규 등록됨 — 판정 대기(DISCOVERED)', 'ok');
      } else {
        const beauty = body.beautyClass ? ` · ${body.beautyClass}` : '';
        show(`이미 등록된 계정 · ${body.status}${beauty}`, 'ok');
      }
    } else if (res.status === 401) {
      show('토큰이 틀렸습니다 — 옵션을 확인하세요.', 'error');
      optionsLink.hidden = false;
    } else {
      show(`등록 실패 (${res.status}): ${body.error ?? '알 수 없는 오류'}`, 'error');
    }
  } catch (e) {
    show(`서버에 연결하지 못했습니다.\n${e.message}`, 'error');
  } finally {
    btn.disabled = false;
  }
}

async function init() {
  const { endpointUrl, apiToken } = await chrome.storage.sync.get(['endpointUrl', 'apiToken']);
  if (!endpointUrl || !apiToken) {
    show('옵션에서 엔드포인트 URL과 토큰을 먼저 설정하세요.', 'error');
    optionsLink.hidden = false;
    return;
  }
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const username = parseInstagramUsername(tab?.url ?? '');
  if (!username) {
    show('인스타그램 프로필 페이지가 아닙니다.', 'error');
    return;
  }
  document.getElementById('username').textContent = '@' + username;
  btn.hidden = false;
  btn.onclick = () => register(endpointUrl, apiToken, username);
}

init();
```

- [ ] **Step 4: options.html / options.js 작성**

`options.html`:

```html
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <style>
    body { font-family: system-ui, sans-serif; max-width: 480px; padding: 16px; }
    label { display: block; margin-top: 12px; font-size: 13px; font-weight: 600; }
    input { width: 100%; padding: 6px; margin-top: 4px; box-sizing: border-box; }
    button { margin-top: 16px; padding: 6px 16px; cursor: pointer; }
    #saved { margin-left: 8px; color: #1e824c; font-size: 13px; }
    p.hint { font-size: 12px; color: #666; }
  </style>
</head>
<body>
  <h1>hypenow 발굴 등록 — 설정</h1>
  <label for="endpointUrl">등록 엔드포인트 URL</label>
  <input id="endpointUrl" type="url"
         placeholder="https://api.hypenow.io/crawler/api/manual-discoveries">
  <p class="hint">SSH 터널 로컬 테스트: http://localhost:8080/api/manual-discoveries</p>
  <label for="apiToken">API 토큰 (X-Api-Token)</label>
  <input id="apiToken" type="password" placeholder="서버 .env의 MANUAL_DISCOVERY_TOKEN 값">
  <button id="save">저장</button><span id="saved" hidden>저장됨</span>
  <script src="options.js"></script>
</body>
</html>
```

`options.js`:

```js
const endpointEl = document.getElementById('endpointUrl');
const tokenEl = document.getElementById('apiToken');
const savedEl = document.getElementById('saved');

chrome.storage.sync.get(['endpointUrl', 'apiToken']).then(({ endpointUrl, apiToken }) => {
  if (endpointUrl) endpointEl.value = endpointUrl;
  if (apiToken) tokenEl.value = apiToken;
});

document.getElementById('save').onclick = async () => {
  await chrome.storage.sync.set({
    endpointUrl: endpointEl.value.trim(),
    apiToken: tokenEl.value.trim(),
  });
  savedEl.hidden = false;
  setTimeout(() => { savedEl.hidden = true; }, 1500);
};
```

- [ ] **Step 5: README.md 작성**

````markdown
# hypenow 발굴 등록 (크롬 익스텐션)

보고 있는 인스타그램 프로필을 hypenow crawler의 발굴 파이프라인(DISCOVERED)에 한 클릭으로 등록한다.
이후 판정(qualify)→뷰티판정(beauty)은 서버의 기존 파이프라인이 처리한다.

## 설치 (개발자 모드)
1. `chrome://extensions` → 우상단 **개발자 모드** 켬
2. **압축해제된 확장 프로그램을 로드** → 이 디렉토리 선택

## 설정
익스텐션 아이콘 우클릭 → 옵션:
- **등록 엔드포인트 URL**: `https://api.hypenow.io/crawler/api/manual-discoveries`
  (SSH 터널 로컬 테스트는 `http://localhost:8080/api/manual-discoveries`)
- **API 토큰**: 서버 `.env`의 `MANUAL_DISCOVERY_TOKEN` 값

## 사용
인스타그램 프로필 페이지(프로필 홈·릴스 탭·스토리 뷰어)에서 아이콘 클릭 → [발굴 등록].
- 신규면 "신규 등록됨", 이미 있으면 현재 상태(QUALIFIED 등 + 뷰티 분류)를 보여준다.

## 테스트
```bash
node --test
```

## 서버 측 계약
`hypenow-backend`의 `docs/superpowers/specs/2026-07-22-manual-discovery-extension-design.md` 참고.
````

- [ ] **Step 6: 테스트·수동 검증**

Run: `cd /Users/dongju/project/current/soma/hypenow/hypenow-extension && node --test`
Expected: pass 5, fail 0.

수동 검증 (실행자가 직접 크롬을 못 여는 경우 사용자에게 안내로 대체):
1. `chrome://extensions`에서 압축해제 로드.
2. 백엔드 로컬 기동: 워크트리에서 `MANUAL_DISCOVERY_TOKEN=dev-token ./gradlew :crawler:bootRun`.
3. 옵션에 `http://localhost:8080/api/manual-discoveries` / `dev-token` 저장.
4. 인스타 프로필 페이지에서 팝업 → 등록 → "신규 등록됨" 확인, `influencer`·`influencer_discovery`(keyword `수동:크롬`) 행 확인.

- [ ] **Step 7: 커밋**

```bash
cd /Users/dongju/project/current/soma/hypenow/hypenow-extension
git add manifest.json popup.html popup.js options.html options.js README.md
git commit -m "feat: MV3 팝업·옵션 — 현재 탭 프로필을 수동 발굴 API로 등록"
```

---

### Task 6: 백엔드 최종 검증·마무리

**Files:** 없음 (검증·브랜치 마무리)

- [ ] **Step 1: 백엔드 전체 테스트**

Run: 워크트리에서 `./gradlew test`
Expected: BUILD SUCCESSFUL (Docker 데몬 필요 — Testcontainers).

- [ ] **Step 2: curl 스모크 테스트 (로컬)**

```bash
MANUAL_DISCOVERY_TOKEN=dev-token ./gradlew :crawler:bootRun   # 별도 셸, DB 컨테이너 docker start 선행
curl -s -X POST http://localhost:8080/api/manual-discoveries \
  -H 'Content-Type: application/json' -H 'X-Api-Token: dev-token' \
  -d '{"username":"@Manual.Test"}'
```

Expected: `{"username":"manual.test","created":true,"status":"DISCOVERED","beautyClass":null}` — 같은 요청 반복 시 `"created":false`. 토큰 없이 보내면 401.
검증 후 테스트 행 정리: `docker exec -i crawler-postgres-1 psql -U crawler -d crawler -c "delete from influencer_discovery where keyword='수동:크롬' and influencer_id in (select id from influencer where username='manual.test'); delete from influencer where username='manual.test';"`

- [ ] **Step 3: 브랜치 마무리**

superpowers:finishing-a-development-branch 스킬로 develop 대상 PR 생성 (제목 예: `feat(crawler): 수동 발굴 등록 API + Caddy 공개 경로`).
