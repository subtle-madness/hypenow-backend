# 모니터링 알람 모듈 + 승인 플로우 제거 (PR①) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 구현됨 (2026-07-30, feat/monitoring-alarm-module)
> 스펙: [specs/2026-07-30-monitoring-alarm-module-design.md](../specs/2026-07-30-monitoring-alarm-module-design.md)
> 계약 정본: [docs/contracts/monitoring-was-contract.md](../../contracts/monitoring-was-contract.md) — 이 트랙에서 **v2.0**으로 개정
> 작업 위치: 워크트리 `.worktrees/monitoring-alarm`, 브랜치 `feat/monitoring-alarm-module`

**Goal:** 스펙 **PR① 전체** — monitoring 개편(target.user_id·승인 플로우 제거·첫 감지 자동 수집·일시 오류 당일 재시도) + 알람 모듈(`alarm_event` 대장·적재 5지점·발송 크론) + app 옵트아웃 테이블(was Flyway V15, was 코드 무변경) + 계약 v2.0 개정. **PR②(was 정렬 — 명령 클라이언트 approve/reject 제거·userId 전달)는 범위 밖.**

**Architecture:** 알람은 monitoring 소유. 이벤트는 **id 기반 대장**(`alarm_event`)에 적재하고 발송 크론이 행 단위로 종결한다 — 워터마크 없음(순서·유실 문제 원천 제거). 수신자 해석은 analysis DB `app` 스키마를 **읽기 전용 롤 `alarm_reader`의 별도 DataSource**로 조회한다(수동 조립·지연 초기화 — 자동구성 DataSource는 monitoring DB 하나뿐이므로 두 번째를 빈으로 노출하지 않는다). 승인 플로우는 제거하고 스윕이 첫 감지 1건을 바로 TRACKING으로 전환한다(캠페인:추적 게시물 = 1:1 유지).

**Tech Stack:** Spring Boot 4.1 (Java 21), **JdbcTemplate**(was의 JdbcClient 아님 — monitoring `store` 관용구), Flyway(monitoring DB `db/migration`, was app 스키마 `db/migration/app`), Jackson 3(`tools.jackson.databind.json.JsonMapper`), RestClient + `JdkClientHttpRequestFactory`(Resend), Testcontainers(`TestDb` 싱글턴 + `resetAndMigrate`), fake `HikerHttp`·fake `MailSender`(Mockito 아님 — monitoring 테스트 관례), AssertJ.

**컨벤션 주의:**
- 주석·로그·테스트 메서드명·커밋 메시지는 **한국어**. 들여쓰기는 **탭**. DTO는 record.
- 테스트 실행 env(필수 — colima):
  ```
  DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
  TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  ./gradlew :monitoring:test
  ```
  (`:was:test`도 동일 prefix 필요.) 이하 모든 Run 줄은 이 prefix를 생략하지 않는다.
- **expand-contract**: 이번 PR의 마이그레이션은 `ADD`·`CREATE`만(뷰는 DROP+CREATE — 뷰는 CI 가드 패턴 대상 밖이며 monitoring은 롤링 배포가 아니다. CI `migration-guard`는 `was/.../db/migration/*.sql`과 analytics만 스캔하므로 monitoring V3는 검사 대상이 아니고, was V15는 `CREATE TABLE`뿐이라 통과한다).
- 커밋 메시지 끝에 트레일러:
  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  ```
- **커밋만 하고 push·PR은 하지 않는다** — 마지막 Task에서 superpowers:finishing-a-development-branch로.

---

## 파일 구조 (전체 조감)

```
monitoring/src/main/resources/
  db/migration/V3__user_id_and_alarm_event.sql            ← Task 1  ADD user_id + alarm_event + 뷰 개정
  application.yml                                          ← Task 3·5  재시도·알람 프로퍼티, 스케줄러 풀

monitoring/src/main/java/com/celfit/monitoring/
  store/
    TargetRepository.java        ← Task 1(insert userId)·4(expireOverdue RETURNING, findTrackingOwners)
    TargetRow.java               ← Task 1  userId 추가
    SnapshotRepository.java      ← Task 4  findLatestPostBefore
    ExpiredTarget.java           ← Task 4  만료 RETURNING 1행
    TargetOwner.java             ← Task 4  추적 게시물 소유 캠페인(수신자 해석)
    PostMetrics.java             ← Task 4  직전 스냅샷 지표 6종
    CandidateRepository.java     ← Task 2  신규 적재 중단(호출자 없음 — 테이블 DROP은 다음 릴리스)
  web/
    RegisterRequest.java         ← Task 1  userId 필수
    TargetController.java        ← Task 2  approve·reject 엔드포인트 삭제
    ApproveResponse.java         ← Task 2  삭제
    RejectResponse.java          ← Task 2  삭제
    ApiExceptionHandler.java     ← Task 2  CANDIDATE_NOT_FOUND 핸들러 삭제
  service/
    RegisterCommand.java         ← Task 1  userId
    RegistrationService.java     ← Task 1(검증·insert)·4(POST 등록 즉시 레인 알람)
    TargetCommandService.java    ← Task 2  approve·reject 삭제(연장·해지만 남음)
    CandidateNotFoundException.java ← Task 2  삭제
    DailySweepJob.java           ← Task 2(자동 전환)·3(재시도 라운드)·4(알람 3지점)
    CollectService.java          ← Task 4  (변경 없음 — PostInfo가 신뢰 플래그를 운반)
    SnapshotWriter.java          ← Task 4  upsert 직전 METRICS_HIDDEN 비교
  hiker/
    HikerProperties.java         ← Task 3  max-retries·retry-backoff
    JdkHikerHttp.java            ← Task 3  일시 오류 백오프 재시도
    HikerClient.java             ← Task 4  clips 보강 성공 여부 전파
    PostInfo.java                ← Task 4  viewsTrusted
  alarm/                          ← Task 4·5 (신설 패키지)
    AlarmEventType.java          ← Task 4  이벤트 4종
    AlarmEmailStatus.java        ← Task 4  발송 상태
    AlarmEvent.java              ← Task 4  대장 1행
    AlarmEventRepository.java    ← Task 4  INSERT·due 조회·상태 갱신
    DispatchLane.java            ← Task 4  즉시 / 당일 09:00 KST
    AlarmRecorder.java           ← Task 4  적재 편의 + METRICS_HIDDEN 오탐 규칙
    AlarmRecipientReader.java    ← Task 5  app 읽기 전용(지연 초기화 DataSource)
    AlarmMailComposer.java       ← Task 5  임시 문안
    AlarmDispatchJob.java        ← Task 5  5분 틱 발송
    AlarmDispatchScheduler.java  ← Task 5  크론 배선(기본 "-")
    AlarmConfig.java             ← Task 5  Clock 빈
  mail/                           ← Task 5 (신설 패키지 — was 07-19 관용구 이식)
    MailSender.java / LoggingMailSender.java / ResendMailSender.java
    MailSendException.java / MailConfig.java

monitoring/src/test/java/com/celfit/monitoring/
  testsupport/TestDb.java        ← Task 5  app 스키마 픽스처
  store/StoreTest.java           ← Task 1·4
  store/ReadSurfaceTest.java     ← Task 1
  MigrationTest.java             ← Task 1
  web/RegistrationApiTest.java   ← Task 1·2·4
  web/CommandApiTest.java        ← Task 2  승인·기각 테스트 삭제 + 404 확인
  service/DailySweepJobTest.java ← Task 2·3·4
  service/SweepSchedulerTest.java← Task 3  생성자 arity
  service/SnapshotWriterAlarmTest.java ← Task 4 (신규)
  hiker/JdkHikerHttpTest.java    ← Task 3
  hiker/HikerClientTest.java     ← Task 4  PostInfo arity
  alarm/DispatchLaneTest.java            ← Task 4 (신규)
  alarm/AlarmRecorderTest.java           ← Task 4 (신규)
  alarm/AlarmMailComposerTest.java       ← Task 5 (신규)
  alarm/AlarmDispatchJobTest.java        ← Task 5 (신규)
  alarm/AlarmDispatchSchedulerTest.java  ← Task 5 (신규)

was/src/main/resources/db/migration/app/V15__monitoring_email_opt_outs.sql  ← Task 6 (was 코드 무변경)

docs/contracts/monitoring-was-contract.md   ← Task 7  v2.0
deploy/README.md · deploy/compose.yaml · deploy/compose.test.yaml · deploy/.env.example  ← Task 7
ARCHITECTURE.md                             ← Task 7  §5·§7
```

---

### Task 1: V3 마이그레이션 + `target.user_id` 관통

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V3__user_id_and_alarm_event.sql`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TargetRow.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TargetRepository.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/web/RegisterRequest.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/RegisterCommand.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/RegistrationService.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/MigrationTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/StoreTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/ReadSurfaceTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/web/RegistrationApiTest.java`
- Test(컴파일 정렬만): `monitoring/src/test/java/com/celfit/monitoring/web/CommandApiTest.java`, `monitoring/src/test/java/com/celfit/monitoring/service/DailySweepJobTest.java`

- [ ] **Step 1: 마이그레이션 작성** (Flyway가 테스트 부팅·`resetAndMigrate`에서 자동 적용되므로 코드보다 먼저)

`monitoring/src/main/resources/db/migration/V3__user_id_and_alarm_event.sql`:

```sql
-- 알람 소유가 monitoring으로 이동(스펙 2026-07-30) — 수신자 해석에 필요한 user_id를 캠페인에 싣고,
-- 알람 이벤트 대장을 신설한다. 승인 플로우 제거로 v_target_overview의 후보 수 컬럼은 의미를 잃었다.

-- was 유저의 논리 참조 (크로스 DB — FK 금지, saved_influencers.handle 관용구).
-- nullable(expand 단계): 기존 운영 행은 null로 남고 알람 적재에서 제외된다(수신자 불명).
-- SET NOT NULL 승격은 백필 런북 실행 후 다음 릴리스에서 판단한다.
ALTER TABLE target ADD COLUMN user_id bigint;

-- 지표 비공개 알람이 "이 게시물을 추적 중인 캠페인"을 역방향으로 찾는다 — 스윕이 게시물마다 도는 조회라
-- 인덱스가 없으면 계정당 12건 × 캠페인 전수 스캔이 된다.
CREATE INDEX idx_target_tracked_short_code ON target (tracked_short_code)
    WHERE tracked_short_code IS NOT NULL;

-- 알람 이벤트 대장 — 발송 여부와 무관하게 모든 이벤트가 남는다(앱 내 알림·히스토리의 단일 원천).
-- id 기반이라 워터마크가 없다: 발송 실패는 행 단위 FAILED로 남고 다음 틱이 그 행만 다시 집는다.
CREATE TABLE alarm_event (
    id             bigserial   PRIMARY KEY,
    target_id      bigint      NOT NULL,   -- 논리 참조(target 행은 불멸이라 조인 안전 — FK를 걸지 않는 건 대장 보존이 목적)
    user_id        bigint      NOT NULL,   -- 수신자 (was 유저 논리 참조)
    event_type     text        NOT NULL CHECK (event_type IN
                   ('COLLECTION_STARTED','COLLECTION_ENDED','METRICS_HIDDEN','CONTENT_UNAVAILABLE')),
    payload        jsonb       NOT NULL,   -- 문안 재료: username·shortCode·상세(숨은 지표 목록 등)
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    dispatch_after timestamptz NOT NULL,   -- 발송 레인: 즉시(=occurred_at) 또는 적재 당일 09:00 KST
    -- SKIPPED_NO_RECIPIENT: 유저 삭제·이메일 부재 — 재시도가 무의미한 종결(FAILED로 두면 매 틱 재시도한다)
    email_status   text        NOT NULL DEFAULT 'PENDING' CHECK (email_status IN
                   ('PENDING','SENT','SKIPPED_OPTOUT','SKIPPED_NO_RECIPIENT','FAILED')),
    -- 발송 시도 횟수(성공·실패 무관). FAILED는 다음 틱에 다시 집히므로 상한이 없으면 영구 실패
    -- 수신자 하나가 5분마다 무한히 Resend를 때린다 — 상한(기본 5) 도달 행은 due 조회에서 자연히 빠진다.
    email_attempts smallint    NOT NULL DEFAULT 0,
    email_sent_at  timestamptz
);

-- 발송 대상 부분 인덱스 — FAILED도 다음 틱 재시도 대상이라 함께 담는다.
-- PENDING만 담으면 FAILED 재시도 조회가 이 인덱스를 못 타고 전수 스캔이 된다.
CREATE INDEX alarm_event_pending_idx ON alarm_event (dispatch_after)
    WHERE email_status IN ('PENDING', 'FAILED');
-- 앱 내 알림·히스토리 서빙용(was 읽기 전용 SELECT)
CREATE INDEX alarm_event_user_idx ON alarm_event (user_id, occurred_at DESC);

-- 조회 뷰 개정 — user_id 노출(was의 소유 스코프 조회용), pending_candidates 제거(승인 플로우 폐지).
-- CREATE OR REPLACE는 컬럼 삭제를 허용하지 않아 DROP+CREATE로 간다.
-- (was_reader GRANT는 V2의 ALTER DEFAULT PRIVILEGES가 새 뷰·새 테이블에 자동 적용한다 —
--  alarm_event·재생성 뷰 모두 같은 소유자(monitoring)가 만들므로 별도 GRANT 문이 필요 없다.)
DROP VIEW v_target_overview;

CREATE VIEW v_target_overview AS
SELECT t.id AS target_id, t.user_id, t.type, t.username, t.short_code, t.keyword_rule, t.status,
       t.tracked_short_code, t.tracked_since, t.registration_key, t.expires_at,
       t.registered_at, t.closed_at, t.last_fetched_at, t.fail_reason,
       ps.captured_on AS profile_captured_on, ps.followers, ps.media_count,
       -- 추적 게시물의 최신 지표 — 미추적(WATCHING) 캠페인은 전부 null.
       xs.captured_on AS post_captured_on, xs.content_type,
       xs.likes, xs.comments, xs.views, xs.saves, xs.shares, xs.reposts
FROM target t
LEFT JOIN LATERAL (
    -- 컬럼은 명시 — SELECT *면 스냅샷 테이블에 컬럼이 늘 때 뷰 계약이 조용히 바뀐다.
    SELECT p.captured_on, p.followers, p.media_count FROM profile_snapshot p
    WHERE p.username = t.username ORDER BY p.captured_on DESC LIMIT 1
) ps ON true
LEFT JOIN LATERAL (
    SELECT s.captured_on, s.content_type, s.likes, s.comments, s.views,
           s.saves, s.shares, s.reposts
    FROM post_snapshot s
    WHERE s.short_code = t.tracked_short_code ORDER BY s.captured_on DESC LIMIT 1
) xs ON true;
```

- [ ] **Step 2: 실패하는 테스트 작성** — 마이그레이션·저장 왕복·뷰·등록 검증

`MigrationTest.java` 전체 교체:

```java
package com.celfit.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationTest {

	@Test
	void 마이그레이션이_핵심_테이블을_만든다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		Long tables = db.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE (table_schema, table_name) IN
				  (('raw','fetch_payload'), ('public','target'), ('public','detected_candidate'),
				   ('public','profile_snapshot'), ('public','post_snapshot'), ('public','alarm_event'))""",
				Long.class);
		assertThat(tables).isEqualTo(6);
	}

	/** user_id는 expand 단계라 nullable이어야 한다 — NOT NULL이면 기존 운영 행 때문에 마이그레이션이 실패한다. */
	@Test
	void target_user_id는_nullable로_추가된다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		assertThat(db.queryForObject("""
				SELECT is_nullable FROM information_schema.columns
				WHERE table_name='target' AND column_name='user_id'""", String.class))
				.isEqualTo("YES");
	}
}
```

`StoreTest.java`에 테스트 추가(기존 `등록키_조회와_keyword_rule_왕복` 바로 아래):

```java
	@Test
	void user_id는_저장_왕복하고_없으면_null이다() {
		var rule = new KeywordRule(List.of("샤넬"), List.of(), List.of());
		targets.insert(TargetType.ACCOUNT, 42L, "acct_a", null, rule,
				TargetStatus.WATCHING, null, "key-uid", Instant.now().plusSeconds(3600));
		// 기존 행(백필 전 운영 데이터)을 흉내 — user_id 없이 들어온 캠페인도 그대로 저장된다.
		targets.insert(TargetType.ACCOUNT, null, "acct_b", null, rule,
				TargetStatus.WATCHING, null, "key-nouid", Instant.now().plusSeconds(3600));

		assertThat(targets.findByRegistrationKey("key-uid").orElseThrow().userId()).isEqualTo(42L);
		assertThat(targets.findByRegistrationKey("key-nouid").orElseThrow().userId()).isNull();
	}
```

`StoreTest`의 기존 `targets.insert(...)` 호출 6곳은 두 번째 인자로 `null`(또는 임의 userId)을 넣어 시그니처에 맞춘다.

`ReadSurfaceTest.java` — 뷰 단언 교체. `seed()`의 첫 INSERT에 user_id를 싣고, 후보 수 관련 테스트 2개를 뷰 개정에 맞춘다:

```java
	private void seed() {
		long watching = db.queryForObject("""
				INSERT INTO target (type, user_id, username, keyword_rule, status, registration_key, expires_at)
				VALUES ('ACCOUNT', 7, 'acct_a', '{"and":["샤넬"],"any":[],"exclude":[]}'::jsonb,
				        'WATCHING', 'key-watching', now() + interval '30 days')
				RETURNING id""", Long.class);
		// 후보 행은 남는다(신규 적재는 중단됐지만 기존 행은 이력) — 뷰에서 빠졌는지 확인하는 데 쓴다.
		db.update("INSERT INTO detected_candidate (target_id, short_code, status) VALUES (?, 'SC_P', 'PENDING')",
				watching);
		db.update("INSERT INTO detected_candidate (target_id, short_code, status) VALUES (?, 'SC_R', 'REJECTED')",
				watching);
		// 프로필 스냅샷 이틀치 — LATERAL이 최신 하루만 집는지 확인용.
		db.update("""
				INSERT INTO profile_snapshot (username, captured_on, followers, following, media_count)
				VALUES ('acct_a', DATE '2026-07-27', 1000, 10, 50),
				       ('acct_a', DATE '2026-07-28', 1200, 11, 52)""");

		db.update("""
				INSERT INTO target (type, username, short_code, status, tracked_short_code, tracked_since,
				                    registration_key, expires_at)
				VALUES ('POST', 'acct_b', 'SC1', 'TRACKING', 'SC1', now(),
				        'key-tracking', now() + interval '30 days')""");
		// 게시물 스냅샷도 이틀치 — 피드 게시물이라 views는 항상 null(delta도 null로 나와야 한다).
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, comments, views, saves, shares, reposts)
				VALUES ('acct_b', 'SC1', DATE '2026-07-27', 'FEED', 100, 5, NULL, 3, 1, 0),
				       ('acct_b', 'SC1', DATE '2026-07-28', 'FEED', 130, 9, NULL, 4, 1, 2)""");
	}
```

기존 `개요_뷰는_최신_프로필_스냅샷과_PENDING_후보_수를_준다`를 아래로 교체하고, `종결된_캠페인은_잔여_PENDING_후보를_세지_않는다`는 **삭제**한다(집계 컬럼 자체가 사라짐):

```java
	@Test
	void 개요_뷰는_user_id와_최신_프로필_스냅샷을_준다() {
		assertThat(db.queryForObject("SELECT count(*) FROM v_target_overview", Long.class)).isEqualTo(2);

		var row = db.queryForMap("SELECT * FROM v_target_overview WHERE username = 'acct_a'");
		assertThat(row.get("status")).isEqualTo("WATCHING");
		assertThat(row.get("registration_key")).isEqualTo("key-watching");
		// 알람 수신자·소유 스코프 조회의 근거 — 뷰에서 빠지면 was가 target을 직접 읽어야 한다.
		assertThat(row.get("user_id")).isEqualTo(7L);
		assertThat(row.get("profile_captured_on")).isEqualTo(Date.valueOf(LocalDate.of(2026, 7, 28)));
		assertThat(row.get("followers")).isEqualTo(1200L);
		assertThat(row.get("media_count")).isEqualTo(52L);

		// 프로필 스냅샷이 없는 캠페인도 목록에서 빠지지 않는다(LEFT JOIN LATERAL).
		var noProfile = db.queryForMap("SELECT * FROM v_target_overview WHERE username = 'acct_b'");
		assertThat(noProfile.get("followers")).isNull();
		assertThat(noProfile.get("user_id")).isNull();   // 백필 전 기존 행
	}

	/** 승인 플로우가 사라져 "승인 대기 N건"은 화면에서 의미가 없다 — 컬럼이 남아 있으면 FE가 다시 붙인다. */
	@Test
	void 개요_뷰에_후보_수_컬럼은_없다() {
		assertThat(db.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_name='v_target_overview' AND column_name='pending_candidates'""",
				Long.class)).isZero();
	}

	@Test
	void was_reader는_알람_대장을_읽을_수_있다() {
		// 앱 내 알림·히스토리는 was가 이 테이블을 읽어 서빙한다(계약 v2 §3).
		assertThat(wasReader.queryForObject("SELECT count(*) FROM alarm_event", Long.class)).isZero();
	}
```

`RegistrationApiTest.java` — 요청 본문 2개에 userId를 넣고 누락 검증을 추가:

```java
	private static final String ACCOUNT_BODY = """
			{"registrationKey":"rk-1","type":"ACCOUNT","userId":7,"username":"someuser",
			 "keywordRule":{"and":[],"any":["샤넬"],"exclude":[]},
			 "expiresAt":"2027-01-01T00:00:00+09:00"}""";

	private static final String POST_BODY = """
			{"registrationKey":"rk-post","type":"POST","userId":7,"shortCode":"DbV7LgZsKG8",
			 "expiresAt":"2027-01-01T00:00:00+09:00"}""";
```

```java
	/** userId가 없으면 알람 수신자를 영원히 알 수 없다 — 등록 자체를 막는다(계약 v2 §2-1 필수 필드). */
	@Test
	void userId_누락은_400_VALIDATION_target_미생성() throws Exception {
		String bad = ACCOUNT_BODY.replace("\"userId\":7,", "");
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(bad))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
	}

	@Test
	void 등록된_캠페인에_user_id가_실린다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated());
		assertThat(db.queryForObject("""
				SELECT user_id FROM target WHERE registration_key='rk-1'""", Long.class)).isEqualTo(7L);
	}
```

`CommandApiTest.seedTarget`·`DailySweepJobTest`의 `watching`/`tracking` 헬퍼도 `insert` 시그니처에 맞춰 userId를 넘긴다(값은 각각 `7L`, `7L` 고정 — Task 4의 알람 단언이 이 값을 쓴다):

```java
	// CommandApiTest
	private long seedTarget(TargetStatus status, String trackedShortCode) {
		return targets.insert(TargetType.ACCOUNT, 7L, "someuser", null, RULE, status, trackedShortCode,
				"rk-" + keySeq.incrementAndGet(), EXPIRES);
	}
```

```java
	// DailySweepJobTest
	private long watching(String username, KeywordRule rule, String key, Instant expiresAt) {
		return targets.insert(TargetType.ACCOUNT, 7L, username, null, rule,
				TargetStatus.WATCHING, null, key, expiresAt);
	}

	private long tracking(String username, String trackedShortCode, String key) {
		return targets.insert(TargetType.ACCOUNT, 7L, username, null, any("무관"),
				TargetStatus.TRACKING, trackedShortCode, key, FUTURE);
	}
```

`DailySweepJobTest.게시물_단독_캠페인은_열거_없이_단건만_수집한다`의 직접 insert도 동일하게 `7L`을 추가한다.

- [ ] **Step 3: 실패 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test
```
Expected: 컴파일 실패 (`TargetRepository.insert`가 9인자 시그니처가 아님, `TargetRow.userId()` 없음)

- [ ] **Step 4: `TargetRow`에 userId 추가**

```java
package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import java.time.Instant;

/**
 * target 테이블 한 행 — 캠페인 단위 등록 정보.
 * userId는 was 유저의 논리 참조이자 알람 수신자다 — V3 이전에 등록된 행은 null이고, 그 캠페인의
 * 알람 이벤트는 적재되지 않는다(수신자 불명 — {@code AlarmRecorder}가 warn만 남기고 건너뛴다).
 * keywordRule은 ACCOUNT 전용이라 POST 등록 행에서는 null이다.
 * registeredAt은 감지 하한선이다 — 이 시각 이후에 게시된 것만 후보가 된다(설계 §5, 07-29 확정).
 */
public record TargetRow(long id, Long userId, TargetType type, String username, String shortCode,
		KeywordRule keywordRule, TargetStatus status, String trackedShortCode,
		String registrationKey, Instant expiresAt, String failReason, Instant registeredAt) {}
```

- [ ] **Step 5: `TargetRepository` 매퍼·insert 갱신**

`ROW` 매퍼와 `insert`만 교체(나머지 메서드는 그대로):

```java
	private static final RowMapper<TargetRow> ROW = (rs, i) -> new TargetRow(
			rs.getLong("id"), rs.getObject("user_id", Long.class),
			TargetType.valueOf(rs.getString("type")),
			rs.getString("username"), rs.getString("short_code"),
			rs.getString("keyword_rule") == null ? null
					: JSON.readValue(rs.getString("keyword_rule"), KeywordRule.class),
			TargetStatus.valueOf(rs.getString("status")), rs.getString("tracked_short_code"),
			rs.getString("registration_key"),
			rs.getTimestamp("expires_at").toInstant(), rs.getString("fail_reason"),
			// NOT NULL DEFAULT now() — 애플리케이션이 값을 주지 않는 컬럼이라 null 분기가 없다.
			rs.getTimestamp("registered_at").toInstant());
```

```java
	/** userId는 nullable — V3 이전 등록분과의 호환을 위해 열어 두지만, 신규 등록은 API가 필수로 막는다. */
	public long insert(TargetType type, Long userId, String username, String shortCode, KeywordRule rule,
			TargetStatus status, String trackedShortCode, String registrationKey, Instant expiresAt) {
		// tracked_since는 tracked_short_code가 있을 때만 채운다.
		// IS NOT NULL 자리의 파라미터는 ::text 캐스팅이 필수 — 없으면 PG가 타입을 못 정해
		// "could not determine data type of parameter"로 실패한다.
		return db.queryForObject("""
				INSERT INTO target (type, user_id, username, short_code, keyword_rule, status,
				                    tracked_short_code, tracked_since, registration_key, expires_at)
				VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, CASE WHEN ?::text IS NOT NULL THEN now() END, ?, ?)
				RETURNING id""",
				Long.class, type.name(), userId, username, shortCode,
				rule == null ? null : JSON.writeValueAsString(rule), status.name(),
				trackedShortCode, trackedShortCode, registrationKey,
				Timestamp.from(expiresAt));
	}
```

- [ ] **Step 6: 등록 요청·명령에 userId 추가**

`web/RegisterRequest.java`:

```java
package com.celfit.monitoring.web;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.service.RegisterCommand;
import java.time.OffsetDateTime;

/**
 * 등록 요청 본문 — 계약 v2 §2-1 그대로.
 * userId는 알람 수신자라 필수다(누락은 VALIDATION 400 — 검증은 서비스가 한다).
 * expiresAt은 오프셋 포함 ISO-8601(예: 2026-08-28T23:59:59+09:00).
 */
public record RegisterRequest(String registrationKey, TargetType type, Long userId, String username,
		String shortCode, KeywordRule keywordRule, OffsetDateTime expiresAt) {

	public RegisterCommand toCommand() {
		return new RegisterCommand(registrationKey, type, userId, username, shortCode, keywordRule,
				expiresAt == null ? null : expiresAt.toInstant());
	}
}
```

`service/RegisterCommand.java`:

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetType;
import java.time.Instant;

/**
 * 등록 명령 — 계약 v2 §2-1 요청의 서비스 층 표현.
 * ACCOUNT는 username·keywordRule, POST는 shortCode만 쓴다(반대쪽 필드는 무시).
 * userId는 타입 무관 필수 — 알람 수신자 해석의 유일한 근거다.
 */
public record RegisterCommand(String registrationKey, TargetType type, Long userId, String username,
		String shortCode, KeywordRule keywordRule, Instant expiresAt) {}
```

- [ ] **Step 7: `RegistrationService` 검증·insert 반영**

`validate`에 userId 검사를 추가(`type` 검사 바로 뒤):

```java
		if (cmd.userId() == null) {
			// 뒤늦게 채울 방법이 없다 — 캠페인이 만들어지고 나면 그 소유자를 monitoring이 알 길이 없다.
			throw new ValidationException("userId는 필수입니다.");
		}
```

`registerAccount`·`registerPost`의 insert 호출을 새 시그니처로:

```java
		long id = targets.insert(TargetType.ACCOUNT, cmd.userId(), cmd.username(), null, cmd.keywordRule(),
				TargetStatus.WATCHING, null, cmd.registrationKey(), cmd.expiresAt());
```

```java
		long id = targets.insert(TargetType.POST, cmd.userId(), post.username(), shortCode, null,
				TargetStatus.TRACKING, shortCode, cmd.registrationKey(), cmd.expiresAt());
```

- [ ] **Step 8: 통과 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test
```
Expected: BUILD SUCCESSFUL — 기존 테스트 전부 + 신규 6개(MigrationTest 2, StoreTest 1, ReadSurfaceTest 2 교체·1 신규, RegistrationApiTest 2) 통과

- [ ] **Step 9: 커밋**

```bash
git add monitoring/src/main/resources/db/migration/V3__user_id_and_alarm_event.sql \
        monitoring/src/main/java/com/celfit/monitoring \
        monitoring/src/test/java/com/celfit/monitoring
git commit -m "$(cat <<'EOF'
feat(monitoring): target.user_id 관통 + alarm_event 대장(V3) — 등록 userId 필수, 개요 뷰 개정

알람 소유가 monitoring으로 이동하면서 수신자 해석 근거가 필요해졌다. user_id는 expand 단계라
nullable(기존 운영 행은 알람 제외), 신규 등록은 API가 필수로 막는다. 승인 플로우 폐지에 맞춰
v_target_overview에서 pending_candidates를 빼고 user_id를 노출한다.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 승인 플로우 제거 + 첫 감지 자동 수집

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/DailySweepJob.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/TargetCommandService.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/web/TargetController.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/web/ApiExceptionHandler.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/CandidateRepository.java` (주석만)
- Delete: `monitoring/src/main/java/com/celfit/monitoring/web/ApproveResponse.java`
- Delete: `monitoring/src/main/java/com/celfit/monitoring/web/RejectResponse.java`
- Delete: `monitoring/src/main/java/com/celfit/monitoring/service/CandidateNotFoundException.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/web/CommandApiTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/DailySweepJobTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/SweepSchedulerTest.java` (생성자 arity)

- [ ] **Step 1: 실패하는 테스트 작성 — 스윕 자동 전환**

`DailySweepJobTest.java`에서 후보 관련 헬퍼·단언을 자동 전환으로 교체한다.

먼저 헬퍼 교체(`candidateCodes`·`candidateCount` 삭제, 대신):

```java
	private String trackedOf(long targetId) {
		return db.queryForObject("SELECT tracked_short_code FROM target WHERE id=?", String.class, targetId);
	}

	private long candidateCount() {
		return db.queryForObject("SELECT count(*) FROM detected_candidate", Long.class);
	}
```

교체 대상 테스트 4개:

```java
	// ① 만료
	@Test
	void 만료_지난_활성_캠페인은_EXPIRED로_종결되고_수집하지_않는다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER));
		long expired = watching("someuser", any("Rare Beginnings"), "rk-expired", PAST);

		job.run();

		assertThat(statusOf(expired)).isEqualTo(TargetStatus.EXPIRED);
		// 만기 지난 캠페인까지 수집하면 종료된 캠페인만큼 매일 Hiker 콜이 새어 나간다.
		assertThat(hiker.profileCalls).isZero();
		assertThat(trackedOf(expired)).isNull();
	}

	// ② 계정당 1회 수집 + 캠페인별 규칙
	@Test
	void 같은_계정_두_캠페인은_수집_1회_감지는_각자() {
		hiker.account("someuser", "111",
				new FakePost("AAA", "Rare Beginnings 신상 런칭", AFTER),
				new FakePost("BBB", "오늘의 데일리 메이크업", AFTER - 100));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);
		long b = watching("someuser", any("절대없는키워드zz"), "rk-b", FUTURE);

		job.run();

		// 캠페인 수만큼 수집하면 같은 계정을 여러 번 긁어 콜이 배로 든다 — 관측 대상 단위로 1회.
		assertThat(hiker.profileCalls).isEqualTo(1);
		// 열거 1회는 clips(조회수 보강) + medias 2콜이다 — 과금 단위가 콜이라 이 구성을 못박는다.
		assertThat(hiker.clipsCalls).isEqualTo(1);
		assertThat(hiker.mediasCalls).isEqualTo(1);
		// 감지 즉시 추적 전환 — 승인 대기 단계가 없다(스펙 §2-2).
		assertThat(statusOf(a)).isEqualTo(TargetStatus.TRACKING);
		assertThat(trackedOf(a)).isEqualTo("AAA");
		assertThat(statusOf(b)).isEqualTo(TargetStatus.WATCHING);
		assertThat(trackedOf(b)).isNull();
		// 스냅샷도 관측 대상 단위 1행(캠페인 2개여도 프로필 1행·게시물 2행).
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("SELECT count(*) FROM post_snapshot", Long.class)).isEqualTo(2);
		assertThat(db.queryForObject("""
				SELECT last_fetched_at IS NOT NULL FROM target WHERE id=?""", Boolean.class, a)).isTrue();
	}

	/**
	 * 첫 감지 1건 규칙 — 같은 스윕에서 여러 게시물이 걸려도 캠페인:추적 게시물은 1:1이다.
	 * 채택 기준은 taken_at 최신(가장 최근 협찬 게시물이 캠페인의 그것일 확률이 높다).
	 * 열거 순서에 기대면 핀 고정 게시물이 앞에 오는 응답에서 옛 게시물이 뽑힌다.
	 */
	@Test
	void 같은_스윕_다중_매칭은_게시_시각_최신_1건만_추적한다() {
		hiker.account("someuser", "111",
				new FakePost("OLDER", "Rare Beginnings 앵콜", AFTER),
				new FakePost("NEWER", "Rare Beginnings 신상 런칭", AFTER + 100),
				new FakePost("CCC", "무관한 게시물", AFTER + 200));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();

		assertThat(trackedOf(a)).isEqualTo("NEWER");
		// 후보 적재는 중단됐다 — detected_candidate에 새 행이 생기면 승인 화면이 되살아난다.
		assertThat(candidateCount()).isZero();
		// 매칭 게시물은 방금 열거에서 스냅샷이 남았다 — 단건 보강 콜이 나가면 콜이 두 배가 된다.
		assertThat(hiker.postCalls).isZero();
	}

	/** 전환은 한 번뿐 — 이미 TRACKING인 캠페인은 새 매칭이 떠도 추적 대상이 바뀌지 않는다. */
	@Test
	void 이미_추적_중인_캠페인은_새_매칭으로_갈아치우지_않는다() {
		hiker.account("someuser", "111",
				new FakePost("AAA", "Rare Beginnings 신상", AFTER),
				new FakePost("BBB", "Rare Beginnings 앵콜", AFTER + 100));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();
		job.run();

		assertThat(trackedOf(a)).isEqualTo("BBB");   // 첫 스윕에서 최신 1건 채택
		assertThat(statusOf(a)).isEqualTo(TargetStatus.TRACKING);
	}
```

`등록_시각_이전_게시물은_키워드가_맞아도_후보가_아니다`·`게시_시각을_모르는_게시물은_보수적으로_후보에서_제외한다` 두 테스트는 단언을 후보 → 전환으로 바꾼다:

```java
	/**
	 * 감지 하한선 — 등록 시각 이후 게시물만 잡는다(설계 §5, 07-29 확정).
	 * 없으면 첫 스윕에서 등록 전 옛 키워드 게시물을 추적 대상으로 잡아 캠페인이 통째로 헛돈다.
	 */
	@Test
	void 등록_시각_이전_게시물은_키워드가_맞아도_추적되지_않는다() {
		hiker.account("someuser", "111",
				new FakePost("OLDPOST", "Rare Beginnings 신상 런칭", BEFORE),
				new FakePost("NEWPOST", "Rare Beginnings 앵콜", AFTER));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();

		assertThat(trackedOf(a)).isEqualTo("NEWPOST");
		// 감지에서 빠졌을 뿐 관측은 한다 — 지표 스냅샷은 등록 전 게시물도 남는다.
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='OLDPOST'""", Long.class))
				.isEqualTo(1);
	}

	/** 게시 시각을 모르면 하한선 판정 자체가 불가능하다 — 잘못 잡은 추적은 되돌릴 수 없으므로 보수적으로 뺀다. */
	@Test
	void 게시_시각을_모르는_게시물은_보수적으로_추적하지_않는다() {
		hiker.account("someuser", "111", new FakePost("NOTIME", "Rare Beginnings 신상 런칭", null));
		long a = watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();

		assertThat(statusOf(a)).isEqualTo(TargetStatus.WATCHING);
		assertThat(trackedOf(a)).isNull();
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='NOTIME'""", Long.class))
				.isEqualTo(1);
	}
```

`매칭_게시물은_PENDING_후보로_쌓이고_재실행해도_중복되지_않는다`는 **삭제**(위 `이미_추적_중인_캠페인은…`이 대체).

실패 격리·404 테스트 3개의 `candidateCount(good)` 단언은 `statusOf(good)`·`trackedOf(good)`로 바꾼다:

```java
	@Test
	void 한_계정_수집_오류가_다른_계정_처리를_막지_않는다() {
		hiker.brokenAccount("bad_user")
				.account("good_user", "222", new FakePost("GGG", "Rare Beginnings 신상", AFTER));
		long bad = watching("bad_user", any("Rare Beginnings"), "rk-bad", FUTURE);
		long good = watching("good_user", any("Rare Beginnings"), "rk-good", FUTURE);

		job.run();

		assertThat(trackedOf(good)).isEqualTo("GGG");
		// 일반 실패는 종결하지 않는다 — 다음 스윕에서 재시도할 여지를 남긴다.
		assertThat(statusOf(bad)).isEqualTo(TargetStatus.WATCHING);
		assertThat(trackedOf(bad)).isNull();
	}
```

(`계정_404는…`·`추적_게시물_404는…`·`비공개_전환_계정은…`은 `candidateCount(good)` 단언만 `assertThat(trackedOf(good)).isEqualTo("GGG");`로 교체.)

- [ ] **Step 2: 실패하는 테스트 작성 — 명령 API 축소**

`CommandApiTest.java`에서 아래 7개 테스트를 **삭제**한다:
`승인은_TRACKING_전환과_즉시_1회_수집까지_한다`, `거절은_후보만_닫고_캠페인은_WATCHING을_지속한다`,
`이미_TRACKING인_캠페인_승인은_409_INVALID_STATE`, `이미_거절한_후보_재승인은_409_INVALID_STATE`,
`승인_중_수집_실패는_전량_롤백된다`, `없는_후보_승인은_404_CANDIDATE_NOT_FOUND`,
`다른_캠페인_소속_후보_승인은_404_CANDIDATE_NOT_FOUND`.

함께 삭제: `seedCandidate` 헬퍼, `candidateStatus` 헬퍼, `CandidateRepository candidates` 필드와 그 import, `db.update("DELETE FROM detected_candidate")` 이외의 후보 관련 코드(정리 구문은 유지 — 옛 행이 남아 있을 수 있다).

클래스 javadoc 교체 + 신규 테스트 추가:

```java
/** 명령 API 2종(연장·해지) — 계약 v2 §2-2~2-3의 상태 전이 규칙. 승인·기각은 v2에서 폐지됐다. */
```

```java
	/**
	 * 승인·기각 경로는 계약 v2에서 사라졌다 — 남아 있으면 구 was가 조용히 성공해
	 * "후보 승인"이라는 없어진 개념이 상태 기계에 되돌아온다. 404가 곧 폐지의 증거다.
	 */
	@Test
	void 승인_기각_경로는_더_이상_존재하지_않는다() throws Exception {
		long targetId = seedTarget(TargetStatus.WATCHING, null);

		mvc.perform(post("/api/targets/{id}/candidates/{cid}/approve", targetId, 1))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/targets/{id}/candidates/{cid}/reject", targetId, 1))
				.andExpect(status().isNotFound());

		assertThat(targetStatus(targetId)).isEqualTo("WATCHING");
	}
```

- [ ] **Step 3: 실패 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test --tests "com.celfit.monitoring.service.DailySweepJobTest" --tests "com.celfit.monitoring.web.CommandApiTest"
```
Expected: `DailySweepJobTest` 다수 실패(아직 후보만 적재하고 전환하지 않음), `CommandApiTest.승인_기각_경로는…` 실패(200 응답)

- [ ] **Step 4: `DailySweepJob` 자동 전환으로 개편**

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.PrivateAccountException;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 일일 스윕(KST 02:00) — 만료 처리 → 계정별 1회 수집(캠페인 수와 무관) →
 * WATCHING 키워드 감지 시 즉시 추적 전환 → TRACKING 게시물 보강. 실패는 계정·캠페인 단위로 격리한다.
 *
 * <p>트랜잭션을 걸지 않는다: 스윕 한 번은 계정 수만큼 외부 콜을 돌기 때문에
 * 전체를 한 트랜잭션으로 묶으면 커넥션을 몇 분씩 잡고, 마지막 계정의 실패가 앞선 전 계정의
 * 수집을 되돌린다. 커밋 단위는 수집 1회({@link SnapshotWriter})와 상태 전이 1건이다.
 * 구 approve의 "트랜잭션 안에서 외부 콜"은 승계하지 않는다(스펙 §2-2).
 */
@Service
public class DailySweepJob {

	private static final Logger log = LoggerFactory.getLogger(DailySweepJob.class);
	private static final String NOT_FOUND = "SUBJECT_NOT_FOUND";
	private static final String PRIVATE_ACCOUNT = "PRIVATE_ACCOUNT";

	private final TargetRepository targets;
	private final CollectService collect;

	public DailySweepJob(TargetRepository targets, CollectService collect) {
		this.targets = targets;
		this.collect = collect;
	}

	public void run() {
		// 만료를 먼저 닫아야 만기 지난 캠페인이 그날 스윕 대상에서 빠진다 — 순서가 바뀌면 종료된 캠페인만큼 콜이 샌다.
		int expired = targets.expireOverdue();
		Map<String, List<TargetRow>> byUsername = targets.findActive().stream()
				.collect(Collectors.groupingBy(TargetRow::username));
		int failedAccounts = 0;
		for (var entry : byUsername.entrySet()) {
			try {
				sweepAccount(entry.getKey(), entry.getValue());
			} catch (SubjectNotFoundException e) {
				// 계정 자체가 없어졌다(삭제·개명) — 재시도해도 결과가 같으니 그 계정의 캠페인을 전부 종결한다.
				closeAll(entry.getKey(), entry.getValue(), NOT_FOUND);
				failedAccounts++;
			} catch (PrivateAccountException e) {
				// 비공개 전환도 결정적 수집 불가다(설계 §5 "계정 소멸·비공개 등 → FAILED").
				// 일반 실패로 두면 만료일까지 매일 1콜을 태우면서 영원히 WATCHING으로 남는다.
				closeAll(entry.getKey(), entry.getValue(), PRIVATE_ACCOUNT);
				failedAccounts++;
			} catch (RuntimeException e) {
				// 재시도 여지가 있는 실패(5xx·타임아웃·셰이프 이상)는 상태를 건드리지 않는다 — Task 3의 재시도 라운드가 다시 본다.
				log.warn("스윕 실패(격리) — 계정 {}: {}", entry.getKey(), e.toString());
				failedAccounts++;
			}
		}
		log.info("스윕 완료 — 계정 {}건(실패 {}), 만료 {}건", byUsername.size(), failedAccounts, expired);
	}

	/**
	 * 계정 1개분 — 열거는 캠페인 수와 무관하게 한 번만 하고, 그 결과를 캠페인들이 나눠 본다.
	 * 여기서 던지는 예외는 계정 전체의 실패다(호출자가 종결 판단).
	 */
	private void sweepAccount(String username, List<TargetRow> accountTargets) {
		// POST 등록분만 있는 계정은 열거할 이유가 없다 — 프로필·열거 2~3콜이 통째로 낭비된다.
		List<PostInfo> posts = needsEnumeration(accountTargets)
				? collect.collectAccount(username).posts()
				: List.of();
		Set<String> enumerated = posts.stream().map(PostInfo::shortCode).collect(Collectors.toSet());
		for (TargetRow t : accountTargets) {
			try {
				sweepTarget(t, posts, enumerated);
			} catch (SubjectNotFoundException e) {
				// 추적 게시물만 삭제된 경우 — 계정은 멀쩡하니 이 캠페인 하나만 종결한다.
				log.info("추적 게시물 부재 — 캠페인 {} 종결: {}", t.id(), t.trackedShortCode());
				closeFailed(t, NOT_FOUND);
			} catch (PrivateAccountException e) {
				// 지금은 도달 불가다 — 비공개 판정은 프로필 응답에만 있고 그건 계정 갈래에서 걸린다.
				// 그래도 계정 갈래와 대칭으로 둔다: 단건 경로(fetchPost)에 비공개 판정이 생기는 순간
				// 이 갈래가 없으면 "일반 실패"로 조용히 새어 만료까지 매일 재시도하게 된다.
				log.info("추적 게시물 비공개 — 캠페인 {} 종결: {}", t.id(), t.trackedShortCode());
				closeFailed(t, PRIVATE_ACCOUNT);
			} catch (RuntimeException e) {
				log.warn("캠페인 스윕 실패(격리) — target {}: {}", t.id(), e.toString());
			}
		}
	}

	/** 결정적 수집 불가 — 그 계정의 활성 캠페인을 한꺼번에 종결한다. */
	private void closeAll(String username, List<TargetRow> accountTargets, String failReason) {
		log.info("계정 수집 불가({}) — {} 캠페인 {}건 종결", failReason, username, accountTargets.size());
		accountTargets.forEach(t -> closeFailed(t, failReason));
	}

	/**
	 * 종결도 실패할 수 있다(DB 순단·락 타임아웃). 여기서 예외가 새면 남은 계정이 통째로 안 돌아
	 * "캠페인 하나 종결 실패"가 "그날 스윕 전면 중단"으로 번진다 — 로그만 남기고 계속한다.
	 */
	private void closeFailed(TargetRow t, String failReason) {
		try {
			targets.close(t.id(), TargetStatus.FAILED, failReason);
		} catch (RuntimeException e) {
			log.warn("종결 실패(격리) — target {} → FAILED/{}: {}", t.id(), failReason, e.toString());
		}
	}

	private void sweepTarget(TargetRow t, List<PostInfo> posts, Set<String> enumerated) {
		if (t.status() == TargetStatus.WATCHING && t.keywordRule() != null) {
			PostInfo detected = firstDetection(t, posts);
			if (detected != null) {
				// 승인 단계 없이 바로 추적으로 넘어간다(스펙 §2-2). 지표는 방금 열거에서 이미 적재됐으므로
				// 추가 단건 콜을 쏘지 않는다 — 감지 대상 자체가 열거 결과라 항상 enumerated 안에 있다.
				targets.markTracking(t.id(), detected.shortCode());
				log.info("첫 감지 자동 전환 — target {} → TRACKING {}", t.id(), detected.shortCode());
				targets.touchFetched(t.id());
				return;
			}
		}
		String tracked = t.status() == TargetStatus.TRACKING ? t.trackedShortCode() : null;
		// 열거 안에 있으면 이미 방금 스냅샷을 남겼다 — 단건을 또 부르면 콜이 두 배가 된다.
		if (tracked != null && !enumerated.contains(tracked)) {
			collect.collectPost(tracked);
		}
		targets.touchFetched(t.id());
	}

	/**
	 * 첫 감지 1건 — 캠페인:추적 게시물은 1:1이라 같은 스윕에 여러 개가 걸려도 하나만 고른다.
	 * 기준은 게시 시각 최신: 열거 순서에 기대면 핀 고정 게시물(taken_at 2023년 사례 — findings §3)이
	 * 먼저 잡힐 수 있고, HikerClient의 재정렬에 암묵 의존하는 코드가 된다.
	 */
	private static PostInfo firstDetection(TargetRow t, List<PostInfo> posts) {
		return posts.stream()
				.filter(p -> postedAfterRegistration(p, t) && t.keywordRule().matches(p.caption()))
				.max(Comparator.comparing(PostInfo::takenAt))   // 필터가 takenAt != null을 보장한다
				.orElse(null);
	}

	/**
	 * 감지 하한선 — 캠페인 등록 시각 이후에 게시된 것만 본다(설계 §5, 07-29 확정).
	 * 없으면 첫 스윕에서 등록 전의 옛 키워드 게시물을 추적 대상으로 잡아 캠페인이 통째로 헛돈다.
	 * taken_at을 못 얻은 게시물은 보수적으로 제외한다 — 잘못 잡은 추적은 되돌릴 수 없지만,
	 * 빠뜨린 게시물은 다음 스윕에서 taken_at이 채워지면 다시 걸린다.
	 */
	private static boolean postedAfterRegistration(PostInfo p, TargetRow t) {
		return p.takenAt() != null
				&& !Instant.ofEpochSecond(p.takenAt()).isBefore(t.registeredAt());
	}

	/**
	 * 계정 열거 필요 여부 — ACCOUNT 등록분이 하나라도 있으면 판단 근거(팔로워 추이·신규 게시물)가 필요하다.
	 * POST 등록분은 등록한 그 게시물만 보므로 단건 콜로 충분하다.
	 */
	private static boolean needsEnumeration(List<TargetRow> ts) {
		return ts.stream().anyMatch(t -> t.type() == TargetType.ACCOUNT);
	}
}
```

`DailySweepJobTest.setUp`의 조립도 새 생성자에 맞춘다(`candidates` 필드·`CandidateRepository` import 삭제):

```java
		job = new DailySweepJob(targets, collect);
```

`SweepSchedulerTest.sweep은_일일_스윕_잡에_위임한다`의 익명 서브클래스도:

```java
		var job = new DailySweepJob(null, null) {
```

- [ ] **Step 5: `TargetCommandService`에서 승인·기각 삭제**

`approve`·`reject`·`requirePendingCandidate` 메서드와 `candidates`·`collectService` 필드, 관련 import(`CandidateStatus`, `CandidateRepository`, `CandidateRow`)를 삭제하고 생성자를 줄인다. 결과:

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 이후의 캠페인 명령 2종 — 연장·해지(계약 v2 §2-2~2-3).
 * 승인·기각은 v2에서 폐지됐다: 감지되면 스윕이 바로 추적으로 전환한다({@link DailySweepJob}).
 * 남은 상태 전이 규칙은 둘이다 — 연장은 활성에서만, 해지는 멱등.
 */
@Service
public class TargetCommandService {

	private final TargetRepository targets;

	public TargetCommandService(TargetRepository targets) {
		this.targets = targets;
	}

	/** 기간 연장 — 활성 캠페인만. 종결분을 되살리는 경로는 없다(재등록이 정답). */
	@Transactional
	public TargetRow extend(long targetId, Instant expiresAt) {
		if (expiresAt == null) {
			throw new ValidationException("expiresAt은 필수입니다.");
		}
		if (!expiresAt.isAfter(Instant.now())) {
			// 과거로의 연장은 다음 스윕에서 즉시 EXPIRED가 된다 — 의도한 명령일 리 없다.
			throw new ValidationException("expiresAt은 미래 시각이어야 합니다.");
		}
		TargetRow target = targets.findById(targetId)
				.orElseThrow(() -> new TargetNotFoundException("target 없음: " + targetId));
		if (!target.status().active()) {
			throw new InvalidStateException("활성 캠페인만 연장할 수 있습니다: " + target.status());
		}
		targets.updateExpiresAt(targetId, expiresAt);
		return targets.findById(targetId).orElseThrow();
	}

	/**
	 * 해지 — CANCELED로 전이(행·스냅샷 보존). 이미 종결이면 현재 상태 그대로 돌려준다(계약 §2-3 멱등).
	 * 멱등이어야 하는 이유: was의 타임아웃 재시도가 409로 튕기면 사용자에게 해지 실패로 보인다.
	 */
	@Transactional
	public TargetRow cancel(long targetId) {
		TargetRow target = targets.findById(targetId)
				.orElseThrow(() -> new TargetNotFoundException("target 없음: " + targetId));
		if (!target.status().active()) {
			return target;   // closed_at도 덮어쓰지 않는다 — 종결 시점이 재시도로 뒤로 밀리면 안 된다
		}
		targets.close(targetId, TargetStatus.CANCELED, null);
		return targets.findById(targetId).orElseThrow();
	}
}
```

> 해지(cancel)는 알람 이벤트를 만들지 않는다 — 사용자가 직접 누른 행동이라 알릴 게 없다(스펙 §1 이벤트 4종에 없음).

- [ ] **Step 6: 컨트롤러·예외 핸들러 정리 + DTO 삭제**

`TargetController.java`에서 `approve`·`reject` 메서드와 `ApproveResponse`·`RejectResponse` 참조를 삭제하고, `@PostMapping` import는 등록에서 계속 쓰므로 유지한다. 클래스 javadoc 갱신:

```java
/**
 * 캠페인 명령 API — 계약 v2 §2(등록·연장·해지 3종). 인증 없음: 접근 통제는 전용 도커
 * 네트워크(monitoring-net) 소속이 강제한다. 토큰·헤더 검사를 여기에 추가하지 말 것
 * (계약 §1 — 연결이 되면 곧 인가된 호출자다).
 * 승인·기각(구 §2-2·2-3)은 v2에서 폐지 — 감지 즉시 자동 추적으로 대체됐다.
 */
```

`ApiExceptionHandler.java`에서 `CandidateNotFoundException` import와 `handleCandidateNotFound` 핸들러를 삭제.

파일 삭제:

```bash
git rm monitoring/src/main/java/com/celfit/monitoring/web/ApproveResponse.java \
       monitoring/src/main/java/com/celfit/monitoring/web/RejectResponse.java \
       monitoring/src/main/java/com/celfit/monitoring/service/CandidateNotFoundException.java
```

`CandidateRepository.java`의 클래스 javadoc만 교체(코드는 그대로 — 테이블·기존 행이 남아 있고 StoreTest가 계속 검증한다):

```java
/**
 * detected_candidate 테이블 접점 — **신규 적재는 v2에서 중단됐다**(승인 플로우 폐지, 스펙 §2-2).
 * 테이블과 기존 행은 이력으로 남고, DROP은 참조가 완전히 끊긴 다음 릴리스의 contract 단계 몫이다.
 * 지금 이 클래스에 호출자는 없다 — 테이블이 살아 있는 동안의 저장소 계약만 유지한다.
 */
```

- [ ] **Step 7: 통과 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test
```
Expected: BUILD SUCCESSFUL — `DailySweepJobTest` 12개, `CommandApiTest` 5개(연장 1·해지 1·종결 연장 1·없는 캠페인 해지 1·경로 폐지 1) + 트랜잭션 참여 1개 통과

- [ ] **Step 8: 커밋**

```bash
git add -A monitoring/src
git commit -m "$(cat <<'EOF'
feat(monitoring): 승인 플로우 제거 — 첫 감지 시 자동 추적 전환

키워드 매칭 게시물이 뜨면 후보로 쌓아 사람의 승인을 기다리는 대신 그 자리에서 TRACKING으로
전환한다. 같은 스윕에 여러 건이 걸리면 게시 시각 최신 1건만 채택해 캠페인:추적 게시물 1:1을
유지한다. approve/reject 엔드포인트·서비스 메서드·DTO·CandidateNotFoundException 삭제,
detected_candidate는 신규 적재 중단(테이블·기존 행은 다음 릴리스까지 잔존).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 일시 오류 당일 재시도 (콜 레벨 + 스윕 라운드)

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerProperties.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/JdkHikerHttp.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/DailySweepJob.java`
- Modify: `monitoring/src/main/resources/application.yml`
- Test: `monitoring/src/test/java/com/celfit/monitoring/hiker/JdkHikerHttpTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/DailySweepJobTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/SweepSchedulerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 — 콜 레벨 재시도**

`JdkHikerHttpTest.java`: 헬퍼를 재시도 설정까지 받게 바꾸고, 서버가 N회 실패 후 성공하는 시나리오를 추가한다.

```java
	/** 기본 헬퍼는 재시도 0 — 상태코드 매핑 테스트가 백오프로 느려지지 않게 한다. */
	private static JdkHikerHttp http(String baseUrl, String apiKey) {
		return new JdkHikerHttp(new HikerProperties(apiKey, baseUrl, Duration.ofSeconds(5), 0, Duration.ZERO));
	}

	private static JdkHikerHttp retryingHttp(String baseUrl, int maxRetries) {
		return new JdkHikerHttp(new HikerProperties("test-key", baseUrl, Duration.ofSeconds(5),
				maxRetries, Duration.ZERO));
	}

	/** 앞선 N회는 실패하고 그 다음 성공하는 서버 — 재시도가 실제로 다시 쏘는지 본다. */
	private String startFlakyServer(int failures, int failStatus) throws IOException {
		AtomicInteger seen = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			int n = seen.incrementAndGet();
			calls.set(n);
			boolean fail = n <= failures;
			byte[] bytes = (fail ? "boom" : "{\"status\":\"ok\"}").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(fail ? failStatus : 200, bytes.length);
			try (var out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}
```

(`private final AtomicInteger calls = new AtomicInteger();` 필드 추가 — `java.util.concurrent.atomic.AtomicInteger` import.)

```java
	/**
	 * 5xx는 일시 오류라 당일 안에 다시 시도해야 한다 — 예전에는 첫 실패로 그 계정이 하루 통째로 비었다.
	 * 재시도가 없으면 스윕 하루치 지표가 조용히 구멍 난다(다음 날 스냅샷의 delta가 이틀치로 합쳐짐).
	 */
	@Test
	void _5xx는_설정된_횟수만큼_재시도하고_성공하면_본문을_준다() throws IOException {
		String baseUrl = startFlakyServer(2, 500);

		assertThat(retryingHttp(baseUrl, 2).get("/v2/user/medias?user_id=1"))
				.isEqualTo("{\"status\":\"ok\"}");
		assertThat(calls.get()).isEqualTo(3);   // 최초 1 + 재시도 2
	}

	@Test
	void 재시도를_다_써도_실패하면_마지막_오류를_던진다() throws IOException {
		String baseUrl = startFlakyServer(9, 500);

		assertThatThrownBy(() -> retryingHttp(baseUrl, 2).get("/v2/user/medias?user_id=1"))
				.isInstanceOf(HikerFetchException.class)
				.hasMessageContaining("500");
		assertThat(calls.get()).isEqualTo(3);
	}

	/** 404는 대상 부재라 몇 번을 더 쏴도 같다 — 재시도는 콜 과금만 늘리고 종결을 늦춘다. */
	@Test
	void _404는_재시도하지_않는다() throws IOException {
		String baseUrl = startFlakyServer(9, 404);

		assertThatThrownBy(() -> retryingHttp(baseUrl, 2).get("/v2/user/by/username?username=ghost"))
				.isInstanceOf(SubjectNotFoundException.class);
		assertThat(calls.get()).isEqualTo(1);
	}
```

기존 4개 테스트의 `http(...)` 호출은 그대로 동작한다(헬퍼가 재시도 0으로 바뀜).

- [ ] **Step 2: 실패하는 테스트 작성 — 스윕 라운드**

`DailySweepJobTest`의 `FakeHiker`에 "N회 실패 후 성공" 계정을 추가한다:

```java
		/** 라운드 N번째부터 성공하는 계정 — 스윕 말미 재시도 라운드가 실제로 회복시키는지 본다. */
		private final Map<String, Integer> flakyRemaining = new HashMap<>();

		FakeHiker flakyAccount(String username, int failures, String userId, FakePost... posts) {
			flakyRemaining.put(username, failures);
			return account(username, userId, posts);
		}
```

`get`의 프로필 분기에서 `brokenUsernames` 검사 바로 뒤에 삽입:

```java
				Integer remaining = flakyRemaining.get(username);
				if (remaining != null && remaining > 0) {
					flakyRemaining.put(username, remaining - 1);
					throw new HikerFetchException("502 일시 " + username);
				}
```

`setUp`의 잡 조립을 재시도 설정까지 넘기게 바꾼다(테스트는 간격 0 — 실시간 대기가 붙으면 테스트가 분 단위로 늘어진다):

```java
		job = new DailySweepJob(targets, collect, 3, Duration.ZERO);
```

신규 테스트:

```java
	/**
	 * 일시 오류는 알람이 아니라 재시도 대상이다(스펙 §2-3) — 시스템이 당일 안에 수집을 완수해야 한다.
	 * 라운드가 없으면 5xx 한 번에 그 계정의 하루가 통째로 비고, 캠페인은 상태도 안 바뀌어 아무도 모른다.
	 */
	@Test
	void 일시_실패_계정은_스윕_말미_재시도_라운드에서_회복된다() {
		hiker.flakyAccount("flaky_user", 2, "333",
				new FakePost("FFF", "Rare Beginnings 신상", AFTER));
		long flaky = watching("flaky_user", any("Rare Beginnings"), "rk-flaky", FUTURE);

		job.run();

		assertThat(trackedOf(flaky)).isEqualTo("FFF");
		assertThat(hiker.profileCalls).isEqualTo(3);   // 최초 + 라운드 2
	}

	/** 라운드를 다 써도 안 되면 상태는 그대로 둔다 — 다음날 스윕이 자연 회복시킨다(알람 없음). */
	@Test
	void 라운드를_다_써도_실패하면_상태를_건드리지_않는다() {
		hiker.brokenAccount("bad_user");
		long bad = watching("bad_user", any("Rare Beginnings"), "rk-bad", FUTURE);

		job.run();

		assertThat(statusOf(bad)).isEqualTo(TargetStatus.WATCHING);
		assertThat(hiker.profileCalls).isEqualTo(4);   // 최초 + 라운드 3
	}

	/** 결정적 실패(404·비공개)는 재시도 대상이 아니다 — 라운드에 넣으면 종결이 라운드 수만큼 늦어진다. */
	@Test
	void 계정_404는_재시도_라운드에_들어가지_않는다() {
		hiker.missingAccount("gone_user");
		watching("gone_user", any("Rare Beginnings"), "rk-gone", FUTURE);

		job.run();

		assertThat(hiker.profileCalls).isEqualTo(1);
	}
```

`한_계정_수집_오류가_다른_계정_처리를_막지_않는다`는 `bad_user`가 라운드까지 실패하므로 `hiker.profileCalls` 단언을 두지 않는다(이미 없음 — 확인만).

- [ ] **Step 3: 실패 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test --tests "com.celfit.monitoring.hiker.JdkHikerHttpTest" --tests "com.celfit.monitoring.service.DailySweepJobTest"
```
Expected: 컴파일 실패 (`HikerProperties` 5인자 생성자 없음, `DailySweepJob` 4인자 생성자 없음)

- [ ] **Step 4: `HikerProperties` 확장**

```java
package com.celfit.monitoring.hiker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hiker 전송 설정. maxRetries·retryBackoff는 일시 오류(5xx·IO) 전용 재시도다 —
 * 404(대상 부재)는 결정적이라 재시도하지 않는다(스펙 §2-3).
 * 값이 null이면 JdkHikerHttp가 기본값(2회·2초)을 쓴다.
 */
@ConfigurationProperties("monitoring.hiker")
public record HikerProperties(String apiKey, String baseUrl, Duration requestTimeout,
		Integer maxRetries, Duration retryBackoff) {}
```

- [ ] **Step 5: `JdkHikerHttp` 백오프 재시도**

```java
package com.celfit.monitoring.hiker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HikerAPI 실제 전송 — crawler의 동일 관용구를 monitoring 소유로 재작성했다(모듈 간 공유 금지).
 *
 * <p>일시 오류(5xx·IO·타임아웃)는 짧은 백오프로 재시도한다(스펙 §2-3): 예전에는 첫 실패로 그
 * 계정의 하루치 수집이 통째로 비었고, 상태도 안 바뀌어 아무도 눈치채지 못했다.
 * 404({@link SubjectNotFoundException})는 결정적 부재라 재시도하지 않는다 — 다시 쏴도 결과가 같고
 * 종결(FAILED)만 늦어진다.
 */
@Component
public class JdkHikerHttp implements HikerHttp {

	private static final Logger log = LoggerFactory.getLogger(JdkHikerHttp.class);

	private final HttpClient client = HttpClient.newHttpClient();
	private final String baseUrl;
	private final String apiKey;
	private final Duration timeout;
	private final int maxRetries;
	private final Duration retryBackoff;

	public JdkHikerHttp(HikerProperties props) {
		// 키가 없어도 앱은 부팅한다 — 실제 호출 시점(get)에만 검증한다.
		this.baseUrl = props.baseUrl() == null ? "https://api.hikerapi.com" : props.baseUrl();
		this.apiKey = props.apiKey();
		this.timeout = props.requestTimeout() == null ? Duration.ofSeconds(15) : props.requestTimeout();
		this.maxRetries = props.maxRetries() == null ? 2 : Math.max(0, props.maxRetries());
		this.retryBackoff = props.retryBackoff() == null ? Duration.ofSeconds(2) : props.retryBackoff();
	}

	@Override
	public String get(String path) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new HikerFetchException("HIKER_API_KEY 미설정");
		}
		HikerFetchException last = null;
		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			if (attempt > 0) {
				// 선형 백오프 — 상대가 순간 과부하일 때 같은 간격으로 몰아치지 않게 회차만큼 벌린다.
				sleep(retryBackoff.multipliedBy(attempt));
				log.warn("Hiker 재시도 {}/{} — {}", attempt, maxRetries, path);
			}
			try {
				return send(path);
			} catch (HikerFetchException e) {
				last = e;   // SubjectNotFoundException은 HikerFetchException이 아니라 여기서 안 잡힌다(결정적 — 즉시 전파)
			}
		}
		throw last;
	}

	private String send(String path) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(timeout)
				.header("x-access-key", apiKey)
				.header("accept", "application/json")
				.GET().build();
		try {
			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() == 404) {
				// 대상 부재(계정 삭제·개명 등) — 재시도 무의미, 호출자가 종결 처리
				throw new SubjectNotFoundException("Hiker 404: " + res.body());
			}
			if (res.statusCode() >= 300) {
				throw new HikerFetchException("Hiker HTTP " + res.statusCode() + ": " + res.body());
			}
			return res.body();
		} catch (IOException e) {
			throw new HikerFetchException("Hiker 요청 실패: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HikerFetchException("Hiker 요청 중단", e);
		}
	}

	private static void sleep(Duration duration) {
		if (duration.isZero() || duration.isNegative()) {
			return;
		}
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			// 인터럽트는 종료 신호다 — 삼키면 셧다운이 백오프 시간만큼 늘어진다.
			Thread.currentThread().interrupt();
			throw new HikerFetchException("Hiker 재시도 대기 중단", e);
		}
	}
}
```

- [ ] **Step 6: `DailySweepJob` 재시도 라운드**

생성자·필드와 `run()`만 교체(나머지는 Task 2 그대로):

```java
	private final TargetRepository targets;
	private final CollectService collect;
	private final int retryRounds;
	private final Duration retryInterval;

	public DailySweepJob(TargetRepository targets, CollectService collect,
			@Value("${monitoring.sweep.retry-rounds:3}") int retryRounds,
			@Value("${monitoring.sweep.retry-interval:10m}") Duration retryInterval) {
		this.targets = targets;
		this.collect = collect;
		this.retryRounds = retryRounds;
		this.retryInterval = retryInterval;
	}

	public void run() {
		// 만료를 먼저 닫아야 만기 지난 캠페인이 그날 스윕 대상에서 빠진다 — 순서가 바뀌면 종료된 캠페인만큼 콜이 샌다.
		int expired = targets.expireOverdue();
		Set<String> pending = sweepRound(null);
		int accounts = pending.size();   // 라운드 로그용 초기 실패 수(전체 계정 수는 sweepRound가 남긴다)
		for (int round = 1; round <= retryRounds && !pending.isEmpty(); round++) {
			// 간격 × 라운드 — 상대가 회복할 시간을 회차마다 늘려 준다.
			sleep(retryInterval.multipliedBy(round));
			log.info("일시 실패 재시도 라운드 {}/{} — 계정 {}건", round, retryRounds, pending.size());
			pending = sweepRound(pending);
		}
		log.info("스윕 완료 — 만료 {}건, 미해소 일시 실패 {}건(최초 {}건)", expired, pending.size(), accounts);
	}

	/**
	 * 한 바퀴. {@code only}가 null이면 전체, 아니면 그 계정들만 돈다.
	 * 활성 target을 매 라운드 다시 읽는다 — 앞 라운드에서 전환·종결된 행을 그대로 들고 돌면
	 * 이미 TRACKING인 캠페인을 WATCHING으로 착각해 감지를 두 번 한다.
	 *
	 * @return 재시도 여지가 있는 실패(일시 오류) 계정. 결정적 실패(404·비공개)는 이미 종결됐으므로 빠진다.
	 */
	private Set<String> sweepRound(Set<String> only) {
		Map<String, List<TargetRow>> byUsername = targets.findActive().stream()
				.filter(t -> only == null || only.contains(t.username()))
				.collect(Collectors.groupingBy(TargetRow::username));
		Set<String> transientFailures = new LinkedHashSet<>();
		for (var entry : byUsername.entrySet()) {
			try {
				sweepAccount(entry.getKey(), entry.getValue());
			} catch (SubjectNotFoundException e) {
				// 계정 자체가 없어졌다(삭제·개명) — 재시도해도 결과가 같으니 그 계정의 캠페인을 전부 종결한다.
				closeAll(entry.getKey(), entry.getValue(), NOT_FOUND);
			} catch (PrivateAccountException e) {
				// 비공개 전환도 결정적 수집 불가다(설계 §5 "계정 소멸·비공개 등 → FAILED").
				// 일반 실패로 두면 만료일까지 매일 1콜을 태우면서 영원히 WATCHING으로 남는다.
				closeAll(entry.getKey(), entry.getValue(), PRIVATE_ACCOUNT);
			} catch (RuntimeException e) {
				// 재시도 여지가 있는 실패(5xx·타임아웃·셰이프 이상)는 상태를 건드리지 않고 다음 라운드로 넘긴다.
				log.warn("스윕 실패(격리) — 계정 {}: {}", entry.getKey(), e.toString());
				transientFailures.add(entry.getKey());
			}
		}
		return transientFailures;
	}

	/** 라운드 사이 대기. 인터럽트는 종료 신호라 남은 라운드를 포기한다(다음날 스윕이 회복시킨다). */
	private static void sleep(Duration duration) {
		if (duration.isZero() || duration.isNegative()) {
			return;
		}
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("스윕 재시도 대기 중단", e);
		}
	}
```

추가 import: `java.time.Duration`, `java.util.LinkedHashSet`, `org.springframework.beans.factory.annotation.Value`.

> `accounts` 로그 값이 "최초 실패 수"라는 점에 주의 — 전체 계정 수 로그가 필요하면 `sweepRound`가 처리 건수를 함께 돌려주도록 확장할 것(지금은 라운드 로그로 충분).

- [ ] **Step 7: `application.yml` 프로퍼티·스케줄러 풀**

```yaml
spring:
  application:
    name: monitoring
  datasource:
    url: jdbc:postgresql://localhost:5433/monitoring
    username: monitoring
    password: monitoring
  task:
    scheduling:
      pool:
        # 기본 1이면 스윕의 재시도 라운드 대기(최대 간격×라운드 합)가 스케줄러 스레드를 붙잡아
        # 그 사이 알람 발송 틱이 통째로 밀린다 — 스윕과 발송을 분리하려면 최소 2가 필요하다.
        size: 2

server:
  port: 8083   # crawler 8080 · was 8081 · analytics 8082 다음

monitoring:
  hiker:
    api-key: ${HIKER_API_KEY:}
    base-url: https://api.hikerapi.com
    request-timeout: 15s
    max-retries: 2       # 일시 오류(5xx·IO) 전용. 404는 재시도하지 않는다
    retry-backoff: 2s    # 회차마다 × n (2s → 4s)
  sweep:
    retry-rounds: 3      # 스윕 말미 일시 실패 계정 재시도 라운드 수
    retry-interval: 10m  # 라운드 간격 × 라운드 번호 (10m → 20m → 30m)
  schedule:
    sweep-cron: "-"     # "-"=비활성. 운영은 UTC 17:00(KST 02:00)을 env로 주입
  enumerate-pages: 1    # 게시물 열거 페이지 수(최근 N개 범위)
```

- [ ] **Step 8: 통과 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test
```
Expected: BUILD SUCCESSFUL — `JdkHikerHttpTest` 7개, `DailySweepJobTest` 15개 통과

- [ ] **Step 9: 커밋**

```bash
git add monitoring/src monitoring/src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat(monitoring): 일시 오류 당일 재시도 — 콜 백오프 + 스윕 말미 재시도 라운드

5xx·IO 오류로 계정 하루치 수집이 통째로 비던 구멍을 막는다. 전송 계층은 선형 백오프로
기본 2회 재시도(404는 결정적이라 즉시 전파), 스윕은 일시 실패 계정만 모아 간격×라운드로
기본 3라운드 재시도한다. 라운드 대기가 알람 발송 틱을 막지 않도록 스케줄러 풀을 2로 올린다.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 알람 대장 + 적재 5지점

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEventType.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEmailStatus.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEvent.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEventRepository.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/DispatchLane.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmRecorder.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/ExpiredTarget.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/TargetOwner.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/store/PostMetrics.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TargetRepository.java` (expireOverdue RETURNING, findTrackingOwners)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/SnapshotRepository.java` (findLatestPostBefore)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/PostInfo.java` (viewsTrusted)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/hiker/HikerClient.java` (clips 보강 성공 전파)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/SnapshotWriter.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/RegistrationService.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/DailySweepJob.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/alarm/DispatchLaneTest.java` (신규)
- Test: `monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmRecorderTest.java` (신규)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/SnapshotWriterAlarmTest.java` (신규)
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/StoreTest.java`, `service/DailySweepJobTest.java`, `web/RegistrationApiTest.java`, `web/CommandApiTest.java`, `hiker/HikerClientTest.java`

- [ ] **Step 1: 발송 레인 계산 + 테스트** (순수 함수라 먼저 못박는다)

`monitoring/src/main/java/com/celfit/monitoring/alarm/DispatchLane.java`:

```java
package com.celfit.monitoring.alarm;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 발송 레인 — 이벤트를 언제 메일로 내보낼지(스펙 §1-5).
 *
 * <p>즉시 레인은 직접 등록發 수집 시작 전용이다(시딩 수십 건은 디바운스가 1통으로 묶는다).
 * 나머지는 아침 레인: **적재 시점 기준 당일 09:00 KST**로 고정한다. 이미 지났으면 그 시각을
 * 그대로 저장해 다음 틱에 바로 due가 된다 — 다음 날로 미루면 새벽 스윕(02:00)이 만든 이벤트가
 * 하루 늦게 나가고, "오늘 아침에 알림" 기대가 깨진다.
 */
public final class DispatchLane {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalTime MORNING = LocalTime.of(9, 0);

	private DispatchLane() {
	}

	public static Instant immediate(Instant occurredAt) {
		return occurredAt;
	}

	public static Instant morning(Instant occurredAt) {
		return occurredAt.atZone(KST).toLocalDate().atTime(MORNING).atZone(KST).toInstant();
	}
}
```

`monitoring/src/test/java/com/celfit/monitoring/alarm/DispatchLaneTest.java`:

```java
package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 발송 레인 계산 — KST 09:00은 UTC 00:00이다(서머타임 없어 연중 고정). */
class DispatchLaneTest {

	@Test
	void 아침_레인은_적재_당일_KST_09시다() {
		// KST 02:10(새벽 스윕) = UTC 전날 17:10 → 같은 KST 날짜의 09:00 = UTC 00:00
		Instant sweep = Instant.parse("2026-07-29T17:10:00Z");

		assertThat(DispatchLane.morning(sweep)).isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
	}

	/** 09:00을 지나 적재된 이벤트는 다음 날로 미루지 않는다 — 그 시각 그대로 = 다음 틱에 즉시 due. */
	@Test
	void 이미_지난_09시는_그_시각_그대로_저장해_즉시_due가_된다() {
		Instant afternoon = Instant.parse("2026-07-30T05:00:00Z");   // KST 14:00

		Instant dispatchAfter = DispatchLane.morning(afternoon);

		assertThat(dispatchAfter).isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
		assertThat(dispatchAfter).isBefore(afternoon);
	}

	/** KST 자정 직후는 그날 09:00이지 전날 09:00이 아니다 — UTC 기준으로 계산하면 하루 어긋난다. */
	@Test
	void KST_자정_직후는_그날_09시다() {
		Instant justAfterMidnightKst = Instant.parse("2026-07-29T15:05:00Z");   // KST 07-30 00:05

		assertThat(DispatchLane.morning(justAfterMidnightKst))
				.isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
	}

	@Test
	void 즉시_레인은_발생_시각_그대로다() {
		Instant now = Instant.parse("2026-07-30T05:00:00Z");

		assertThat(DispatchLane.immediate(now)).isEqualTo(now);
	}
}
```

- [ ] **Step 2: 실패 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test --tests "com.celfit.monitoring.alarm.DispatchLaneTest"
```
Expected: PASS (4 tests) — 구현을 같은 스텝에 넣었으므로 여기서 바로 통과. 이후 스텝은 테스트 → 실패 → 구현 순서를 지킨다.

- [ ] **Step 3: 알람 어휘·대장 record 작성**

`alarm/AlarmEventType.java`:

```java
package com.celfit.monitoring.alarm;

import java.util.Optional;

/**
 * 알람 이벤트 4종(스펙 §1-3) — 화면 문구와 1:1이고, app 옵트아웃 테이블의 event_type 어휘와도 같다.
 * 이 enum이 어휘의 정본이다(계약 v2 §3·§6).
 */
public enum AlarmEventType {

	/** 게시물 수집 시작 — 직접 등록(즉시 레인) 또는 스윕 첫 감지 자동 전환(아침 레인). */
	COLLECTION_STARTED,
	/** 게시물 수집 종료 — 기간 만료(EXPIRED 전이). */
	COLLECTION_ENDED,
	/** 일부 지표 비공개 — 스냅샷 지표가 값→null로 전환. */
	METRICS_HIDDEN,
	/** 콘텐츠 비공개/삭제/수집 오류 — FAILED 전이(재시도로 해소 불가한 결정적 실패). */
	CONTENT_UNAVAILABLE;

	/** 외부(app 옵트아웃 행)에서 온 문자열 해석 — 모르는 어휘는 무시한다(was가 먼저 배포될 수 있다). */
	public static Optional<AlarmEventType> parse(String value) {
		for (AlarmEventType type : values()) {
			if (type.name().equals(value)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}
}
```

`alarm/AlarmEmailStatus.java`:

```java
package com.celfit.monitoring.alarm;

/**
 * 대장 행의 메일 발송 상태. PENDING·FAILED만 다음 틱의 발송 대상이다 —
 * SKIPPED_* 는 재시도가 무의미한 종결이고(옵트아웃·수신자 부재), SENT는 완료다.
 */
public enum AlarmEmailStatus {

	PENDING, SENT,
	/** 유저가 그 이벤트 종류의 메일을 껐다 — 대장엔 남아 앱 내 알림으로는 계속 서빙된다. */
	SKIPPED_OPTOUT,
	/** 유저 삭제·이메일 부재 — 몇 번을 더 시도해도 보낼 곳이 없다. */
	SKIPPED_NO_RECIPIENT,
	/** 발송 실패(Resend 오류·네트워크) — 다음 틱에 그 행만 다시 시도한다. */
	FAILED
}
```

`alarm/AlarmEvent.java`:

```java
package com.celfit.monitoring.alarm;

import java.time.Instant;

/**
 * alarm_event 한 행 — 알람의 단일 원천(메일 발송 + 앱 내 알림·히스토리).
 * payload는 문안 재료 JSON 문자열이다(username·shortCode·상세) — 발송기가 파싱해 쓴다.
 * email_attempts는 일부러 싣지 않는다: 상한 판정은 due 조회의 WHERE가 DB에서 끝내므로
 * 자바 쪽에 들고 오면 "읽고 비교하는" 두 번째 판정 지점이 생겨 둘이 어긋날 여지만 만든다.
 */
public record AlarmEvent(long id, long targetId, long userId, AlarmEventType eventType,
		String payload, Instant occurredAt, Instant dispatchAfter,
		AlarmEmailStatus emailStatus, Instant emailSentAt) {}
```

`alarm/AlarmEventRepository.java`:

```java
package com.celfit.monitoring.alarm;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** alarm_event 테이블 접점 — 적재·발송 대상 조회·행 단위 상태 종결. */
@Repository
public class AlarmEventRepository {

	private static final RowMapper<AlarmEvent> ROW = (rs, i) -> new AlarmEvent(
			rs.getLong("id"), rs.getLong("target_id"), rs.getLong("user_id"),
			AlarmEventType.valueOf(rs.getString("event_type")), rs.getString("payload"),
			rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("dispatch_after").toInstant(),
			AlarmEmailStatus.valueOf(rs.getString("email_status")),
			rs.getTimestamp("email_sent_at") == null ? null : rs.getTimestamp("email_sent_at").toInstant());

	private final JdbcTemplate db;

	public AlarmEventRepository(JdbcTemplate db) {
		this.db = db;
	}

	public long insert(long targetId, long userId, AlarmEventType type, String payloadJson,
			Instant dispatchAfter) {
		return db.queryForObject("""
				INSERT INTO alarm_event (target_id, user_id, event_type, payload, dispatch_after)
				VALUES (?, ?, ?, ?::jsonb, ?)
				RETURNING id""",
				Long.class, targetId, userId, type.name(), payloadJson, Timestamp.from(dispatchAfter));
	}

	/**
	 * 발송 대상 — 아직 종결되지 않았고 발송 시각이 지난 행.
	 * FAILED를 함께 집는 게 행 단위 재시도다(스펙 §3-1): 다음 틱이 그 행만 다시 보낸다.
	 * 상한(email_attempts)을 WHERE에 두는 이유: 별도 "포기" 상태 전이를 만들지 않아도
	 * 상한에 닿은 행이 조회에서 자연히 빠진다 — FAILED + attempts>=상한이 곧 종결이다.
	 * 유저별로 묶어 1통으로 합치므로 user_id 우선 정렬로 돌려준다.
	 */
	public List<AlarmEvent> findDue(Instant now, int maxAttempts) {
		return db.query("""
				SELECT id, target_id, user_id, event_type, payload::text AS payload, occurred_at,
				       dispatch_after, email_status, email_sent_at
				FROM alarm_event
				WHERE email_status IN ('PENDING', 'FAILED') AND dispatch_after <= ?
				  AND email_attempts < ?
				ORDER BY user_id, occurred_at, id""",
				ROW, Timestamp.from(now), maxAttempts);
	}

	/**
	 * 행 단위 종결 + 시도 횟수 증가.
	 * sentAt은 SENT일 때만 채운다 — 스킵·실패에 발송 시각을 남기면 이력이 거짓말을 한다.
	 * attempts는 성공·실패·스킵을 가리지 않고 올린다: "이 행을 몇 번 집었나"가 상한의 기준이고,
	 * 실패에서만 올리면 성공/실패가 번갈아 나는 경로에서 상한이 영영 안 찬다.
	 */
	public void updateStatus(Collection<Long> ids, AlarmEmailStatus status, Instant sentAt) {
		if (ids.isEmpty()) {
			return;
		}
		db.batchUpdate("""
				UPDATE alarm_event
				SET email_status=?, email_sent_at=?, email_attempts = email_attempts + 1
				WHERE id=?""",
				ids.stream().map(id -> new Object[] {
						status.name(), sentAt == null ? null : Timestamp.from(sentAt), id }).toList());
	}
}
```

- [ ] **Step 4: store 보조 record + 조회 추가**

`store/ExpiredTarget.java`:

```java
package com.celfit.monitoring.store;

/**
 * 만료 스윕이 종결시킨 캠페인 1건 — 수집 종료 알람의 재료.
 * userId는 V3 이전 등록분에서 null일 수 있다(그 캠페인은 알람에서 제외된다).
 */
public record ExpiredTarget(long id, Long userId, String username, String trackedShortCode) {}
```

`store/TargetOwner.java`:

```java
package com.celfit.monitoring.store;

/**
 * 어떤 게시물을 추적 중인 캠페인과 그 수신자 — 지표 비공개 알람의 수신자 해석용.
 * 스냅샷은 게시물 단위라 캠페인 간 공유된다: 같은 게시물을 여러 캠페인이 추적하면 각자 알람을 받는다.
 */
public record TargetOwner(long targetId, Long userId, String username) {}
```

`store/PostMetrics.java`:

```java
package com.celfit.monitoring.store;

/**
 * post_snapshot 지표 6종 + content_type — 직전 스냅샷과의 비교(지표 비공개 판정)에만 쓴다.
 * 취득 불가 지표는 null이다(피드 조회수 등 — 계약 §3 null 규칙).
 */
public record PostMetrics(String contentType, Long likes, Long comments, Long views,
		Long saves, Long shares, Long reposts) {}
```

`TargetRepository`에 추가 / 교체:

```java
	/**
	 * 만료 스윕 — 활성 상태만 EXPIRED로 종결하고 **종결된 행을 돌려준다**.
	 * RETURNING이 필요한 이유: 만료는 이 UPDATE가 유일한 발생 지점이라, 반환이 없으면
	 * "방금 무엇이 끝났는지"를 다시 알아낼 방법이 없다(closed_at 시각 재조회는 경합에 취약).
	 */
	public List<ExpiredTarget> expireOverdue() {
		return db.query("""
				UPDATE target SET status='EXPIRED', closed_at=now()
				WHERE status IN ('WATCHING','TRACKING') AND expires_at < now()
				RETURNING id, user_id, username, tracked_short_code""",
				(rs, i) -> new ExpiredTarget(rs.getLong("id"), rs.getObject("user_id", Long.class),
						rs.getString("username"), rs.getString("tracked_short_code")));
	}

	/** 이 게시물을 추적 중인 활성 캠페인 — 지표 비공개 알람의 수신자(스냅샷은 캠페인 간 공유라 N건일 수 있다). */
	public List<TargetOwner> findTrackingOwners(String shortCode) {
		return db.query("""
				SELECT id, user_id, username FROM target
				WHERE tracked_short_code = ? AND status IN ('WATCHING','TRACKING')""",
				(rs, i) -> new TargetOwner(rs.getLong("id"), rs.getObject("user_id", Long.class),
						rs.getString("username")),
				shortCode);
	}
```

`SnapshotRepository`에 추가:

```java
	/**
	 * 직전 스냅샷 — 지표 비공개 판정 기준. 같은 날 재수집은 upsert로 덮이므로 **그 이전 날짜**만 본다:
	 * 당일 행까지 포함하면 등록 직후 스윕처럼 하루에 두 번 들어오는 경로에서 자기 자신과 비교하게 된다.
	 */
	public Optional<PostMetrics> findLatestPostBefore(String shortCode, LocalDate on) {
		return db.query("""
				SELECT content_type, likes, comments, views, saves, shares, reposts
				FROM post_snapshot
				WHERE short_code = ? AND captured_on < ?
				ORDER BY captured_on DESC LIMIT 1""",
				(rs, i) -> new PostMetrics(rs.getString("content_type"),
						rs.getObject("likes", Long.class), rs.getObject("comments", Long.class),
						rs.getObject("views", Long.class), rs.getObject("saves", Long.class),
						rs.getObject("shares", Long.class), rs.getObject("reposts", Long.class)),
				shortCode, on)
				.stream().findFirst();
	}
```

(import 추가: `java.util.Optional`.)

- [ ] **Step 5: `PostInfo`에 조회수 신뢰 플래그 + `HikerClient` 전파**

`hiker/PostInfo.java`:

```java
package com.celfit.monitoring.hiker;

/**
 * 게시물 스냅샷 원재료 — 6지표(좋아요·댓글·조회·저장·공유·리포스트).
 * 취득 불가 지표는 null이다: 조회·저장·공유는 릴스 전용이고, 피드·캐러셀 응답에는 키 자체가 없다(findings §2).
 * takenAt은 taken_at(epoch seconds) — 핀 고정 게시물 때문에 배열 순서를 믿을 수 없어 재정렬 기준으로 쓴다.
 * rawJson은 이 게시물만이 아니라 **응답 body 전체**다(열거면 그 페이지의 12건 전부).
 * 그래서 감사용 원형 적재는 여기서 하지 않는다 — 전송 계층(RecordingHikerHttp)이 콜 단위로 남긴다.
 *
 * <p>viewsTrusted는 "views가 null인 게 진짜 부재인가"를 하류에 알린다: 열거 경로의 조회수는
 * /v2/user/clips 보강으로만 채워지는데 그 보강은 실패해도 스윕을 계속한다(조용히 null).
 * 이 플래그가 없으면 보강 실패가 "조회수 비공개 전환"으로 오탐돼 알람이 나간다(스펙 §3-2).
 */
public record PostInfo(String shortCode, String username, String contentType, String caption,
		Long takenAt, Long likes, Long comments, Long views, Long saves,
		Long shares, Long reposts, String rawJson, boolean viewsTrusted) {}
```

`hiker/HikerClient.java` — `fetchClipPlays`·`fetchRecentPosts`·`fetchPost`·`toPost` 교체:

```java
	/** 클립 보강 결과 — complete=false면 조회수 null이 "부재"가 아니라 "미취득"이다(오탐 방지 근거). */
	private record ClipPlays(Map<String, Long> plays, boolean complete) {}
```

```java
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		int wanted = Math.max(1, pages);
		ClipPlays clips = fetchClipPlays(userId, wanted);
		Map<String, PostInfo> byCode = new LinkedHashMap<>();
		String cursor = null;
		for (int page = 0; page < wanted; page++) {
			String body = http.get("/v2/user/medias?user_id=" + enc(userId) + pageParam(cursor));
			JsonNode root = root(body);
			int before = byCode.size();
			for (JsonNode item : items(root)) {
				PostInfo post = toPost(item, username, body, clips.plays(), clips.complete());
				byCode.putIfAbsent(post.shortCode(), post);   // 페이지 경계 중복 방지
			}
			// 커서 전진 가드: 커서 파라미터명이 틀리면 API가 같은 1페이지를 계속 돌려주는데
			// dedupe가 이를 조용히 흡수해 "누락 없는 정상"으로 보인다(콜만 2배 과금).
			// 새 숏코드가 0건이면 전진하지 않은 것으로 보고 중단한다.
			if (page > 0 && byCode.size() == before) {
				log.warn("커서 미전진 의심 — user_id {} {}페이지에서 새 게시물 0건, 열거 중단", userId, page + 1);
				break;
			}
			cursor = nextPageId(root);
			if (cursor == null || !moreAvailable(root)) {
				break;
			}
		}
		// 핀 고정 게시물이 배열 맨 앞에 옴(taken_at 2023년 사례 — findings §3) → 게시 시각 내림차순 재정렬
		List<PostInfo> out = new ArrayList<>(byCode.values());
		out.sort(Comparator.comparing(PostInfo::takenAt,
				Comparator.nullsLast(Comparator.reverseOrder())));
		return out;
	}

	/** 릴스 재생수 보강 — /v2/user/clips는 items[].media로 한 겹 더 감싼다. 실패해도 스윕은 계속(조회수만 null). */
	private ClipPlays fetchClipPlays(String userId, int pages) {
		Map<String, Long> plays = new HashMap<>();
		try {
			String cursor = null;
			for (int page = 0; page < pages; page++) {
				JsonNode root = root(http.get("/v2/user/clips?user_id=" + enc(userId) + pageParam(cursor)));
				int before = plays.size();
				for (JsonNode item : root.path("response").path("items")) {
					JsonNode m = item.path("media");
					Long play = firstLong(m, "play_count", "ig_play_count");
					if (play != null) {
						plays.put(m.path("code").asString(), play);
					}
				}
				if (page > 0 && plays.size() == before) {   // 열거와 동일한 커서 전진 가드
					log.warn("클립 커서 미전진 의심 — user_id {} {}페이지에서 새 릴스 0건, 보강 중단", userId, page + 1);
					break;
				}
				cursor = nextPageId(root);
				if (cursor == null || !moreAvailable(root)) {
					break;
				}
			}
		} catch (RuntimeException e) {
			// 삼키되 실패 사실은 남긴다 — 이 플래그가 없으면 하류가 "조회수 비공개"로 오탐한다.
			log.warn("클립 재생수 보강 실패 — user_id {}: {}", userId, e.getMessage());
			return new ClipPlays(plays, false);
		}
		return new ClipPlays(plays, true);
	}

	public PostInfo fetchPost(String shortCode) {
		String body = http.get("/v2/media/by/code?code=" + enc(shortCode));
		List<JsonNode> items = items(root(body));
		if (items.isEmpty()) {
			throw new SubjectNotFoundException("게시물 응답이 비어 있음: " + shortCode);
		}
		// 단건 응답에는 play_count가 그대로 실린다 — clips 보강 경로를 타지 않으므로 조회수는 항상 신뢰 가능하다.
		PostInfo post = toPost(items.getFirst(), null, body, Map.of(), true);
		// 단건 응답에는 usernameHint가 없어 소유 계정을 user.username에서만 얻는다.
		// 없으면 스냅샷 적재(post_snapshot.username NOT NULL)도 target 등록도 불가 → 셰이프 이상으로 본다.
		if (post.username() == null) {
			throw new HikerFetchException("단건 응답에 소유 계정(user.username)이 없음: " + shortCode);
		}
		return post;
	}
```

```java
	private static PostInfo toPost(JsonNode node, String usernameHint, String rawJson,
			Map<String, Long> clipPlays, boolean viewsTrusted) {
		JsonNode m = node.has("media") ? node.path("media") : node;   // clips 열거는 한 겹 더 감쌈
		String code = m.path("code").asString();
		String username = usernameHint != null ? usernameHint : m.path("user").path("username").asString(null);
		// media_type==2는 일반 비디오 피드도 포함 → 릴스 판별은 product_type(findings §4)
		String contentType = "clips".equals(m.path("product_type").asString("")) ? "REELS" : "FEED";
		// v2는 caption.text, v1은 caption_text — caption 자체가 null일 수 있다
		String caption = m.path("caption_text").isMissingNode()
				? m.path("caption").path("text").asString(null) : m.path("caption_text").asString(null);
		// view_count 키는 v2 응답에 부재 → 후보에서 제외. 열거 응답엔 play_count가 없어 clips 머지로 보강.
		Long views = firstLong(m, "play_count", "ig_play_count");
		return new PostInfo(code, username, contentType, caption,
				firstLong(m, "taken_at"),
				firstLong(m, "like_count"), firstLong(m, "comment_count"),
				views != null ? views : clipPlays.get(code),
				firstLong(m, "save_count"),          // 릴스 전용 — 피드·캐러셀은 키 부재 → null
				firstLong(m, "reshare_count"),       // 공유. 릴스 전용
				firstLong(m, "media_repost_count"),  // 리포스트. 전 타입 제공
				rawJson, viewsTrusted);
	}
```

`HikerClientTest`·`StoreTest`·`CommandApiTest`의 `new PostInfo(...)` 호출은 마지막 인자에 `true`를 추가한다.

- [ ] **Step 6: 실패하는 테스트 작성 — `AlarmRecorder`**

`monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmRecorderTest.java`:

```java
package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 알람 적재 규칙 — 수신자 스킵과 METRICS_HIDDEN 오탐 방지가 핵심이다. */
class AlarmRecorderTest {

	private static final Instant FUTURE = Instant.now().plusSeconds(86_400);

	JdbcTemplate db;
	TargetRepository targets;
	SnapshotRepository snapshots;
	AlarmEventRepository events;
	AlarmRecorder recorder;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		snapshots = new SnapshotRepository(db);
		events = new AlarmEventRepository(db);
		recorder = new AlarmRecorder(events, targets, snapshots);
	}

	private long tracking(Long userId, String shortCode, String key) {
		return targets.insert(TargetType.ACCOUNT, userId, "acct_a", null,
				new KeywordRule(List.of(), List.of("샤넬"), List.of()),
				TargetStatus.TRACKING, shortCode, key, FUTURE);
	}

	private List<Map<String, Object>> allEvents() {
		return db.queryForList("SELECT * FROM alarm_event ORDER BY id");
	}

	private PostInfo post(String contentType, Long likes, Long views, Long saves, boolean viewsTrusted) {
		return new PostInfo("SC1", "acct_a", contentType, "캡션", 1_785_000_000L,
				likes, 5L, views, saves, null, 1L, "{}", viewsTrusted);
	}

	private void seedYesterday(String contentType, Long likes, Long views, Long saves) {
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, comments, views, saves, shares, reposts)
				VALUES ('acct_a', 'SC1', DATE '2026-07-29', ?, ?, 5, ?, ?, NULL, 1)""",
				contentType, likes, views, saves);
	}

	@Test
	void 수집_시작_즉시_레인은_발송_시각이_발생_시각과_같다() {
		long id = tracking(7L, "SC1", "rk-1");

		recorder.collectionStartedImmediate(id, 7L, "acct_a", "SC1");

		var row = allEvents().getFirst();
		assertThat(row.get("event_type")).isEqualTo("COLLECTION_STARTED");
		assertThat(row.get("user_id")).isEqualTo(7L);
		assertThat(row.get("email_status")).isEqualTo("PENDING");
		assertThat(row.get("dispatch_after")).isEqualTo(row.get("occurred_at"));
	}

	@Test
	void 자동_전환은_아침_레인이라_발송_시각이_발생_시각과_다르다() {
		long id = tracking(7L, "SC1", "rk-1");

		recorder.collectionStartedScheduled(id, 7L, "acct_a", "SC1");

		var row = allEvents().getFirst();
		assertThat(row.get("dispatch_after")).isNotEqualTo(row.get("occurred_at"));
	}

	/** user_id가 없는 기존 캠페인은 수신자를 알 수 없다 — 적재하면 영원히 못 보내는 행이 쌓인다. */
	@Test
	void 수신자_없는_캠페인은_적재하지_않는다() {
		long id = tracking(null, "SC1", "rk-1");

		recorder.collectionStartedImmediate(id, null, "acct_a", "SC1");
		recorder.collectionEnded(id, null, "acct_a", "SC1");
		recorder.contentUnavailable(id, null, "acct_a", "SC1", "SUBJECT_NOT_FOUND");

		assertThat(allEvents()).isEmpty();
	}

	@Test
	void 종료_실패_이벤트는_사유를_payload에_싣는다() {
		long id = tracking(7L, "SC1", "rk-1");

		recorder.contentUnavailable(id, 7L, "acct_a", "SC1", "PRIVATE_ACCOUNT");

		var row = allEvents().getFirst();
		assertThat(row.get("event_type")).isEqualTo("CONTENT_UNAVAILABLE");
		assertThat(row.get("payload").toString()).contains("PRIVATE_ACCOUNT").contains("SC1");
	}

	@Test
	void 지표가_값에서_null로_바뀌면_비공개_이벤트를_남긴다() {
		tracking(7L, "SC1", "rk-1");
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, null, null, true));

		var row = allEvents().getFirst();
		assertThat(row.get("event_type")).isEqualTo("METRICS_HIDDEN");
		assertThat(row.get("payload").toString()).contains("views").contains("saves");
	}

	/** 피드는 조회·저장·공유 키가 응답에 아예 없다 — 상시 null을 비교하면 매일 오탐이 나간다. */
	@Test
	void 피드의_상시_null_지표는_비교하지_않는다() {
		tracking(7L, "SC1", "rk-1");
		// 어제 릴스로 잡혔다가 오늘 피드로 판정이 갈린 극단 케이스 — 그래도 오탐이면 안 된다.
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("FEED", 100L, null, null, true));

		assertThat(allEvents()).isEmpty();
	}

	/** clips 보강 실패는 조회수를 조용히 null로 만든다 — 그걸 비공개로 읽으면 장애 때마다 알람이 터진다. */
	@Test
	void clips_보강_실패면_조회수_비교를_건너뛴다() {
		tracking(7L, "SC1", "rk-1");
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, null, 20L, false));

		assertThat(allEvents()).isEmpty();
	}

	@Test
	void null에서_값_복귀와_null_유지는_이벤트가_아니다() {
		tracking(7L, "SC1", "rk-1");
		seedYesterday("REELS", 100L, null, null);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, 5000L, null, true));

		assertThat(allEvents()).isEmpty();
	}

	/** 스냅샷은 게시물 단위라 여러 캠페인이 공유한다 — 각 수신자가 자기 알람을 받아야 한다. */
	@Test
	void 같은_게시물을_추적하는_캠페인마다_이벤트가_생긴다() {
		tracking(7L, "SC1", "rk-1");
		tracking(9L, "SC1", "rk-2");
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, null, 20L, true));

		assertThat(allEvents()).hasSize(2);
		assertThat(allEvents().stream().map(r -> r.get("user_id"))).containsExactlyInAnyOrder(7L, 9L);
	}

	/** 추적 캠페인이 없는 게시물(열거로 딸려 온 남의 게시물)은 비교 자체를 하지 않는다. */
	@Test
	void 추적_캠페인이_없으면_이벤트가_없다() {
		seedYesterday("REELS", 100L, 5000L, 20L);

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", 100L, null, null, true));

		assertThat(allEvents()).isEmpty();
	}

	/** 첫 수집은 비교 대상이 없다 — 직전 스냅샷 부재를 "전부 비공개"로 읽으면 등록 다음날 알람이 쏟아진다. */
	@Test
	void 직전_스냅샷이_없으면_이벤트가_없다() {
		tracking(7L, "SC1", "rk-1");

		recorder.recordMetricsHidden(LocalDate.of(2026, 7, 30), post("REELS", null, null, null, true));

		assertThat(allEvents()).isEmpty();
	}
}
```

- [ ] **Step 7: 실패 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test --tests "com.celfit.monitoring.alarm.AlarmRecorderTest"
```
Expected: 컴파일 실패 (`AlarmRecorder` 없음)

- [ ] **Step 8: `AlarmRecorder` 구현**

```java
package com.celfit.monitoring.alarm;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.PostMetrics;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetOwner;
import com.celfit.monitoring.store.TargetRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알람 이벤트 적재의 유일한 입구 — 발송 레인 선택과 수신자 가드가 여기 한 곳에 모인다.
 *
 * <p>예외를 삼키지 않는다: 적재 실패를 조용히 넘기면 알람이 통째로 사라진 걸 아무도 모른다.
 * 호출자 쪽에서 격리한다({@code DailySweepJob}은 이미 계정·캠페인 단위 try/catch를 가진다).
 * 스냅샷 쓰기 경로는 트랜잭션 안이라 더더욱 삼키면 안 된다 — PostgreSQL은 문장 하나가 실패하면
 * 그 트랜잭션 전체를 abort 상태로 만들어, 삼킨 뒤의 후속 SQL이 전부 깨진다.
 */
@Component
public class AlarmRecorder {

	private static final Logger log = LoggerFactory.getLogger(AlarmRecorder.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final AlarmEventRepository events;
	private final TargetRepository targets;
	private final SnapshotRepository snapshots;

	public AlarmRecorder(AlarmEventRepository events, TargetRepository targets,
			SnapshotRepository snapshots) {
		this.events = events;
		this.targets = targets;
		this.snapshots = snapshots;
	}

	/** 게시물 직접 등록 — 사용자가 방금 누른 행동이라 즉시 레인(스펙 §1-5). */
	public void collectionStartedImmediate(long targetId, Long userId, String username, String shortCode) {
		Instant now = Instant.now();
		record(targetId, userId, AlarmEventType.COLLECTION_STARTED,
				basePayload(username, shortCode), DispatchLane.immediate(now));
	}

	/** 스윕 첫 감지 자동 전환 — 새벽에 일어난 일이라 아침 레인. */
	public void collectionStartedScheduled(long targetId, Long userId, String username, String shortCode) {
		record(targetId, userId, AlarmEventType.COLLECTION_STARTED,
				basePayload(username, shortCode), DispatchLane.morning(Instant.now()));
	}

	public void collectionEnded(long targetId, Long userId, String username, String shortCode) {
		record(targetId, userId, AlarmEventType.COLLECTION_ENDED,
				basePayload(username, shortCode), DispatchLane.morning(Instant.now()));
	}

	public void contentUnavailable(long targetId, Long userId, String username, String shortCode,
			String failReason) {
		Map<String, Object> payload = basePayload(username, shortCode);
		payload.put("failReason", failReason);
		record(targetId, userId, AlarmEventType.CONTENT_UNAVAILABLE, payload,
				DispatchLane.morning(Instant.now()));
	}

	/**
	 * 지표 비공개 감지 — **upsert 직전**에 불러야 한다(직전 스냅샷이 아직 남아 있어야 비교가 성립).
	 * 추적 캠페인 조회를 먼저 하는 이유: 계정 열거는 남의 게시물까지 12건씩 들고 오는데,
	 * 그 전부에 대해 직전 스냅샷을 읽으면 스윕 한 번에 불필요한 쿼리가 계정당 열두 번씩 늘어난다.
	 */
	public void recordMetricsHidden(LocalDate capturedOn, PostInfo post) {
		List<TargetOwner> owners = targets.findTrackingOwners(post.shortCode());
		if (owners.isEmpty()) {
			return;
		}
		PostMetrics previous = snapshots.findLatestPostBefore(post.shortCode(), capturedOn).orElse(null);
		if (previous == null) {
			return;   // 첫 수집 — 비교 대상 없음
		}
		List<String> hidden = hiddenMetrics(post, previous);
		if (hidden.isEmpty()) {
			return;
		}
		for (TargetOwner owner : owners) {
			Map<String, Object> payload = basePayload(owner.username(), post.shortCode());
			payload.put("metrics", hidden);
			record(owner.targetId(), owner.userId(), AlarmEventType.METRICS_HIDDEN, payload,
					DispatchLane.morning(Instant.now()));
		}
	}

	/**
	 * 값 → null로 바뀐 지표 목록. 오탐 규칙 두 가지가 여기 있다(스펙 §3-2):
	 * ① 조회·저장·공유는 릴스 전용이라 피드에서는 상시 null — 비교 대상에서 뺀다.
	 * ② 릴스 조회수는 clips 보강이 성공했을 때만 신뢰한다 — 보강 실패도 views를 null로 만든다.
	 * null→null·null→값(복귀)은 이벤트가 아니다. 같은 지표의 반복 알람은 "전이"만 잡으므로 자연히 1회다.
	 */
	static List<String> hiddenMetrics(PostInfo now, PostMetrics before) {
		boolean reels = "REELS".equals(now.contentType());
		List<String> hidden = new ArrayList<>();
		addIfHidden(hidden, "likes", before.likes(), now.likes(), true);
		addIfHidden(hidden, "comments", before.comments(), now.comments(), true);
		addIfHidden(hidden, "views", before.views(), now.views(), reels && now.viewsTrusted());
		addIfHidden(hidden, "saves", before.saves(), now.saves(), reels);
		addIfHidden(hidden, "shares", before.shares(), now.shares(), reels);
		addIfHidden(hidden, "reposts", before.reposts(), now.reposts(), true);   // 전 타입 제공(findings §2)
		return hidden;
	}

	private static void addIfHidden(List<String> out, String metric, Long before, Long after,
			boolean comparable) {
		if (comparable && before != null && after == null) {
			out.add(metric);
		}
	}

	/** payload는 null 값을 담을 수 있어야 한다(shortCode 미정 캠페인) — Map.of는 null을 거부한다. */
	private static Map<String, Object> basePayload(String username, String shortCode) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", username);
		payload.put("shortCode", shortCode);
		return payload;
	}

	private void record(long targetId, Long userId, AlarmEventType type, Map<String, Object> payload,
			Instant dispatchAfter) {
		if (userId == null) {
			// V3 이전 등록분 — 백필 런북 전까지는 수신자를 알 방법이 없다(스펙 §2-1·§7).
			log.warn("수신자 미상 — 알람 적재 스킵: target {} {}", targetId, type);
			return;
		}
		events.insert(targetId, userId, type, JSON.writeValueAsString(payload), dispatchAfter);
	}
}
```

- [ ] **Step 9: 실패하는 테스트 작성 — 5지점 연동**

`monitoring/src/test/java/com/celfit/monitoring/service/SnapshotWriterAlarmTest.java` (신규):

```java
package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.alarm.AlarmEventRepository;
import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 스냅샷 쓰기 경로의 지표 비공개 적재 — 비교는 upsert **직전**에 일어나야 한다.
 * 순서가 뒤집히면 방금 쓴 값과 자기 자신을 비교해 전이가 영원히 안 잡힌다.
 */
class SnapshotWriterAlarmTest {

	JdbcTemplate db;
	TargetRepository targets;
	SnapshotWriter writer;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		var snapshots = new SnapshotRepository(db);
		var recorder = new AlarmRecorder(new AlarmEventRepository(db), targets, snapshots);
		writer = new SnapshotWriter(snapshots, recorder);
	}

	private PostInfo post(Long views) {
		return new PostInfo("SC1", "acct_a", "REELS", "캡션", 1_785_000_000L,
				100L, 5L, views, 20L, 3L, 1L, "{}", true);
	}

	private long alarmCount() {
		return db.queryForObject("SELECT count(*) FROM alarm_event", Long.class);
	}

	@Test
	void 단건_저장은_직전_스냅샷과_비교해_비공개를_적재한다() {
		targets.insert(TargetType.ACCOUNT, 7L, "acct_a", null,
				new KeywordRule(List.of(), List.of("샤넬"), List.of()),
				TargetStatus.TRACKING, "SC1", "rk-1", Instant.now().plusSeconds(86_400));

		writer.savePost(LocalDate.of(2026, 7, 29), post(5000L));
		assertThat(alarmCount()).isZero();          // 첫날은 비교 대상 없음

		writer.savePost(LocalDate.of(2026, 7, 30), post(null));

		assertThat(alarmCount()).isEqualTo(1);
		// 오늘 값도 정상 적재됐다 — 알람이 upsert를 가로채면 안 된다.
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='SC1'""", Long.class)).isEqualTo(2);
	}

	@Test
	void 계정_저장도_게시물마다_비교한다() {
		targets.insert(TargetType.ACCOUNT, 7L, "acct_a", null,
				new KeywordRule(List.of(), List.of("샤넬"), List.of()),
				TargetStatus.TRACKING, "SC1", "rk-1", Instant.now().plusSeconds(86_400));
		var profile = new ProfileInfo("acct_a", "1", 100L, 10L, 5L, "{}");

		writer.saveAccount("acct_a", LocalDate.of(2026, 7, 29), profile, List.of(post(5000L)));
		writer.saveAccount("acct_a", LocalDate.of(2026, 7, 30), profile, List.of(post(null)));

		assertThat(alarmCount()).isEqualTo(1);
	}
}
```

`DailySweepJobTest`에 알람 단언 3개 추가(`setUp`의 잡 조립도 `AlarmRecorder`를 받게 바꾼다):

```java
	AlarmRecorder alarms;

	@BeforeEach
	void setUp() {
		...
		var snapshotRepo = new SnapshotRepository(db);
		alarms = new AlarmRecorder(new AlarmEventRepository(db), targets, snapshotRepo);
		var collect = new CollectService(client, new SnapshotWriter(snapshotRepo, alarms), 1);
		job = new DailySweepJob(targets, collect, alarms, 3, Duration.ZERO);
	}

	private List<String> alarmTypes() {
		return db.queryForList("SELECT event_type FROM alarm_event ORDER BY id", String.class);
	}
```

```java
	@Test
	void 자동_전환은_수집_시작_알람을_아침_레인으로_남긴다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER));
		watching("someuser", any("Rare Beginnings"), "rk-a", FUTURE);

		job.run();

		assertThat(alarmTypes()).containsExactly("COLLECTION_STARTED");
		assertThat(db.queryForObject("""
				SELECT dispatch_after <> occurred_at FROM alarm_event""", Boolean.class)).isTrue();
	}

	@Test
	void 만료_종결은_수집_종료_알람을_남긴다() {
		hiker.account("someuser", "111", new FakePost("AAA", "Rare Beginnings 신상", AFTER));
		watching("someuser", any("Rare Beginnings"), "rk-expired", PAST);

		job.run();

		assertThat(alarmTypes()).containsExactly("COLLECTION_ENDED");
	}

	/** 계정 소멸·비공개는 재시도로 해소되지 않는다 — 사용자에게 알려야 다른 게시물로 재등록한다. */
	@Test
	void 결정적_실패_종결은_콘텐츠_이용불가_알람을_남긴다() {
		hiker.missingAccount("gone_user").privateAccount("shy_user");
		watching("gone_user", any("Rare Beginnings"), "rk-gone", FUTURE);
		watching("shy_user", any("Rare Beginnings"), "rk-shy", FUTURE);

		job.run();

		assertThat(alarmTypes()).containsExactly("CONTENT_UNAVAILABLE", "CONTENT_UNAVAILABLE");
	}
```

`RegistrationApiTest`에 즉시 레인 단언 추가(`setUp`의 정리 구문에 `db.update("DELETE FROM alarm_event")`를 **target 삭제보다 먼저** 넣는다):

```java
	/** 게시물 직접 등록은 그 자리에서 수집이 시작된다 — 사용자가 방금 누른 행동이라 즉시 레인이다. */
	@Test
	void 게시물_등록은_즉시_레인_수집_시작_알람을_남긴다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isCreated());

		assertThat(db.queryForObject("""
				SELECT event_type FROM alarm_event""", String.class)).isEqualTo("COLLECTION_STARTED");
		assertThat(db.queryForObject("""
				SELECT dispatch_after = occurred_at FROM alarm_event""", Boolean.class)).isTrue();
		assertThat(db.queryForObject("SELECT user_id FROM alarm_event", Long.class)).isEqualTo(7L);
	}

	/** 계정 등록은 아직 수집 시작이 아니다(WATCHING) — 여기서 알람이 나가면 "시작"이 두 번 온다. */
	@Test
	void 계정_등록은_알람을_남기지_않는다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated());

		assertThat(db.queryForObject("SELECT count(*) FROM alarm_event", Long.class)).isZero();
	}

	/** replay(200)는 새 캠페인이 아니다 — 재시도마다 알람이 쌓이면 사용자가 같은 메일을 반복해 받는다. */
	@Test
	void 같은_키_replay는_알람을_추가로_남기지_않는다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isOk());

		assertThat(db.queryForObject("SELECT count(*) FROM alarm_event", Long.class)).isEqualTo(1);
	}
```

`StoreTest.만료_스윕은_활성만_EXPIRED로`의 반환 단언 교체:

```java
		var expired = targets.expireOverdue();

		assertThat(expired).hasSize(1);
		assertThat(expired.getFirst().username()).isEqualTo("acct_a");
		assertThat(expired.getFirst().trackedShortCode()).isEqualTo("SC1");
```

`CommandApiTest.setUp`에도 `db.update("DELETE FROM alarm_event")`를 target 삭제 앞에 추가한다.

- [ ] **Step 10: 실패 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test
```
Expected: 컴파일 실패 (`SnapshotWriter` 2인자 생성자 없음, `DailySweepJob` 5인자 생성자 없음)

- [ ] **Step 11: `SnapshotWriter` 연동**

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.SnapshotRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스냅샷 쓰기의 트랜잭션 경계 — 수집 1회분을 원자적으로 커밋한다.
 *
 * <p>{@link CollectService}와 분리된 빈인 이유는 두 가지다.
 * ① Hiker 호출(최대 수 초)을 트랜잭션 밖에 두려면 fetch와 write의 경계가 갈라져야 한다 —
 *   한 클래스 안에서 {@code @Transactional} 메서드를 자기 호출하면 프록시를 타지 않아 경계가 사라진다.
 * ② 트랜잭션이 fetch를 감싸면 Hiker 레이턴시 동안 DB 커넥션을 붙잡고 있고,
 *   수집 실패 시 이미 나간 콜의 원형 적재(RecordingHikerHttp)까지 같이 롤백돼 감사 기록이 사라진다.
 *
 * <p>지표 비공개 알람이 여기 붙는 이유: 직전 스냅샷과의 비교는 **덮어쓰기 전에만** 가능하다.
 * 알람 적재를 같은 트랜잭션에 두는 것도 의도다 — 스냅샷이 롤백되면 그 비교로 만든 알람도 함께 사라져야 한다.
 */
@Component
public class SnapshotWriter {

	private final SnapshotRepository snapshots;
	private final AlarmRecorder alarms;

	public SnapshotWriter(SnapshotRepository snapshots, AlarmRecorder alarms) {
		this.snapshots = snapshots;
		this.alarms = alarms;
	}

	/** 계정 1회 수집분 — 프로필 1행 + 게시물 N행을 한 트랜잭션으로. */
	@Transactional
	public void saveAccount(String username, LocalDate on, ProfileInfo profile, List<PostInfo> posts) {
		snapshots.upsertProfile(username, on, profile);
		posts.forEach(p -> savePostRow(on, p));
	}

	@Transactional
	public void savePost(LocalDate on, PostInfo post) {
		savePostRow(on, post);
	}

	/** 순서 고정: 비교 → upsert. 뒤집으면 방금 쓴 값과 자기 자신을 비교해 전이가 영원히 안 잡힌다. */
	private void savePostRow(LocalDate on, PostInfo post) {
		alarms.recordMetricsHidden(on, post);
		snapshots.upsertPost(on, post);
	}
}
```

- [ ] **Step 12: `RegistrationService` 즉시 레인 적재**

필드·생성자에 `AlarmRecorder alarms`를 추가하고 `registerPost`의 반환 직전에 적재:

```java
	private final CollectService collect;
	private final TargetRepository targets;
	private final AlarmRecorder alarms;

	public RegistrationService(CollectService collect, TargetRepository targets, AlarmRecorder alarms) {
		this.collect = collect;
		this.targets = targets;
		this.alarms = alarms;
	}
```

```java
		targets.touchFetched(id);
		// 게시물 직접 등록은 등록 = 수집 시작이다. replay 경로는 여기 오지 않으므로 재시도로 중복되지 않는다.
		// 적재가 실패하면 등록도 500으로 실패하지만, 같은 registrationKey replay로 안전하게 복구된다.
		alarms.collectionStartedImmediate(id, cmd.userId(), post.username(), shortCode);
		var snapshot = new PostSnapshot(new PostSnapshot.Post(post.shortCode(), post.contentType(),
				post.likes(), post.comments(), post.views(), post.saves(), post.shares(), post.reposts()));
		return new Result(id, TargetStatus.TRACKING.name(), snapshot, false);
```

(계정 등록에는 적재하지 않는다 — WATCHING은 아직 수집 시작이 아니다.)

- [ ] **Step 13: `DailySweepJob` 알람 3지점**

생성자에 `AlarmRecorder alarms`를 추가하고(`@Value` 파라미터 앞에 둔다), `run()`·`sweepTarget`·`closeFailed`를 갱신:

```java
	public DailySweepJob(TargetRepository targets, CollectService collect, AlarmRecorder alarms,
			@Value("${monitoring.sweep.retry-rounds:3}") int retryRounds,
			@Value("${monitoring.sweep.retry-interval:10m}") Duration retryInterval) {
```

```java
	public void run() {
		// 만료를 먼저 닫아야 만기 지난 캠페인이 그날 스윕 대상에서 빠진다 — 순서가 바뀌면 종료된 캠페인만큼 콜이 샌다.
		List<ExpiredTarget> expired = targets.expireOverdue();
		for (ExpiredTarget e : expired) {
			try {
				alarms.collectionEnded(e.id(), e.userId(), e.username(), e.trackedShortCode());
			} catch (RuntimeException ex) {
				// 알람 하나가 스윕 전체를 막으면 그날 수집이 통째로 빈다 — 로그만 남기고 계속한다.
				log.warn("만료 알람 적재 실패(격리) — target {}: {}", e.id(), ex.toString());
			}
		}
		Set<String> pending = sweepRound(null);
		...
		log.info("스윕 완료 — 만료 {}건, 미해소 일시 실패 {}건(최초 {}건)", expired.size(), pending.size(), accounts);
	}
```

```java
			if (detected != null) {
				targets.markTracking(t.id(), detected.shortCode());
				alarms.collectionStartedScheduled(t.id(), t.userId(), t.username(), detected.shortCode());
				log.info("첫 감지 자동 전환 — target {} → TRACKING {}", t.id(), detected.shortCode());
				targets.touchFetched(t.id());
				return;
			}
```

```java
	private void closeFailed(TargetRow t, String failReason) {
		try {
			targets.close(t.id(), TargetStatus.FAILED, failReason);
			alarms.contentUnavailable(t.id(), t.userId(), t.username(), t.trackedShortCode(), failReason);
		} catch (RuntimeException e) {
			log.warn("종결 실패(격리) — target {} → FAILED/{}: {}", t.id(), failReason, e.toString());
		}
	}
```

(import 추가: `com.celfit.monitoring.alarm.AlarmRecorder`, `com.celfit.monitoring.store.ExpiredTarget`.)

- [ ] **Step 14: 통과 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test
```
Expected: BUILD SUCCESSFUL — `AlarmRecorderTest` 11개, `DispatchLaneTest` 4개, `SnapshotWriterAlarmTest` 2개, `DailySweepJobTest` 18개, `RegistrationApiTest` 16개 통과

- [ ] **Step 15: 커밋**

```bash
git add monitoring/src
git commit -m "$(cat <<'EOF'
feat(monitoring): 알람 이벤트 대장 + 적재 5지점 — 워터마크 없는 id 기반 원천

alarm_event에 직접 등록(즉시 레인)·자동 전환·만료·지표 비공개·결정적 실패 5지점을 적재한다.
만료는 expireOverdue를 RETURNING으로 개조해 "방금 무엇이 끝났는지"를 잃지 않게 했고,
지표 비공개는 upsert 직전 비교 + 오탐 2규칙(피드 상시 null 제외, clips 보강 실패 시 조회수 스킵)을
건다. PostInfo에 viewsTrusted를 실어 보강 실패가 "비공개 전환"으로 오독되는 경로를 끊는다.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 발송 크론 (디바운스·옵트아웃·유저당 1통)

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/mail/{MailSender,LoggingMailSender,ResendMailSender,MailSendException,MailConfig}.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmRecipientReader.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmMailComposer.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmDispatchJob.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmDispatchScheduler.java`
- Create: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmConfig.java`
- Modify: `monitoring/src/main/resources/application.yml`
- Modify: `monitoring/src/test/java/com/celfit/monitoring/testsupport/TestDb.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmMailComposerTest.java` (신규)
- Test: `monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmDispatchJobTest.java` (신규)
- Test: `monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmDispatchSchedulerTest.java` (신규)

- [ ] **Step 1: 메일 발송 포트 이식** (was 07-19 관용구 — 모듈 간 Java 공유 금지라 monitoring 소유로 재작성)

`mail/MailSender.java`:

```java
package com.celfit.monitoring.mail;

/** 메일 발송 포트 — 실패는 MailSendException(호출측이 알람 행을 FAILED로 종결하고 다음 틱에 재시도). */
public interface MailSender {

	void send(String to, String subject, String text);
}
```

`mail/MailSendException.java`:

```java
package com.celfit.monitoring.mail;

/** 발송 실패 — 재시도 가능(다음 5분 틱이 같은 행만 다시 보낸다). */
public class MailSendException extends RuntimeException {

	public MailSendException(String message, Throwable cause) {
		super(message, cause);
	}
}
```

`mail/LoggingMailSender.java`:

```java
package com.celfit.monitoring.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** RESEND_API_KEY 미설정 시 대체 — 발송 대신 내용을 로그로 출력(로컬 개발·통합 테스트용). */
public class LoggingMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

	@Override
	public void send(String to, String subject, String text) {
		log.info("메일 발송(로깅 모드) to={} subject={} text={}", to, subject, text);
	}
}
```

`mail/ResendMailSender.java`:

```java
package com.celfit.monitoring.mail;

import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * Resend HTTPS API 발송(POST /emails) — SMTP 불사용이라 오라클 아웃바운드 25포트 차단과 무관.
 * 비2xx·네트워크 오류는 MailSendException으로 감싼다.
 */
public class ResendMailSender implements MailSender {

	private final RestClient restClient;
	private final String from;

	public ResendMailSender(RestClient restClient, String from) {
		this.restClient = restClient;
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String text) {
		try {
			restClient.post().uri("/emails")
					.body(Map.of("from", from, "to", new String[] {to}, "subject", subject, "text", text))
					.retrieve()
					.toBodilessEntity();
		} catch (RuntimeException e) {
			throw new MailSendException("Resend 발송 실패: " + e.getMessage(), e);
		}
	}
}
```

`mail/MailConfig.java`:

```java
package com.celfit.monitoring.mail;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 발송 구현 선택 — monitoring.mail.resend-api-key가 비어 있으면 LoggingMailSender(로컬·테스트),
 * 있으면 ResendMailSender. 프로파일 분기 대신 키 유무 단일 기준(설정 실수여도 부팅은 된다).
 */
@Configuration
public class MailConfig {

	private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

	@Bean
	MailSender mailSender(@Value("${monitoring.mail.resend-api-key:}") String apiKey,
			@Value("${monitoring.mail.from:hypenow <no-reply@hypenow.io>}") String from) {
		if (apiKey == null || apiKey.isBlank()) {
			log.warn("RESEND_API_KEY 미설정 — 메일을 실발송하지 않고 로그로만 출력한다(운영이라면 설정 누락)");
			return new LoggingMailSender();
		}
		// 크론 스레드가 무한 블록되면 다음 틱까지 밀린다 — connect 5초·read 10초
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
		requestFactory.setReadTimeout(Duration.ofSeconds(10));
		RestClient restClient = RestClient.builder()
				.requestFactory(requestFactory)
				.baseUrl("https://api.resend.com")
				.defaultHeader("Authorization", "Bearer " + apiKey)
				.build();
		log.info("Resend 메일 발송 활성 from={}", from);
		return new ResendMailSender(restClient, from);
	}
}
```

- [ ] **Step 2: 테스트 픽스처 — app 스키마 흉내**

`testsupport/TestDb.java`에 메서드 추가(was Flyway를 monitoring 테스트에서 돌리지 않는다 — 모듈 경계):

```java
	/**
	 * 알람 발송기가 읽는 analysis DB app 스키마 흉내 — 계약 v2 §6이 정의한 **두 객체만** 만든다.
	 * was Flyway를 여기서 돌리지 않는 이유: monitoring이 was 마이그레이션에 빌드 의존을 갖게 되고,
	 * 실제로 읽는 컬럼(email·event_type)보다 훨씬 넓은 표면을 테스트가 보증하게 된다.
	 * resetAndMigrate가 public·raw만 지우므로 app은 여기서 따로 초기화한다.
	 */
	public static void resetAppFixture(JdbcTemplate db) {
		db.update("DROP SCHEMA IF EXISTS app CASCADE");
		db.update("CREATE SCHEMA app");
		db.update("""
				CREATE TABLE app.users (
				    id    bigserial PRIMARY KEY,
				    email text
				)""");
		db.update("""
				CREATE TABLE app.monitoring_email_opt_outs (
				    user_id    bigint      NOT NULL,
				    event_type text        NOT NULL,
				    created_at timestamptz NOT NULL DEFAULT now(),
				    PRIMARY KEY (user_id, event_type)
				)""");
	}
```

- [ ] **Step 3: 실패하는 테스트 작성 — 문안**

`monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmMailComposerTest.java`:

```java
package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 임시 문안 — 정식 카피·딥링크는 후속이라 여기서 지키는 건 "재료가 다 실리는가"뿐이다. */
class AlarmMailComposerTest {

	private static final Instant T = Instant.parse("2026-07-30T00:00:00Z");

	private final AlarmMailComposer composer = new AlarmMailComposer();

	private static AlarmEvent event(long id, AlarmEventType type, String payload) {
		return new AlarmEvent(id, 1L, 7L, type, payload, T, T, AlarmEmailStatus.PENDING, null);
	}

	@Test
	void 유저_한_통에_이벤트_종류별_구획이_실린다() {
		var mail = composer.compose(List.of(
				event(1, AlarmEventType.COLLECTION_STARTED,
						"{\"username\":\"acct_a\",\"shortCode\":\"SC1\"}"),
				event(2, AlarmEventType.METRICS_HIDDEN,
						"{\"username\":\"acct_a\",\"shortCode\":\"SC1\",\"metrics\":[\"views\",\"saves\"]}")));

		assertThat(mail.subject()).contains("2건");
		assertThat(mail.text())
				.contains("게시물 수집 시작")
				.contains("일부 지표 비공개")
				.contains("@acct_a")
				.contains("SC1")
				// 지표 이름은 사용자 화면 어휘로 — 영문 키가 그대로 나가면 메일이 로그처럼 보인다
				.contains("조회수")
				.contains("저장");
	}

	@Test
	void 콘텐츠_이용불가는_사유를_함께_보여준다() {
		var mail = composer.compose(List.of(event(1, AlarmEventType.CONTENT_UNAVAILABLE,
				"{\"username\":\"acct_a\",\"shortCode\":\"SC1\",\"failReason\":\"PRIVATE_ACCOUNT\"}")));

		assertThat(mail.text()).contains("콘텐츠 비공개/삭제/수집 오류").contains("PRIVATE_ACCOUNT");
	}

	/** payload가 깨져도 메일은 나가야 한다 — 문안 조립 실패가 발송 전체를 막으면 알람이 통째로 멈춘다. */
	@Test
	void 깨진_payload도_문안을_만든다() {
		var mail = composer.compose(List.of(event(1, AlarmEventType.COLLECTION_ENDED, "{}")));

		assertThat(mail.text()).contains("게시물 수집 종료");
	}
}
```

- [ ] **Step 4: 실패하는 테스트 작성 — 발송 잡**

`monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmDispatchJobTest.java`:

```java
package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.mail.MailSendException;
import com.celfit.monitoring.mail.MailSender;
import com.celfit.monitoring.testsupport.TestDb;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 5분 틱 발송 — 디바운스·옵트아웃·유저당 1통·행 단위 재시도. */
class AlarmDispatchJobTest {

	/** 발송 fake — 수신 기록을 남기고, fail=true면 실패로 전환한다(Mockito 대신 monitoring 관례). */
	static final class FakeMailSender implements MailSender {

		record Sent(String to, String subject, String text) {}

		final List<Sent> sent = new ArrayList<>();
		/** 성공·실패를 가리지 않은 호출 횟수 — 재시도 상한 검증에 쓴다(sent는 성공분만 담는다). */
		int attempts;
		boolean fail;

		@Override
		public void send(String to, String subject, String text) {
			attempts++;
			if (fail) {
				throw new MailSendException("발송 실패(테스트)", null);
			}
			sent.add(new Sent(to, subject, text));
		}
	}

	private static final Instant NOW = Instant.parse("2026-07-30T01:00:00Z");

	JdbcTemplate db;
	AlarmEventRepository events;
	FakeMailSender mail;
	AlarmDispatchJob job;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		TestDb.resetAppFixture(db);
		events = new AlarmEventRepository(db);
		mail = new FakeMailSender();
		job = new AlarmDispatchJob(events, new AlarmRecipientReader(ds), new AlarmMailComposer(),
				mail, Duration.ofMinutes(10), 5, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private long user(long id, String email) {
		db.update("INSERT INTO app.users (id, email) VALUES (?, ?)", id, email);
		return id;
	}

	private void optOut(long userId, AlarmEventType type) {
		db.update("INSERT INTO app.monitoring_email_opt_outs (user_id, event_type) VALUES (?, ?)",
				userId, type.name());
	}

	/** occurredAt을 직접 지정한다 — 디바운스 판정이 "얼마나 최근인가"에 달려 있어서. */
	private long event(long userId, AlarmEventType type, Instant occurredAt, Instant dispatchAfter) {
		return db.queryForObject("""
				INSERT INTO alarm_event (target_id, user_id, event_type, payload, occurred_at, dispatch_after)
				VALUES (1, ?, ?, '{"username":"acct_a","shortCode":"SC1"}'::jsonb, ?, ?)
				RETURNING id""",
				Long.class, userId, type.name(), Timestamp.from(occurredAt), Timestamp.from(dispatchAfter));
	}

	private String statusOf(long eventId) {
		return db.queryForObject("SELECT email_status FROM alarm_event WHERE id=?", String.class, eventId);
	}

	private static final Instant SETTLED = NOW.minusSeconds(3600);   // 디바운스 창 밖

	@Test
	void 유저당_한_통으로_묶어_보내고_행을_SENT로_닫는다() {
		user(7, "a@test.io");
		long e1 = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		long e2 = event(7, AlarmEventType.METRICS_HIDDEN, SETTLED, SETTLED);

		job.run();

		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.getFirst().to()).isEqualTo("a@test.io");
		assertThat(statusOf(e1)).isEqualTo("SENT");
		assertThat(statusOf(e2)).isEqualTo("SENT");
		assertThat(db.queryForObject("""
				SELECT email_sent_at IS NOT NULL FROM alarm_event WHERE id=?""", Boolean.class, e1))
				.isTrue();
	}

	@Test
	void 유저가_다르면_통도_다르다() {
		user(7, "a@test.io");
		user(9, "b@test.io");
		event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		event(9, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();

		assertThat(mail.sent).hasSize(2);
	}

	/**
	 * 디바운스 — 시딩 수십 건을 연속 등록하면 즉시 레인 이벤트가 몰아친다.
	 * 대기 없이 보내면 등록할 때마다 메일이 한 통씩 나가 받은편지함이 찢어진다.
	 */
	@Test
	void 방금_들어온_이벤트가_있으면_이번_틱은_건너뛴다() {
		user(7, "a@test.io");
		event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		long fresh = event(7, AlarmEventType.COLLECTION_STARTED, NOW.minusSeconds(60), NOW.minusSeconds(60));

		job.run();

		assertThat(mail.sent).isEmpty();
		assertThat(statusOf(fresh)).isEqualTo("PENDING");   // 다음 틱에 함께 나간다
	}

	@Test
	void 발송_시각이_안_된_행은_대상이_아니다() {
		user(7, "a@test.io");
		long future = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, NOW.plusSeconds(3600));

		job.run();

		assertThat(mail.sent).isEmpty();
		assertThat(statusOf(future)).isEqualTo("PENDING");
	}

	/** 옵트아웃은 메일만 끈다 — 대장 행은 남아 앱 내 알림으로 계속 서빙된다(스펙 §3-3). */
	@Test
	void 옵트아웃_이벤트는_SKIPPED_OPTOUT으로_닫히고_나머지만_발송된다() {
		user(7, "a@test.io");
		optOut(7, AlarmEventType.METRICS_HIDDEN);
		long muted = event(7, AlarmEventType.METRICS_HIDDEN, SETTLED, SETTLED);
		long live = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();

		assertThat(statusOf(muted)).isEqualTo("SKIPPED_OPTOUT");
		assertThat(statusOf(live)).isEqualTo("SENT");
		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.getFirst().text()).doesNotContain("일부 지표 비공개");
	}

	@Test
	void 전부_옵트아웃이면_메일을_보내지_않는다() {
		user(7, "a@test.io");
		optOut(7, AlarmEventType.COLLECTION_STARTED);
		long muted = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();

		assertThat(mail.sent).isEmpty();
		assertThat(statusOf(muted)).isEqualTo("SKIPPED_OPTOUT");
	}

	/** 옵트아웃 행이 없으면 켜짐이 기본 — 빈 테이블로 전원 on(설정 화면과 1:1). */
	@Test
	void 옵트아웃_행이_없으면_기본_발송이다() {
		user(7, "a@test.io");
		event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();

		assertThat(mail.sent).hasSize(1);
	}

	/** 유저 삭제·이메일 부재는 재시도해도 보낼 곳이 없다 — FAILED로 두면 매 틱 헛돈다. */
	@Test
	void 수신자가_없으면_SKIPPED_NO_RECIPIENT로_종결한다() {
		user(7, null);
		long orphan = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		long ghost = event(8, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);   // app.users에 없는 유저

		job.run();

		assertThat(statusOf(orphan)).isEqualTo("SKIPPED_NO_RECIPIENT");
		assertThat(statusOf(ghost)).isEqualTo("SKIPPED_NO_RECIPIENT");
		assertThat(mail.sent).isEmpty();
	}

	/** 발송 실패는 행 단위 FAILED — 다음 틱이 그 행만 다시 보낸다(전체 재발송 없음). */
	@Test
	void 발송_실패는_FAILED로_남고_다음_틱에_다시_시도된다() {
		user(7, "a@test.io");
		long e1 = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		mail.fail = true;

		job.run();
		assertThat(statusOf(e1)).isEqualTo("FAILED");
		assertThat(db.queryForObject("""
				SELECT email_sent_at IS NULL FROM alarm_event WHERE id=?""", Boolean.class, e1)).isTrue();

		mail.fail = false;
		job.run();

		assertThat(statusOf(e1)).isEqualTo("SENT");
		assertThat(mail.sent).hasSize(1);
	}

	/**
	 * 재시도 상한 — 영구 실패 수신자(주소 폐기·도메인 거부) 하나가 5분마다 무한히 Resend를 때리는 걸 막는다.
	 * 별도 "포기" 상태를 두지 않는다: FAILED + attempts >= 상한이면 due 조회에서 자연히 빠진다.
	 */
	@Test
	void 상한에_도달한_행은_더_이상_시도되지_않는다() {
		user(7, "a@test.io");
		long e1 = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);
		mail.fail = true;

		for (int tick = 0; tick < 6; tick++) {
			job.run();
		}

		// 상한 5 — 6번째 틱은 조회 단계에서 걸러져 발송기를 부르지도 않는다.
		assertThat(mail.attempts).isEqualTo(5);
		assertThat(statusOf(e1)).isEqualTo("FAILED");
		assertThat(db.queryForObject("SELECT email_attempts FROM alarm_event WHERE id=?",
				Integer.class, e1)).isEqualTo(5);
	}

	/** 한 유저의 실패가 다른 유저의 발송을 막으면 장애 하나가 알람 전체를 멈춘다. */
	@Test
	void 이미_종결된_행은_다시_보내지_않는다() {
		user(7, "a@test.io");
		long e1 = event(7, AlarmEventType.COLLECTION_STARTED, SETTLED, SETTLED);

		job.run();
		job.run();

		assertThat(mail.sent).hasSize(1);
		assertThat(statusOf(e1)).isEqualTo("SENT");
	}
}
```

`monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmDispatchSchedulerTest.java`:

```java
package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * 발송 크론 배선 — SweepScheduler와 같은 규율이다. 우리가 틀릴 수 있는 건 셋:
 * 기본값이 비활성인지(잘못 켜지면 개통 전 환경에서 실메일이 나간다), zone이 고정인지,
 * 그리고 운영에 넣을 5분 크론 문자열이 실제로 파싱되는지.
 */
class AlarmDispatchSchedulerTest {

	@Test
	void 운영_5분_크론은_유효하다() {
		CronExpression cron = CronExpression.parse("0 */5 * * * *");

		assertThat(cron.next(java.time.ZonedDateTime.parse("2026-07-30T00:01:00Z")))
				.isEqualTo(java.time.ZonedDateTime.parse("2026-07-30T00:05:00Z"));
	}

	@Test
	void 스케줄_애노테이션은_기본_비활성이고_UTC_기준이다() throws Exception {
		Scheduled scheduled = AlarmDispatchScheduler.class.getMethod("dispatch")
				.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.cron())
				.isEqualTo("${monitoring.alarm.dispatch-cron:" + Scheduled.CRON_DISABLED + "}");
		assertThat(scheduled.zone()).isEqualTo("UTC");
	}

	@Test
	void dispatch는_발송_잡에_위임한다() {
		var calls = new int[1];
		var job = new AlarmDispatchJob(null, null, null, null, null, 5, null) {
			@Override
			public void run() {
				calls[0]++;
			}
		};

		new AlarmDispatchScheduler(job).dispatch();

		assertThat(calls[0]).isEqualTo(1);
	}
}
```

- [ ] **Step 5: 실패 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test --tests "com.celfit.monitoring.alarm.*"
```
Expected: 컴파일 실패 (`AlarmDispatchJob`·`AlarmMailComposer`·`AlarmRecipientReader`·`AlarmDispatchScheduler` 없음)

- [ ] **Step 6: `AlarmRecipientReader` 구현**

```java
package com.celfit.monitoring.alarm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 수신자 해석 — analysis DB의 app 스키마를 읽기 전용 롤(alarm_reader)로 조회한다(계약 v2 §6 역방향).
 * 읽는 객체는 {@code app.users(email)}와 {@code app.monitoring_email_opt_outs} 둘뿐이고,
 * 롤에도 그 둘만 GRANT한다 — 그 밖을 건드리면 권한 오류로 fail-closed다.
 *
 * <p>DataSource는 **지연 생성**한다: 알람이 꺼진 환경(로컬·테스트·개통 전 운영)에서 DSN이 없다는
 * 이유로 monitoring 부팅이 깨지면 안 된다. 스프링 자동구성 DataSource와 별개인 수동 조립이라
 * 빈으로 노출하지 않는다 — 두 번째 DataSource 빈이 뜨면 기존 JdbcTemplate 주입이 모호해진다.
 */
@Component
public class AlarmRecipientReader {

	private final String url;
	private final String username;
	private final String password;

	private volatile JdbcTemplate db;
	private HikariDataSource dataSource;

	public AlarmRecipientReader(@Value("${monitoring.alarm.reader.url:}") String url,
			@Value("${monitoring.alarm.reader.username:}") String username,
			@Value("${monitoring.alarm.reader.password:}") String password) {
		this.url = url;
		this.username = username;
		this.password = password;
	}

	/** 테스트 전용 — 이미 만들어진 DataSource로 조립한다(컨테이너 공유). */
	AlarmRecipientReader(DataSource ds) {
		this(null, null, null);
		this.db = new JdbcTemplate(ds);
	}

	/** DSN이 없으면 발송 잡 자체를 돌리지 않는다 — 유저마다 예외를 던져 로그를 채우는 것보다 낫다. */
	public boolean configured() {
		return db != null || (url != null && !url.isBlank());
	}

	public Optional<String> findEmail(long userId) {
		return db().queryForList("SELECT email FROM app.users WHERE id = ?", String.class, userId)
				.stream().filter(e -> e != null && !e.isBlank()).findFirst();
	}

	/** 행이 없으면 켜짐(기본 on) — 설정 화면과 1:1이라 빈 테이블이 곧 "전원 수신"이다. */
	public Set<AlarmEventType> findOptOuts(long userId) {
		Set<AlarmEventType> out = EnumSet.noneOf(AlarmEventType.class);
		for (String value : db().queryForList("""
				SELECT event_type FROM app.monitoring_email_opt_outs WHERE user_id = ?""",
				String.class, userId)) {
			// 모르는 어휘는 무시한다 — was가 새 이벤트 종류를 먼저 배포할 수 있다.
			AlarmEventType.parse(value).ifPresent(out::add);
		}
		return out;
	}

	private JdbcTemplate db() {
		JdbcTemplate local = db;
		if (local == null) {
			synchronized (this) {
				local = db;
				if (local == null) {
					HikariConfig hikari = new HikariConfig();
					hikari.setJdbcUrl(url);
					hikari.setUsername(username);
					hikari.setPassword(password);
					hikari.setMaximumPoolSize(2);   // 5분 틱 조회 전용
					hikari.setPoolName("alarm-reader");
					hikari.setReadOnly(true);
					dataSource = new HikariDataSource(hikari);
					local = new JdbcTemplate(dataSource);
					db = local;
				}
			}
		}
		return local;
	}

	@PreDestroy
	void close() {
		if (dataSource != null) {
			dataSource.close();
		}
	}
}
```

- [ ] **Step 7: `AlarmMailComposer` 구현**

```java
package com.celfit.monitoring.alarm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메일 문안 조립 — **임시 카피**다(스펙 §1-6). 정식 문안과 딥링크는 프론트 경로 확정 후 교체하며,
 * 교체 지점이 이 클래스 하나로 모이도록 발송 잡에서 분리해 뒀다.
 */
@Component
public class AlarmMailComposer {

	private static final Logger log = LoggerFactory.getLogger(AlarmMailComposer.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 화면 문구 — 스펙 §1-3 표와 1:1. */
	private static final Map<AlarmEventType, String> HEADINGS = Map.of(
			AlarmEventType.COLLECTION_STARTED, "게시물 수집 시작",
			AlarmEventType.COLLECTION_ENDED, "게시물 수집 종료",
			AlarmEventType.METRICS_HIDDEN, "일부 지표 비공개",
			AlarmEventType.CONTENT_UNAVAILABLE, "콘텐츠 비공개/삭제/수집 오류");

	/** 지표 키 → 사용자 어휘. 영문 키가 그대로 나가면 메일이 로그처럼 보인다. */
	private static final Map<String, String> METRIC_LABELS = Map.of(
			"likes", "좋아요", "comments", "댓글", "views", "조회수",
			"saves", "저장", "shares", "공유", "reposts", "리포스트");

	public record Mail(String subject, String text) {}

	public Mail compose(List<AlarmEvent> events) {
		Map<AlarmEventType, StringBuilder> sections = new LinkedHashMap<>();
		for (AlarmEvent event : events) {
			sections.computeIfAbsent(event.eventType(), t -> new StringBuilder())
					.append("- ").append(line(event)).append('\n');
		}
		StringBuilder text = new StringBuilder("안녕하세요. hypenow 모니터링 알림입니다.\n");
		sections.forEach((type, body) -> text.append('\n')
				.append("■ ").append(HEADINGS.get(type)).append('\n').append(body));
		text.append("\n※ 알림 설정은 hypenow 웹에서 변경할 수 있습니다.\n");
		return new Mail("[hypenow] 모니터링 알림 " + events.size() + "건", text.toString());
	}

	/** 한 줄 요약. payload가 깨져도 조립은 계속한다 — 문안 실패로 알람 전체가 멈추면 안 된다. */
	private String line(AlarmEvent event) {
		JsonNode payload = parse(event.payload());
		String username = payload.path("username").asString("(계정 미상)");
		String shortCode = payload.path("shortCode").asString(null);
		StringBuilder line = new StringBuilder("@").append(username);
		if (shortCode != null) {
			line.append(" · ").append(shortCode);
		}
		if (event.eventType() == AlarmEventType.METRICS_HIDDEN) {
			List<String> labels = new java.util.ArrayList<>();
			payload.path("metrics").forEach(m -> labels.add(
					METRIC_LABELS.getOrDefault(m.asString(""), m.asString(""))));
			if (!labels.isEmpty()) {
				line.append(" (").append(String.join(", ", labels)).append(')');
			}
		}
		String failReason = payload.path("failReason").asString(null);
		if (failReason != null) {
			line.append(" (").append(failReason).append(')');
		}
		return line.toString();
	}

	private JsonNode parse(String payload) {
		try {
			return JSON.readTree(payload == null ? "{}" : payload);
		} catch (RuntimeException e) {
			log.warn("알람 payload 파싱 실패 — 빈 값으로 문안을 만든다: {}", e.getMessage());
			return JSON.createObjectNode();
		}
	}
}
```

- [ ] **Step 8: `AlarmDispatchJob`·`AlarmDispatchScheduler`·`AlarmConfig` 구현**

`alarm/AlarmDispatchJob.java`:

```java
package com.celfit.monitoring.alarm;

import com.celfit.monitoring.mail.MailSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 알람 발송 틱(기본 5분) — due 행을 유저별로 묶어 **한 통**으로 보내고 행 단위로 종결한다.
 *
 * <p>워터마크가 없다: 무엇을 보냈는지는 행의 email_status가 말한다. 그래서 중복 발송도,
 * 크래시로 인한 유실도 구조적으로 생기지 않는다(FAILED는 다음 틱에 그 행만 다시 집는다).
 * 재시도는 email_attempts 상한(기본 5)으로 끊는다 — 없으면 영구 실패 수신자 하나가 무한히 돈다.
 *
 * <p>트랜잭션을 걸지 않는다 — 발송(외부 HTTP)이 트랜잭션 안에 들어가면 커넥션을 쥔 채 수 초를
 * 기다리고, 커밋 직전 실패가 "메일은 나갔는데 SENT는 안 찍힌" 상태(= 다음 틱 재발송)를 만든다.
 */
@Component
public class AlarmDispatchJob {

	private static final Logger log = LoggerFactory.getLogger(AlarmDispatchJob.class);

	private final AlarmEventRepository events;
	private final AlarmRecipientReader recipients;
	private final AlarmMailComposer composer;
	private final MailSender mailSender;
	private final Duration debounce;
	private final int maxAttempts;
	private final Clock clock;

	public AlarmDispatchJob(AlarmEventRepository events, AlarmRecipientReader recipients,
			AlarmMailComposer composer, MailSender mailSender,
			@Value("${monitoring.alarm.debounce:10m}") Duration debounce,
			@Value("${monitoring.alarm.max-attempts:5}") int maxAttempts, Clock clock) {
		this.events = events;
		this.recipients = recipients;
		this.composer = composer;
		this.mailSender = mailSender;
		this.debounce = debounce;
		this.maxAttempts = maxAttempts;
		this.clock = clock;
	}

	public void run() {
		if (!recipients.configured()) {
			// 개통 전에는 크론이 꺼져 있어 여기 오지 않는다 — 크론만 켜고 DSN을 빠뜨린 오배선 방어.
			log.warn("alarm_reader DSN 미설정 — 알람 발송을 건너뛴다");
			return;
		}
		Instant now = clock.instant();
		// 상한 판정은 DB가 한다 — 상한에 닿은 행은 여기 오지도 않으므로 별도 "포기" 분기가 필요 없다.
		List<AlarmEvent> due = events.findDue(now, maxAttempts);
		if (due.isEmpty()) {
			return;
		}
		Map<Long, List<AlarmEvent>> byUser = due.stream().collect(
				Collectors.groupingBy(AlarmEvent::userId, LinkedHashMap::new, Collectors.toList()));
		for (var entry : byUser.entrySet()) {
			try {
				dispatchUser(entry.getKey(), entry.getValue(), now);
			} catch (RuntimeException e) {
				// 한 유저의 실패가 나머지를 막으면 장애 하나가 알람 전체를 멈춘다.
				log.warn("알람 발송 실패(격리) — user {}: {}", entry.getKey(), e.toString());
			}
		}
	}

	private void dispatchUser(long userId, List<AlarmEvent> rows, Instant now) {
		if (stillArriving(rows, now)) {
			// 디바운스 — 시딩 수십 건 연속 등록을 흡수한다. 잦아들고 나서 1통으로 나간다.
			return;
		}
		String email = recipients.findEmail(userId).orElse(null);
		if (email == null) {
			// 유저 삭제·이메일 부재 — 재시도해도 보낼 곳이 없다(FAILED로 두면 매 틱 헛돈다).
			log.info("수신 이메일 없음 — user {} 알람 {}건 종결", userId, rows.size());
			events.updateStatus(ids(rows), AlarmEmailStatus.SKIPPED_NO_RECIPIENT, null);
			return;
		}
		Set<AlarmEventType> optOuts = recipients.findOptOuts(userId);
		List<AlarmEvent> muted = rows.stream().filter(r -> optOuts.contains(r.eventType())).toList();
		List<AlarmEvent> sendable = rows.stream().filter(r -> !optOuts.contains(r.eventType())).toList();
		// 꺼진 종류도 대장엔 남는다 — 앱 내 알림으로는 계속 서빙된다(스펙 §3-3).
		events.updateStatus(ids(muted), AlarmEmailStatus.SKIPPED_OPTOUT, null);
		if (sendable.isEmpty()) {
			return;
		}
		AlarmMailComposer.Mail mail = composer.compose(sendable);
		try {
			mailSender.send(email, mail.subject(), mail.text());
		} catch (RuntimeException e) {
			log.warn("알람 메일 발송 실패 — user {} {}건: {}", userId, sendable.size(), e.toString());
			events.updateStatus(ids(sendable), AlarmEmailStatus.FAILED, null);
			return;
		}
		events.updateStatus(ids(sendable), AlarmEmailStatus.SENT, clock.instant());
	}

	/**
	 * 아직 이벤트가 몰아치는 중인지 — due 행 중 가장 최근 발생이 디바운스 창 안이면 이번 틱을 넘긴다.
	 * "즉시 레인만" 보지 않고 due 전체를 보는 건 의도다: 09:00 레인 이벤트는 새벽 스윕에서 나와
	 * occurred_at이 몇 시간 전이라 애초에 창에 걸리지 않고, 레인 구분을 위해 컬럼을 하나 더 두는 것보다
	 * "최근에 뭔가 들어왔으면 잠깐 기다린다"가 더 단순하고 틀릴 여지가 적다.
	 */
	private boolean stillArriving(List<AlarmEvent> rows, Instant now) {
		Instant newest = rows.stream().map(AlarmEvent::occurredAt).max(Comparator.naturalOrder())
				.orElseThrow();
		return newest.isAfter(now.minus(debounce));
	}

	private static List<Long> ids(List<AlarmEvent> rows) {
		return rows.stream().map(AlarmEvent::id).toList();
	}
}
```

`alarm/AlarmDispatchScheduler.java`:

```java
package com.celfit.monitoring.alarm;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 알람 발송 스케줄 — 기본 "-"(비활성). 운영은 env로 5분 틱 주입(0 */5 * * * *).
 * SweepScheduler와 같은 관용구다: 기본 비활성이라 개통 전 환경에서 실메일이 나갈 일이 없다.
 */
@Component
public class AlarmDispatchScheduler {

	private final AlarmDispatchJob job;

	public AlarmDispatchScheduler(AlarmDispatchJob job) {
		this.job = job;
	}

	@Scheduled(cron = "${monitoring.alarm.dispatch-cron:-}", zone = "UTC")
	public void dispatch() {
		job.run();
	}
}
```

`alarm/AlarmConfig.java`:

```java
package com.celfit.monitoring.alarm;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 알람 모듈 조립. Clock을 빈으로 두는 이유는 발송 잡의 디바운스·due 판정을 테스트가 고정하기 위해서다. */
@Configuration
public class AlarmConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
```

- [ ] **Step 9: `application.yml`에 알람 블록 추가**

`monitoring:` 블록 끝에 추가:

```yaml
  alarm:
    dispatch-cron: "-"   # "-"=비활성. 운영은 5분 틱(0 */5 * * * *)을 env로 주입
    debounce: 10m        # 즉시 레인 몰아치기 흡수 — 마지막 이벤트가 이 시간 안이면 다음 틱으로 미룬다
    max-attempts: 5      # 행별 발송 시도 상한. 도달하면 due 조회에서 빠진다(영구 실패 수신자 무한 재시도 차단)
    reader:              # analysis DB app 스키마 읽기 전용(계약 v2 §6 — 두 객체만 GRANT)
      url: ${MONITORING_ALARM_READER_URL:}
      username: ${MONITORING_ALARM_READER_USERNAME:}
      password: ${MONITORING_ALARM_READER_PASSWORD:}
  mail:
    resend-api-key: ${RESEND_API_KEY:}          # 미설정이면 로깅 폴백(실발송 없음)
    from: hypenow <no-reply@hypenow.io>
```

- [ ] **Step 10: 통과 확인**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test
```
Expected: BUILD SUCCESSFUL — `AlarmDispatchJobTest` 11개, `AlarmMailComposerTest` 3개, `AlarmDispatchSchedulerTest` 3개 통과 + 기존 전부

- [ ] **Step 11: 커밋**

```bash
git add monitoring/src monitoring/src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat(monitoring): 알람 발송 크론 — 유저당 1통 통합·디바운스·옵트아웃·행 단위 재시도

5분 틱이 due 행을 유저별로 묶어 한 통으로 보낸다. 디바운스(기본 10분)가 시딩 연속 등록을
흡수하고, 옵트아웃은 SKIPPED_OPTOUT으로 닫아 대장엔 남긴다(앱 내 알림은 계속 서빙).
수신자 해석은 app 스키마 읽기 전용 별도 DataSource(지연 초기화 — DSN 없이도 부팅), 발송기는
Resend HTTP 클라이언트(키 미설정 시 로깅 폴백)를 monitoring 소유로 신설했다.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: app 옵트아웃 테이블 (was Flyway V15 — was 코드 무변경)

**Files:**
- Create: `was/src/main/resources/db/migration/app/V15__monitoring_email_opt_outs.sql`

> was Java 코드는 **한 줄도 건드리지 않는다.** 토글 API(쓰기)는 프론트 작업 때 was가 소유하고,
> 지금은 알람 모듈이 읽기만 한다. 이 PR에 동봉하는 이유는 발송 필터가 이 테이블에 의존해서다 —
> 분리하면 "monitoring은 배포됐는데 테이블이 없어 발송이 전부 터지는" 배포 순서 결합이 생긴다.

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- 모니터링 알람 이메일 옵트아웃 — **행 없음 = 켜짐**(기본 on). 설정 화면과 1:1이라 빈 테이블이 곧 전원 수신.
-- 읽기는 monitoring 알람 모듈(읽기 전용 롤 alarm_reader), 쓰기(토글 API)는 was 소유 — 계약 v2 §6.
-- event_type 어휘의 정본은 monitoring의 AlarmEventType이다(alarm_event.event_type CHECK와 같은 목록).
CREATE TABLE app.monitoring_email_opt_outs (
    user_id    bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    event_type text        NOT NULL CHECK (event_type IN
               ('COLLECTION_STARTED','COLLECTION_ENDED','METRICS_HIDDEN','CONTENT_UNAVAILABLE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_type)
);
```

- [ ] **Step 2: 마이그레이션 가드 셀프 검사**

Run: `.github/scripts/check-migration-safety.sh --scan was/src/main/resources/db/migration/app/V15__monitoring_email_opt_outs.sql`
Expected: `OK   was/src/main/resources/db/migration/app/V15__monitoring_email_opt_outs.sql` (CREATE만 있어 파괴 패턴 없음)

- [ ] **Step 3: was 전체 회귀**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :was:test
```
Expected: BUILD SUCCESSFUL — was 코드 무변경이므로 전부 통과(Flyway가 V15까지 적용되는지의 확인 겸용. 실패하면 `app.users` 참조·CHECK 문법을 먼저 의심)

- [ ] **Step 4: 커밋**

```bash
git add was/src/main/resources/db/migration/app/V15__monitoring_email_opt_outs.sql
git commit -m "$(cat <<'EOF'
feat(was): 모니터링 알람 이메일 옵트아웃 테이블(V15) — 행 없음=켜짐

monitoring 알람 모듈의 발송 필터가 읽는 테이블. 쓰기(토글 API)는 프론트 작업 때 was가 맡고
지금은 읽기 전용이라 was 코드 변경은 없다. 배포 순서 결합을 없애려고 알람 PR에 동봉한다.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 계약 v2.0 개정 + 배포·구조 문서

**Files:**
- Modify: `docs/contracts/monitoring-was-contract.md`
- Modify: `deploy/README.md` (§13)
- Modify: `deploy/compose.yaml` · `deploy/compose.test.yaml` · `deploy/.env.example`
- Modify: `ARCHITECTURE.md` (§5·§7)
- Modify: `docs/superpowers/specs/2026-07-30-monitoring-alarm-module-design.md` (상태 헤더)
- Modify: 이 플랜 (상태 헤더)

- [ ] **Step 1: 계약 문서 v2.0 개정** (스펙 §5 목록 그대로 — 아래 편집을 순서대로)

1. **헤더**(4~6줄): 상태 줄을 교체.
   ```
   > 상태: **v2.0 (구현 반영 — 2026-07-30)** · 명령 API **3종**(등록·연장·해지)·조회 표면(테이블 4 + 알람 대장 + 뷰 2)·
   > 알람은 **monitoring 소유**(was는 알람 경로에서 빠짐)·에러 어휘 전부 구현과 일치.
   > 이전: v1.0 (2026-07-29) — 승인·기각 명령 2종과 was 09:00 이메일 크론이 있던 판.
   ```

2. **§0 한 장 요약**: 두 항목 교체·한 항목 추가.
   - "쓰기(명령)는 전부 monitoring 내부 API — **등록·연장·해지 3개**. 승인·기각은 v2에서 폐지(감지 즉시 자동 추적)."
   - was가 app에 보관할 것에서 "**(+ 알람 발송 워터마크)**"를 삭제 — 워터마크는 id 대장으로 대체됐다.
   - 신규 항목: "**알람은 monitoring 소유** — 이벤트 대장 `alarm_event`가 단일 원천이고, 메일 발송도 monitoring
     크론이 한다. was는 앱 내 알림·히스토리를 이 테이블에서 **읽기만** 한다."

3. **§2 에러 표**: `CANDIDATE_NOT_FOUND` 행 삭제(`TARGET_NOT_FOUND`만 남긴다).

4. **§2-1 등록**: 두 요청 JSON에 `"userId": 12345,   // was 유저 id — 알람 수신자. 필수(누락 시 VALIDATION 400)`를
   `registrationKey` 다음 줄에 추가. 동작 설명에 한 문장 추가:
   > **ACCOUNT 등록의 status는 `WATCHING`이고, 첫 키워드 감지 시 monitoring이 스스로 `TRACKING`으로
   > 전환한다**(승인 절차 없음 — v2). 전환 시점은 일일 스윕(KST 02:00)이다.

5. **§2-2 승인 / §2-3 기각**: **절 전체 삭제**. 뒤 절 번호를 당긴다 — §2-4 기간 연장 → **§2-2**, §2-5 해지 → **§2-3**.
   본문 안의 "계약 §2-5 멱등" 같은 자기 참조도 새 번호로 고친다.

6. **§3 조회 표면**:
   - `target` 표에 행 추가: `| `user_id` | bigint null | 소유 유저(was 유저 논리 참조 — 알람 수신자). V3 이전 등록분은 null |`
   - `tracked_short_code` 행의 설명을 "승인(또는 직접 등록)된" → "**첫 감지 자동 전환**(또는 직접 등록)된"으로.
   - `detected_candidate` 절 머리에 deprecated 주석 추가:
     > **⚠ deprecated (v2)** — 신규 적재가 중단됐다(승인 플로우 폐지). 테이블과 기존 행은 이력으로 남지만
     > 새 행은 생기지 않으므로 조회하지 말 것. DROP은 참조가 끊긴 다음 릴리스의 contract 단계.
   - **`alarm_event` 절 신설**(profile/post_snapshot 절 앞):

     ```
     ### alarm_event — 알람 이벤트 대장 (앱 내 알림·히스토리의 단일 원천)

     | 컬럼 | 타입 | 의미 |
     |---|---|---|
     | `id` | bigint PK | 이벤트 id. 워터마크 대신 이 id와 상태로 발송을 관리한다 |
     | `target_id` | bigint | 소속 캠페인 (논리 참조) |
     | `user_id` | bigint | 수신자 (was 유저 논리 참조) |
     | `event_type` | text | `COLLECTION_STARTED` / `COLLECTION_ENDED` / `METRICS_HIDDEN` / `CONTENT_UNAVAILABLE` |
     | `payload` | jsonb | 문안 재료 — `username`, `shortCode`, METRICS_HIDDEN의 `metrics[]`, CONTENT_UNAVAILABLE의 `failReason` |
     | `occurred_at` | timestamptz | 발생 시각 |
     | `dispatch_after` | timestamptz | 메일 발송 레인(즉시 = occurred_at, 아침 = 적재 당일 09:00 KST) |
     | `email_status` | text | `PENDING`/`SENT`/`SKIPPED_OPTOUT`/`SKIPPED_NO_RECIPIENT`/`FAILED` |
     | `email_sent_at` | timestamptz null | SENT일 때만 채워진다 |

     - **메일 발송은 monitoring 몫이다** — was는 이 테이블을 읽어 앱 내 알림·히스토리를 서빙만 한다
       (`email_status`가 SKIPPED_OPTOUT이어도 앱 내에서는 보여준다 — 옵트아웃은 메일만 끈다).
     - 읽음 상태는 was가 자기 `app` 스키마에 워터마크로 보관한다(프론트 API 작업 때).
     - 화면 문구: 수집 시작 / 수집 종료 / 일부 지표 비공개 / 콘텐츠 비공개·삭제·수집 오류.
     ```
   - `v_target_overview` 절: 컬럼 표의 target 구획을 14 → **15**(맨 앞에 `user_id` 추가), **후보 (1) 행 삭제**,
     제목의 "26컬럼"은 그대로(15+3+8=26). `pending_candidates` 설명 불릿도 삭제.
   - **§3 자주 쓸 쿼리 예**: "이메일 알람 크론(09:00)" 쿼리를 삭제하고 아래로 교체.

     ```sql
     -- 앱 내 알림 목록 (was 서빙 — 최신순)
     SELECT id, target_id, event_type, payload, occurred_at
     FROM alarm_event
     WHERE user_id = :user_id
     ORDER BY occurred_at DESC
     LIMIT 50;
     ```

7. **§4 플로우**:
   - "감지 → 승인" 절을 교체:
     ```
     ### 감지 → 자동 추적 (v2)

     1. 02:00 monitoring 스윕: 등록 시각 이후 게시물 중 키워드 매칭 → **그 자리에서 TRACKING 전환**
        (같은 스윕에 여러 건이면 게시 시각 최신 1건. 캠페인:추적 게시물 = 1:1)
     2. `COLLECTION_STARTED` 알람 이벤트 적재(아침 레인)
     3. was는 별도 명령 없이 조회 표면에서 상태 변화를 본다 — 사용자 승인 단계가 없다
     ```
   - "이메일 알람 (was 소속 09:00 크론)" 절을 교체:
     ```
     ### 알람 (monitoring 소유 — was 무관여)

     1. monitoring이 이벤트 발생 지점 5곳에서 `alarm_event`에 적재한다
        (직접 등록·자동 전환·만료·지표 비공개·결정적 실패)
     2. monitoring 발송 크론(5분 틱)이 `dispatch_after <= now()`인 행을 유저별로 묶어 **1통**으로 보낸다.
        디바운스 10분 — 시딩 연속 등록은 잦아든 뒤 한 통으로 나간다
     3. 옵트아웃(`app.monitoring_email_opt_outs`)은 메일만 끈다 — 대장 행은 남는다
     4. **was는 발송에 관여하지 않는다.** 앱 내 알림·히스토리 서빙만 한다(§3 `alarm_event`)
     ```

8. **§6 신설 — 역방향 계약**(§5 뒤에 추가):

   ```
   ## 6. 알람 모듈 → app 읽기 전용 (역방향)

   monitoring 알람 모듈이 analysis DB의 `app` 스키마를 **두 객체만** 읽는다. 전용 읽기 전용 롤
   `alarm_reader`에 그 둘만 GRANT하고, 접속은 monitoring DB와 **별도 DataSource**다.

   | 객체 | 읽는 컬럼 | 용도 |
   |---|---|---|
   | `app.users` | `id`, `email` | 수신자 이메일 해석 |
   | `app.monitoring_email_opt_outs` | `user_id`, `event_type` | 메일 옵트아웃 필터 |

   - `monitoring_email_opt_outs`는 **행 없음 = 켜짐**(기본 on). 쓰기(토글 API)는 was 소유.
   - `event_type` 어휘의 정본은 monitoring(`alarm_event.event_type`과 같은 목록) — was는 그대로 저장만 한다.
   - 이 둘 밖을 읽으려 하면 권한 오류로 fail-closed다(의도).
   ```

9. **§5 was 구현 시 주의**에 호환 항목 추가:

   ```
   - **v2 호환 주의** — 구 was의 `approve`/`reject` 호출은 **404**(경로 삭제), `userId` 없는 등록은 **400**이다.
     현재 프론트 `/v1` 미배선이라 실호출자는 없다(dev 스모크만 주의). PR②가 was 클라이언트를 정렬한다.
   ```

- [ ] **Step 2: `deploy/README.md` §13 개통 절차 보강**

§13의 4번 뒤에 5번을 추가:

```markdown
5. **알람 개통 (07-30~, 별도 단계 — 기본 비활성이라 서두르지 않아도 된다)**
   1. analysis DB에 읽기 전용 롤 생성 + 두 객체만 GRANT (계약 v2 §6):
      ```bash
      docker exec -it deploy-postgres-1 psql -U <DB_USER> -d analysis \
        -c "CREATE ROLE alarm_reader LOGIN PASSWORD '<실값>'" \
        -c "GRANT USAGE ON SCHEMA app TO alarm_reader" \
        -c "GRANT SELECT (id, email) ON app.users TO alarm_reader" \
        -c "GRANT SELECT ON app.monitoring_email_opt_outs TO alarm_reader"
      ```
      (`app.monitoring_email_opt_outs`는 was Flyway V15가 만든다 — **was 배포 후**에 실행할 것)
   2. `~/deploy/.env`에 `ALARM_READER_PASSWORD`, `RESEND_API_KEY` 실값 등록
      (`RESEND_API_KEY`는 was가 이미 쓰던 값과 같은 키를 공유한다)
   3. 발송 크론 켜기 — `deploy/compose.yaml`의 `MONITORING_ALARM_DISPATCH_CRON`을 `"0 */5 * * * *"`로
      바꿔 커밋·배포(서버에서 직접 고친 값은 다음 CD가 레포 compose로 덮는다 — 스윕 크론과 같은 규칙)
   4. 검증: `docker logs deploy-monitoring-1 | grep -i resend` — "Resend 메일 발송 활성"이면 실발송 모드,
      "RESEND_API_KEY 미설정"이면 로깅 폴백(개통 실패)
```

`### 접근 통제·디버깅` 목록에도 한 줄 추가:

```markdown
- 알람 발송은 컨테이너 env `MONITORING_ALARM_DISPATCH_CRON`(기본 `"-"`=비활성, 운영 5분 틱).
  임시 중단은 `"-"`로 두고 재기동 — 대장(`alarm_event`)에 PENDING으로 쌓였다가 다시 켜면 그대로 나간다
  (워터마크가 없어 중단 구간 유실이 없다).
```

- [ ] **Step 3: compose 배선**

`deploy/compose.yaml`의 `monitoring:` 서비스 environment에 추가:

```yaml
      # 알람 발송(07-30~) — 기본 비활성. 개통 절차는 README §13-5
      MONITORING_ALARM_DISPATCH_CRON: "-"
      RESEND_API_KEY: ${RESEND_API_KEY:-}
      # 수신자 해석용 analysis DB 읽기 전용(두 객체만 GRANT — 계약 v2 §6)
      MONITORING_ALARM_READER_URL: jdbc:postgresql://postgres:5432/analysis
      MONITORING_ALARM_READER_USERNAME: alarm_reader
      MONITORING_ALARM_READER_PASSWORD: ${ALARM_READER_PASSWORD:-}
```

`deploy/compose.test.yaml`의 `test-monitoring:` environment에도 동일하게(비활성 유지·test DB 대상):

```yaml
      MONITORING_ALARM_DISPATCH_CRON: "-"   # test는 실발송 금지 — 켜지 않는다
      MONITORING_ALARM_READER_URL: jdbc:postgresql://test-postgres:5432/analysis
      MONITORING_ALARM_READER_USERNAME: alarm_reader
      MONITORING_ALARM_READER_PASSWORD: ${DEV_ALARM_READER_PASSWORD:-}
```

`deploy/.env.example`에 항목 추가(값 비움):

```
# 모니터링 알람 — analysis DB 읽기 전용 롤(계약 v2 §6). 개통 전에는 비워 둬도 된다(크론이 꺼져 있음)
ALARM_READER_PASSWORD=
DEV_ALARM_READER_PASSWORD=
```

> 모두 `${VAR:-}` 기본값 형태라 `.env`에 없어도 CD의 env 게이트(§5)가 배포를 막지 않는다.

- [ ] **Step 4: `ARCHITECTURE.md` 갱신**

§2 모듈 표의 `monitoring` 행 설명에서 "(FE 승인)→"을 지우고 "→**첫 감지 자동 추적**→"으로 바꾼 뒤,
"**알람(이벤트 대장·이메일 발송)도 monitoring 소유** — was는 앱 내 알림 서빙만"을 덧붙인다.

§5 작업 트랙 표에 행 추가(문자는 기존 최댓값 다음 — **표를 열어 실제 최대 문자를 확인한 뒤** 그 다음 글자를 쓴다):

```
| <다음 문자> | 모니터링 알람 모듈 | 알람 소유를 monitoring으로 이동 — `alarm_event` 대장(워터마크 없음)·적재 5지점·발송 크론(디바운스·옵트아웃·유저당 1통) + 승인 플로우 제거(첫 감지 자동 추적) + 일시 오류 당일 재시도 + `target.user_id`(V3)·app 옵트아웃(was V15) + 계약 **v2.0**. PR②(was 클라이언트 정렬)·프론트 알림 API는 후속 — [specs/2026-07-30-monitoring-alarm-module-design.md](docs/superpowers/specs/2026-07-30-monitoring-alarm-module-design.md) | S | 🔨 |
```

§7 결정 기록에 추가:

```
- 2026-07-30 모니터링 알람: 알람 소유를 was → monitoring으로 이동. 워터마크 대신 **id 기반 대장**
  (`alarm_event`)으로 순서·유실 문제를 없애고, 발송 실패는 행 단위 FAILED로 다음 틱 재시도.
  승인 플로우는 제거(감지 즉시 자동 추적, 첫 1건 = 게시 시각 최신) — 캠페인:추적 게시물 1:1은 유지.
  일시 오류(5xx·IO)는 알람이 아니라 재시도 대상(콜 백오프 + 스윕 말미 라운드).
  수신자 해석은 analysis DB app 스키마 **두 객체만** 읽는 별도 읽기 전용 롤(역방향 계약 신설).
```

- [ ] **Step 5: 상태 헤더 갱신 후 커밋**

스펙 헤더를 `> 상태: ✅ 구현됨 (2026-07-30, feat/monitoring-alarm-module)`로, 이 플랜 헤더도 동일하게 바꾼다.

```bash
git add docs/contracts/monitoring-was-contract.md deploy ARCHITECTURE.md docs/superpowers
git commit -m "$(cat <<'EOF'
docs: 계약 v2.0 개정 + 알람 개통 절차·compose 배선·ARCHITECTURE 반영

명령 5→3종(승인·기각 삭제), 등록 userId 필수, alarm_event 조회 표면 신설,
알람 절을 monitoring 소유로 교체, 알람 모듈→app 읽기 전용 역방향 계약(§6) 신설.
deploy README §13-5에 alarm_reader 롤·RESEND·크론 개통 절차를 추가하고 compose는
기본 비활성으로 배선했다.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: 전체 검증 + 최종 리뷰 준비

- [ ] **Step 1: monitoring 전체 테스트**

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :monitoring:test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: was 전체 테스트** (V15 회귀)

Run:
```
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :was:test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 마이그레이션 가드**

Run: `.github/scripts/check-migration-safety.sh origin/develop`
Expected: `OK   was/src/main/resources/db/migration/app/V15__monitoring_email_opt_outs.sql` + `검사 완료`
(monitoring V3는 스캔 대상이 아니다 — 스크립트가 was·analytics 경로만 본다)

- [ ] **Step 4: 죽은 참조 스윕** (삭제한 개념이 남아 있지 않은지)

Run:
```bash
grep -rn "approve\|reject\|CANDIDATE_NOT_FOUND\|pending_candidates\|insertPending" \
  monitoring/src docs/contracts/monitoring-was-contract.md
```
Expected: `CandidateRepository.insertPending`과 `StoreTest`의 그 호출, `detected_candidate` deprecated 주석만
(테이블이 남아 있는 동안의 의도된 잔존). `monitoring/src/main`의 web·service에는 approve/reject가 없어야 하고,
계약 문서에도 승인·기각 절이 없어야 한다.

- [ ] **Step 5: 전체 빌드**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL (Error Prone ERROR 등급 통과)

- [ ] **Step 6: 리뷰 요청**

superpowers:requesting-code-review로 리뷰를 받고, 정리되면
superpowers:finishing-a-development-branch로 develop 대상 PR을 만든다(직접 push 금지).

---

## Self-Review 결과 (작성 시 반영 완료)

### 스펙 §1~§8 ↔ 태스크 매핑

| 스펙 | 항목 | 태스크 |
|---|---|---|
| §1-1 | 승인 플로우 제거 · 첫 감지 1건만 자동 수집 | Task 2 (`firstDetection` — taken_at 최신 1건) |
| §1-2 | 알람 monitoring 소유 · `target.user_id` | Task 1 (V3·등록 필수) + Task 4·5 |
| §1-3 | 이벤트 4종, 제외 없음 | Task 4 (`AlarmEventType`) |
| §1-4 | 일시 오류는 재시도 대상 | Task 3 (콜 백오프 + 스윕 라운드) |
| §1-5 | 즉시 / 09:00 KST 레인 | Task 4 (`DispatchLane` + 테스트 4개) |
| §1-6 | 임시 문안·딥링크 제외·기본 on | Task 5 (`AlarmMailComposer`), Task 6 (행 없음=on) |
| §1-7 | 앱 내 알림·히스토리 단일 원천 | Task 1 (`alarm_event_user_idx`), Task 7 (계약 §3 조회 표면) |
| §2-1 | user_id nullable·등록 필수·뷰 노출 | Task 1 전체 |
| §2-2 | markTracking 직행·approve/reject 삭제·무트랜잭션 | Task 2 전체 |
| §2-3 | 콜 레벨 + 스윕 레벨 재시도 | Task 3 전체 |
| §3-1 | `alarm_event` DDL(+`email_attempts`)·인덱스 2종·행 단위 재시도 상한 | Task 1 Step 1, Task 4 Step 3(`findDue`·`updateStatus`), Task 5 Step 8 |
| §3-2 | 적재 5지점 + METRICS_HIDDEN 오탐 3규칙 | Task 4 (`AlarmRecorder` + 지점별 연동) |
| §3-3 | 5분 틱·디바운스·옵트아웃·1통·행별 종결·Resend 폴백 | Task 5 전체 |
| §3-4 | `alarm_reader` 별도 DataSource·지연 초기화 | Task 5 (`AlarmRecipientReader`) |
| §4 | app V15 옵트아웃 | Task 6 |
| §5 | 계약 v2.0 개정 6항목 | Task 7 Step 1 (1~9번 편집으로 전개) |
| §6 | 테스트 전략 전 항목 | 각 Task + Task 8 |
| §7 | 배포·개통 체크리스트 | Task 7 Step 2·3 |
| §8 | 후속(범위 밖) | 계획에 포함하지 않음 |

### 타입·시그니처 교차 확인

- `TargetRepository.insert(TargetType, Long userId, String, String, KeywordRule, TargetStatus, String, String, Instant)` —
  Task 1에서 확정, 호출자 5곳(`RegistrationService` 2, `StoreTest`, `DailySweepJobTest` 3, `CommandApiTest` 1, `AlarmRecorderTest` 1) 전부 반영.
- `TargetRow(long, Long userId, …)` — `userId`가 2번째. `DailySweepJob`(Task 2·4)·`AlarmRecorder`가 사용.
- `expireOverdue()` 반환이 `int` → `List<ExpiredTarget>`: Task 2에서는 아직 `int`, **Task 4에서 교체**하며
  `DailySweepJob.run()`의 로그·`StoreTest` 단언을 같은 스텝에서 함께 고친다(중간 상태가 컴파일되도록 순서 고정).
- `PostInfo(…, String rawJson, boolean viewsTrusted)` — 마지막 인자. 생성자 호출 3곳(`HikerClient.toPost`,
  `StoreTest`, `CommandApiTest`) + 테스트 헬퍼 2곳(`AlarmRecorderTest`, `SnapshotWriterAlarmTest`).
- `SnapshotWriter(SnapshotRepository, AlarmRecorder)` / `DailySweepJob(TargetRepository, CollectService, AlarmRecorder, int, Duration)` /
  `RegistrationService(CollectService, TargetRepository, AlarmRecorder)` — Task 4에서 동시에 바뀌므로 한 스텝에 묶었다.
- `AlarmDispatchJob(AlarmEventRepository, AlarmRecipientReader, AlarmMailComposer, MailSender, Duration debounce, int maxAttempts, Clock)` —
  7인자. `AlarmDispatchJobTest`(`…, 5, Clock.fixed(...)`)·`AlarmDispatchSchedulerTest`(`…, 5, null`)가 같은 arity로 생성.
- `AlarmEventRepository.findDue(Instant, int maxAttempts)` — 호출자는 `AlarmDispatchJob.run()` 한 곳.
  `updateStatus`는 시그니처 불변(내부 SQL에서 `email_attempts + 1`).
- `AlarmEmailStatus`(5값) ↔ V3 CHECK 목록 일치, `AlarmEventType`(4값) ↔ V3 CHECK·was V15 CHECK 일치.
- `email_attempts`는 DB 전용 — `AlarmEvent` record에 없다(상한 판정 지점을 due 조회 WHERE 한 곳으로 고정).

### placeholder 스캔

TBD·`...`·"~하게 구현"류 없음. 코드 블록은 전부 그대로 붙여 넣을 수 있는 완성 코드이며, 부분 교체 블록은
"이 메서드만 교체" 식으로 범위를 명시했다.

### 구현 중 주의 (스펙 대비 판단이 필요했던 지점)

1. **`SKIPPED_NO_RECIPIENT`** — §3-1 CHECK 목록에 없었지만 §3-3이 "수신자 없음은 FAILED가 아니라
   SKIPPED 계열"을 요구했다. `SKIPPED_OPTOUT`에 뭉치면 관측이 거짓말을 한다. **2026-07-30 스펙 정정 반영 —
   값 추가 확정.**
2. **due 조회에 `FAILED` 포함 + 재시도 상한** — §3-3 1번은 `PENDING`만 말하지만 §3-1은 "FAILED → 다음 틱
   그 행만 재시도"를 명시한다(전자만 따르면 FAILED 행이 영구 사장). **2026-07-30 스펙 정정 반영:**
   `email_attempts smallint NOT NULL DEFAULT 0` + 상한 프로퍼티(`monitoring.alarm.max-attempts`, 기본 5).
   판정은 due 조회 WHERE 한 곳에서만 한다 — 별도 "포기" 상태 전이를 만들지 않아도
   `FAILED + attempts >= 상한`이 곧 종결이고, 자바 쪽에 두 번째 판정 지점이 생기지 않는다.
   attempts는 성공·실패·스킵을 가리지 않고 올린다(실패에서만 올리면 성공/실패가 번갈아 나는 경로에서
   상한이 영영 안 찬다). 부분 인덱스도 `IN ('PENDING','FAILED')`로 맞춰 FAILED 재시도 조회가 인덱스를 탄다.
3. **디바운스 판정 범위** — 스펙은 "즉시 레인 이벤트 중 최신 occurred_at"이라 하지만, 레인을 구분하는
   컬럼이 없다(즉시 레인은 `dispatch_after == occurred_at`이라는 암묵 규칙에만 의존). due 행 전체의
   최신 `occurred_at`으로 대체했다 — 아침 레인 이벤트는 새벽 스윕에서 나와 창에 걸리지 않으므로 실동작이 같고,
   레인 컬럼을 추가하지 않아도 된다.
4. **`app.users.email` 픽스처가 nullable** — 실제 스키마는 NOT NULL이라 "이메일 null" 케이스는 운영에서
   발생하지 않는다. 테스트에서만 방어 경로를 덮으려고 fixture를 느슨하게 뒀다(계약 v2 §6이 보증하는 건
   `email` 컬럼의 존재뿐이라 monitoring이 null을 가정하는 게 안전하다).
5. **`spring.task.scheduling.pool.size: 2`** — 스펙에 없지만 필수다. 기본 1이면 스윕의 재시도 라운드 대기
   (최대 10+20+30분)가 스케줄러 스레드를 붙잡아 그 사이 5분 발송 틱이 통째로 밀린다.
6. **`CandidateRepository` 존치** — 스펙 §2-2의 삭제 목록에 없고 테이블도 남으므로 클래스는 두되 호출자가
   0이 된다(주석으로 명시). 다음 릴리스 contract 단계에서 테이블과 함께 제거.

