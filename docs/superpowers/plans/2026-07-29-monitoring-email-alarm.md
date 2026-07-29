# 모니터링 이메일 알람 1차 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 구현됨 (2026-07-29)
> 스펙: [specs/2026-07-29-monitoring-email-alarm-design.md](../specs/2026-07-29-monitoring-email-alarm-design.md)
> 계약(v1.0): [docs/contracts/monitoring-was-contract.md](../../contracts/monitoring-was-contract.md)
> 작업 위치: 워크트리 `.worktrees/monitoring-email-alarm`, 브랜치 `feat/monitoring-email-alarm`

**Goal:** 게시물 감지(POST_DETECTED) 이메일 알람 크론 + 알람 설정 저장 계층(V15) + 계약 v1.0 정렬. 토글 API·타 이벤트·딥링크는 범위 밖.

**Architecture:** 옵트아웃 테이블(행 없음=on) + 이벤트별 워터마크. 크론은 `MonitoringConfig` 조건부 빈(@EnableScheduling 포함 — 비활성 환경 무영향). 부분 실패 시 워터마크 유지(유실보다 중복). 문안은 Composer 클래스로 격리(임시 문안 — 교체 1파일).

**Tech Stack:** 기존 seam 계층(MonitoringReadRepository·MonitoringCampaignMappingRepository·MonitoringConfig) + `com.celfit.was.mail.MailSender`(send(to, subject, text) — Resend/Logging 구현 존재) + @Scheduled + Flyway app V15.

**컨벤션·환경:** 탭 들여쓰기, 한국어 주석·테스트명·커밋. 테스트는 colima 환경변수 필수:
`DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :was:test --tests "..."`

---

## 파일 구조

```
was/src/main/resources/db/migration/app/V15__monitoring_email_alarm.sql   ← Task 1
was/src/main/java/com/celfit/was/monitoring/
  MonitoringAlarmRepository.java        ← Task 1  옵트아웃·워터마크·유저 이메일 (@Repository, 항상 활성)
  MonitoringCampaignMappingRepository.java ← Task 2 수정  findByTargetIds 추가
  MonitoringReadRepository.java         ← Task 2 수정  알람 쿼리 v1.0 정렬(활성 캠페인만)
  MonitoringAlarmMailComposer.java      ← Task 3  임시 문안 조립
  MonitoringAlarmJob.java               ← Task 3  09:00 크론 본체
  MonitoringConfig.java                 ← Task 4 수정  @EnableScheduling + 잡 빈
was/src/test/java/com/celfit/was/monitoring/
  MonitoringAlarmRepositoryTest.java    ← Task 1
  MonitoringCampaignMappingRepositoryTest.java / MonitoringReadRepositoryTest.java ← Task 2 테스트 추가
  MonitoringAlarmJobTest.java           ← Task 3
was/src/test/java/com/celfit/was/MonitoringDisabledTest.java / MonitoringEnabledConfigTest.java ← Task 4 단언 추가
```

---

### Task 1: V15 마이그레이션 + MonitoringAlarmRepository

**Files:**
- Create: `was/src/main/resources/db/migration/app/V15__monitoring_email_alarm.sql`
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringAlarmRepository.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringAlarmRepositoryTest.java`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- 모니터링 이메일 알람 저장 계층(스펙 2026-07-29 §2).
-- 옵트아웃: 행 없음 = 알림 on(기본) — 설정 화면(이벤트 4종 × 이메일 토글)의 저장소.
-- 토글 API는 프론트 /v1 작업 때 — 지금은 크론이 읽기만 한다.
CREATE TABLE app.monitoring_email_opt_outs (
    user_id    bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    event_type text   NOT NULL CHECK (event_type IN
                      ('POST_DETECTED', 'POST_HIDDEN', 'UPLOAD_MISSED', 'MONITORING_ENDED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_type)
);

-- 발송 워터마크: 이벤트별 1행 — 중복 발송 방지는 전적으로 was 책임(계약 §4).
CREATE TABLE app.monitoring_alarm_state (
    event_type       text PRIMARY KEY,
    last_notified_at timestamptz NOT NULL
);

-- 시드: 마이그레이션 시각부터 시작 — 적용 이전 감지분의 일괄 발송 방지.
INSERT INTO app.monitoring_alarm_state (event_type, last_notified_at)
VALUES ('POST_DETECTED', now());
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MonitoringAlarmRepositoryTest extends IntegrationTest {

	@Autowired
	MonitoringAlarmRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userA;
	long userB;

	@BeforeEach
	void 유저_시드() {
		userA = seedUser();
		userB = seedUser();
	}

	long seedUser() {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id
				""")
				.param("email", "alarm-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void 옵트아웃_행이_없으면_아무도_제외되지_않는다() {
		assertThat(repository.optedOutUserIds("POST_DETECTED", List.of(userA, userB))).isEmpty();
		assertThat(repository.optedOutUserIds("POST_DETECTED", List.of())).isEmpty();
	}

	@Test
	void 옵트아웃한_유저만_해당_이벤트에서_제외된다() {
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
				VALUES (:u, 'POST_DETECTED'), (:u2, 'MONITORING_ENDED')
				""").param("u", userA).param("u2", userB).update();

		Set<Long> optedOut = repository.optedOutUserIds("POST_DETECTED", List.of(userA, userB));

		assertThat(optedOut).containsExactly(userA);   // userB는 다른 이벤트만 껐다
	}

	@Test
	void 워터마크는_시드돼_있고_전진만_허용된다() {
		OffsetDateTime seeded = repository.watermark("POST_DETECTED");
		assertThat(seeded).isNotNull();

		OffsetDateTime future = seeded.plusDays(1);
		repository.advanceWatermark("POST_DETECTED", future);
		assertThat(repository.watermark("POST_DETECTED")).isEqualTo(future);

		// 과거 값으로 호출해도 후퇴하지 않는다
		repository.advanceWatermark("POST_DETECTED", seeded);
		assertThat(repository.watermark("POST_DETECTED")).isEqualTo(future);
	}

	@Test
	void 유저_이메일_일괄_조회() {
		Map<Long, String> emails = repository.emailsByUserIds(List.of(userA, userB));

		assertThat(emails).hasSize(2);
		assertThat(emails.get(userA)).contains("@test.io");
		assertThat(repository.emailsByUserIds(List.of())).isEmpty();
	}
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringAlarmRepositoryTest"` (colima env)
Expected: 컴파일 실패 (`MonitoringAlarmRepository` 없음)

- [ ] **Step 4: 리포지토리 구현**

```java
package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 알람 설정·발송 상태(app 스키마 — 스펙 2026-07-29 §2). 옵트아웃은 "행 없음 = on(기본)" —
 * 설정 토글 API는 프론트 /v1 작업 때 붙고, 지금은 크론이 읽기만 한다.
 * 모니터링 비활성이어도 무해한 app 테이블 접근이라 항상 활성.
 */
@Repository
public class MonitoringAlarmRepository {

	private final JdbcClient jdbcClient;

	public MonitoringAlarmRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Set<Long> optedOutUserIds(String eventType, Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbcClient.sql("""
				SELECT user_id FROM app.monitoring_email_opt_outs
				WHERE event_type = :eventType AND user_id IN (:userIds)
				""")
				.param("eventType", eventType)
				.param("userIds", userIds)
				.query(Long.class)
				.list());
	}

	/** 이벤트 워터마크 — V15 시드로 행이 항상 있다(없으면 마이그레이션 누락이라 예외가 맞다). */
	public OffsetDateTime watermark(String eventType) {
		return jdbcClient.sql("""
				SELECT last_notified_at FROM app.monitoring_alarm_state WHERE event_type = :eventType
				""")
				.param("eventType", eventType)
				.query(OffsetDateTime.class)
				.single();
	}

	/** 전진만 허용(후퇴 방지 가드) — 과거 값으로 호출돼도 무해. */
	public void advanceWatermark(String eventType, OffsetDateTime to) {
		jdbcClient.sql("""
				UPDATE app.monitoring_alarm_state SET last_notified_at = :to
				WHERE event_type = :eventType AND last_notified_at < :to
				""")
				.param("to", to)
				.param("eventType", eventType)
				.update();
	}

	public Map<Long, String> emailsByUserIds(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return jdbcClient.sql("SELECT id, email FROM app.users WHERE id IN (:ids)")
				.param("ids", userIds)
				.query(UserEmail.class)
				.list()
				.stream()
				.collect(Collectors.toMap(UserEmail::id, UserEmail::email));
	}

	record UserEmail(long id, String email) {
	}
}
```

- [ ] **Step 5: 통과 확인** — PASS(4 tests)

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/resources/db/migration/app/V15__monitoring_email_alarm.sql was/src/main/java/com/celfit/was/monitoring/MonitoringAlarmRepository.java was/src/test/java/com/celfit/was/monitoring/MonitoringAlarmRepositoryTest.java
git commit -m "feat(was): 모니터링 알람 저장 계층(V15) — 옵트아웃(행 없음=on)·이벤트별 워터마크"
```

---

### Task 2: 계약 v1.0 정렬 — 알람 쿼리 상태 조건 + findByTargetIds

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringReadRepository.java` (findPendingCandidatesSince)
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringCampaignMappingRepository.java` (findByTargetIds 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringReadRepositoryTest.java` (테스트 1개 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringCampaignMappingRepositoryTest.java` (테스트 1개 추가)

- [ ] **Step 1: 실패하는 테스트 2개 추가**

`MonitoringReadRepositoryTest`에 (기존 seedTarget 헬퍼 사용):

```java
	@Test
	void 종결_캠페인의_잔여_PENDING은_알람_조회에서_제외된다() {
		// 계약 v1.0: 종결 캠페인 후보는 승인·거절이 모두 409라 알람이 나가면 안 된다
		long active = seedTarget("acc_live", null, "WATCHING");
		long closed = seedTarget("acc_closed", null, "EXPIRED");
		jdbc.sql("""
				INSERT INTO detected_candidate (target_id, short_code, detected_at, caption_excerpt, status)
				VALUES (:a, 'LIVE1', now(), '…', 'PENDING'),
				       (:c, 'DEAD1', now(), '…', 'PENDING')
				""").param("a", active).param("c", closed).update();

		List<PendingCandidate> fresh = repository.findPendingCandidatesSince(
				Instant.now().minusSeconds(3600));

		assertThat(fresh).hasSize(1);
		assertThat(fresh.get(0).shortCode()).isEqualTo("LIVE1");
	}
```

`MonitoringCampaignMappingRepositoryTest`에:

```java
	@Test
	void target_다건_역방향_조회() {
		UUID key1 = UUID.randomUUID();
		UUID key2 = UUID.randomUUID();
		repository.insertPending(userId, key1);
		repository.confirmTarget(key1, 101L);
		repository.insertPending(userId, key2);
		repository.confirmTarget(key2, 102L);

		List<MonitoringCampaignMapping> found = repository.findByTargetIds(List.of(101L, 102L, 999L));

		assertThat(found).hasSize(2);
		assertThat(found).allSatisfy(m -> assertThat(m.userId()).isEqualTo(userId));
		assertThat(repository.findByTargetIds(List.of())).isEmpty();
	}
```

(import `java.util.List` 필요 시 추가)

- [ ] **Step 2: 실패 확인** — 두 테스트 클래스 실행: 역방향 조회는 컴파일 실패, 종결 제외는 hasSize(1)에서 FAIL(현재 2건 반환) 예상

- [ ] **Step 3: 구현**

`MonitoringReadRepository.findPendingCandidatesSince`의 SQL만 교체 (계약 v1.0 알람 쿼리 그대로):

```java
	/** 워터마크 이후 신규 PENDING(계약 §3 알람 쿼리 그대로 — v1.0: 활성 캠페인만). */
	public List<PendingCandidate> findPendingCandidatesSince(Instant since) {
		return jdbc.sql("""
				SELECT c.id, c.target_id, c.short_code, c.caption_excerpt, c.detected_at, t.username
				FROM detected_candidate c JOIN target t ON t.id = c.target_id
				WHERE c.status = 'PENDING' AND c.detected_at > :since
				  AND t.status IN ('WATCHING', 'TRACKING')
				ORDER BY c.detected_at
				""")
				.param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
				.query(PendingCandidate.class)
				.list();
	}
```

`MonitoringCampaignMappingRepository`에 추가:

```java
	/** 알람 크론용 역방향 조회 — 후보의 target들을 소유 유저로 해석한다(스펙 2026-07-29 §4). */
	public List<MonitoringCampaignMapping> findByTargetIds(Collection<Long> targetIds) {
		if (targetIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbcClient.sql("""
				SELECT id, user_id, registration_key, target_id, created_at
				FROM app.monitoring_campaigns
				WHERE target_id IN (:targetIds)
				""")
				.param("targetIds", targetIds)
				.query(MonitoringCampaignMapping.class)
				.list();
	}
```

(import `java.util.Collection` 추가)

- [ ] **Step 4: 통과 확인** — 두 테스트 클래스 전체 PASS (기존 테스트 포함 Read 7 + Mapping 6)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/ was/src/test/java/com/celfit/was/monitoring/
git commit -m "feat(was): 계약 v1.0 정렬 — 알람 쿼리 활성 캠페인 한정 + target 역방향 조회"
```

---

### Task 3: MonitoringAlarmMailComposer + MonitoringAlarmJob

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringAlarmMailComposer.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/MonitoringAlarmJob.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringAlarmJobTest.java`

- [ ] **Step 1: Composer 작성**

```java
package com.celfit.was.monitoring;

import java.util.List;

/**
 * 알람 메일 문안 조립 — 임시(test) 문안(사용자 결정 2026-07-29). 정식 문안·딥링크는
 * 프론트 기획 확정 후 이 클래스만 교체하면 된다(잡 로직과 분리 목적).
 */
public class MonitoringAlarmMailComposer {

	public String subject(int count) {
		return "[hypenow] 모니터링 알림 — 새 게시물 감지 " + count + "건";
	}

	public String body(List<PendingCandidate> candidates) {
		StringBuilder body = new StringBuilder("등록하신 캠페인에서 조건에 맞는 게시물이 감지되었습니다.\n\n");
		for (PendingCandidate candidate : candidates) {
			body.append("- @").append(candidate.username())
					.append(" — 게시물 ").append(candidate.shortCode()).append('\n');
			if (candidate.captionExcerpt() != null) {
				body.append("  ").append(candidate.captionExcerpt()).append('\n');
			}
		}
		body.append("\nhypenow 콘텐츠 모니터링에서 확인 후 승인/기각해 주세요. (임시 안내 메일)");
		return body.toString();
	}
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.celfit.was.IntegrationTest;
import com.celfit.was.mail.MailSender;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MonitoringAlarmJobTest extends IntegrationTest {

	static final OffsetDateTime BASE = OffsetDateTime.parse("2026-07-01T00:00:00+09:00");
	static final OffsetDateTime DETECTED_1 = OffsetDateTime.parse("2026-07-28T02:00:00+09:00");
	static final OffsetDateTime DETECTED_2 = OffsetDateTime.parse("2026-07-29T02:00:00+09:00");

	@Autowired
	DataSource dataSource;
	@Autowired
	MonitoringCampaignMappingRepository mappings;
	@Autowired
	MonitoringAlarmRepository alarmRepository;
	@Autowired
	JdbcClient jdbcClient;

	JdbcClient monitoringJdbc;
	MailSender mailSender;
	MonitoringAlarmJob job;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot RESTART IDENTITY")
				.update();
		jdbcClient.sql("TRUNCATE app.monitoring_campaigns, app.monitoring_email_opt_outs").update();
		jdbcClient.sql("""
				UPDATE app.monitoring_alarm_state SET last_notified_at = :base
				WHERE event_type = 'POST_DETECTED'
				""").param("base", BASE).update();

		mailSender = mock(MailSender.class);
		job = new MonitoringAlarmJob(new MonitoringReadRepository(monitoringJdbc), mappings,
				alarmRepository, new MonitoringAlarmMailComposer(), mailSender);
	}

	long seedUser() {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id
				""")
				.param("email", "job-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	long seedTarget(String username, String status) {
		return monitoringJdbc.sql("""
				INSERT INTO target (type, username, status, registration_key, expires_at)
				VALUES ('ACCOUNT', :username, :status, gen_random_uuid()::text, now() + interval '30 days')
				RETURNING id
				""")
				.param("username", username).param("status", status)
				.query(Long.class).single();
	}

	void seedCandidate(long targetId, String shortCode, OffsetDateTime detectedAt) {
		monitoringJdbc.sql("""
				INSERT INTO detected_candidate (target_id, short_code, detected_at, caption_excerpt, status)
				VALUES (:t, :sc, :at, '…샤넬…', 'PENDING')
				""")
				.param("t", targetId).param("sc", shortCode).param("at", detectedAt).update();
	}

	long linkUserToTarget(long userId, long targetId) {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		mappings.confirmTarget(key, targetId);
		return targetId;
	}

	String emailOf(long userId) {
		return jdbcClient.sql("SELECT email FROM app.users WHERE id = :id")
				.param("id", userId).query(String.class).single();
	}

	@Test
	void 유저당_한_통으로_묶어_발송하고_워터마크를_전진한다() {
		long user = seedUser();
		long t1 = linkUserToTarget(user, seedTarget("acc1", "WATCHING"));
		long t2 = linkUserToTarget(user, seedTarget("acc2", "TRACKING"));
		seedCandidate(t1, "NEW1", DETECTED_1);
		seedCandidate(t2, "NEW2", DETECTED_2);

		job.sendPostDetectedAlarms();

		verify(mailSender).send(eq(emailOf(user)), contains("2건"), contains("NEW1"));
		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(DETECTED_2);
	}

	@Test
	void 옵트아웃_유저는_제외되고_워터마크는_전진한다() {
		long user = seedUser();
		long t1 = linkUserToTarget(user, seedTarget("acc1", "WATCHING"));
		seedCandidate(t1, "NEW1", DETECTED_1);
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
				VALUES (:u, 'POST_DETECTED')
				""").param("u", user).update();

		job.sendPostDetectedAlarms();

		verify(mailSender, never()).send(anyString(), anyString(), anyString());
		// 발송 대상 0명 = 처리 완료 — 워터마크는 전진해야 다음 회차가 같은 후보를 재평가하지 않는다
		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(DETECTED_1);
	}

	@Test
	void 발송_실패가_있으면_워터마크를_유지한다() {
		long user = seedUser();
		long t1 = linkUserToTarget(user, seedTarget("acc1", "WATCHING"));
		seedCandidate(t1, "NEW1", DETECTED_1);
		doThrow(new RuntimeException("발송 실패")).when(mailSender)
				.send(anyString(), anyString(), anyString());

		job.sendPostDetectedAlarms();   // 예외를 밖으로 던지지 않는다(크론 스레드 보호)

		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(BASE);
	}

	@Test
	void 매핑_없는_후보는_스킵되고_나머지는_정상_발송된다() {
		long user = seedUser();
		long linked = linkUserToTarget(user, seedTarget("acc1", "WATCHING"));
		long orphan = seedTarget("acc_orphan", "WATCHING");   // 매핑 없음(탈퇴 CASCADE 등)
		seedCandidate(linked, "NEW1", DETECTED_1);
		seedCandidate(orphan, "ORPHAN1", DETECTED_2);

		job.sendPostDetectedAlarms();

		verify(mailSender).send(eq(emailOf(user)), contains("1건"), contains("NEW1"));
		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(DETECTED_2);
	}

	@Test
	void 신규_후보가_없으면_아무것도_하지_않는다() {
		job.sendPostDetectedAlarms();

		verifyNoInteractions(mailSender);
		assertThat(alarmRepository.watermark("POST_DETECTED")).isEqualTo(BASE);
	}
}
```

- [ ] **Step 3: 실패 확인** — 컴파일 실패(`MonitoringAlarmJob` 없음) 예상

- [ ] **Step 4: Job 구현**

```java
package com.celfit.was.monitoring;

import com.celfit.was.mail.MailSender;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 이메일 알람 크론(계약 §4) — 게시물 감지(POST_DETECTED) 1종, 매일 09:00 KST.
 * 중복 발송 방지는 전적으로 was 책임: 이벤트별 워터마크로 관리하고, 발송 실패가 하나라도
 * 있으면 워터마크를 전진하지 않는다(다음 회차 재발송 — 유실보다 중복이 낫다는 결정, 스펙 §3).
 * 실패를 밖으로 던지지 않는다 — 스케줄 스레드 보호, 관측은 로그로.
 */
public class MonitoringAlarmJob {

	public static final String EVENT_POST_DETECTED = "POST_DETECTED";

	private static final Logger log = LoggerFactory.getLogger(MonitoringAlarmJob.class);

	private final MonitoringReadRepository readRepository;
	private final MonitoringCampaignMappingRepository mappings;
	private final MonitoringAlarmRepository alarmRepository;
	private final MonitoringAlarmMailComposer composer;
	private final MailSender mailSender;

	public MonitoringAlarmJob(MonitoringReadRepository readRepository,
			MonitoringCampaignMappingRepository mappings, MonitoringAlarmRepository alarmRepository,
			MonitoringAlarmMailComposer composer, MailSender mailSender) {
		this.readRepository = readRepository;
		this.mappings = mappings;
		this.alarmRepository = alarmRepository;
		this.composer = composer;
		this.mailSender = mailSender;
	}

	@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
	public void sendPostDetectedAlarms() {
		OffsetDateTime watermark = alarmRepository.watermark(EVENT_POST_DETECTED);
		List<PendingCandidate> fresh = readRepository.findPendingCandidatesSince(watermark.toInstant());
		if (fresh.isEmpty()) {
			log.info("모니터링 알람: 신규 감지 후보 없음 (워터마크 {})", watermark);
			return;
		}

		Map<Long, Long> userByTarget = new LinkedHashMap<>();
		mappings.findByTargetIds(fresh.stream().map(PendingCandidate::targetId).distinct().toList())
				.forEach(mapping -> userByTarget.put(mapping.targetId(), mapping.userId()));

		Map<Long, List<PendingCandidate>> byUser = new LinkedHashMap<>();
		for (PendingCandidate candidate : fresh) {
			Long userId = userByTarget.get(candidate.targetId());
			if (userId == null) {
				// 매핑 없음 — 탈퇴(CASCADE) 등. 발송 불가이므로 스킵, 관측만 남긴다
				log.warn("모니터링 알람: 매핑 없는 후보 스킵 targetId={} candidateId={}",
						candidate.targetId(), candidate.id());
				continue;
			}
			byUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(candidate);
		}

		Set<Long> optedOut = alarmRepository.optedOutUserIds(EVENT_POST_DETECTED, byUser.keySet());
		byUser.keySet().removeAll(optedOut);
		Map<Long, String> emails = alarmRepository.emailsByUserIds(byUser.keySet());

		int failures = 0;
		for (Map.Entry<Long, List<PendingCandidate>> entry : byUser.entrySet()) {
			try {
				mailSender.send(emails.get(entry.getKey()),
						composer.subject(entry.getValue().size()), composer.body(entry.getValue()));
			} catch (RuntimeException e) {
				failures++;
				log.error("모니터링 알람 발송 실패 userId={}", entry.getKey(), e);
			}
		}

		if (failures == 0) {
			// now()가 아니라 처리분 기준 전진 — 실행 중 새로 감지된 행은 다음 회차가 줍는다
			OffsetDateTime maxDetected = fresh.stream().map(PendingCandidate::detectedAt)
					.max(Comparator.naturalOrder()).orElseThrow();
			alarmRepository.advanceWatermark(EVENT_POST_DETECTED, maxDetected);
			log.info("모니터링 알람: {}명 발송(옵트아웃 {}명 제외), 워터마크 {} 전진",
					byUser.size(), optedOut.size(), maxDetected);
		} else {
			log.error("모니터링 알람: 발송 실패 {}건 — 워터마크 유지(다음 회차 재발송, 중복 수신 가능)",
					failures);
		}
	}
}
```

- [ ] **Step 5: 통과 확인** — PASS(5 tests)

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/ was/src/test/java/com/celfit/was/monitoring/MonitoringAlarmJobTest.java
git commit -m "feat(was): 모니터링 이메일 알람 크론 — 게시물 감지 09:00 발송·유저당 묶음·실패 시 워터마크 유지"
```

---

### Task 4: Config 배선 + 전체 검증 + 문서

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringConfig.java`
- Modify: `was/src/test/java/com/celfit/was/MonitoringDisabledTest.java` / `MonitoringEnabledConfigTest.java` (단언 추가)
- Modify: `ARCHITECTURE.md` (§5 트랙 갱신·§7 결정 기록), 스펙·플랜 상태 헤더

- [ ] **Step 1: 실패하는 단언 추가**

`MonitoringDisabledTest`에:

```java
	@Test
	void 비활성이면_알람_잡도_없다() {
		assertThat(context.getBeanNamesForType(com.celfit.was.monitoring.MonitoringAlarmJob.class)).isEmpty();
	}
```

`MonitoringEnabledConfigTest`에:

```java
	@Test
	void 활성이면_알람_잡이_뜬다() {
		assertThat(context.getBeanNamesForType(com.celfit.was.monitoring.MonitoringAlarmJob.class)).hasSize(1);
	}
```

- [ ] **Step 2: MonitoringConfig 수정** — 클래스에 `@EnableScheduling` 추가(import `org.springframework.scheduling.annotation.EnableScheduling`) + 빈 추가:

```java
	@Bean
	MonitoringAlarmJob monitoringAlarmJob(MonitoringCampaignMappingRepository mappings,
			MonitoringAlarmRepository alarmRepository, com.celfit.was.mail.MailSender mailSender) {
		return new MonitoringAlarmJob(monitoringReadRepository(), mappings, alarmRepository,
				new MonitoringAlarmMailComposer(), mailSender);
	}
```

(주석 한 줄: `// @EnableScheduling은 이 조건부 Config에만 — 비활성 환경엔 스케줄러 자체가 없다`)

- [ ] **Step 3: 통과 확인** — Disabled 3 / Enabled 5 PASS

- [ ] **Step 4: 전체 테스트** — `./gradlew :was:test` (colima env) BUILD SUCCESSFUL·실패 0 (총 개수 XML 집계 보고)

- [ ] **Step 5: 문서 갱신·커밋**

- ARCHITECTURE §5: 모니터링 seam 트랙(S) 행의 요약에 "이메일 알람 1차(감지)" 반영 또는 관례에 맞게 갱신, §7에 한 줄:
  "2026-07-29 모니터링 이메일 알람(was): 옵트아웃(행 없음=on)·이벤트별 워터마크(V15), 부분 실패 시 워터마크 유지(유실보다 중복), 문안은 Composer 격리(임시). 알람 쿼리 계약 v1.0 정렬(활성 캠페인 한정). 게시물 숨김은 monitoring 계약에 신호 없음 — 확장 필요."
- 스펙 상태 헤더 → `✅ 구현됨 (2026-07-29)`, 플랜 상태 헤더 → `✅ 구현됨`
- 커밋: `docs: ARCHITECTURE·스펙 상태 — 모니터링 이메일 알람 1차 반영`

---

## Self-Review 결과 (작성 시 반영)

- 스펙 커버리지: §2 저장(Task 1), §3 크론·부분 실패·Composer(Task 3), §4 v1.0 정렬 3건(Task 2 — 픽스처 대조는 이미 완료·문서 기록), §5 테스트 전 항목(각 Task), §6 문서(Task 4). 제외 항목(토글 API·타 이벤트·딥링크)은 스펙 §1에 명시.
- 타입 일관성: MonitoringAlarmJob 생성자 시그니처(Task 3)와 Config 빈(Task 4), PendingCandidate 컴포넌트(detectedAt·targetId·shortCode·captionExcerpt·username — 기존 record) 대조 완료.
- 주의: `옵트아웃` 테스트의 워터마크 전진 단언 — 발송 대상 0명이어도 후보를 "처리"한 것이므로 전진이 맞다(Task 3 Step 2 주석 참조).
