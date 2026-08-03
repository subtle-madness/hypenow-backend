# 업데이트 소식(제품 공지) API 구현 계획

> 상태: ✅ 구현됨
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영팀이 어드민에서 작성한 제품 업데이트 소식을 유저 대시보드 패널에 서빙하는 `/v1/notices` + `/v1/admin/notices` API를 was 모듈에 추가한다.

**Architecture:** 신규 리소스 3테이블(`app.notices`, `app.notice_items`, `app.notice_seen`) + JdbcClient 리포지토리 2개 + 컨트롤러 2개(유저/어드민) + 어드민 쓰기 검증 서비스 1개. 기존 `/v1/admin/**` 보안 게이트(SecurityConfig `hasRole("ADMIN")` + `AdminRoleFreshnessFilter` DB 재확인)를 그대로 타므로 **SecurityConfig 변경 없음**. 유저 표면은 `anyRequest().authenticated()` 기본 잠금으로 충분.

**Tech Stack:** Java 21 / Spring Boot 4.1 / JdbcClient(text block SQL) / Flyway(UTC 타임스탬프 채번) / Testcontainers 통합 테스트.

**요청서 정본:** 프론트 변경요청서(2026-08-03, 대화 원문). §6 열린 질문은 문서 기본값 채택 — 경로 `/v1/notices`, 예약 발행 **포함**(유저 목록에서 미래 건 제외, 어드민 목록 포함), 대상 전 유저 공통.

**계약 규약 정본:** `docs/contracts/monitoring-frontend-api-spec.md` §1 — envelope `{success,data,error,meta?}`, nullable 키 생략 금지(명시적 null), 타임스탬프 KST 오프셋, 목록 `meta.total`.

---

## 확정 설계 결정

| 결정 | 내용 |
|---|---|
| id 직렬화 | DB `bigserial`, JSON에서는 문자열(`String.valueOf(id)`) — `RegistrationResponse` 관례. 요청서의 `notice-v1-1-0` 슬러그는 목데이터 예시일 뿐, 서버 부여 id는 숫자 문자열로 충분(프론트는 opaque 취급) |
| `date` | 컬럼 없음. 응답 시 `published_at`의 KST 달력 날짜 파생(`KstTimestamps.toKstDate`) |
| `publishedAt` 입력 | `OffsetDateTime.parse` 시도 → 실패 시 `LocalDateTime.parse` 후 KST(+09:00) 부여. 둘 다 실패·누락 → 400 |
| `publishedAt` 출력 | `KstTimestamps.toKstIso` (KST 오프셋 ISO 8601) |
| 정렬 | `ORDER BY published_at DESC, id DESC`. date가 publishedAt의 단조 함수라 같은 날짜 연속성 자동 보장 |
| link | `link_href`/`link_label` 둘 다 null 또는 둘 다 값 — CHECK 제약. 응답에서 null이면 명시적 `null` 키(레코드 기본 동작, NON_NULL 금지) |
| PATCH | title/publishedAt 갱신 + items 전량 DELETE 후 재INSERT(전체 교체, 항목 id 재발급 허용 — 요청서 명시) |
| DELETE | hard delete + CASCADE. 대상 부재 시 404 (어드민 도구 관례 — `AdminUsersController` 참조) |
| 잘못된 path id | 숫자 아님 → 404로 접는다 (`AdminUsersController.parseId` 관례) |
| seen | 유저당 1행 upsert(`ON CONFLICT (user_id) DO UPDATE`). PUT은 받은 값 저장만(역행 가드 없음 — 요청서 "저장만 하면 됩니다"). 메서드는 요청서 계약 그대로 **PUT** + 204 |
| 검증 | title 공백/items 빈 배열/summary 공백/tag 4종 밖/link 있는데 href·label 공백/body null/publishedAt 불량 → 400 `VALIDATION_FAILED`. body 빈 문자열은 허용 |
| meta | 두 목록 응답 모두 `meta.total = data.length`(전량 반환) |
| 403/401 | 기존 필터가 낸다 — 새 코드 없음 |

## File Structure

```
was/src/main/resources/db/migration/app/V20260803120000__notices.sql   (생성)
was/src/main/java/com/celfit/was/
  notices/NoticeRepository.java          (생성 — notices+items CRUD, row 레코드 내장)
  notices/NoticeSeenRepository.java      (생성 — seen upsert/조회)
  v1/notices/NoticeResponse.java         (생성 — 응답 record + from())
  v1/notices/NoticeUpsertRequest.java    (생성 — POST/PATCH 공용 요청 record)
  v1/notices/NoticeSeenDtos.java 또는 개별 record 2개 (생성)
  v1/notices/V1NoticesController.java    (생성 — GET /v1/notices, GET·PUT /v1/notices/seen)
  v1/notices/NoticeAdminService.java     (생성 — 검증+파싱+쓰기 트랜잭션)
  v1/notices/AdminNoticesController.java (생성 — /v1/admin/notices CRUD; 기존 어드민 컨트롤러와 같은 패키지가 낫다고 판단되면 그쪽에 두되 advice 범위 com.celfit.was.v1 안에 있을 것)
was/src/test/java/com/celfit/was/
  v1/notices/AdminNoticesIntegrationTest.java (생성)
  v1/notices/V1NoticesIntegrationTest.java    (생성 — 유저 목록 + seen)
docs/tracks/<다음 트랙문자>-업데이트-소식.md   (생성 — NN 다음 자유 문자 확인 후)
DECISIONS.md                              (맨 위에 결정 1건 추가)
```

---

### Task 1: Flyway 마이그레이션 + 리포지토리

**Files:**
- Create: `was/src/main/resources/db/migration/app/V20260803120000__notices.sql`
- Create: `was/src/main/java/com/celfit/was/notices/NoticeRepository.java`
- Create: `was/src/main/java/com/celfit/was/notices/NoticeSeenRepository.java`

- [ ] **Step 1-1: 마이그레이션 작성** (버전 충돌 없는지 `ls was/src/main/resources/db/migration/app/` 먼저 확인)

```sql
-- 업데이트 소식(제품 공지) — 운영팀이 어드민에서 작성, 유저 대시보드 패널에 전 유저 공통 서빙.
-- date 컬럼은 두지 않는다: published_at의 KST 달력 날짜를 응답 시점에 파생(프론트 변경요청서 §5).
CREATE TABLE app.notices (
    id           bigserial PRIMARY KEY,
    title        text        NOT NULL,
    published_at timestamptz NOT NULL,  -- 정렬·NEW 배지·예약 발행 판정 기준
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notices_published_idx ON app.notices (published_at DESC);

CREATE TABLE app.notice_items (
    id         bigserial PRIMARY KEY,
    notice_id  bigint NOT NULL REFERENCES app.notices(id) ON DELETE CASCADE,
    position   int    NOT NULL,           -- 표시 순서(어드민 작성 순서 그대로)
    tag        text   NOT NULL CHECK (tag IN ('new','changed','improved','fixed')),
    summary    text   NOT NULL,
    body       text   NOT NULL,           -- 빈 문자열 허용(그 경우 프론트가 펼침 없는 한 줄로 그림)
    link_href  text,
    link_label text,
    CHECK ((link_href IS NULL) = (link_label IS NULL))  -- 링크는 href·label 쌍으로만
);
CREATE INDEX notice_items_notice_idx ON app.notice_items (notice_id, position);

CREATE TABLE app.notice_seen (
    user_id      bigint PRIMARY KEY REFERENCES app.users(id) ON DELETE CASCADE,
    last_seen_at timestamptz NOT NULL
);
```

- [ ] **Step 1-2: NoticeRepository** — JdbcClient, `app.` prefix, 부모-자식은 쿼리 2회 후 자바 조립(`RegistrationRepository` 관례). 핵심 시그니처:

```java
public record ItemInput(String tag, String summary, String body, String linkHref, String linkLabel) {}
public record ItemRow(long id, String tag, String summary, String body, String linkHref, String linkLabel) {}
public record NoticeRow(long id, String title, OffsetDateTime publishedAt, List<ItemRow> items) {}

public List<NoticeRow> findPublished()      // WHERE published_at <= now() ORDER BY published_at DESC, id DESC
public List<NoticeRow> findAll()            // 예약분 포함, 같은 정렬
public Optional<NoticeRow> findById(long id)
public long insert(String title, OffsetDateTime publishedAt, List<ItemInput> items)  // notices RETURNING id → items 순회 INSERT(position = index)
public boolean update(long id, String title, OffsetDateTime publishedAt, List<ItemInput> items)
    // UPDATE notices SET title=:t, published_at=:p, updated_at=now() WHERE id=:id → 0행이면 false
    // 이후 DELETE FROM notice_items WHERE notice_id=:id → 전량 재INSERT (전체 교체 계약)
public boolean delete(long id)              // DELETE FROM app.notices WHERE id=:id, 갱신 행수>0 반환 (items는 CASCADE)
```
items 일괄 조회: `WHERE notice_id IN (:ids) ORDER BY notice_id, position` (ids 비면 쿼리 생략 — IN () 오류 회피).

- [ ] **Step 1-3: NoticeSeenRepository**

```java
public Optional<OffsetDateTime> findLastSeenAt(long userId)
public void upsert(long userId, OffsetDateTime lastSeenAt)
    // INSERT INTO app.notice_seen (user_id, last_seen_at) VALUES (:u, :t)
    // ON CONFLICT (user_id) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
```

- [ ] **Step 1-4: 컴파일 확인** `./gradlew :was:compileJava` → BUILD SUCCESSFUL
- [ ] **Step 1-5: 커밋** `feat(was): 업데이트 소식 테이블·리포지토리 추가`

### Task 2: 유저 표면 — GET /v1/notices, GET·PUT /v1/notices/seen (TDD)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/notices/NoticeResponse.java` (+ NoticeItemResponse·NoticeLink — 파일 분리 여부는 기존 관례 따름)
- Create: `was/src/main/java/com/celfit/was/v1/notices/V1NoticesController.java`
- Create: seen 요청/응답 record
- Test: `was/src/test/java/com/celfit/was/v1/notices/V1NoticesIntegrationTest.java`

- [ ] **Step 2-1: 통합 테스트 먼저 작성** — `IntegrationTest` 상속 + `@AutoConfigureMockMvc`, 유저 세션은 `V1AuthTestSteps.signUp(...)`. 시나리오:
  - 발행된 소식이 publishedAt 내림차순으로, items가 position 순으로 온다. envelope(`$.success=true`)·`meta.total`·`date` KST 파생·`publishedAt` KST 오프셋 검증.
  - **미래 publishedAt 소식은 제외**된다 (DB 직접 INSERT로 미래 건 시딩).
  - link 없는 항목은 응답에 `link` 키가 **명시적 null**로 존재한다 (`jsonPath("$.data[0].items[1].link").value(nullValue())` + 키 존재 확인).
  - 빈 목록이면 `data: []`.
  - 비로그인 401.
  - seen: 최초 GET → `data.lastSeenAt = null`; PUT `{"lastSeenAt":"2026-08-03T10:05:00+09:00"}` → 204; 재GET → 저장값 KST 반영; PUT 재호출로 갱신; body 누락/불량 → 400.
- [ ] **Step 2-2: 실패 확인** `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock ./gradlew :was:test --tests "com.celfit.was.v1.notices.V1NoticesIntegrationTest"` → FAIL(404/컴파일 에러)
- [ ] **Step 2-3: 구현**

```java
public record NoticeLink(String href, String label) {}
public record NoticeItemResponse(String id, String tag, String summary, String body, NoticeLink link) {
    static NoticeItemResponse from(NoticeRepository.ItemRow row) {
        NoticeLink link = row.linkHref() == null ? null : new NoticeLink(row.linkHref(), row.linkLabel());
        return new NoticeItemResponse(String.valueOf(row.id()), row.tag(), row.summary(), row.body(), link);
    }
}
public record NoticeResponse(String id, String title, String date, String publishedAt, List<NoticeItemResponse> items) {
    public static NoticeResponse from(NoticeRepository.NoticeRow row) {
        return new NoticeResponse(String.valueOf(row.id()), row.title(),
                KstTimestamps.toKstDate(row.publishedAt()), KstTimestamps.toKstIso(row.publishedAt()),
                row.items().stream().map(NoticeItemResponse::from).toList());
    }
}
```
컨트롤러는 서비스 없이 리포지토리 직결(관례). PUT seen은 `@ResponseStatus(NO_CONTENT)`, lastSeenAt 파싱은 `OffsetDateTime.parse` 실패 시 `V1ApiException.validation(...)`. nullable 필드 Javadoc에 "계약 무결성 규칙 #1 — 명시적 null" 명기.
- [ ] **Step 2-4: 테스트 통과 확인** (같은 명령) → PASS
- [ ] **Step 2-5: 커밋** `feat(was): 유저 업데이트 소식 목록·마지막 확인 시각 API`

### Task 3: 어드민 표면 — /v1/admin/notices CRUD (TDD)

**Files:**
- Create: `NoticeUpsertRequest.java` — `record NoticeUpsertRequest(String title, String publishedAt, List<Item> items)`, `record Item(String tag, String summary, String body, LinkInput link)`, `record LinkInput(String href, String label)`
- Create: `NoticeAdminService.java` (@Service, @Transactional 쓰기)
- Create: `AdminNoticesController.java`
- Test: `AdminNoticesIntegrationTest.java`

- [ ] **Step 3-1: 통합 테스트 먼저 작성** — `AdminQueryApiIntegrationTest` 패턴(ADMIN 유저 INSERT + 로그인 쿠키). 시나리오:
  - POST 201 → 응답이 4-1 형태(id 채워짐, date 파생, items 순서 보존, link null 명시). `publishedAt: "2026-08-03T09:00"`(초·오프셋 없음)이 KST로 해석돼 `+09:00`으로 돌아온다.
  - 검증 400 4종: title 공백 / items 빈 배열 / summary 공백 / tag 밖(`"tag":"broken"`), + link.href만 있고 label 없음.
  - GET 어드민 목록: 미래 예약분 **포함**, 유저 GET /v1/notices에는 같은 건이 **제외** (교차 검증).
  - PATCH: items 2개→순서 뒤집힌 3개로 전체 교체 → 응답·재조회 모두 새 구성. 부재 id 404, 숫자 아닌 id 404.
  - DELETE 204 → 재조회 목록에서 사라짐, items도 CASCADE 삭제. 부재 id 404.
  - 일반 유저(role USER) 세션으로 POST → 403 envelope. 비로그인 → 401.
- [ ] **Step 3-2: 실패 확인** → FAIL
- [ ] **Step 3-3: 구현** — 서비스에서 검증 전량 선행(all-or-nothing, `NotificationSettingsService` 관례):

```java
private static final Set<String> TAGS = Set.of("new", "changed", "improved", "fixed");
private static final ZoneOffset KST = ZoneOffset.ofHours(9);

// validate(): title 공백 → validation("제목을 입력해 주세요.") 등 한국어 메시지.
// body는 null만 거부(빈 문자열 허용). link != null이면 href·label 모두 공백 불가.
// parsePublishedAt(): OffsetDateTime.parse 시도 → DateTimeParseException 시 LocalDateTime.parse(raw).atOffset(KST) → 둘 다 실패면 400.
```
컨트롤러: POST `@ResponseStatus(CREATED)` + 생성 결과 `ApiResponse.ok(NoticeResponse.from(...))`, GET 목록 `meta.total`, PATCH 200, DELETE `@ResponseStatus(NO_CONTENT)`. path id 파싱 실패 → `V1ApiException.notFound("소식을 찾을 수 없습니다.")`.
- [ ] **Step 3-4: 테스트 통과 확인** → PASS
- [ ] **Step 3-5: 커밋** `feat(was): 어드민 업데이트 소식 CRUD API`

### Task 4: 검증·문서·PR 준비

- [ ] **Step 4-1:** `DOCKER_HOST=... ./gradlew :was:test` 모듈 전체 → PASS (전체 `./gradlew test`는 PR 직전에만이라는 규칙이 있으나 이 변경은 was 한정이라 모듈 테스트로 충분)
- [ ] **Step 4-2:** `docs/tracks/` 최신 트랙 문자 확인 후 다음 문자로 `docs/tracks/<X>-업데이트-소식.md` 생성(요청서 요약·엔드포인트·결정·상태). DECISIONS.md 맨 위에 "공지는 /v1/notices 신설 — /v1/notifications 미확장(계약 4필드 충돌)" 결정 추가.
- [ ] **Step 4-3:** 커밋 `docs: 업데이트 소식 트랙·결정 기록`
- [ ] **Step 4-4:** push + develop 대상 PR 생성(제목 한국어, 본문에 요청서 대응표·§6 채택 기본값 명기)

## Self-Review 결과

- 요청서 4-1~4-6 전 엔드포인트가 Task 2·3에 대응. §5 데이터 모델 → Task 1. §6 기본값 채택은 헤더에 명기.
- 타입 일관성: `NoticeRepository.ItemRow/NoticeRow/ItemInput` 이름을 Task 2·3에서 동일 사용.
- 함정 반영: DOCKER_HOST 필수, 마이그레이션 UTC 채번·충돌 확인, IN () 빈 리스트 가드, NON_NULL 금지.
