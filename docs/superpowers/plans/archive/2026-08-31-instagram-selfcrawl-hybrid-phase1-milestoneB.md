> 상태: ✅ 구현됨 · 마일스톤 B 전 11태스크 완료 · 회귀 통과(instagram-source 113 / monitoring 783, 0 failures, 토글 off=행동 변화 0) (2026-08-31)
> 범위: Phase 1 마일스톤 B(자체크롤 백엔드 신설, **토글 off = 행동 변화 0**). **선행: 마일스톤 A 완료됨**(seam·모듈 존재).
> 구현: branch `claude/optimistic-knuth-3212ef` 커밋 8e9d5fdf~449a1f4c. 후속 마일스톤 C는 `plans/`에 활성 유지.
> C 이월 항목: ①K≈3 워커 어피니티(효율 최적화) ②og 표면·og/wpi A/B ③런타임 app_setting 토글·킬스위치·Micrometer 메트릭 ④geo:kr 실엔드포인트 A/B·Hiker 지연 벤치·dev/staging e2e ⑤DirectComment 페이지 간 pageDelay(라이브러리 밖 caller 책임) ⑥Failover에 self發 미분류 RuntimeException catch-all 폴백(개통 전 하드닝) ⑦IG_COMMENT_DOC_ID/FRIENDLY_NAME·DATAIMPULSE_* 운영 env 주입.
> 설계 정본: `docs/superpowers/specs/2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md`. 선행 실측: 메모리 `hiker-self-scraping-breakeven.md`(embed 08-31 재검증 = 살아있음).

# 인스타그램 수집 하이브리드 — Phase 1 마일스톤 B(자체크롤 백엔드) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `instagram-source` 모듈에 자체크롤 백엔드(`SelfCrawlBackend`)를 신설한다 — 저수준 HTTP 전송(프록시 로테이션·geo:kr·fastfail)·문서표면 fetcher(embed 단건·프로필)·DirectComment·에러 taxonomy·표면별 서킷을 갖추고, `FailoverInstagramSource`에 "자체 1순위 + Hiker 폴백" 정책을 채운다. **자체크롤 토글은 기본 off(=Hiker)로 배선** — 코드가 들어가도 런타임 동작은 마일스톤 A와 동일하고, 점진 개통·실측은 마일스톤 C.

**Architecture:** 순수 JDK(`java.net.http`) 저수준 전송 위에 표면별 fetcher를 얹고, `SelfCrawlBackend`가 이를 `InstagramSource` 계약으로 정규화한다. `FailoverInstagramSource`가 (자체 → Hiker) 폴백·에러 taxonomy 라우팅·서킷을 관장한다. 하드게이트 3종(태그드·해시태그 발견, by-id 작성자)은 자체 백엔드가 `UnsupportedOperationException`을 던져 정책이 곧장 Hiker로 라우팅. 신 코드는 `com.celfit.instagram.source.self` 서브패키지, 공개 계약(InstagramSource·DTO·HikerBackend·FailoverInstagramSource)은 top-level 유지. **DB 쓰기·Spring 의존 없음**(빈 배선은 monitoring).

**Tech Stack:** Java 21, `java.net.http.HttpClient`(HTTP/2 강제), Jackson 3(`tools.jackson`, web_profile_info JSON), JUnit 5 + AssertJ, JDK `com.sun.net.httpserver.HttpServer`(전송 mock). 프록시=DataImpulse(레지덴셜·모바일). Spring·Testcontainers 불사용(모듈).

---

## 검증된 핵심 사실 (착수 전 필독 — 08-31 실측)

**embed 단건 = 살아있음, 정확 지표(08-31 재검증):** `/p/{code}/embed/captioned/` 200 응답에 **서버렌더 로케일 텍스트**로 정확값이 옴 — `Accept-Language: en-US`면 `N likes`·`N comments`(영상은 `N views`), `ko`면 `좋아요 N개`·`댓글 N개`. **JSON 아님**(과거 `shortcode_media` 키 없음), doc_id 불필요, **JS 불필요(raw HTTP로 파싱 가능)**. 실측: nasa 게시물 `좋아요 484,902개`·`댓글 3,355개`(정확·라이브). ⚠️삭제된 게시물은 빈 셸("삭제됨" 메시지) — 정상 404-류 처리. ⚠️데이터센터 단일 IP로 버스트하면 rate-limit 빈 응답 → **프록시 로테이션 필수**(이 마일스톤이 해결).

**저수준 전송(crawler `JdkInstagramWebClient` 실측 이식 대상):** `java.net.http`, **HTTP/2 강제**(IG는 HTTP/1.1 web_profile_info를 봇판정 429), 요청마다 새 `HttpClient`(=새 CONNECT 터널=새 exit IP, 종료는 `shutdownNow()`), 정적초기화로 `jdk.http.auth.tunneling.disabledSchemes` 클리어(프록시 Basic auth over CONNECT), 프록시=`ProxySelector.of(InetSocketAddress)`+`Authenticator`(user:pass), gzip 수동 gunzip, 401은 "WWW-Authenticate header missing for response code 401" IOException을 잡아 `status 401`로 복원. `x-ig-app-id=936619743392459`, UA=`Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36`.

**geo:kr 핀(하니스 `with_country` 실측):** DataImpulse 국가 라우팅 = 프록시 **username**에 `__cr.kr` 삽입(이미 `__` 파라미터 블록 있으면 `;cr.kr` 추가). 예: `http://u:p@h:823` → `http://u__cr.kr:p@h:823`. 전송 성공률 95→100%·지연 -20% 실측(레지덴셜).

**에러 taxonomy(하니스 `outcomes.py` 실측):** 전송 예외는 `__cause__/__context__` 체인 6홉을 걸어 분류 — `ProxyError`/TLS(ssl·tls·certificate·buffer_underflow·handshake 키워드 or SSLException)/`ConnectTimeout`/`ConnectError`/`RemoteProtocolError`/`Timeout`. HTTP 상태: 429·401·404·403·기타. 200이 `<!DOCTYPE`/`<html`로 시작하면 로그인 벽(challenge/checkpoint 포함 시 CHALLENGE). JSON 파스 실패 = PARSE_FAIL.

**web_profile_info(프로필, 하니스 실측):** `https://www.instagram.com/api/v1/users/web_profile_info/?username={}`, x-ig-app-id 헤더 필요(401-prone → 모바일 프록시), 루트 `data.data.user`, `followers=edge_followed_by.count`·`following=edge_follow.count`·`posts=edge_owner_to_timeline_media.count`·최근 12=`edge_owner_to_timeline_media.edges[:12]`(각 `node`, `taken_at=taken_at_timestamp`, 지표는 `edge_media_preview_like.count`·`edge_media_to_comment.count`·`video_view_count|play_count`).

**DirectComment(crawler `DirectCommentFetcher` 실측 이식):** `https://www.instagram.com/api/graphql` POST, 헤더 `x-ig-app-id`+`x-fb-lsd`, `doc_id`+`fb_api_req_friendly_name`는 config(`DirectCommentProperties`), **lsd 부트스트랩**=청크당 포스트 페이지 GET 1회(`HandshakeExtractor.lsdFrom`), 커서 페이지네이션(`variables.after`), 무진전 가드.

**A 완료 후 모듈 현 상태:** `com.celfit.instagram.source`에 `InstagramSource`(10메서드), DTO 5·결과 record 5, `HikerHttp`·예외 6·`ShortCodes`, `HikerBackend`, `FailoverInstagramSource`(현재 Hiker 단독 위임 pass-through). monitoring `HikerConfig`가 `FailoverInstagramSource(HikerBackend(체인))`을 `InstagramSource` 빈으로 노출.

---

## 스코핑 결정 (계획 리뷰에서 확인 요망 — 스펙 대비 의도적 조정)

1. **로테이션 입도 = K=1(요청당 새 터널) + 자체측 재시도 3회 채택, K≈3은 C로 연기.** 스펙/개요는 레지덴셜 K≈3을 명시하나, 하니스 실측상 K≈3의 이득은 "커버리지 아니라 효율(재시도·대역폭↓)"이고 K=1+재시도가 crawler 운영 87%의 정본이다. **★ K=1은 재시도와 반드시 짝이다:** K=1 레지덴셜은 401 ~8~12%(공유 IP의 이전 테넌트가 익명 예산 소진 — 풀 고갈 아님, 그 IP의 잔여 예산 문제)인데, 이걸 재시도(=다음 시도가 새 IP)로 회복해야 자체 성공률이 유지되고 Hiker 폴백 유출(=비용 누수)을 막는다(crawler `BLOCK_MAX_ATTEMPTS=3`). 재시도 없이 401→즉시 Hiker면 8~12%가 그대로 Hiker로 새어 절감이 깎인다. **B는 K=1 + 회복가능 실패(401·전송·429) 시 최대 3회 재시도(각 시도 새 IP)**로 가고, K≈3 워커 어피니티는 C의 실측 최적화로 남긴다. IP 풀은 로테이팅 레지덴셜/모바일이라 고갈되지 않으며(crawler가 K=1로 운영), 풀 크기가 걸리는 유일한 경우=geo:kr(KR 서브셋 축소)라 geo:kr은 기본 off·C에서 A/B(결정 5·§10-1).
2. **프로필 표면 = wpi(web_profile_info) 채택, og는 C의 A/B로.** 스펙 기본값은 og(문서표면)이나 og HTML 파싱은 미검증(스크래치패드 소실)이고 wpi는 하니스에 구조 확보. B는 wpi(모바일 프록시)로 구현하고, og 표면·og/wpi A/B는 C에서. (embed 단건은 문서표면 그대로 채택.)
3. **자체 토글 = 정적 config 플래그(기본 false).** 런타임 app_setting 토글·킬스위치는 C. B는 빈 생성 시점 플래그 `self-enabled=false`로 배선 → 프로덕션은 전량 Hiker(행동 변화 0), 테스트는 플래그 true로 자체 경로 검증.
4. **프리미엄 지표(저장·공유·리포스트)·`fetchClipCounts`·릴스 보강 = 자체 미구현.** self 백엔드에서 `UnsupportedOperationException` → 정책이 Hiker로. (스펙 §2·§5-3 그대로.)
5. **실측(프록시·실 IG·geo:kr·지연·안정성) = 전부 C.** B는 JDK `HttpServer` mock + 실 응답 픽스처 단위테스트만. 단, **embed·wpi 파서 픽스처 1회는 프록시로 포착**(Task 6·7 첫 스텝) — 파서를 진짜 응답에 대고 TDD하기 위한 바운디드 라이브 액션.

---

## File Structure (마일스톤 B 종료 시점)

**신설(모듈 서브패키지 `com.celfit.instagram.source.self`):**
- `ProxyTier.java` — RESIDENTIAL / MOBILE
- `ProxyUrls.java` — geo:kr `withCountry` util (+ 필요 시 sessid)
- `ProxyConfig.java` — 순수 record(레지·모바일 URL·타임아웃·geoKr), `urlFor(tier)`
- `SelfErrorClass.java` — 라우팅용 에러 분류 enum
- `SelfErrorClassifier.java` — 상태·예외·본문 → SelfErrorClass
- `SelfCrawlException.java` — SelfErrorClass 실은 런타임 예외
- `SelfResponse.java` — record(status, body)
- `SelfHttpClient.java` — 저수준 전송(HTTP/2·프록시 요청당·헤더·gunzip·401복원·fastfail)
- `SelfRetry.java` — 회복가능 실패(401·전송·429) 시 최대 3회 재시도(각 시도 새 IP=K=1), 비회복(NOT_FOUND·400·로그인벽)은 즉시 중단
- `SurfaceCircuitBreaker.java` — 표면별 연속 블록 트립 + 전역 킬
- `EmbedPostFetcher.java` — `/embed/captioned/` → PostInfo (로케일 텍스트 파싱)
- `WpiProfileFetcher.java` — web_profile_info → ProfileInfo + recent PostInfo
- `DirectCommentFetcher.java` + `HandshakeExtractor.java` — 자체 댓글(lsd·graphql)
- `SelfCrawlBackend.java` — `implements InstagramSource`, fetcher 정규화 + 하드게이트 미구현
- 테스트: 각 `*Test.java` + `src/test/resources/self/*`(embed·wpi 픽스처)

**변경(top-level):**
- `FailoverInstagramSource.java` — pass-through → 정책(자체 1순위·폴백·taxonomy·서킷·토글)

**monitoring 변경:**
- `hiker/InstagramProxyProperties.java`(신규 `@ConfigurationProperties("monitoring.proxy")`) 또는 기존 config 확장
- `config/HikerConfig.java` — SelfCrawlBackend 조립 + Failover에 주입(토글 off)
- `application.yml` — `monitoring.proxy.*`(DATAIMPULSE env)·`self-enabled:false`
- `deploy/compose.yaml`·`deploy/.env.example` — monitoring 서비스에 `DATAIMPULSE_*`

**빌드/테스트:** 모듈 `./gradlew :instagram-source:test`(Docker 불필요). monitoring 회귀 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :monitoring:test`.

---

## Task 1: ProxyTier + ProxyUrls(geo:kr) + ProxyConfig

**Files:**
- Create: `instagram-source/src/main/java/com/celfit/instagram/source/self/ProxyTier.java`
- Create: `instagram-source/src/main/java/com/celfit/instagram/source/self/ProxyUrls.java`
- Create: `instagram-source/src/main/java/com/celfit/instagram/source/self/ProxyConfig.java`
- Test: `instagram-source/src/test/java/com/celfit/instagram/source/self/ProxyUrlsTest.java`

- [x] **Step 1: 실패 테스트(geo:kr 변환 — 하니스 검증 assertion 이식)**

`ProxyUrlsTest.java`:
```java
package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProxyUrlsTest {

	@Test
	void withCountry_는_username에_cr_국가를_붙인다() {
		assertThat(ProxyUrls.withCountry("http://u:p@h:823", "kr"))
				.isEqualTo("http://u__cr.kr:p@h:823");
	}

	@Test
	void withCountry_는_기존_파라미터블록이_있으면_세미콜론으로_잇는다() {
		assertThat(ProxyUrls.withCountry("http://u__sessid.a:p@h:823", "kr"))
				.isEqualTo("http://u__sessid.a;cr.kr:p@h:823");
	}

	@Test
	void withCountry_는_비밀번호_특수문자와_포트없음도_처리한다() {
		assertThat(ProxyUrls.withCountry("http://user:p@ss@h", "kr"))
				.isEqualTo("http://user__cr.kr:p@ss@h");
	}
}
```

- [x] **Step 2: 테스트 실패 확인** — Run: `./gradlew :instagram-source:test --tests "*ProxyUrlsTest"` → FAIL(심볼 없음).

- [x] **Step 3: ProxyTier**
```java
package com.celfit.instagram.source.self;

/** 자체크롤 프록시 티어 — 문서표면·회복가능 경로는 RESIDENTIAL, 401-민감(web_profile_info)은 MOBILE. */
public enum ProxyTier {
	RESIDENTIAL,
	MOBILE
}
```

- [x] **Step 4: ProxyUrls (URI 대신 수동 파싱 — userinfo/비밀번호 특수문자 안전)**
```java
package com.celfit.instagram.source.self;

/**
 * DataImpulse 프록시 URL 조작 유틸(하니스 with_country 이식). 국가 라우팅은 username에 매개변수
 * 블록(__ 시작, ; 구분)으로 실린다: exit IP를 country로 고정하려면 __cr.<country>를 붙인다.
 * URI 파서 대신 scheme://userinfo@hostport를 수동 분해 — 프록시 password의 @·: 등 특수문자가
 * URI.create를 깨뜨릴 수 있어서다.
 */
public final class ProxyUrls {

	private ProxyUrls() {}

	/** exit IP를 country(예: "kr")로 고정한 프록시 URL. */
	public static String withCountry(String proxyUrl, String country) {
		int sep = proxyUrl.indexOf("://");
		String scheme = proxyUrl.substring(0, sep);
		String rest = proxyUrl.substring(sep + 3);
		int at = rest.lastIndexOf('@');
		String userinfo = at < 0 ? "" : rest.substring(0, at);
		String hostport = at < 0 ? rest : rest.substring(at + 1);
		int colon = userinfo.indexOf(':');
		String user = colon < 0 ? userinfo : userinfo.substring(0, colon);
		String pass = colon < 0 ? null : userinfo.substring(colon + 1);
		String newUser = user.contains("__") ? user + ";cr." + country : user + "__cr." + country;
		String newUserinfo = pass == null ? newUser : newUser + ":" + pass;
		return scheme + "://" + newUserinfo + "@" + hostport;
	}
}
```

- [x] **Step 5: ProxyConfig**
```java
package com.celfit.instagram.source.self;

import java.time.Duration;

/**
 * 자체크롤 프록시 설정 — 순수 값(Spring 무관). 값 주입은 소비 모듈(monitoring)이 한다.
 * geoKr=true면 exit IP를 KR로 핀(전송 성공률·지연 개선). URL 미설정 티어는 null(=직접 연결 폴백).
 */
public record ProxyConfig(String residentialUrl, String mobileUrl, Duration requestTimeout, boolean geoKr) {

	/** 티어의 프록시 URL(geoKr면 __cr.kr 적용). 미설정이면 null. */
	public String urlFor(ProxyTier tier) {
		String url = switch (tier) {
			case RESIDENTIAL -> residentialUrl;
			case MOBILE -> mobileUrl;
		};
		if (url == null || url.isBlank()) {
			return null;
		}
		return geoKr ? ProxyUrls.withCountry(url, "kr") : url;
	}
}
```

- [x] **Step 6: 테스트 통과** — Run: `./gradlew :instagram-source:test --tests "*ProxyUrlsTest"` → PASS(3).

- [x] **Step 7: 커밋**
```bash
git add instagram-source/src/main/java/com/celfit/instagram/source/self/ instagram-source/src/test/java/com/celfit/instagram/source/self/ProxyUrlsTest.java
git commit -m "feat(instagram-source): 자체크롤 프록시 기반 - ProxyTier·ProxyConfig·geo:kr 변환"
```

---

## Task 2: 에러 taxonomy (SelfErrorClass + Classifier + SelfCrawlException)

**Files:**
- Create: `self/SelfErrorClass.java`, `self/SelfErrorClassifier.java`, `self/SelfCrawlException.java`, `self/SelfResponse.java`
- Test: `self/SelfErrorClassifierTest.java`

에러 분류는 스펙 §8-1 라우팅과 직결된다: 구조적400=즉시 Hiker / 회복가능401=로테이트+재시도 / 전송=재시도(새 터널) / 로그인벽=다음 표면→Hiker / 404=종료(폴백 안 함).

- [x] **Step 1: SelfResponse record**
```java
package com.celfit.instagram.source.self;

/** 저수준 전송 응답 — 상태코드 + 본문(gunzip 완료). 401은 전송이 복원해 넣는다. */
public record SelfResponse(int status, String body) {}
```

- [x] **Step 2: SelfErrorClass enum**
```java
package com.celfit.instagram.source.self;

/**
 * 자체크롤 실패 분류 — FailoverInstagramSource의 라우팅 결정에 쓴다(스펙 §8-1).
 */
public enum SelfErrorClass {
	/** 200 + 파싱 성공(예외 아님, 분류 완결성용). */
	OK,
	/** 익명 한도 401 — 로테이트+재시도로 회복 가능. */
	RECOVERABLE_401,
	/** 429 봇판정/과열 — 재시도(새 터널). */
	RATE_LIMIT_429,
	/** 전송 실패(TLS/Connect/Proxy/Protocol/Timeout) — geo:kr + 새 터널 1회 재시도. */
	TRANSPORT,
	/** 200인데 로그인 벽 HTML — 이 표면 소진, 다음 표면/Hiker. */
	LOGIN_WALL,
	/** 구조적 400(계정 버그) — 재시도 무의미, 즉시 Hiker. */
	STRUCTURAL_400,
	/** 404 — 계정/게시물 부재. 종료(스킵), 폴백 안 함. */
	NOT_FOUND,
	/** 403·기타 — Hiker 폴백. */
	OTHER
}
```

- [x] **Step 3: SelfCrawlException (분류 실은 예외)**
```java
package com.celfit.instagram.source.self;

/** 자체크롤 실패 — errorClass가 FailoverInstagramSource의 라우팅을 결정한다. */
public class SelfCrawlException extends RuntimeException {

	private final transient SelfErrorClass errorClass;

	public SelfCrawlException(SelfErrorClass errorClass, String message) {
		super(message);
		this.errorClass = errorClass;
	}

	public SelfCrawlException(SelfErrorClass errorClass, String message, Throwable cause) {
		super(message, cause);
		this.errorClass = errorClass;
	}

	public SelfErrorClass errorClass() {
		return errorClass;
	}
}
```

- [x] **Step 4: 실패 테스트 (분류 표 — 하니스 outcomes.py 이식)**

`SelfErrorClassifierTest.java`:
```java
package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class SelfErrorClassifierTest {

	@Test
	void 상태코드_분류() {
		assertThat(SelfErrorClassifier.ofStatus(401, "{}")).isEqualTo(SelfErrorClass.RECOVERABLE_401);
		assertThat(SelfErrorClassifier.ofStatus(429, "")).isEqualTo(SelfErrorClass.RATE_LIMIT_429);
		assertThat(SelfErrorClassifier.ofStatus(400, "")).isEqualTo(SelfErrorClass.STRUCTURAL_400);
		assertThat(SelfErrorClassifier.ofStatus(404, "")).isEqualTo(SelfErrorClass.NOT_FOUND);
		assertThat(SelfErrorClassifier.ofStatus(403, "")).isEqualTo(SelfErrorClass.OTHER);
	}

	@Test
	void 로그인벽_HTML_은_LOGIN_WALL() {
		assertThat(SelfErrorClassifier.ofStatus(200, "<!DOCTYPE html><html>...login...</html>"))
				.isEqualTo(SelfErrorClass.LOGIN_WALL);
	}

	@Test
	void TLS_핸드셰이크_예외는_TRANSPORT() {
		assertThat(SelfErrorClassifier.ofException(new SSLHandshakeException("handshake_failure")))
				.isEqualTo(SelfErrorClass.TRANSPORT);
		assertThat(SelfErrorClassifier.ofException(new IOException("Connection reset")))
				.isEqualTo(SelfErrorClass.TRANSPORT);
	}
}
```

- [x] **Step 5: 테스트 실패 확인** — Run: `./gradlew :instagram-source:test --tests "*SelfErrorClassifierTest"` → FAIL.

- [x] **Step 6: SelfErrorClassifier**
```java
package com.celfit.instagram.source.self;

import java.util.Locale;

/**
 * 상태코드·본문·전송 예외 → SelfErrorClass(스펙 §8-1). 하니스 outcomes.py의 분류 규칙 이식:
 * 전송 예외는 원인 체인을 걸어 TLS/Connect류를 잡고, 200이 HTML로 시작하면 로그인 벽으로 본다.
 */
public final class SelfErrorClassifier {

	private SelfErrorClassifier() {}

	/** 200 응답의 본문까지 보고 분류(성공/로그인벽 구분). */
	public static SelfErrorClass ofStatus(int status, String body) {
		if (status == 200) {
			String head = body == null ? "" : body.stripLeading().toLowerCase(Locale.ROOT);
			if (head.startsWith("<!doctype") || head.startsWith("<html")) {
				return SelfErrorClass.LOGIN_WALL;
			}
			return SelfErrorClass.OK;
		}
		return switch (status) {
			case 401 -> SelfErrorClass.RECOVERABLE_401;
			case 429 -> SelfErrorClass.RATE_LIMIT_429;
			case 400 -> SelfErrorClass.STRUCTURAL_400;
			case 404 -> SelfErrorClass.NOT_FOUND;
			default -> SelfErrorClass.OTHER;
		};
	}

	/** 전송 예외 분류 — 원인 체인 6홉을 걸어 TLS/Connect 키워드를 찾는다(하니스 chain-walk). */
	public static SelfErrorClass ofException(Throwable e) {
		Throwable t = e;
		for (int i = 0; i < 6 && t != null; i++) {
			String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
			if (t instanceof javax.net.ssl.SSLException
					|| msg.contains("ssl") || msg.contains("tls") || msg.contains("certificate")
					|| msg.contains("handshake") || msg.contains("buffer_underflow")) {
				return SelfErrorClass.TRANSPORT;
			}
			t = t.getCause();
		}
		// ConnectException·SocketTimeout·기타 IO는 전부 전송 실패로(재시도 대상).
		return SelfErrorClass.TRANSPORT;
	}
}
```

- [x] **Step 7: 테스트 통과** — Run: `./gradlew :instagram-source:test --tests "*SelfErrorClassifierTest"` → PASS.

- [x] **Step 8: 커밋**
```bash
git add -A && git commit -m "feat(instagram-source): 자체크롤 에러 taxonomy - SelfErrorClass·분류기·예외(스펙 §8-1 라우팅)"
```

---

## Task 3: SelfHttpClient (저수준 전송)

**Files:**
- Create: `self/SelfHttpClient.java`
- Test: `self/SelfHttpClientTest.java` (JDK `com.sun.net.httpserver.HttpServer`)

crawler `JdkInstagramWebClient`를 순수 JDK로 이식(Spring 제거, ProxyConfig 주입). 요청당 새 클라이언트(K=1 로테이션), HTTP/2 강제, gunzip, 401 복원, fastfail connect 타임아웃.

- [x] **Step 1: 실패 테스트 (JDK HttpServer로 전송·헤더·gunzip·401 검증)**

`SelfHttpClientTest.java`:
```java
package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SelfHttpClientTest {

	private HttpServer server;
	private final AtomicReference<String> seenUa = new AtomicReference<>();
	private final AtomicReference<String> seenAppId = new AtomicReference<>();

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.stop(0);
		}
	}

	private String start(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", ex -> {
			seenUa.set(ex.getRequestHeaders().getFirst("User-Agent"));
			seenAppId.set(ex.getRequestHeaders().getFirst("x-ig-app-id"));
			byte[] b = body.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(status, b.length);
			ex.getResponseBody().write(b);
			ex.close();
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static SelfHttpClient client() {
		// 프록시 미설정(직접) — 로컬 서버 대상. geoKr는 프록시 있을 때만 적용된다.
		return new SelfHttpClient(new ProxyConfig(null, null, Duration.ofSeconds(5), false));
	}

	@Test
	void get_200이면_본문과_헤더를_돌려준다() throws IOException {
		String base = start(200, "{\"ok\":true}");
		SelfResponse res = client().get(base + "/api/x", ProxyTier.RESIDENTIAL,
				Map.of("x-ig-app-id", "936619743392459"));
		assertThat(res.status()).isEqualTo(200);
		assertThat(res.body()).isEqualTo("{\"ok\":true}");
		assertThat(seenAppId.get()).isEqualTo("936619743392459");
		assertThat(seenUa.get()).contains("Chrome/120.0");
	}

	@Test
	void 비200_상태는_그대로_전달한다() throws IOException {
		String base = start(404, "not found");
		SelfResponse res = client().get(base + "/x", ProxyTier.RESIDENTIAL, Map.of());
		assertThat(res.status()).isEqualTo(404);
	}
}
```
(주: 프록시 CONNECT 터널·gunzip·401복원은 실 프록시/IG 없이는 로컬 재현이 제한적이라, 그 부분은 코드 이식 정확성 + Task 6/7의 실 픽스처로 담보한다. 로컬 HttpServer는 헤더·상태·본문 왕복만 검증.)

- [x] **Step 2: 테스트 실패 확인** — Run: `./gradlew :instagram-source:test --tests "*SelfHttpClientTest"` → FAIL.

- [x] **Step 3: SelfHttpClient 구현 (crawler JdkInstagramWebClient 이식, 순수 JDK)**
```java
package com.celfit.instagram.source.self;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 자체크롤 저수준 HTTP(crawler JdkInstagramWebClient 이식, 순수 JDK). 프록시 경로는 요청마다 새
 * HttpClient(=새 CONNECT 터널=새 exit IP, K=1 로테이션), 종료는 shutdownNow(). HTTP/2 강제(IG는
 * HTTP/1.1 web_profile_info를 봇판정 429). gzip 수동 해제. IG의 401(WWW-Authenticate 부재)은
 * IOException으로 오는데, 이를 status 401로 복원해 호출자가 회복(로테이트·재시도)하게 한다.
 */
public class SelfHttpClient {

	private static final Logger log = LoggerFactory.getLogger(SelfHttpClient.class);
	private static final String UA =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
			+ "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3); // fastfail(죽은 IP 꼬리 절단)

	static {
		// 프록시 CONNECT 터널의 Basic auth를 JDK 기본이 끈다 — 클리어해야 자격증명이 실린다(crawler와 동일).
		if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
			System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
		}
	}

	private final ProxyConfig proxy;
	private final Duration requestTimeout;

	public SelfHttpClient(ProxyConfig proxy) {
		this.proxy = proxy;
		this.requestTimeout = proxy.requestTimeout() == null ? Duration.ofSeconds(15) : proxy.requestTimeout();
	}

	public SelfResponse get(String url, ProxyTier tier, Map<String, String> headers) {
		HttpRequest.Builder b = baseRequest(url).GET();
		headers.forEach(b::header);
		return exchange(b.build(), tier);
	}

	public SelfResponse post(String url, String formBody, ProxyTier tier, Map<String, String> headers) {
		HttpRequest.Builder b = baseRequest(url)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8));
		headers.forEach(b::header);
		return exchange(b.build(), tier);
	}

	private HttpRequest.Builder baseRequest(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(requestTimeout)
				.header("User-Agent", UA)
				.header("Accept-Encoding", "gzip");
	}

	private SelfResponse exchange(HttpRequest req, ProxyTier tier) {
		String proxyUrl = proxy.urlFor(tier);
		HttpClient client = newClient(proxyUrl);
		try {
			HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
			String enc = res.headers().firstValue("content-encoding").orElse("");
			String body = "gzip".equalsIgnoreCase(enc.trim())
					? gunzip(res.body())
					: new String(res.body(), StandardCharsets.UTF_8);
			return new SelfResponse(res.statusCode(), body);
		} catch (Exception e) {
			if (isInterceptedUnauthorized(e)) {
				return new SelfResponse(401, "");
			}
			throw new SelfCrawlException(SelfErrorClassifier.ofException(e),
					"자체크롤 전송 실패: " + e.getMessage(), e);
		} finally {
			if (proxyUrl != null) {
				client.shutdownNow(); // 즉시 터널 종료(로테이션). close()는 401 뒤 수십초 블록.
			}
		}
	}

	private static HttpClient newClient(String proxyUrl) {
		HttpClient.Builder b = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_2)
				.connectTimeout(CONNECT_TIMEOUT);
		if (proxyUrl != null) {
			URI p = URI.create(proxyUrl);
			b.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), p.getPort())));
			String ui = p.getUserInfo();
			if (ui != null && ui.contains(":")) {
				String[] parts = ui.split(":", 2);
				b.authenticator(new Authenticator() {
					@Override
					protected PasswordAuthentication getPasswordAuthentication() {
						return new PasswordAuthentication(parts[0], parts[1].toCharArray());
					}
				});
			}
		}
		return b.build();
	}

	private static String gunzip(byte[] compressed) throws IOException {
		try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** IG의 401은 WWW-Authenticate 부재라 JDK가 IOException을 던진다 — 그 메시지로 판별해 401로 복원. */
	static boolean isInterceptedUnauthorized(Exception e) {
		String m = e.getMessage();
		return m != null && m.contains("WWW-Authenticate header missing for response code 401");
	}
}
```
주의: `URI.create(proxyUrl)`가 password 특수문자에서 깨질 수 있다 — Task 1의 수동 파서(`ProxyUrls`)와 달리 여기선 host/port/userinfo만 필요하니, 필요 시 `ProxyUrls`에 `hostOf/portOf/userOf/passOf` 헬퍼를 추가해 재사용한다(구현 중 password에 `@`·`:`가 있으면 이 스텝에서 헬퍼 분해로 교체).

- [x] **Step 4: 테스트 통과** — Run: `./gradlew :instagram-source:test --tests "*SelfHttpClientTest"` → PASS.

- [x] **Step 5: 커밋**
```bash
git add -A && git commit -m "feat(instagram-source): 자체크롤 저수준 전송 SelfHttpClient - HTTP/2·프록시 요청당·gunzip·401복원·fastfail"
```

---

## Task 4: SurfaceCircuitBreaker

**Files:** Create `self/SurfaceCircuitBreaker.java`; Test `self/SurfaceCircuitBreakerTest.java`

표면별(embed/wpi/comment) 연속 블록 5회 트립 → 이후 그 표면은 곧장 폴백(캐스케이드 세금 회피). 전역 킬 포함. crawler `RATE_LIMIT_STREAK_LIMIT=5` 계승.

- [x] **Step 1: 실패 테스트**
```java
package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SurfaceCircuitBreakerTest {

	@Test
	void 연속_5회_블록이면_트립하고_성공하면_리셋한다() {
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5);
		for (int i = 0; i < 4; i++) {
			cb.recordBlock("embed");
			assertThat(cb.isOpen("embed")).isFalse();
		}
		cb.recordBlock("embed");
		assertThat(cb.isOpen("embed")).isTrue();
		assertThat(cb.isOpen("wpi")).isFalse(); // 표면 독립
		cb.recordSuccess("embed");
		assertThat(cb.isOpen("embed")).isFalse();
	}

	@Test
	void 전역_킬은_모든_표면을_연다() {
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5);
		cb.killAll();
		assertThat(cb.isOpen("embed")).isTrue();
		assertThat(cb.isOpen("wpi")).isTrue();
	}
}
```

- [x] **Step 2: 실패 확인** → FAIL.

- [x] **Step 3: 구현**
```java
package com.celfit.instagram.source.self;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 표면별 서킷 — 한 표면(embed/wpi/comment 등)에서 연속 블록이 임계값에 도달하면 트립해, 이후 그
 * 표면 요청은 자체를 스킵하고 곧장 폴백하게 한다(캐스케이드 세금 회피, 스펙 §8-4). 성공하면 리셋.
 * 전역 킬(killAll)은 자체 전체를 즉시 차단(광범위 붕괴 대응). 스레드 안전.
 */
public class SurfaceCircuitBreaker {

	private final int threshold;
	private final ConcurrentHashMap<String, AtomicInteger> streaks = new ConcurrentHashMap<>();
	private volatile boolean killed = false;

	public SurfaceCircuitBreaker(int threshold) {
		this.threshold = threshold;
	}

	public boolean isOpen(String surface) {
		return killed || counter(surface).get() >= threshold;
	}

	public void recordBlock(String surface) {
		counter(surface).incrementAndGet();
	}

	public void recordSuccess(String surface) {
		counter(surface).set(0);
	}

	public void killAll() {
		killed = true;
	}

	public void reset() {
		killed = false;
		streaks.clear();
	}

	private AtomicInteger counter(String surface) {
		return streaks.computeIfAbsent(surface, k -> new AtomicInteger());
	}
}
```

- [x] **Step 4: 통과** → PASS.

- [x] **Step 5: SelfRetry (K=1의 짝 — 회복가능 실패를 새 IP로 재시도) + 테스트**

`self/SelfRetry.java`:
```java
package com.celfit.instagram.source.self;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 회복가능 자체 실패를 재시도한다 — K=1(요청당 새 exit IP)이라 재시도가 곧 IP 교체다(crawler
 * BLOCK_MAX_ATTEMPTS=3 계승). 401(익명 한도)·전송 실패·429는 재시도로 회복(다음 IP는 예산이
 * 남았을 확률), NOT_FOUND·구조적 400·로그인 벽은 재시도 무의미라 즉시 전파. 재시도를 소진하면
 * 마지막 예외를 던져 FailoverInstagramSource가 Hiker로 폴백하게 한다.
 */
public final class SelfRetry {

	private static final Logger log = LoggerFactory.getLogger(SelfRetry.class);
	private final int maxAttempts;

	public SelfRetry(int maxAttempts) {
		this.maxAttempts = Math.max(1, maxAttempts);
	}

	public <T> T call(String surface, Supplier<T> op) {
		SelfCrawlException last = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return op.get();
			} catch (SelfCrawlException e) {
				last = e;
				if (!recoverable(e.errorClass()) || attempt == maxAttempts) {
					throw e; // 비회복 or 소진 → 전파(정책이 Hiker 폴백/부재 판정)
				}
				log.info("자체 {} 재시도 {}/{} — {} (다음 시도=새 IP)",
						surface, attempt + 1, maxAttempts, e.errorClass());
			}
		}
		throw last; // 도달 불가
	}

	private static boolean recoverable(SelfErrorClass ec) {
		return ec == SelfErrorClass.RECOVERABLE_401
				|| ec == SelfErrorClass.TRANSPORT
				|| ec == SelfErrorClass.RATE_LIMIT_429;
	}
}
```
`SelfRetryTest.java`: (a)회복가능(RECOVERABLE_401) 2회 실패 후 성공 → op 3회 호출·성공값 반환; (b)비회복(NOT_FOUND) → op 1회만·즉시 전파; (c)회복가능 3회 소진 → 마지막 예외 전파. `Supplier`를 카운터 람다로 검증.

- [x] **Step 6: 통과 → 커밋** `feat(instagram-source): 표면별 서킷브레이커 + SelfRetry(K=1 회복가능 재시도 3회=새 IP)`.

---

## Task 5: EmbedPostFetcher (단건 지표 — 문서표면) ★ 실 픽스처 TDD

**Files:** Create `self/EmbedPostFetcher.java`; Test `self/EmbedPostFetcherTest.java`; Fixtures `src/test/resources/self/embed_image_en.html`, `embed_reel_en.html`, `embed_deleted.html`.

embed는 로케일 텍스트라 파서를 **진짜 응답에 대고** 만들어야 한다. 첫 스텝에서 프록시로 실 응답을 포착해 픽스처로 커밋한다(바운디드 라이브 액션 — 이후 구현·테스트는 mock).

- [x] **Step 1: 실 embed 픽스처 3종 포착(프록시, en 로케일 고정)**

프록시 URL 조달(시크릿, 출력 금지): `ssh hypenow 'docker exec deploy-crawler-1 printenv DATAIMPULSE_RESIDENTIAL_PROXY_URL'`. geo:kr 적용 후, 살아있는 이미지 게시물·릴스(영상) 각 1개 + 삭제/비공개 게시물 1개의 `/p/{code}/embed/captioned/`를 `Accept-Language: en-US`로 포착:
```bash
# 살아있는 shortcode는 공개 계정 프로필 페이지에서 얻는다(로그아웃 브라우저로 /nasa/ 등 렌더 → a[href*="/p/"]).
curl -sS --proxy "$PXKR" -A "$UA" -H "Accept: text/html" -H "Accept-Language: en-US,en;q=0.9" \
  -H "Sec-Fetch-Mode: navigate" -H "Upgrade-Insecure-Requests: 1" \
  "https://www.instagram.com/p/<IMG_CODE>/embed/captioned/" -o instagram-source/src/test/resources/self/embed_image_en.html
# 릴스(영상)·삭제 게시물도 동일하게 embed_reel_en.html·embed_deleted.html로.
```
포착 후 각 파일에서 지표 텍스트가 실제로 어떤 문자열·컨테이너로 오는지 육안 확인(예: `<span class="...">1,234 likes</span>`, `View all 56 comments`, `1.2M views`). **이 실측 문자열이 Step 3 정규식의 정본**이다. ⚠️민감정보 없음(공개 게시물) — 픽스처 커밋 OK. ⚠️포착 후 프록시 임시 파일 삭제.

- [x] **Step 2: 실패 테스트 (포착한 픽스처의 실제 값으로 assert)**

`EmbedPostFetcherTest.java` — fake `SelfHttpClient`(픽스처 반환)로 파싱만 검증. **아래 기대값(likes/comments/views/caption/shortcode)은 Step 1에서 포착한 픽스처의 실제 값으로 채운다**(플레이스홀더 금지 — 포착 즉시 확정):
```java
package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.instagram.source.PostInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbedPostFetcherTest {

	private static String fixture(String name) {
		try (var in = EmbedPostFetcherTest.class.getResourceAsStream("/self/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** SelfHttpClient를 상속해 get을 픽스처로 오버라이드하는 대신, EmbedPostFetcher가 함수형 fetch를
	 *  주입받게 설계한다(아래 구현 참조) — 여기선 (url,tier,headers)->SelfResponse 람다로 대체. */
	private static EmbedPostFetcher fetcherReturning(String body, int status) {
		return new EmbedPostFetcher((url, tier, headers) -> new SelfResponse(status, body));
	}

	@Test
	void 이미지_게시물_정확_지표를_파싱한다() {
		PostInfo p = fetcherReturning(fixture("embed_image_en.html"), 200).fetch("<IMG_CODE>");
		assertThat(p.shortCode()).isEqualTo("<IMG_CODE>");
		assertThat(p.likes()).isEqualTo(<IMG_LIKES>L);       // Step1 포착값
		assertThat(p.comments()).isEqualTo(<IMG_COMMENTS>L); // Step1 포착값
		assertThat(p.views()).isNull();                       // 이미지=조회수 없음
		assertThat(p.username()).isEqualTo("<IMG_OWNER>");
		assertThat(p.caption()).contains("<IMG_CAPTION_SUBSTR>");
	}

	@Test
	void 릴스_영상_조회수를_파싱한다() {
		PostInfo p = fetcherReturning(fixture("embed_reel_en.html"), 200).fetch("<REEL_CODE>");
		assertThat(p.views()).isEqualTo(<REEL_VIEWS>L);
		assertThat(p.contentType()).isEqualTo("REELS");
	}

	@Test
	void 삭제_게시물은_NOT_FOUND로_던진다() {
		try {
			fetcherReturning(fixture("embed_deleted.html"), 200).fetch("<DEAD_CODE>");
			org.junit.jupiter.api.Assertions.fail("예외가 나야 한다");
		} catch (SelfCrawlException e) {
			assertThat(e.errorClass()).isEqualTo(SelfErrorClass.NOT_FOUND);
		}
	}
}
```

- [x] **Step 3: EmbedPostFetcher 구현 (Step 1 실측 문자열 기반 정규식)**

함수형 fetch 이음매(`SelfFetch`)를 주입받아 테스트 가능하게. 파싱은 en 로케일 텍스트 정규식. **정규식·컨테이너 셀렉터는 Step 1 픽스처 실측으로 확정**:
```java
package com.celfit.instagram.source.self;

import com.celfit.instagram.source.PostInfo;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 게시물 단건 지표 — /p/{code}/embed/captioned/ 문서표면(스펙 §5-1). 서버렌더 로케일 텍스트에서
 * 좋아요·댓글·조회수·캡션·소유자를 뽑는다(en 로케일 고정, 08-31 실측: "N likes"·"N comments"·
 * "N views"). doc_id 불필요. 삭제/비공개는 빈 셸("...deleted..." or 미디어 부재)→ NOT_FOUND.
 * ⚠️정규식은 Task5 Step1에서 포착한 실 픽스처 문자열이 정본 — 아래는 08-31 관측 형태.
 */
public class EmbedPostFetcher {

	/** 전송 이음매 — 프로덕션은 SelfHttpClient::get, 테스트는 픽스처 람다. */
	@FunctionalInterface
	public interface SelfFetch {
		SelfResponse fetch(String url, ProxyTier tier, Map<String, String> headers);
	}

	private static final String SURFACE = "embed";
	// en 로케일 렌더 텍스트(Step1 실측으로 확정). 콤마 구분 정수 + 축약(1.2M) 모두 대응.
	private static final Pattern LIKES = Pattern.compile("([\\d.,]+[KMB]?)\\s+likes");
	private static final Pattern COMMENTS = Pattern.compile("(?:View all )?([\\d.,]+[KMB]?)\\s+comments");
	private static final Pattern VIEWS = Pattern.compile("([\\d.,]+[KMB]?)\\s+views");

	private final SelfFetch fetch;

	public EmbedPostFetcher(SelfFetch fetch) {
		this.fetch = fetch;
	}

	public PostInfo fetch(String shortCode) {
		String url = "https://www.instagram.com/p/" + shortCode + "/embed/captioned/";
		SelfResponse res = fetch.fetch(url, ProxyTier.RESIDENTIAL, Map.of(
				"Accept", "text/html",
				"Accept-Language", "en-US,en;q=0.9",
				"Sec-Fetch-Mode", "navigate",
				"Upgrade-Insecure-Requests", "1"));
		SelfErrorClass ec = SelfErrorClassifier.ofStatus(res.status(), res.body());
		if (ec != SelfErrorClass.OK) {
			throw new SelfCrawlException(ec, "embed 실패 status=" + res.status() + " code=" + shortCode);
		}
		String html = res.body();
		// 빈 셸(삭제/비공개): 지표·캡션·소유자 모두 부재 → NOT_FOUND(정상 스킵, 폴백 안 함).
		String owner = extractOwner(html);   // Step1 실측 셀렉터로 구현
		Long likes = num(LIKES, html);
		if (owner == null && likes == null) {
			throw new SelfCrawlException(SelfErrorClass.NOT_FOUND, "embed 빈 셸(삭제/비공개): " + shortCode);
		}
		boolean isVideo = html.contains("EmbedVideo") || VIEWS.matcher(html).find(); // Step1 실측으로 확정
		return new PostInfo(shortCode, owner, extractOwnerFullName(html), extractOwnerPic(html), null,
				isVideo ? "REELS" : "FEED", extractCaption(html), extractThumb(html),
				null /*takenAt: embed 미제공*/, likes, num(COMMENTS, html), num(VIEWS, html),
				null, null, null, null, null, null, null,
				num(VIEWS, html) != null, likes == null, false);
	}

	private static Long num(Pattern p, String html) {
		Matcher m = p.matcher(html);
		return m.find() ? parseAbbrev(m.group(1)) : null;
	}

	/** "1,234"·"1.2M"·"56K" → long. Step1 실측 형식에 맞춰 확정. */
	static Long parseAbbrev(String s) {
		String t = s.replace(",", "").trim();
		double mult = 1;
		char last = t.charAt(t.length() - 1);
		if (last == 'K' || last == 'M' || last == 'B') {
			mult = last == 'K' ? 1_000 : last == 'M' ? 1_000_000 : 1_000_000_000;
			t = t.substring(0, t.length() - 1);
		}
		return (long) (Double.parseDouble(t) * mult);
	}

	// extractOwner/extractOwnerFullName/extractOwnerPic/extractCaption/extractThumb:
	//   Step1 픽스처의 실제 컨테이너(예: <a class="EmbedProfile...">nasa</a>, <div class="Caption">…)를
	//   보고 정확 셀렉터/정규식으로 구현한다. 캡션은 <div class="Caption"> 내부 텍스트.
	private static String extractOwner(String html) { /* Step1 실측 셀렉터 */ return null; }
	private static String extractOwnerFullName(String html) { return null; }
	private static String extractOwnerPic(String html) { return null; }
	private static String extractCaption(String html) { return null; }
	private static String extractThumb(String html) { return null; }
}
```
⚠️ **구현자 주의:** `extract*` 5개 메서드와 `isVideo` 판별은 Step 1 픽스처의 실제 HTML 구조를 보고 채운다(빈 스텁으로 두지 말 것 — No Placeholders). PostInfo 22개 필드 순서는 `com.celfit.instagram.source.PostInfo` 정의를 따른다(saves/shares/reposts/fbPlays/videoUrl/videoDuration/isPaidPartnership는 embed 미제공 → null; `viewsTrusted=조회수 있으면 true`, `likesHidden=likes null이면 true`, `sharesHidden=false`).

- [x] **Step 4: 통과** → PASS(3). **Step 5: 커밋** `feat(instagram-source): embed 단건 fetcher - 문서표면 로케일 텍스트 파싱(정확 지표·doc_id불필요)`.

---

## Task 6: WpiProfileFetcher (프로필 + 최근12) ★ 실 픽스처 TDD

**Files:** Create `self/WpiProfileFetcher.java`; Test `self/WpiProfileFetcherTest.java`; Fixture `src/test/resources/self/wpi_profile.json`.

web_profile_info JSON → ProfileInfo + 최근 12 PostInfo. 401-prone라 MOBILE 티어. Jackson으로 파싱(하니스 경로 확정).

- [x] **Step 1: 실 wpi 픽스처 포착(모바일 프록시)** — `DATAIMPULSE_MOBILE_PROXY_URL`로 `web_profile_info/?username=nasa`(x-ig-app-id) 200 응답을 `wpi_profile.json`으로 저장. 응답 크기 큼 — 커밋 전 민감정보(없음, 공개 프로필) 확인.

- [x] **Step 2: 실패 테스트** — 포착 픽스처의 실제 followers/following/posts/최근12 shortcode로 assert(EmbedPostFetcherTest와 동일한 fake SelfFetch 패턴).

- [x] **Step 3: 구현** — `https://www.instagram.com/api/v1/users/web_profile_info/?username={}` GET(MOBILE, x-ig-app-id 헤더). Jackson `JsonMapper.readTree`, 루트 `data.user`: `edge_followed_by.count`·`edge_follow.count`·`edge_owner_to_timeline_media.count`, `is_private`면 `PrivateAccountException` 승격(HikerBackend와 동일 계약), 최근 12=`edge_owner_to_timeline_media.edges[:12]`의 각 `node`(shortcode·`edge_media_preview_like.count`·`edge_media_to_comment.count`·`video_view_count`·`taken_at_timestamp`) → PostInfo. `SelfErrorClassifier.ofStatus`로 401/404 분기 → SelfCrawlException.

- [x] **Step 4: 통과. Step 5: 커밋** `feat(instagram-source): wpi 프로필 fetcher - web_profile_info JSON→ProfileInfo+최근12(모바일 티어)`.

---

## Task 7: DirectCommentFetcher (자체 댓글)

**Files:** Create `self/DirectCommentFetcher.java`, `self/HandshakeExtractor.java`; Test `self/DirectCommentFetcherTest.java`; Fixtures `src/test/resources/self/comment_page.json`, `post_page_lsd.html`.

crawler `DirectCommentFetcher` 이식(순수 JDK, CrawlExecutor·저장 결합 제거 — 순수 fetch+DTO). lsd 부트스트랩 + graphql 커서 페이지네이션 → `List<CommentInfo>`(모듈 DTO).

- [x] **Step 1: 픽스처 포착** — 포스트 페이지 GET 1건(lsd 추출용 `post_page_lsd.html`) + graphql 댓글 응답 1페이지(`comment_page.json`). 프록시 경유. doc_id/friendlyName은 crawler `DirectCommentProperties` 값 재사용(구현 시 `git show` 또는 config에서 확인 — 값은 crawler와 동일).

- [x] **Step 2: 실패 테스트** — fake SelfFetch로 (a)포스트 페이지→lsd 추출, (b)graphql→댓글 파싱. 픽스처의 실제 댓글 id/text/작성자로 assert. `HandshakeExtractor.lsdFrom` 단위테스트 포함.

- [x] **Step 3: 구현** — `HandshakeExtractor`(lsd 정규식·shortcode→mediaId, crawler 이식). `DirectCommentFetcher.fetch(shortCode, pages)`: 포스트 페이지 GET(RESIDENTIAL)→lsd, `https://www.instagram.com/api/graphql` POST(헤더 x-ig-app-id·x-fb-lsd, body `lsd`+`fb_api_req_friendly_name`+`doc_id`+`variables`{media_id,after}), 커서 순회(무진전 가드), 응답 파싱→`CommentInfo`(id·author·body·likeCount·commentedAt). 결손 필드 댓글 제외(HikerBackend `toComment` 규칙 일치).

- [x] **Step 4: 통과. Step 5: 커밋** `feat(instagram-source): 자체 댓글 fetcher - lsd 부트스트랩+graphql 커서(crawler DirectComment 이식)`.

---

## Task 8: SelfCrawlBackend (implements InstagramSource)

**Files:** Create `self/SelfCrawlBackend.java`; Test `self/SelfCrawlBackendTest.java`

fetcher들을 `InstagramSource` 계약으로 정규화. 하드게이트 3종 + 미구현 경로는 `UnsupportedOperationException`(정책이 Hiker로 라우팅). 서킷 연동.

- [x] **Step 1: 실패 테스트** — `new SelfCrawlBackend(fakeEmbed, fakeWpi, fakeComments, new SurfaceCircuitBreaker(5), new SelfRetry(3))`. 검증: `fetchPost`→embed 위임, `fetchProfile`→wpi, `fetchComments`→direct, `fetchRecentPosts`→wpi 최근12. `fetchTaggedPage`·`fetchAuthorProfile`·`fetchHashtagRecentPage`·`fetchClipCounts`→`UnsupportedOperationException`. **재시도 연동**: fakeEmbed가 RECOVERABLE_401 2회 후 성공하면 `fetchPost`가 성공값 반환(SelfRetry 관통). RECOVERABLE_401 3회 소진하면 `SelfCrawlException` 전파 + 그 표면 서킷 카운트 증가. NOT_FOUND는 재시도 없이 즉시 전파. 서킷 열림 시 `guard`가 `SelfCrawlException(OTHER)`.

- [x] **Step 2: 구현**
```java
package com.celfit.instagram.source.self;

import com.celfit.instagram.source.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 자체크롤 백엔드 — 표면 fetcher들을 InstagramSource 계약으로 정규화(스펙 §4). 자체 가능 경로만
 * 구현하고, 하드게이트 3종(태그드·해시태그 발견, by-id 작성자)과 프리미엄·clip 경로는
 * UnsupportedOperationException → FailoverInstagramSource가 Hiker로 라우팅한다. 표면별 서킷이
 * 열린 표면은 곧장 SelfCrawlException(OTHER)으로 폴백을 유도한다.
 */
public class SelfCrawlBackend implements InstagramSource {

	private final EmbedPostFetcher embed;
	private final WpiProfileFetcher wpi;
	private final DirectCommentFetcher comments;
	private final SurfaceCircuitBreaker circuit;
	private final SelfRetry retry;

	public SelfCrawlBackend(EmbedPostFetcher embed, WpiProfileFetcher wpi,
			DirectCommentFetcher comments, SurfaceCircuitBreaker circuit, SelfRetry retry) {
		this.embed = embed;
		this.wpi = wpi;
		this.comments = comments;
		this.circuit = circuit;
		this.retry = retry;
	}

	@Override
	public PostInfo fetchPost(String shortCode) {
		return run("embed", () -> embed.fetch(shortCode));
	}

	@Override
	public ProfileInfo fetchProfile(String username) {
		return run("wpi", () -> wpi.fetchProfile(username));
	}

	@Override
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		return run("wpi", () -> wpi.fetchRecentPosts(username));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return run("comment", () -> comments.fetch(shortCode, postUsername, pages));
	}

	/**
	 * 서킷 가드 → SelfRetry(회복가능 실패를 새 IP로 3회) → 성공 시 서킷 리셋, 소진·비회복 시 서킷
	 * 블록 기록 후 전파(정책이 Hiker 폴백/부재 판정). K=1의 401을 여기서 흡수해 Hiker 유출을 막는다.
	 */
	private <T> T run(String surface, java.util.function.Supplier<T> op) {
		guard(surface);
		try {
			T r = retry.call(surface, op);
			circuit.recordSuccess(surface);
			return r;
		} catch (SelfCrawlException e) {
			recordIfBlock(surface, e);
			throw e;
		}
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		return fetchComments(shortCode, postUsername, pages); // 자체는 knownIds 최적화 미적용(Hiker만)
	}

	// ── 하드게이트·미구현: 정책이 Hiker로 라우팅 ──
	@Override
	public AuthorInfo fetchAuthorProfile(String userId) {
		throw new UnsupportedOperationException("자체 미지원(하드게이트 by-id 작성자) — Hiker");
	}

	@Override
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		throw new UnsupportedOperationException("자체 미지원(하드게이트 태그드 발견) — Hiker");
	}

	@Override
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		throw new UnsupportedOperationException("자체 미지원(하드게이트 해시태그 발견) — Hiker");
	}

	@Override
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		throw new UnsupportedOperationException("자체 미지원(clip 보강 폐지, embed 흡수) — Hiker");
	}

	@Override
	public MediaRef resolveMediaByUrl(String url) {
		throw new UnsupportedOperationException("자체 미지원(share 해소, 후속) — Hiker");
	}

	private void guard(String surface) {
		if (circuit.isOpen(surface)) {
			throw new SelfCrawlException(SelfErrorClass.OTHER, "서킷 열림: " + surface);
		}
	}

	private void recordIfBlock(String surface, SelfCrawlException e) {
		switch (e.errorClass()) {
			case RECOVERABLE_401, RATE_LIMIT_429, TRANSPORT, LOGIN_WALL -> circuit.recordBlock(surface);
			default -> { /* NOT_FOUND·STRUCTURAL_400·OTHER은 서킷 카운트 안 함 */ }
		}
	}
}
```
(주: `resolveMediaByUrl`는 스펙상 자체 가능(리다이렉트 추적)이나 저volume·best-effort라 B에선 Hiker 유지, C/후속에서 자체 추가.)

- [x] **Step 3: 통과. Step 4: 커밋** `feat(instagram-source): SelfCrawlBackend - fetcher→InstagramSource 정규화, 하드게이트 미구현`.

---

## Task 9: FailoverInstagramSource 정책 (자체 1순위 + Hiker 폴백)

**Files:** Modify `FailoverInstagramSource.java`(top-level); Test `FailoverInstagramSourcePolicyTest.java`

마일스톤 A의 pass-through를 정책으로 교체. 생성자에 (self, hiker, selfEnabled) — **selfEnabled 기본 false면 전량 Hiker(행동 변화 0)**. self가 `UnsupportedOperationException`·폴백류 SelfCrawlException이면 Hiker, `NOT_FOUND`면 그대로 전파(폴백 안 함).

- [x] **Step 1: 실패 테스트** — (a)selfEnabled=false면 모든 호출이 hiker로만; (b)true면 fetchPost가 self 먼저, self가 STRUCTURAL_400/LOGIN_WALL/OTHER SelfCrawlException이면 hiker 폴백; (c)self가 NOT_FOUND면 hiker 호출 없이 예외 전파; (d)하드게이트(self가 UnsupportedOperationException)면 hiker. mock InstagramSource(self)·mock(hiker)로 검증.

- [x] **Step 2: 구현** — 각 메서드를 `route(surface, selfCall, hikerCall)` 헬퍼로:
```java
// 핵심 라우팅 로직(발췌 — 각 메서드가 이 패턴을 따른다)
private <T> T route(java.util.function.Supplier<T> selfCall, java.util.function.Supplier<T> hikerCall) {
	if (!selfEnabled) {
		return hikerCall.get();
	}
	try {
		return selfCall.get();
	} catch (UnsupportedOperationException e) {
		return hikerCall.get();                // 하드게이트·미구현 → Hiker
	} catch (SelfCrawlException e) {
		if (e.errorClass() == SelfErrorClass.NOT_FOUND) {
			throw e;                            // 부재는 종료(폴백 안 함, 스펙 §8-1)
		}
		return hikerCall.get();                // 그 외 자체 실패 → Hiker 폴백
	}
}
```
`fetchPost` 예: `return route(() -> self.fetchPost(shortCode), () -> hiker.fetchPost(shortCode));`. 10개 메서드 전부 이 패턴(단, `NOT_FOUND` 시 self가 던지는 예외 타입이 소비자 계약과 맞는지 확인 — 자체 NOT_FOUND는 Hiker의 `SubjectNotFoundException`으로 변환해 던져 소비자 catch 호환 유지). 기존 생성자 `FailoverInstagramSource(InstagramSource hiker)`는 유지(self 없는 A식 조립 = selfEnabled false)하거나 오버로드 추가.

- [x] **Step 3: 통과. Step 4: 커밋** `feat(instagram-source): FailoverInstagramSource 정책 - 자체 1순위+Hiker 폴백+에러 라우팅(토글 off 기본)`.

---

## Task 10: monitoring 배선 (프록시 env + SelfCrawlBackend 조립, 토글 off)

**Files:** Modify `monitoring/.../config/HikerConfig.java`; Create/Modify `monitoring` 프록시 프로퍼티; Modify `monitoring/src/main/resources/application.yml`, `deploy/compose.yaml`, `deploy/.env.example`

- [x] **Step 1: 프록시 프로퍼티** — monitoring에 `@ConfigurationProperties("monitoring.proxy")` record(`residentialUrl`, `mobileUrl`, `requestTimeout`, `geoKr`, `selfEnabled`) 추가(`HikerProperties`와 동일 관용구). `PropertiesConfig`에 `@EnableConfigurationProperties` 등재.

- [x] **Step 2: application.yml** — `monitoring.proxy` 블록 추가(UTC 채번 마이그레이션 불필요, yml만):
```yaml
  proxy:
    residential-url: ${DATAIMPULSE_RESIDENTIAL_PROXY_URL:}
    mobile-url: ${DATAIMPULSE_MOBILE_PROXY_URL:}
    request-timeout: 15s
    geo-kr: false          # C 실측 전 기본 off(스펙 §10-1)
    self-enabled: false    # 마일스톤 B: 자체 경로 미개통(=Hiker). C에서 점진 on
```

- [x] **Step 3: HikerConfig 조립** — `instagramSource` 빈에서 ProxyConfig 구성 후 `SelfHttpClient httpClient = new SelfHttpClient(proxyConfig)`를 만들고, SelfCrawlBackend(EmbedPostFetcher(httpClient::get), WpiProfileFetcher(httpClient, jackson), DirectCommentFetcher(httpClient, docId, friendlyName), `new SurfaceCircuitBreaker(5)`, `new SelfRetry(3)`)를 조립, `new FailoverInstagramSource(self, new HikerBackend(chain), proxyProps.selfEnabled())`로 노출. **selfEnabled=false라 self는 생성되되 호출 안 됨** → 회귀 동작 동일.

- [x] **Step 4: compose·env** — `deploy/compose.yaml`의 monitoring 서비스 environment에 `DATAIMPULSE_RESIDENTIAL_PROXY_URL: ${DATAIMPULSE_RESIDENTIAL_PROXY_URL:-}`·`DATAIMPULSE_MOBILE_PROXY_URL: ${...:-}` 추가, `deploy/.env.example`에 항목 추가(crawler와 동일 패턴).

- [x] **Step 5: FULL GATE (행동 변화 0)**
```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :instagram-source:test :monitoring:test
```
monitoring 783 + 모듈(신규 self 테스트 포함) 전부 PASS, **기존 monitoring 동작 불변**(selfEnabled=false). 실패 시 배선 오류 — 토글 off인데 동작이 바뀌면 안 됨.

- [x] **Step 6: 커밋** `refactor(monitoring): 자체크롤 백엔드 배선 - 프록시 env·SelfCrawlBackend 조립(토글 off, 행동 불변)`.

---

## Task 11: 마무리 — 회귀·경계·자기검토

- [x] **Step 1: 모듈 경계** — `grep -rnE 'org\.springframework|javax\.sql|java\.sql|jakarta' instagram-source/src/main` → 0(순수 유지).
- [x] **Step 2: 하드게이트 확인** — SelfCrawlBackend의 3종+clip이 UnsupportedOperationException인지, Failover가 이를 Hiker로 라우팅하는지 테스트로 재확인.
- [x] **Step 3: FULL 회귀** — `:instagram-source:test :monitoring:test` 그린(토글 off = 행동 변화 0 재확인).
- [x] **Step 4: 자기검토(writing-plans Self-Review)** — 스펙 §5-1 자체 5경로 중 B 구현분(단건 embed·프로필 wpi·최근열거·댓글) 커버 확인, resolveShare·og·K≈3·런타임토글은 C로 명시. 플레이스홀더 스캔(특히 EmbedPostFetcher `extract*`가 실제 구현됐는지). 타입 일관성(SelfCrawlBackend/Failover의 InstagramSource override 10개 시그니처).
- [x] **Step 5: 계획 문서 아카이브** — 이 문서를 `plans/archive/`로(상태 ✅). 커밋.

---

## 마일스톤 B 완료 기준(Definition of Done)

- [x] `self` 서브패키지에 전송·프록시·taxonomy·서킷·fetcher(embed·wpi·comment)·SelfCrawlBackend 신설, 각 단위테스트 통과.
- [x] `FailoverInstagramSource`가 자체 1순위+Hiker 폴백+에러 라우팅 구현, **selfEnabled 기본 false**.
- [x] monitoring 프록시 env 배선, `:monitoring:test` 783 그대로 통과(**토글 off = 행동 변화 0**).
- [x] 하드게이트 3종 + clip/premium = 자체 미구현(UnsupportedOperationException→Hiker).
- [x] 모듈 Spring·DB 의존 없음.
- [x] 실측(프록시·geo:kr·og/wpi A/B·Hiker 지연 벤치·dev/staging e2e)·런타임 토글·킬스위치·메트릭·K≈3 = **마일스톤 C**.

**PR·배포·운영 개통은 이 계획 범위 밖** — push까지만, PR 여부는 사용자 확인.
