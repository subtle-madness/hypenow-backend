> 상태: 🟢 활성 · 계획 완료, 실행 미착수 (2026-08-31)
> 범위: Phase 1 마일스톤 A(안전 리팩터 — 신 모듈 + monitoring seam 결선, **행동 변화 0**).
> 설계 정본: `docs/superpowers/specs/2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md`(branch `docs/instagram-selfcrawl-hybrid-spec`, commit 781ca688).
> 후속: 마일스톤 B(자체크롤 백엔드) / C(점진 개통) 계획은 같은 폴더의 `-milestoneB.md` / `-milestoneC.md`.

# 인스타그램 수집 하이브리드 — Phase 1 마일스톤 A 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** monitoring의 인스타그램 수집을 신 공유 모듈 `instagram-source`의 `InstagramSource` 인터페이스 뒤로 통과시킨다 — 백엔드는 여전히 Hiker 단독이라 **런타임 동작은 오늘과 완전히 동일**하고, 이후 마일스톤 B가 자체크롤 백엔드를 꽂을 seam만 만든다.

**Architecture:** 신 `java-library` 모듈(`contract-analysis`·`common-llm`과 동류의 "순수 JDK 공유 예외")에 DTO·전송 인터페이스·Hiker 파싱을 승격한다. monitoring의 `HikerClient`는 모듈의 `HikerBackend`(파싱 이식)가 되고, `FailoverInstagramSource`(마일스톤 A에선 Hiker 단독 위임)를 거쳐 `InstagramSource` 빈으로 노출된다. monitoring 소비자는 `HikerClient` 대신 `InstagramSource`를 주입받는다. **DB 쓰기·Spring 의존은 모듈에 반입하지 않는다** — 빈 배선(전송 데코레이터 체인·과금·메트릭·원형 적재)은 전부 monitoring에 남아 동작이 보존된다.

**Tech Stack:** Java 21, Gradle 멀티모듈(`java-library`), Jackson 3(`tools.jackson.core:jackson-databind:3.1.4`), SLF4J, JUnit 5 + AssertJ. 신 모듈은 Spring·Testcontainers **불사용**(순수 단위 테스트: fake `HikerHttp` 람다 + JSON 픽스처).

---

## 배경 사실 (착수 전 필독 — 실측으로 확정된 현 구조)

**monitoring `com.celfit.monitoring.hiker` 패키지 21개 파일의 이동 분류** (이 계획의 뼈대):

| 분류 | 파일 | 이동 여부 |
|---|---|---|
| DTO (5) | `ProfileInfo` `PostInfo` `CommentInfo` `MediaRef` `AuthorInfo` | **모듈로 이동** |
| 전송 인터페이스 | `HikerHttp` (`String get(String path)`) | **모듈로 이동** |
| 예외 (6) | `HikerFetchException` `SubjectNotFoundException` `HikerBadRequestException` `PrivateAccountException` `ShareLinkUnresolvedException` `PostShapeUnsupportedException` | **모듈로 이동** |
| 헬퍼 | `ShortCodes` (shortcode→mediaId 산술) | **모듈로 이동** |
| 파싱 본체 | `HikerClient` (9개 공개 메서드 + 중첩 record) | **모듈로 이동 → `HikerBackend`로 개명** |
| 전송 구현·데코레이터·설정 (7) | `JdkHikerHttp` `TimedHikerHttp` `RecordingHikerHttp` `CountingHikerHttp` `HikerProperties` `BrandCallContext` `TargetCallContext` | **monitoring 잔류** (모니터링 스토어·메트릭·과금 컨텍스트에 결합) |

**`HikerClient`의 중첩 record 5개**(`ClipCounts` `TaggedPage` `HashtagPost` `HashtagPage` `CommentsFetch`)는 **모듈 top-level record로 승격**한다. 사설 record `ClipPlays`·`ClipItem`은 `HikerBackend` 안에 사설로 유지.

**`InstagramSource` 인터페이스 = 현 `HikerClient` 공개 표면 그대로**(행동 변화 0을 위해 이름·시그니처 불변):
```
ProfileInfo fetchProfile(String username)
AuthorInfo fetchAuthorProfile(String userId)
List<PostInfo> fetchRecentPosts(String username, String userId, int pages)
Map<String, ClipCounts> fetchClipCounts(String userId, int pages)
TaggedPage fetchTaggedPage(String userId, String pageId)
HashtagPage fetchHashtagRecentPage(String tag, String pageId)
PostInfo fetchPost(String shortCode)
CommentsFetch fetchComments(String shortCode, String postUsername, int pages)
CommentsFetch fetchComments(String shortCode, String postUsername, int pages, Set<String> knownCommentIds)
MediaRef resolveMediaByUrl(String url)
```
(스펙의 `resolveShare`·경로 정리 등 개명은 마일스톤 A 비범위 — 이름을 바꾸면 호출부가 흔들려 "행동 변화 0"을 검증하기 어렵다. 개명은 마일스톤 C의 순수 리네임 단계로 미룬다.)

**`HikerClient`를 주입받는 monitoring 클래스 전수**(모두 `InstagramSource`로 교체 대상):
- `service/CollectService` — `fetchProfile` `fetchRecentPosts` `fetchClipCounts` `fetchPost` `fetchComments(3-arg)`, `HikerClient.ClipCounts` 참조
- `service/BrandCollectService` — `fetchTaggedPage` `fetchPost` `fetchProfile` `fetchAuthorProfile` `fetchComments(4-arg)`, `HikerClient.TaggedPage`·`HikerClient.CommentsFetch` 참조
- `service/ShareResolveService` — `resolveMediaByUrl`
- `service/BrandDirectCollectService` — `fetchPost`(2회)
- `service/BrandHashtagCollectService` — `fetchHashtagRecentPage`, `HikerClient.HashtagPage`·`HikerClient.HashtagPost` 참조
- `service/BrandRegistrationService` — `fetchProfile`
- `image/AuthorImageBackfillJob` — `fetchAuthorProfile` `fetchProfile`(필드명 `hikerClient`)
- `config/BrandHashtagConfig` — `@Bean` 파라미터 `HikerClient hiker`
- `config/ImageArchiveConfig` — `@Bean` 파라미터 `HikerClient hikerClient`
- `config/HikerConfig` — `HikerClient` 빈 **생성**(재배선의 중심)
- `service/RegistrationService` — **HikerClient 미주입**(CollectService에 위임, 변경 불필요)

**`ShortCodes`는 hiker 패키지 밖에서도 쓰인다**: `service/DailySweepJob`, `store/TargetRepository`. 이동 시 두 파일의 import도 고쳐야 한다.

**테스트 관례**:
- 파싱 단위 테스트 = fake `HikerHttp` 람다 + `src/test/resources/hiker/*.json` 픽스처(`HikerClientTest`·`HikerClientHashtagTest`·`PostInfoTest`·`ShortCodesTest`). → 모듈로 이동.
- 전송 단위 테스트 = JDK `com.sun.net.httpserver.HttpServer`(`JdkHikerHttpTest`), 데코레이터 테스트(`TimedHikerHttpTest`·`RecordingHikerHttpTest`·`CountingHikerHttpTest`). → monitoring 잔류.
- Testcontainers 테스트(`StoreTest` 등)는 `DOCKER_HOST` 필요(CLAUDE.md). 신 모듈 테스트는 컨테이너 불사용.

**빌드/테스트 명령**(모든 gate에서 사용):
```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
```
- 모듈만: `./gradlew :instagram-source:test`
- monitoring: `./gradlew :monitoring:test`
- 단일: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.CollectServiceTest"`

---

## File Structure (마일스톤 A 종료 시점)

**신설 모듈 `instagram-source/`** (`com.celfit.instagram.source`, 전부 flat 패키지):
- `build.gradle` — java-library + Jackson 3.1.4 + slf4j + junit/assertj
- `src/main/java/.../package-info.java` — 스코프 규약
- `src/main/java/.../InstagramSource.java` — 인터페이스(10 메서드)
- `src/main/java/.../HikerBackend.java` — `HikerClient` 파싱 이식(implements InstagramSource)
- `src/main/java/.../FailoverInstagramSource.java` — 정책 seam(마일스톤 A=Hiker 단독 위임)
- `src/main/java/.../HikerHttp.java` — 전송 인터페이스(이동)
- `src/main/java/.../{ProfileInfo,PostInfo,CommentInfo,MediaRef,AuthorInfo}.java` — DTO(이동)
- `src/main/java/.../{ClipCounts,TaggedPage,HashtagPost,HashtagPage,CommentsFetch}.java` — 결과 record(승격)
- `src/main/java/.../{HikerFetchException,SubjectNotFoundException,HikerBadRequestException,PrivateAccountException,ShareLinkUnresolvedException,PostShapeUnsupportedException}.java` — 예외(이동)
- `src/main/java/.../ShortCodes.java` — 헬퍼(이동)
- `src/test/java/.../{HikerBackendTest,HikerBackendHashtagTest,PostInfoTest,ShortCodesTest,FailoverInstagramSourceTest}.java`
- `src/test/resources/hiker/*.json` — 파싱 픽스처(이동)

**monitoring 변경**:
- `build.gradle` — `implementation project(':instagram-source')` 추가
- `hiker/` 패키지 — 위 이동분 삭제, `JdkHikerHttp`·데코레이터 3종·`HikerProperties`·`BrandCallContext`·`TargetCallContext`만 잔류(이동 타입 import 추가)
- `config/HikerConfig.java` — `InstagramSource` 빈 조립으로 재작성
- 소비자 10개 파일 — `HikerClient` → `InstagramSource` 주입 교체 + 중첩 타입 참조 갱신
- `service/DailySweepJob.java`·`store/TargetRepository.java` — `ShortCodes` import 갱신

**루트/문서**:
- `settings.gradle` — `include 'instagram-source'`
- `ARCHITECTURE.md` §2 모듈 표 + §4-4 공유 원칙에 신 모듈 등재

---

## Task 1: 신 모듈 스캐폴드

**Files:**
- Modify: `settings.gradle`
- Create: `instagram-source/build.gradle`
- Create: `instagram-source/src/main/java/com/celfit/instagram/source/package-info.java`
- Modify: `ARCHITECTURE.md` (§2 모듈 표, §4-4 공유 원칙)

- [ ] **Step 1: `settings.gradle`에 모듈 등록**

기존(verbatim):
```groovy
rootProject.name = 'hypenow-backend'

include 'crawler'
include 'analytics'
include 'contract-analysis'
include 'common-llm'
include 'was'
include 'monitoring'
```
`include 'monitoring'` 아래에 한 줄 추가 → 최종:
```groovy
rootProject.name = 'hypenow-backend'

include 'crawler'
include 'analytics'
include 'contract-analysis'
include 'common-llm'
include 'was'
include 'monitoring'
include 'instagram-source'
```

- [ ] **Step 2: 모듈 `build.gradle` 생성**

`instagram-source/build.gradle`:
```groovy
// 인스타그램 수집 어댑터 모듈(2026-08-31 신설 — 스펙 2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md).
// 순수 fetch+DTO: IG/HikerAPI에서 수집해 DTO로 정규화만 한다. DB 쓰기·Spring 의존 절대 금지 —
// 빈 배선(전송·과금·메트릭)은 소비 모듈(monitoring) 몫. contract-analysis·common-llm과 같은
// java-library 공유 예외(ARCHITECTURE.md §4-4).
plugins {
	id 'java-library'
}

dependencies {
	implementation 'org.slf4j:slf4j-api:2.0.18'
	// Jackson 3(tools.jackson.*) — Spring BOM이 없는 순수 JDK 모듈이라 명시 채번한다.
	// monitoring runtimeClasspath가 해석하는 값과 동일하게 고정(Spring Boot 4.1 정렬, 실측 3.1.4).
	// 파싱은 모듈 내부 전용 — 공개 시그니처(InstagramSource)엔 Jackson 타입이 노출되지 않으므로
	// implementation으로 충분(소비 모듈 컴파일 클래스패스 미오염).
	implementation 'tools.jackson.core:jackson-databind:3.1.4'

	testImplementation platform('org.junit:junit-bom:6.0.3')
	testImplementation 'org.junit.jupiter:junit-jupiter'
	testImplementation 'org.assertj:assertj-core:3.27.7'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
	useJUnitPlatform()
}
```

- [ ] **Step 3: `package-info.java` 생성(스코프 규약)**

`instagram-source/src/main/java/com/celfit/instagram/source/package-info.java`:
```java
/**
 * 인스타그램 수집 어댑터 계층만 — IG/HikerAPI HTTP 수집·DTO 정규화·에러 매핑. DB 쓰기·Spring 의존·
 * 소비자별 저장 로직은 여기 반입 금지, 소비 모듈(monitoring 등) 소관이다(ARCHITECTURE.md §4-4,
 * 스펙 2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md). contract-analysis·common-llm과
 * 동류의 순수 JDK 공유 예외 — 두 백엔드(자체크롤·Hiker)를 한 인터페이스 뒤에 두고 폴백을
 * 모듈 안에 가둔다.
 */
package com.celfit.instagram.source;
```

- [ ] **Step 4: 빈 모듈 컴파일 확인**

Run: `./gradlew :instagram-source:compileJava`
Expected: `BUILD SUCCESSFUL` (package-info만 컴파일).

- [ ] **Step 5: `ARCHITECTURE.md` 갱신**

§4-4 "모듈 공유 원칙"의 공유 모듈 목록(현재 `contract-analysis`·`common-llm` 2개 bullet)에 세 번째 bullet 추가:
```
  - **`instagram-source`**(2026-08-31 신설) — 인스타그램 수집 어댑터만(IG/HikerAPI HTTP
    수집·DTO 정규화·에러 매핑·프록시 로테이션). Spring·DB 의존 금지, 빈 배선은 소비 모듈 몫.
    Phase 1은 monitoring이 소비하며, crawler 이관은 Phase 2(팀 승인 후). 두 백엔드(자체크롤·
    Hiker)를 한 인터페이스 뒤에 두고 장애 시 상호 폴백을 모듈 안에 가둔다.
```
그리고 §2 "시스템 구조"의 모듈 표에 `instagram-source` 행 추가(기존 6모듈 나열 형식에 맞춰 "인스타 수집 어댑터(공유)"로).

- [ ] **Step 6: 커밋**

```bash
git add settings.gradle instagram-source/build.gradle instagram-source/src/main/java/com/celfit/instagram/source/package-info.java ARCHITECTURE.md
git commit -m "feat(instagram-source): 신 공유 모듈 스캐폴드 - java-library + Jackson3 + 스코프 규약"
```

---

## Task 2: DTO 5종을 모듈로 승격

**Files:**
- Move: `monitoring/src/main/java/com/celfit/monitoring/hiker/{ProfileInfo,PostInfo,CommentInfo,MediaRef,AuthorInfo}.java` → `instagram-source/src/main/java/com/celfit/instagram/source/`
- Move test: `monitoring/src/test/java/com/celfit/monitoring/hiker/PostInfoTest.java` → `instagram-source/src/test/java/com/celfit/instagram/source/PostInfoTest.java`
- Modify: `monitoring/build.gradle` (모듈 의존 추가)
- Modify: monitoring 전역 — `com.celfit.monitoring.hiker.{ProfileInfo,PostInfo,CommentInfo,MediaRef,AuthorInfo}` import를 `com.celfit.instagram.source.*`로 재작성

**주의:** DTO 본문(레코드 필드·`PostInfo`의 `withFbPlays`/`mergedMetrics`/`mergedWith`/`coalesce`)은 **한 글자도 바꾸지 않는다** — `package` 선언만 교체하는 순수 이동이다.

- [ ] **Step 1: monitoring이 모듈에 의존하도록 build.gradle 수정**

`monitoring/build.gradle`의 `dependencies {}` 첫 줄 `implementation project(':common-llm')` 아래에 추가:
```groovy
	implementation project(':instagram-source')
```

- [ ] **Step 2: DTO 5파일을 git mv로 이동**

```bash
SRC=monitoring/src/main/java/com/celfit/monitoring/hiker
DST=instagram-source/src/main/java/com/celfit/instagram/source
mkdir -p "$DST"
git mv $SRC/ProfileInfo.java $SRC/PostInfo.java $SRC/CommentInfo.java $SRC/MediaRef.java $SRC/AuthorInfo.java "$DST/"
```

- [ ] **Step 3: 이동한 5파일의 package 선언 교체**

각 파일의 `package com.celfit.monitoring.hiker;`를 `package com.celfit.instagram.source;`로 바꾼다:
```bash
sed -i '' 's/^package com\.celfit\.monitoring\.hiker;/package com.celfit.instagram.source;/' \
  instagram-source/src/main/java/com/celfit/instagram/source/ProfileInfo.java \
  instagram-source/src/main/java/com/celfit/instagram/source/PostInfo.java \
  instagram-source/src/main/java/com/celfit/instagram/source/CommentInfo.java \
  instagram-source/src/main/java/com/celfit/instagram/source/MediaRef.java \
  instagram-source/src/main/java/com/celfit/instagram/source/AuthorInfo.java
```
(`PostInfo.java`는 package 선언이 없던 형태면 파일 첫 줄에 `package com.celfit.instagram.source;`를 추가한다 — 조사 결과 `PostInfo`는 no-package로 보고됐으나 실제로는 `com.celfit.monitoring.hiker`에 있으므로 위 sed로 처리되거나, 무매치면 첫 줄 삽입. `CommentInfo`는 `import java.time.Instant;`를 유지.)

- [ ] **Step 4: 모듈에서 DTO 컴파일 확인**

Run: `./gradlew :instagram-source:compileJava`
Expected: `BUILD SUCCESSFUL` (DTO 5종 + package-info).

- [ ] **Step 5: monitoring 전역 import 재작성**

monitoring `src` 전체에서 이동한 DTO의 완전지정 import를 새 패키지로 바꾼다:
```bash
grep -rl 'com\.celfit\.monitoring\.hiker\.\(ProfileInfo\|PostInfo\|CommentInfo\|MediaRef\|AuthorInfo\)' monitoring/src \
| xargs sed -i '' -E 's/com\.celfit\.monitoring\.hiker\.(ProfileInfo|PostInfo|CommentInfo|MediaRef|AuthorInfo)/com.celfit.instagram.source.\1/g'
```
이 명령은 import 문과 javadoc `{@link ...}` 참조를 함께 고친다. **hiker 패키지 잔류 파일**(`HikerClient` 포함)은 DTO를 같은 패키지로 참조해 import가 없었으므로, 다음 스텝의 컴파일이 누락 import를 드러낸다.

- [ ] **Step 6: 잔류 hiker 파일에 DTO import 추가(컴파일이 안내)**

Run: `./gradlew :monitoring:compileJava`
Expected(초기): `HikerClient.java`에서 `cannot find symbol: class PostInfo/ProfileInfo/...` 다수.
`HikerClient.java`의 import 블록(현재 `import tools.jackson.databind.json.JsonMapper;` 아래)에 추가:
```java
import com.celfit.instagram.source.AuthorInfo;
import com.celfit.instagram.source.CommentInfo;
import com.celfit.instagram.source.MediaRef;
import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.ProfileInfo;
```
Run 재실행: `./gradlew :monitoring:compileJava` → `BUILD SUCCESSFUL`. (다른 잔류 파일에서 심볼 오류가 더 나오면 같은 방식으로 해당 DTO import를 추가.)

- [ ] **Step 7: `PostInfoTest`를 모듈로 이동**

```bash
git mv monitoring/src/test/java/com/celfit/monitoring/hiker/PostInfoTest.java \
  instagram-source/src/test/java/com/celfit/instagram/source/PostInfoTest.java
mkdir -p instagram-source/src/test/java/com/celfit/instagram/source
sed -i '' 's/^package com\.celfit\.monitoring\.hiker;/package com.celfit.instagram.source;/' \
  instagram-source/src/test/java/com/celfit/instagram/source/PostInfoTest.java
```
(`PostInfoTest`는 `import static org.assertj.core.api.Assertions.assertThat;` + `org.junit.jupiter.api.Test`만 쓰고 DB·Spring 없음 — 모듈 test 의존으로 그대로 통과.)

- [ ] **Step 8: 모듈 테스트 + monitoring 컴파일 gate**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :instagram-source:test :monitoring:compileJava :monitoring:compileTestJava
```
Expected: `BUILD SUCCESSFUL`, `PostInfoTest` 통과.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "refactor(instagram-source): DTO 5종(Profile/Post/Comment/MediaRef/Author) 모듈 승격 - 행동 불변"
```

---

## Task 3: 전송 인터페이스·예외·ShortCodes를 모듈로 이동

**Files:**
- Move: `hiker/HikerHttp.java`, 6개 예외, `hiker/ShortCodes.java` → 모듈
- Move test: `hiker/ShortCodesTest.java` → 모듈
- Modify: 잔류 hiker 파일(`JdkHikerHttp`, `TimedHikerHttp`, `RecordingHikerHttp`, `CountingHikerHttp`)에 `HikerHttp`·예외 import 추가
- Modify: `service/DailySweepJob.java`, `store/TargetRepository.java` — `ShortCodes` import 갱신

**주의:** 파일 본문 불변 — `package` 선언만 교체.

- [ ] **Step 1: 9파일을 git mv로 이동**

```bash
SRC=monitoring/src/main/java/com/celfit/monitoring/hiker
DST=instagram-source/src/main/java/com/celfit/instagram/source
git mv $SRC/HikerHttp.java \
  $SRC/HikerFetchException.java $SRC/SubjectNotFoundException.java $SRC/HikerBadRequestException.java \
  $SRC/PrivateAccountException.java $SRC/ShareLinkUnresolvedException.java $SRC/PostShapeUnsupportedException.java \
  $SRC/ShortCodes.java "$DST/"
```

- [ ] **Step 2: package 선언 교체**

```bash
sed -i '' 's/^package com\.celfit\.monitoring\.hiker;/package com.celfit.instagram.source;/' \
  instagram-source/src/main/java/com/celfit/instagram/source/HikerHttp.java \
  instagram-source/src/main/java/com/celfit/instagram/source/HikerFetchException.java \
  instagram-source/src/main/java/com/celfit/instagram/source/SubjectNotFoundException.java \
  instagram-source/src/main/java/com/celfit/instagram/source/HikerBadRequestException.java \
  instagram-source/src/main/java/com/celfit/instagram/source/PrivateAccountException.java \
  instagram-source/src/main/java/com/celfit/instagram/source/ShareLinkUnresolvedException.java \
  instagram-source/src/main/java/com/celfit/instagram/source/PostShapeUnsupportedException.java \
  instagram-source/src/main/java/com/celfit/instagram/source/ShortCodes.java
```

- [ ] **Step 3: 모듈 컴파일 확인**

Run: `./gradlew :instagram-source:compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: monitoring 전역 import 재작성(예외·HikerHttp·ShortCodes)**

```bash
grep -rl 'com\.celfit\.monitoring\.hiker\.\(HikerHttp\|HikerFetchException\|SubjectNotFoundException\|HikerBadRequestException\|PrivateAccountException\|ShareLinkUnresolvedException\|PostShapeUnsupportedException\|ShortCodes\)' monitoring/src \
| xargs sed -i '' -E 's/com\.celfit\.monitoring\.hiker\.(HikerHttp|HikerFetchException|SubjectNotFoundException|HikerBadRequestException|PrivateAccountException|ShareLinkUnresolvedException|PostShapeUnsupportedException|ShortCodes)/com.celfit.instagram.source.\1/g'
```
이로써 `DailySweepJob`·`TargetRepository`의 `ShortCodes` import와 서비스들의 예외 import가 갱신된다.

- [ ] **Step 5: 잔류 hiker 파일에 import 추가(컴파일이 안내)**

Run: `./gradlew :monitoring:compileJava`
같은 패키지라 import가 없던 잔류 파일들이 심볼을 못 찾는다. 다음을 추가:
- `JdkHikerHttp.java` — import: `com.celfit.instagram.source.HikerHttp`, `com.celfit.instagram.source.HikerFetchException`, `com.celfit.instagram.source.SubjectNotFoundException`, `com.celfit.instagram.source.HikerBadRequestException`.
- `TimedHikerHttp.java`, `RecordingHikerHttp.java`, `CountingHikerHttp.java` — import: `com.celfit.instagram.source.HikerHttp`.
- `HikerClient.java` — import: `com.celfit.instagram.source.HikerHttp`, `com.celfit.instagram.source.ShortCodes`, 그리고 사용 중인 예외 전부(`HikerFetchException` `SubjectNotFoundException` `HikerBadRequestException` `PrivateAccountException` `ShareLinkUnresolvedException`).

Run 재실행: `./gradlew :monitoring:compileJava` → `BUILD SUCCESSFUL`.

- [ ] **Step 6: `ShortCodesTest`를 모듈로 이동**

```bash
git mv monitoring/src/test/java/com/celfit/monitoring/hiker/ShortCodesTest.java \
  instagram-source/src/test/java/com/celfit/instagram/source/ShortCodesTest.java
sed -i '' 's/^package com\.celfit\.monitoring\.hiker;/package com.celfit.instagram.source;/' \
  instagram-source/src/test/java/com/celfit/instagram/source/ShortCodesTest.java
```
(`ShortCodesTest`가 순수 단위(JUnit+AssertJ)인지 확인 — 맞으면 그대로 통과. Testcontainers/Spring을 쓴다면 이동하지 말고 monitoring에 두되 `ShortCodes` import만 갱신하고 이 스텝을 스킵.)

- [ ] **Step 7: gate**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :instagram-source:test :monitoring:compileJava :monitoring:compileTestJava
```
Expected: `BUILD SUCCESSFUL`, `PostInfoTest`·`ShortCodesTest` 통과.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "refactor(instagram-source): 전송 인터페이스 HikerHttp·예외 6종·ShortCodes 모듈 이동"
```

---

## Task 4: 결과 record 5종을 모듈 top-level로 승격

`HikerClient`에 중첩 정의된 공개 record `ClipCounts`·`TaggedPage`·`HashtagPost`·`HashtagPage`·`CommentsFetch`를 모듈 top-level 파일로 뽑고, `HikerClient` 및 소비자의 참조를 갱신한다. 사설 record `ClipPlays`·`ClipItem`은 `HikerClient` 안에 그대로 둔다.

**Files:**
- Create: `instagram-source/.../{ClipCounts,TaggedPage,HashtagPost,HashtagPage,CommentsFetch}.java`
- Modify: `hiker/HikerClient.java` — 중첩 공개 record 정의 삭제 + import 추가
- Modify: `service/CollectService.java` (`HikerClient.ClipCounts`→`ClipCounts`), `service/BrandCollectService.java` (`HikerClient.TaggedPage`·`HikerClient.CommentsFetch`), `service/BrandHashtagCollectService.java` (`HikerClient.HashtagPage`·`HikerClient.HashtagPost`)

- [ ] **Step 1: 5개 top-level record 파일 생성(본문은 현 중첩 정의 verbatim)**

`instagram-source/src/main/java/com/celfit/instagram/source/ClipCounts.java`:
```java
package com.celfit.instagram.source;

/**
 * 코드별 관측 지표 — igPlays는 IG 전용, fbPlays는 null(키 부재)과 0(관측된 0)을 구분한다.
 * saves·shares·reposts도 함께 나른다(08-04): 저장·리포스트 키는 세션 복권(콜 단위 전부/전무,
 * clips 존재율 ~45%)이라 clips 관측을 버리면 medias(~30%)보다 좋은 공급원을 매일 흘리게 된다.
 */
public record ClipCounts(Long igPlays, Long fbPlays, Long saves, Long shares, Long reposts) {

	/** 저장·공유·리포스트 중 하나라도 실렸는가 — 세션 복권 당첨 판정(재시도 중단 기준). */
	public boolean hasMetricKeys() {
		return saves != null || shares != null || reposts != null;
	}
}
```

`instagram-source/src/main/java/com/celfit/instagram/source/TaggedPage.java`:
```java
package com.celfit.instagram.source;

import java.util.List;

/** 태그 열거 1페이지 — posts는 응답 순서 그대로(태그된 시점 순 — 중단 판정은 호출자가 페이지 단위로 한다). */
public record TaggedPage(List<PostInfo> posts, String nextPageId) {}
```

`instagram-source/src/main/java/com/celfit/instagram/source/HashtagPost.java`:
```java
package com.celfit.instagram.source;

import java.util.List;

/** 해시태그 recent 스트림 게시물 + 사진 태그된 계정 목록(소문자 정규화). */
public record HashtagPost(PostInfo post, List<String> taggedUsernames) {}
```

`instagram-source/src/main/java/com/celfit/instagram/source/HashtagPage.java`:
```java
package com.celfit.instagram.source;

import java.util.List;

public record HashtagPage(List<HashtagPost> posts, String nextPageId) {}
```

`instagram-source/src/main/java/com/celfit/instagram/source/CommentsFetch.java`:
```java
package com.celfit.instagram.source;

import java.util.List;

/**
 * 댓글 수집 결과 — complete=false면 중간 페이지 콜 실패로 뒤 페이지를 못 받은 부분 결과다.
 * 받은 페이지분은 그대로 저장 가능하지만, 브랜드 워터마크처럼 "이 게시물 댓글을 다 봤다"를
 * 전제하는 갱신은 하면 안 된다(다음 스윕이 재시도할 근거를 지운다).
 */
public record CommentsFetch(List<CommentInfo> comments, boolean complete) {}
```

- [ ] **Step 2: 모듈 컴파일 확인**

Run: `./gradlew :instagram-source:compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: `HikerClient`에서 중첩 공개 record 정의 삭제 + import 추가**

`HikerClient.java`에서 다음 5개 중첩 `public record` 블록을 삭제한다(각 javadoc 포함): `ClipCounts`(hasMetricKeys 포함), `TaggedPage`, `HashtagPost`, `HashtagPage`, `CommentsFetch`. 사설 `ClipPlays`·`ClipItem`은 남긴다.
import 블록에 추가:
```java
import com.celfit.instagram.source.ClipCounts;
import com.celfit.instagram.source.CommentsFetch;
import com.celfit.instagram.source.HashtagPage;
import com.celfit.instagram.source.HashtagPost;
import com.celfit.instagram.source.TaggedPage;
```

- [ ] **Step 4: 소비자 3파일의 중첩 참조 갱신**

`service/CollectService.java`: `HikerClient.ClipCounts` → `ClipCounts`(2곳), import `com.celfit.instagram.source.ClipCounts` 추가.
```bash
sed -i '' 's/HikerClient\.ClipCounts/ClipCounts/g' monitoring/src/main/java/com/celfit/monitoring/service/CollectService.java
```
`service/BrandCollectService.java`: `HikerClient.TaggedPage`→`TaggedPage`, `HikerClient.CommentsFetch`→`CommentsFetch`, 두 import 추가.
```bash
sed -i '' -e 's/HikerClient\.TaggedPage/TaggedPage/g' -e 's/HikerClient\.CommentsFetch/CommentsFetch/g' monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java
```
`service/BrandHashtagCollectService.java`: `HikerClient.HashtagPage`→`HashtagPage`, `HikerClient.HashtagPost`→`HashtagPost`, 두 import 추가.
```bash
sed -i '' -e 's/HikerClient\.HashtagPage/HashtagPage/g' -e 's/HikerClient\.HashtagPost/HashtagPost/g' monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java
```
(각 파일 import 블록에 대응 `com.celfit.instagram.source.*` import를 손으로 추가 — sed는 참조만 바꾸고 import는 다음 컴파일이 요구한다.)

- [ ] **Step 5: gate**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :instagram-source:test :monitoring:compileJava :monitoring:compileTestJava
```
Expected: `BUILD SUCCESSFUL`. (테스트 코드도 `HikerClient.ClipCounts` 등을 참조하면 같은 sed를 test 트리에도 적용.)

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor(instagram-source): 결과 record 5종(ClipCounts/TaggedPage/Hashtag*/CommentsFetch) top-level 승격"
```

---

## Task 5: `InstagramSource` 인터페이스 정의

**Files:**
- Create: `instagram-source/src/main/java/com/celfit/instagram/source/InstagramSource.java`

- [ ] **Step 1: 인터페이스 생성(현 HikerClient 공개 표면 그대로)**

`InstagramSource.java`:
```java
package com.celfit.instagram.source;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 인스타그램 수집의 안정된 계약 하나 — 자체크롤·Hiker 백엔드를 이 뒤에 두고 폴백을 모듈 안에 가둔다
 * (스펙 2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md §4). 소비자는 어느 백엔드가
 * 응답했는지 몰라도 되고, 자기 스토어에만 저장한다(경계 규칙 위반 없음).
 *
 * <p>마일스톤 A는 Hiker 단독 구현({@link FailoverInstagramSource} → {@link HikerBackend})이라
 * 메서드 이름·시그니처를 monitoring 기존 HikerClient와 동일하게 유지한다(행동 변화 0). 하드게이트
 * 3종(fetchTaggedPage·fetchAuthorProfile·fetchHashtagRecentPage)은 자체 백엔드가 없어 마일스톤 B
 * 이후에도 Hiker 단독으로 남는다.
 */
public interface InstagramSource {

	ProfileInfo fetchProfile(String username);

	AuthorInfo fetchAuthorProfile(String userId);

	List<PostInfo> fetchRecentPosts(String username, String userId, int pages);

	Map<String, ClipCounts> fetchClipCounts(String userId, int pages);

	TaggedPage fetchTaggedPage(String userId, String pageId);

	HashtagPage fetchHashtagRecentPage(String tag, String pageId);

	PostInfo fetchPost(String shortCode);

	CommentsFetch fetchComments(String shortCode, String postUsername, int pages);

	CommentsFetch fetchComments(String shortCode, String postUsername, int pages, Set<String> knownCommentIds);

	MediaRef resolveMediaByUrl(String url);
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :instagram-source:compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: 커밋**

```bash
git add instagram-source/src/main/java/com/celfit/instagram/source/InstagramSource.java
git commit -m "feat(instagram-source): InstagramSource 인터페이스 - 현 HikerClient 공개 표면 추출"
```

---

## Task 6: `HikerClient` → `HikerBackend` 이식(implements InstagramSource)

**Files:**
- Move: `hiker/HikerClient.java` → `instagram-source/.../HikerBackend.java`
- Move test: `hiker/HikerClientTest.java`, `hiker/HikerClientHashtagTest.java` → 모듈, `HikerBackendTest`·`HikerBackendHashtagTest`로 개명
- Move fixtures: `monitoring/src/test/resources/hiker/*.json` → `instagram-source/src/test/resources/hiker/`
- Modify: `HikerBackend` — 클래스명·`implements`·불필요해진 DTO/예외 import 정리(같은 패키지가 됨)

- [ ] **Step 1: 파일 이동 + 클래스 개명**

```bash
git mv monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java \
  instagram-source/src/main/java/com/celfit/instagram/source/HikerBackend.java
sed -i '' \
  -e 's/^package com\.celfit\.monitoring\.hiker;/package com.celfit.instagram.source;/' \
  -e 's/public class HikerClient {/public class HikerBackend implements InstagramSource {/' \
  -e 's/public HikerClient(HikerHttp http)/public HikerBackend(HikerHttp http)/' \
  -e 's/LoggerFactory\.getLogger(HikerClient\.class)/LoggerFactory.getLogger(HikerBackend.class)/' \
  instagram-source/src/main/java/com/celfit/instagram/source/HikerBackend.java
```

- [ ] **Step 2: 모듈 내부가 된 import 정리**

`HikerBackend.java`는 이제 `com.celfit.instagram.source` 패키지 안이라, Task 2·3·4에서 추가했던 `import com.celfit.instagram.source.*`(DTO·예외·HikerHttp·ShortCodes·결과 record)는 **자기 패키지 import라 삭제**한다(불필요·Error Prone 무해하나 정리). 남기는 import는 JDK(`java.net.URLEncoder` 등)·Jackson(`tools.jackson.databind.JsonNode`, `tools.jackson.databind.json.JsonMapper`)·slf4j뿐.
공개 메서드 9종에 `@Override`를 붙인다(인터페이스 계약 명시 — `fetchProfile`·`fetchAuthorProfile`·`fetchRecentPosts`·`fetchClipCounts`·`fetchTaggedPage`·`fetchHashtagRecentPage`·`fetchPost`·`fetchComments`(2개)·`resolveMediaByUrl`).

- [ ] **Step 3: 모듈 컴파일 확인**

Run: `./gradlew :instagram-source:compileJava`
Expected: `BUILD SUCCESSFUL`. (실패 시 남은 자기패키지 import 또는 `@Override` 시그니처 불일치를 수정.)

- [ ] **Step 4: 파싱 테스트·픽스처 이동**

```bash
mkdir -p instagram-source/src/test/resources/hiker
git mv monitoring/src/test/resources/hiker/*.json instagram-source/src/test/resources/hiker/
git mv monitoring/src/test/java/com/celfit/monitoring/hiker/HikerClientTest.java \
  instagram-source/src/test/java/com/celfit/instagram/source/HikerBackendTest.java
git mv monitoring/src/test/java/com/celfit/monitoring/hiker/HikerClientHashtagTest.java \
  instagram-source/src/test/java/com/celfit/instagram/source/HikerBackendHashtagTest.java
sed -i '' \
  -e 's/^package com\.celfit\.monitoring\.hiker;/package com.celfit.instagram.source;/' \
  -e 's/class HikerClientTest/class HikerBackendTest/g' \
  -e 's/HikerClientTest\.class/HikerBackendTest.class/g' \
  -e 's/new HikerClient(/new HikerBackend(/g' \
  instagram-source/src/test/java/com/celfit/instagram/source/HikerBackendTest.java
sed -i '' \
  -e 's/^package com\.celfit\.monitoring\.hiker;/package com.celfit.instagram.source;/' \
  -e 's/class HikerClientHashtagTest/class HikerBackendHashtagTest/g' \
  -e 's/HikerClientHashtagTest\.class/HikerBackendHashtagTest.class/g' \
  -e 's/new HikerClient(/new HikerBackend(/g' \
  instagram-source/src/test/java/com/celfit/instagram/source/HikerBackendHashtagTest.java
```
(두 테스트가 `HikerClient.ClipCounts` 등 중첩 참조를 쓰면 `HikerClient\.` prefix 제거 sed도 적용. fixture `getResourceAsStream("/hiker/...")` 경로는 test resources 이동으로 그대로 유효.)

- [ ] **Step 5: 픽스처가 다른 monitoring 테스트에서도 쓰이는지 확인**

Run:
```bash
grep -rl '/hiker/' monitoring/src/test | grep -v '/hiker/'
```
잔류 monitoring 테스트가 `/hiker/*.json`을 참조하면 그 픽스처만 **복사**(이동이 아니라)로 monitoring에도 남긴다:
```bash
# 예: 잔류 참조가 있으면
git checkout HEAD -- monitoring/src/test/resources/hiker/<needed>.json 2>/dev/null || true
```
(원칙: 모듈 파싱 테스트가 정본, monitoring 잔류 전송/데코레이터 테스트가 픽스처를 쓰면 사본 유지.)

- [ ] **Step 6: gate**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :instagram-source:test
```
Expected: `HikerBackendTest`·`HikerBackendHashtagTest`·`PostInfoTest`·`ShortCodesTest` 전부 PASS.
(monitoring은 아직 `HikerClient` 심볼 부재로 컴파일 실패 — 다음 Task 7·8에서 해소. 이 Task의 gate는 모듈 테스트까지.)

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor(instagram-source): HikerClient 파싱 HikerBackend로 이식 - InstagramSource 구현"
```

---

## Task 7: `FailoverInstagramSource` 정책 seam(마일스톤 A=Hiker 단독)

**Files:**
- Create: `instagram-source/.../FailoverInstagramSource.java`
- Create test: `instagram-source/src/test/java/com/celfit/instagram/source/FailoverInstagramSourceTest.java`

마일스톤 A에서는 자체크롤 백엔드가 없으므로 이 클래스는 주입된 Hiker 백엔드로 **전부 위임**한다. 마일스톤 B가 여기 `SelfCrawlBackend`와 폴백 정책을 채운다. seam을 지금 두는 이유 = 소비자가 `InstagramSource`(정책 계층)를 주입받게 만들어, B에서 소비자를 다시 건드리지 않기 위함.

- [ ] **Step 1: 실패하는 테스트 작성(위임 검증)**

`FailoverInstagramSourceTest.java`:
```java
package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FailoverInstagramSourceTest {

	/** path→body 픽스처가 필요 없는 최소 백엔드: HikerBackend에 fake HikerHttp를 주입해 위임을 관찰. */
	private static HikerBackend hikerBackendReturning(String profileBody) {
		return new HikerBackend(path -> profileBody);
	}

	@Test
	void fetchProfile는_hiker_백엔드로_위임한다() {
		String body = "{\"user\":{\"pk\":123,\"follower_count\":10,\"following_count\":5,"
				+ "\"media_count\":2,\"full_name\":\"n\",\"is_private\":false}}";
		FailoverInstagramSource source = new FailoverInstagramSource(hikerBackendReturning(body));
		ProfileInfo p = source.fetchProfile("acct");
		assertThat(p.userId()).isEqualTo("123");
		assertThat(p.followers()).isEqualTo(10L);
	}

	@Test
	void fetchComments_3arg는_4arg로_위임되지_않고_백엔드_계약을_그대로_노출한다() {
		// 위임 존재만 확인(빈 knownIds) — 실제 파싱은 HikerBackendTest가 검증.
		FailoverInstagramSource source = new FailoverInstagramSource(
				hikerBackendReturning("{\"response\":{\"comments\":[]}}"));
		CommentsFetch fetch = source.fetchComments("ABC", "owner", 1);
		assertThat(fetch.comments()).isEqualTo(List.of());
		assertThat(fetch.complete()).isTrue();
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :instagram-source:test --tests "com.celfit.instagram.source.FailoverInstagramSourceTest"`
Expected: FAIL — `FailoverInstagramSource` 심볼 없음(컴파일 에러).

- [ ] **Step 3: `FailoverInstagramSource` 구현(전부 위임)**

`FailoverInstagramSource.java`:
```java
package com.celfit.instagram.source;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 수집 정책 계층 — 자체크롤 1순위 + Hiker 폴백 + 서킷을 이 뒤에 가둔다(스펙 §4). 소비자는 항상 이
 * 타입을 주입받아, 백엔드/표면 선택을 모른다.
 *
 * <p>마일스톤 A: 자체크롤 백엔드가 아직 없어 모든 경로를 {@code hiker}로 위임한다(행동 변화 0).
 * 마일스톤 B가 SelfCrawlBackend·표면 사다리·서킷·에러 taxonomy를 여기에 채운다 — 그때도 소비자
 * 주입 지점은 이 타입 그대로라 소비자 코드는 다시 바뀌지 않는다.
 */
public class FailoverInstagramSource implements InstagramSource {

	private final InstagramSource hiker;

	public FailoverInstagramSource(InstagramSource hiker) {
		this.hiker = hiker;
	}

	@Override
	public ProfileInfo fetchProfile(String username) {
		return hiker.fetchProfile(username);
	}

	@Override
	public AuthorInfo fetchAuthorProfile(String userId) {
		return hiker.fetchAuthorProfile(userId);
	}

	@Override
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		return hiker.fetchRecentPosts(username, userId, pages);
	}

	@Override
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		return hiker.fetchClipCounts(userId, pages);
	}

	@Override
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		return hiker.fetchTaggedPage(userId, pageId);
	}

	@Override
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		return hiker.fetchHashtagRecentPage(tag, pageId);
	}

	@Override
	public PostInfo fetchPost(String shortCode) {
		return hiker.fetchPost(shortCode);
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return hiker.fetchComments(shortCode, postUsername, pages);
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		return hiker.fetchComments(shortCode, postUsername, pages, knownCommentIds);
	}

	@Override
	public MediaRef resolveMediaByUrl(String url) {
		return hiker.resolveMediaByUrl(url);
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :instagram-source:test --tests "com.celfit.instagram.source.FailoverInstagramSourceTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add instagram-source/src/main/java/com/celfit/instagram/source/FailoverInstagramSource.java instagram-source/src/test/java/com/celfit/instagram/source/FailoverInstagramSourceTest.java
git commit -m "feat(instagram-source): FailoverInstagramSource 정책 seam - 마일스톤 A는 Hiker 단독 위임"
```

---

## Task 8: monitoring `HikerConfig` 재배선 → `InstagramSource` 빈

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/config/HikerConfig.java`

기존 데코레이터 체인(Counting→Recording→Timed→transport)은 그대로 두고, 끝에 `HikerBackend`→`FailoverInstagramSource`를 얹어 `InstagramSource` 빈으로 노출한다. `HikerClient` 빈은 제거한다.

- [ ] **Step 1: `HikerConfig` 재작성**

`HikerConfig.java` 최종:
```java
package com.celfit.monitoring.config;

import com.celfit.instagram.source.FailoverInstagramSource;
import com.celfit.instagram.source.HikerBackend;
import com.celfit.instagram.source.HikerHttp;
import com.celfit.instagram.source.InstagramSource;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.CountingHikerHttp;
import com.celfit.monitoring.hiker.RecordingHikerHttp;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.hiker.TimedHikerHttp;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HikerConfig {

	/**
	 * 수집 진입점 — 소비자는 이 InstagramSource를 주입받는다. 전송 데코레이터 체인(과금·원형 적재·
	 * 지연 메트릭)은 그대로 유지되고, 그 위에 Hiker 파싱 백엔드(HikerBackend)와 정책 계층
	 * (FailoverInstagramSource)을 얹는다. 마일스톤 A는 자체 백엔드가 없어 Failover가 Hiker로만
	 * 위임한다(행동 변화 0). 마일스톤 B에서 SelfCrawlBackend가 Failover 안에 추가된다.
	 */
	@Bean
	public InstagramSource instagramSource(HikerHttp transport, RawPayloadRepository rawPayloads,
			BrandCallContext brandContext, BrandCallCountRepository brandCounts,
			TargetCallContext targetContext, TargetCallCountRepository targetCounts,
			MeterRegistry meterRegistry) {
		HikerHttp chain = new CountingHikerHttp(
				new RecordingHikerHttp(new TimedHikerHttp(transport, meterRegistry), rawPayloads),
				brandContext, brandCounts, targetContext, targetCounts);
		return new FailoverInstagramSource(new HikerBackend(chain));
	}
}
```

- [ ] **Step 2: 컴파일 확인(소비자는 아직 HikerClient 참조 — 다음 Task에서 해소)**

Run: `./gradlew :monitoring:compileJava`
Expected(초기): 소비자 파일들이 `HikerClient` 심볼 부재로 실패. `HikerConfig.java` 자체는 오류 없어야 한다(오류가 `config/HikerConfig.java`에 있으면 이 스텝에서 고친다). 나머지 소비자 오류는 Task 9가 해소.

- [ ] **Step 3: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/config/HikerConfig.java
git commit -m "refactor(monitoring): HikerConfig가 InstagramSource 빈 조립 - 데코레이터 체인 위에 Failover+HikerBackend"
```

---

## Task 9: 소비자 10개 파일 주입 교체 `HikerClient` → `InstagramSource`

**Files (전부 Modify):**
- `service/CollectService.java`, `service/BrandCollectService.java`, `service/ShareResolveService.java`
- `service/BrandDirectCollectService.java`, `service/BrandHashtagCollectService.java`, `service/BrandRegistrationService.java`
- `image/AuthorImageBackfillJob.java`
- `config/BrandHashtagConfig.java`, `config/ImageArchiveConfig.java`
- Test: 위 서비스들의 테스트(`*ServiceTest` 등)에서 `HikerClient` 목/스텁 참조 교체

교체 규칙(모든 파일 공통):
1. `import com.celfit.monitoring.hiker.HikerClient;` → `import com.celfit.instagram.source.InstagramSource;`
2. 필드·생성자·파라미터 타입 `HikerClient` → `InstagramSource`(필드명 `hiker`/`hikerClient`는 유지 — 메서드 호출은 이름 동일이라 그대로 컴파일).
3. javadoc의 `{@link com.celfit.monitoring.hiker.PrivateAccountException}` 류 완전지정 참조는 `com.celfit.instagram.source.PrivateAccountException`로(Task 3 sed가 이미 처리했을 수 있음 — 컴파일이 남은 것 안내).

- [ ] **Step 1: 소비자 9파일 타입 일괄 교체**

```bash
FILES="
monitoring/src/main/java/com/celfit/monitoring/service/CollectService.java
monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java
monitoring/src/main/java/com/celfit/monitoring/service/ShareResolveService.java
monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java
monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java
monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java
monitoring/src/main/java/com/celfit/monitoring/image/AuthorImageBackfillJob.java
monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagConfig.java
monitoring/src/main/java/com/celfit/monitoring/config/ImageArchiveConfig.java
"
for f in $FILES; do
  sed -i '' \
    -e 's/import com\.celfit\.monitoring\.hiker\.HikerClient;/import com.celfit.instagram.source.InstagramSource;/' \
    -e 's/\bHikerClient\b/InstagramSource/g' \
    "$f"
done
```
주의: 위 `\bHikerClient\b` 전역치환은 타입 사용처(필드 선언·생성자 파라미터·`@Bean` 파라미터)를 모두 바꾼다. 필드/파라미터 **변수명**(`hiker`, `hikerClient`)은 `HikerClient`와 다른 토큰이라 영향 없다. 치환 후 각 파일에 `InstagramSource` import가 있는지 확인(위 첫 sed가 기존 import를 교체; import가 없던 파일은 수동 추가).

- [ ] **Step 2: 컴파일 gate**

Run: `./gradlew :monitoring:compileJava`
Expected: `BUILD SUCCESSFUL`. (남은 심볼/ import 오류는 해당 파일에 `com.celfit.instagram.source.*` import를 추가해 해소.)

- [ ] **Step 3: 테스트 코드의 HikerClient 참조 교체**

Run:
```bash
grep -rln '\bHikerClient\b' monitoring/src/test
```
나오는 각 테스트(예: `CollectServiceTest`, `BrandCollectServiceTest`, `BrandDirectCollectServiceTest`, `BrandHashtagCollectServiceTest`, `BrandRegistrationServiceTest`, `AuthorImageBackfillJobTest`, `ShareControllerTest` 등)에서:
- fake/mock 대상이 `HikerClient`면 `InstagramSource`로 바꾼다. 테스트가 `new HikerClient(fakeHttp)`로 실제 파싱을 태웠다면 `new HikerBackend(fakeHttp)`(모듈 import) 또는 `new FailoverInstagramSource(new HikerBackend(fakeHttp))`로 바꾼다 — 기존 의도(실 파싱 vs 목)에 맞춰 선택.
- `HikerClient.ClipCounts`/`.TaggedPage`/`.CommentsFetch`/`.HashtagPage`/`.HashtagPost` 참조는 top-level 타입(`com.celfit.instagram.source.*`)으로.
```bash
for f in $(grep -rln '\bHikerClient\b' monitoring/src/test); do
  sed -i '' \
    -e 's/import com\.celfit\.monitoring\.hiker\.HikerClient;//' \
    -e 's/new HikerClient(/new HikerBackend(/g' \
    -e 's/\bHikerClient\b/InstagramSource/g' \
    "$f"
done
```
치환 후 각 테스트에 필요한 import(`com.celfit.instagram.source.InstagramSource`, `HikerBackend`, 결과 record)를 컴파일 안내에 따라 추가.

- [ ] **Step 4: gate**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:compileTestJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor(monitoring): 수집 소비자 10곳 HikerClient→InstagramSource 주입 교체 - 행동 불변"
```

---

## Task 10: 전체 회귀 + 잔재 점검 + 자기 검토

**Files:** (검증 전용 — 발견 시 수정)

- [ ] **Step 1: `HikerClient` 심볼 완전 소멸 확인**

Run:
```bash
grep -rn '\bHikerClient\b' monitoring/src instagram-source/src
```
Expected: **0건**(주석 포함 0). 남으면 잔재 — 위 규칙으로 교체.

- [ ] **Step 2: hiker 패키지 잔류 파일 확인(딱 7개여야 함)**

Run:
```bash
ls monitoring/src/main/java/com/celfit/monitoring/hiker/
```
Expected: `JdkHikerHttp.java` `TimedHikerHttp.java` `RecordingHikerHttp.java` `CountingHikerHttp.java` `HikerProperties.java` `BrandCallContext.java` `TargetCallContext.java` (7개). 그 외 파일이 남았으면 이동 누락.

- [ ] **Step 3: 모듈 경계 규칙 확인(모듈에 Spring·DB 없음)**

Run:
```bash
grep -rnE 'org\.springframework|javax\.sql|java\.sql|jakarta' instagram-source/src/main
```
Expected: **0건**(순수 fetch+DTO 성격 유지, 스펙 §10-5).

- [ ] **Step 4: 모듈 전체 테스트**

Run: `./gradlew :instagram-source:test`
Expected: 전 테스트 PASS(`HikerBackendTest`·`HikerBackendHashtagTest`·`PostInfoTest`·`ShortCodesTest`·`FailoverInstagramSourceTest`).

- [ ] **Step 5: monitoring 전체 테스트(회귀 — 행동 변화 0 검증)**

Run:
```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test
```
Expected: **마일스톤 A 착수 전과 동일한 테스트 결과**(전부 PASS). 이 테스트 스위트(서비스·스토어·전송·데코레이터·웹)가 "행동 변화 0"의 회귀 가드다. 실패가 나면 그 테스트가 짚는 지점이 이동/재배선에서 의미를 바꾼 곳 — 되돌려 원인 규명(systematic-debugging).

- [ ] **Step 6: 빈 배선 스모크(컨텍스트 로드)**

`HikerClientTest`가 컨텍스트를 안 띄우므로, `@SpringBootTest`류 기존 테스트가 `InstagramSource` 빈을 정상 주입받는지 확인(Step 5 스위트에 포함). 별도로 확인하려면 monitoring의 컨텍스트 로드 테스트를 지정 실행:
```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.*Controller*Test"
```
Expected: 컨텍스트 로드 성공(빈 그래프에서 `HikerClient`→`InstagramSource` 전환 정상).

- [ ] **Step 7: 자기 검토(writing-plans Self-Review)**

- 스펙 §9 Phase 1-①(모듈 신설: 인터페이스·DTO·FailoverInstagramSource·HikerBackend) 커버 확인.
- 플레이스홀더 스캔: 이 마일스톤 산출 코드에 TODO/TBD 없음 확인.
- 타입 일관성: `InstagramSource` 10 메서드 시그니처 = `HikerBackend`/`FailoverInstagramSource` `@Override`와 일치 확인.
- **비범위 재확인**: 프록시 env·자체크롤·토글·메트릭 신설은 **이 마일스톤에 없음**(B·C). monitoring 컨테이너 env·`application.yml`은 손대지 않았다.

- [ ] **Step 8: 최종 커밋(잔재 수정분이 있으면)**

```bash
git add -A
git commit -m "chore(instagram-source): 마일스톤 A 회귀 통과 - HikerClient 완전 대체, 행동 변화 0 검증"
```

---

## 마일스톤 A 완료 기준(Definition of Done)

- [ ] `instagram-source` 모듈이 `settings.gradle`에 등록되고 독립 컴파일·테스트된다.
- [ ] DTO 5종·결과 record 5종·전송 인터페이스·예외 6종·`ShortCodes`·파싱(`HikerBackend`)이 모듈에 있다.
- [ ] `InstagramSource` 인터페이스와 `FailoverInstagramSource`(Hiker 단독 위임)가 있다.
- [ ] monitoring이 `implementation project(':instagram-source')`로 의존하고, `HikerConfig`가 `InstagramSource` 빈을 조립한다.
- [ ] 소비자 10곳이 `InstagramSource`를 주입받는다. `HikerClient` 심볼은 코드베이스에서 0건.
- [ ] `:monitoring:test` 전체가 착수 전과 동일하게 PASS(**행동 변화 0**).
- [ ] 모듈에 Spring·DB 의존 없음(경계 규칙 새 예외 성격 유지).
- [ ] 이 계획 문서를 실행 완료 시 `docs/superpowers/plans/archive/`로 이동(CLAUDE.md 세션 위생).

**PR·배포는 이 계획 범위 밖** — push까지만 하고 PR 여부는 사용자에게 확인받는다(전역 지침).
