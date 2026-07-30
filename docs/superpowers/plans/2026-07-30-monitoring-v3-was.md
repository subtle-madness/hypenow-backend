# 모니터링 v3 was 구현 계획

> 상태: 🟢 활성 · 실행 대기
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프론트 모니터링 v3 계약(6.25~6.33, [contracts/monitoring-frontend-api-spec.md](../../contracts/monitoring-frontend-api-spec.md))을 기존 monitoring seam 위에 was `/v1` API 9종 + 크론 2종으로 구현한다.

**Architecture:** 수집·감지는 monitoring 모듈(별도 컨테이너, 팀원 소유)이 담당하고 was는 그 위의 유저 표면이다. was는 ① app 스키마에 유저 소유 데이터(추적 행·캠페인·처리 내역·다이제스트·알림 설정)를 보관하고 ② monitoring 명령 API(등록·승인·거절·연장·해지)와 읽기 전용 조회 표면으로 target을 오케스트레이션하며 ③ 프론트 상태 6종·알림 이벤트 4종을 monitoring 원시 상태에서 **유도**한다(영속화 최소). 승인 큐는 was가 자동 approve 크론으로 대행해 프론트에는 승인 없는 자동 수집으로 보인다.

**Tech Stack:** Spring Boot 4.1 / Java 21 / JdbcClient / Flyway(app 스키마) / Testcontainers / 기존 seam(`was/monitoring` 패키지) 재사용.

---

## 전제 (실행 전 확인)

1. **PR #183(이메일 알람) 머지 선행.** V15(옵트아웃·워터마크)·mail 인프라(ResendMailSender)·크론 골격을 재사용한다. 머지가 늦어지면 이 계획의 V16이 V15가 되도록 번호를 재확인한다(**마이그레이션 번호는 머지 직전 재확인** — V18 경합 사례).
2. **monitoring P1 확장은 팀원 병행** ([contracts/monitoring-v3-extension-request.md](../../contracts/monitoring-v3-extension-request.md)). was 테스트 픽스처(`was/src/test/resources/monitoring-schema.sql`)는 P1 확장(post_meta·tracked_hidden_at·fetch_failing·sweep_run)을 **계약 유도로 선반영**해 작성하고, 팀원 실구현 확정 시 대조한다(07-29 이메일 알람과 같은 관례). P1 합류 전 개통 불가 항목: hidden/error 상태, content_issue 알림, 캡션·썸네일·게시일 실값.
3. 운영은 `MONITORING_ENABLED=false`·V13 테이블 0행(미개통) — V16의 파괴적 재구성이 안전한 근거다. 실행 전 운영 DB에서 `SELECT count(*) FROM app.monitoring_campaigns`가 0인지 확인한다.
4. 작업 워크트리: `.worktrees/monitoring-v3`, 브랜치 `feat/monitoring-v3-was`, PR은 develop 대상.

## 프론트 status ↔ monitoring 원시 상태 유도표 (전 태스크 공통 참조)

| 우선순위 | 조건 (위에서부터 첫 매치) | 프론트 status |
|---|---|---|
| 1 | `canceled_at IS NOT NULL` | `canceled_from='detecting'` → `not_uploaded`, 그 외 → `ended` |
| 2 | `target_id IS NULL` (백그라운드 등록 처리 중) | mode=url → `collecting`, mode=account → `detecting` |
| 3 | target.status=`WATCHING`, PENDING 후보 있음 | `collecting` (감지됨·자동 승인 대기) |
| 4 | target.status=`WATCHING` | `detecting` |
| 5 | target.status=`TRACKING`, `tracked_hidden_at IS NOT NULL` | `hidden` |
| 6 | target.status=`TRACKING`, `fetch_failing` | `error` |
| 7 | target.status=`TRACKING` | `tracking` |
| 8 | target.status=`EXPIRED`, `tracked_short_code IS NULL` | `not_uploaded` |
| 9 | target.status=`EXPIRED` | `ended` |
| 10 | target.status=`CANCELED` (방어 — 취소는 1에서 잡힘) | tracked 유무로 8·9와 동일 |
| 11 | target.status=`FAILED` (방어 — 등록 실패는 행 삭제로 미노출) | `error` |

`expires_at` 계산(등록·기간 변경 공통): `registered_on + tracking_days`일의 KST 자정(exclusive) = `registered_on.plusDays(trackingDays).atStartOfDay(KST)`.

---

## Phase 1 — app 스키마 V16 + 저장 계층

### Task 1: V16 마이그레이션

**Files:**
- Create: `was/src/main/resources/db/migration/app/V16__monitoring_v3.sql`
- Modify: `was/src/test/resources/monitoring-schema.sql` (P1 확장 선반영)

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- 모니터링 v3(프론트 계약 6.25~6.33) 저장 계층 재구성.
-- 전제: 기능 미개통(MONITORING_ENABLED=off, 운영 0행) — 데이터 이관 없음.
-- allow-destructive: 개통 전 빈 테이블 재구성 — V13 매핑 테이블을 v3 추적 행으로 대체
DROP TABLE app.monitoring_campaigns;
-- no-backfill: 개통 전 빈 테이블 — 롤링 창 유실분 없음

-- v3 유저 캠페인(프론트 Campaign 6.25). 이름이 라우트 키 — (user, name) 유니크가 곧 계약.
CREATE TABLE app.monitoring_campaigns (
    id          bigserial PRIMARY KEY,
    user_id     bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    name        text   NOT NULL CHECK (char_length(name) <= 40),
    description text   CHECK (char_length(description) <= 200),
    start_date  date,
    end_date    date,
    brand       text   CHECK (char_length(brand) <= 30),
    budget      bigint CHECK (budget >= 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

-- 추적 행(프론트 TrackingItem) — V13 (user,target,멱등키) 매핑을 흡수·확장.
-- target_id NULL = 백그라운드 등록 처리 중(멱등키 replay 복구 가능 상태).
CREATE TABLE app.monitoring_items (
    id                  bigserial PRIMARY KEY,
    user_id             bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    mode                text   NOT NULL CHECK (mode IN ('url', 'account')),
    registration_key    uuid   NOT NULL UNIQUE,
    target_id           bigint,
    campaign_id         bigint REFERENCES app.monitoring_campaigns(id) ON DELETE SET NULL,
    input_value         text   NOT NULL,   -- url 모드: shortcode / account 모드: 소문자 핸들
    source_url          text,              -- url 모드만: 정규화된 등록 원본 URL
    keywords            jsonb,             -- account 모드만: {"and":[],"or":[],"exclude":[]}
    tracking_days       int    NOT NULL CHECK (tracking_days BETWEEN 1 AND 90),
    registered_on       date   NOT NULL,   -- KST 등록일 — 모든 기간 계산 기준
    canceled_at         timestamptz,
    canceled_from       text   CHECK (canceled_from IN ('detecting', 'tracking', 'error')),
    started_notified_on date,              -- collection_started 다이제스트 반영일(중복 발화 방지)
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX monitoring_items_user_idx ON app.monitoring_items (user_id);
CREATE UNIQUE INDEX monitoring_items_user_target_uidx
    ON app.monitoring_items (user_id, target_id) WHERE target_id IS NOT NULL;

-- 등록 처리 내역(6.28) — 요청 1행 + 건별 결과(입력 순서 보존).
CREATE TABLE app.monitoring_registrations (
    id           bigserial PRIMARY KEY,
    user_id      bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE TABLE app.monitoring_registration_entries (
    registration_id bigint NOT NULL REFERENCES app.monitoring_registrations(id) ON DELETE CASCADE,
    seq             int    NOT NULL,
    input           text   NOT NULL,
    kind            text   NOT NULL CHECK (kind IN ('post', 'account')),
    result          text   NOT NULL CHECK (result IN ('success', 'failed', 'duplicate', 'pending')),
    reason_code     text   CHECK (reason_code IN ('invalid_format', 'not_found', 'private_account',
                                                  'share_link_unresolved', 'duplicate', 'internal_error')),
    reason          text,
    resolved_url    text,
    item_id         bigint REFERENCES app.monitoring_items(id) ON DELETE SET NULL,
    PRIMARY KEY (registration_id, seq)
);
CREATE INDEX monitoring_registrations_user_idx
    ON app.monitoring_registrations (user_id, requested_at DESC);

-- 데일리 다이제스트(6.32) — (user, date) 유니크가 하루 1건 계약.
CREATE TABLE app.monitoring_digests (
    id          bigserial PRIMARY KEY,
    user_id     bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    digest_date date   NOT NULL,
    items       jsonb  NOT NULL,   -- [{"category":"content","type":"...","summary":"...","count":N}]
    created_at  timestamptz NOT NULL DEFAULT now(),
    read_at     timestamptz,
    UNIQUE (user_id, digest_date)
);
CREATE INDEX monitoring_digests_user_idx ON app.monitoring_digests (user_id, digest_date DESC);

-- 알림 설정: V15 옵트아웃(행 없음=on)의 이벤트 어휘를 v3 4종으로 교체.
-- allow-destructive: 개통 전 빈 테이블 — 구 어휘(POST_DETECTED 등) 행 없음
ALTER TABLE app.monitoring_email_opt_outs DROP CONSTRAINT monitoring_email_opt_outs_event_type_check;
DELETE FROM app.monitoring_email_opt_outs;
ALTER TABLE app.monitoring_email_opt_outs ADD CONSTRAINT monitoring_email_opt_outs_event_type_check
    CHECK (event_type IN ('collection_started', 'collection_ended', 'metrics_private', 'content_issue'));

-- 다이제스트 생성 워터마크(9시 크론 창의 시작점). V15 alarm_state 재사용, 새 키 시드.
INSERT INTO app.monitoring_alarm_state (event_type, last_notified_at)
VALUES ('DIGEST', now())
ON CONFLICT DO NOTHING;
```

주의: V15의 CHECK 제약 이름은 머지된 실제 DDL에서 확인 후 맞춘다(익명 제약이면 `\d` 로 생성명 확인 또는 `DROP CONSTRAINT` 대신 컬럼 재정의).

- [ ] **Step 2: 테스트 픽스처에 monitoring P1 확장 선반영**

`was/src/test/resources/monitoring-schema.sql`의 target에 `tracked_hidden_at timestamptz`, `fetch_failing boolean NOT NULL DEFAULT false` 컬럼을 추가하고, `post_meta`·`post_comment`·`sweep_run` 테이블(확장 요구 문서의 DDL 그대로)을 추가한다. 파일 머리에 "P1 확장 선반영 — monitoring 실구현 확정 시 대조" 주석.

- [ ] **Step 3: 통합 테스트로 마이그레이션 적용 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.MonitoringDisabledTest"` (컨텍스트 로드 = Flyway 적용)
Expected: PASS

- [ ] **Step 4: Commit** — `feat(was): 모니터링 v3 스키마 — V13 매핑을 추적 행으로 재구성(V16)`

### Task 2: 저장 리포지토리 3종

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringItemRepository.java` (+ `MonitoringItemRow` record)
- Create: `was/src/main/java/com/celfit/was/monitoring/CampaignRepository.java` (+ `CampaignRow` record)
- Create: `was/src/main/java/com/celfit/was/monitoring/RegistrationRepository.java` (+ `RegistrationRow`, `RegistrationEntryRow` record)
- Delete: `was/src/main/java/com/celfit/was/monitoring/MonitoringCampaignMapping{,Repository}.java` (V13 대체 — 참조하는 `MonitoringCampaignService`는 Task 8에서 개조하므로 이 태스크에서는 컴파일 유지 최소 수정만)
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringItemRepositoryTest.java` 외 2종 (기존 `MonitoringCampaignMappingRepositoryTest` 관례: `IntegrationTest` 상속)

모두 기존 관용구: 생성자 주입 `JdbcClient`(monitoring용이 아니라 **app 기본 DataSource**), record 매핑, 빈 노출은 `MonitoringConfig`.

핵심 시그니처(테스트가 이 시그니처로 먼저 작성돼야 한다):

```java
public class MonitoringItemRepository {
    // 등록 1단계: 멱등키 선저장(target_id null) — 기존 insertPending 계승
    long insertPending(long userId, String mode, UUID registrationKey, Long campaignId,
            String inputValue, String sourceUrl, String keywordsJson, int trackingDays, LocalDate registeredOn);
    void confirmTarget(long itemId, long targetId);      // 2단계 확정
    void delete(long itemId);                            // 첫 수집 실패 시 행 삭제
    Optional<MonitoringItemRow> findByIdAndUser(long id, long userId);
    List<MonitoringItemRow> findByUser(long userId);     // registered_on ASC, id ASC (6.26 정렬)
    List<MonitoringItemRow> findActiveDuplicates(long userId, String mode, String inputValue);
        // canceled_at IS NULL AND (target 종결 여부는 서비스에서 target 조회로 재확인)
    void updateTrackingDays(long itemId, int trackingDays);
    void updateCampaign(long itemId, Long campaignId);   // null = 해제
    void markCanceled(long itemId, String canceledFrom, OffsetDateTime at);
    void markStartedNotified(List<Long> itemIds, LocalDate on);
    List<MonitoringItemRow> findPendingOlderThan(Duration age);  // 재기동 복구용(target_id null)
}
public class CampaignRepository {
    CampaignRow insert(long userId, String name, String description, LocalDate startDate,
            LocalDate endDate, String brand, Long budget);   // (user,name) 충돌 → DuplicateKeyException
    Optional<CampaignRow> findByIdAndUser(long id, long userId);
    Optional<CampaignRow> findByNameAndUser(String name, long userId);
    List<CampaignRow> findByUser(long userId);           // created_at ASC, id ASC
    void update(long id, ...);                            // 부분 갱신은 서비스에서 조립(6.31 null 의미론)
    void delete(long id);                                 // FK ON DELETE SET NULL이 연결 해제 담당
}
public class RegistrationRepository {
    long insert(long userId, OffsetDateTime requestedAt);
    void insertEntry(long registrationId, int seq, String input, String kind, String result,
            String reasonCode, String reason, String resolvedUrl, Long itemId);
    void updateEntryResult(long registrationId, int seq, String result,
            String reasonCode, String reason, String resolvedUrl, Long itemId);
    void markCompletedIfAllSettled(long registrationId);  -- pending 0건이면 completed_at=now()
    List<RegistrationRow> findRecentByUser(long userId, int limit);  // entries 포함 조립, 최근 50
    long countByUser(long userId);
}
```

- [ ] Step 1: 리포지토리 테스트 3종 작성(각 메서드 왕복 검증 — 기존 `MonitoringCampaignMappingRepositoryTest` 스타일) → Step 2: 실패 확인 → Step 3: 구현 → Step 4: `./gradlew :was:test --tests "com.celfit.was.monitoring.*RepositoryTest"` PASS → Step 5: Commit `feat(was): 모니터링 v3 저장 리포지토리 3종`

---

## Phase 2 — 캠페인 CRUD (6.31, monitoring 의존 없음)

### Task 3: 캠페인 이름 정규화·검증 + 서비스

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/CampaignName.java` (정규화 유틸)
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/V1CampaignService.java`
- Test: `was/src/test/java/com/celfit/was/v1/monitoring/CampaignNameTest.java` (단위)

정규화 규칙(6.25 Campaign): trim → 연속 공백 1칸 축약 → 검증(비어 있지 않음, ≤40자, `/`·`%`·개행 금지). 위반 시 `V1ApiException.validation("캠페인 이름에 쓸 수 없는 문자가 있어요.")` 계열.

서비스 동작:
- `create`: 정규화 → insert, `DuplicateKeyException` → `V1ApiException.conflict("CAMPAIGN_NAME_EXISTS", "같은 이름의 캠페인이 이미 있어요.")`
- `patch`: **키 존재 여부로 유지/해제 구분**(6.31) — 요청 DTO는 `Map<String, Object>` 대신 `JsonNullable` 없이 record + `Set<String> presentKeys`를 컨트롤러에서 전달(Jackson 3에서 `@JsonAnySetter` 관용구 또는 `Map` 수신 후 서비스에서 필드별 처리 — 기존 `V1SavedInfluencersController`가 Map 수신 관례이므로 Map 채택)
- `resolveOrCreate(userId, name)`: 등록·행 수정의 campaignName 경로 공용 — 동명 연결, 없으면 이름만으로 생성. **생성 여부를 boolean으로 반환**(6.27 응답 campaign 동봉 근거)
- `delete`: 존재·소유 검증 → delete (FK SET NULL이 소속 행 해제)
- budget 검증: 정수(소수 거부)·0 이상. startDate ≤ endDate(둘 다 있을 때만)

- [ ] Step 1: `CampaignNameTest` 작성(정규화 6케이스: trim·공백 축약·40자 경계·금지 문자 3종) → Step 2: 실패 확인 → Step 3: 구현 → Step 4: PASS → Step 5: Commit `feat(was): 캠페인 이름 정규화·서비스(6.31)`

### Task 4: 캠페인 컨트롤러 + 통합 테스트

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/V1CampaignController.java`
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/CampaignResponse.java` (record, 정적 `from(CampaignRow)`)
- Test: `was/src/test/java/com/celfit/was/v1/monitoring/V1CampaignControllerTest.java` (@WebMvcTest — 기존 `V1SavedContentsControllerTest` 관례: `user(principal())`, `csrf()`, `V1ExceptionAdvice`·`SecurityConfig` Import)

계약 요점(테스트 케이스로 전부 고정):
- `GET /v1/monitoring/campaigns` → 200, createdAt ASC·id ASC, `meta.total == data.length`
- `POST` → 201 Campaign / 이름 중복 409 CAMPAIGN_NAME_EXISTS / name 누락·금지 문자 400
- `PATCH /{id}` → 200, 키 없음=유지·null=해제 구분 케이스, 타 유저 소유 404
- `DELETE /{id}` → 204, 소속 행 campaign_id NULL 확인(통합 테스트)
- **직렬화: nullable 필드 명시적 null** — `description: null`이 응답 JSON에 키로 존재하는지 단언(계약 무결성 #1). `budget`은 응답에서 number(원 단위) 그대로
- `createdAt`은 KST 오프셋(`+09:00`) 직렬화 — `CampaignResponse.from`에서 `OffsetDateTime.atZoneSameInstant(KST)` 변환

id 직렬화: `String.valueOf(row.id())` — 프론트 계약 "ID는 문자열".

- [ ] Step 1: @WebMvcTest 작성(위 케이스) → Step 2: 실패 → Step 3: 컨트롤러 구현 → Step 4: PASS + `:was:test` 전체 → Step 5: Commit `feat(was): 캠페인 CRUD API(6.31)`

---

## Phase 3 — 알림 설정 (6.33)

### Task 5: 설정 조회·패치

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/V1NotificationSettingsController.java`
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/NotificationSettingsService.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringAlarmRepository.java` (#183의 옵트아웃 조회에 유저별 조회·토글 추가)
- Test: `was/src/test/java/com/celfit/was/v1/monitoring/V1NotificationSettingsControllerTest.java`

저장 모델 = V15 옵트아웃(행 없음=on) 그대로. GET은 **4종 키 완전체**를 항상 조립(옵트아웃 행이 있는 유형만 false). PATCH는 부분 객체 머지: `email:false` → INSERT ON CONFLICT DO NOTHING, `email:true` → DELETE. 알 수 없는 이벤트 키·`content` 밖 카테고리 → 400 VALIDATION_FAILED.

응답 형태(6.33 예시 그대로 — `data.content.<event>.email`):

```java
public record NotificationSettingsResponse(Map<String, Map<String, EmailToggle>> content) { ... }
// 순서 고정을 위해 LinkedHashMap: collection_started → collection_ended → metrics_private → content_issue
```

- [ ] Step 1: 컨트롤러 테스트(GET 완전체 4키·기본 전부 true / PATCH off→on 왕복 / 미지 키 400) → Step 2: 실패 → Step 3: 구현 → Step 4: PASS → Step 5: Commit `feat(was): 알림 설정 API(6.33) — V15 옵트아웃 재사용`

---

## Phase 4 — 등록 (6.27) + 처리 내역 (6.28)

### Task 6: 입력 파서 (URL·핸들 정규화)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/MonitoringInput.java` (파서·검증, 순수 함수)
- Test: `was/src/test/java/com/celfit/was/v1/monitoring/MonitoringInputTest.java` (단위)

```java
public sealed interface MonitoringInput {
    record Post(String shortCode, String canonicalUrl) implements MonitoringInput {}
    record ShareLink(String originalUrl) implements MonitoringInput {}   // 해소는 실행기에서
    record Account(String handle) implements MonitoringInput {}          // 소문자 정규화 완료값
    record Invalid(String input, String reasonCode, String reason) implements MonitoringInput {}

    static MonitoringInput parsePost(String url);    // {p|reel|reels}/{shortcode} → Post, share/ → ShareLink, 그 외 Invalid(invalid_format)
    static MonitoringInput parseAccount(String h);   // ^[a-z0-9._]{1,30}$ (소문자 변환 후 검사)
}
```

shortcode 추출: `instagram.com/(?:[^/]+/)?(?:p|reel|reels)/([A-Za-z0-9_-]+)` — 프로필 경유 주소 허용. canonical: `https://www.instagram.com/{p|reel}/{code}/` (reels→reel 접기, 중복 판정은 shortcode).

- [ ] Step 1: 파서 테스트(정상 3형·프로필 경유·share·대문자 핸들 정규화·invalid 4케이스) → Step 2: 실패 → Step 3: 구현 → Step 4: PASS → Step 5: Commit `feat(was): 모니터링 등록 입력 파서`

### Task 7: 등록 접수 서비스 + 컨트롤러 (동기 구간)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/V1MonitoringRegistrationService.java`
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/V1MonitoringItemsController.java` (POST만 이 태스크)
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/RegisterItemsRequest.java` (record)
- Test: `was/src/test/java/com/celfit/was/v1/monitoring/V1MonitoringItemsControllerTest.java` (POST 검증 케이스), `V1MonitoringRegistrationServiceTest.java` (IntegrationTest)

동기 구간 순서(멱등 2단계의 1단계까지):
1. 구조 검증 → 400 VALIDATION_FAILED: posts·accounts 모두 빈 배열 / 합산 >100 / trackingDays ∉ [1,90] / accounts 있는데 keywords 규칙 위반(and·or 합 0, 배열당 >5) / campaignId·campaignName 동시 전달. **posts 전용 등록의 빈 keywords는 통과**
2. campaignName → `resolveOrCreate` (생성 시 응답 `campaign` 동봉) / campaignId → 소유 검증(부재 404)
3. registration 행 + 입력 순서대로 entry 생성
4. 건별: 파싱 실패 → entry `failed`(invalid_format) / 중복(같은 유저 진행 중 행 — `findActiveDuplicates` + target 종결 여부 재확인, **error 상태 포함·종결 3종 제외**, 핸들 중복은 account 모드 행만) → entry `duplicate` / 통과 → `monitoring_items` pending 행(insertPending) + entry `pending`
5. 백그라운드 실행기에 registrationId 제출(Task 8), **201 즉시 응답**: `{registrationId, items[], campaign?}` — items는 pending 행의 TrackingItem 조립(url→collecting, account→detecting; 어셈블러는 Task 10에서 완성되므로 이 태스크에서는 pending 상태 전용 최소 조립)

같은 요청 안의 상호 중복(posts에 같은 shortcode 2번)도 뒤 항목을 duplicate 처리.

- [ ] Step 1: 컨트롤러 검증 테스트(400 케이스 전종·201 접수·campaign 동봉) + 서비스 통합 테스트(중복 규칙: error 포함·종결 제외·account 모드 한정) → Step 2: 실패 → Step 3: 구현 → Step 4: PASS → Step 5: Commit `feat(was): 모니터링 등록 접수(6.27 동기 구간)`

### Task 8: 백그라운드 등록 실행기 (비동기 구간 + seam 개조)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/RegistrationExecutor.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringCampaignService.java` → `MonitoringTargetService.java`로 개명·개조(매핑 리포지토리를 `MonitoringItemRepository`로 교체, 멱등 2단계 계승 — insertPending은 이제 Task 7 동기 구간에서 수행되므로 `completeRegistration(itemId, key, request)` 형태로 분리)
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringConfig.java` (빈 배선: 실행기 + `ThreadPoolTaskExecutor`(bounded, 스레드 2) — monitoring.enabled 조건부는 기존 관례 유지)
- Test: `was/src/test/java/com/celfit/was/v1/monitoring/RegistrationExecutorTest.java` (MockRestServiceServer — **willThrow().willReturn() 체이닝 함정 주의**, 기존 관례)

건별 처리(제출된 registrationId의 pending entry 순회):
- Post: `client.register(RegisterRequest.post(key, shortCode, expiresAt))` — 성공 → `confirmTarget` + entry `success`(itemId) / `MonitoringApiException` → 행 삭제 + entry `failed`(코드 매핑: SUBJECT_NOT_FOUND→not_found, PRIVATE_ACCOUNT→private_account, VALIDATION→invalid_format, 그 외→internal_error) / `MonitoringUnavailableException`(재시도 1회 후에도) → 행 유지(pending) — 복구 크론이 같은 키로 replay
- ShareLink: HTTP 리다이렉트 해소(JDK HttpClient, 3xx 추적 최대 3회, 타임아웃 5s) → shortcode 추출 성공 시 Post 경로(+entry.resolvedUrl 기록), 실패 → 행 삭제 + `failed`(share_link_unresolved)
- Account: `RegisterRequest.account(key, handle, keywordRule, expiresAt)` — keywords.or → 계약 `any`로 매핑. 성공/실패 처리 동일
- 전 entry 종결 시 `markCompletedIfAllSettled`
- expiresAt = 유도표 아래 공식. `registered_on`은 접수 시각의 KST 날짜

재기동 복구: `findPendingOlderThan(5분)` 행을 자동 승인 크론(Task 12)이 같은 registrationKey로 replay(멱등 — monitoring이 200으로 기존 target 반환).

- [ ] Step 1: 실행기 테스트(성공 confirm / API 실패 삭제+코드 매핑 / 전송 실패 pending 유지 / share 해소) → Step 2: 실패 → Step 3: 구현(+ seam 개명 리팩터링, 기존 seam 테스트 갱신) → Step 4: `:was:test` 전체 PASS → Step 5: Commit `feat(was): 등록 백그라운드 실행기 + seam v3 개조`

### Task 9: 처리 내역 조회 (6.28)

**Files:**
- Modify: `V1MonitoringItemsController.java` 아님 — Create: `was/src/main/java/com/celfit/was/v1/monitoring/V1RegistrationsController.java`
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/RegistrationResponse.java`
- Test: `V1RegistrationsControllerTest.java`

`GET /v1/monitoring/registrations` → 최근 50건(요청 시각 DESC), `meta.total` = 전체 건수, entries는 **seq 순서 그대로**, nullable(completedAt·reason·reasonCode·resolvedUrl·itemId) 명시적 null 단언 테스트.

- [ ] Step 1: 테스트 → Step 2: 실패 → Step 3: 구현 → Step 4: PASS → Step 5: Commit `feat(was): 등록 처리 내역 API(6.28)`

---

## Phase 5 — 목록 조회 (6.26): TrackingItem 어셈블러

### Task 10: 조회 리포지토리 확장 + 어셈블러

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringReadRepository.java` — 추가 조회: `findTargetsByIds`, `findPostMeta(shortCodes)`, `findSnapshots(shortCode, fromDate, toDate)`, `findComments(shortCode, limit)`, `findLatestProfile(username)`, `findPendingCandidates(targetIds)`, `lastSuccessfulSweepAt()` (sweep_run)
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/TrackingItemAssembler.java`
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/TrackingItemResponse.java` (+ TrackedPostResponse, SnapshotResponse, PostCommentResponse record)
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/AuthorMask.java` (댓글 작성자 마스킹)
- Test: `TrackingItemAssemblerTest.java` (IntegrationTest — 픽스처 시드 조합별 상태·필드 단언), `AuthorMaskTest.java`

어셈블러 규칙(계약 6.25·6.26 전부):
- **상태 유도표** 그대로 구현(위 공통 표) — 표의 행마다 테스트 1개
- handle: `target.username` 소문자(빈 문자열 규칙: target 미확정 url 모드만), displayName: 계정 메타 부재 동안 handle 동일값, profileImageUrl/lastUploadedAt: P2 부재 동안 null, followers: `v_target_overview` 대신 개별 조회(뷰 조인 금지 주의)
- post 객체: post_meta + snapshots + comments 조립. **snapshots 창**: `captured_on ≥ registered_on`(소급 금지) AND `captured_on ≤ lastCollectedAt의 날짜`(워터마크 — 부분 수집 배제). account 모드는 `tracked_since` 날짜 이후
- 지표 매트릭스 방어: content_type=FEED면 views·shares·reposts를 **null로 강제**(0 왜곡 방지 — monitoring이 값을 줘도 계약 우선)
- 댓글: 최신순 상한 8, 필드 결손(body·like_count·commented_at null) 행은 **통째로 제외**, author는 `AuthorMask.mask("glowdeep_92") → "gl***_92"` (앞 2자 + `***` + 마지막 `_` 뒤 보존 규칙이 없으므로 규칙 확정: 앞 2자 유지 + 나머지 `***` + 끝 2자 유지, 4자 이하면 첫 자 + `***`), reply: owner_reply_text → `{text}` 
- matchedKeywords: detected_candidate.matched_keywords(P2) 부재 동안 — was가 keywords와 caption으로 재계산(대소문자 무시 부분일치), url 모드는 빈 배열
- nextCheckAt: detecting·collecting만 — 다음 02:00 KST(등록 처리 중이면 몇 분 후 now+5m)
- campaignId·campaignName 짝 불변식, id 문자열 변환
- `meta.lastCollectedAt` = `sweep_run` 최근 성공 completed_at(KST 오프셋, 없으면 null), `meta.today` = 서버 KST 날짜, `meta.total`

- [ ] Step 1: 어셈블러 테스트(상태 유도 11행 + 스냅샷 창 + FEED null 강제 + 댓글 마스킹·결손 제외 + 짝 불변식) → Step 2: 실패 → Step 3: 구현 → Step 4: PASS → Step 5: Commit `feat(was): TrackingItem 어셈블러 — 상태 유도·스냅샷 창·마스킹`

### Task 11: GET /v1/monitoring/items 컨트롤러

**Files:**
- Modify: `V1MonitoringItemsController.java` (GET 추가)
- Test: `V1MonitoringItemsControllerTest.java` (GET — 정렬·meta 3종·nullable 명시 null·KST 오프셋 직렬화)

- [ ] Step 1~5 (TDD 사이클 + Commit `feat(was): 모니터링 목록 조회(6.26)`)

---

## Phase 6 — 행 수정·취소 (6.29 / 6.30)

### Task 12-a: PATCH /v1/monitoring/items/{itemId}

**Files:** Modify: `V1MonitoringItemsController.java`, Create: `V1MonitoringItemService.java`, Test 확장

- trackingDays: [1,90] + 종료일(`registered_on + days`)이 오늘(KST) 이후 + 진행 중 상태만(유도 상태 ∈ {collecting, detecting, tracking, error}) → monitoring `extend(targetId, 새 expiresAt)` 호출(target 미확정 pending이면 app만 갱신 — 실행기가 새 expiresAt 사용) → app 갱신
- campaignName/campaignId: 6.29 그대로 — 모든 상태 허용, null=해제, 새 캠페인 생성 시 응답 `campaign` 동봉, 동시 전달 400
- 응답: 수정된 TrackingItem(어셈블러 재사용)

### Task 12-b: POST /v1/monitoring/items/{itemId}/cancel

- 허용 상태(유도) ∈ {detecting, tracking, error} — 위반 400 VALIDATION_FAILED(message에 현재 상태), 404(타 유저·부재)
- monitoring `cancel(targetId)` 성공 후 `markCanceled(itemId, 유도상태, now)` — detecting→not_uploaded, tracking·error→ended 매핑은 조회 시 유도표 1행이 담당
- **기존 `cancelAndDelete`와 달리 행을 지우지 않는다**(v3는 종결 상태로 영구 노출)

- [ ] 각각 Step 1(테스트: 검증·상태 게이트·monitoring 호출 검증 MockRestServiceServer) → 구현 → PASS → Commit `feat(was): 행 수정·취소(6.29/6.30)`

---

## Phase 7 — 크론 2종 + 알림 (6.32)

### Task 13: 자동 승인 크론 (02:30 KST)

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/AutoApproveJob.java`
- Modify: `MonitoringConfig.java` (크론 배선 — #183의 `monitoring.alarm.cron` 프로퍼티 관례: `monitoring.auto-approve.cron`, 기본 `0 30 2 * * *`, 테스트는 `-`로 봉인)
- Test: `AutoApproveJobTest.java`

동작:
1. PENDING 후보 조회(활성 target만 — 계약 §3 쿼리 관례)
2. 후보별 소유 유저 확인(monitoring_items의 target_id 매핑) → **같은 유저의 다른 진행 중 행이 이미 추적하는 shortcode면 `reject`**(이중 추적 방지 + 재제안 금지), 아니면 `approve`(즉시 첫 수집 → TRACKING)
3. 실패(Unavailable)는 로그 후 다음 크론에서 재시도(후보가 PENDING으로 남음)
4. pending 등록 행 복구 replay(Task 8의 `findPendingOlderThan`)도 이 잡 앞부분에서 수행

- [ ] Step 1: 테스트(approve 경로 / 같은 유저 중복 → reject / 장애 시 잔존) → 구현 → PASS → Commit `feat(was): 자동 승인 크론 — 승인 큐를 서버 대행으로 흡수`

### Task 14: 다이제스트 생성·발송 크론 (09:00 KST) — #183 알람 잡 대체

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/DigestJob.java`, `DigestEventDetector.java`
- Modify: #183의 `MonitoringAlarmJob` 제거·`MonitoringAlarmRepository` 개조, `MonitoringConfig.java`
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/DigestEmailComposer.java` (문안 — #183 Composer 격리 관례 계승)
- Test: `DigestJobTest.java`, `DigestEventDetectorTest.java`

이벤트 유도(창 = `alarm_state['DIGEST'].last_notified_at` ~ now, 유저별 집계):
- `collection_started`: target TRACKING 전환(`tracked_since` ∈ 창) & `started_notified_on IS NULL` → count, 반영 후 `markStartedNotified`
- `collection_ended`: target EXPIRED 전환(`closed_at` ∈ 창) & `canceled_at IS NULL`(취소 제외)
- `metrics_private`: 추적 중 게시물의 최신 스냅샷에서 직전 값 존재 지표가 null 전환(최신 2행 비교, 전 지표 소실은 제외 — content_issue로) — FEED 상시 null 지표는 비교 모수에서 제외
- `content_issue`: `tracked_hidden_at` ∈ 창 OR `fetch_failing` false→true 전환(전환 시각 컬럼이 없으므로 P1 확장에 `fetch_failing_since` 필요 여부를 구현 중 재확인 — 없으면 "현재 fetch_failing이고 직전 다이제스트에 미포함" 판정을 was 기록으로 유지)

생성: 이벤트 있는 유저만 `monitoring_digests` upsert(유니크 충돌 시 스킵 — 재실행 안전), items 순서는 유형 표 나열 순서, summary는 건수 미포함 고정 문구 4종. 생성 성공 후 워터마크 전진(부분 실패 시 유지 — 중복>유실, #183 계약 테스트 관례 계승).

발송: 인앱 생성과 분리 — 옵트아웃 아닌 유형만 담아 이메일 1통(`MailSender` — #183 인프라), 포함 0건이면 미발송.

지연 계약: 크론 시각에 `sweep_run` 최근 성공이 오늘 02:00 이후가 아니면 스킵하고 **10분 간격 재시도 크론**(같은 잡이 `@Scheduled` 2개: 09:00 정시 + 09:10~23:50 10분 간격 가드 — 이미 오늘 다이제스트가 생성됐으면 no-op) — "늦게라도 그날 발송".

- [ ] Step 1: 디텍터 단위 테스트(이벤트 4종 발생·비발생 경계 각 2케이스) + 잡 테스트(0건 유저 미생성 / 재실행 중복 없음 / 워터마크 부분 실패 유지 / 늦은 배치 가드) → 구현 → PASS → Commit `feat(was): 다이제스트 생성·발송 크론(6.32) — #183 알람 잡 대체`

### Task 15: 알림 API (6.32)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/monitoring/V1NotificationsController.java` (+ DigestResponse record)
- Create: `was/src/main/java/com/celfit/was/monitoring/DigestRepository.java`
- Test: `V1NotificationsControllerTest.java`

- `GET /v1/notifications`: date DESC 최대 30건, `meta.total`=전체, **readAt 명시적 null 단언**(계약 무결성 #1의 그 사례)
- `POST /v1/notifications/read`: `{ids}` 또는 `{all:true}`(전체 — 30건 창 제한 없음), 멱등(부재·타유저·기읽음 무시), 204. 둘 다 없으면 400

- [ ] Step 1~5 TDD + Commit `feat(was): 알림 다이제스트 API(6.32)`

---

## Phase 8 — 수명주기·마무리

### Task 16: 탈퇴 시 cancel 루프

**Files:**
- Modify: 탈퇴 서비스(기존 `deleteAccount` 경로 — `was/src/main/java/com/celfit/was/v1/me/` 쪽, 구현 시 정확 위치 확인)
- Test: 탈퇴 통합 테스트 확장

탈퇴 직전 유저의 진행 중 행(target 확정 & 미종결)에 monitoring `cancel(targetId)` 루프 — 실패해도 탈퇴는 진행(V13 CASCADE 의도 계승: 고아 target은 expires_at 자연 만료 유계, 로그만). app 데이터 5종은 기존 FK CASCADE가 삭제.

- [ ] TDD + Commit `feat(was): 탈퇴 시 모니터링 해지 루프`

### Task 17: 문서·배선 마무리

- [ ] `ARCHITECTURE.md` §5 트랙 추가(문자: **X 다음 — Y**로 등록, W까지 사용 중)·§7 결정 기록(승인 자동 대행, V13 재구성, 상태 유도 설계)
- [ ] `docs/contracts/monitoring-was-contract.md`에 소비자 노트 추가(자동 approve 크론 02:30 전제, sweep_run 의존)
- [ ] `.env.example`·compose의 크론 프로퍼티 노출(기본값 코드에 있으므로 필수 아님 — README §13-5 개통 절차에 v3 항목 추가)
- [ ] `./gradlew test` 전체 PASS 확인 → Commit `docs: 모니터링 v3 트랙 반영`
- [ ] PR 생성(develop 대상): 계약 문서 2종 + 구현 전체. 본문에 P1 확장 의존(개통 게이트)과 프론트 배선 순서 명시

---

## Self-Review 결과 (계획 작성 시점)

- **스펙 커버리지**: 6.26~6.33 전 엔드포인트 → Task 3~15. 6.25 규약(상태 유도·지표 매트릭스·직렬화·무결성 5규칙) → Task 10·각 컨트롤러 테스트. 미커버 확인: `meta.today`(Task 10에 포함), 등록 응답 campaign 동봉(Task 7·12-a), 늦은 배치 재시도(Task 14).
- **의도적 제외(스코프 밖)**: 4절 25(보존 기간 — 미결정, 무기한 유지)·32(비교 뷰 — 미연결 폐기 대기)·33·34·35(CDN 프록시), D15 법무. 모두 미결정 항목으로 사용자 몫.
- **알려진 리스크**: ① `fetch_failing` 전환 시각 부재 시 content_issue 중복 발화 — Task 14 구현 중 P1 확장에 `fetch_failing_since` 추가 요청 여부 결정 ② 테스트 픽스처와 monitoring 실구현의 표류 — 개통 전 대조 필수 ③ V15 CHECK 제약 이름 — 머지 후 실 DDL 확인.
