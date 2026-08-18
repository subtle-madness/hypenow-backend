# 브랜드 direct 게시물 파이프라인 통합 — 구현 계획 (2026-08-18)

> 상태: ✅ 구현됨(E1·E2) · M 이관·C contract 잔여 — 잔여는 MON-BT 트랙 문서가 정본

설계 정본: [2026-08-18-brand-direct-pipeline-unification-design.md](../../specs/2026-08-18-brand-direct-pipeline-unification-design.md)
(이하 "설계 §N"으로 인용). 이 계획은 **결정을 다시 하지 않는다** — 설계에서 갈린 지점을
그대로 실행한다.

## 실행 규약

- **각 태스크는 build(sonnet) 서브에이전트에 그대로 넘길 수 있게 self-contained로 썼다.**
  위임 시 이 문서의 해당 절 전문 + 설계 문서 경로를 프롬프트에 붙인다.
- **배포 순서 의존: monitoring → was.** E1(monitoring)이 운영에 먼저 올라간 뒤 E2(was)가 올라간다.
  역순이면 was가 없는 컬럼을 조회해 브랜드 게시물 목록이 전면 500이 된다(08-13과 같은 의존, 롤백도
  같은 방향).
- **테스트는 모듈 단위**: `./gradlew :monitoring:test`, `./gradlew :was:test`. 전체 `./gradlew test`는
  PR 직전 1회만. 셸에 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`이 없으면
  Testcontainers가 무더기로 죽는다 — 대량 실패를 보면 이것부터 확인할 것.
- **Flyway 채번**: `date -u +%Y%m%d%H%M%S`(반드시 UTC)로 `V<타임스탬프>__<설명>.sql`. 버전 공간은
  monitoring(`monitoring/src/main/resources/db/migration/`)과 was app(`was/src/main/resources/db/
  migration/app/`)이 서로 독립이다. 기존 `V1`~`V49` 파일은 절대 rename 금지.
- **주석·로그·커밋 메시지는 한국어.** 커밋 prefix `feat(monitoring):` / `feat(was):` / `docs:`.
- **PR은 사용자 명시 승인 후에만 연다.** 태스크 완료 시 push와 보고까지만 한다.

## 태스크 지도

```
E1 monitoring (배포 1순위)          E2 was (배포 2순위)
  T1 스키마·리포지토리 소스 분리  ──▶  T6 app 스키마
  T2 direct 수집 서비스                T7 monitoring 명령 클라이언트 확장
  T3 스윕 2단계 합류                   T8 브랜드 등록 실행기
  T4 명령 API 2종 + import 모드        T9 V1BrandDirectPostService 재작성
  T5 monitoring 테스트 정리            T10 BrandPostAssembler 통합
                                       T11 발견 카드 재배선
                                       T12 성과 대시보드 재배선
                                       T13 아카이브 카탈로그·삭제 경로
                                       T14 was 테스트 정리
                                     ─────────────────────────────
                                       M  이관 잡 (배포 직후 운영 실행)
                                       C  contract 단계 (다음 릴리스)
```

T1→T2→T3→T4는 순차. T6~T13은 T4의 API 시그니처가 확정된 뒤 T6·T7을 먼저 하고 T8~T13은
부분 병렬 가능하나, T10이 T9·T11·T12의 전제라 **T10을 T11·T12보다 먼저** 끝낸다.

---

# E1 — monitoring (이번 릴리스, expand)

## T1. `brand_tagged_post` 스키마 확장 + 리포지토리 소스 분리

**목적**: direct 링크가 들어갈 자리를 만들고, 태그 열거 깊이 판정이 direct-only 행에 오염되지
않도록 쿼리에 가드를 넣는다. 설계 §3-1·§5-3.

**마이그레이션** — `monitoring/src/main/resources/db/migration/V<UTC타임스탬프>__brand_tagged_post_direct_source.sql`

```sql
-- 브랜드 direct 게시물 파이프라인 통합(2026-08-18 설계 §3-1) — expand 단계, nullable ADD만.
-- source 단일 enum을 두지 않는 이유: 한 게시물이 태그 발견분이면서 동시에 직접 등록분일 수 있고
-- PK가 (brand_id, short_code)라 행이 하나뿐이다. 단일 값으로 접으면 취소 시 태그 발견 사실을 잃는다.
-- 응답의 source는 direct_registered_at IS NOT NULL 로 파생한다(direct 우선 = 현행 mergeByShortcode 규칙).
--
-- tag_detected_at의 DEFAULT now()는 롤링 배포 전용이다 — 구버전 파드의 TaggedPostRepository.insert는
-- 이 컬럼을 모르므로 DEFAULT가 채워야 한다. direct 경로는 명시적 NULL로 DEFAULT를 무력화한다.
-- contract 단계에서 DEFAULT를 제거한다.
ALTER TABLE brand_tagged_post
    ADD COLUMN tag_detected_at      timestamptz DEFAULT now(),
    ADD COLUMN direct_registered_at timestamptz;

-- 기존 행은 전부 태그 열거 산지다. 백필을 빠뜨리면 열거 깊이 판정(trackedPosts)의 가드가
-- 전 행을 제외해 다음 스윕이 14일 깊이만 열고 티어 2~4가 통째로 멈춘다.
UPDATE brand_tagged_post SET tag_detected_at = first_seen_at WHERE tag_detected_at IS NULL;

-- direct 2단계 스윕의 모수 조회용 부분 인덱스(전체 행 대비 극소수).
CREATE INDEX brand_tagged_post_direct_idx
    ON brand_tagged_post (brand_id) WHERE direct_registered_at IS NOT NULL;
```

**코드** — `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java`

1. `trackedPosts(long brandId, Instant minTakenAt)` 의 WHERE에 `AND tag_detected_at IS NOT NULL`
   추가. **주석 필수**: direct-only 행은 태그 열거에 절대 나타나지 않으므로 due로 잡히면
   `touchCrawled`가 영영 안 걸려 매일 최대 180일 깊이를 여는 요청량 누수가 영구화된다.
2. `touchCrawledDepth(long brandId, Instant minTakenAt, Instant at)` 의 WHERE에도 같은 가드 추가.
   **주석 필수**: 이 가드가 없으면 direct-only 행이 "수집된 적 없는데 크롤됨"으로 마킹돼 단건
   수집이 영영 안 돈다.
3. 신규 `List<TrackedPost> directDuePosts(long brandId, Instant minTakenAt)`:
   ```sql
   SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
    WHERE brand_id = ? AND direct_registered_at IS NOT NULL
      AND tag_detected_at IS NULL AND taken_at >= ?
   ```
   (due 판정 자체는 호출자가 `BrandCrawlPolicy.due`로 한다 — 기존 관용구 유지.)
4. 신규 `void upsertDirect(long brandId, PostInfo post, Instant registeredAt)`:
   ```sql
   INSERT INTO brand_tagged_post
       (brand_id, short_code, author_username, author_ig_user_id, taken_at,
        tag_detected_at, direct_registered_at)
   VALUES (?, ?, ?, ?, ?, NULL, ?)
   ON CONFLICT (brand_id, short_code) DO UPDATE SET
       direct_registered_at = COALESCE(brand_tagged_post.direct_registered_at, EXCLUDED.direct_registered_at),
       author_ig_user_id    = COALESCE(brand_tagged_post.author_ig_user_id, EXCLUDED.author_ig_user_id)
   ```
   (기존 tagged 행에 direct 표식만 얹는 경우를 같은 문으로 처리. `tag_detected_at`은 건드리지 않는다.)
5. 신규 `void clearDirect(long brandId, String shortCode)` — `UPDATE ... SET direct_registered_at = NULL
   WHERE brand_id=? AND short_code=?`, 그리고
   `boolean deleteIfDirectOnly(long brandId, String shortCode)` —
   `DELETE FROM brand_tagged_post WHERE brand_id=? AND short_code=? AND tag_detected_at IS NULL`
   (영향 행수 > 0 반환). 취소 분기(설계 §2-4)가 쓴다.
6. 기존 `insert(long brandId, PostInfo post)`(태그 열거용)에 `tag_detected_at`을 명시적으로 넣도록
   수정: `ON CONFLICT (brand_id, short_code) DO UPDATE SET tag_detected_at = COALESCE(brand_tagged_post.tag_detected_at, now())`.
   **이유**: direct로 먼저 들어온 행을 나중에 태그 열거가 만나면 `DO NOTHING`으로는 `tag_detected_at`이
   영영 안 채워져 2단계 단건 콜과 열거가 같은 게시물을 이중 수집한다.

**테스트** — `monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java`에 추가:

- 기존 tagged 행이 마이그레이션 후 `tag_detected_at IS NOT NULL`이다.
- `upsertDirect` 신규 삽입 → `tag_detected_at IS NULL`, `direct_registered_at` 채워짐.
- `upsertDirect`를 기존 tagged 행에 → `tag_detected_at` 보존, `direct_registered_at` 채워짐.
- `insert`(열거)를 기존 direct-only 행에 → `direct_registered_at` 보존, `tag_detected_at` 채워짐.
- `trackedPosts`가 direct-only 행을 **반환하지 않는다**.
- `touchCrawledDepth`가 direct-only 행의 `last_crawled_at`을 **건드리지 않는다**.
- `directDuePosts`가 direct-only 행만 반환한다(direct+tagged 겹침 행 제외).

**완료 판정**: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.*"` 통과 +
위 6개 테스트가 가드 제거 시 실제로 실패하는지 한 번 깨뜨려 확인(설계 R5).

---

## T2. `BrandDirectCollectService` — direct 게시물 단건 수집

**목적**: 태그 열거가 도달할 수 없는 게시물을 단건 콜로 수집·보강한다. 설계 §2-2·§3-2.

**새 파일** — `monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java`

클래스 javadoc에 반드시 담을 것: **"단건 게시물 콜 전면 금지"(08-06·08-09) 결정과 충돌하지
않는다** — 그 결정은 태그 열거로 이미 얻은 게시물에 단건 콜을 덧붙이는 제안을 "열거 대비 추가
지표 없음"을 근거로 기각한 것이고, direct 게시물은 애초에 열거에 실리지 않아 그 근거가 성립하지
않는다. 레거시 url 모드는 지금도 같은 엔드포인트를 쓴다(`CollectService.collectPost`).

**공개 메서드 2개**

```java
/** 게시물 1건 등록·이관 경로 — 동기 완결(단건 1콜 + enrich). 예외는 호출자(컨트롤러)가 매핑한다. */
public PostInfo collectAndEnrich(BrandRow brand, String shortCode, Instant registeredAt)

/** 야간 스윕 2단계 — directDuePosts 중 BrandCrawlPolicy.due인 것만 게시물 단위 격리로 수집. */
public void sweepDirect(BrandRow brand)
```

**`collectAndEnrich` 흐름**

```
callContext.scoped(brand.id(), () -> {
    PostInfo post = hiker.fetchPost(shortCode);            // SubjectNotFound·HikerFetch는 그대로 전파
    List<PostInfo> adjusted = collect.adjustLotteryMetrics(List.of(post));   // 복권 3종 보정 재사용
    writer.savePost(LocalDate.now(KST), adjusted.get(0));  // brand_post_snapshot + brand_post_meta
    taggedPosts.upsertDirect(brand.id(), adjusted.get(0), registeredAt);
    taggedPosts.touchCrawled(brand.id(), List.of(shortCode), Instant.now());
    collect.enrich(brand, adjusted);                       // 게시자 + 댓글 + markEnriched(finally)
    return adjusted.get(0);
});
```

- `BrandCollectService.adjustLotteryMetrics`는 현재 private다 — **package-private로 올려 재사용**한다
  (복사 금지: 0 캐리·부재=0 규칙이 두 벌이 되면 반드시 갈라진다).
- `enrich`는 이미 public이고 `markEnriched`를 `finally`로 보장한다 — 그대로 쓴다.
- `taken_at`이 null인 게시물은 `brand_tagged_post.taken_at`이 NOT NULL이라 저장 불가다.
  `HikerFetchException("게시일 미상")`으로 던져 호출자가 failed로 정산하게 한다.

**`sweepDirect` 흐름**

```
Instant now = Instant.now();
List<TrackedPost> due = taggedPosts.directDuePosts(brand.id(), now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE))
        .stream().filter(t -> BrandCrawlPolicy.due(t.takenAt(), t.lastCrawledAt(), now)).toList();
// 게시물 단위 격리 — 한 건의 404·타임아웃이 나머지를 죽이지 않는다.
// 삭제·비공개 전환(SubjectNotFound)에도 행을 지우지 않는다: 브랜드 파이프라인은 상태 전이를
// 하지 않는다(스펙 §8·BrandSweepJob 주석). 카드는 마지막 스냅샷으로 남는다.
for (배치(예: 20건)마다) { 수집 → enrich(배치) }   // markEnriched는 enrich의 finally가 보장
```

- 게시자 프로필·댓글 병렬화는 `enrich` 안의 `brandEnrichWorkerPool`이 이미 한다 — 여기서
  추가 병렬화하지 않는다(전역 동시 콜 상한 계산이 깨진다, `BrandBackfillConfig` 주석 참조).

**테스트** — `monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java`(신규)

- 페이크 `HikerClient`로: 수집 1건 → 스냅샷·메타·링크 행·`enriched_at`이 전부 채워진다.
- `SubjectNotFoundException` → 예외 전파(`collectAndEnrich`), 그리고 `sweepDirect`에서는 삼키고
  나머지 건이 계속 수집된다 + 링크 행이 남아 있다.
- `taken_at` null 응답 → `HikerFetchException`.
- 180일 초과 direct 행은 `sweepDirect`가 콜을 내지 않는다(`CountingHikerHttp`로 콜 수 0 확인).
- 14일 이내 direct 행은 매일 due, 30일 행은 3일 주기(시각 주입으로 검증).

**완료 판정**: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"` 통과.

---

## T3. 야간 스윕에 2단계 합류

**목적**: 등록 이후의 지속 수집 경로를 연다. 설계 §3-2.

**파일** — `monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java`

`runSweep()`의 브랜드 루프에 단계 하나를 추가한다. 격리 규율은 기존 2단계(유저태그·해시태그)와
동일 — **각자 try/catch**, 실패 카운트 별도 집계, 한쪽 실패가 다른 쪽에 영향 없음.

```java
try { collect.sweep(b); brands.touchSwept(b.id(), today); } catch (RuntimeException e) { … }
try { directCollect.sweepDirect(b); } catch (RuntimeException e) { directFailures++; … }   // 신규
try { hashtagCollect.sweep(b); } catch (RuntimeException e) { … }
```

- **`touchSwept`는 1단계(유저태그) 성공에만 찍는다** — 현행 그대로. direct 2단계 실패가 계정을
  "수집 준비 중"으로 되돌리면 안 된다(direct 실패는 그 게시물만의 문제다).
- 완료 로그에 direct 실패 건수를 포함한다.

**테스트** — `monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java`

- direct 단계가 던져도 해시태그 단계가 실행되고 `touchSwept`가 유지된다.
- 유저태그 단계가 던져도 direct 단계는 실행된다.

**완료 판정**: `./gradlew :monitoring:test --tests "*BrandSweepJobTest"` 통과.

---

## T4. monitoring 명령 API 2종 + import 모드

**목적**: was가 direct 등록·취소·이관을 명령할 표면을 연다. 설계 §2-2·§2-4·§4-2.

**파일** — `monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java`(기존, `/api/brands`)

### 4-1. `POST /api/brands/{brandId}/direct-posts`

> 경로 변수를 `{username}`이 아니라 `{brandId}`로 두는 이유: was는 `app.brand_monitorings.brand_id`를
> 들고 있고 username은 브랜드 계정명 변경 시 흔들린다. 기존 해시태그 API가 `{username}`을 쓰는 것과
> 의도적으로 다르다 — javadoc에 근거를 남길 것.

요청 `{ "shortCode": "ABC123", "registeredAt": "…"(선택, import 전용), "importLegacyHistory": false }`

| 상황 | 응답 |
|---|---|
| 신규 수집 성공 | `201` `{shortCode, authorUsername, takenAt, contentType}` |
| 이미 `direct_registered_at`이 있는 행 | `200` (같은 바디, 멱등) |
| 게시물 부재·삭제(`SubjectNotFoundException`) | `404` `{code:"POST_NOT_FOUND", message:"게시물을 찾을 수 없습니다."}` |
| 비공개 계정(`PrivateAccountException`) | `422` `{code:"PRIVATE_ACCOUNT", …}` |
| 게시일 미상 등 셰이프 이상 | `422` `{code:"POST_UNSUPPORTED", …}` |
| 브랜드 미존재·비ACTIVE | `404` `{code:"BRAND_NOT_FOUND", …}` |

- **에러 바디를 반드시 `{code, message}`로 채운다.** 비우면 was `MonitoringCommandClient.exchange`가
  코드 없는 응답으로 오인해 `MonitoringUnavailableException`(503)으로 잘못 승격한다(08-11 실측 —
  `brandNotFound()` 주석 참조).
- `importLegacyHistory=true`면 `collectAndEnrich` **전에** 레거시 이력을 복사한다(아래 4-3).
- 처리는 동기다(최대 5콜 ≈ 7초). 컨트롤러 타임아웃 설정이 이보다 짧지 않은지 확인할 것.

### 4-2. `DELETE /api/brands/{brandId}/direct-posts/{shortCode}`

```
행 없음                              → 204 (멱등)
tag_detected_at IS NOT NULL          → clearDirect(...)         → 204
tag_detected_at IS NULL              → deleteIfDirectOnly(...)  → 204
```

`brand_post_snapshot`·`brand_post_meta`·`brand_post_comment`는 **지우지 않는다**(게시물 전역 자산,
"윈도우 이탈 후에도 영구 보존" 규칙). javadoc에 명시.

### 4-3. 레거시 이력 복사 (import 모드)

같은 DB 안이므로 순수 SQL이다. 새 리포지토리 메서드
(`monitoring/src/main/java/com/celfit/monitoring/store/BrandLegacyHistoryCopier.java` 신설)로 분리한다.

```sql
INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type, likes, likes_hidden,
                                 comments, views, fb_plays, saves, shares, shares_hidden, reposts)
SELECT username, short_code, captured_on, content_type, likes, likes_hidden,
       comments, views, fb_plays, saves, shares, shares_hidden, reposts
  FROM post_snapshot WHERE short_code = ?
ON CONFLICT (short_code, captured_on) DO NOTHING;

INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url,
                             first_seen_at, image_object_path, image_source_name, image_archived_at)
SELECT … FROM post_meta WHERE short_code = ?
ON CONFLICT (short_code) DO NOTHING;

INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at, owner_reply_text)
SELECT … FROM post_comment WHERE short_code = ?
ON CONFLICT (short_code, id) DO NOTHING;
```

**⚠️ 구현 전에 반드시 실제 DDL을 컬럼 단위로 대조하고, 대조 결과(어느 컬럼이 어느 쪽에만 있는지)를
SQL 주석으로 남길 것**(설계 R9). 확인 명령 예:

```
docker exec -i ${PG_CONTAINER:-crawler-postgres-1} psql -U crawler -d crawler \
  -c "\d post_snapshot" -c "\d brand_post_snapshot" -c "\d post_meta" -c "\d brand_post_meta"
```

브랜드 전용 필드(`video_url`·`video_duration`·`is_paid_partnership`)는 레거시에 없다 — NULL로 두고
직후의 `collectAndEnrich`가 채운다(`ON CONFLICT DO NOTHING`이라 복사가 먼저여도 `savePost`의
upsert가 나중에 덮는다).

**레거시 원본은 절대 지우지 않는다** — 같은 게시물이 다른 유저의 개인 캠페인에서 추적 중일 수 있다.

**테스트** — `monitoring/src/test/java/com/celfit/monitoring/web/BrandControllerTest.java` 확장 +
`BrandLegacyHistoryCopierTest`(신규, Testcontainers)

- 201/200(멱등)/404/422 각 분기.
- DELETE 3분기(행 없음·겹침·direct-only)와 게시물 전역 테이블 보존.
- import: 레거시 스냅샷 3행이 있는 shortcode → 복사 후 brand_post_snapshot 3행 + 오늘자 1행.
- import 재실행이 중복 행을 만들지 않는다.

**완료 판정**: `./gradlew :monitoring:test` 전체 통과.

---

## T5. monitoring 회귀 확인

**완료 판정**: `./gradlew :monitoring:test` 전량 green. 실패 시 `DOCKER_HOST` 먼저 확인.
`git push`까지. **PR은 사용자 승인 후.**

---

# E2 — was (이번 릴리스, expand · monitoring 배포 후)

## T6. app 스키마

**마이그레이션** — `was/src/main/resources/db/migration/app/V<UTC타임스탬프>__brand_post_unification.sql`

```sql
-- 브랜드 direct 게시물 파이프라인 통합(2026-08-18 설계 §2-1·§3·§4-1) — expand 단계.

-- 브랜드 전용 등록 상태 저장소. 레거시 monitoring_registrations 위임을 끊는다 —
-- brand_id가 등록 행에 있어 share 해소분의 브랜드 추정(resolveLazyMappingBrand)이 통째로 불필요해진다.
CREATE TABLE app.brand_post_registrations (
    id           bigserial   PRIMARY KEY,
    user_id      bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id     bigint      NOT NULL,   -- monitoring brand_account.id 논리 참조(크로스 DB FK 금지)
    campaign_id  bigint      REFERENCES app.monitoring_campaigns(id) ON DELETE SET NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE INDEX brand_post_registrations_user_idx ON app.brand_post_registrations (user_id);

CREATE TABLE app.brand_post_registration_entries (
    registration_id bigint NOT NULL REFERENCES app.brand_post_registrations(id) ON DELETE CASCADE,
    seq             int    NOT NULL,
    input           text   NOT NULL,
    short_code      text,
    result          text   NOT NULL CHECK (result IN ('pending','success','failed','duplicate')),
    reason_code     text,
    reason          text,
    settled_at      timestamptz,
    PRIMARY KEY (registration_id, seq)
);

-- 게시물↔캠페인 N:M. 캠페인은 서비스 데이터라 monitoring이 아니라 여기 둔다(시스템 경계).
-- campaign_id에 CASCADE를 걸지 않는다 — ArchiveCascadeReachabilityTest가 monitoring_campaigns의
-- CASCADE 자식이 0개일 것을 강제한다(CampaignRepository.delete가 캠페인 1행만 아카이브·삭제한다는
-- 전제). 캠페인 삭제 경로에서 이 테이블을 명시적으로 아카이브·삭제한다
-- (brand_direct_posts가 monitoring_items에 대해 쓰는 패턴, V20260811090500과 동형).
CREATE TABLE app.brand_post_campaigns (
    brand_id    bigint      NOT NULL,
    short_code  text        NOT NULL,
    campaign_id bigint      NOT NULL REFERENCES app.monitoring_campaigns(id),
    user_id     bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, short_code, campaign_id)
);
CREATE INDEX brand_post_campaigns_campaign_idx ON app.brand_post_campaigns (campaign_id);
CREATE INDEX brand_post_campaigns_user_idx     ON app.brand_post_campaigns (user_id);

-- 이관 진행 표식. NOT NULL = "이 매핑의 정본은 monitoring 통합 풀이다"(레거시 조립 대상 아님).
-- 롤링 창과 이관 진행 중에도 카드가 사라지지 않게 하는 장치 — contract 단계에서 제거한다.
ALTER TABLE app.brand_direct_posts ADD COLUMN migrated_at timestamptz;

-- 신규 등록은 레거시 아이템을 만들지 않는다. DROP NOT NULL은 구버전 코드를 죽이지 않으므로
-- migration-guard의 금지 목록(SET NOT NULL)에 해당하지 않는다.
ALTER TABLE app.brand_direct_posts ALTER COLUMN monitoring_item_id DROP NOT NULL;
```

**완료 판정**: `./gradlew :was:test --tests "*Migration*"` 및 Flyway 부팅 성공. CI
`migration-guard` 잡이 이 파일을 통과해야 한다(destructive 없음 — `-- allow-destructive` 불필요).

---

## T7. `MonitoringCommandClient` 확장

**파일** — `was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java`

기존 관용구(에러 바디 `{code,message}` 있으면 `MonitoringApiException`, 없으면
`MonitoringUnavailableException`)를 그대로 따라 2개 추가:

```java
/** 201=신규 수집, 200=이미 풀에 있음(멱등). 둘 다 성공으로 접는다. */
public DirectPostResult registerDirectPost(long brandId, String shortCode,
        OffsetDateTime registeredAt, boolean importLegacyHistory);

/** 없어도 204(멱등). */
public void deleteDirectPost(long brandId, String shortCode);
```

**테스트** — `was/src/test/java/com/celfit/was/monitoring/MonitoringCommandClientTest.java`(기존 관용구
확장): 201·200·404(`POST_NOT_FOUND`)·422·503·바디 없는 5xx 각각의 예외 매핑.

**완료 판정**: `./gradlew :was:test --tests "*MonitoringCommandClientTest"` 통과.

---

## T8. `BrandDirectRegistrationExecutor`

**새 파일** — `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandDirectRegistrationExecutor.java`

기존 `was/src/main/java/com/celfit/was/v1/monitoring/MonitoringRegistrationExecutor.java`를
**구조적 참고 대상**으로 삼는다(복사가 아니라 관용구 승계): 전용 스레드 풀,
`afterCommit` 제출, `RejectedExecutionException`은 로그만 남기고 pending 유지, 5분 stale 복구.

**entry 1건 처리** (설계 §2-2)

```
① MonitoringInput.parsePost(input)
     Invalid          → failed(invalid.reasonCode(), invalid.reason())
     Post             → shortCode 확정
     Share 단축 링크  → ② 로
② client.resolveShare(input, userId)   (기존 POST /api/share/resolve)
     ShareLinkUnresolved → failed
     성공 → shortCode 확정, entry.short_code 저장
③ 브랜드 풀 중복 재검사(BrandReadRepository.findBrandPoolStatus) → duplicate면 확정
④ client.registerDirectPost(brandId, shortCode, null, false)
     성공(201/200)                       → success
     MonitoringApiException              → failed(e.code() 매핑)
     MonitoringUnavailableException/타임아웃 → pending 유지(로그만) — stale 복구가 재시도
⑤ 성공 시 app.brand_direct_posts upsert(user_id, brand_id, short_code, monitoring_item_id=NULL,
   migrated_at=now())  ← 유저 귀속 원장
⑥ 등록의 campaign_id가 있으면 app.brand_post_campaigns upsert
```

- **`reasonCode` 어휘는 레거시 상수를 그대로 재사용**한다. 새 문자열을 만들면 FE 분기가 깨진다.
  `was/src/main/java/com/celfit/was/monitoring/RegistrationResult.java`의 상수가 정본이다:

  | 상황 | `result` | `reasonCode` |
  |---|---|---|
  | 정상 | `SUCCESS`("success") | null |
  | URL 형식 오류 | `FAILED` | `REASON_INVALID_FORMAT`("invalid_format") — `MonitoringInput.Invalid`가 주는 값 그대로 |
  | monitoring `POST_NOT_FOUND`(404) | `FAILED` | `REASON_NOT_FOUND`("not_found") |
  | monitoring `PRIVATE_ACCOUNT`(422) | `FAILED` | `REASON_PRIVATE_ACCOUNT`("private_account") |
  | share 해소 실패 | `FAILED` | `REASON_SHARE_LINK_UNRESOLVED`("share_link_unresolved") |
  | 브랜드 풀 중복 | `DUPLICATE` | `REASON_DUPLICATE`("duplicate") |
  | 그 외 확정 실패·stale 정산 | `FAILED` | `REASON_INTERNAL_ERROR`("internal_error") |

  `CANCELED`/`REASON_CANCELED`는 산지 자체가 없어져 쓰이지 않는다(레거시 아이템 취소 개념 소멸) —
  현행 `toEntry`의 "canceled → failed 접기"도 함께 삭제한다.
- 대응하는 monitoring 예외는 `com.celfit.monitoring.hiker`의 `SubjectNotFoundException`(→404
  `POST_NOT_FOUND`) · `PrivateAccountException`(→422 `PRIVATE_ACCOUNT`) ·
  `ShareLinkUnresolvedException`(share 해소) · `HikerFetchException`(→422 `POST_UNSUPPORTED`)이다.
- 모든 entry가 settled면 `brand_post_registrations.completed_at`을 찍는다.
- **stale 정산**: 24시간 넘게 pending인 entry를 failed로 정산하는 스케줄러를 함께 둔다
  (설계 R7 — 레거시 `settleStaleRegistrationEntries` 동형).

**테스트** — `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandDirectRegistrationExecutorTest.java`

페이크 `MonitoringCommandClient`로: 성공/404 failed/503 pending 유지 + 복구 재시도/share 해소 성공·
실패/중복 재검사 duplicate/전건 정산 시 `completed_at`.

**완료 판정**: 위 테스트 통과.

---

## T9. `V1BrandDirectPostService` 재작성

**파일** — `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandDirectPostService.java`

**삭제할 것**: `V1MonitoringRegistrationService` 위임 전체, `settle`/`Plan`의 seq 인덱스 매칭,
`resolveLazyMappingBrand`, `activeItemId`/`targetsOf`/`TERMINAL_STATUSES`,
`cancelLegacyIfPossible`. 의존 빈에서 `V1MonitoringRegistrationService`·`MonitoringItemRepository`·
`MonitoringReadRepository`·`RegistrationRepository`·`V1MonitoringItemUpdateService`가 빠진다.

### `register(userId, brandId, postUrls, trackingDays, campaignId)` — 202

```
① requireOwnership(userId, brandId)   … 현행 유지(competitor는 403 COMPETITOR_ACCOUNT_NOT_ALLOWED)
② validatePostUrls(≤100) / validateTrackingDays(1~90)
   ⚠️ trackingDays는 검증만 하고 저장·사용하지 않는다(설계 §5-1). 그 사실을 javadoc에 명시.
③ campaignId 소유 검증(CampaignRepository.findByIdAndUser) → 없으면 404
④ 브랜드 풀 스냅샷 조회(§중복 판정) → 입력별 duplicate 1차 확정
⑤ brand_post_registrations + entries 삽입(입력 순서 = seq)
⑥ afterCommit → executor.submit(registrationId)
⑦ 202 응답(현행 BrandDirectRegistrationResponse 셰이프 그대로, monitoringItemId는 항상 null)
```

**중복 판정** (설계 §2-3) — `BrandReadRepository`에 신규 메서드:

```sql
-- findBrandPoolStatus(brandId, shortCodes) → (shortCode, taggedDetected, directRegistered, takenAt)
SELECT short_code,
       tag_detected_at IS NOT NULL      AS tag_detected,
       direct_registered_at IS NOT NULL AS direct_registered,
       taken_at
  FROM brand_tagged_post
 WHERE brand_id = :brandId AND short_code IN (:shortCodes)
```

```
duplicate ⟺ 행이 있고 ( direct_registered OR taken_at >= 링크 표시 창 컷 )
링크 표시 창 컷 = max(오늘 − link.collectionMonths, 오늘 − BrandPostAssembler.WINDOW_DAYS) 자정(KST)
```

**현행과 달라지는 점을 주석으로 남길 것**: ① 유저의 다른 브랜드 direct 매핑을 모수에 넣지 않는다
(크로스 브랜드 누수 제거) ② 창 밖 tagged는 duplicate가 아니라 승격 대상이다(데드엔드 해소)
③ 미정산 tagged를 중복으로 잡을 필요가 없어졌다(셰이프가 하나라 direct 고정 위험이 소멸).

### `get(userId, registrationId)` — 폴링

`brand_post_registrations`를 유저 스코프로 읽어 entry를 그대로 옮긴다. **보정 로직 없음** —
success 승격도, 지연 매핑도 사라진다. 남의 등록·없는 등록은 똑같이 404.

### `cancel(userId, postId)` — 204/400/404 (설계 §2-4)

```
유저의 활성 링크 전체를 순회하며 그 브랜드 풀에서 postId 행을 찾는다
  못 찾음                              → 404 "대상을 찾을 수 없습니다."
  direct_registered = false            → 400 TAGGED_POST_NOT_CANCELABLE
                                          "태그로 발견된 게시물은 취소할 수 없어요."
  그 외                                → client.deleteDirectPost(brandId, postId)   ← 실패는 전파
                                        + app.brand_direct_posts 원장 행 삭제
                                        + app.brand_post_campaigns 해당 행 삭제
                                        → 204
```

**원격 실패를 삼키지 않는 이유를 주석으로 남길 것**: 원장만 지우면 monitoring은 계속 수집하는데
화면에서만 사라지는 불일치가 생긴다(현행 `cancelLegacyIfPossible` 판단의 승계).

**테스트** — `V1BrandDirectPostServiceTest` 재작성 + `V1BrandDirectPostCancelIntegrationTest` 수정

- 등록 → 취소 → 재등록 왕복이 새 등록으로 성립한다(현행 통합 테스트의 계약 유지).
- 겹침 행 취소 → 204이고 목록에 tagged로 남는다.
- 순수 direct 취소 → 204이고 목록에서 사라진다.
- 순수 tagged 취소 → 400.
- 창 밖 tagged 직접 등록 → duplicate가 아니라 위임된다.
- competitor 브랜드 등록 → 403.
- 다른 브랜드에 등록된 shortcode를 이 브랜드에 등록 → duplicate가 **아니다**(회귀 방지).

**완료 판정**: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"` 통과.

---

## T10. `BrandPostAssembler` 통합 (T11·T12의 전제)

**파일** — `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandPostAssembler.java`

**삭제**: `directPost(...)`, `mergeByShortcode(...)`, `promoteSponsorship(...)`, `assembleDirect(...)`,
`TrackingItemAssembler` 의존.

**변경**: `taggedPost(...)` → `brandPost(...)`로 개명하고 설계 §3-3 표대로 필드 규칙을 적용한다.

- `source` = `direct_registered_at IS NOT NULL ? SOURCE_DIRECT : SOURCE_TAGGED`
- `trackingStatus` = 항상 `"tracking"`
- `trackingStartedAt`·`registeredAt` = `COALESCE(direct_registered_at, first_seen_at)`
- `firstSeenAt` = `first_seen_at`
- `endedAt` = null
- `updatedAt` = `GREATEST(account.lastSweptAt, row.lastCrawledAt)`
- `campaignIds` = `app.brand_post_campaigns` 배치 조회(brandId + shortCode 묶음)
- `TaggedScope` → `BrandPostScope`로 개명(값 2종은 유지, **기본값 없는 필수 인자 규칙도 유지**)

**`BrandReadRepository` 변경** — `findTaggedPostsInWindow`/`findEnrichedTaggedPostsInWindow`를
`findBrandPostsInWindow(brandId, cutoff, scope)`로 통합하고 SELECT·WHERE를 설계 §3-3대로 바꾼다.
`last_crawled_at`·`tag_detected_at`·`direct_registered_at`을 추가로 읽고, WHERE에
`( taken_at >= :cutoff OR direct_registered_at IS NOT NULL )`를 넣는다(direct 창 예외).

**과도기 폴백**(설계 §4-1) — `assembleForBrand`가 아래를 결과에 얹는다. **C 단계에서 제거할 것을
`// TODO(contract)` 주석으로 표시**한다.

```java
// 이관 전 매핑(migrated_at IS NULL)만 레거시 셰이프로 조립해 얹는다 — 이관 잡이 진행되는 만큼
// 자연히 비어간다. contract 단계에서 이 분기와 migrated_at 컬럼을 함께 제거한다.
List<BrandPostResponse> legacyPending = assembleLegacyPending(userId, brandId);
```

**테스트** — `BrandPostAssemblerTest` 재작성

- direct 행이 tagged와 동일한 필드 셋을 갖는다(`videoUrl`·`videoDuration`·`authorIsVerified`·
  `isPaidPartnership`이 채워진다) — **비대칭 해소의 회귀 방지 테스트**.
- 창 밖 direct 행이 목록에 남고, 창 밖 tagged 행은 빠진다.
- `source` 파생 규칙 3종(tagged only / direct only / 겹침 → direct).
- `updatedAt`이 행 `last_crawled_at`과 계정 `last_swept_at` 중 늦은 값이다.
- `campaignIds`가 채워진다.

**완료 판정**: `./gradlew :was:test --tests "*BrandPostAssemblerTest"` 통과.

---

## T11. 발견 카드(`hashtag-posts`) 재배선

**파일** — `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagPostAssembler.java`

설계 §2-5 표대로 판정 산지를 바꾼다. `BrandDirectPostRepository` 의존을 제거하고,
`BrandReadRepository.findBrandPoolStatus(brandId, shortCodes)` 1회 조회로 갈음한다.

```
제외 조건    : tag_detected AND NOT direct_registered
brandPostId  : direct_registered ? shortCode : null
```

08-18 결정(발견 목록은 "태그 안 된 게시물", direct 승격분은 유지)의 의미는 **불변**임을 javadoc에
명시한다 — 판정 산지만 app 매핑에서 브랜드 풀 컬럼으로 옮겼다.

**테스트** — `BrandHashtagPostAssemblerTest`: 겹침 제외·direct 승격 유지·`brandPostId` 3분기.

**완료 판정**: `./gradlew :was:test --tests "*BrandHashtagPostAssemblerTest"` 통과.

---

## T12. 성과 대시보드 재배선

**파일** — `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java`

설계 §결정 3 후반부대로:

1. `directMapping(userId)` / `DirectMapping` 레코드 **삭제**.
2. `fromTagged` → `fromBrandPost`로 일반화. `source`는 `BrandPostResponse.source()`를 그대로
   쓴다(`direct`/`tagged`).
3. `campaignId`·`campaignName`은 `BrandPostResponse.campaignIds()`의 head로 채운다(비면 null).
   `campaignName`은 기존 `campaignsById` 룩업 재사용.
4. `attributedBrandAccountId`의 "direct(own)가 경쟁사 tagged를 이긴다" 예외 **삭제** — direct와
   tagged가 한 행이라 귀속이 곧 그 행의 `brand_id`다. `ownFirst`(브랜드 간 동률)는 **유지**.
5. `mergeSnapshots`는 **유지** — 브랜드 풀 게시물이 동시에 개인 캠페인(individual) 아이템일 수 있다.
6. `SYNTHETIC_ID_PREFIX = "bt_"`는 유지하되 javadoc을 "tagged-only 합성"에서 **"브랜드 풀 콘텐츠"**로
   고쳐 쓴다. direct 콘텐츠의 `item.id`가 숫자에서 `bt_<shortcode>`로 바뀌는 것은 FE 통지 항목이다.
7. `TAGGED_TRACKING_DAYS = 90` 주석이 "브랜드 표시 윈도우(90일)"라 되어 있으나 실제 창은 365일이다 —
   이번에 값·주석 정합을 바로잡을지 판단하고, 바꾸면 FE 통지에 추가한다.

**테스트** — 기존 perfdashboard 테스트 전량 + 신규:
direct 콘텐츠의 `source`가 `direct`, `item.id`가 `bt_` 접두, `campaignId`가 채워진다.

**완료 판정**: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"` 통과.

---

## T13. 아카이브 카탈로그·삭제 경로

**파일**
- `was/src/main/java/com/celfit/was/archive/ArchiveTables.java`
- `was/src/main/java/com/celfit/was/auth/UserRepository.java`(탈퇴)
- `was/src/main/java/com/celfit/was/monitoring/CampaignRepository.java`(캠페인 삭제)

1. `ArchiveTables`에 신규 3테이블 등재: `BRAND_POST_REGISTRATIONS`(PK `id`, `t.user_id`),
   `BRAND_POST_REGISTRATION_ENTRIES`(PK `registration_id,seq`, user_id 없음 → registration 경유
   서브쿼리 — `MONITORING_REGISTRATION_ENTRIES`와 동형), `BRAND_POST_CAMPAIGNS`(PK
   `brand_id,short_code,campaign_id`, `t.user_id`). `CATALOG`와 `ACCOUNT_DELETION_ORDER` **둘 다**에
   넣는다(두 목록은 서로 파생시키지 않는다 — 클래스 주석 참조).
2. `UserRepository.deleteAccount`의 명시 삭제 순서에 3테이블을 넣는다.
3. `CampaignRepository.delete`에 `app.brand_post_campaigns`의 **명시 아카이브 + 삭제**를 캠페인 행
   삭제 **앞에** 넣는다(FK에 CASCADE가 없으므로 순서를 어기면 FK 위반이 난다 — 그게 이 설계의
   의도된 안전망이다).
4. `ArchiveCascadeReachabilityTest`가 요구하는 계약을 **깨뜨려 확인**한다: 새 테이블을 카탈로그에서
   일부러 빼고 테스트가 실패하는지 본 뒤 되돌린다(NN 트랙 "가드는 반드시 깨뜨려보고 확인" 규칙).

**완료 판정**: `./gradlew :was:test --tests "com.celfit.was.archive.*"` 통과 + 위 깨뜨리기 확인.

---

## T14. was 회귀 확인

**완료 판정**: `./gradlew :was:test` 전량 green. 이어서 PR 직전 1회 `./gradlew test` 전체.
`git push`까지. **PR은 사용자 승인 후.**

---

# M — 이관 (배포 직후, 운영 1회)

## M1. 사전 규모 확인 (설계 R1·R2)

```sql
-- was app DB
SELECT count(*) AS mappings, count(DISTINCT short_code) AS posts,
       count(*) FILTER (WHERE migrated_at IS NULL) AS pending
  FROM app.brand_direct_posts;

SELECT brand_id, count(*) FROM app.brand_monitorings
 WHERE deleted_at IS NULL GROUP BY brand_id HAVING count(*) > 1;   -- R2 영향 브랜드
```

예산: `posts × 최대 5콜 × $0.0006`. 결과를 사용자에게 보고하고 **승인 후** M2를 실행한다.

## M2. 이관 잡 실행

**구현** — was 어드민 트리거 엔드포인트 또는 일회성 CLI. 멱등(재실행 안전).

```
for each row in app.brand_direct_posts WHERE migrated_at IS NULL (short_code 기준 브랜드별 dedupe):
    client.registerDirectPost(row.brandId, row.shortCode,
                              registeredAt = row.createdAt, importLegacyHistory = true)
    성공 → UPDATE app.brand_direct_posts SET migrated_at = now() WHERE user_id=? AND short_code=?
           + monitoring_items.campaign_id 가 있으면 app.brand_post_campaigns INSERT
    404/422 → migrated_at을 찍고(게시물이 이미 사라진 건) 로그에 남긴다 — 무한 재시도 금지
    503    → 건너뛰고 다음 실행이 재시도
```

**완료 판정**:
- `SELECT count(*) FROM app.brand_direct_posts WHERE migrated_at IS NULL` = 0
- monitoring `SELECT count(*) FROM brand_tagged_post WHERE direct_registered_at IS NOT NULL`
  이 M1의 `posts`와 일치
- monitoring `SELECT count(*) FROM brand_tagged_post WHERE enriched_at IS NULL` = 0
  (0이 아니면 그만큼의 게시물이 목록에서 사라진 상태다 — 08-13과 같은 확인 항목)
- 표본 3건을 브랜드 화면에서 눈으로 확인: `videoUrl`·인증 배지·팔로워·스냅샷 이력이 이관 전보다
  줄지 않았을 것

## M3. 콜 증분 실측 (설계 R3)

이관 후 2주간 일별로 비교한다.

```sql
-- monitoring DB
SELECT called_on, sum(calls) FROM brand_call_count GROUP BY called_on ORDER BY called_on DESC LIMIT 20;
SELECT called_on, sum(calls) FROM target_call_count GROUP BY called_on ORDER BY called_on DESC LIMIT 20;
```

증분이 예상(0 이하)과 다르면 설계 §5-2의 전제를 재검토한다. **이 확인의 주체를 명시해 둔다** —
배포 후 별도 세션(칩)에서 수행.

---

# C — contract (다음 릴리스)

이번 릴리스에서 **하지 않는다**. 참조 코드가 끊긴 다음 릴리스로 넘긴다.

## C1. was

- `BrandPostAssembler.assembleLegacyPending` 폴백 분기 삭제.
- `BrandDirectPostRepository`에서 `monitoring_item_id` 읽기·쓰기 제거.
- 마이그레이션 `V<UTC>__brand_direct_posts_contract.sql`:
  ```sql
  -- allow-destructive: 통합 풀이 direct 정본이 된 뒤 참조 코드를 전부 제거한 릴리스(2026-08-18 통합 E2)
  -- no-backfill: migrated_at·monitoring_item_id는 이관 진행 표식·레거시 링크일 뿐, 통합 풀에
  --              이미 정본이 있어 보정할 대상 데이터가 없다.
  ALTER TABLE app.brand_direct_posts DROP COLUMN monitoring_item_id;
  ALTER TABLE app.brand_direct_posts DROP COLUMN migrated_at;
  ```
  (가드 v2의 DROP COLUMN 짝 검사가 걸리므로 `-- no-backfill` 주석이 반드시 필요하다.)

## C2. monitoring

```sql
-- allow-destructive: DEFAULT 제거 — 롤링 창이 끝나 모든 파드가 tag_detected_at을 명시적으로 쓴다
ALTER TABLE brand_tagged_post ALTER COLUMN tag_detected_at DROP DEFAULT;
-- 선택: CHECK (tag_detected_at IS NOT NULL OR direct_registered_at IS NOT NULL) 도입 여부 판단
```

---

# X — 별도 트랙 (이번 범위 밖)

- 레거시 추적 화면·`/v1/monitoring/**` 표면을 유저 개념에서 제거하는 작업.
- tagged 게시물의 캠페인 **부착·해제 API**(`app.brand_post_campaigns`에 쓰는 사용자 경로).
- `V1BrandDirectPostService.cancel`의 무아카이브 hard delete 교정(설계 R6).
- `reasonCode` 어휘 이원화(v2 대문자 vs 레거시 소문자) 정리.

---

# 완료 시 문서 갱신 (잊지 말 것)

1. `DECISIONS.md` **맨 위**에 이번 결정 행 추가(설계 문서 링크 + 마이그레이션 파일명 + 배포 순서
   monitoring→was + FE 통지 4건).
2. `docs/tracks/MON-BT-브랜드-태그-모니터링.md` 갱신 — direct 통합 절 추가, "미결·후속"에서 해소된
   항목(창 밖 tagged 데드엔드, 겹침 direct 셰이프 고정) 취소선 처리, 신규 미결(R1~R9) 등재.
3. `docs/tracks/PP-경쟁사-계정-타입.md`의 후속 #1에서 `resolveLazyMappingBrand` 관련 절반이 해소됨을
   기록.
4. `docs/contracts/monitoring-was-contract.md` v2.11로 갱신 — §8-2 취소 의미 정정(매핑 삭제 →
   direct 표식 해제), 신규 monitoring 명령 2종, FE 통지 4건.
5. PR을 열 때 **이 계획 문서를 같은 PR에서 `docs/superpowers/plans/archive/`로 이동**하고 상태
   헤더를 `✅ 실행됨`으로 바꾼다. 설계 스펙은 영구 보존 대상이라 `specs/`에 남기되, 트랙 완결 시
   `specs/archive/`로 옮긴다.
