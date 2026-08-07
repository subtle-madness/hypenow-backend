# 브랜드 모니터링 was API 구현 계획

> 상태: ✅ 실행됨 (2026-08-07 — Task 1~11 전부 구현·PR #354 머지·운영 배포. 급행 승격으로 Task 12의
> 전체 테스트·Task 11 리뷰는 사후 수행 — 전체 스위트 green(34de6760), Task 11 사후 리뷰·문서 갱신은
> 08-07 잔여작업 세션에서 완료. 편차·후속은 DECISIONS 08-07 행 참조)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FE 브랜드뷰 명세(브랜드 모니터링 v1 · 성과 대시보드 v1 · 캠페인 확장 v2)를 was에 구현한다 — 레거시 `/v1/monitoring/**`는 완전 동결, 전부 추가.

**Architecture:** monitoring은 08-06 브랜드 전용 테이블 3개에 nullable 컬럼 추가 + 이미 받는 Hiker 응답의 추가 파싱만(API 콜 증가 0). was는 신규 패키지 3개(`v1/brandmonitoring`·`v1/perfdashboard`·`v2/monitoring`) + seam 확장(`BrandReadRepository`·커맨드 메서드 2개). 레거시 아이템 조립은 `TrackingItemAssembler`를 호출자로 재사용한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient(was 조회)/JdbcTemplate(monitoring), Flyway, Testcontainers(PostgreSQL), Jackson 3(`tools.jackson.*`), record DTO.

**정본 스펙:** [specs/2026-08-07-brand-monitoring-was-api-design.md](../specs/2026-08-07-brand-monitoring-was-api-design.md) (§ 번호 인용은 이 문서 기준. "FE §"는 FE 명세 기준)

## Global Constraints

- **레거시 동결**: 기존 `/v1/monitoring/**` 컨트롤러·서비스·DTO·응답은 0줄 변경. 기존 monitoring 캠페인 파이프라인(`target`·`post_snapshot` 계열) 0줄 변경. 수정 허용 파일은 각 태스크 Files에 명시된 것뿐.
- **envelope**: 모든 응답은 `ApiResponse`(success/data/error/meta). nullable 필드는 키 생략 없이 명시적 null(record 기본 — `@JsonInclude` 금지, meta 제외).
- **username 정규화**: `@` 제거·trim·소문자. 허용 정규식 `^[a-z0-9._]{1,30}$`(정규화 후), `..` 금지.
- **타임스탬프**: `KstTimestamps.toKstIso()`로 `+09:00` 오프셋 ISO 8601. 날짜는 `YYYY-MM-DD`. 스냅샷은 날짜 오름차순.
- **ID**: 전부 JSON string. `BrandAccount.id`=brand_account.id 문자열, `BrandPost.id`=`canonicalPostId`=shortcode.
- **마이그레이션**: UTC 타임스탬프 채번(`V<YYYYMMDDHHMMSS>__`), nullable ADD COLUMN만(expand). 기존 `V1~V49` rename 금지.
- **주석·로그·커밋 한국어**. 커밋 prefix `feat(monitoring):`/`feat(was):`/`docs:`.
- **테스트**: 모듈 단위(`./gradlew :monitoring:test` / `:was:test`), 실행 전 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 필수. 전체 `./gradlew test`는 PR 직전에만.
- **grep 시 `--exclude-dir=docs`** (한글 문서 3.9MB가 코드 히트를 묻는다).
- 에러 메시지는 FE가 그대로 노출 가능한 한국어 문장.

## 설계 결정(스펙에서 확정된 것 — 재논의 금지)

1. 등록은 **monitoring 동기 검증 → was 커밋** 순서(FE §5.2 순서와 의도적으로 다름 — 오타 영구 저장 차단).
2. `lastDetectedAt`=`lastTrackedAt`=`last_swept_at` 동일값.
3. 직접 등록은 **레거시 등록 파이프라인 위임** + `app.brand_direct_posts` 매핑.
4. 캠페인 관계는 **1:1 유지**(`monitoring_items.campaign_id`) — 다른 캠페인 소속이면 entry `duplicate`.
5. 협찬 판정은 조회 시 계산(저장 안 함): paid true→sponsored / 캡션 키워드→sponsored / paid false→organic / paid null→unknown.
6. 스냅샷 병합(대시보드 겹침): 날짜별·지표별 non-null 우선, 둘 다 값이면 브랜드 값.
7. 대시보드 대표 source 우선순위 individual > direct > tagged. tagged-only 합성 아이템 id는 `"bt_"+shortcode`.

## File Structure

| 파일 | 책임 |
|---|---|
| **monitoring** | |
| `monitoring/src/main/resources/db/migration/V20260807130000__brand_was_contract_fields.sql` | 브랜드 3테이블 컬럼 확장(expand) |
| `hiker/ProfileInfo.java` (수정) | +isVerified·externalUrl |
| `hiker/AuthorInfo.java` (수정) | +isVerified |
| `hiker/PostInfo.java` (수정) | +videoUrl·videoDuration·isPaidPartnership |
| `hiker/HikerClient.java` (수정) | 위 필드 파싱 추가 |
| `store/BrandRepository.java` (수정) | 프로필 전필드 저장·touchSwept 확장·markBackfillError |
| `store/BrandPostMetaRepository.java` (수정) | video·paid 컬럼 upsert |
| `store/AuthorProfileRepository.java` (수정) | is_verified upsert |
| `service/BrandSnapshotWriter.java` (수정) | 확장 필드 전달 |
| `service/BrandRegistrationService.java` (수정) | 백필 실패 시 markBackfillError |
| **was — app·seam** | |
| `was/src/main/resources/db/migration/app/V20260807130500__brand_monitoring_links.sql` | users 컬럼 + 연결·direct 매핑 테이블 |
| `was/monitoring/BrandLinkRepository.java` (신규) | app 연결 테이블 + users 컬럼 접점 |
| `was/monitoring/BrandDirectPostRepository.java` (신규) | app direct 매핑 접점 |
| `was/monitoring/MonitoringCommandClient.java` (수정) | registerBrand·deregisterBrand |
| `was/monitoring/BrandReadRepository.java` (신규) | monitoring 브랜드 테이블 읽기 전용 배치 조회 |
| `was/monitoring/MonitoringConfig.java` (수정) | BrandReadRepository 빈 추가 |
| **was — v1/brandmonitoring** (전부 신규) | |
| `V1BrandAccountsController.java` | accounts 목록·단건·등록·삭제 |
| `V1BrandAccountService.java` | 등록·삭제 플로우(§5-1·§5-3) |
| `BrandAccountAssembler.java` | BrandAccountResponse 조립 + 상태 유도(§5-2) |
| `BrandAccountResponse.java` / `BrandProfileResponse.java` | DTO |
| `V1BrandPostsController.java` | posts 목록·상세·direct-posts·direct-registrations |
| `BrandPostAssembler.java` | BrandPostResponse 조립(tagged+direct 합성) |
| `BrandPostResponse.java` | DTO(스냅샷·댓글은 레거시 `TrackingItemResponse.SnapshotResponse`·`PostCommentResponse` 재사용) |
| `BrandSponsorshipClassifier.java` | 협찬 판정 순수 함수 |
| `V1BrandDirectPostService.java` | 직접 등록 위임 + 매핑 |
| `BrandDirectRegistrationResponse.java` | DTO |
| **was — v1/perfdashboard** (전부 신규) | |
| `V1PerformanceDashboardController.java` | contents 목록·단건 |
| `PerformanceContentAssembler.java` | 3계열 통합·중복 제거·스냅샷 병합 |
| `PerformanceContentResponse.java` / `PerformanceItemResponse.java` / `PerformancePostResponse.java` | DTO |
| **was — v2/monitoring** (전부 신규) | |
| `V2CampaignContentsController.java` / `V2CampaignContentService.java` | 캠페인 콘텐츠 추가·제거 |

---

### Task 1: monitoring 파싱 확장 + 마이그레이션

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V20260807130000__brand_was_contract_fields.sql`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/ProfileInfo.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/AuthorInfo.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/PostInfo.java` (record 필드 3개 추가 — `withFbPlays`·`mergedMetrics`·`mergedWith` 사본 생성자 전부 갱신 필수)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/hiker/HikerClientTest.java` (수정)
- Test: `monitoring/src/test/java/com/celfit/monitoring/MigrationTest.java` (수정 — 신규 컬럼 존재 검증 패턴이 이미 있으면 따름)

**Interfaces:**
- Produces: `ProfileInfo(..., String biography, Boolean isVerified, String externalUrl, String rawJson)` — biography 뒤·rawJson 앞에 2필드 삽입.
- Produces: `AuthorInfo(..., boolean isPrivate, Boolean isVerified)` — 말미 추가.
- Produces: `PostInfo(..., Long reposts, String videoUrl, Double videoDuration, Boolean isPaidPartnership, String rawJson, ...)` — reposts 뒤·rawJson 앞에 3필드 삽입.
- 이후 태스크(2·4)는 이 필드 순서를 그대로 소비한다.

- [x] **Step 1: 마이그레이션 작성**

```sql
-- 브랜드 was 계약 필드(2026-08-07 스펙 §3-2) — expand 단계, 전부 nullable ADD.
-- 대상은 08-06 브랜드 전용 테이블만 — 기존 캠페인 테이블 무접촉.
ALTER TABLE brand_account
    ADD COLUMN full_name             text,        -- 프로필 콜 관측 최신값(BrandProfile.fullName)
    ADD COLUMN profile_pic_url       text,
    ADD COLUMN is_verified           boolean,
    ADD COLUMN external_url          text,
    ADD COLUMN following             bigint,      -- 최신값(추이는 brand_profile_snapshot)
    ADD COLUMN media_count           bigint,
    ADD COLUMN backfill_error        text,        -- 초기 백필 실패 기록 — 스윕 성공 시 클리어(§5-2)
    ADD COLUMN backfill_completed_at timestamptz, -- 최초 완주 시각(collectionCompletedAt)
    ADD COLUMN last_swept_at         timestamptz; -- lastDetectedAt·lastTrackedAt 공급(시각 — last_swept_on은 날짜)

ALTER TABLE brand_post_meta
    ADD COLUMN video_url           text,
    ADD COLUMN video_duration      double precision,
    ADD COLUMN is_paid_partnership boolean;      -- null=키 부재(판정 unknown 근거)

ALTER TABLE author_profile
    ADD COLUMN is_verified boolean;
```

- [x] **Step 2: 픽스처 실값 확인** — 파싱 기대값을 픽스처 실측으로 고정한다.

Run: `python3 -c "import json,glob; d=json.load(open('monitoring/src/test/resources/hiker/profile.json')); u=d.get('user',d); print({k:u.get(k) for k in ['is_verified','external_url']})"`
Run: `python3 -c "import json; d=json.load(open('monitoring/src/test/resources/hiker/medias.json')); items=d.get('response',d).get('items',[]); m=items[0].get('media',items[0]); print({k:m.get(k) for k in ['video_duration','is_paid_partnership']}, bool(m.get('video_versions')))"`

키가 픽스처에 없으면(부재) 해당 필드 기대값은 null이다 — 아래 테스트의 기대 상수를 실측값으로 맞춘다.

- [x] **Step 3: 실패 테스트 작성** — `HikerClientTest`에 추가(기존 fakeHttp 관용구 그대로)

```java
@Test
void 프로필_파싱은_인증뱃지와_외부링크를_담는다() {
	HikerClient client = new HikerClient(fakeHttp());
	ProfileInfo p = client.fetchProfile("rarebeauty");
	// Step 2 실측값으로 기대 고정 — 키 부재 픽스처면 isNull()로.
	assertThat(p.isVerified()).isNotNull();
	// external_url은 없을 수 있는 필드 — 파싱이 예외 없이 null 허용하는지가 계약.
}

@Test
void 게시물_파싱은_영상과_유료협찬_표시를_담는다() {
	HikerClient client = new HikerClient(fakeHttp());
	List<PostInfo> posts = client.fetchRecentPosts("rarebeauty", "12345", 1);
	PostInfo reels = posts.stream().filter(p -> "REELS".equals(p.contentType())).findFirst().orElseThrow();
	// 릴스는 video_versions[0].url·video_duration이 실린다(Step 2 실측 확인 후 고정)
	assertThat(reels.videoUrl()).isNotBlank();
	assertThat(reels.videoDuration()).isPositive();
	// is_paid_partnership 키 부재면 null(unknown 판정 근거) — false와 구분해야 한다
}
```

- [x] **Step 4: 실패 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.HikerClientTest"`
Expected: 컴파일 실패(record에 필드 없음)

- [x] **Step 5: record 3종 필드 추가 + HikerClient 파싱**

`ProfileInfo`·`AuthorInfo`는 필드 추가만. `PostInfo`는 사본 생성자 3곳(`withFbPlays`·`mergedMetrics`·`mergedWith`) 모두에서 새 필드를 관통시킨다(`mergedWith`는 `coalesce(videoUrl, fallback.videoUrl)` 등 non-null 우선 — 캡션·썸네일과 동일 취급. `isPaidPartnership`도 coalesce).

`HikerClient` 파싱(기존 스타일 그대로):

```java
// fetchProfile 내 — user 노드에서:
Boolean isVerified = user.path("is_verified").isMissingNode() || user.path("is_verified").isNull()
		? null : user.path("is_verified").asBoolean();
String externalUrl = user.path("external_url").asString(null);

// fetchAuthorProfile 내 동일 패턴으로 is_verified.

// toPost 내 — m 노드에서:
String videoUrl = m.path("video_versions").path(0).path("url").asString(null);
Double videoDuration = m.path("video_duration").isNumber() ? m.path("video_duration").asDouble() : null;
Boolean isPaidPartnership = m.path("is_paid_partnership").isMissingNode() || m.path("is_paid_partnership").isNull()
		? null : m.path("is_paid_partnership").asBoolean();
```

- [x] **Step 6: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.*"`
Expected: PASS (기존 파싱 테스트 포함 전부)

- [x] **Step 7: 커밋**

```bash
git add monitoring/src
git commit -m "feat(monitoring): 브랜드 was 계약 필드 확장 — 마이그레이션 + Hiker 파싱(인증뱃지·외부링크·영상·유료협찬, 추가 콜 0)"
```

---

### Task 2: monitoring 저장 확장 + 백필 오류 기록

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandPostMetaRepository.java` (upsert에 video_url·video_duration·is_paid_partnership 3컬럼 추가 — 기존 컬럼 목록에 이어붙임, `EXCLUDED.` 갱신 동일)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/AuthorProfileRepository.java` (upsert에 is_verified 추가)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandSnapshotWriter.java` (saveBrandProfile → refreshProfile에 ProfileInfo 통째 전달)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java` (수정)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java` (수정)

**Interfaces:**
- Consumes: Task 1의 `ProfileInfo`·`AuthorInfo`·`PostInfo` 확장 필드.
- Produces: `BrandRepository.refreshProfile(long brandId, ProfileInfo profile)` — 기존 (id, followers, biography) 시그니처를 ProfileInfo 통째로 교체(브랜드 전용 코드라 허용. 호출처는 BrandSnapshotWriter.saveBrandProfile뿐 — grep으로 확인).
- Produces: `BrandRepository.touchSwept(long brandId, LocalDate on)` — SQL 확장(시그니처 불변):
  `UPDATE brand_account SET last_swept_on=?, last_swept_at=now(), backfill_completed_at=COALESCE(backfill_completed_at, now()), backfill_error=NULL WHERE id=?`
- Produces: `BrandRepository.markBackfillError(long brandId, String message)` — `UPDATE brand_account SET backfill_error=? WHERE id=? AND last_swept_on IS NULL` (ready 이후엔 안 덮음).
- `insertOrReactivate`도 재활성 시 `backfill_error=NULL, backfill_completed_at=NULL` 리셋 추가(재등록 = 백필 다시 "수집 준비 중").

- [x] **Step 1: 실패 테스트** — `BrandStoreTest`에 추가(기존 TestDb 관용구):

```java
@Test
void touchSwept는_완주시각과_오류클리어까지_기록한다() {
	long id = brands.insertOrReactivate("brand_x", profileInfo("brand_x"));
	brands.markBackfillError(id, "백필 실패: 타임아웃");
	assertThat(backfillError(id)).isEqualTo("백필 실패: 타임아웃");
	brands.touchSwept(id, LocalDate.of(2026, 8, 7));
	assertThat(backfillError(id)).isNull();
	assertThat(lastSweptAt(id)).isNotNull();
	assertThat(backfillCompletedAt(id)).isNotNull();
}

@Test
void markBackfillError는_ready_이후엔_덮지_않는다() {
	long id = brands.insertOrReactivate("brand_y", profileInfo("brand_y"));
	brands.touchSwept(id, LocalDate.of(2026, 8, 7));
	brands.markBackfillError(id, "늦게 온 실패");
	assertThat(backfillError(id)).isNull();
}

@Test
void refreshProfile은_전필드를_갱신한다() {
	long id = brands.insertOrReactivate("brand_z", profileInfo("brand_z"));
	brands.refreshProfile(id, new ProfileInfo("brand_z", "1", 10L, 5L, 3L,
			"이름", "https://pic", "소개", true, "https://link", "{}"));
	// full_name·profile_pic_url·is_verified·external_url·following·media_count 반영 확인(JdbcTemplate 직조회)
}
```

(`profileInfo(username)`은 테스트 로컬 헬퍼 — Task 1 확장 순서로 `new ProfileInfo(username, "1", 1L, 1L, 1L, null, null, null, null, null, "{}")`. `backfillError(id)` 등 컬럼 직조회 헬퍼도 테스트 로컬.)

- [x] **Step 2: 실패 확인** — Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest"` → 컴파일 실패

- [x] **Step 3: 저장 계층 구현** — Interfaces 블록의 SQL 그대로. `insertOrReactivate(String username, ProfileInfo profile)`로 시그니처 변경(전필드 INSERT + `ON CONFLICT` 갱신 목록에 신규 컬럼 추가). 호출처 `BrandRegistrationService.register`를 함께 갱신.

- [x] **Step 4: 백필 오류 기록** — `BrandRegistrationService.runBackfillSafely` catch에 한 줄 추가:

```java
} catch (RuntimeException e) {
	log.warn("브랜드 등록 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
	// was 폴링 계약(§5-2) — collecting에서 빠져나올 신호. 다음 스윕 성공(touchSwept)이 클리어한다.
	brands.markBackfillError(row.id(), "초기 수집에 실패했어요. 자동으로 재시도 중이에요.");
}
```

`BrandRegistrationServiceTest`에 "백필 실패 시 backfill_error가 기록된다" 케이스 추가(collect.sweep 스텁이 throw).

- [x] **Step 5: 통과 확인** — Run: `./gradlew :monitoring:test` → 전체 PASS

- [x] **Step 6: 커밋**

```bash
git add monitoring/src
git commit -m "feat(monitoring): 브랜드 저장 확장 — 프로필 전필드·영상/협찬 메타 적재, 백필 오류 기록·스윕 클리어"
```

---

### Task 3: app 마이그레이션 + 연결·direct 매핑 리포지토리

**Files:**
- Create: `was/src/main/resources/db/migration/app/V20260807130500__brand_monitoring_links.sql`
- Create: `was/src/main/java/com/celfit/was/monitoring/BrandLinkRepository.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/BrandLinkRow.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/BrandDirectPostRepository.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandLinkRepositoryTest.java` (신규 — 기존 was 통합 테스트 관용구: `V1MonitoringItemUpdateIntegrationTest`가 쓰는 Testcontainers 베이스를 그대로 따른다)

**Interfaces:**
- Produces:
  - `record BrandLinkRow(long id, long userId, long brandId, String username, OffsetDateTime createdAt, OffsetDateTime deletedAt)`
  - `Optional<BrandLinkRow> findActiveByUser(long userId)`
  - `Optional<BrandLinkRow> findActiveByUserAndBrand(long userId, long brandId)`
  - `String instagramAccountNameForUpdate(long userId)` — `SELECT instgram_account_name FROM app.users WHERE id = :id FOR UPDATE` (null 가능. 행 부재는 인증 전제상 불가 — orElseThrow)
  - `void saveInstagramAccountName(long userId, String username)`
  - `long insertLink(long userId, long brandId, String username)` — RETURNING id
  - `boolean softDeleteActiveLink(long userId)` — `UPDATE ... SET deleted_at = now() WHERE user_id = :id AND deleted_at IS NULL`, 갱신 행 수 > 0
  - `int countActiveByBrand(long brandId)`
  - `BrandDirectPostRepository`: `record Row(long userId, long brandId, String shortCode, long monitoringItemId)` · `List<Row> findByUser(long userId)` · `void upsert(long userId, long brandId, String shortCode, long itemId)`(ON CONFLICT DO NOTHING) · `Set<String> shortCodesByUser(long userId)`

- [x] **Step 1: 마이그레이션 작성** — 스펙 §3-1 SQL 그대로:

```sql
-- 브랜드 모니터링 연결(2026-08-07 스펙 §3-1) — expand 단계.
-- instgram 철자는 FE 명세 §10.1이 명시적으로 고정한 요청 컬럼명이다(오타 아님 — 계약).
ALTER TABLE app.users ADD COLUMN instgram_account_name varchar(30);
CREATE INDEX users_instgram_account_name_idx ON app.users (instgram_account_name);

-- user↔브랜드 활성 연결. brand_id는 monitoring brand_account.id 논리 참조(크로스 DB FK 금지).
CREATE TABLE app.brand_monitorings (
    id         bigserial PRIMARY KEY,
    user_id    bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id   bigint NOT NULL,
    username   text   NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);
CREATE UNIQUE INDEX brand_monitorings_active_user_uidx
    ON app.brand_monitorings (user_id) WHERE deleted_at IS NULL;
CREATE INDEX brand_monitorings_brand_idx ON app.brand_monitorings (brand_id);

-- 직접 등록 매핑 — 레거시 아이템에 "브랜드 화면 소속" 표식(FE brand_direct_media 역할).
CREATE TABLE app.brand_direct_posts (
    user_id            bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id           bigint NOT NULL,
    short_code         text   NOT NULL,
    monitoring_item_id bigint NOT NULL REFERENCES app.monitoring_items(id) ON DELETE CASCADE,
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, short_code)
);
```

- [x] **Step 2: 실패 테스트** — 통합 테스트로 링크 라이프사이클:

```java
@Test
void 활성_연결은_사용자당_하나다() {
	long userId = 테스트유저();   // 베이스 클래스의 유저 생성 헬퍼 따름
	repository.saveInstagramAccountName(userId, "lizda_official");
	repository.insertLink(userId, 100L, "lizda_official");
	assertThat(repository.findActiveByUser(userId)).isPresent();
	// 활성 중복은 유니크 위반
	assertThatThrownBy(() -> repository.insertLink(userId, 101L, "other"))
			.isInstanceOf(DuplicateKeyException.class);
	// soft-delete 후 재삽입 가능(재등록 경로)
	assertThat(repository.softDeleteActiveLink(userId)).isTrue();
	repository.insertLink(userId, 100L, "lizda_official");
	assertThat(repository.countActiveByBrand(100L)).isEqualTo(1);
}
```

- [x] **Step 3: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandLinkRepositoryTest"` → 컴파일 실패
- [x] **Step 4: 리포지토리 구현** — JdbcClient(`MonitoringItemRepository` 관용구), Interfaces 시그니처 그대로.
- [x] **Step 5: 통과 확인** — 동일 명령 PASS. Flyway가 신규 마이그레이션을 적용하는지 로그 확인.
- [x] **Step 6: 커밋**

```bash
git add was/src
git commit -m "feat(was): 브랜드 연결 저장 계층 — users.instgram_account_name + brand_monitorings·brand_direct_posts"
```

---

### Task 4: seam 확장 — 브랜드 명령 + BrandReadRepository

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java` (메서드 2개·record 1개 추가만)
- Create: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` — row record들은 전부 이 클래스의 **중첩 record**로 둔다(레거시 `PostMetaRow` 등과 이름 충돌 방지 + 후속 태스크가 `BrandReadRepository.BrandSnapshotRow`식으로 참조)
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringConfig.java` (`@Bean BrandReadRepository brandReadRepository()` 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandReadRepositoryTest.java` (신규 — monitoring DB 스키마가 필요하므로 기존 seam 조회 테스트(`MonitoringReadRepository` 검증 위치)의 Testcontainers·스키마 적용 관용구를 그대로 따른다. 없으면 시드 SQL을 테스트가 직접 실행)

**Interfaces:**
- Produces: `MonitoringCommandClient`
  - `record BrandRegisterResult(long brandId, String username, Long followers, String status)`
  - `BrandRegisterResult registerBrand(String username)` — `POST /api/brands` body `{"username": ...}`. 에러 승격은 기존 exchange() 재사용(404·422 → MonitoringApiException, 전송 실패 → MonitoringUnavailableException)
  - `void deregisterBrand(String username)` — `DELETE /api/brands/{username}`. 404는 삼킨다(이미 없음 = 목적 달성 — 로그만)
- Produces: `BrandReadRepository` (읽기 전용 monitoring JdbcClient — SQL은 브랜드 테이블만, 초안 뷰·조인 금지 관용구 동일):
  - `record BrandAccountRow(long id, String username, LocalDate lastSweptOn, OffsetDateTime lastSweptAt, OffsetDateTime registeredAt, OffsetDateTime backfillCompletedAt, String backfillError, Long followers, Long following, Long mediaCount, String biography, String fullName, String profilePicUrl, Boolean isVerified, String externalUrl, String status)`
  - `Optional<BrandAccountRow> findAccount(long brandId)`
  - `record BrandTaggedPostRow(String shortCode, String authorUsername, String authorIgUserId, OffsetDateTime takenAt, OffsetDateTime firstSeenAt, long commentsCollectedCount)`
  - `List<BrandTaggedPostRow> findTaggedPostsInWindow(long brandId, OffsetDateTime cutoff, int limit)` — `WHERE brand_id = :id AND taken_at >= :cutoff ORDER BY taken_at DESC LIMIT :limit`
  - `record BrandPostMetaRow(String shortCode, String username, String contentType, LocalDate uploadedAt, String caption, String thumbnailUrl, String videoUrl, Double videoDuration, Boolean isPaidPartnership)`
  - `List<BrandPostMetaRow> findPostMeta(Collection<String> shortCodes)`
  - `record BrandSnapshotRow(String shortCode, LocalDate capturedOn, String contentType, Long likes, boolean likesHidden, Long comments, Long views, Long saves, Long shares, boolean sharesHidden, Long reposts)` — views는 DDL상 이미 IG+FB 합산 저장값이라 그대로 SELECT(fb_plays 별도 조회 불필요)
  - `List<BrandSnapshotRow> findSnapshots(Collection<String> shortCodes)` — `ORDER BY short_code, captured_on`
  - `record BrandCommentRow(String shortCode, String id, String author, String body, long likeCount, OffsetDateTime commentedAt, String ownerReplyText)`
  - `List<BrandCommentRow> findComments(Collection<String> shortCodes)` — `ORDER BY short_code, commented_at DESC`
  - `record AuthorRow(String igUserId, String username, String fullName, Long followers, String profilePicUrl, Boolean isVerified)`
  - `List<AuthorRow> findAuthors(Collection<String> igUserIds)` + `List<AuthorRow> findAuthorsByUsername(Collection<String> usernames)` (tagged 행의 author_ig_user_id null 폴백)
  - 전 메서드 빈 컬렉션 선처리(`IN ()` SQL 오류 방지 — MonitoringReadRepository 관용구)

- [x] **Step 1: 실패 테스트** — 시드 SQL(테스트 로컬)로 brand_account·brand_tagged_post·brand_post_snapshot 각 1~2행 넣고 조회 검증:

```java
@Test
void 윈도우_조회는_컷과_상한을_적용하고_최신순이다() {
	// 시드: taken_at 100일 전 1건 + 10일 전 2건, limit 2
	List<BrandTaggedPostRow> rows = repository.findTaggedPostsInWindow(1L,
			OffsetDateTime.now().minusDays(90), 2);
	assertThat(rows).hasSize(2);
	assertThat(rows.get(0).takenAt()).isAfter(rows.get(1).takenAt());
}

@Test
void 스냅샷은_날짜_오름차순이다() { /* 시드 2일치 역순 삽입 → 조회 오름차순 확인 */ }

@Test
void 빈_컬렉션은_빈_결과다() {
	assertThat(repository.findPostMeta(List.of())).isEmpty();
}
```

- [x] **Step 2: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandReadRepositoryTest"` → 컴파일 실패
- [x] **Step 3: 구현** — Interfaces의 SQL·record 그대로. `MonitoringConfig`에 빈 추가:

```java
@Bean
BrandReadRepository brandReadRepository() {
	return new BrandReadRepository(monitoringJdbc);
}
```

`MonitoringCommandClient` 추가분:

```java
/** 브랜드 등록(monitoring BrandController) — 동기 프로필 검증 포함. 404/422는 MonitoringApiException으로 승격된다. */
public BrandRegisterResult registerBrand(String username) {
	return exchange(() -> restClient.post().uri("/api/brands")
			.body(Map.of("username", username)).retrieve().body(BrandRegisterResult.class));
}

/** 브랜드 탈퇴 — 404(미등록)는 목적 달성으로 삼킨다(삭제 재시도 안전). */
public void deregisterBrand(String username) {
	try {
		exchange(() -> restClient.delete().uri("/api/brands/{username}", username)
				.retrieve().toBodilessEntity());
	} catch (MonitoringApiException e) {
		if (e.httpStatus() != 404) {
			throw e;
		}
		log.info("브랜드 탈퇴 — monitoring에 미등록(이미 정리됨): {}", username);
	}
}
```

(`MonitoringApiException`의 HTTP 상태 접근자 이름은 실제 클래스 정의를 확인해 맞출 것 — 생성자에 `e.getStatusCode().value()`가 들어가는 것은 확인됨.)

- [x] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.*"` → PASS
- [x] **Step 5: 커밋**

```bash
git add was/src
git commit -m "feat(was): seam 브랜드 확장 — registerBrand/deregisterBrand 명령 + BrandReadRepository 배치 조회"
```

---

### Task 5: 브랜드 계정 API — 등록·목록·단건·삭제·폴링

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsController.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssembler.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountResponse.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandUsername.java` (정규화·검증 순수 함수)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandUsernameTest.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java` (@WebMvcTest — `V1CampaignControllerTest` 관용구: SecurityConfig·V1ExceptionAdvice Import, MockitoBean 리포지토리·클라이언트)

**Interfaces:**
- Consumes: Task 3 `BrandLinkRepository`, Task 4 `MonitoringCommandClient.registerBrand/deregisterBrand`·`BrandReadRepository.findAccount`.
- Produces:
  - `BrandUsername.normalize(String raw)` → String — trim·`@` 접두 제거·소문자. `BrandUsername.validate(String normalized)` → 위반 시 `V1ApiException.validation("프로필 URL이 아닌 @를 제외한 인스타그램 계정명만 입력해주세요.")`(URL·공백·정규식 위반·`..` 포함)
  - `V1BrandAccountService.register(long userId, String rawUsername)` → `BrandAccountResponse` (§5-1 플로우)
  - `V1BrandAccountService.delete(long userId, long brandId)` (§5-3)
  - `BrandAccountAssembler.toResponse(BrandAccountRow row)` → `BrandAccountResponse` (§5-2 상태 유도)
  - `record BrandAccountResponse(String id, Profile profile, String collectionStatus, String collectionStartedAt, String collectionCompletedAt, String lastDetectedAt, String lastTrackedAt, String nextScheduledAt, CollectionError collectionError, String createdAt)`
  - `record Profile(String profileUrl, String username, String fullName, String profilePicUrl, boolean isVerified, Long mediaCount, Long followerCount, Long followingCount, String biography, String externalUrl)` — `profileUrl = "https://www.instagram.com/" + username + "/"`, fullName·biography는 null → `""`, isVerified null → false
  - Task 7·9가 `BrandAccountResponse`·상태 유도 규칙을 소비한다.

- [x] **Step 1: BrandUsername TDD** — 실패 테스트:

```java
@Test
void 정규화는_골뱅이와_공백을_제거하고_소문자화한다() {
	assertThat(BrandUsername.normalize(" @Lizda_Official ")).isEqualTo("lizda_official");
}

@Test
void URL과_연속점과_금지문자는_거부한다() {
	for (String bad : List.of("https://www.instagram.com/lizda_official/", "a..b", "한글계정", "a b", "", "@")) {
		String normalized = BrandUsername.normalize(bad);
		assertThatThrownBy(() -> BrandUsername.validate(normalized))
				.isInstanceOf(V1ApiException.class);
	}
}
```

구현(정규식 `^[a-z0-9._]{1,30}$` + `contains("..")` 거부 + `contains("/")`·`contains(":")` 거부) → PASS 확인.

- [x] **Step 2: 서비스·컨트롤러 실패 테스트** — 핵심 계약 케이스:

```java
@Test
void 등록은_202와_collecting_계정을_반환한다() throws Exception {
	given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn(null);
	given(linkRepository.findActiveByUser(7L)).willReturn(Optional.empty());
	given(commandClient.registerBrand("lizda_official"))
			.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

	mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"username\": \"@Lizda_Official\"}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.collectionStatus").value("collecting"))
			.andExpect(jsonPath("$.data.profile.username").value("lizda_official"))
			.andExpect(jsonPath("$.data.collectionCompletedAt").value(Matchers.nullValue()));
}

@Test
void 같은_값_활성_연결이면_409_ALREADY_EXISTS다() throws Exception { /* linkRepository 활성 연결 + 같은 컬럼값 스텁 → 409 코드 검증 */ }

@Test
void 다른_값이_저장돼_있으면_409_IMMUTABLE이고_monitoring을_호출하지_않는다() throws Exception {
	given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn("other_brand");
	mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("BRAND_ACCOUNT_IMMUTABLE"));
	then(commandClient).should(never()).registerBrand(anyString());
}

@Test
void monitoring_404는_422_INSTAGRAM_ACCOUNT_NOT_FOUND로_번역된다() throws Exception { /* registerBrand가 MonitoringApiException(404) throw 스텁 */ }

@Test
void 목록은_계정_없으면_빈_배열과_total_0이다() throws Exception { /* findActiveByUser empty → data [] + meta.total 0 */ }

@Test
void 단건은_ready_전이를_반영한다() throws Exception { /* readyRow(last_swept_on 有) → collectionStatus ready + completedAt 존재 */ }

@Test
void 백필_오류는_error와_collectionError를_반환한다() throws Exception { /* backfill_error 有 row → error + collectionError.message */ }

@Test
void 삭제는_마지막_사용자일_때만_monitoring_탈퇴를_호출한다() throws Exception {
	given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
	given(linkRepository.countActiveByBrand(100L)).willReturn(0);   // soft-delete 후 잔여 0
	mockMvc.perform(delete("/v1/brand-monitoring/accounts/100").with(user(principal())).with(csrf()))
			.andExpect(status().isNoContent());
	then(commandClient).should().deregisterBrand("lizda_official");
}
```

- [x] **Step 3: 실패 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` → 컴파일 실패

- [x] **Step 4: 구현** — 서비스 register 핵심(§5-1 — 트랜잭션 경계 주의: monitoring 호출은 트랜잭션 밖):

```java
public BrandAccountResponse register(long userId, String rawUsername) {
	String username = BrandUsername.normalize(rawUsername);
	BrandUsername.validate(username);
	precheck(userId, username);                       // 비잠금 사전 확인 — 즉시 409 반환용
	BrandRegisterResult registered = translate(() -> commandClient.registerBrand(username));
	try {
		txTemplate.executeWithoutResult(tx -> {       // FOR UPDATE 재확인 + 저장(동시 요청 방어)
			String stored = linkRepository.instagramAccountNameForUpdate(userId);
			if (stored != null && !stored.equals(username)) {
				throw V1ApiException.conflict("BRAND_ACCOUNT_IMMUTABLE", "이미 등록한 브랜드 계정은 변경할 수 없습니다.");
			}
			if (linkRepository.findActiveByUser(userId).isPresent()) {
				throw V1ApiException.conflict("BRAND_ACCOUNT_ALREADY_EXISTS", "이미 등록된 브랜드 계정입니다.");
			}
			if (stored == null) {
				linkRepository.saveInstagramAccountName(userId, username);
			}
			linkRepository.insertLink(userId, registered.brandId(), username);
		});
	} catch (RuntimeException e) {
		compensateDeregister(username, registered);   // best-effort — 실패해도 무해(고아 replay 흡수)
		throw e;
	}
	return assembler.toResponse(brandReadRepository.findAccount(registered.brandId())
			.orElseThrow(() -> V1ApiException.notFound("브랜드 계정을 찾을 수 없습니다.")));
}
```

`translate()`는 MonitoringApiException(404→422 INSTAGRAM_ACCOUNT_NOT_FOUND, 422→422 그대로 메시지 유지)·MonitoringUnavailableException(→503 SERVICE_UNAVAILABLE) 번역 — `V1ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ...)`·`(HttpStatus.SERVICE_UNAVAILABLE, ...)` 직접 생성. 상태 유도·nextScheduledAt(`@Value("${was.brand.sweep-hour-kst:3}")` — 다음 KST 그 시각)은 `BrandAccountAssembler`. 컨트롤러는 `V1MonitoringItemsController` 관용구(AuthenticationPrincipal, ApiResponse). GET 목록 meta는 `{"total": n, "limit": 10}`.

- [x] **Step 5: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` → PASS
- [x] **Step 6: 커밋**

```bash
git add was/src
git commit -m "feat(was): 브랜드 계정 API — 등록(동기 검증 선행)·목록·단건 폴링·삭제, 상태 유도 collecting/ready/error"
```

---

### Task 6: BrandSponsorshipClassifier

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandSponsorshipClassifier.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandSponsorshipClassifierTest.java`

**Interfaces:**
- Produces: `static String classify(Boolean isPaidPartnership, String caption)` → `"sponsored" | "organic" | "unknown"`. Task 7·9 소비.

- [x] **Step 1: 실패 테스트**

```java
@Test
void 판정_규칙_4단계() {
	assertThat(BrandSponsorshipClassifier.classify(true, null)).isEqualTo("sponsored");
	assertThat(BrandSponsorshipClassifier.classify(null, "오늘의 #협찬 후기")).isEqualTo("sponsored");
	assertThat(BrandSponsorshipClassifier.classify(false, "#광고 아님… 이 아니라 광고")).isEqualTo("sponsored"); // 키워드가 플래그 false보다 우선
	assertThat(BrandSponsorshipClassifier.classify(false, "그냥 일상")).isEqualTo("organic");
	assertThat(BrandSponsorshipClassifier.classify(null, "그냥 일상")).isEqualTo("unknown");
	assertThat(BrandSponsorshipClassifier.classify(null, null)).isEqualTo("unknown");
}
```

- [x] **Step 2: 실패 확인** → 컴파일 실패
- [x] **Step 3: 구현**

```java
/** 협찬 판정(FE §4.4) — 조회 시 계산·저장 없음(캡션 원문이 있어 키워드 개선이 과거분에 즉시 소급). */
public final class BrandSponsorshipClassifier {

	private static final List<String> CONFIRM_KEYWORDS =
			List.of("#광고", "#협찬", "#유료광고", "유료 광고", "유료광고", "광고입니다", "협찬받", "협찬 받");

	private BrandSponsorshipClassifier() {}

	public static String classify(Boolean isPaidPartnership, String caption) {
		if (Boolean.TRUE.equals(isPaidPartnership)) {
			return "sponsored";
		}
		if (caption != null && CONFIRM_KEYWORDS.stream().anyMatch(caption::contains)) {
			return "sponsored";
		}
		return Boolean.FALSE.equals(isPaidPartnership) ? "organic" : "unknown";
	}
}
```

- [x] **Step 4: 통과 확인** → PASS
- [x] **Step 5: 커밋** — `git commit -m "feat(was): 협찬 판정 순수 함수 — 유료협찬 플래그·캡션 확정 키워드 4단계"`

---

### Task 7: 브랜드 게시물 목록·상세 API

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostResponse.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java` (이 태스크에선 목록·상세 2 엔드포인트만 — direct는 Task 8)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandPostAssemblerTest.java` (단위 — 리포지토리 mock 없이 row record 직접 조립)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java` (@WebMvcTest)

**Interfaces:**
- Consumes: Task 4 row record들, Task 6 classify, Task 3 `BrandDirectPostRepository`, 레거시 `TrackingItemAssembler.assembleList(long)`·`TrackingItemResponse`(직접 등록분 상태·스냅샷 재사용)·`AuthorMask.mask(String)`.
- Produces:
  - `record BrandPostResponse(String id, String brandAccountId, String source, String postUrl, String shortcode, String contentType, String takenAt, String caption, String thumbnailUrl, String videoUrl, Double videoDuration, String authorProfileUrl, String authorUsername, String authorFullName, String authorProfilePicUrl, boolean authorIsVerified, Long authorFollowers, String sponsorship, Boolean isPaidPartnership, String trackingStatus, String trackingStartedAt, String trackingEndedAt, TrackingItemResponse.SnapshotResponse latestSnapshot, List<TrackingItemResponse.SnapshotResponse> snapshots, Long commentsTotal, boolean commentsHidden, long commentsCollectedCount, List<TrackingItemResponse.PostCommentResponse> recentComments, List<String> campaignIds, String createdAt, String updatedAt)`
  - `BrandPostAssembler.assembleForBrand(long userId, BrandAccountRow account)` → `List<BrandPostResponse>` (tagged+direct 병합·direct 우선, 필터 전 전량)
  - `BrandPostAssembler.snapshotOf(BrandSnapshotRow row)` → `TrackingItemResponse.SnapshotResponse` — **FEED면 views·shares·reposts null 강제**, sharesHidden·likesHidden 관통
  - Task 9가 `assembleForBrand`·`BrandPostResponse`를 소비한다.

- [x] **Step 1: 어셈블러 단위 실패 테스트** — row record를 손으로 만들어 규칙 검증:

```java
@Test
void FEED_스냅샷은_views_shares_reposts를_null로_강제한다() {
	var row = new BrandReadRepository.BrandSnapshotRow("ABC", LocalDate.of(2026, 8, 6), "FEED",
			10L, false, 2L, 999L, 5L, 7L, false, 3L);
	var s = BrandPostAssembler.snapshotOf(row);
	assertThat(s.views()).isNull();
	assertThat(s.shares()).isNull();
	assertThat(s.reposts()).isNull();
	assertThat(s.likes()).isEqualTo(10L);
}

@Test
void 같은_shortcode가_tagged와_direct_양쪽이면_direct_한_건이다() { /* 두 계열 입력 → source direct 1건 */ }

@Test
void 스냅샷은_오름차순이고_latestSnapshot은_마지막_원소다() { /* 2일치 → snapshots[1]==latest */ }

@Test
void 게시자_프로필_부재면_author_필드는_열거_관측값으로_폴백한다() { /* author_profile 없음 → username은 tagged 행 값, followers null */ }
```

- [x] **Step 2: 실패 확인** → 컴파일 실패
- [x] **Step 3: 어셈블러 구현** — 조립 순서:

```
1. tagged: BrandReadRepository.findTaggedPostsInWindow(brandId, now-90d, 105)
   → shortcode 묶음 → findPostMeta·findSnapshots·findComments·findAuthors(+username 폴백) 배치 4~5 SQL
   → BrandPostResponse(source "tagged", trackingStatus "tracking",
      trackingStartedAt=firstSeenAt, trackingEndedAt=null,
      sponsorship=classify(meta.isPaidPartnership, meta.caption),
      postUrl = contentType REELS → "https://www.instagram.com/reel/{code}/" / FEED → ".../p/{code}/",
      authorProfileUrl = "https://www.instagram.com/{authorUsername}/",
      commentsTotal=최신 스냅샷 comments, commentsHidden=(comments==null && 스냅샷 존재),
      recentComments=BrandCommentRow → PostCommentResponse(author는 AuthorMask.mask, reply는 ownerReplyText null 아니면 생성),
      campaignIds=List.of(), createdAt=firstSeenAt, updatedAt=lastSweptAt)
2. direct: BrandDirectPostRepository.findByUser(userId) 교집합(brandId) →
   TrackingItemAssembler.assembleList(userId)에서 해당 아이템 필터 →
   TrackingItemResponse → BrandPostResponse 변환(source "direct", trackingStatus=item.status,
   sponsorship=classify(null, post.caption), shortcode=매핑 행 값,
   snapshots·recentComments는 레거시 것 그대로 — 셰이프 동형)
3. shortcode 병합: direct 우선, tagged 겹침은 버리되 sponsorship은 tagged의
   isPaidPartnership 관측이 있으면 그것으로 재판정(정보 손실 방지)
```

- [x] **Step 4: 컨트롤러 + 테스트** — `GET /v1/brand-monitoring/accounts/{accountId}/posts`(소유 검증 → 어셈블 → 쿼리 필터 `source`/`sponsorship`/`sort`/`uploadedFrom`/`uploadedTo` 메모리 적용 → meta `{total, limit: 200, counts{all,tagged,direct,sponsored,organic,unknown}, lastCollectedAt}`) · `GET /v1/brand-monitoring/posts/{postId}`(shortcode — 내 tagged/direct 어디에도 없으면 404). 컨트롤러 테스트: 200 목록·counts 정합, 필터 동작(`?sponsorship=sponsored`), 남의 계정 403, 미소유 게시물 404, `performance_desc` 정렬(최신 스냅샷 views 내림차순·null 마지막).

- [x] **Step 5: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` → PASS
- [x] **Step 6: 커밋**

```bash
git add was/src
git commit -m "feat(was): 브랜드 게시물 목록·상세 API — tagged+direct 합성, FEED null 규칙·협찬 판정·counts meta"
```

---

### Task 8: 직접 등록 API

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandDirectPostService.java`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandDirectRegistrationResponse.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java` (엔드포인트 2개 추가)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandDirectPostServiceTest.java`

**Interfaces:**
- Consumes: 레거시 `V1MonitoringRegistrationService.register(long userId, Map<String,Object> body)` — body 키 `posts`(URL 문자열 리스트)·`trackingDays`·`campaignId`, 반환 `MonitoringRegistrationResponse`(registrationId + items). 레거시 `RegistrationRepository`(entry 조회 — `V1RegistrationsController`가 쓰는 조회 메서드 재사용), `MonitoringInput.parsePost(String)`(shortcode 정규화 — 반환 타입 sealed 분기는 `V1MonitoringRegistrationService.processPost` 참조).
- Produces:
  - `POST /v1/brand-monitoring/accounts/{accountId}/direct-posts` body `{postUrls[], trackingDays, campaignId}` → 202 `{registrationId, requestedAt, entries[{input, result, reasonCode, reason, brandPostId, monitoringItemId}]}`
  - `GET /v1/brand-monitoring/direct-registrations/{registrationId}` → 동일 셰이프(entry result `pending|success|failed|duplicate`)
  - `record BrandDirectRegistrationResponse(String registrationId, String requestedAt, List<Entry> entries)` + `record Entry(String input, String result, String reasonCode, String reason, String brandPostId, String monitoringItemId)`

- [x] **Step 1: 실패 테스트**

```java
@Test
void 이미_태그_게시물로_있으면_레거시_위임_없이_duplicate다() {
	// tagged shortcode 집합에 ABC 존재 스텁 → postUrls [".../reel/ABC/"] 등록
	var response = service.register(7L, 100L, List.of("https://www.instagram.com/reel/ABC/"), 30, null);
	assertThat(response.entries().get(0).result()).isEqualTo("duplicate");
	then(legacyRegistration).should(never()).register(anyLong(), any());
}

@Test
void 신규_URL은_레거시_등록에_위임하고_매핑을_만든다() {
	// legacyRegistration.register 반환 스텁(registrationId 55, item id "301") →
	// directPostRepository.upsert(7L, 100L, "DEF", 301L) 호출 검증 + entries result pending
}

@Test
void 이미_레거시_추적_중이면_매핑만_추가하고_success다() { /* 레거시 duplicate entry(기존 item id 동반) → upsert + success */ }
```

- [x] **Step 2: 실패 확인** → 컴파일 실패
- [x] **Step 3: 구현** — 플로우(§6-4):

```
1. 계정 소유 검증(BrandLinkRepository.findActiveByUserAndBrand — 아니면 403)
2. postUrls 각각 MonitoringInput.parsePost → shortcode. Invalid → entry failed(레거시 reasonCode 재사용)
3. shortcode ∈ (내 tagged ∪ 내 direct) → entry duplicate("이미 브랜드 목록에 있는 게시물입니다."), 위임 제외
4. 잔여 URL들 → legacyRegistration.register(userId, Map.of("posts", urls, "trackingDays", days[, "campaignId", id]))
5. 반환 items·entry 결과를 순서로 매칭 → 성공·기존행 연결분은 brand_direct_posts.upsert
6. 202 + 레거시 registrationId 그대로 노출
```

GET은 레거시 entry 조회 → 같은 셰이프 재조립(brandPostId=shortcode, 아직 미확정이면 null).

- [x] **Step 4: 통과 확인** → PASS
- [x] **Step 5: 커밋** — `git commit -m "feat(was): 브랜드 직접 등록 — 레거시 등록 파이프라인 위임 + direct 매핑, entry 단위 부분 성공"`

---

### Task 9: PerformanceContentAssembler — 통합·중복 제거·스냅샷 병합

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java`
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentResponse.java` (중첩으로 `PerformanceItemResponse`·`PerformancePostResponse` 포함)
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java`

**Interfaces:**
- Consumes: `TrackingItemAssembler.assembleList(long)` → `AssembledList(items, lastCollectedAt, today)`, Task 7 `BrandPostAssembler.assembleForBrand`·`BrandPostResponse`, Task 3 `BrandDirectPostRepository.shortCodesByUser`.
- Produces:
  - `record PerformanceContentResponse(PerformanceItemResponse item, String source, String sponsorship, String canonicalPostId, List<String> additionalSources, String brandAccountId)`
  - `record PerformanceItemResponse(String id, String mode, String status, String handle, String displayName, String profileImageUrl, Long followers, String lastUploadedAt, String campaignId, String campaignName, String sourceUrl, String registeredAt, int trackingDays, TrackingItemResponse.Keywords keywords, PerformancePostResponse post, String nextCheckAt)`
  - `record PerformancePostResponse(String url, String shortcode, String contentType, String uploadedAt, String caption, List<String> matchedKeywords, String thumbnailUrl, String hiddenAt, List<TrackingItemResponse.SnapshotResponse> snapshots, Long commentsTotal, boolean commentsHidden, long commentsCollectedCount, List<TrackingItemResponse.PostCommentResponse> recentComments)`
  - `PerformanceContentAssembler.assemble(long userId)` → `record Assembled(List<PerformanceContentResponse> contents, OffsetDateTime lastCollectedAt)` — 필터 전 전량. Task 10 소비.
  - `static List<TrackingItemResponse.SnapshotResponse> mergeSnapshots(List<...> legacy, List<...> brand)` — 순수 함수(테스트 대상)

- [x] **Step 1: 실패 테스트** — 병합 규칙 핵심:

```java
@Test
void 같은_날짜는_지표별_non_null_우선_둘다_값이면_브랜드값이다() {
	var legacy = List.of(snapshot("2026-08-06", /*views*/ 100L, /*likes*/ null, /*comments*/ 5L));
	var brand  = List.of(snapshot("2026-08-06", /*views*/ 120L, /*likes*/ 8L,  /*comments*/ null));
	var merged = PerformanceContentAssembler.mergeSnapshots(legacy, brand);
	assertThat(merged).hasSize(1);
	assertThat(merged.get(0).views()).isEqualTo(120L);   // 둘 다 값 → 브랜드
	assertThat(merged.get(0).likes()).isEqualTo(8L);     // 레거시 null → 브랜드
	assertThat(merged.get(0).comments()).isEqualTo(5L);  // 브랜드 null → 레거시
}

@Test
void 중복_제거는_individual_direct_tagged_우선순위다() {
	// 레거시 아이템(비direct) + 같은 shortcode tagged → source individual, additionalSources [tagged], item은 레거시
}

@Test
void tagged_only는_bt_접두_합성_아이템이다() {
	// tagged만 → item.id "bt_ABC", mode url, status tracking, trackingDays 90, post 채움
}

@Test
void 게시물_없는_detecting은_canonicalPostId가_null이다() { /* post null 레거시 아이템 → canonicalPostId null 유지 */ }
```

- [x] **Step 2: 실패 확인** → 컴파일 실패
- [x] **Step 3: 구현** — 조립 흐름:

```
1. legacy = trackingItemAssembler.assembleList(userId) — TrackingItemResponse → PerformanceItemResponse 변환
   (shortcode는 post.url 경로 세그먼트에서 추출: "/reel/{code}/"·"/p/{code}/" — 정규식 "/(?:p|reel|reels)/([A-Za-z0-9_-]+)")
   commentsTotal=최신 스냅샷 comments, commentsCollectedCount=recentComments.size(), commentsHidden=false
2. directCodes = brandDirectPostRepository.shortCodesByUser(userId)
   → legacy 항목 source = shortcode ∈ directCodes ? "direct" : "individual"
3. 활성 브랜드 있으면 tagged = brandPostAssembler.assembleForBrand(...) 중 source=="tagged"만
4. shortcode 키 병합: legacy 항목 우선(item·상태·캠페인 유지), tagged 겹침 → additionalSources+["tagged"]
   + mergeSnapshots(legacy.post.snapshots, tagged.snapshots) + sponsorship은 tagged의 판정으로 승격
   tagged-only → 합성 PerformanceItemResponse(설계 결정 7)
5. sponsorship: legacy-only는 classify(null, caption), post 없으면 "unknown"
6. brandAccountId: tagged 관측이 있으면 브랜드 id 문자열, 아니면 null
```

- [x] **Step 4: 통과 확인** → PASS
- [x] **Step 5: 커밋** — `git commit -m "feat(was): 성과 대시보드 통합 어셈블러 — 3계열 병합·shortcode 중복 제거·스냅샷 지표별 병합"`

---

### Task 10: 성과 대시보드 컨트롤러

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardControllerTest.java` (@WebMvcTest — PerformanceContentAssembler MockitoBean)

**Interfaces:**
- Consumes: Task 9 `PerformanceContentAssembler.assemble(long)`.
- Produces: `GET /v1/performance-dashboard/contents`(쿼리 `uploadedFrom`·`uploadedTo`·`source`·`sponsorship`·`campaignId`(all|none|{id})·`status`·`brandAccountId`) · `GET /v1/performance-dashboard/contents/{contentId}`.

- [x] **Step 1: 실패 테스트**

```java
@Test
void statusCounts는_업로드_기간_필터와_무관하다() {
	// assemble 스텁: tracking 2건(업로드 8/1·8/6) → ?uploadedFrom=2026-08-05 요청
	// data 1건, meta.statusCounts.tracking 2 검증
}

@Test
void 상태_7종_키가_항상_전부_존재한다() { /* 0건이어도 statusCounts에 tracking~error 7키 */ }

@Test
void campaignId_none은_캠페인_없는_콘텐츠만이다() { /* campaignId null 항목만 */ }

@Test
void 단건은_canonicalPostId로_찾고_없으면_404다() { /* contents/{shortcode} */ }
```

- [x] **Step 2: 실패 확인** → 컴파일 실패
- [x] **Step 3: 구현** — 필터는 전부 메모리(`data`에 전 필터, statusCounts는 uploadedFrom/To 제외 필터만 적용 — §7-1). 업로드 기간은 `item.post.uploadedAt`(post null 항목은 기간 필터 시 제외하되 statusCounts엔 포함). meta는 `{"total", "limit": 250, "lastCollectedAt", "statusCounts"}` — LinkedHashMap 관용구.
- [x] **Step 4: 통과 확인** → PASS
- [x] **Step 5: 커밋** — `git commit -m "feat(was): 성과 대시보드 API — 필터·기간 무관 statusCounts·단건 조회"`

---

### Task 11: 캠페인 v2 — 콘텐츠 추가·제거

**Files:**
- Create: `was/src/main/java/com/celfit/was/v2/monitoring/V2CampaignContentsController.java`
- Create: `was/src/main/java/com/celfit/was/v2/monitoring/V2CampaignContentService.java`
- Test: `was/src/test/java/com/celfit/was/v2/monitoring/V2CampaignContentServiceTest.java`
- Test: `was/src/test/java/com/celfit/was/v2/monitoring/V2CampaignContentsControllerTest.java`

**Interfaces:**
- Consumes: `CampaignRepository.findByIdAndUser(long, long)`, Task 9의 shortcode→아이템 대응(전량 조회 재사용: `TrackingItemAssembler.assembleList` + shortcode 추출), `V1MonitoringItemUpdateService.patch(long userId, long itemId, Map fields)`(campaignId 연결 — 기존 검증 재사용), `V1MonitoringRegistrationService.register`(미존재 시 아이템 생성), Task 7 `BrandPostAssembler`(tagged 게시물의 canonical URL 확보).
- Produces:
  - `POST /v2/monitoring/campaigns/{campaignId}/contents` body `{contentIds[], trackingDays}` → 200/202 `{campaignId, results[{contentId, result, monitoringItemId, reasonCode, reason}]}`
  - `DELETE /v2/monitoring/campaigns/{campaignId}/contents/{contentId}` → 204

- [x] **Step 1: 실패 테스트**

```java
@Test
void 기존_아이템은_캠페인만_연결한다() { /* 아이템 campaignId null → patch({campaignId}) 호출, result success */ }

@Test
void 같은_캠페인_소속이면_duplicate다() { /* reasonCode CAMPAIGN_CONTENT_ALREADY_EXISTS + 한국어 reason */ }

@Test
void 다른_캠페인_소속이면_duplicate고_이동하지_않는다() { /* patch 미호출 검증 */ }

@Test
void 미존재_콘텐츠는_레거시_등록으로_아이템을_만든다() { /* tagged 게시물 URL로 register 위임 */ }

@Test
void 제거는_campaign_연결만_끊고_204다() { /* patch({campaignId: null}) — 모니터링 지속 */ }

@Test
void 남의_캠페인은_404다() { /* findByIdAndUser empty */ }
```

- [x] **Step 2: 실패 확인** → 컴파일 실패
- [x] **Step 3: 구현** — 추가 플로우(§8): 캠페인 소유 검증 → contentId(=shortcode)별 분기(기존 아이템: campaign null→patch 연결·같은 캠페인→duplicate·다른 캠페인→duplicate / 미존재: tagged에서 canonical URL 찾아 `register(userId, Map.of("posts", [url], "trackingDays", days, "campaignId", id))` → 그 결과 item id) → results 조립. contentId가 tagged에도 없으면 result failed(`NOT_FOUND`, "게시물을 찾을 수 없습니다."). 제거: shortcode→아이템 탐색(그 캠페인 소속 확인 — 아니면 404) → patch campaignId null → 204.
- [x] **Step 4: 통과 확인** — Run: `./gradlew :was:test --tests "com.celfit.was.v2.monitoring.*"` → PASS
- [x] **Step 5: 커밋** — `git commit -m "feat(was): 캠페인 v2 콘텐츠 관계 — canonicalPostId 기반 추가·제거, 1:1 유지·entry 부분 성공"`

---

### Task 12: 총정리 — 전체 검증·문서 갱신·PR

**Files:**
- Modify: `DECISIONS.md` (맨 위 신규 행), `docs/tracks/MON-BT-브랜드-태그-모니터링.md` (상태·미결 갱신 — "was 조회 API" 미결 해소), `docs/superpowers/specs/2026-08-07-brand-monitoring-was-api-design.md` (상태 헤더 → ✅ 구현됨), 본 계획 → `docs/superpowers/plans/archive/`로 이동

- [x] **Step 1: 레거시 회귀 확인** — Run: `./gradlew :was:test` (기존 `v1/monitoring` 테스트 전부 무수정 PASS = 레거시 계약 보존 증거). 기존 테스트 파일을 수정했다면 그 자체가 위반 — diff로 확인: `git diff --stat develop -- was/src/test/java/com/celfit/was/v1/monitoring/`은 비어 있어야 한다.
- [x] **Step 2: 전체 테스트** — Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew test` (PR 직전 1회 규칙)
- [x] **Step 3: 문서 갱신** — DECISIONS 신규 행(브랜드 was API 구현 — 스펙 §2 의도적 편차 5개 요지 포함), MON-BT 트랙 상태 🔵→✅ 계열 갱신, 스펙 상태 헤더, 계획 아카이브 이동.
- [x] **Step 4: 커밋 + PR**

```bash
git add -A
git commit -m "docs: 브랜드 모니터링 was API 구현 반영 — DECISIONS·MON-BT 트랙·스펙 상태 갱신, 계획 아카이브"
git push -u origin feature/brand-monitoring-api-spec-794b18
```

PR은 develop 대상, 본문에 스펙·계획 링크 + FE 공유 필요 사항(스펙 §2 표) 명시. gh CLI 부재 시 git credential+curl API 경로(메모리 참조).
