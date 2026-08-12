# 브랜드 태그 모니터링 수집 파이프라인 구현 계획

> 상태: ✅ 실행됨 · 🗄 부분 대체 (2026-08-06 작성·실행 — 이후 같은 날 설계 재논의로 **공용 테이블 재사용 → 전면 전용 스키마, 감지/트래킹 구분 → 매일 전량**으로 개정. 정본은 DECISIONS 08-06 개정 행)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 회원가입 계정에 태그된 게시물을 자동 모니터링하는 수집 파이프라인을 monitoring 모듈에 구현한다(수집만 — was 조회 API·FE 계약은 범위 밖, 2026-08-06 사용자 확정).

**Architecture:** 기존 monitoring 모듈을 최대 재사용한다 — 스냅샷·게시물 메타·댓글·게시자 표시 메타는 **기존 테이블(post_snapshot·post_meta·post_comment·profile_meta)과 SnapshotWriter.savePost 깔때기를 그대로 쓰고**, 신규 테이블은 브랜드 도메인 3개(brand_account·brand_tagged_post·author_profile)만 만든다. 수집은 `/v2/user/tag/medias` 열거 단일 경로(단건 게시물 조회 금지), 감지 매일 1콜·트래킹 3일 1회 105개 깊이(감지 겸함), 윈도우 90일 & 105개.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcTemplate, Flyway, Testcontainers(PostgreSQL), Jackson 3(`tools.jackson.*`). 테스트는 기존 모듈 관용구(Mockito 없이 fake HikerHttp 람다 + 스텁 서브클래스 + TestDb Testcontainers).

**정본 스펙:** [specs/2026-08-06-brand-tag-monitoring-schedule-design.md](../../specs/archive/2026-08-06-brand-tag-monitoring-schedule-design.md) · 실측 [findings §11](2026-07-28-monitoring-hiker-findings.md)

## Global Constraints

- **열거 단일 경로**: 태그 게시물 수집은 `/v2/user/tag/medias`만. `/v2/media/*` 단건 게시물 콜 금지(댓글 `/v2/media/comments`는 예외 — 스펙 §2).
- **페이지당 21건 하드코딩 금지**: IG 소관 값. "목표 개수(105)를 채울 때까지 `next_page_id` 추종"으로 구현(스펙 §6).
- **태그 열거 정렬은 태그된 시점 순**(taken_at 비단조, 소급 태그 혼입): 페이지 안에서 "기지 code 만나면 중단" 금지 — 페이지 전체 처리 + code dedupe. 백필 90일 컷은 "**페이지 전체가** 컷 이전일 때 중단"(스펙 §6).
- **감지 매일 1페이지 1콜 고정**(스펙 §3 — 비용 모델 월 30콜과 정합). 놓친 심층분은 3일 트래킹(105개)이 잡는다.
- **윈도우 90일 & 105개**: 백필도 이후 추적도 같은 기준. 윈도우 이탈 = 추적 자연 종료(별도 상태 없음).
- **댓글 게이팅**: 열거 `comment_count` > 저장값일 때만, 게시물당 최대 3콜(45개), 기지 댓글 페이지에서 중단(스펙 §2).
- **게시자 프로필**: `/v2/user/by/id`, 브랜드 간 전역 캐시, 처리 대상 게시물 작성자 중 미보유·30일 경과만 콜(신규 감지 1회 + 등장 시 stale 갱신 — 월 일괄 배치 아님, §8).
- **복권 3종(저장·공유·리포스트)**: DB 적재·FE 미노출. **재시도 콜 없음**(비용 모델에 재시도 예산 없음) — 캐리포워드(fb 합산)·0 캐리·부재=0 규칙만 재사용(DECISIONS 08-03·08-04·08-05).
- **계정 추적은 가입 시 자동 시작~탈퇴까지**(휴면 완화 없음, §8).
- 마이그레이션은 UTC 타임스탬프 채번 + expand-contract(CLAUDE.md). 주석·로그·커밋 한국어. 커밋 prefix `feat(monitoring):`.
- 테스트 실행 전 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 필수(CLAUDE.md 함정).
- 시스템 경계: monitoring DB만 접근. crawler DB 프로필 5.4만 건 재사용은 **범위 밖**(스펙 §6은 "후보"로만 언급 — 크로스 DB 접근 금지).

## 설계 결정(이 계획이 확정하는 것)

1. **스냅샷·메타·댓글 테이블 재사용** — 태그 게시물도 `post_snapshot`(short_code, captured_on)·`post_meta`·`post_comment`·`profile_meta`에 적재한다. `SnapshotWriter.savePost`가 단일 깔때기라 fb 캐리포워드·역전파·게시자 표시 메타 upsert가 공짜로 따라오고, `AlarmRecorder.recordMetricsHidden`은 추적 캠페인이 없는 code에서 자연 no-op(`findTrackingOwners` 빈 결과 → return — AlarmRecorder.java:87-90 확인됨). 캠페인 조회 표면(v_target_overview 등)은 tracked_short_code 조인이라 무영향.
2. **감지일에도 기지 게시물 스냅샷은 upsert한다** — 감지 1페이지가 공짜로 실어 오는 지표를 버리지 않는다(추가 콜 0). 단 댓글 게이트·게시자 stale 갱신은 트래킹일(+신규 감지분)에만 — 콜이 드는 작업은 스펙 주기 그대로.
3. **부재=0은 저장 시점에 즉시 적용** — 캠페인 모듈은 재시도 소진 시점에 적용하지만 태그 경로는 재시도가 없으므로, 열거 응답에서 saves 관측 ∧ reposts/shares(숨김 제외) 부재면 그 자리에서 0 기록(save·repost 키 동반 실림 566/596 근거 동일). 남은 null은 0 캐리 이력 판정(`SnapshotRepository.codesWith*ZeroCarry` 재사용).
4. **등록 백필 = 트래킹과 같은 코드** — `track()` 하나가 등록 백필·3일 트래킹 양쪽을 담당(깊이·컷 규칙이 동일하다는 스펙 §4 정합의 코드 표현). 등록은 프로필 1콜만 동기, 백필은 전용 단일 스레드 executor에서 비동기(was 10초 예산 관용구). 백필 실패 시 `last_tracked_on`이 null로 남아 다음 스윕이 트래킹으로 백스톱.
5. **브랜드 스윕은 캠페인 스윕과 분리** — 전용 스케줄러·크론 키(`monitoring.brand.schedule.sweep-cron`, 기본 `-` 비활성). sweep_run 기록·재시도 라운드는 도입하지 않는다(YAGNI — 실패는 브랜드 단위 격리 + 다음날 백스톱).

## File Structure

| 파일 | 책임 |
|---|---|
| `monitoring/src/main/resources/db/migration/V20260806150000__brand_tag_monitoring.sql` | 신규 3테이블(expand) |
| `monitoring/src/main/java/com/celfit/monitoring/hiker/AuthorInfo.java` (신규) | `/v2/user/by/id` 파싱 결과 record |
| `monitoring/src/main/java/com/celfit/monitoring/hiker/ProfileInfo.java` (수정) | biography 필드 추가 |
| `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java` (수정) | fetchTaggedPage·fetchAuthorProfile·fetchComments(knownIds 중단) 추가 |
| `monitoring/src/main/java/com/celfit/monitoring/hiker/RecordingHikerHttp.java` (수정) | 신규 경로 2종 kind 판정 |
| `monitoring/src/main/java/com/celfit/monitoring/domain/BrandStatus.java` (신규) | ACTIVE/CLOSED enum |
| `monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java` (신규) | brand_account 접점 |
| `monitoring/src/main/java/com/celfit/monitoring/store/BrandRow.java` (신규) | 조회 record |
| `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java` (신규) | brand_tagged_post 접점 |
| `monitoring/src/main/java/com/celfit/monitoring/store/AuthorProfileRepository.java` (신규) | author_profile 접점(전역 캐시) |
| `monitoring/src/main/java/com/celfit/monitoring/store/CommentRepository.java` (수정) | findIds 추가 |
| `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` (신규) | 열거 워크·감지/트래킹 적용·지표 보정·댓글 게이트·게시자 캐시 |
| `monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java` (신규) | 브랜드 순회 + 3일 주기 판정 + 격리 |
| `monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepScheduler.java` (신규) | 크론 진입점 |
| `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java` (신규) | 등록/탈퇴 + 비동기 백필 |
| `monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java` (신규) | 백필 전용 executor |
| `monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java` (신규) | POST/DELETE /api/brands |
| `monitoring/src/main/resources/application.yml` (수정) | monitoring.brand.* 설정 |
| 테스트: `MigrationTest`(수정)·`HikerClientTest`(수정)·`RecordingHikerHttpTest`(수정)·`BrandStoreTest`(신규)·`BrandCollectServiceTest`(신규)·`BrandSweepJobTest`(신규)·`BrandRegistrationServiceTest`(신규)·`BrandControllerTest`(신규) | |

---

### Task 1: 마이그레이션 — 브랜드 도메인 3테이블

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V20260806150000__brand_tag_monitoring.sql`
- Modify: `monitoring/src/test/java/com/celfit/monitoring/MigrationTest.java`

**Interfaces:**
- Produces: `brand_account`·`brand_tagged_post`·`author_profile` 테이블(이후 Task 4 리포지토리가 사용). was_reader SELECT는 V2의 ALTER DEFAULT PRIVILEGES가 자동 적용(별도 GRANT 불필요 — 기존 관용구).

- [ ] **Step 1: MigrationTest에 실패하는 테스트 추가**

`MigrationTest.java`에 추가(기존 테스트 관용구 그대로):

```java
/** 브랜드 태그 모니터링(2026-08-06 스펙) — 신규 3테이블이 생성되는지. */
@Test
void 브랜드_태그_모니터링_테이블_3종이_생성된다() {
	var ds = TestDb.dataSource(TestDb.container());
	var db = new JdbcTemplate(ds);
	TestDb.resetAndMigrate(db, ds);

	Long tables = db.queryForObject("""
			SELECT count(*) FROM information_schema.tables
			WHERE (table_schema, table_name) IN
			  (('public','brand_account'), ('public','brand_tagged_post'), ('public','author_profile'))""",
			Long.class);
	assertThat(tables).isEqualTo(3);
}

/** 신규 표면도 was_reader가 SELECT할 수 있어야 한다 — V2 ALTER DEFAULT PRIVILEGES 자동 적용 확인. */
@Test
void was_reader는_브랜드_표면을_SELECT할_수_있다() {
	var pg = TestDb.container();
	var ds = TestDb.dataSource(pg);
	var db = new JdbcTemplate(ds);
	TestDb.resetAndMigrate(db, ds);
	var wasReader = new JdbcTemplate(TestDb.wasReaderDataSource(pg));

	assertThat(wasReader.queryForObject("SELECT count(*) FROM brand_account", Long.class)).isZero();
	assertThat(wasReader.queryForObject("SELECT count(*) FROM brand_tagged_post", Long.class)).isZero();
	assertThat(wasReader.queryForObject("SELECT count(*) FROM author_profile", Long.class)).isZero();
}
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test --tests "com.celfit.monitoring.MigrationTest"
```
Expected: 신규 테스트 2건 FAIL (count 0 ≠ 3).

- [ ] **Step 3: 마이그레이션 작성**

`V20260806150000__brand_tag_monitoring.sql`:

```sql
-- 브랜드 태그 모니터링(2026-08-06 스펙) — expand 단계 신규 3테이블.
-- 스냅샷·게시물 메타·댓글·게시자 표시 메타는 기존 post_snapshot·post_meta·post_comment·
-- profile_meta를 재사용한다(SnapshotWriter.savePost 단일 깔때기) — 여기엔 브랜드 도메인만 담는다.
-- was_reader SELECT는 V2의 ALTER DEFAULT PRIVILEGES가 자동 적용(별도 GRANT 불필요).

-- 브랜드 계정 — 가입 시 자동 시작, 탈퇴(CLOSED)까지 추적(휴면 완화 없음 — 스펙 §8).
-- followers·biography는 등록 시 1콜의 관측값(스펙 §2 — 상시 갱신 대상 아님).
CREATE TABLE brand_account (
    id              bigserial   PRIMARY KEY,
    username        text        NOT NULL UNIQUE,
    ig_user_id      text        NOT NULL,   -- 태그 열거의 user_id 파라미터(등록 프로필 콜에서 해석)
    followers       bigint,
    biography       text,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    registered_at   timestamptz NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    -- 3일 트래킹 주기 판정 기준. null = 백필 미완(등록 직후 비동기 백필 실패 포함) —
    -- 다음 스윕이 트래킹으로 백스톱한다(감지/트래킹 판정은 BrandSweepJob).
    last_tracked_on date
);

-- 브랜드별 확보 태그 게시물 — 지표·메타·댓글은 기존 공용 테이블에 있고, 여기는
-- "이 브랜드 윈도우에 이 게시물이 있다"는 링크와 댓글 게이트 상태만 담는다.
CREATE TABLE brand_tagged_post (
    brand_id                 bigint      NOT NULL REFERENCES brand_account (id),
    short_code               text        NOT NULL,
    author_username          text        NOT NULL,
    author_ig_user_id        text,                    -- 열거 user.pk(셰이프에 따라 null 가능)
    taken_at                 timestamptz NOT NULL,    -- 90일 윈도우 컷 판정 기준(열거 taken_at)
    first_seen_at            timestamptz NOT NULL DEFAULT now(),
    -- 댓글 게이팅 저장값(스펙 §2) — 마지막 댓글 수집 시점의 열거 comment_count.
    -- 열거값이 이보다 클 때만 댓글 콜을 낸다(신규 게시물은 0이라 "댓글 1개 이상만 수집"이 자동 성립).
    comments_collected_count bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (brand_id, short_code)
);

-- 게시자(인플루언서) 프로필 — 브랜드 간 전역 캐시(스펙 §6). 이력 없이 최신 1행 upsert,
-- fetched_at 30일 경과 + 트래킹 등장 시 재조회(월 일괄 배치 아님 — 스펙 §8).
CREATE TABLE author_profile (
    ig_user_id      text        PRIMARY KEY,
    username        text        NOT NULL,
    full_name       text,
    followers       bigint,
    following       bigint,
    media_count     bigint,
    biography       text,
    profile_pic_url text,
    is_private      boolean,    -- 게시자 비공개는 오류가 아니라 관측값(브랜드 계정과 다름)
    fetched_at      timestamptz NOT NULL
);
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.MigrationTest"
```
Expected: PASS (기존 테스트 포함 전건).

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/resources/db/migration/V20260806150000__brand_tag_monitoring.sql monitoring/src/test/java/com/celfit/monitoring/MigrationTest.java
git commit -m "feat(monitoring): 브랜드 태그 모니터링 도메인 3테이블 — brand_account·brand_tagged_post·author_profile"
```

---

### Task 2: HikerClient — 게시자 프로필(by/id)·브랜드 biography·원형 적재 kind

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/hiker/AuthorInfo.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/ProfileInfo.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/RecordingHikerHttp.java`
- Create: `monitoring/src/test/resources/hiker/author-profile-by-id.json`
- Test: `monitoring/src/test/java/com/celfit/monitoring/hiker/HikerClientTest.java`, `RecordingHikerHttpTest.java`

**Interfaces:**
- Produces: `record AuthorInfo(String igUserId, String username, String fullName, Long followers, Long following, Long mediaCount, String biography, String profilePicUrl, boolean isPrivate)`
- Produces: `HikerClient.fetchAuthorProfile(String userId)` → `AuthorInfo` (비공개여도 예외 없이 관측값 반환 — 게시자 비공개는 데이터)
- Produces: `ProfileInfo`에 `String biography` 필드 추가(profilePicUrl 뒤, rawJson 앞) — Task 8 브랜드 등록이 사용
- 주의: `/v2/user/by/id` 응답 셰이프는 라이브 미실측(스펙 §2가 경로만 확정) — `/v2/user/by/username`과 동일한 `{user:{...}}` 셰이프로 가정하고 방어적으로 파싱한다. 픽스처는 profile.json에서 파생(가정임을 픽스처 주석 불가하니 테스트 javadoc에 명시).

- [ ] **Step 1: 픽스처 생성**

`monitoring/src/test/resources/hiker/profile.json`을 읽어 같은 셰이프로 `author-profile-by-id.json`을 만든다 — `user` 노드에 `pk`(number)·`username`·`full_name`·`follower_count`·`following_count`·`media_count`·`biography`·`profile_pic_url`·`is_private:false` 포함(값은 임의 인플루언서로 변경, 예: username "beauty_creator", pk 9876543210, follower_count 152000).

- [ ] **Step 2: 실패하는 테스트 작성**

`HikerClientTest.java`에 추가:

```java
/**
 * 게시자 프로필(/v2/user/by/id — 브랜드 태그 모니터링 스펙 §2). 응답 셰이프는 라이브 미실측이라
 * /v2/user/by/username과 동일한 {user:{...}}로 가정(스펙이 경로만 확정) — 픽스처는 profile.json 파생.
 */
@Test
void 게시자_프로필_파싱_by_id() {
	HikerClient client = new HikerClient(path -> fixture("author-profile-by-id.json"));
	AuthorInfo a = client.fetchAuthorProfile("9876543210");
	assertThat(a.igUserId()).isEqualTo("9876543210");
	assertThat(a.username()).isNotBlank();
	assertThat(a.followers()).isPositive();
}

/** 게시자 비공개는 오류가 아니라 관측값이다 — fetchProfile과 달리 예외를 던지면 안 된다. */
@Test
void 게시자_프로필은_비공개여도_예외_없이_관측값을_준다() {
	String privateUser = fixture("author-profile-by-id.json").replace("\"is_private\": false", "\"is_private\": true");
	HikerClient client = new HikerClient(path -> privateUser);
	AuthorInfo a = client.fetchAuthorProfile("9876543210");
	assertThat(a.isPrivate()).isTrue();
}

@Test
void 프로필_파싱은_biography를_담는다() {
	HikerClient client = new HikerClient(fakeHttp());
	ProfileInfo p = client.fetchProfile("rarebeauty");
	assertThat(p.biography()).isNotNull();   // profile.json의 user.biography
}
```

주의: `is_private` 치환 문자열은 픽스처 실제 포맷(공백 유무)에 맞춘다. profile.json에 `biography` 키가 없으면 픽스처에 추가하지 말고 assertion을 `isNull()` 확인으로 두지 말 것 — **픽스처를 열어 실제 키 존재를 확인**하고, 없으면 author-profile-by-id.json에만 biography를 넣고 프로필 테스트는 `p.biography()`가 null이어도 파싱이 안 죽는지로 완화한다(실측 픽스처 불변 원칙).

- [ ] **Step 3: 실패 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.HikerClientTest"
```
Expected: 컴파일 실패 (AuthorInfo·fetchAuthorProfile·biography 미정의).

- [ ] **Step 4: 구현**

`AuthorInfo.java`:

```java
package com.celfit.monitoring.hiker;

/**
 * 게시자(인플루언서) 프로필 — /v2/user/by/id 파싱 결과(브랜드 태그 모니터링 스펙 §2).
 * 브랜드 계정 프로필(ProfileInfo)과 달리 비공개(isPrivate)가 오류가 아니라 관측값이다 —
 * 게시자 캐시(author_profile)는 표시·집계용이지 수집 가능성 판정용이 아니다.
 * 원형은 전송 계층(RecordingHikerHttp)이 남기므로 rawJson을 나르지 않는다.
 */
public record AuthorInfo(String igUserId, String username, String fullName, Long followers,
		Long following, Long mediaCount, String biography, String profilePicUrl, boolean isPrivate) {}
```

`ProfileInfo.java` — biography 추가:

```java
public record ProfileInfo(String username, String userId, Long followers, Long following,
		Long mediaCount, String fullName, String profilePicUrl, String biography, String rawJson) {}
```

`HikerClient.java`:
- `fetchProfile`의 return을 biography 포함으로 수정: `user.path("biography").asString(null)`을 profilePicUrl 다음 인자로.
- 신규 메서드 추가:

```java
/**
 * 게시자 프로필 — /v2/user/by/id?user_id=(브랜드 태그 모니터링 스펙 §2). fetchProfile과 달리
 * 비공개를 예외로 승격하지 않는다(게시자 비공개는 관측값 — author_profile.is_private).
 * 응답 셰이프는 by/username과 동일한 {user:{...}}로 가정(라이브 미실측 — 스펙이 경로만 확정).
 */
public AuthorInfo fetchAuthorProfile(String userId) {
	String body = http.get("/v2/user/by/id?user_id=" + enc(userId));
	JsonNode user = root(body).path("user");
	if (user.isMissingNode() || user.isNull()) {
		throw new HikerFetchException("게시자 프로필 응답에 user 없음: " + userId);
	}
	// user.pk는 JSON number(findings §2-①) — 응답의 pk를 정본으로 쓰되 없으면 요청값 유지.
	String igUserId = user.path("pk").isNumber() ? user.path("pk").asString() : userId;
	return new AuthorInfo(igUserId, user.path("username").asString(null),
			user.path("full_name").asString(null),
			firstLong(user, "follower_count"), firstLong(user, "following_count"),
			firstLong(user, "media_count"),
			user.path("biography").asString(null), user.path("profile_pic_url").asString(null),
			user.path("is_private").asBoolean(false));
}
```

- `ProfileInfo` 생성자 변경에 따른 컴파일 오류 전수 수정(컴파일러 주도): `CollectServiceTest`·`DailySweepJobTest` 등 테스트의 ProfileInfo 생성부에 biography 인자(null) 추가.

`RecordingHikerHttp.java` — kindOf에 분기 추가(기존 PROFILE 분기 **앞이 아니라 뒤**, `/v2/user/by/username`은 startsWith가 먼저 걸리므로 순서 무관하지만 가독성상 인접 배치):

```java
if (path.startsWith("/v2/user/by/id")) {
	return "PROFILE_BY_ID";
}
if (path.startsWith("/v2/user/tag/medias")) {
	return "TAGGED";
}
```

주의: `/v2/user/tag/medias` 분기는 `/v2/user/medias` 분기보다 **먼저** 와야 한다… 실제로 startsWith("/v2/user/medias")는 "/v2/user/tag/medias"에 매치되지 않으므로(경로 상이) 순서 무관 — 그래도 kindOf 최상단에 두 분기를 추가해 명시한다. subjectOf switch에 추가:

```java
case "PROFILE_BY_ID", "TAGGED" -> "user_id";
```

`RecordingHikerHttpTest.java`에 kind 판정 케이스 2건 추가(기존 테스트의 케이스 나열 관용구를 따라 `/v2/user/by/id?user_id=123` → kind PROFILE_BY_ID·subject "123", `/v2/user/tag/medias?user_id=123` → kind TAGGED·subject "123").

- [ ] **Step 5: 통과 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.*"
```
Expected: PASS. 이어서 모듈 컴파일 전체 확인: `./gradlew :monitoring:compileJava :monitoring:compileTestJava` PASS.

- [ ] **Step 6: 커밋**

```bash
git add -A monitoring/src
git commit -m "feat(monitoring): 게시자 프로필 /v2/user/by/id 파싱 + ProfileInfo biography + 태그 열거 원형 kind"
```

---

### Task 3: HikerClient — 태그 열거 fetchTaggedPage

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java`
- Create: `monitoring/src/test/resources/hiker/tag-medias.json`
- Test: `monitoring/src/test/java/com/celfit/monitoring/hiker/HikerClientTest.java`

**Interfaces:**
- Produces: `HikerClient.TaggedPage(List<PostInfo> posts, String nextPageId)` (HikerClient 내부 public record — ClipCounts 관용구)
- Produces: `HikerClient.fetchTaggedPage(String userId, String pageId)` → `TaggedPage`. **1페이지 1콜** — 페이지네이션은 호출자(BrandCollectService)가 중단 규칙과 함께 몬다. 404(Entries not found)는 "태그 0건"으로 빈 페이지 반환.
- PostInfo 파싱은 기존 `toPost(item, null, body, Map.of(), true)` 재사용 — 태그 열거는 릴스 조회수(`ig_play_count`)가 상시 인라인이라(findings §11-2) clips 보강이 필요 없고 viewsTrusted=true가 맞다. 작성자 user 노드(username·pk·full_name·profile_pic_url)도 toPost가 이미 뽑는다(ownerUserId 등).

- [ ] **Step 1: 픽스처 생성**

findings §11 셰이프(medias와 동형)로 `tag-medias.json` 작성 — `{"response":{"items":[...4건...],"num_results":4,"more_available":true},"next_page_id":"cursor-p2"}`. 4건 구성(태그 시점 순 정렬·taken_at 비단조 재현):
1. 릴스: `product_type:"clips"`, `ig_play_count:52000`, `play_count:52000`, `like_count`, `comment_count:3`, `save_count:120`, `reshare_count:80`, `media_repost_count:5`, `taken_at` 최근(예: 2026-08-01), `user:{pk:9876543210,"username":"beauty_creator","full_name":"뷰티크리에이터","profile_pic_url":"https://..."}`, `caption:{text:"#브랜드 태그"}`, `image_versions2.candidates[0].url` 존재
2. 피드 단일(t=1): play/save/reshare 키 부재, `media_repost_count` 존재, 다른 작성자 user
3. 캐러셀(t=8): `reshare_count` 존재(§11-2 "캐러셀에도 꽤 실린다"), play/save 부재
4. **소급 태그 옛 릴스**: `taken_at` 2026-01-03(비단조 — 최신 페이지에 1월 게시물 혼입 재현), ig_play_count 존재

- [ ] **Step 2: 실패하는 테스트 작성**

```java
/** 태그 열거(findings §11) — 릴스 조회수가 인라인이라 clips 보강 콜 없이 1페이지 1콜이어야 한다. */
@Test
void 태그_열거는_1콜에_릴스_조회수_인라인이고_클립_콜이_없다() {
	List<String> calls = new ArrayList<>();
	HikerClient client = new HikerClient(path -> {
		calls.add(path);
		return fixture("tag-medias.json");
	});
	var page = client.fetchTaggedPage("17841400000000000", null);
	assertThat(calls).hasSize(1);
	assertThat(calls.getFirst()).startsWith("/v2/user/tag/medias?user_id=");
	assertThat(page.nextPageId()).isEqualTo("cursor-p2");
	var reel = page.posts().stream().filter(p -> "REELS".equals(p.contentType())).findFirst().orElseThrow();
	assertThat(reel.views()).isPositive();        // ig_play_count 인라인(§11-2 — clips 머지 불필요)
	assertThat(reel.viewsTrusted()).isTrue();
	assertThat(reel.ownerUserId()).isNotBlank();  // 게시자 프로필 콜의 user_id 공급원
	assertThat(reel.username()).isNotBlank();     // 작성자 username(usernameHint 없이 user 노드에서)
}

/** 커서 전달 — 2페이지 요청은 page_id 파라미터를 실어야 한다. */
@Test
void 태그_열거는_커서를_page_id로_전달한다() {
	List<String> calls = new ArrayList<>();
	HikerClient client = new HikerClient(path -> {
		calls.add(path);
		return fixture("tag-medias.json");
	});
	client.fetchTaggedPage("17841400000000000", "cursor-p2");
	assertThat(calls.getFirst()).contains("page_id=cursor-p2");
}

/** 태그 0건 계정 — Hiker는 200 빈 배열이 아니라 404를 준다(fetchRecentPosts와 동일 규칙). */
@Test
void 태그_열거_404는_빈_페이지다() {
	HikerClient client = new HikerClient(path -> {
		throw new SubjectNotFoundException("Entries not found");
	});
	var page = client.fetchTaggedPage("17841400000000000", null);
	assertThat(page.posts()).isEmpty();
	assertThat(page.nextPageId()).isNull();
}
```

- [ ] **Step 3: 실패 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.HikerClientTest"
```
Expected: 컴파일 실패 (fetchTaggedPage 미정의).

- [ ] **Step 4: 구현**

`HikerClient.java`에 추가:

```java
/** 태그 열거 1페이지 — posts는 응답 순서 그대로(태그된 시점 순 — 중단 판정은 호출자가 페이지 단위로 한다). */
public record TaggedPage(List<PostInfo> posts, String nextPageId) {}

/**
 * 계정에 태그된 게시물 열거 — /v2/user/tag/medias(findings §11). 1페이지 1콜만 하고 커서를
 * 그대로 반환한다: 감지(매일 1콜)·트래킹(105개 깊이)·백필(90일 컷)의 중단 규칙이 서로 달라
 * 페이지네이션은 호출자(BrandCollectService)가 몬다. 페이지당 건수는 IG 소관 값(실측 21)이라
 * 여기서 어떤 개수도 가정하지 않는다(스펙 §6 하드코딩 금지).
 *
 * <p>릴스 조회수(ig_play_count)는 이 열거에 상시 인라인이다(§11-2 — 프로필 열거와 결정적 차이)
 * → clips 보강 없이 viewsTrusted=true. 정렬은 태그된 시점 순이라 taken_at 비단조(소급 태그 혼입)
 * — 재정렬하지 않고 응답 순서를 유지한다(페이지 단위 중단 판정이 순서에 의존).
 */
public TaggedPage fetchTaggedPage(String userId, String pageId) {
	String body;
	try {
		body = http.get("/v2/user/tag/medias?user_id=" + enc(userId) + pageParam(pageId));
	} catch (SubjectNotFoundException e) {
		// 태그된 게시물이 0건이면 Hiker는 200 빈 배열이 아니라 404를 준다(fetchRecentPosts와
		// 동일 규칙) — 브랜드 계정에 태그가 아직 없는 건 정상 상태라 조용히 빈 페이지로 넘긴다.
		log.info("태그 열거 404 — user_id {} page_id {}, 태그 게시물 없음/커서 종료로 간주", userId, pageId);
		return new TaggedPage(List.of(), null);
	}
	JsonNode root = root(body);
	List<PostInfo> posts = new ArrayList<>();
	for (JsonNode item : items(root)) {
		posts.add(toPost(item, null, body, Map.of(), true));
	}
	String cursor = moreAvailable(root) ? nextPageId(root) : null;
	return new TaggedPage(posts, cursor);
}
```

- [ ] **Step 5: 통과 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.HikerClientTest"
```
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add -A monitoring/src
git commit -m "feat(monitoring): 태그 열거 fetchTaggedPage — 1페이지 1콜, 조회수 인라인, 404=태그 0건"
```

---

### Task 4: 댓글 기지 중단 + CommentRepository.findIds

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/CommentRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/hiker/HikerClientTest.java`, `monitoring/src/test/java/com/celfit/monitoring/store/StoreTest.java`

**Interfaces:**
- Produces: `HikerClient.fetchComments(String shortCode, String postUsername, int pages, Set<String> knownCommentIds)` — 페이지 처리 **후** 그 페이지의 유효 댓글이 1건 이상이고 전부 knownCommentIds에 있으면 다음 페이지를 부르지 않는다(스펙 §3 "최신부터 읽다 기지 댓글에서 중단" — 정렬이 IG 랭킹 혼합이라 건 단위가 아닌 페이지 단위 중단). 기존 3-인자 메서드는 `Set.of()`로 위임(캠페인 경로 동작 불변).
- Produces: `CommentRepository.findIds(String shortCode)` → `Set<String>` (post_comment의 기존 id 집합).

- [ ] **Step 1: 실패하는 테스트 작성**

`HikerClientTest.java`:

```java
/** 댓글 기지 중단(태그 모니터링 스펙 §3) — 페이지 전체가 기지 댓글이면 다음 페이지를 부르지 않는다. */
@Test
void 댓글_수집은_페이지_전체가_기지면_중단한다() {
	List<String> calls = new ArrayList<>();
	HikerClient client = new HikerClient(path -> {
		calls.add(path);
		return alwaysMorePage(calls.size());   // CollectServiceTest 관용구 — 필요시 이 클래스에 동일 헬퍼 추가
	});
	// 1페이지의 댓글 id(c1)가 이미 기지 — 3페이지 허용이어도 1콜에서 멈춰야 한다.
	var comments = client.fetchComments("DbV7LgZsKG8", "brand", 3, Set.of("c1"));
	assertThat(calls).hasSize(1);
	assertThat(comments).hasSize(1);   // 기지여도 이번 응답분은 반환(upsert 갱신용)
}

/** 기지 집합이 비면 기존 동작 그대로 페이지 수만큼 간다. */
@Test
void 댓글_수집은_기지_집합이_비면_페이지_수만큼_간다() {
	List<String> calls = new ArrayList<>();
	HikerClient client = new HikerClient(path -> {
		calls.add(path);
		return alwaysMorePage(calls.size());
	});
	client.fetchComments("DbV7LgZsKG8", "brand", 3, Set.of());
	assertThat(calls).hasSize(3);
}
```

`alwaysMorePage` 헬퍼는 `CollectServiceTest`의 것과 동일 형태(매 페이지 pk가 `c<콜순번>`인 유효 댓글 1건 + next_page_id) — HikerClientTest에 복사 추가.

`StoreTest.java`(기존 Testcontainers 스토어 테스트 클래스)에 추가:

```java
@Test
void 댓글_id_집합을_조회한다() {
	// 기존 StoreTest 셋업 관용구를 따라 post_comment에 2건 upsert 후:
	assertThat(comments.findIds("CodeA")).containsExactlyInAnyOrder("c1", "c2");
	assertThat(comments.findIds("없는코드")).isEmpty();
}
```

(StoreTest의 실제 셋업·필드명은 그 파일 관용구에 맞춰 조정 — CommentRepository 인스턴스를 이미 만들고 있으면 재사용.)

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.HikerClientTest" --tests "com.celfit.monitoring.store.StoreTest"
```
Expected: 컴파일 실패 (4-인자 fetchComments·findIds 미정의).

- [ ] **Step 3: 구현**

`HikerClient.fetchComments` — 기존 3-인자 본문을 4-인자로 옮기고 중단 조건 추가:

```java
public List<CommentInfo> fetchComments(String shortCode, String postUsername, int pages) {
	return fetchComments(shortCode, postUsername, pages, Set.of());
}

/**
 * knownCommentIds가 주어지면(태그 모니터링 경로) 페이지 처리 후 그 페이지의 유효 댓글이
 * 1건 이상 전부 기지일 때 다음 페이지를 부르지 않는다 — "최신부터 읽다 기지 댓글에서 중단"
 * (스펙 §3). 정렬이 IG 랭킹 혼합이라 건 단위 중단은 신규를 놓칠 수 있어 페이지 단위로 본다.
 * 기지 댓글도 반환 목록에는 담는다(upsert가 body·like_count를 갱신).
 */
public List<CommentInfo> fetchComments(String shortCode, String postUsername, int pages,
		Set<String> knownCommentIds) {
	// ...기존 루프 본문 그대로, 페이지 for 루프 안 커서 전진 가드 뒤에 추가:
	//   List<CommentInfo> pageComments = (이번 페이지에서 추가된 분 — before 인덱스로 subList)
	//   if (!knownCommentIds.isEmpty() && !pageComments.isEmpty()
	//           && pageComments.stream().allMatch(c -> knownCommentIds.contains(c.id()))) {
	//       break;   // 페이지 전체 기지 — 더 내려가도 신규가 없다고 본다
	//   }
}
```

구현 시 기존 `before` 변수(커서 전진 가드용 out.size())를 재사용해 `out.subList(before, out.size())`로 이번 페이지분을 얻는다.

`CommentRepository.findIds`:

```java
/** 게시물의 기존 댓글 id 집합 — 태그 모니터링 댓글 수집의 기지 중단 판정용(스펙 §3). */
public Set<String> findIds(String shortCode) {
	return new HashSet<>(db.queryForList(
			"SELECT id FROM post_comment WHERE short_code = ?", String.class, shortCode));
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.HikerClientTest" --tests "com.celfit.monitoring.store.StoreTest"
```
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A monitoring/src
git commit -m "feat(monitoring): 댓글 기지 페이지 중단 + post_comment id 조회 — 태그 댓글 재수집 게이트 재료"
```

---

### Task 5: 스토어 — BrandRepository·TaggedPostRepository·AuthorProfileRepository

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/domain/BrandStatus.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRow.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/AuthorProfileRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java` (신규 — StoreTest 관용구)

**Interfaces:**
- Produces:
  - `enum BrandStatus { ACTIVE, CLOSED }`
  - `record BrandRow(long id, String username, String igUserId, BrandStatus status, LocalDate lastTrackedOn)`
  - `BrandRepository`: `long insertOrReactivate(String username, String igUserId, Long followers, String biography)` / `Optional<BrandRow> findByUsername(String username)` / `List<BrandRow> findActive()` / `boolean close(String username)` / `void touchTracked(long brandId, LocalDate on)`
  - `TaggedPostRepository`: `Set<String> knownCodes(long brandId)` / `void insert(long brandId, PostInfo post)` (author_username=post.username(), author_ig_user_id=post.ownerUserId(), taken_at=Instant.ofEpochSecond(post.takenAt()) — ON CONFLICT DO NOTHING) / `Map<String, Long> commentsCollectedCounts(long brandId, Collection<String> codes)` / `void updateCommentsCollected(long brandId, String shortCode, long count)`
  - `AuthorProfileRepository`: `void upsert(AuthorInfo a)` (fetched_at=now()) / `Set<String> freshIgUserIds(Collection<String> igUserIds, Instant staleBefore)` — fetched_at ≥ staleBefore인(=콜 불필요) id만 반환. 필요분 = 후보 − 반환값.

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandStoreTest.java` — `StoreTest`의 셋업 관용구(TestDb.container()+resetAndMigrate, JdbcTemplate 직접 생성)를 따라 작성:

```java
package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.AuthorInfo;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BrandStoreTest {

	private JdbcTemplate db;
	private BrandRepository brands;
	private TaggedPostRepository taggedPosts;
	private AuthorProfileRepository authors;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		brands = new BrandRepository(db);
		taggedPosts = new TaggedPostRepository(db);
		authors = new AuthorProfileRepository(db);
	}

	@Test
	void 브랜드_등록과_재가입_반환() {
		long id = brands.insertOrReactivate("brandx", "111", 1000L, "소개");
		assertThat(brands.findActive()).hasSize(1);
		assertThat(brands.close("brandx")).isTrue();
		assertThat(brands.findActive()).isEmpty();
		assertThat(brands.close("brandx")).isFalse();          // 이미 닫힘 — 멱등
		long reId = brands.insertOrReactivate("brandx", "111", 2000L, "소개2");
		assertThat(reId).isEqualTo(id);                        // 같은 행 재활성(UNIQUE username)
		BrandRow row = brands.findByUsername("brandx").orElseThrow();
		assertThat(row.status()).isEqualTo(BrandStatus.ACTIVE);
		assertThat(row.lastTrackedOn()).isNull();              // 재가입 시 초기화 — 백필 백스톱 재발동
	}

	@Test
	void 트래킹_일자_갱신() {
		long id = brands.insertOrReactivate("brandx", "111", null, null);
		brands.touchTracked(id, LocalDate.of(2026, 8, 6));
		assertThat(brands.findByUsername("brandx").orElseThrow().lastTrackedOn())
				.isEqualTo(LocalDate.of(2026, 8, 6));
	}

	@Test
	void 태그_게시물_링크와_댓글_게이트_상태() {
		long id = brands.insertOrReactivate("brandx", "111", null, null);
		taggedPosts.insert(id, post("CodeA", 1754000000L));
		taggedPosts.insert(id, post("CodeA", 1754000000L));    // 재감지 — ON CONFLICT 무해
		assertThat(taggedPosts.knownCodes(id)).containsExactly("CodeA");
		assertThat(taggedPosts.commentsCollectedCounts(id, Set.of("CodeA")))
				.containsEntry("CodeA", 0L);
		taggedPosts.updateCommentsCollected(id, "CodeA", 7);
		assertThat(taggedPosts.commentsCollectedCounts(id, Set.of("CodeA")))
				.containsEntry("CodeA", 7L);
	}

	@Test
	void 게시자_캐시_upsert와_stale_판정() {
		authors.upsert(new AuthorInfo("999", "creator", "이름", 100L, 10L, 5L, "bio", "https://p", false));
		// 방금 넣은 행은 신선하다 — 30일 전 기준으로 fresh 집합에 있어야 한다.
		assertThat(authors.freshIgUserIds(Set.of("999", "888"), Instant.now().minusSeconds(30L * 24 * 3600)))
				.containsExactly("999");   // 888은 미보유 → 콜 필요
		assertThat(authors.freshIgUserIds(Set.of("999"), Instant.now().plusSeconds(60)))
				.isEmpty();                // 기준이 미래면 전원 stale
		assertThat(authors.freshIgUserIds(List.of(), Instant.now())).isEmpty();
	}

	private static PostInfo post(String code, long takenAt) {
		return new PostInfo(code, "creator", null, null, "999", "REELS", "캡션", null,
				takenAt, 10L, 2L, 100L, null, null, null, null, "{}", true, false, false);
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest"
```
Expected: 컴파일 실패 (신규 클래스 미정의).

- [ ] **Step 3: 구현**

`BrandStatus.java`:

```java
package com.celfit.monitoring.domain;

/** 브랜드 계정 추적 상태 — 가입 시 ACTIVE로 시작, 탈퇴 시 CLOSED(휴면 완화 없음 — 스펙 §8). */
public enum BrandStatus { ACTIVE, CLOSED }
```

`BrandRow.java`:

```java
package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.BrandStatus;
import java.time.LocalDate;

/** 브랜드 스윕·등록이 쓰는 조회 단면 — followers·biography는 등록 시 1회 관측이라 싣지 않는다. */
public record BrandRow(long id, String username, String igUserId, BrandStatus status,
		LocalDate lastTrackedOn) {}
```

`BrandRepository.java`:

```java
package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.BrandStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** brand_account 접점 — username UNIQUE가 멱등 키다(같은 계정 재가입은 같은 행 재활성). */
@Repository
public class BrandRepository {

	private final JdbcTemplate db;

	public BrandRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * 등록 또는 재가입 — CLOSED 행이 있으면 ACTIVE로 재활성하고 프로필 관측값을 갱신한다.
	 * last_tracked_on을 null로 되돌리는 이유: 재가입 시점의 윈도우(90일)를 백필이 다시 채워야
	 * 하는데, 옛 값이 남으면 다음 스윕이 감지(1페이지)만 돌아 탈퇴 기간의 유입을 놓친다.
	 */
	public long insertOrReactivate(String username, String igUserId, Long followers, String biography) {
		return db.queryForObject("""
				INSERT INTO brand_account (username, ig_user_id, followers, biography)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (username) DO UPDATE SET
				  ig_user_id = EXCLUDED.ig_user_id, followers = EXCLUDED.followers,
				  biography = EXCLUDED.biography, status = 'ACTIVE', closed_at = NULL,
				  last_tracked_on = NULL, registered_at = now()
				RETURNING id""",
				Long.class, username, igUserId, followers, biography);
	}

	public Optional<BrandRow> findByUsername(String username) {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_tracked_on
				FROM brand_account WHERE username = ?""",
				BrandRepository::toRow, username).stream().findFirst();
	}

	public List<BrandRow> findActive() {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_tracked_on
				FROM brand_account WHERE status = 'ACTIVE' ORDER BY id""",
				BrandRepository::toRow);
	}

	/** 탈퇴 — ACTIVE였던 행만 닫는다. @return 실제로 전이됐으면 true(이미 닫힘·미존재는 false). */
	public boolean close(String username) {
		return db.update("""
				UPDATE brand_account SET status = 'CLOSED', closed_at = now()
				WHERE username = ? AND status = 'ACTIVE'""", username) > 0;
	}

	public void touchTracked(long brandId, LocalDate on) {
		db.update("UPDATE brand_account SET last_tracked_on = ? WHERE id = ?", on, brandId);
	}

	private static BrandRow toRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		java.sql.Date tracked = rs.getDate("last_tracked_on");
		return new BrandRow(rs.getLong("id"), rs.getString("username"), rs.getString("ig_user_id"),
				BrandStatus.valueOf(rs.getString("status")),
				tracked == null ? null : tracked.toLocalDate());
	}
}
```

`TaggedPostRepository.java`:

```java
package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.PostInfo;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * brand_tagged_post 접점 — 브랜드 윈도우의 게시물 링크 + 댓글 게이트 상태.
 * 지표·메타·댓글 본문은 기존 공용 테이블(post_snapshot·post_meta·post_comment)에 있다.
 */
@Repository
public class TaggedPostRepository {

	private final JdbcTemplate db;

	public TaggedPostRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** 이 브랜드가 확보한 전체 code — 감지 신규 판정용(윈도우 이탈분 포함: 재유입 시 신규 아님). */
	public Set<String> knownCodes(long brandId) {
		return new HashSet<>(db.queryForList(
				"SELECT short_code FROM brand_tagged_post WHERE brand_id = ?", String.class, brandId));
	}

	/** 신규 감지 게시물 링크 — 재감지(ON CONFLICT)는 무해하게 무시한다. taken_at null은 호출자가 거른다. */
	public void insert(long brandId, PostInfo post) {
		db.update("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id, taken_at)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (brand_id, short_code) DO NOTHING""",
				brandId, post.shortCode(), post.username(), post.ownerUserId(),
				Timestamp.from(Instant.ofEpochSecond(post.takenAt())));
	}

	/** 댓글 게이트 저장값 배치 조회(IN절 1쿼리) — 열거 comment_count가 이 값보다 클 때만 댓글 콜. */
	public Map<String, Long> commentsCollectedCounts(long brandId, Collection<String> codes) {
		if (codes.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
		Object[] args = new Object[codes.size() + 1];
		args[0] = brandId;
		int i = 1;
		for (String code : codes) {
			args[i++] = code;
		}
		Map<String, Long> out = new HashMap<>();
		db.query("SELECT short_code, comments_collected_count FROM brand_tagged_post WHERE brand_id = ? AND short_code IN ("
						+ placeholders + ")",
				rs -> {
					out.put(rs.getString("short_code"), rs.getLong("comments_collected_count"));
				}, args);
		return out;
	}

	public void updateCommentsCollected(long brandId, String shortCode, long count) {
		db.update("""
				UPDATE brand_tagged_post SET comments_collected_count = ?
				WHERE brand_id = ? AND short_code = ?""", count, brandId, shortCode);
	}
}
```

`AuthorProfileRepository.java`:

```java
package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.AuthorInfo;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * author_profile 접점 — 게시자 프로필 전역 캐시(브랜드 간 공유, 스펙 §6).
 * 이력 없이 최신 1행 upsert, 30일 stale 판정은 fetched_at 기준(스펙 §8 — 등장 시 갱신).
 */
@Repository
public class AuthorProfileRepository {

	private final JdbcTemplate db;

	public AuthorProfileRepository(JdbcTemplate db) {
		this.db = db;
	}

	public void upsert(AuthorInfo a) {
		db.update("""
				INSERT INTO author_profile (ig_user_id, username, full_name, followers, following,
				                            media_count, biography, profile_pic_url, is_private, fetched_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (ig_user_id) DO UPDATE SET
				  username = EXCLUDED.username, full_name = EXCLUDED.full_name,
				  followers = EXCLUDED.followers, following = EXCLUDED.following,
				  media_count = EXCLUDED.media_count, biography = EXCLUDED.biography,
				  profile_pic_url = EXCLUDED.profile_pic_url, is_private = EXCLUDED.is_private,
				  fetched_at = now()""",
				a.igUserId(), a.username(), a.fullName(), a.followers(), a.following(),
				a.mediaCount(), a.biography(), a.profilePicUrl(), a.isPrivate());
	}

	/**
	 * 신선한(콜 불필요) id 집합 — fetched_at ≥ staleBefore인 보유 행만 반환한다.
	 * 콜 필요분 = 후보 − 반환값(미보유·stale 모두 포함) — 호출자(BrandCollectService)가 차집합을 만든다.
	 */
	public Set<String> freshIgUserIds(Collection<String> igUserIds, Instant staleBefore) {
		if (igUserIds.isEmpty()) {
			return Set.of();
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(igUserIds.size(), "?"));
		Object[] args = new Object[igUserIds.size() + 1];
		int i = 0;
		for (String id : igUserIds) {
			args[i++] = id;
		}
		args[i] = java.sql.Timestamp.from(staleBefore);
		return new HashSet<>(db.queryForList(
				"SELECT ig_user_id FROM author_profile WHERE ig_user_id IN (" + placeholders
						+ ") AND fetched_at >= ?",
				String.class, args));
	}
}
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest"
```
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A monitoring/src
git commit -m "feat(monitoring): 브랜드 도메인 스토어 3종 — 재가입 재활성·댓글 게이트 상태·게시자 전역 캐시"
```

---

### Task 6: BrandCollectService — 열거 워크 + 감지/트래킹 적용

가장 큰 태스크. 스펙의 수집 규칙 전부가 여기 모인다.

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`

**Interfaces:**
- Consumes: `HikerClient.fetchTaggedPage/fetchAuthorProfile/fetchComments(4-인자)`, `SnapshotWriter.savePost(LocalDate, PostInfo)`, `SnapshotRepository.codesWithRepostsZeroCarry/codesWithSharesZeroCarry(Collection<String>, LocalDate)`, `CommentRepository.findIds/upsertForPost`, `BrandRepository`, `TaggedPostRepository`, `AuthorProfileRepository` (전부 앞 태스크 시그니처)
- Produces:
  - `void detect(BrandRow brand)` — 감지: 태그 열거 **정확히 1페이지 1콜**, 페이지 전체 code dedupe 처리(기지 code 만나도 페이지 내 중단 없음). 신규 in-window 게시물 → 링크 insert + 스냅샷 + 게시자(미보유·stale만) + 댓글(게이트). 기지 in-window 게시물 → 스냅샷 upsert만(공짜 데이터 — 설계 결정 2).
  - `void track(BrandRow brand)` — 트래킹(=등록 백필): 105개 깊이 열거(감지 겸함) + 전 in-window 게시물 스냅샷 + 댓글 게이트 + 게시자 stale 갱신.
- 생성자:

```java
public BrandCollectService(HikerClient hiker, SnapshotWriter writer, SnapshotRepository snapshots,
		CommentRepository comments, TaggedPostRepository taggedPosts, AuthorProfileRepository authors,
		@Value("${monitoring.brand.window-days:90}") int windowDays,
		@Value("${monitoring.brand.window-posts:105}") int windowPosts,
		@Value("${monitoring.brand.comment-pages:3}") int commentPages,
		@Value("${monitoring.brand.author-stale-days:30}") int authorStaleDays)
```

**핵심 로직(구현 지침):**

```java
/** 감지(매일) — 1페이지 1콜 고정(스펙 §3, 비용 모델 월 30콜 정합). 놓친 심층분은 트래킹이 잡는다. */
public void detect(BrandRow brand) {
	HikerClient.TaggedPage page = hiker.fetchTaggedPage(brand.igUserId(), null);
	process(brand, dedupeByCode(page.posts()), false);
}

/**
 * 트래킹(3일 1회)·등록 백필 공용 — 목표 개수(windowPosts=105)까지 next_page_id 추종.
 * 페이지당 건수(실측 21)는 IG 소관 값이라 가정하지 않는다(스펙 §6). 이날 감지는 이 열거가
 * 겸한다(추가 콜 없음). 중단: ①누적 unique code ≥ windowPosts ②페이지 전체가 90일 컷 이전
 * (소급 태그 혼입 때문에 "오래된 글 1건 발견 즉시 중단" 금지 — 스펙 §5) ③커서 소진
 * ④커서 미전진(신규 code 0건 — fetchRecentPosts 가드 관용구).
 */
public void track(BrandRow brand) {
	Instant cutoff = Instant.now().minus(Duration.ofDays(windowDays));
	Map<String, PostInfo> byCode = new LinkedHashMap<>();
	String cursor = null;
	while (true) {
		HikerClient.TaggedPage page = hiker.fetchTaggedPage(brand.igUserId(), cursor);
		if (page.posts().isEmpty()) {
			break;
		}
		int before = byCode.size();
		page.posts().forEach(p -> byCode.putIfAbsent(p.shortCode(), p));
		boolean wholePageBeforeCutoff = page.posts().stream()
				.allMatch(p -> p.takenAt() != null
						&& Instant.ofEpochSecond(p.takenAt()).isBefore(cutoff));
		if (byCode.size() >= windowPosts || wholePageBeforeCutoff
				|| page.nextPageId() == null || byCode.size() == before) {
			break;
		}
		cursor = page.nextPageId();
	}
	process(brand, List.copyOf(byCode.values()), true);
}
```

`process(BrandRow brand, List<PostInfo> posts, boolean tracking)`:

```java
1. inWindow = posts.stream().filter(p -> p.takenAt() != null
       && !Instant.ofEpochSecond(p.takenAt()).isBefore(cutoff)).toList()
   // taken_at 미상은 보수적으로 제외(postedAfterRegistration 관용구) — 다음 열거에서 채워지면 잡힌다.
2. known = taggedPosts.knownCodes(brand.id())
   fresh = inWindow에서 code ∉ known
3. adjusted = adjustLotteryMetrics(inWindow)   // ①부재=0 ②0 캐리 — 아래 참조
4. for (PostInfo p : adjusted) writer.savePost(today, p)   // 스냅샷+post_meta+profile_meta 깔때기
5. for (PostInfo p : fresh의 adjusted 대응분) taggedPosts.insert(brand.id(), p)
6. ensureAuthors(tracking ? adjusted : fresh의 adjusted 대응분)   // 미보유·30일 stale만 콜, 게시자 단위 격리
7. collectCommentsGated(brand.id(), tracking ? adjusted : fresh의 adjusted 대응분)   // 게시물 단위 격리
```

주의(3~5·7의 대응분): `adjustLotteryMetrics`가 PostInfo 사본을 만들므로 fresh 판정은 shortCode 기준 Set으로 유지하고 adjusted 리스트에서 필터한다.

`adjustLotteryMetrics(List<PostInfo> posts)` — 재시도 없는 태그 경로의 복권 3종 적재 규칙(설계 결정 3):

```java
// ① 부재=0(DECISIONS 08-05): 릴스에서 saves 관측 ∧ reposts 부재 → reposts 0
//    (save·repost 키 동반 실림 566/596 — 당첨 세션 근거가 있을 때만 "부재=생략" 해석 성립).
//    saves 관측 ∧ shares 부재 ∧ !sharesHidden → shares 0(숨김은 0이 아니라 비공개 — null 유지).
//    캠페인 모듈은 재시도 소진 시점에 적용하지만 태그 경로는 재시도가 없어 저장 전에 즉시 적용.
// ② 0 캐리(DECISIONS 08-05): ① 후에도 null인 릴스 reposts/shares(숨김 제외)는
//    SnapshotRepository.codesWithRepostsZeroCarry/SharesZeroCarry(오늘 기준)로 이력 판정 —
//    양수 관측 전무 ∧ 전일 0 종료면 0으로 잇는다(구조적 부재 게시물의 null 구멍 방지).
//    실제 값이 생기면 키가 오기 시작해 자동 해제(양수 관측이 이력 판정을 이긴다).
// ③ 전부 꽝 세션(saves도 부재)은 근거가 없으므로 ①을 적용하지 않는다 — null(미관측) 유지.
//    fb 캐리포워드·역전파는 SnapshotRepository.upsertPost가 이미 처리하므로 여기서 손대지 않는다.
List<PostInfo> adjustLotteryMetrics(List<PostInfo> posts) {
	// 1) ① 적용: p.mergedMetrics(null, zeroShares, zeroReposts)
	// 2) 남은 null 후보 code 수집 → codesWith*ZeroCarry 배치 2쿼리 → ② 적용(mergedMetrics)
}
```

`ensureAuthors(Collection<PostInfo> posts)`:

```java
// 처리 대상 게시물의 작성자 pk(ownerUserId non-null) 중복 제거 →
// authors.freshIgUserIds(ids, now - authorStaleDays일) 차집합만 fetchAuthorProfile → upsert.
// 게시자 단위 try/catch 격리(한 명 실패가 나머지·게시물 수집에 번지면 안 된다 — 로그만).
// ownerUserId가 null인 게시물(구형 셰이프)은 건너뛴다(콜 공급원이 없다).
```

`collectCommentsGated(long brandId, Collection<PostInfo> posts)`:

```java
// counts = taggedPosts.commentsCollectedCounts(brandId, codes) — 배치 1쿼리.
// 게시물별: p.comments() != null && p.comments() > counts.getOrDefault(code, 0L)일 때만
//   knownIds = comments.findIds(code)
//   fetched = hiker.fetchComments(code, p.username(), commentPages, knownIds)   // 최대 3콜 45개
//   comments.upsertForPost(code, fetched)
//   taggedPosts.updateCommentsCollected(brandId, code, p.comments())
// 게시물 단위 try/catch 격리. 댓글 숨김·0건 게시물은 게이트가 자연 차단(저장값 0, comment_count 0).
```

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandCollectServiceTest.java` — CollectServiceTest 관용구(fake HikerHttp 람다 + 스텁 서브클래스, DB 없음). 스텁: `SnapshotWriter`(savePost 기록만), `SnapshotRepository`(codesWith* 고정 반환), `CommentRepository`(upsert no-op·findIds 고정), `TaggedPostRepository`(인메모리 Map), `AuthorProfileRepository`(인메모리 Map) — 각각 `super(null)` 서브클래스. 테스트 케이스(각각 인라인 JSON 페이지 빌더 헬퍼 사용 — `tagPage(nextPageId, item...)`):

```java
@Test void 감지는_정확히_1콜만_낸다()
	// 3페이지어치 커서가 있어도 fetchTaggedPage 1콜 — calls 리스트 크기 1 검증.

@Test void 감지는_페이지_중간_기지_code_뒤의_소급_태그_신규를_놓치지_않는다()
	// 페이지: [기지A, 신규B(소급 태그 — taken_at 옛날이지만 90일 이내)] → B가 insert됨.

@Test void 윈도우_밖_게시물은_적재하지_않는다()
	// taken_at 91일 전 게시물 → savePost·insert 안 됨. taken_at null도 제외.

@Test void 트래킹은_목표_개수까지_커서를_추종하고_페이지_전체가_컷_이전이면_중단한다()
	// windowPosts=3으로 생성. 페이지1(2건 최신)+페이지2(2건 최신) → 3개 도달 시점에 중단(2콜).
	// 별도 케이스: 페이지2가 전부 91일 전 → 커서가 남아도 중단(2콜), 페이지1 분만 처리.

@Test void 트래킹은_기지_게시물_스냅샷을_갱신하고_신규는_감지한다()
	// 기지A+신규B 페이지 → savePost 2건, insert는 B만.

@Test void 부재_0_간주는_saves_관측시에만_적용한다()
	// 릴스: save_count=10만 실림(reshare·repost 부재, 숨김 아님) → 저장된 PostInfo의
	// shares=0·reposts=0. 대조: 전부 꽝(saves도 부재) → 셋 다 null 유지.
	// 대조2: sharesHidden 릴스 → shares는 null 유지, reposts만 0.

@Test void 잔여_null은_0_캐리_이력으로_잇는다()
	// 전부 꽝 릴스 + SnapshotRepository 스텁이 codesWithRepostsZeroCarry에 그 code 반환
	// → reposts=0으로 저장, shares는 스텁 미반환이라 null.

@Test void 게시자는_미보유이거나_30일_경과만_콜한다()
	// 작성자 3명: 캐시 신선 1(콜 0)·stale 1(콜 1)·미보유 1(콜 1) → /v2/user/by/id 2콜.
	// 게시자 1명 콜 실패 → 나머지 게시자·게시물 수집은 계속(격리).

@Test void 감지일_게시자_콜은_신규_게시물_작성자만_본다()
	// 기지 게시물 작성자가 stale이어도 감지일엔 콜 0 — 트래킹일에만 stale 갱신(스펙 §8).

@Test void 댓글은_comment_count가_저장값보다_클_때만_콜한다()
	// 신규(저장값 0) comment_count=2 → 댓글 콜 O + updateCommentsCollected(2).
	// 기지 저장값 5, comment_count=5 → 콜 X. comment_count=7 → 콜 O.
	// comment_count=0 신규 → 콜 X(댓글 없는 게시물 — 스펙 §2 게이팅 자동 성립).

@Test void 태그_0건_계정은_아무것도_하지_않는다()
	// fetchTaggedPage가 빈 페이지(404 삼킴) → savePost·insert·게시자·댓글 콜 전부 0.
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"
```
Expected: 컴파일 실패.

- [ ] **Step 3: 구현** — 위 구현 지침대로 `BrandCollectService.java` 작성. 클래스 javadoc에 명시할 것: 트랜잭션 없음(CollectService와 같은 이유 — 쓰기는 SnapshotWriter·리포지토리가 짧게 묶는다), 단건 게시물 콜 금지(열거 단일 경로 — 스펙 §1), 감지 1콜 고정의 근거(비용 모델 정합 — "페이지 전체 기지 중단" 규칙은 페이지 **내** 조기 중단 금지의 뜻으로 새긴다: 심층 소급 태그는 3일 트래킹 105개 깊이가 잡는다).

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"
```
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A monitoring/src
git commit -m "feat(monitoring): 브랜드 태그 수집 본체 — 감지 1콜·트래킹 105개 추종·부재=0·0 캐리·게시자 stale·댓글 게이트"
```

---

### Task 7: BrandSweepJob + 스케줄러

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepScheduler.java`
- Modify: `monitoring/src/main/resources/application.yml`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java`

**Interfaces:**
- Consumes: `BrandRepository.findActive/touchTracked`, `BrandCollectService.detect/track`
- Produces: `BrandSweepJob.run()` — 활성 브랜드 순회: 트래킹 도래(`lastTrackedOn == null || today ≥ lastTrackedOn + intervalDays`)면 `track()` 후 `touchTracked(today)`, 아니면 `detect()`. 브랜드 단위 try/catch 격리(재시도 라운드 없음 — 실패 브랜드는 다음날 백스톱, track 실패는 touchTracked를 안 하므로 다음날 다시 트래킹).

```java
@Service
public class BrandSweepJob {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	public BrandSweepJob(BrandRepository brands, BrandCollectService collect,
			@Value("${monitoring.brand.tracking-interval-days:3}") int trackingIntervalDays) { ... }

	public void run() {
		LocalDate today = LocalDate.now(KST);
		List<BrandRow> active = brands.findActive();
		int failures = 0;
		for (BrandRow b : active) {
			try {
				if (trackingDue(b, today)) {
					collect.track(b);
					brands.touchTracked(b.id(), today);   // 성공 시에만 — 실패 브랜드는 내일 다시 트래킹
				} else {
					collect.detect(b);
				}
			} catch (RuntimeException e) {
				// 계정 삭제·비공개 전환(SubjectNotFound·PrivateAccount)도 여기로 온다 — 브랜드는
				// 탈퇴까지 추적이 정본(스펙 §8)이라 상태 전이 없이 다음날 재시도한다(캠페인 hidden
				// 전이와 다른 점). 태그 열거 404는 fetchTaggedPage가 이미 "태그 0건"으로 삼킨다.
				failures++;
				log.warn("브랜드 스윕 실패(격리) — {}: {}", b.username(), e.toString());
			}
		}
		log.info("브랜드 태그 스윕 완료 — 브랜드 {}건 중 실패 {}건", active.size(), failures);
	}

	private boolean trackingDue(BrandRow b, LocalDate today) {
		return b.lastTrackedOn() == null
				|| !today.isBefore(b.lastTrackedOn().plusDays(trackingIntervalDays));
	}
}
```

- `BrandSweepScheduler` — SweepScheduler 관용구, 전용 크론 키 + 자체 가드(수동 트리거가 없으므로 공유 SweepGuard 대신 내부 AtomicBoolean):

```java
@Component
public class BrandSweepScheduler {

	private final AtomicBoolean running = new AtomicBoolean(false);

	@Scheduled(cron = "${monitoring.brand.schedule.sweep-cron:-}", zone = "UTC")
	public void sweep() {
		if (!running.compareAndSet(false, true)) {
			log.warn("브랜드 스윕 스킵 — 이미 실행 중");
			return;
		}
		try {
			job.run();
		} finally {
			running.set(false);
		}
	}
}
```

- `application.yml` 추가(monitoring 블록 안):

```yaml
  brand:
    schedule:
      sweep-cron: "-"   # "-"=비활성. 운영은 캠페인 스윕(KST 02:00)과 겹치지 않게 UTC 18:00(KST 03:00) env 주입
    tracking-interval-days: 3   # 트래킹 주기(스펙 §3) — 트래킹일엔 감지 겸함(추가 콜 없음)
    window-days: 90             # 슬라이딩 윈도우(스펙 §4) — 백필·추적 동일 기준
    window-posts: 105           # 열거 깊이(실측 5페이지) — 백필=트래킹 깊이 정합으로 "백필분 전부 계속 갱신" 보장
    comment-pages: 3            # 게시물당 댓글 상한 3콜 45개(스펙 §2)
    author-stale-days: 30       # 게시자 프로필 등장 시 stale 갱신 기준(스펙 §8)
```

- [ ] **Step 1: 실패하는 테스트 작성** — `BrandSweepJobTest.java`: 스텁 BrandRepository(인메모리)·스텁 BrandCollectService(호출 기록: `super(null, ...)` 서브클래스 또는 detect/track 오버라이드). 케이스:

```java
@Test void 트래킹_도래_판정_3일_주기()
	// lastTrackedOn null → track. 2일 전 → detect. 3일 전 → track. today → detect.

@Test void 트래킹_성공시에만_last_tracked_on을_갱신한다()
	// track 정상 → touchTracked 호출됨. track 예외 → touchTracked 미호출 + 다음 브랜드 계속.

@Test void 브랜드_실패는_격리된다()
	// 브랜드 3개 중 2번째 detect가 예외 → 1·3번째는 정상 처리.
```

- [ ] **Step 2: 실패 확인** — `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandSweepJobTest"` → 컴파일 실패.
- [ ] **Step 3: 구현** — 위 코드 그대로.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS. 부트 검증: `./gradlew :monitoring:compileJava` PASS(크론 기본 `-` 비활성이라 스케줄러는 무해).
- [ ] **Step 5: 커밋**

```bash
git add -A monitoring/src
git commit -m "feat(monitoring): 브랜드 스윕 잡·스케줄러 — 3일 트래킹 주기, 브랜드 단위 격리, 실패시 다음날 백스톱"
```

---

### Task 8: 등록/탈퇴 — BrandRegistrationService + BrandController + 백필 executor

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java`, `monitoring/src/test/java/com/celfit/monitoring/web/BrandControllerTest.java`

**Interfaces:**
- Consumes: `HikerClient.fetchProfile`(존재·공개 검증 + pk·팔로워·biography), `BrandRepository`, `BrandCollectService.track`
- Produces:
  - `BrandBackfillConfig` — `@Bean(name = "brandBackfillExecutor")` 단일 스레드 데몬 executor(MetricsBackfillConfig 관용구 — 캠페인 metricsBackfillExecutor와 **분리**: 백필 1건이 수십 초~분 단위 콜 체인이라 공유하면 캠페인 등록 백필이 밀린다)
  - `BrandRegistrationService.Result(long brandId, String username, Long followers, boolean replayed)`
  - `BrandRegistrationService.register(String username)` → Result — ACTIVE 기존 행이면 replay(Hiker 콜 0), 아니면 프로필 1콜 동기(존재·공개 검증) → insertOrReactivate → **백필은 executor에서 비동기**(`collect.track(row)` + 성공 시 `touchTracked`; 실패는 로그 — last_tracked_on null 유지로 다음 스윕 백스톱)
  - `BrandRegistrationService.deregister(String username)` → boolean (BrandRepository.close 위임)
  - `BrandController` — `POST /api/brands` body `{"username": "..."}` → 201(신규)/200(replay), body `{brandId, username, followers, status:"ACTIVE"}`; `DELETE /api/brands/{username}` → 204(전이 성공 또는 이미 CLOSED), 404(미등록 username). username blank → 기존 `ValidationException`(ApiExceptionHandler가 400 매핑). 계정 미존재·비공개는 fetchProfile 예외가 기존 ApiExceptionHandler 매핑을 그대로 탄다(등록 전 검증 실패 — brand_account 행을 만들지 않는다).

```java
@Service
public class BrandRegistrationService {

	public record Result(long brandId, String username, Long followers, boolean replayed) {}

	public BrandRegistrationService(HikerClient hiker, BrandRepository brands,
			BrandCollectService collect,
			@Qualifier("brandBackfillExecutor") Executor backfill) { ... }

	/**
	 * 가입 = 추적 자동 시작(스펙 §1). 동기 구간은 프로필 1콜뿐 — 백필(~160콜, 수 분)은 was 동기
	 * 예산(10초) 밖 전용 executor에서 돈다. 백필 실패·앱 재시작으로 끊겨도 last_tracked_on이
	 * null로 남아 다음 스윕이 트래킹(=같은 깊이)으로 백스톱한다(설계 결정 4).
	 */
	public Result register(String username) {
		if (username == null || username.isBlank()) {
			throw new ValidationException("username은 필수다");
		}
		String normalized = username.strip();
		var existing = brands.findByUsername(normalized);
		if (existing.isPresent() && existing.get().status() == BrandStatus.ACTIVE) {
			return new Result(existing.get().id(), normalized, null, true);   // 멱등 replay — Hiker 콜 0
		}
		ProfileInfo profile = hiker.fetchProfile(normalized);   // 존재·공개 검증 + pk·팔로워·bio
		long id = brands.insertOrReactivate(normalized, profile.userId(), profile.followers(),
				profile.biography());
		BrandRow row = brands.findByUsername(normalized).orElseThrow();
		backfill.execute(() -> runBackfillSafely(row));
		return new Result(id, normalized, profile.followers(), false);
	}

	private void runBackfillSafely(BrandRow row) {
		try {
			collect.track(row);
			brands.touchTracked(row.id(), LocalDate.now(KST));
		} catch (RuntimeException e) {
			log.warn("브랜드 등록 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
		}
	}

	public boolean deregister(String username) {
		return brands.close(username);
	}
}
```

`BrandController`(TargetController·ApiError 관용구):

```java
@RestController
@RequestMapping("/api/brands")
public class BrandController {

	public record BrandRegisterRequest(String username) {}
	public record BrandRegisterResponse(long brandId, String username, Long followers, String status) {}

	@PostMapping
	public ResponseEntity<BrandRegisterResponse> register(@RequestBody BrandRegisterRequest req) {
		var result = service.register(req.username());
		return ResponseEntity.status(result.replayed() ? 200 : 201)
				.body(new BrandRegisterResponse(result.brandId(), result.username(),
						result.followers(), "ACTIVE"));
	}

	@DeleteMapping("/{username}")
	public ResponseEntity<Void> deregister(@PathVariable String username) {
		if (service.deregister(username)) {
			return ResponseEntity.noContent().build();
		}
		// 이미 CLOSED면 멱등 204, 아예 미등록이면 404 — was 재시도가 안전해야 한다.
		return brands.findByUsername(username).isPresent()
				? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}
}
```

(deregister 분기의 findByUsername은 컨트롤러에 BrandRepository를 직접 주입하지 말고 `BrandRegistrationService`에 `enum DeregisterOutcome { CLOSED, ALREADY_CLOSED, NOT_FOUND }`를 반환하는 메서드로 승격해도 좋다 — 구현 시 택1, 서비스 반환형 승격 권장.)

- [ ] **Step 1: 실패하는 테스트 작성** — `BrandRegistrationServiceTest`(fake HikerHttp + 인메모리 스텁 BrandRepository + 동기 executor `Runnable::run` — 백필 즉시 실행으로 검증):

```java
@Test void 등록은_프로필_1콜_동기_후_백필을_예약한다()
	// 프로필 픽스처 → register → Result(replayed=false, followers 양수),
	// 동기 executor라 track이 이미 호출됨(스텁 collect 기록) + touchTracked 호출됨.

@Test void 활성_브랜드_재등록은_replay다()
	// 등록 2회 → 2번째는 Hiker 콜 0(calls 크기 불변)·replayed=true.

@Test void 백필_실패는_등록을_실패시키지_않는다()
	// 스텁 collect.track이 예외 → register는 정상 Result, touchTracked 미호출(백스톱 성립).

@Test void 탈퇴는_close_위임이다()

@Test void username_공백은_ValidationException()
```

`BrandControllerTest` — 기존 `CommandApiTest`/`SweepControllerTest`의 웹 계층 테스트 관용구(@WebMvcTest — Spring Boot 4는 `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`)를 따라: 201/200/204/404/400 상태 코드와 응답 body 검증(서비스는 스텁 빈).

- [ ] **Step 2: 실패 확인** — 컴파일 실패 확인.
- [ ] **Step 3: 구현** — 위 코드 + `BrandBackfillConfig`(MetricsBackfillConfig 복제, 빈 이름 `brandBackfillExecutor`, 스레드명 `brand-backfill`).
- [ ] **Step 4: 통과 확인**

```bash
./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest" --tests "com.celfit.monitoring.web.BrandControllerTest"
```
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A monitoring/src
git commit -m "feat(monitoring): 브랜드 등록·탈퇴 API — 동기 프로필 1콜 + 비동기 백필(전용 executor), 멱등 replay"
```

---

### Task 9: 마무리 — 모듈 전체 테스트·문서·PR

**Files:**
- Modify: `DECISIONS.md` (맨 위 행 추가)
- Modify: `docs/superpowers/specs/2026-08-06-brand-tag-monitoring-schedule-design.md` (상태 헤더만 `🟢 활성 (설계 확정 2026-08-06 · 구현 전)` → `✅ 구현됨(2026-08-06)` — 본문 불변)
- Modify: `docs/tracks/` 해당 monitoring 트랙 파일(`ls docs/tracks/`로 확인, 브랜드 태그 모니터링 항목 추가)
- Modify: 이 계획 문서 상태 헤더 → `✅ 실행됨` 후 `docs/superpowers/plans/archive/`로 이동

- [ ] **Step 1: 모듈 전체 테스트**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :monitoring:test
```
Expected: 전건 PASS. 실패 시 superpowers:systematic-debugging으로 원인 규명 후 수정(테스트 삭제·완화 금지).

- [ ] **Step 2: DECISIONS.md 맨 위 행 추가** — 요지: 브랜드 태그 모니터링 수집 파이프라인 구현(스펙 2026-08-06 그대로). 구조 결정 기록: ①스냅샷·메타·댓글은 기존 post_snapshot·post_meta·post_comment·profile_meta 재사용(SnapshotWriter 깔때기 — METRICS_HIDDEN 알람은 캠페인 owner 없는 code에서 자연 no-op) ②감지는 1페이지 1콜 고정(비용 모델 정합 — "페이지 전체 기지 중단"은 페이지 내 조기 중단 금지로 해석, 심층 소급 태그는 3일 트래킹이 잡음) ③부재=0은 재시도가 없어 저장 시점 즉시 적용 ④등록 백필=track() 코드 공유, 실패 시 last_tracked_on null 백스톱 ⑤복권 3종 재시도 콜 없음(비용 모델에 예산 없음). 링크: 스펙·이 계획 문서.

- [ ] **Step 3: 트랙 문서 갱신** — `ls docs/tracks/`에서 monitoring 트랙 파일을 찾아 브랜드 태그 모니터링 구현 완료 상태를 추가(파일이 없으면 CLAUDE.md §5 규칙대로 신규 트랙 파일 생성).

- [ ] **Step 4: 스펙·계획 상태 헤더 갱신 + 계획 아카이브 이동**

```bash
git mv docs/superpowers/plans/2026-08-06-brand-tag-monitoring-impl.md docs/superpowers/plans/archive/
```

- [ ] **Step 5: 커밋 + PR**

```bash
git add -A
git commit -m "docs: 브랜드 태그 모니터링 구현 반영 — DECISIONS·트랙·스펙 상태 갱신"
```

PR은 develop 대상 draft로 즉시 연다(세션 위생 — gh 부재 시 git credential+curl API 경로, 메모리 참조). PR 본문에 스펙 링크·구현 범위(수집만, was API·FE 계약 제외)·검증 결과(:monitoring:test 전건)를 명시.

---

## Self-Review 결과 (계획 작성 시 수행)

- **스펙 커버리지**: §1 가입 자동 시작~탈퇴(Task 8 register/deregister·§5 CLOSED) ✓ / §2 수집 대상 4종(프로필 Task 8, 태그 열거 Task 3·6, 댓글 게이팅 Task 4·6, 게시자 stale Task 2·5·6) ✓ / §3 주기(감지 매일·트래킹 3일·겸함 Task 6·7) ✓ / §4 윈도우 90일&105개(Task 6) ✓ / §5 백필 절차·페이지 전체 컷 중단(Task 6 track·Task 8 백필) ✓ / §6 함정 3종(하드코딩 금지·페이지 단위 중단·캐리포워드/0 캐리 재사용 Task 6) ✓ / §8 후속 확정 3종(stale 갱신·복권 DB 적재 FE 미노출(서빙 계약 자체가 범위 밖)·휴면 완화 없음) ✓
- **해석 확정 1건**: "감지 중단 = 페이지 전체 기지"를 다중 페이지 감지 규칙이 아니라 **페이지 내 조기 중단 금지**로 해석(스펙 §3 "매일 1페이지 1콜"·§7 비용 모델 감지 월 30콜과의 정합 우선). Task 6 javadoc·DECISIONS 행에 근거 명기.
- **타입 일관성**: BrandRow·AuthorInfo·TaggedPage 시그니처를 태스크 간 Interfaces 블록으로 전달, PostInfo 생성자 20-인자 순서는 Task 5 테스트 헬퍼에 명시 ✓
- **미결 없음**: `/v2/user/by/id` 셰이프 미실측은 가정 명기(Task 2) — 운영 첫 콜에서 셰이프가 다르면 HikerFetchException으로 표면화되고 게시자 단위 격리라 수집 본체는 계속 돈다.
