# 주간 알림 다이제스트 개편 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 · 2026-08-27 작성. 설계 정본은 [specs/2026-08-27-weekly-notification-digest-design.md](../specs/2026-08-27-weekly-notification-digest-design.md).

**Goal:** 콘텐츠 알림 4종의 일일 다이제스트를 폐지하고, 브랜드 새 게시물 발견과 등록 게시물 광고 미표기 판정을 더한 **주 1건(월 09:00 KST) 인앱 알림 + 주간 리포트 메일 1통**으로 통합한다.

**Architecture:** 주간 집계 잡(`WeeklyDigestJob`, was)이 명시적 주간 창(`WeekWindow`, 월~일 KST)을 받아 monitoring DB의 `alarm_event`·`brand_tagged_post`·`brand_hashtag_post`·`brand_post_meta`와 app 스키마의 등록 원장을 **was 코드에서 조합**해 `app.monitoring_digests`에 `(user_id, 주 시작일)` 멱등 upsert한다(크로스 DB 조인 금지 — 시스템 경계). 조립은 순수 `WeeklyDigestAssembler`가, 메일은 was의 기존 Resend 스택(`com.celfit.was.mail.MailSender`)을 쓰는 `WeeklyDigestMailer`가 다이제스트 생성 직후 같은 루프에서 발송하며, 발송 상태는 다이제스트 행의 `email_sent_at`·`email_attempts`가 들고 있어 따라잡기 틱이 실패분만 재시도한다. monitoring 모듈의 5분 틱 디스패처·디바운스·즉시 레인·중복 Resend 스택은 통째로 제거한다.

**Tech Stack:** Java 21 · Spring Boot 4.1 · Gradle 멀티모듈(was/monitoring) · JdbcClient + record DTO · Flyway(app: `was/src/main/resources/db/migration/app`, UTC 타임스탬프 채번) · Jackson 3(`tools.jackson.*`) · JUnit 5 + AssertJ + Mockito · Testcontainers(PostgreSQL) · Resend(MailSender)

**전제 · 실행 규칙**

- 모든 경로는 worktree 루트 `/Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign` 기준이다.
- 테스트 전 셸에 반드시 다음을 export한다. 빠뜨리면 Testcontainers 테스트가 무더기로 죽는다(CLAUDE.md 함정).
  ```
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  ```
- 테스트는 모듈 단위로만 돌린다(`./gradlew :was:test`). 전체 `./gradlew test`는 PR 직전 1회.
- 커밋 메시지는 한국어, prefix는 `feat(was):`/`feat(monitoring):`/`chore(deploy):`/`docs:`. 푸터는
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **사용자向 문안에 엠대시(—) 금지.** 구분은 `" - "`·쉼표·가운뎃점(`·`)으로.
- PR은 열지 않는다.

**설계 대비 코드 현실 보정 3가지**(이 계획은 코드 현실을 따른다)

1. 설계 §4는 광고 판정 컬럼을 `brand_tagged_post.ad_verdict`로 적었으나 실제 컬럼은 **`brand_post_meta.ad_verdict`·`ad_judged_at`**이다(V20260817160000). 이 계획은 `brand_post_meta`를 읽는다.
2. 설계 §4는 등록 원장을 `app.brand_post_registrations`로 적었으나, "이 유저가 이 게시물을 등록했다"의 정본은 **`app.brand_direct_posts`**(PK `(user_id, short_code)`)다. `brand_post_registrations`는 요청·엔트리 이력이다. 이 계획은 `BrandDirectPostRepository.shortCodesByUser`를 쓴다.
3. 설계 §6은 "Resend 발송 스택 유지"라 적었는데, monitoring 모듈의 발송 스택은 5분 틱 디스패처 전용 사본이고 was에 동등한 스택(`com.celfit.was.mail`)이 이미 있다. 주간 발송이 was로 오므로 **monitoring 사본은 제거**하고 was 스택을 쓴다.

**태스크 목록**

| # | 태스크 | 설계 대응 |
|---|---|---|
| 1 | 주간 창 값 객체 `WeekWindow` | §4·§8 명시적 기간 파라미터 |
| 2 | app 스키마 expand 마이그레이션 | §7 |
| 3 | 주간 옵트아웃·미표기 이력 저장 계층 + 아카이브 등재 | §7·§8 |
| 4 | 브랜드 주간 조회 3종 + 활성 연결 전량 조회 | §4 |
| 5 | 캠페인 이름 문맥 조회 | §3 |
| 6 | `DigestItem` 모델과 `WeeklyDigestAssembler` | §3 |
| 7 | API 응답 확장(`metrics`)과 계약 유지 검증 | §3·§5 |
| 8 | `WeeklyDigestJob`(일일 `DigestJob` 대체) | §2·§4·§8 |
| 9 | 알림 설정 API 축소(주간 이메일 토글 1개) | §5 |
| 10 | 주간 리포트 메일 문안·발송 | §6·§8 |
| 11 | monitoring 이메일 디스패처·즉시 레인 폐지 | §4·§6 |
| 12 | 운영 설정 변경(compose·README) | §7 |

---

## Task 1: 주간 창 값 객체 `WeekWindow`

잡이 벽시계 대신 명시적 기간 값을 받게 하는 토대(설계 §8 "주간 경계 유실"). 월요일 09:00 실행과 주중 따라잡기 틱이 **같은 창**을 계산해야 재실행이 멱등해진다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/WeekWindow.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/WeekWindowTest.java`

**Steps:**

- [x] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/WeekWindowTest.java`에 아래 전문을 쓴다(컨테이너 불필요한 순수 단위 테스트).
  ```java
  package com.celfit.was.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  import java.time.LocalDate;
  import java.time.OffsetDateTime;
  import java.time.ZoneOffset;
  import org.junit.jupiter.api.Test;

  /** 주간 집계 창(월~일 KST) — 잡이 받는 명시적 기간 파라미터(설계 §8). */
  class WeekWindowTest {

  	@Test
  	void 월요일_실행은_직전_주_월요일이_시작일이다() {
  		WeekWindow window = WeekWindow.previousWeekOf(LocalDate.of(2026, 8, 24));   // 2026-08-24는 월요일

  		assertThat(window.startDate()).isEqualTo(LocalDate.of(2026, 8, 17));
  		assertThat(window.endDateInclusive()).isEqualTo(LocalDate.of(2026, 8, 23));
  	}

  	@Test
  	void 같은_주_어느_요일에_돌려도_같은_창을_준다() {
  		WeekWindow monday = WeekWindow.previousWeekOf(LocalDate.of(2026, 8, 24));
  		WeekWindow sunday = WeekWindow.previousWeekOf(LocalDate.of(2026, 8, 30));   // 같은 주의 일요일

  		assertThat(sunday).isEqualTo(monday);
  	}

  	@Test
  	void 경계는_시작일_KST_자정_포함과_다음_월요일_KST_자정_배타다() {
  		WeekWindow window = WeekWindow.previousWeekOf(LocalDate.of(2026, 8, 24));

  		assertThat(window.from())
  				.isEqualTo(OffsetDateTime.of(2026, 8, 17, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
  		assertThat(window.toExclusive())
  				.isEqualTo(OffsetDateTime.of(2026, 8, 24, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
  	}

  	@Test
  	void 월요일이_아닌_시작일은_거부한다() {
  		assertThatThrownBy(() -> new WeekWindow(LocalDate.of(2026, 8, 18)))
  				.isInstanceOf(IllegalArgumentException.class);
  	}
  }
  ```

- [x] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeekWindowTest"
  ```
  기대 출력: 컴파일 실패 — `error: cannot find symbol ... class WeekWindow`.

- [x] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/WeekWindow.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import com.celfit.was.v1.common.KstTimestamps;
  import java.time.DayOfWeek;
  import java.time.LocalDate;
  import java.time.OffsetDateTime;

  /**
   * 주간 다이제스트의 집계 창(월~일, KST) — 설계 §8 "주간 경계 유실" 대응. 잡은 벽시계에서
   * 창을 유도하는 대신 이 값을 명시적 파라미터로 받는다. 월요일 09:00 정시 실행과 그 주의
   * 따라잡기 틱이 전부 같은 창을 계산하므로, 몇 번을 다시 돌려도 같은 (user, 주 시작일) 행을
   * 멱등하게 덮어쓴다.
   *
   * <p>시작일은 항상 월요일이다 — 다이제스트 행의 digest_date가 곧 주 시작일이라(설계 §7)
   * 다른 요일이 섞이면 (user, digest_date) 유니크가 "주 1건" 계약을 더 이상 뜻하지 않는다.
   */
  public record WeekWindow(LocalDate startDate) {

  	public WeekWindow {
  		if (startDate.getDayOfWeek() != DayOfWeek.MONDAY) {
  			throw new IllegalArgumentException("주 시작일은 월요일이어야 한다: " + startDate);
  		}
  	}

  	/** 기준 KST 날짜가 속한 주의 <b>직전</b> 주(월요일 시작). 주중 어느 날에 불러도 같은 값이다. */
  	public static WeekWindow previousWeekOf(LocalDate kstToday) {
  		return new WeekWindow(kstToday.with(DayOfWeek.MONDAY).minusWeeks(1));
  	}

  	/** 창의 마지막 날(일요일, 포함) — KST 날짜 구간 조회의 상한. */
  	public LocalDate endDateInclusive() {
  		return startDate.plusDays(6);
  	}

  	/** 창 시작(포함) — 월요일 KST 자정. timestamptz 컬럼 범위 조회용. */
  	public OffsetDateTime from() {
  		return startDate.atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
  	}

  	/** 창 끝(배타) — 다음 월요일 KST 자정. 경계 이벤트가 두 주에 겹치지 않게 항상 배타로 쓴다. */
  	public OffsetDateTime toExclusive() {
  		return startDate.plusWeeks(1).atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
  	}
  }
  ```

- [x] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeekWindowTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`, 4개 테스트 통과.

- [x] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add was/src/main/java/com/celfit/was/monitoring/WeekWindow.java was/src/test/java/com/celfit/was/monitoring/WeekWindowTest.java
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): 주간 다이제스트 집계 창 값 객체 WeekWindow 추가

  월~일 KST 창을 명시적 파라미터로 다루기 위한 토대. 주중 어느 틱에서 불러도
  같은 창을 주므로 재실행·따라잡기가 멱등해진다(설계 §8).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 2: app 스키마 expand 마이그레이션

설계 §7의 이관·§8의 알림 이력 가드·§6의 발송 상태를 한 파일에 담는다. **전부 expand 단계**(신규 테이블, CHECK 허용값 확대, nullable/DEFAULT 있는 ADD COLUMN)라 롤링 창에서 구버전 was가 깨지지 않는다. 구 4종 옵트아웃 행과 CHECK의 구 어휘는 **이번 릴리스에서 지우지 않는다** — contract 단계(다음 릴리스) 몫이다.

**Files:**
- Create: `was/src/main/resources/db/migration/app/V20260827135725__weekly_notification_digest_expand.sql`(재채번 — 아래 참조)

**Steps:**

- [x] 마이그레이션 파일 작성 — 위 경로에 아래 전문을 쓴다. **실제로는 구현 시점에 `date -u +%Y%m%d%H%M%S`로 재채번**해 `20260827135725`를 썼다(디렉토리 최대 기존 버전 `20260819050953`보다 큼 확인 — 세션 지시에 따름, 계획서 원안의 `20260827112529`는 미사용).
  ```sql
  -- 주간 알림 다이제스트 개편(2026-08-27 설계 §6·§7·§8) — expand 단계.
  -- 셋 다 additive다: ① 옵트아웃 CHECK 허용값 확대 + 보수적 이관 INSERT, ② 신규 테이블 1개,
  -- ③ monitoring_digests에 DEFAULT 있는 ADD COLUMN 2개. DROP·RENAME·타입 변경·SET NOT NULL 없음.

  -- ① 주간 이메일 수신 토글(설계 §5) — 별도 테이블을 만들지 않고 기존 옵트아웃 테이블의
  -- event_type 어휘를 하나 넓힌다. 이미 아카이브 카탈로그·탈퇴 이관 경로에 배선된 테이블이라
  -- 새 테이블을 만들 때 필요한 배선(ArchiveTables·ACCOUNT_DELETION_ORDER)이 통째로 불필요하다.
  -- CHECK 확대는 허용 범위를 넓히기만 하므로 롤링 중 구버전 코드가 이 값을 몰라도 위반이 없다
  -- (선례: V20260827060558 brand_post_meta_ad_verdict_check).
  ALTER TABLE app.monitoring_email_opt_outs DROP CONSTRAINT monitoring_email_opt_outs_event_type_check;
  ALTER TABLE app.monitoring_email_opt_outs ADD CONSTRAINT monitoring_email_opt_outs_event_type_check
      CHECK (event_type IN ('COLLECTION_STARTED', 'COLLECTION_ENDED', 'METRICS_HIDDEN',
                            'CONTENT_UNAVAILABLE', 'WEEKLY_DIGEST'));

  -- 보수적 이관(설계 §7) — 기존 4종 중 하나라도 꺼 둔 유저는 주간 이메일도 off로 시작한다.
  -- 구 4종 행은 그대로 남긴다(contract 단계에서 정리) — 롤링 창의 구버전 was가 아직 읽는다.
  INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
  SELECT DISTINCT user_id, 'WEEKLY_DIGEST' FROM app.monitoring_email_opt_outs
  ON CONFLICT DO NOTHING;

  -- ② 미표기 판정 알림 이력(설계 §8 "미표기 재판정 중복") — 게시물당 1회 알림 가드.
  -- notified_week를 함께 들고 있어야 같은 주 재실행(따라잡기·재기동)이 자기가 방금 남긴
  -- 이력에 걸려 그 주 알림을 스스로 지우는 자기무효화가 생기지 않는다.
  CREATE TABLE app.ad_disclosure_notices (
      user_id       bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
      short_code    text        NOT NULL,
      notified_week date        NOT NULL,   -- 알린 주의 시작일(월요일)
      created_at    timestamptz NOT NULL DEFAULT now(),
      PRIMARY KEY (user_id, short_code)
  );

  -- ③ 주간 리포트 메일 발송 상태(설계 §6) — 다이제스트 행이 곧 발송 대장이다. 워터마크 없이
  -- "안 보냈고 시도 상한 미달인 행"만 발송 대상이 되고, 실패는 시도만 올려 다음 따라잡기 틱이
  -- 그 행만 다시 집는다(at-least-once). DEFAULT가 있는 ADD COLUMN이라 기존 행도 즉시 유효하다.
  ALTER TABLE app.monitoring_digests
      ADD COLUMN email_sent_at  timestamptz,
      ADD COLUMN email_attempts smallint NOT NULL DEFAULT 0;
  ```

- [x] 실행해 마이그레이션 적용 확인 — 마이그레이션은 통합 테스트 부팅 시 Flyway가 적용한다. 기존 테스트 하나로 부팅을 검증한다.
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.DigestRepositoryTest"
  ```
  기대 출력: `BUILD SUCCESSFUL` (Flyway가 새 버전을 적용하고 기존 다이제스트 테스트가 그대로 통과). **확인됨** — `BUILD SUCCESSFUL in 38s`.

- [x] 마이그레이션 가드 확인 — 파괴적 구문이 없는지 눈으로 재확인한다(가드는 CI에서 돈다).
  ```
  grep -nE 'DROP TABLE|DROP COLUMN|RENAME|SET NOT NULL|ALTER COLUMN .* TYPE' was/src/main/resources/db/migration/app/V20260827135725__weekly_notification_digest_expand.sql
  ```
  기대 출력: 매치 없음(exit code 1). **실제로는 헤더 주석 3행("DROP·RENAME·타입 변경·SET NOT NULL 없음")의 서술 텍스트가 이 단순 grep에 문자 그대로 걸려 매치 1건(exit 0)이 나온다** — SQL 본문이 아니라 주석 안에서 "없다"고 설명하는 문장 자체가 패턴에 맞음. 실제 CI 가드(`.github/scripts/check-migration-safety.sh`, `sed 's/--.*$//'`로 주석 제거 후 검사)로 직접 재확인: `--scan` 실행 결과 `OK`(파괴적 DDL 없음 확인).

- [x] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add was/src/main/resources/db/migration/app/V20260827135725__weekly_notification_digest_expand.sql
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): 주간 알림 개편 app 스키마 expand 마이그레이션

  옵트아웃 어휘에 WEEKLY_DIGEST 추가 + 보수적 이관, 미표기 알림 이력 테이블 신설,
  monitoring_digests에 주간 메일 발송 상태 2컬럼 추가(설계 §6·§7·§8).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---
## Task 3: 주간 옵트아웃·미표기 이력 저장 계층 + 아카이브 등재

주간 잡과 메일이 쓸 저장소 2개를 **새로 추가만** 한다. 구 `EmailOptOutRepository`와 알림 설정 API는 아직 손대지 않는다 — 그 클래스를 여기서 지우면 Task 9에서야 고칠 기존 테스트 2개가 컴파일 단계에서 깨진다(Gradle은 `--tests` 필터와 무관하게 테스트 소스셋 전체를 컴파일한다). 계약 교체는 Task 9가 한 번에 처리한다.

새 테이블 `app.ad_disclosure_notices`는 `app.users` CASCADE 자식이라 `ArchiveCascadeReachabilityTest`·`ArchiveInventoryTest`가 아카이브 등재를 강제한다 — 같은 태스크에서 등재까지 끝낸다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/WeeklyEmailOptOutRepository.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/AdDisclosureNoticeRepository.java`
- Create: `was/src/test/java/com/celfit/was/monitoring/WeeklyEmailOptOutRepositoryTest.java`
- Create: `was/src/test/java/com/celfit/was/monitoring/AdDisclosureNoticeRepositoryTest.java`
- Modify: `was/src/main/java/com/celfit/was/archive/ArchiveTables.java` (137~141행 뒤에 상수 추가, 149~167행 `CATALOG`, 175~191행 `ACCOUNT_DELETION_ORDER`)

**Steps:**

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/WeeklyEmailOptOutRepositoryTest.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.celfit.was.IntegrationTest;
  import java.util.UUID;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.jdbc.core.simple.JdbcClient;

  /** 주간 리포트 메일 옵트아웃 — 행 없음 = 수신(기본 on), 행 있음 = 수신 거부(설계 §5). */
  class WeeklyEmailOptOutRepositoryTest extends IntegrationTest {

  	@Autowired
  	WeeklyEmailOptOutRepository repository;
  	@Autowired
  	JdbcClient jdbcClient;

  	long userId;

  	@BeforeEach
  	void 유저_시드() {
  		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
  				.param("email", "weekly-optout-" + UUID.randomUUID() + "@test.io")
  				.query(Long.class).single();
  	}

  	@Test
  	void 기본_상태는_수신이다() {
  		assertThat(repository.isOptedOut(userId)).isFalse();
  	}

  	@Test
  	void optOut_왕복() {
  		repository.optOut(userId);

  		assertThat(repository.isOptedOut(userId)).isTrue();
  	}

  	@Test
  	void optOut_두_번_호출해도_멱등() {
  		repository.optOut(userId);
  		repository.optOut(userId);

  		assertThat(repository.isOptedOut(userId)).isTrue();
  		assertThat(jdbcClient.sql("""
  				SELECT count(*) FROM app.monitoring_email_opt_outs
  				WHERE user_id = :userId AND event_type = 'WEEKLY_DIGEST'
  				""").param("userId", userId).query(Long.class).single()).isEqualTo(1);
  	}

  	@Test
  	void optIn_후_다시_수신() {
  		repository.optOut(userId);
  		repository.optIn(userId);

  		assertThat(repository.isOptedOut(userId)).isFalse();
  	}

  	@Test
  	void optIn_행이_없어도_에러_없이_통과() {
  		repository.optIn(userId);

  		assertThat(repository.isOptedOut(userId)).isFalse();
  	}

  	@Test
  	void 구_4종_옵트아웃_행은_주간_판정에_영향을_주지_않는다() {
  		// 이관은 마이그레이션이 이미 끝냈다 — 이후 새로 생긴 구 어휘 행이 주간 토글을 오염시키면 안 된다.
  		jdbcClient.sql("""
  				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
  				VALUES (:userId, 'COLLECTION_ENDED')
  				""").param("userId", userId).update();

  		assertThat(repository.isOptedOut(userId)).isFalse();
  	}
  }
  ```

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/AdDisclosureNoticeRepositoryTest.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.celfit.was.IntegrationTest;
  import java.time.LocalDate;
  import java.util.List;
  import java.util.UUID;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.jdbc.core.simple.JdbcClient;

  /**
   * 미표기 판정 알림 이력 — 게시물당 1회 가드(설계 §8). 같은 주 재실행은 자기 이력에 걸리지
   * 않아야 하고(멱등), 다른 주의 재판정분은 걸러져야 한다.
   */
  class AdDisclosureNoticeRepositoryTest extends IntegrationTest {

  	private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
  	private static final LocalDate NEXT_WEEK = LocalDate.of(2026, 8, 24);

  	@Autowired
  	AdDisclosureNoticeRepository repository;
  	@Autowired
  	JdbcClient jdbcClient;

  	long userId;

  	@BeforeEach
  	void 유저_시드() {
  		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
  				.param("email", "ad-notice-" + UUID.randomUUID() + "@test.io")
  				.query(Long.class).single();
  	}

  	@Test
  	void 이력이_없으면_걸러낼_대상도_없다() {
  		assertThat(repository.findNotifiedInOtherWeek(userId, List.of("SC1", "SC2"), WEEK)).isEmpty();
  	}

  	@Test
  	void 빈_입력은_조회하지_않고_빈_집합() {
  		assertThat(repository.findNotifiedInOtherWeek(userId, List.of(), WEEK)).isEmpty();
  	}

  	@Test
  	void 같은_주에_기록한_이력은_그_주_재실행에서_걸러지지_않는다() {
  		repository.markNotified(userId, List.of("SC1"), WEEK);

  		assertThat(repository.findNotifiedInOtherWeek(userId, List.of("SC1"), WEEK)).isEmpty();
  	}

  	@Test
  	void 다른_주에_기록한_이력은_이번_주_후보에서_걸러진다() {
  		repository.markNotified(userId, List.of("SC1"), WEEK);

  		assertThat(repository.findNotifiedInOtherWeek(userId, List.of("SC1", "SC2"), NEXT_WEEK))
  				.containsExactly("SC1");
  	}

  	@Test
  	void markNotified는_멱등이며_최초_주를_보존한다() {
  		repository.markNotified(userId, List.of("SC1"), WEEK);
  		repository.markNotified(userId, List.of("SC1"), NEXT_WEEK);

  		assertThat(jdbcClient.sql("""
  				SELECT notified_week FROM app.ad_disclosure_notices
  				WHERE user_id = :userId AND short_code = 'SC1'
  				""").param("userId", userId).query(LocalDate.class).single()).isEqualTo(WEEK);
  	}

  	@Test
  	void 다른_유저의_이력은_섞이지_않는다() {
  		long otherUserId = jdbcClient
  				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
  				.param("email", "ad-notice-other-" + UUID.randomUUID() + "@test.io")
  				.query(Long.class).single();
  		repository.markNotified(otherUserId, List.of("SC1"), WEEK);

  		assertThat(repository.findNotifiedInOtherWeek(userId, List.of("SC1"), NEXT_WEEK)).isEmpty();
  	}
  }
  ```

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyEmailOptOutRepositoryTest" --tests "com.celfit.was.monitoring.AdDisclosureNoticeRepositoryTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: class WeeklyEmailOptOutRepository`, `cannot find symbol: class AdDisclosureNoticeRepository`.

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/WeeklyEmailOptOutRepository.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Repository;

  /**
   * 주간 리포트 메일 수신 토글(2026-08-27 주간 개편 §5) — 저장은 기존 app.monitoring_email_opt_outs의
   * event_type='WEEKLY_DIGEST' 행이다. <b>행 없음 = 수신(기본 on)</b>, 행 있음 = 수신 거부.
   *
   * <p>별도 테이블을 만들지 않은 이유: 이 테이블은 이미 아카이브 카탈로그·탈퇴 이관 순서에
   * 배선돼 있어(ArchiveTables.MONITORING_EMAIL_OPT_OUTS) 새 테이블이었다면 필요했을 배선이
   * 통째로 불필요하다. 구 4종 어휘 행은 이번 릴리스에서 읽지도 쓰지도 않는다 — 롤링 창의
   * 구버전 was가 아직 그 행을 쓰기 때문에 남겨 두고, contract 단계에서 정리한다.
   */
  @Repository
  public class WeeklyEmailOptOutRepository {

  	/** 주간 토글 전용 event_type 값 — 마이그레이션 V20260827112529가 CHECK에 추가했다. */
  	private static final String WEEKLY_DIGEST = "WEEKLY_DIGEST";

  	private final JdbcClient jdbcClient;

  	public WeeklyEmailOptOutRepository(JdbcClient jdbcClient) {
  		this.jdbcClient = jdbcClient;
  	}

  	/** true면 주간 리포트 메일을 보내지 않는다. 행이 없으면(기본) false. */
  	public boolean isOptedOut(long userId) {
  		return Boolean.TRUE.equals(jdbcClient.sql("""
  				SELECT EXISTS (
  				    SELECT 1 FROM app.monitoring_email_opt_outs
  				    WHERE user_id = :userId AND event_type = :eventType
  				)
  				""")
  				.param("userId", userId)
  				.param("eventType", WEEKLY_DIGEST)
  				.query(Boolean.class)
  				.single());
  	}

  	/** 멱등 — 이미 거부 상태면 그대로 둔다. */
  	public void optOut(long userId) {
  		jdbcClient.sql("""
  				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
  				VALUES (:userId, :eventType)
  				ON CONFLICT DO NOTHING
  				""")
  				.param("userId", userId)
  				.param("eventType", WEEKLY_DIGEST)
  				.update();
  	}

  	/** 멱등 — 행이 없어도(이미 수신) 에러 없이 통과. */
  	public void optIn(long userId) {
  		jdbcClient.sql("""
  				DELETE FROM app.monitoring_email_opt_outs
  				WHERE user_id = :userId AND event_type = :eventType
  				""")
  				.param("userId", userId)
  				.param("eventType", WEEKLY_DIGEST)
  				.update();
  	}
  }
  ```

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/AdDisclosureNoticeRepository.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import java.time.LocalDate;
  import java.util.Collection;
  import java.util.LinkedHashSet;
  import java.util.Set;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Repository;

  /**
   * app.ad_disclosure_notices — 광고 미표기 판정 알림을 <b>게시물당 1회</b>로 묶는 이력(설계 §8).
   * 사전·프롬프트 갱신 후 리셋·재판정이 돌면 같은 게시물이 다음 주에 다시 NOT_DISCLOSED로 잡히는데,
   * 이 이력이 없으면 사용자가 같은 게시물을 매주 다시 통보받는다.
   *
   * <p>행이 주(notified_week)를 함께 들고 있는 이유: 이력을 "있다/없다"로만 두면 잡이 이번 주
   * 후보를 기록한 직후 같은 주 따라잡기 틱이 그 기록에 걸려 방금 만든 알림 항목을 스스로
   * 지워 버린다. "이번 주가 아닌 주에 이미 알린 것"만 걸러내면 그 자기무효화가 사라진다.
   */
  @Repository
  public class AdDisclosureNoticeRepository {

  	private final JdbcClient jdbcClient;

  	public AdDisclosureNoticeRepository(JdbcClient jdbcClient) {
  		this.jdbcClient = jdbcClient;
  	}

  	/** 후보 중 <b>이번 주가 아닌</b> 주에 이미 알린 shortCode 집합 — 호출부가 후보에서 뺀다. */
  	public Set<String> findNotifiedInOtherWeek(long userId, Collection<String> shortCodes, LocalDate weekStart) {
  		if (shortCodes.isEmpty()) {
  			return Set.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
  		}
  		return new LinkedHashSet<>(jdbcClient.sql("""
  				SELECT short_code FROM app.ad_disclosure_notices
  				WHERE user_id = :userId AND short_code IN (:shortCodes) AND notified_week <> :weekStart
  				""")
  				.param("userId", userId)
  				.param("shortCodes", shortCodes)
  				.param("weekStart", weekStart)
  				.query(String.class)
  				.list());
  	}

  	/** 이번 주 알림 대상 기록 — 이미 있으면 최초 주를 보존한다(같은 주 재실행 멱등). */
  	public void markNotified(long userId, Collection<String> shortCodes, LocalDate weekStart) {
  		for (String shortCode : shortCodes) {
  			jdbcClient.sql("""
  					INSERT INTO app.ad_disclosure_notices (user_id, short_code, notified_week)
  					VALUES (:userId, :shortCode, :weekStart)
  					ON CONFLICT (user_id, short_code) DO NOTHING
  					""")
  					.param("userId", userId)
  					.param("shortCode", shortCode)
  					.param("weekStart", weekStart)
  					.update();
  		}
  	}
  }
  ```

- [ ] 아카이브 카탈로그 등재 — `was/src/main/java/com/celfit/was/archive/ArchiveTables.java`의 `BRAND_HASHTAG_TAGS` 상수 정의(137~141행) 바로 뒤에 아래를 삽입한다.
  ```java
  	/**
  	 * 광고 미표기 알림 이력(2026-08-27 주간 개편 §8) — users CASCADE(직접 FK). BRAND_HASHTAG_TAGS와
  	 * 같은 위상이다(자식 없음, 단순 유저 소유 매핑). 탈퇴 시 이관만 하고 삭제는 users CASCADE가 한다.
  	 */
  	public static final ArchiveTable AD_DISCLOSURE_NOTICES = new ArchiveTable(
  			"app.ad_disclosure_notices", List.of("user_id", "short_code"), "t.user_id",
  			List.of(), "t.user_id = :userId");
  ```
  이어서 `CATALOG` 목록의 마지막 원소 `BRAND_HASHTAG_TAGS);`를 `BRAND_HASHTAG_TAGS,\n\t\t\tAD_DISCLOSURE_NOTICES);`로, `ACCOUNT_DELETION_ORDER`의 `BRAND_HASHTAG_TAGS,`(USERS 직전) 뒤에 `\t\t\tAD_DISCLOSURE_NOTICES,` 한 줄을 추가한다.

- [ ] 실행해 통과 확인 — 순수 추가라 기존 테스트는 영향받지 않는다.
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyEmailOptOutRepositoryTest" --tests "com.celfit.was.monitoring.AdDisclosureNoticeRepositoryTest" --tests "com.celfit.was.archive.*"
  ```
  기대 출력: `BUILD SUCCESSFUL`. 특히 `ArchiveCascadeReachabilityTest.users에서_CASCADE로_재귀_도달하는_테이블은_전부_ACCOUNT_DELETION_ORDER에_있다`가 통과해야 한다(등재 누락 시 `missing: [app.ad_disclosure_notices]`로 실패).

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add was/src/main/java/com/celfit/was/monitoring/WeeklyEmailOptOutRepository.java was/src/main/java/com/celfit/was/monitoring/AdDisclosureNoticeRepository.java was/src/test/java/com/celfit/was/monitoring/WeeklyEmailOptOutRepositoryTest.java was/src/test/java/com/celfit/was/monitoring/AdDisclosureNoticeRepositoryTest.java was/src/main/java/com/celfit/was/archive/ArchiveTables.java
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): 주간 이메일 옵트아웃·미표기 알림 이력 저장 계층 추가

  주간 토글 1개를 읽고 쓰는 저장소와, 미표기 판정을 게시물당 1회로 묶는
  ad_disclosure_notices 저장소를 추가했다. 새 테이블은 아카이브 카탈로그·탈퇴
  이관 순서에 등재했다(설계 §5·§8).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---
## Task 4: 브랜드 주간 조회 3종 + 활성 연결 전량 조회

설계 §4 표의 두 신규 소스를 읽는 쿼리를 만든다. 전부 monitoring DB만 읽고(시스템 경계), 유저 스코프(어떤 브랜드가 이 유저 것인지)와 등록 원장 대조는 was 코드가 한다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/WeeklyPostMetrics.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` (302행 `findHashtagPosts` 뒤에 메서드 3개 추가)
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandLinkRepository.java` (`findAllActiveByUser` 뒤에 `findAllActive` 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandWeeklyReadRepositoryTest.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandLinkRepositoryTest.java` (없으면 신규 생성)

**Steps:**

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/BrandWeeklyReadRepositoryTest.java`에 아래 전문을 쓴다(`BrandReadRepositoryTest`와 같은 관용구: `monitoring-brand-schema.sql` 픽스처 + 직접 시드).
  ```java
  package com.celfit.was.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.celfit.was.IntegrationTest;
  import java.sql.Connection;
  import java.time.LocalDate;
  import java.time.OffsetDateTime;
  import java.time.ZoneOffset;
  import java.util.List;
  import javax.sql.DataSource;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.core.io.ClassPathResource;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.jdbc.datasource.init.ScriptUtils;

  /**
   * 브랜드 주간 조회(설계 §4) — 지난주 발견분·미표기 판정. BrandReadRepositoryTest와 같은
   * 관용구(monitoring-brand-schema.sql 픽스처 + 직접 시드)를 따른다.
   */
  class BrandWeeklyReadRepositoryTest extends IntegrationTest {

  	private static final WeekWindow WEEK = new WeekWindow(LocalDate.of(2026, 8, 17));
  	private static final OffsetDateTime IN_WEEK =
  			OffsetDateTime.of(2026, 8, 19, 12, 0, 0, 0, ZoneOffset.ofHours(9));
  	private static final OffsetDateTime BEFORE_WEEK =
  			OffsetDateTime.of(2026, 8, 16, 23, 59, 0, 0, ZoneOffset.ofHours(9));
  	private static final OffsetDateTime AFTER_WEEK =
  			OffsetDateTime.of(2026, 8, 24, 0, 0, 1, 0, ZoneOffset.ofHours(9));

  	@Autowired
  	DataSource dataSource;

  	JdbcClient jdbc;
  	BrandReadRepository repository;
  	long brandId;

  	@BeforeEach
  	void setUp() throws Exception {
  		try (Connection conn = dataSource.getConnection()) {
  			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
  		}
  		jdbc = JdbcClient.create(dataSource);
  		jdbc.sql("""
  				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
  				         brand_post_comment, author_profile, brand_hashtag_post, brand_hashtag_exclusion,
  				         brand_seeded_account
  				         RESTART IDENTITY CASCADE
  				""").update();
  		repository = new BrandReadRepository(jdbc);
  		brandId = jdbc.sql("""
  				INSERT INTO brand_account (username, ig_user_id) VALUES ('weekly_brand', '1') RETURNING id
  				""").query(Long.class).single();
  	}

  	private void seedTagged(String shortCode, OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt) {
  		jdbc.sql("""
  				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at,
  				                               tag_detected_at, direct_registered_at)
  				VALUES (:brandId, :shortCode, 'author_a', :takenAt, :tagDetectedAt, :directRegisteredAt)
  				""")
  				.param("brandId", brandId).param("shortCode", shortCode).param("takenAt", IN_WEEK)
  				.param("tagDetectedAt", tagDetectedAt).param("directRegisteredAt", directRegisteredAt)
  				.update();
  	}

  	private void seedSnapshot(String shortCode, LocalDate capturedOn, String contentType, Long views, Long likes) {
  		jdbc.sql("""
  				INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type, views, likes, comments)
  				VALUES ('author_a', :shortCode, :capturedOn, :contentType, :views, :likes, 7)
  				""")
  				.param("shortCode", shortCode).param("capturedOn", capturedOn)
  				.param("contentType", contentType).param("views", views).param("likes", likes)
  				.update();
  	}

  	private void seedMeta(String shortCode, String adVerdict, OffsetDateTime adJudgedAt) {
  		jdbc.sql("""
  				INSERT INTO brand_post_meta (short_code, username, uploaded_at, caption, ad_verdict, ad_judged_at)
  				VALUES (:shortCode, 'author_a', DATE '2026-08-19', '캡션', :adVerdict, :adJudgedAt)
  				""")
  				.param("shortCode", shortCode).param("adVerdict", adVerdict).param("adJudgedAt", adJudgedAt)
  				.update();
  	}

  	@Test
  	void 태그_발견분은_창_안_tag_detected_at만_돌려준다() {
  		seedTagged("SC_IN", IN_WEEK, null);
  		seedTagged("SC_BEFORE", BEFORE_WEEK, null);
  		seedTagged("SC_AFTER", AFTER_WEEK, null);

  		List<WeeklyPostMetrics> found = repository.findTaggedPostsDiscoveredBetween(
  				List.of(brandId), WEEK.from(), WEEK.toExclusive());

  		assertThat(found).extracting(WeeklyPostMetrics::shortCode).containsExactly("SC_IN");
  	}

  	@Test
  	void direct_등록분은_발견분에서_제외된다() {
  		seedTagged("SC_TAG", IN_WEEK, null);
  		seedTagged("SC_DIRECT", IN_WEEK, IN_WEEK);

  		List<WeeklyPostMetrics> found = repository.findTaggedPostsDiscoveredBetween(
  				List.of(brandId), WEEK.from(), WEEK.toExclusive());

  		assertThat(found).extracting(WeeklyPostMetrics::shortCode).containsExactly("SC_TAG");
  	}

  	@Test
  	void 태그_발견분_지표는_최신_스냅샷_1행이다() {
  		seedTagged("SC_IN", IN_WEEK, null);
  		seedSnapshot("SC_IN", LocalDate.of(2026, 8, 19), "REELS", 100L, 10L);
  		seedSnapshot("SC_IN", LocalDate.of(2026, 8, 21), "REELS", 300L, 30L);

  		List<WeeklyPostMetrics> found = repository.findTaggedPostsDiscoveredBetween(
  				List.of(brandId), WEEK.from(), WEEK.toExclusive());

  		assertThat(found).singleElement().satisfies(post -> {
  			assertThat(post.views()).isEqualTo(300L);
  			assertThat(post.likes()).isEqualTo(30L);
  			assertThat(post.contentType()).isEqualTo("REELS");
  			assertThat(post.authorUsername()).isEqualTo("author_a");
  		});
  	}

  	@Test
  	void 스냅샷이_없는_발견분도_모수에_남는다() {
  		seedTagged("SC_IN", IN_WEEK, null);

  		List<WeeklyPostMetrics> found = repository.findTaggedPostsDiscoveredBetween(
  				List.of(brandId), WEEK.from(), WEEK.toExclusive());

  		assertThat(found).singleElement().satisfies(post -> {
  			assertThat(post.shortCode()).isEqualTo("SC_IN");
  			assertThat(post.views()).isNull();
  			assertThat(post.likes()).isNull();
  		});
  	}

  	@Test
  	void 빈_브랜드_목록은_조회하지_않고_빈_리스트() {
  		assertThat(repository.findTaggedPostsDiscoveredBetween(List.of(), WEEK.from(), WEEK.toExclusive()))
  				.isEmpty();
  		assertThat(repository.findHashtagPostsDiscoveredBetween(List.of(), WEEK.from(), WEEK.toExclusive()))
  				.isEmpty();
  		assertThat(repository.findNotDisclosedJudgedBetween(List.of(), WEEK.from(), WEEK.toExclusive()))
  				.isEmpty();
  	}

  	@Test
  	void 해시태그_발견분은_RELEVANT_창_안_first_seen_at만_돌려준다() {
  		jdbc.sql("""
  				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username, taken_at,
  				                                content_type, likes, comments, verdict, verdict_source, first_seen_at)
  				VALUES (:brandId, 'HS_IN', '#brand', 'author_b', :takenAt, 'FEED', 11, 2, 'RELEVANT', 'RULE', :inWeek),
  				       (:brandId, 'HS_OUT', '#brand', 'author_b', :takenAt, 'FEED', 11, 2, 'RELEVANT', 'RULE', :beforeWeek),
  				       (:brandId, 'HS_SELF', '#brand', 'author_b', :takenAt, 'FEED', 11, 2, 'SELF', 'RULE', :inWeek)
  				""")
  				.param("brandId", brandId).param("takenAt", IN_WEEK)
  				.param("inWeek", IN_WEEK).param("beforeWeek", BEFORE_WEEK)
  				.update();

  		List<WeeklyPostMetrics> found = repository.findHashtagPostsDiscoveredBetween(
  				List.of(brandId), WEEK.from(), WEEK.toExclusive());

  		assertThat(found).singleElement().satisfies(post -> {
  			assertThat(post.shortCode()).isEqualTo("HS_IN");
  			assertThat(post.views()).isNull();          // 해시태그 발견분은 스냅샷이 없다(스펙 §5 보류)
  			assertThat(post.likes()).isEqualTo(11L);
  			assertThat(post.comments()).isEqualTo(2L);
  		});
  	}

  	@Test
  	void 미표기_판정은_NOT_DISCLOSED와_창_안_ad_judged_at만_돌려준다() {
  		seedMeta("SC_ND", "NOT_DISCLOSED", IN_WEEK);
  		seedMeta("SC_OK", "DISCLOSED", IN_WEEK);
  		seedMeta("SC_OLD", "NOT_DISCLOSED", BEFORE_WEEK);
  		seedMeta("SC_UNJUDGED", null, null);

  		List<String> found = repository.findNotDisclosedJudgedBetween(
  				List.of("SC_ND", "SC_OK", "SC_OLD", "SC_UNJUDGED"), WEEK.from(), WEEK.toExclusive());

  		assertThat(found).containsExactly("SC_ND");
  	}
  }
  ```

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.BrandWeeklyReadRepositoryTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: class WeeklyPostMetrics`, `cannot find symbol: method findTaggedPostsDiscoveredBetween(...)`.

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/WeeklyPostMetrics.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  /**
   * 주간 다이제스트가 다루는 게시물 1건의 최소 지표(설계 §3) — 산지가 셋이라 공통 형태로 모은다:
   * 브랜드 태그 발견분(brand_tagged_post + 최신 brand_post_snapshot), 해시태그 발견분
   * (brand_hashtag_post 열거 관측값), 수집 종료분(target + 최신 post_snapshot).
   *
   * <p>{@code views}는 산지가 준 원시값이다 — <b>피드 게시물의 조회수는 항상 NULL</b>이라는 규칙
   * (CLAUDE.md 함정)은 표시·합산 단계에서 {@code contentType}으로 한 번 더 접는다
   * ({@code WeeklyDigestAssembler}). 여기서 접지 않는 이유는 산지별로 접는 규칙이 달라지면
   * 합산이 산지에 따라 갈리기 때문이다.
   */
  public record WeeklyPostMetrics(String shortCode, String authorUsername, String contentType,
  		Long views, Long likes, Long comments) {
  }
  ```

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java`의 `findHashtagPosts` 메서드가 끝나는 302행(`}`) 뒤에 아래 3개 메서드를 삽입한다.
  ```java
  	/**
  	 * 지난주 <b>태그 열거로 새로 발견된</b> 게시물 + 최신 스냅샷 지표(설계 §4 브랜드 새 게시물).
  	 * direct 등록분은 제외한다 — 사용자가 스스로 넣은 게시물은 "발견 소식"이 아니다.
  	 * 같은 게시물이 유저의 브랜드 두 개에 동시에 걸리면 DISTINCT ON이 한 건으로 접는다.
  	 * 스냅샷이 아직 없는 발견분도 모수에 남도록 LEFT JOIN이며, 그때 지표는 전부 null이다.
  	 */
  	public List<WeeklyPostMetrics> findTaggedPostsDiscoveredBetween(Collection<Long> brandIds,
  			OffsetDateTime from, OffsetDateTime toExclusive) {
  		if (brandIds.isEmpty()) {
  			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
  		}
  		return jdbc.sql("""
  				SELECT DISTINCT ON (t.short_code) t.short_code, t.author_username,
  				       s.content_type, s.views, s.likes, s.comments
  				FROM brand_tagged_post t
  				LEFT JOIN brand_post_snapshot s ON s.short_code = t.short_code
  				WHERE t.brand_id IN (:brandIds)
  				  AND t.direct_registered_at IS NULL
  				  AND t.tag_detected_at >= :from AND t.tag_detected_at < :toExclusive
  				ORDER BY t.short_code, s.captured_on DESC NULLS LAST
  				""")
  				.param("brandIds", brandIds)
  				.param("from", from)
  				.param("toExclusive", toExclusive)
  				.query(WeeklyPostMetrics.class)
  				.list();
  	}

  	/**
  	 * 지난주 <b>해시태그 스윕이 새로 발견한</b> 관련 게시물(설계 §4). 이 표면은 스냅샷·보강이
  	 * 없어(스펙 2026-08-11 §5 보류) 지표가 열거 관측값 그대로고 조회수 자체가 없다 — views는
  	 * 항상 null로 내려 합산 규칙(릴스만 조회수)과 자연히 정합한다.
  	 */
  	public List<WeeklyPostMetrics> findHashtagPostsDiscoveredBetween(Collection<Long> brandIds,
  			OffsetDateTime from, OffsetDateTime toExclusive) {
  		if (brandIds.isEmpty()) {
  			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
  		}
  		return jdbc.sql("""
  				SELECT DISTINCT ON (short_code) short_code, author_username, content_type,
  				       NULL::bigint AS views, likes, comments
  				FROM brand_hashtag_post
  				WHERE brand_id IN (:brandIds) AND verdict = 'RELEVANT'
  				  AND first_seen_at >= :from AND first_seen_at < :toExclusive
  				ORDER BY short_code, first_seen_at DESC
  				""")
  				.param("brandIds", brandIds)
  				.param("from", from)
  				.param("toExclusive", toExclusive)
  				.query(WeeklyPostMetrics.class)
  				.list();
  	}

  	/**
  	 * 후보 shortcode 중 지난주에 <b>광고 미표기</b>로 판정된 것(설계 §4 광고 미표기).
  	 * 후보(= 그 유저가 등록한 게시물)는 app 스키마 원장에서 오고 여기 파라미터로 들어온다 —
  	 * monitoring DB와 app 스키마를 SQL로 조인하지 않는다(시스템 경계, 조합은 was 코드).
  	 * 판정 컬럼의 실제 위치는 brand_post_meta다(V20260817160000).
  	 */
  	public List<String> findNotDisclosedJudgedBetween(Collection<String> shortCodes,
  			OffsetDateTime from, OffsetDateTime toExclusive) {
  		if (shortCodes.isEmpty()) {
  			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
  		}
  		return jdbc.sql("""
  				SELECT short_code FROM brand_post_meta
  				WHERE short_code IN (:shortCodes) AND ad_verdict = 'NOT_DISCLOSED'
  				  AND ad_judged_at >= :from AND ad_judged_at < :toExclusive
  				ORDER BY short_code
  				""")
  				.param("shortCodes", shortCodes)
  				.param("from", from)
  				.param("toExclusive", toExclusive)
  				.query(String.class)
  				.list();
  	}
  ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.BrandWeeklyReadRepositoryTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`, 7개 테스트 통과.

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/BrandLinkRepositoryTest.java`에 아래 전문을 쓴다(파일이 이미 있으면 클래스 안에 `findAllActive` 테스트 2개만 추가한다).
  ```java
  package com.celfit.was.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.celfit.was.IntegrationTest;
  import java.util.List;
  import java.util.UUID;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.jdbc.core.simple.JdbcClient;

  /** 브랜드 연결 전량 조회 — 주간 잡이 "유저별 브랜드 목록"을 한 왕복으로 얻는 경로(설계 §4). */
  class BrandLinkRepositoryTest extends IntegrationTest {

  	@Autowired
  	BrandLinkRepository repository;
  	@Autowired
  	JdbcClient jdbcClient;

  	private long seedUser() {
  		return jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
  				.param("email", "brand-link-" + UUID.randomUUID() + "@test.io")
  				.query(Long.class).single();
  	}

  	@Test
  	void findAllActive는_해제된_연결을_빼고_전_유저를_돌려준다() {
  		long userA = seedUser();
  		long userB = seedUser();
  		long brandA = 900_001L;
  		long brandB = 900_002L;
  		repository.insertLink(userA, brandA, "brand_a", "own", 3);
  		repository.insertLink(userB, brandB, "brand_b", "own", 3);
  		repository.softDeleteLink(userB, brandB);

  		List<BrandLinkRow> active = repository.findAllActive();

  		assertThat(active).extracting(BrandLinkRow::userId).contains(userA).doesNotContain(userB);
  		assertThat(active).allSatisfy(row -> assertThat(row.deletedAt()).isNull());
  	}
  }
  ```

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.BrandLinkRepositoryTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: method findAllActive()`.

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/BrandLinkRepository.java`의 `findAllActiveByUser` 메서드 뒤에 아래를 삽입한다.
  ```java
  	/**
  	 * 전 유저의 활성 연결(2026-08-27 주간 다이제스트) — 주간 잡이 유저별 브랜드 목록을 유저 수만큼
  	 * 왕복하지 않고 한 번에 읽어 메모리에서 그룹핑하기 위한 것이다. 행 수는 활성 연결 수(유저당
  	 * 소수)라 전량이 부담이 아니다.
  	 */
  	public List<BrandLinkRow> findAllActive() {
  		return jdbcClient.sql("""
  				SELECT %s FROM app.brand_monitorings
  				WHERE deleted_at IS NULL
  				ORDER BY user_id, created_at, id
  				""".formatted(SELECT_COLUMNS))
  				.query(BrandLinkRow.class)
  				.list();
  	}
  ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.BrandLinkRepositoryTest" --tests "com.celfit.was.monitoring.BrandWeeklyReadRepositoryTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`.

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add was/src/main/java/com/celfit/was/monitoring/WeeklyPostMetrics.java was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java was/src/main/java/com/celfit/was/monitoring/BrandLinkRepository.java was/src/test/java/com/celfit/was/monitoring/BrandWeeklyReadRepositoryTest.java was/src/test/java/com/celfit/was/monitoring/BrandLinkRepositoryTest.java
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): 주간 다이제스트용 브랜드 발견분·미표기 판정 조회 추가

  태그·해시태그 발견분(창 안, direct 제외)과 등록 게시물 미표기 판정을 monitoring DB에서
  읽는 조회 3종, 그리고 전 유저 활성 브랜드 연결 조회를 추가했다(설계 §4).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 5: 캠페인 이름 문맥 조회

설계 §3의 "모니터링 진행 섹션에 캠페인 이름 문맥 포함". alarm_event의 `target_id`를 app 스키마 추적 행(`app.monitoring_items.target_id`)으로 되짚어 캠페인 이름을 얻는다. 둘 다 app 스키마라 SQL 조인이 허용된다(분석 결과와의 조인이 아니다).

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringItemRepository.java` (`findActiveTargetIds` 뒤에 메서드 1개 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringItemRepositoryTest.java` (기존 클래스에 테스트 3개 추가)

**Steps:**

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/MonitoringItemRepositoryTest.java`의 클래스 마지막 `}` 앞에 아래를 추가한다. 이 클래스는 `@BeforeEach 유저_시드`가 채우는 `userId` 필드와 `repository`·`campaignRepository`·`jdbcClient`를 이미 갖고 있으므로 그대로 쓴다.
  ```java
  	/** 캠페인 이름 문맥 조회 전용 헬퍼 — 아래 테스트 3개에서만 쓴다. */
  	private long 이름있는_캠페인(long ownerId, String name) {
  		return campaignRepository.insert(ownerId, name, null, null, null, null, null, null).id();
  	}

  	private void 추적행_시드(long ownerId, Long campaignId, long targetId, String inputValue) {
  		long itemId = repository.insertPending(ownerId, "url", UUID.randomUUID(), campaignId, inputValue,
  				"https://instagram.com/p/" + inputValue, null, 30, LocalDate.of(2026, 8, 19));
  		repository.confirmTarget(itemId, targetId);
  	}

  	@Test
  	void 캠페인_이름은_이름순_중복_제거로_돌아온다() {
  		long summer = 이름있는_캠페인(userId, "여름 캠페인");
  		long winter = 이름있는_캠페인(userId, "겨울 캠페인");
  		추적행_시드(userId, summer, 8001L, "camp01");
  		추적행_시드(userId, summer, 8002L, "camp02");
  		추적행_시드(userId, winter, 8003L, "camp03");

  		assertThat(repository.findCampaignNamesByTargetIds(userId, List.of(8001L, 8002L, 8003L)))
  				.containsExactly("겨울 캠페인", "여름 캠페인");
  	}

  	@Test
  	void 캠페인이_없는_추적_행은_이름을_만들지_않는다() {
  		추적행_시드(userId, null, 8010L, "camp10");

  		assertThat(repository.findCampaignNamesByTargetIds(userId, List.of(8010L))).isEmpty();
  	}

  	@Test
  	void 빈_target_목록은_조회하지_않고_빈_리스트() {
  		assertThat(repository.findCampaignNamesByTargetIds(userId, List.of())).isEmpty();
  	}

  	@Test
  	void 남의_추적_행은_캠페인_이름을_노출하지_않는다() {
  		long other = jdbcClient
  				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
  				.param("email", "mon-item-other-" + UUID.randomUUID() + "@test.io")
  				.query(Long.class).single();
  		long campaignId = 이름있는_캠페인(userId, "남의 캠페인");
  		추적행_시드(userId, campaignId, 8020L, "camp20");

  		assertThat(repository.findCampaignNamesByTargetIds(other, List.of(8020L))).isEmpty();
  	}
  ```
  `insertPending`의 시그니처는 이 파일 첫 테스트(`pending_선저장과_target_확정_왕복`)가 쓰는 것과 같다(userId, mode, registrationKey, campaignId, inputValue, sourceUrl, keywords, trackingDays, registeredOn).

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringItemRepositoryTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: method findCampaignNamesByTargetIds(long,List<Long>)`.

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/MonitoringItemRepository.java`의 `findActiveTargetIds` 뒤에 아래를 삽입한다.
  ```java
  	/**
  	 * monitoring target id 묶음이 속한 캠페인 이름(2026-08-27 주간 다이제스트 §3 "캠페인 이름 문맥").
  	 * 유저 스코프를 WHERE에 박아 남의 추적 행에 붙은 캠페인 이름이 새지 않게 한다 — target id는
  	 * monitoring 전역 키라 유저 스코프 없이 조회하면 남의 캠페인 이름이 문안에 섞인다.
  	 * 캠페인 미배정 행(campaign_id NULL)은 조인에서 자연히 빠진다.
  	 */
  	public List<String> findCampaignNamesByTargetIds(long userId, Collection<Long> targetIds) {
  		if (targetIds.isEmpty()) {
  			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
  		}
  		return jdbcClient.sql("""
  				SELECT DISTINCT c.name
  				FROM app.monitoring_items i
  				JOIN app.monitoring_campaigns c ON c.id = i.campaign_id
  				WHERE i.user_id = :userId AND i.target_id IN (:targetIds)
  				ORDER BY c.name
  				""")
  				.param("userId", userId)
  				.param("targetIds", targetIds)
  				.query(String.class)
  				.list();
  	}
  ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.MonitoringItemRepositoryTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`(새 테스트 4개 포함 전부 통과).

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add was/src/main/java/com/celfit/was/monitoring/MonitoringItemRepository.java was/src/test/java/com/celfit/was/monitoring/MonitoringItemRepositoryTest.java
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): target id로 캠페인 이름을 되짚는 조회 추가

  주간 다이제스트 모니터링 진행 섹션의 캠페인 이름 문맥용. 유저 스코프를 WHERE에 박아
  남의 캠페인 이름이 문안에 새지 않게 했다(설계 §3).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---
## Task 6: `DigestItem` 모델과 `WeeklyDigestAssembler`

설계 §3(섹션 3개 + 하이라이트, 섹션별 합산 한 줄, 피드 조회수 NULL 규칙)의 **전부**를 순수 로직으로 구현한다. 컨테이너 없는 단위 테스트라 반복이 싸다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/DigestItem.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestInput.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestAssembler.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestAssemblerTest.java`

**Steps:**

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestAssemblerTest.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;

  import java.util.List;
  import java.util.Map;
  import org.junit.jupiter.api.Test;

  /** 주간 다이제스트 항목 조립(설계 §3) — 섹션 순서·합산 지표·조회수 NULL 규칙·하이라이트. */
  class WeeklyDigestAssemblerTest {

  	private final WeeklyDigestAssembler assembler = new WeeklyDigestAssembler();

  	private static WeeklyPostMetrics reels(String shortCode, String author, Long views, Long likes, Long comments) {
  		return new WeeklyPostMetrics(shortCode, author, "REELS", views, likes, comments);
  	}

  	private static WeeklyPostMetrics feed(String shortCode, String author, Long views, Long likes, Long comments) {
  		return new WeeklyPostMetrics(shortCode, author, "FEED", views, likes, comments);
  	}

  	private static WeeklyDigestInput input(Map<String, Long> eventCounts, List<WeeklyPostMetrics> brandNewPosts,
  			List<WeeklyPostMetrics> endedPosts, List<String> adShortCodes, List<String> campaignNames) {
  		return new WeeklyDigestInput(eventCounts, brandNewPosts, endedPosts, adShortCodes, campaignNames);
  	}

  	@Test
  	void 아무것도_없으면_빈_목록이다() {
  		assertThat(assembler.assemble(input(Map.of(), List.of(), List.of(), List.of(), List.of()))).isEmpty();
  	}

  	@Test
  	void 섹션_순서는_확인필요_브랜드_모니터링_하이라이트다() {
  		List<DigestItem> items = assembler.assemble(input(
  				Map.of("collection_started", 1L, "collection_ended", 2L, "metrics_private", 3L, "content_issue", 4L),
  				List.of(reels("B1", "author_b", 100L, 10L, 1L)),
  				List.of(reels("E1", "author_e", 50L, 5L, 1L)),
  				List.of("AD1"),
  				List.of()));

  		assertThat(items).extracting(DigestItem::type).containsExactly(
  				"ad_not_disclosed", "content_issue", "metrics_private",
  				"brand_new_posts",
  				"collection_started", "collection_ended",
  				"top_post");
  		assertThat(items).extracting(DigestItem::category).containsExactly(
  				"action_needed", "action_needed", "action_needed",
  				"brand", "content", "content", "highlight");
  	}

  	@Test
  	void 건수가_0인_항목은_아예_빠진다() {
  		List<DigestItem> items = assembler.assemble(input(
  				Map.of("collection_started", 2L), List.of(), List.of(), List.of(), List.of()));

  		assertThat(items).extracting(DigestItem::type).containsExactly("collection_started");
  		assertThat(items.get(0).count()).isEqualTo(2);
  		assertThat(items.get(0).summary()).isEqualTo("새로 수집을 시작한 콘텐츠가 있어요");
  		assertThat(items.get(0).metrics()).isNull();
  	}

  	@Test
  	void 브랜드_섹션은_합산_지표를_들고_있다() {
  		List<DigestItem> items = assembler.assemble(input(Map.of(),
  				List.of(reels("B1", "a", 100L, 10L, 1L), reels("B2", "b", 200L, 20L, 2L)),
  				List.of(), List.of(), List.of()));

  		DigestItem brand = items.get(0);
  		assertThat(brand.type()).isEqualTo("brand_new_posts");
  		assertThat(brand.count()).isEqualTo(2);
  		assertThat(brand.summary()).isEqualTo("브랜드를 언급한 새 게시물을 찾았어요");
  		assertThat(brand.metrics()).isEqualTo(new DigestItem.Metrics(300L, 30L, 3L));
  	}

  	@Test
  	void 피드_게시물의_조회수는_합산에서_제외된다() {
  		List<DigestItem> items = assembler.assemble(input(Map.of(),
  				List.of(reels("B1", "a", 100L, 10L, 1L), feed("B2", "b", 999L, 20L, 2L)),
  				List.of(), List.of(), List.of()));

  		assertThat(items.get(0).metrics()).isEqualTo(new DigestItem.Metrics(100L, 30L, 3L));
  	}

  	@Test
  	void 그_주_조회수가_전부_없으면_views는_null이다() {
  		List<DigestItem> items = assembler.assemble(input(Map.of(),
  				List.of(feed("B1", "a", null, 10L, 1L), feed("B2", "b", null, 20L, 2L)),
  				List.of(), List.of(), List.of()));

  		assertThat(items.get(0).metrics()).isEqualTo(new DigestItem.Metrics(null, 30L, 3L));
  	}

  	@Test
  	void 수집_종료_항목은_종료분_누적_지표를_들고_있다() {
  		List<DigestItem> items = assembler.assemble(input(
  				Map.of("collection_ended", 2L),
  				List.of(),
  				List.of(reels("E1", "a", 70L, 7L, 1L), reels("E2", "b", 30L, 3L, 1L)),
  				List.of(), List.of()));

  		DigestItem ended = items.get(0);
  		assertThat(ended.type()).isEqualTo("collection_ended");
  		assertThat(ended.metrics()).isEqualTo(new DigestItem.Metrics(100L, 10L, 2L));
  	}

  	@Test
  	void 캠페인_이름이_있으면_모니터링_진행_문안에_붙는다() {
  		List<DigestItem> items = assembler.assemble(input(
  				Map.of("collection_started", 1L), List.of(), List.of(), List.of(),
  				List.of("여름 캠페인", "가을 캠페인")));

  		assertThat(items.get(0).summary())
  				.isEqualTo("새로 수집을 시작한 콘텐츠가 있어요 (여름 캠페인, 가을 캠페인)");
  	}

  	@Test
  	void 캠페인_이름이_셋_이상이면_둘만_적고_나머지는_외_N건이다() {
  		List<DigestItem> items = assembler.assemble(input(
  				Map.of("collection_started", 1L), List.of(), List.of(), List.of(),
  				List.of("여름 캠페인", "가을 캠페인", "겨울 캠페인", "봄 캠페인")));

  		assertThat(items.get(0).summary())
  				.isEqualTo("새로 수집을 시작한 콘텐츠가 있어요 (여름 캠페인, 가을 캠페인 외 2건)");
  	}

  	@Test
  	void 확인필요_섹션_문안과_건수() {
  		List<DigestItem> items = assembler.assemble(input(
  				Map.of("metrics_private", 2L, "content_issue", 1L),
  				List.of(), List.of(), List.of("AD1", "AD2", "AD3"), List.of()));

  		assertThat(items).extracting(DigestItem::summary).containsExactly(
  				"광고 표기가 없는 등록 게시물이 있어요",
  				"게시물을 확인하지 못한 콘텐츠가 있어요",
  				"일부 지표가 비공개로 바뀐 콘텐츠가 있어요");
  		assertThat(items).extracting(DigestItem::count).containsExactly(3, 1, 2);
  	}

  	@Test
  	void 하이라이트는_조회수_최대_게시물이다() {
  		List<DigestItem> items = assembler.assemble(input(Map.of(),
  				List.of(reels("B1", "small", 5_000L, 500L, 5L), reels("B2", "big", 123_456L, 10L, 1L)),
  				List.of(reels("E1", "mid", 60_000L, 100L, 3L)), List.of(), List.of()));

  		DigestItem highlight = items.get(items.size() - 1);
  		assertThat(highlight.category()).isEqualTo("highlight");
  		assertThat(highlight.type()).isEqualTo("top_post");
  		assertThat(highlight.count()).isEqualTo(1);
  		assertThat(highlight.summary()).isEqualTo("@big 게시물 · 조회수 12.3만");
  	}

  	@Test
  	void 만_단위가_딱_떨어지면_소수점을_붙이지_않는다() {
  		List<DigestItem> items = assembler.assemble(input(Map.of(),
  				List.of(reels("B1", "big", 20_000L, 1L, 1L)), List.of(), List.of(), List.of()));

  		assertThat(items.get(items.size() - 1).summary()).isEqualTo("@big 게시물 · 조회수 2만");
  	}

  	@Test
  	void 만_미만_조회수는_천단위_구분으로_적는다() {
  		List<DigestItem> items = assembler.assemble(input(Map.of(),
  				List.of(reels("B1", "big", 9_999L, 1L, 1L)), List.of(), List.of(), List.of()));

  		assertThat(items.get(items.size() - 1).summary()).isEqualTo("@big 게시물 · 조회수 9,999");
  	}

  	@Test
  	void 조회수가_전부_없으면_하이라이트는_좋아요_최대로_고른다() {
  		List<DigestItem> items = assembler.assemble(input(Map.of(),
  				List.of(feed("B1", "few", null, 10L, 1L), feed("B2", "many", null, 88L, 2L)),
  				List.of(), List.of(), List.of()));

  		DigestItem highlight = items.get(items.size() - 1);
  		assertThat(highlight.summary()).isEqualTo("@many 게시물 · 좋아요 88");
  		assertThat(highlight.metrics()).isEqualTo(new DigestItem.Metrics(null, 88L, 2L));
  	}

  	@Test
  	void 지표가_하나도_없으면_하이라이트를_만들지_않는다() {
  		List<DigestItem> items = assembler.assemble(input(Map.of(),
  				List.of(feed("B1", "a", null, null, null)), List.of(), List.of(), List.of()));

  		assertThat(items).extracting(DigestItem::type).containsExactly("brand_new_posts");
  	}

  	@Test
  	void 문안에_엠대시가_없다() {
  		List<DigestItem> items = assembler.assemble(input(
  				Map.of("collection_started", 1L, "collection_ended", 1L, "metrics_private", 1L, "content_issue", 1L),
  				List.of(reels("B1", "a", 100L, 10L, 1L)), List.of(reels("E1", "b", 10L, 1L, 1L)),
  				List.of("AD1"), List.of("여름 캠페인", "가을 캠페인", "겨울 캠페인")));

  		assertThat(items).extracting(DigestItem::summary)
  				.allSatisfy(summary -> assertThat(summary).doesNotContain("—"));
  	}
  }
  ```

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyDigestAssemblerTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: class DigestItem`, `class WeeklyDigestInput`, `class WeeklyDigestAssembler`.

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/DigestItem.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  /**
   * app.monitoring_digests.items[] 원소의 저장 형태(2026-08-27 주간 개편 §3). 기존 4필드
   * (category·type·summary·count)에 섹션 합산 지표 {@code metrics}를 더한 확장이다 - 없는 항목은
   * null로 내려 프론트가 키 부재와 값 없음을 구분하지 않아도 되게 한다(계약 무결성 규칙 #1).
   *
   * <p>응답 DTO({@code DigestResponse.Item})와 형태가 같지만 계층이 다르다: 이 record는 잡이
   * 쓰는 저장 모델이고, 응답 DTO는 v1 계약이다. 같은 jsonb를 양쪽이 각자 직렬화·역직렬화한다.
   */
  public record DigestItem(String category, String type, String summary, int count, Metrics metrics) {

  	/**
  	 * 섹션 합산 지표. views는 <b>릴스만</b> 집계된다 - 피드 게시물의 조회수는 항상 NULL이라는
  	 * 관측 규칙(CLAUDE.md 함정) 때문에, 그 주에 릴스가 하나도 없으면 views 자체가 null이 된다.
   	 */
  	public record Metrics(Long views, Long likes, Long comments) {
  	}
  }
  ```

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestInput.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import java.util.List;
  import java.util.Map;

  /**
   * 주간 다이제스트 1건을 조립하는 데 필요한 유저 1명분 입력 전부(설계 §3). 잡이 여러 산지에서
   * 모아 채우고, {@link WeeklyDigestAssembler}가 이것만 보고 항목을 만든다 - 조립기가 DB를
   * 모르게 갈라 두면 문안·합산·하이라이트 규칙을 컨테이너 없이 검증할 수 있다.
   *
   * @param eventCounts   프론트 어휘(collection_started 등) → 지난주 alarm_event 건수
   * @param brandNewPosts 지난주 새로 발견된 브랜드 게시물(태그 + 해시태그, direct 제외)
   * @param endedPosts    지난주 수집이 끝난 콘텐츠의 최신 지표
   * @param adNotDisclosedShortCodes 지난주 미표기 판정된 등록 게시물(이미 알린 것은 제외된 뒤)
   * @param campaignNames 모니터링 진행 섹션 문안에 붙일 캠페인 이름(이름순, 중복 없음)
   */
  public record WeeklyDigestInput(
  		Map<String, Long> eventCounts,
  		List<WeeklyPostMetrics> brandNewPosts,
  		List<WeeklyPostMetrics> endedPosts,
  		List<String> adNotDisclosedShortCodes,
  		List<String> campaignNames) {
  }
  ```

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestAssembler.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import java.util.ArrayList;
  import java.util.Comparator;
  import java.util.List;
  import java.util.Locale;
  import java.util.Optional;
  import java.util.function.Function;
  import org.springframework.stereotype.Component;

  /**
   * 주간 다이제스트 항목 조립(설계 §3) - 섹션 3개(확인 필요·브랜드 소식·모니터링 진행)와
   * 선택 노출 하이라이트 1건을 만든다. <b>내용이 있는 항목만</b> 남기므로, 결과가 빈 목록이면
   * 그 주는 알림을 만들지 않는다는 판정이 호출부에서 그대로 성립한다.
   *
   * <p>DB를 모르는 순수 컴포넌트다 - 문안·합산·하이라이트 규칙의 회귀는 전부 단위 테스트가 잡는다.
   *
   * <p>지표 표기 규칙 둘(설계 §3): ① 섹션별 <b>합산 한 줄까지만</b> 담고 개별 게시물은 나열하지
   * 않는다(상세는 딥링크). ② 조회수는 릴스만 집계된다 - 피드는 관측 자체가 NULL이라 content_type이
   * REELS가 아닌 행의 views는 있더라도 버린다. 그 주에 릴스가 없으면 views 합계는 null이고,
   * 문안·메일은 그 숫자를 아예 빼고 렌더링한다.
   */
  @Component
  public class WeeklyDigestAssembler {

  	static final String CATEGORY_ACTION = "action_needed";
  	static final String CATEGORY_BRAND = "brand";
  	static final String CATEGORY_CONTENT = "content";
  	static final String CATEGORY_HIGHLIGHT = "highlight";

  	public List<DigestItem> assemble(WeeklyDigestInput input) {
  		List<DigestItem> items = new ArrayList<>();
  		// 1. 확인 필요 - 사용자가 손을 대야 하는 것부터 위에 온다.
  		add(items, CATEGORY_ACTION, "ad_not_disclosed", "광고 표기가 없는 등록 게시물이 있어요",
  				input.adNotDisclosedShortCodes().size(), null);
  		add(items, CATEGORY_ACTION, "content_issue", "게시물을 확인하지 못한 콘텐츠가 있어요",
  				count(input, "content_issue"), null);
  		add(items, CATEGORY_ACTION, "metrics_private", "일부 지표가 비공개로 바뀐 콘텐츠가 있어요",
  				count(input, "metrics_private"), null);
  		// 2. 브랜드 소식
  		add(items, CATEGORY_BRAND, "brand_new_posts", "브랜드를 언급한 새 게시물을 찾았어요",
  				input.brandNewPosts().size(), sum(input.brandNewPosts()));
  		// 3. 모니터링 진행 - 캠페인 이름 문맥은 두 항목에 같은 목록으로 붙인다.
  		add(items, CATEGORY_CONTENT, "collection_started",
  				withCampaigns("새로 수집을 시작한 콘텐츠가 있어요", input.campaignNames()),
  				count(input, "collection_started"), null);
  		add(items, CATEGORY_CONTENT, "collection_ended",
  				withCampaigns("모니터링 기간이 끝난 콘텐츠가 있어요", input.campaignNames()),
  				count(input, "collection_ended"), sum(input.endedPosts()));
  		// 4. 하이라이트(선택 노출)
  		highlight(input).ifPresent(items::add);
  		return List.copyOf(items);
  	}

  	/** 건수가 0이면 항목 자체를 만들지 않는다 - "내용이 있는 섹션만 노출"(설계 §3)의 구현 지점. */
  	private static void add(List<DigestItem> items, String category, String type, String summary,
  			int count, DigestItem.Metrics metrics) {
  		if (count > 0) {
  			items.add(new DigestItem(category, type, summary, count, metrics));
  		}
  	}

  	private static int count(WeeklyDigestInput input, String frontType) {
  		return Math.toIntExact(input.eventCounts().getOrDefault(frontType, 0L));
  	}

  	/** 릴스만 조회수로 인정한다 - 피드는 관측이 항상 NULL이라 값이 들어와도 신뢰하지 않는다. */
  	private static Long viewsOf(WeeklyPostMetrics post) {
  		return "REELS".equalsIgnoreCase(post.contentType()) ? post.views() : null;
  	}

  	private static DigestItem.Metrics sum(List<WeeklyPostMetrics> posts) {
  		if (posts.isEmpty()) {
  			return null;
  		}
  		return new DigestItem.Metrics(sumOrNull(posts, WeeklyDigestAssembler::viewsOf),
  				sumOrNull(posts, WeeklyPostMetrics::likes), sumOrNull(posts, WeeklyPostMetrics::comments));
  	}

  	/** 값이 하나도 없으면 0이 아니라 null - 0은 "실제로 0"이라는 거짓말이 된다. */
  	private static Long sumOrNull(List<WeeklyPostMetrics> posts, Function<WeeklyPostMetrics, Long> extractor) {
  		List<Long> values = posts.stream().map(extractor).filter(java.util.Objects::nonNull).toList();
  		return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).sum();
  	}

  	/** 이번 주 등장 게시물(새 발견 + 수집 종료) 중 최고 지표 1건. 조회수 우선, 없으면 좋아요. */
  	private static Optional<DigestItem> highlight(WeeklyDigestInput input) {
  		List<WeeklyPostMetrics> candidates = new ArrayList<>(input.brandNewPosts());
  		candidates.addAll(input.endedPosts());
  		Optional<WeeklyPostMetrics> byViews = candidates.stream()
  				.filter(post -> viewsOf(post) != null)
  				.max(Comparator.comparingLong(post -> viewsOf(post)));
  		if (byViews.isPresent()) {
  			WeeklyPostMetrics top = byViews.get();
  			return Optional.of(new DigestItem(CATEGORY_HIGHLIGHT, "top_post",
  					"@%s 게시물 · 조회수 %s".formatted(top.authorUsername(), formatCount(viewsOf(top))), 1,
  					new DigestItem.Metrics(viewsOf(top), top.likes(), top.comments())));
  		}
  		return candidates.stream()
  				.filter(post -> post.likes() != null)
  				.max(Comparator.comparingLong(WeeklyPostMetrics::likes))
  				.map(top -> new DigestItem(CATEGORY_HIGHLIGHT, "top_post",
  						"@%s 게시물 · 좋아요 %s".formatted(top.authorUsername(), formatCount(top.likes())), 1,
  						new DigestItem.Metrics(null, top.likes(), top.comments())));
  	}

  	/**
  	 * 만 단위 축약 - 1만 미만은 천단위 구분 그대로, 그 이상은 소수 첫째 자리까지(버림).
  	 * 정수 연산으로 계산한다(double 반올림 오차가 문안에 드러나지 않게).
  	 */
  	static String formatCount(long value) {
  		if (value < 10_000) {
  			return String.format(Locale.KOREA, "%,d", value);
  		}
  		long tenthsOfMan = value / 1_000;
  		long man = tenthsOfMan / 10;
  		long fraction = tenthsOfMan % 10;
  		return fraction == 0 ? man + "만" : man + "." + fraction + "만";
  	}

  	/**
  	 * 캠페인 이름 문맥(설계 §2 "다이제스트 문안에 캠페인 이름 문맥만 반영"). 셋 이상이면 문안이
  	 * 길어져 요약이 아니게 되므로 둘까지만 적고 나머지는 건수로 접는다.
  	 * 엠대시 금지 규칙에 따라 구분은 쉼표와 괄호만 쓴다.
  	 */
  	static String withCampaigns(String base, List<String> names) {
  		if (names.isEmpty()) {
  			return base;
  		}
  		if (names.size() <= 2) {
  			return base + " (" + String.join(", ", names) + ")";
  		}
  		return base + " (" + names.get(0) + ", " + names.get(1) + " 외 " + (names.size() - 2) + "건)";
  	}
  }
  ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyDigestAssemblerTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`, 15개 테스트 통과.

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add was/src/main/java/com/celfit/was/monitoring/DigestItem.java was/src/main/java/com/celfit/was/monitoring/WeeklyDigestInput.java was/src/main/java/com/celfit/was/monitoring/WeeklyDigestAssembler.java was/src/test/java/com/celfit/was/monitoring/WeeklyDigestAssemblerTest.java
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): 주간 다이제스트 항목 조립기 추가

  섹션 3개(확인 필요·브랜드 소식·모니터링 진행)와 하이라이트 1건, 섹션별 합산 지표와
  릴스만 조회수 규칙, 캠페인 이름 문맥을 순수 로직으로 구현했다(설계 §3).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---
## Task 7: API 응답 확장(`metrics`)과 계약 유지 검증

설계 §5는 `GET /v1/notifications`·`POST /v1/notifications/read`의 **계약 유지**를 요구한다(알림 단위가 일→주로 바뀔 뿐). 다만 items[] 원소에 `metrics`가 붙으므로, 응답 DTO가 그 키를 모르면 Jackson이 미지 속성으로 역직렬화에 실패한다 — **잡이 새 형태를 쓰기 전에** 응답 쪽을 먼저 넓힌다.

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/monitoring/DigestResponse.java` (전문 교체)
- Test: `was/src/test/java/com/celfit/was/v1/monitoring/V1NotificationsControllerTest.java` (클래스 마지막 `}` 앞에 테스트 3개 추가)

**Steps:**

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/v1/monitoring/V1NotificationsControllerTest.java`의 클래스 마지막 `}` 앞에 아래 3개 테스트를 추가한다.
  ```java
  	@Test
  	void 주간_항목의_metrics가_응답에_그대로_실린다() throws Exception {
  		String itemsJson = """
  				[{"category":"brand","type":"brand_new_posts","summary":"브랜드를 언급한 새 게시물을 찾았어요",\
  				"count":12,"metrics":{"views":123456,"likes":7890,"comments":123}}]""";
  		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(
  				row(1L, LocalDate.of(2026, 8, 17), OffsetDateTime.parse("2026-08-24T00:00:00Z"), null, itemsJson)));
  		given(repository.countByUser(7L)).willReturn(1L);

  		mockMvc.perform(get("/v1/notifications").with(user(principal())))
  				.andExpect(status().isOk())
  				.andExpect(jsonPath("$.data[0].date").value("2026-08-17"))
  				.andExpect(jsonPath("$.data[0].items[0].category").value("brand"))
  				.andExpect(jsonPath("$.data[0].items[0].type").value("brand_new_posts"))
  				.andExpect(jsonPath("$.data[0].items[0].count").value(12))
  				.andExpect(jsonPath("$.data[0].items[0].metrics.views").value(123456))
  				.andExpect(jsonPath("$.data[0].items[0].metrics.likes").value(7890))
  				.andExpect(jsonPath("$.data[0].items[0].metrics.comments").value(123));
  	}

  	@Test
  	void metrics가_없는_기존_일일_항목도_그대로_읽힌다() throws Exception {
  		// 개편 전에 쌓인 일일 다이제스트 행은 metrics 키가 없다 - 히스토리로 보존되므로(설계 §7)
  		// 응답 조립이 깨지면 안 된다.
  		String itemsJson = """
  				[{"category":"content","type":"collection_started","summary":"새로 수집을 시작한 콘텐츠가 있어요","count":2}]""";
  		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(
  				row(1L, LocalDate.of(2026, 7, 30), OffsetDateTime.parse("2026-07-30T00:00:00Z"), null, itemsJson)));
  		given(repository.countByUser(7L)).willReturn(1L);

  		mockMvc.perform(get("/v1/notifications").with(user(principal())))
  				.andExpect(status().isOk())
  				.andExpect(jsonPath("$.data[0].items[0].count").value(2))
  				.andExpect(jsonPath("$.data[0].items[0].metrics").doesNotExist());
  	}

  	@Test
  	void metrics의_null_지표는_키를_유지한_채_내려간다() throws Exception {
  		// 조회수가 그 주에 전부 NULL이면 views는 null이다 - 키를 지우면 프론트가 0과 구분하지 못한다.
  		String itemsJson = """
  				[{"category":"brand","type":"brand_new_posts","summary":"브랜드를 언급한 새 게시물을 찾았어요",\
  				"count":3,"metrics":{"views":null,"likes":30,"comments":3}}]""";
  		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(
  				row(1L, LocalDate.of(2026, 8, 17), OffsetDateTime.parse("2026-08-24T00:00:00Z"), null, itemsJson)));
  		given(repository.countByUser(7L)).willReturn(1L);

  		mockMvc.perform(get("/v1/notifications").with(user(principal())))
  				.andExpect(status().isOk())
  				.andExpect(jsonPath("$.data[0].items[0].metrics.views").doesNotExist())
  				.andExpect(jsonPath("$.data[0].items[0].metrics").exists())
  				.andExpect(jsonPath("$.data[0].items[0].metrics.likes").value(30));
  	}
  ```
  > `metrics.views`가 null일 때 `jsonPath(...).doesNotExist()`인 이유: Jackson은 키를 내리지만 JsonPath는 null 값을 `doesNotExist`로 판정한다. 키 존재 자체는 바로 위 `metrics").exists()`가 확인한다.

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.v1.monitoring.V1NotificationsControllerTest"
  ```
  기대 출력: `주간_항목의_metrics가_응답에_그대로_실린다` 실패 — items json의 `metrics` 미지 속성으로 역직렬화 예외(`UnrecognizedPropertyException` 계열)가 나거나 `metrics.views` JsonPath 미존재.

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/v1/monitoring/DigestResponse.java`를 아래 전문으로 교체한다.
  ```java
  package com.celfit.was.v1.monitoring;

  import io.swagger.v3.oas.annotations.media.Schema;
  import java.util.List;

  /**
   * 알림 다이제스트 응답(스펙 6.32, 2026-08-27 주간 개편 §3·§5). 순수 DTO - jsonb 파싱·조립은
   * {@link DigestAssembler} 몫이다(리포 관용구: DTO record는 순수, jsonb 파싱은 @Component
   * Assembler가 소유).
   *
   * <p>주간 개편으로 <b>알림 단위가 하루에서 한 주로</b> 바뀌었다. {@code date}는 이제 주 시작일
   * (월요일)이고 목록·읽음 구조는 그대로다(설계 §5 "계약 유지"). 개편 전에 쌓인 일일 행도 그대로
   * 조회되므로 items[] 원소는 {@code metrics} 없이도 파싱돼야 한다.
   *
   * <p>readAt은 계약 무결성 규칙 #1(1.8)이 짚은 그 사례다 - 키를 생략하면 프론트가 전 알림을
   * 읽음으로 오판해 안읽음 배지가 영구히 0이 된다. record 기본 동작(NON_NULL 미적용)으로 키를
   * 항상 유지한다.
   */
  public record DigestResponse(String id, String date, String createdAt, String readAt, List<Item> items) {

  	public record Item(
  			// 섹션 구분(설계 §3). 개편 전 일일 행은 전부 "content"였다 - 그 값도 계속 유효하다.
  			@Schema(allowableValues = {"action_needed", "brand", "content", "highlight"})
  			String category,
  			// 항목 종류. 정본은 WeeklyDigestAssembler - 컴파일 상수 참조가 불가해 문자열로 표기한다.
  			@Schema(allowableValues = {"ad_not_disclosed", "content_issue", "metrics_private",
  					"brand_new_posts", "collection_started", "collection_ended", "top_post"})
  			String type,
  			String summary, int count,
  			// 섹션 합산 지표. 지표가 붙지 않는 항목은 null이다(개편 전 일일 행도 전부 null).
  			Metrics metrics) {

  		/**
  		 * 합산 지표. views는 <b>릴스만</b> 집계된다 - 피드 게시물의 조회수는 항상 NULL이라는 관측
  		 * 규칙 때문에, 그 주에 릴스가 없으면 views 자체가 null이다(설계 §3).
  		 */
  		public record Metrics(Long views, Long likes, Long comments) {
  		}
  	}
  }
  ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.v1.monitoring.V1NotificationsControllerTest"
  ```
  기대 출력: `BUILD SUCCESSFUL` — 기존 계약 테스트(목록 30건 상한·meta.total·readAt 명시적 null·읽음 400 분기)와 새 3개가 함께 통과.

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add was/src/main/java/com/celfit/was/v1/monitoring/DigestResponse.java was/src/test/java/com/celfit/was/v1/monitoring/V1NotificationsControllerTest.java
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): 알림 응답 items에 섹션 합산 지표 metrics 추가

  목록·읽음 계약은 그대로 두고 items[] 원소만 넓혔다. metrics 없는 기존 일일 행도
  그대로 파싱된다(설계 §3·§5).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 8: `WeeklyDigestJob`(일일 `DigestJob` 대체)

설계 §2·§4·§8의 본체. 일일 잡을 지우고 주간 잡을 세운다. 킬 스위치 게이트·미표기 1회 가드·(user, 주 시작일) 멱등 upsert·이벤트 0건 미생성이 전부 여기서 만난다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestJob.java`
- Delete: `was/src/main/java/com/celfit/was/monitoring/DigestJob.java`
- Delete: `was/src/test/java/com/celfit/was/monitoring/DigestJobTest.java`
- Create: `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestJobTest.java`
- Modify: `was/src/main/resources/application.yml` (99행 `monitoring:` 블록에 `digest:` 하위 추가)

**Steps:**

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestJobTest.java`에 아래 전문을 쓴다(`DigestJobTest`의 관용구를 따르되 브랜드 픽스처도 함께 올린다).
  ```java
  package com.celfit.was.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.celfit.was.IntegrationTest;
  import java.sql.Connection;
  import java.time.Clock;
  import java.time.Instant;
  import java.time.LocalDate;
  import java.time.OffsetDateTime;
  import java.time.ZoneOffset;
  import java.util.List;
  import java.util.Map;
  import java.util.UUID;
  import javax.sql.DataSource;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.core.io.ClassPathResource;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.jdbc.datasource.init.ScriptUtils;
  import tools.jackson.databind.ObjectMapper;

  /**
   * WeeklyDigestJob — 지난주(월~일 KST) 이벤트·발견분·미표기 판정을 모아 (user, 주 시작일)로
   * 멱등 upsert하는 주간 크론(설계 §2·§4·§8). MonitoringReadRepositoryTest·BrandReadRepositoryTest와
   * 같은 방식으로 monitoring 픽스처 2개를 같은 컨테이너의 public 스키마에 올려 monitoring DB를
   * 흉내 낸다. 잡은 Spring 조건부 배선과 무관하게 직접 new 한다.
   */
  class WeeklyDigestJobTest extends IntegrationTest {

  	/** 2026-08-24(월) 09:30 KST — 이 시각의 "지난주"는 2026-08-17(월) ~ 08-23(일)이다. */
  	private static final Instant NOW = OffsetDateTime.of(2026, 8, 24, 9, 30, 0, 0, ZoneOffset.ofHours(9))
  			.toInstant();
  	private static final LocalDate WEEK_START = LocalDate.of(2026, 8, 17);
  	private static final OffsetDateTime IN_WEEK =
  			OffsetDateTime.of(2026, 8, 19, 12, 0, 0, 0, ZoneOffset.ofHours(9));
  	private static final OffsetDateTime BEFORE_WEEK =
  			OffsetDateTime.of(2026, 8, 16, 12, 0, 0, 0, ZoneOffset.ofHours(9));

  	@Autowired
  	DataSource dataSource;
  	@Autowired
  	DigestRepository digestRepository;
  	@Autowired
  	BrandLinkRepository brandLinkRepository;
  	@Autowired
  	BrandDirectPostRepository brandDirectPostRepository;
  	@Autowired
  	MonitoringItemRepository monitoringItemRepository;
  	@Autowired
  	AdDisclosureNoticeRepository adDisclosureNoticeRepository;
  	@Autowired
  	JdbcClient jdbcClient;

  	JdbcClient monitoringJdbc;
  	MonitoringReadRepository monitoringReadRepository;
  	BrandReadRepository brandReadRepository;
  	WeeklyDigestJob job;

  	@BeforeEach
  	void setUp() throws Exception {
  		try (Connection conn = dataSource.getConnection()) {
  			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
  			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
  		}
  		monitoringJdbc = JdbcClient.create(dataSource);
  		monitoringJdbc.sql("TRUNCATE alarm_event RESTART IDENTITY").update();
  		monitoringJdbc.sql("""
  				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
  				         brand_hashtag_post RESTART IDENTITY CASCADE
  				""").update();
  		monitoringReadRepository = new MonitoringReadRepository(monitoringJdbc);
  		brandReadRepository = new BrandReadRepository(monitoringJdbc);
  		job = newJob(true);
  	}

  	private WeeklyDigestJob newJob(boolean exposeAdDisclosure) {
  		return new WeeklyDigestJob(monitoringReadRepository, brandReadRepository, brandLinkRepository,
  				brandDirectPostRepository, monitoringItemRepository, adDisclosureNoticeRepository,
  				digestRepository, new WeeklyDigestAssembler(), new ObjectMapper(),
  				Clock.fixed(NOW, ZoneOffset.UTC), exposeAdDisclosure);
  	}

  	private long seedUser() {
  		return jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
  				.param("email", "weekly-job-" + UUID.randomUUID() + "@test.io")
  				.query(Long.class).single();
  	}

  	private void seedEvent(long targetId, long userId, String eventType, OffsetDateTime occurredAt) {
  		monitoringJdbc.sql("""
  				INSERT INTO alarm_event (target_id, user_id, event_type, payload, occurred_at, dispatch_after)
  				VALUES (:targetId, :userId, :eventType, '{}'::jsonb, :occurredAt, :occurredAt)
  				""")
  				.param("targetId", targetId).param("userId", userId)
  				.param("eventType", eventType).param("occurredAt", occurredAt)
  				.update();
  	}

  	private long seedBrand(long userId, String username) {
  		long brandId = monitoringJdbc.sql("""
  				INSERT INTO brand_account (username, ig_user_id) VALUES (:username, '1') RETURNING id
  				""").param("username", username).query(Long.class).single();
  		brandLinkRepository.insertLink(userId, brandId, username, "own", 3);
  		return brandId;
  	}

  	private void seedTagged(long brandId, String shortCode, OffsetDateTime tagDetectedAt) {
  		monitoringJdbc.sql("""
  				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at, tag_detected_at)
  				VALUES (:brandId, :shortCode, 'author_a', :takenAt, :tagDetectedAt)
  				""")
  				.param("brandId", brandId).param("shortCode", shortCode)
  				.param("takenAt", IN_WEEK).param("tagDetectedAt", tagDetectedAt)
  				.update();
  	}

  	private void seedMeta(String shortCode, String adVerdict, OffsetDateTime adJudgedAt) {
  		monitoringJdbc.sql("""
  				INSERT INTO brand_post_meta (short_code, username, uploaded_at, caption, ad_verdict, ad_judged_at)
  				VALUES (:shortCode, 'author_a', DATE '2026-08-19', '캡션', :adVerdict, :adJudgedAt)
  				""")
  				.param("shortCode", shortCode).param("adVerdict", adVerdict).param("adJudgedAt", adJudgedAt)
  				.update();
  	}

  	@SuppressWarnings("unchecked")
  	private List<Map<String, Object>> items(long userId) {
  		DigestRow row = digestRepository.findRecentByUser(userId, 30).stream()
  				.filter(candidate -> candidate.digestDate().equals(WEEK_START))
  				.findFirst()
  				.orElseThrow(() -> new AssertionError("주간 다이제스트 없음 — user " + userId));
  		return new ObjectMapper().readValue(row.itemsJson(), List.class);
  	}

  	@Test
  	void 지난주_이벤트가_주_시작일_행_한_건으로_모인다() {
  		long userId = seedUser();
  		seedEvent(1, userId, "COLLECTION_STARTED", IN_WEEK);
  		seedEvent(2, userId, "COLLECTION_STARTED", IN_WEEK);
  		seedEvent(3, userId, "CONTENT_UNAVAILABLE", IN_WEEK);

  		job.run();

  		assertThat(digestRepository.countByUser(userId)).isEqualTo(1);
  		assertThat(items(userId)).extracting(item -> item.get("type"))
  				.containsExactly("content_issue", "collection_started");
  		assertThat(items(userId).get(1)).containsEntry("count", 2);
  	}

  	@Test
  	void 창_밖_이벤트는_집계되지_않는다() {
  		long userId = seedUser();
  		seedEvent(1, userId, "COLLECTION_STARTED", BEFORE_WEEK);

  		job.run();

  		assertThat(digestRepository.countByUser(userId)).isZero();
  	}

  	@Test
  	void 이벤트도_소식도_없으면_다이제스트를_만들지_않는다() {
  		long userId = seedUser();
  		seedBrand(userId, "empty_brand");

  		job.run();

  		assertThat(digestRepository.countByUser(userId)).isZero();
  	}

  	@Test
  	void 브랜드_발견분이_브랜드_소식으로_잡힌다() {
  		long userId = seedUser();
  		long brandId = seedBrand(userId, "found_brand");
  		seedTagged(brandId, "SC_IN", IN_WEEK);
  		seedTagged(brandId, "SC_OUT", BEFORE_WEEK);

  		job.run();

  		assertThat(items(userId)).singleElement()
  				.containsEntry("type", "brand_new_posts")
  				.containsEntry("count", 1);
  	}

  	@Test
  	void 미표기_판정은_등록_게시물만_잡히고_이력에_남는다() {
  		long userId = seedUser();
  		long brandId = seedBrand(userId, "ad_brand");
  		seedTagged(brandId, "SC_MINE", BEFORE_WEEK);      // 발견은 지난주 밖 - 브랜드 소식에는 안 잡힌다
  		seedTagged(brandId, "SC_OTHERS", BEFORE_WEEK);
  		seedMeta("SC_MINE", "NOT_DISCLOSED", IN_WEEK);
  		seedMeta("SC_OTHERS", "NOT_DISCLOSED", IN_WEEK);
  		brandDirectPostRepository.upsertDirect(userId, brandId, "SC_MINE");   // 등록 원장에는 SC_MINE만

  		job.run();

  		assertThat(items(userId)).singleElement()
  				.containsEntry("type", "ad_not_disclosed")
  				.containsEntry("count", 1);
  		assertThat(adDisclosureNoticeRepository.findNotifiedInOtherWeek(userId, List.of("SC_MINE"),
  				WEEK_START.plusWeeks(1))).containsExactly("SC_MINE");
  	}

  	@Test
  	void 이미_알린_미표기_게시물은_다음_주에_다시_알리지_않는다() {
  		long userId = seedUser();
  		long brandId = seedBrand(userId, "reset_brand");
  		seedTagged(brandId, "SC_MINE", BEFORE_WEEK);
  		seedMeta("SC_MINE", "NOT_DISCLOSED", IN_WEEK);
  		brandDirectPostRepository.upsertDirect(userId, brandId, "SC_MINE");
  		adDisclosureNoticeRepository.markNotified(userId, List.of("SC_MINE"), WEEK_START.minusWeeks(1));

  		job.run();

  		assertThat(digestRepository.countByUser(userId)).isZero();
  	}

  	@Test
  	void 킬_스위치가_꺼져_있으면_미표기_섹션을_만들지_않는다() {
  		long userId = seedUser();
  		long brandId = seedBrand(userId, "gated_brand");
  		seedTagged(brandId, "SC_MINE", BEFORE_WEEK);
  		seedMeta("SC_MINE", "NOT_DISCLOSED", IN_WEEK);
  		brandDirectPostRepository.upsertDirect(userId, brandId, "SC_MINE");

  		newJob(false).run();

  		assertThat(digestRepository.countByUser(userId)).isZero();
  	}

  	@Test
  	void 재실행해도_행이_늘지_않고_items만_갱신되며_읽음이_보존된다() {
  		long userId = seedUser();
  		seedEvent(1, userId, "COLLECTION_STARTED", IN_WEEK);
  		job.run();
  		long digestId = digestRepository.findRecentByUser(userId, 1).get(0).id();
  		digestRepository.markRead(userId, List.of(digestId));
  		DigestRow before = digestRepository.findRecentByUser(userId, 1).get(0);

  		seedEvent(2, userId, "METRICS_HIDDEN", IN_WEEK);
  		job.catchUp();

  		DigestRow after = digestRepository.findRecentByUser(userId, 1).get(0);
  		assertThat(digestRepository.countByUser(userId)).isEqualTo(1);
  		assertThat(after.id()).isEqualTo(digestId);
  		assertThat(after.readAt()).isEqualTo(before.readAt());
  		assertThat(after.createdAt()).isEqualTo(before.createdAt());
  		assertThat(items(userId)).extracting(item -> item.get("type"))
  				.containsExactly("metrics_private", "collection_started");
  	}

  	@Test
  	void 유저_2명이_각자_따로_집계된다() {
  		long userA = seedUser();
  		long userB = seedUser();
  		seedEvent(1, userA, "COLLECTION_STARTED", IN_WEEK);
  		seedEvent(2, userB, "COLLECTION_ENDED", IN_WEEK);

  		job.run();

  		assertThat(items(userA)).extracting(item -> item.get("type")).containsExactly("collection_started");
  		assertThat(items(userB)).extracting(item -> item.get("type")).containsExactly("collection_ended");
  	}

  	@Test
  	void 명시적_창을_주면_그_창만_집계한다() {
  		long userId = seedUser();
  		seedEvent(1, userId, "COLLECTION_STARTED", BEFORE_WEEK);   // 2026-08-16(일) = 그 전 주

  		job.runFor(new WeekWindow(WEEK_START.minusWeeks(1)));

  		assertThat(digestRepository.findRecentByUser(userId, 30))
  				.extracting(DigestRow::digestDate)
  				.containsExactly(WEEK_START.minusWeeks(1));
  	}
  }
  ```

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyDigestJobTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: class WeeklyDigestJob`.

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestJob.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import com.celfit.was.v1.common.KstTimestamps;
  import java.time.Clock;
  import java.time.Instant;
  import java.util.LinkedHashMap;
  import java.util.LinkedHashSet;
  import java.util.List;
  import java.util.Map;
  import java.util.Set;
  import java.util.stream.Collectors;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
  import org.springframework.scheduling.annotation.Scheduled;
  import org.springframework.stereotype.Component;
  import tools.jackson.databind.ObjectMapper;

  /**
   * 주간 다이제스트 생성 크론(2026-08-27 주간 개편 §2·§4·§8) — 매주 월요일 09:00 KST에 <b>지난주
   * (월~일)</b>를 집계해 app.monitoring_digests에 (user_id, 주 시작일) 멱등 upsert한다.
   * 일일 다이제스트(구 DigestJob)를 대체한다.
   *
   * <h2>이벤트 원장 없이 "주간 조회"</h2>
   * 주간 리듬에서는 실시간 이벤트 적재가 대부분 불필요하다(설계 §4). 이 잡이 정본 테이블을 기간
   * 조회한다: 브랜드 발견분은 brand_tagged_post·brand_hashtag_post, 미표기 판정은 brand_post_meta,
   * 콘텐츠 알림 4종만 기존 alarm_event 원장이다(지표 숨김 같은 상태 전이는 소급 조회가 불가능해
   * 적재를 유지하고 소비 리듬만 주간으로 옮겼다).
   *
   * <h2>명시적 창 — 벽시계 유도를 쓰지 않는다</h2>
   * {@link #runFor(WeekWindow)}가 본체이고 스케줄 진입점 둘은 같은 창을 계산해 넘긴다. 창 계산이
   * "그 주 어느 요일에 불러도 직전 주"라 09:00 정시 실행과 따라잡기 틱이 전부 같은 구간을 다시
   * 집계한다 — 다이제스트 자정 경계 유실(트랙 GG)과 같은 계열의 사고를 구조적으로 막는다(설계 §8).
   *
   * <h2>멱등 — 워터마크가 없다</h2>
   * {@link DigestRepository#upsert}가 (user_id, digest_date) 유니크로 재실행을 안전하게 만든다.
   * 같은 주를 몇 번 다시 돌려도 행이 늘지 않고 items만 최신 집계로 덮인다(created_at·read_at은
   * SET 절에 없어 보존). 유일한 부작용 기록은 미표기 알림 이력인데, 그것도 "이번 주가 아닌 주에
   * 알린 것만 제외"라 같은 주 재실행이 자기 기록에 걸리지 않는다.
   *
   * <h2>이벤트 0건이면 미생성</h2>
   * 조립 결과가 빈 목록이면 upsert 자체를 하지 않는다(설계 §2 "이벤트 0건이면 그 주는 알림 미생성").
   * 브랜드 연결만 있고 소식이 없는 유저에게 빈 알림이 매주 가지 않는다.
   */
  @Component
  @ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
  public class WeeklyDigestJob {

  	private static final Logger log = LoggerFactory.getLogger(WeeklyDigestJob.class);
  	private static final String COLLECTION_STARTED = "COLLECTION_STARTED";
  	private static final String COLLECTION_ENDED = "COLLECTION_ENDED";

  	private final MonitoringReadRepository monitoringRead;
  	private final BrandReadRepository brandRead;
  	private final BrandLinkRepository brandLinks;
  	private final BrandDirectPostRepository brandDirectPosts;
  	private final MonitoringItemRepository monitoringItems;
  	private final AdDisclosureNoticeRepository adNotices;
  	private final DigestRepository digests;
  	private final WeeklyDigestAssembler assembler;
  	private final ObjectMapper objectMapper;
  	private final Clock clock;
  	/** 광고 표기 판정 노출 킬 스위치 — FE와 같은 키를 본다(설계 §8 "킬 스위치 정합"). */
  	private final boolean exposeAdDisclosure;

  	public WeeklyDigestJob(MonitoringReadRepository monitoringRead, BrandReadRepository brandRead,
  			BrandLinkRepository brandLinks, BrandDirectPostRepository brandDirectPosts,
  			MonitoringItemRepository monitoringItems, AdDisclosureNoticeRepository adNotices,
  			DigestRepository digests, WeeklyDigestAssembler assembler, ObjectMapper objectMapper, Clock clock,
  			@Value("${monitoring.brand.ad-disclosure.expose:false}") boolean exposeAdDisclosure) {
  		this.monitoringRead = monitoringRead;
  		this.brandRead = brandRead;
  		this.brandLinks = brandLinks;
  		this.brandDirectPosts = brandDirectPosts;
  		this.monitoringItems = monitoringItems;
  		this.adNotices = adNotices;
  		this.digests = digests;
  		this.assembler = assembler;
  		this.objectMapper = objectMapper;
  		this.clock = clock;
  		this.exposeAdDisclosure = exposeAdDisclosure;
  	}

  	/** 월요일 09:00 KST 정시 실행. */
  	@Scheduled(cron = "${monitoring.digest.weekly-cron:0 0 9 * * MON}", zone = "Asia/Seoul")
  	public void run() {
  		runFor(currentWindow());
  	}

  	/**
  	 * 따라잡기 틱(월요일 09:10~23:50, 매 10분) — {@link #run()}과 완전히 같은 재계산을 반복한다.
  	 * 09:00 실행 이후 도착한 이벤트(늦게 끝난 스윕 등)를 같은 주 행으로 흡수하고, 메일 발송
  	 * 실패분을 재시도할 창이기도 하다(Task 10에서 발송이 붙는다).
  	 */
  	@Scheduled(cron = "${monitoring.digest.weekly-catchup-cron:0 10,20,30,40,50 9-23 * * MON}", zone = "Asia/Seoul")
  	public void catchUp() {
  		runFor(currentWindow());
  	}

  	private WeekWindow currentWindow() {
  		return WeekWindow.previousWeekOf(Instant.now(clock).atZone(KstTimestamps.KST).toLocalDate());
  	}

  	/**
  	 * 창을 명시적으로 받는 본체. 대상 유저는 "지난주 알람 이벤트가 있는 유저" ∪ "활성 브랜드 연결이
  	 * 있는 유저"다 — 전자만 보면 브랜드 소식·미표기만 있는 유저가 통째로 빠진다.
  	 */
  	public void runFor(WeekWindow window) {
  		Map<Long, List<AlarmEventRow>> eventsByUser = monitoringRead
  				.findAlarmEventsBetween(window.startDate(), window.endDateInclusive()).stream()
  				.collect(Collectors.groupingBy(AlarmEventRow::userId, LinkedHashMap::new, Collectors.toList()));
  		Map<Long, List<Long>> brandIdsByUser = brandLinks.findAllActive().stream()
  				.collect(Collectors.groupingBy(BrandLinkRow::userId, LinkedHashMap::new,
  						Collectors.mapping(BrandLinkRow::brandId, Collectors.toList())));
  		Set<Long> userIds = new LinkedHashSet<>(eventsByUser.keySet());
  		userIds.addAll(brandIdsByUser.keySet());

  		int upserted = 0;
  		for (long userId : userIds) {
  			try {
  				if (upsertWeekly(userId, window, eventsByUser.getOrDefault(userId, List.of()),
  						brandIdsByUser.getOrDefault(userId, List.of()))) {
  					upserted++;
  				}
  			} catch (RuntimeException e) {
  				// 한 유저의 실패가 나머지를 막으면 장애 하나가 주간 알림 전체를 멈춘다(AlarmRecorder와 동일 정책).
  				log.error("주간 다이제스트 생성 실패(격리) — user {}, week {}", userId, window.startDate(), e);
  			}
  		}
  		log.info("주간 다이제스트 생성 완료 — 창 {}~{} KST, 대상 유저 {}명, upsert {}건",
  				window.startDate(), window.endDateInclusive(), userIds.size(), upserted);
  	}

  	/** @return 다이제스트를 만들었으면 true, 내용이 없어 건너뛰었으면 false */
  	private boolean upsertWeekly(long userId, WeekWindow window, List<AlarmEventRow> userEvents,
  			List<Long> brandIds) {
  		Map<String, Long> eventCounts = userEvents.stream().collect(Collectors.groupingBy(
  				event -> MonitoringEventTypes.toFront(event.eventType()), Collectors.counting()));
  		List<String> adShortCodes = adNotDisclosed(userId, window);
  		List<DigestItem> items = assembler.assemble(new WeeklyDigestInput(eventCounts,
  				brandNewPosts(brandIds, window), endedPosts(userEvents), adShortCodes,
  				campaignNames(userId, userEvents)));
  		if (items.isEmpty()) {
  			return false;
  		}
  		// 이력은 실제로 알림에 실릴 때만 남긴다 — 조립 결과가 비면(도달 불가하지만 방어) 다음 주에 다시 기회를 준다.
  		adNotices.markNotified(userId, adShortCodes, window.startDate());
  		digests.upsert(userId, window.startDate(), objectMapper.writeValueAsString(items));
  		return true;
  	}

  	/** 태그 발견분 + 해시태그 발견분(shortcode 중복 제거) — direct 등록분은 조회 단계에서 이미 빠졌다. */
  	private List<WeeklyPostMetrics> brandNewPosts(List<Long> brandIds, WeekWindow window) {
  		if (brandIds.isEmpty()) {
  			return List.of();
  		}
  		Map<String, WeeklyPostMetrics> byShortCode = new LinkedHashMap<>();
  		for (WeeklyPostMetrics post : brandRead.findTaggedPostsDiscoveredBetween(
  				brandIds, window.from(), window.toExclusive())) {
  			byShortCode.putIfAbsent(post.shortCode(), post);
  		}
  		for (WeeklyPostMetrics post : brandRead.findHashtagPostsDiscoveredBetween(
  				brandIds, window.from(), window.toExclusive())) {
  			// 태그 풀에 이미 있는 게시물이면 지표가 더 풍부한 태그 쪽(스냅샷 기반)을 남긴다.
  			byShortCode.putIfAbsent(post.shortCode(), post);
  		}
  		return List.copyOf(byShortCode.values());
  	}

  	/** 수집 종료 이벤트의 target을 되짚어 추적 게시물의 최신 스냅샷 지표를 모은다. */
  	private List<WeeklyPostMetrics> endedPosts(List<AlarmEventRow> userEvents) {
  		List<Long> targetIds = userEvents.stream()
  				.filter(event -> COLLECTION_ENDED.equals(event.eventType()))
  				.map(AlarmEventRow::targetId)
  				.distinct()
  				.toList();
  		if (targetIds.isEmpty()) {
  			return List.of();
  		}
  		Map<String, String> authorByShortCode = new LinkedHashMap<>();
  		for (TargetRow target : monitoringRead.findTargets(targetIds)) {
  			if (target.trackedShortCode() != null) {
  				authorByShortCode.putIfAbsent(target.trackedShortCode(), target.username());
  			}
  		}
  		if (authorByShortCode.isEmpty()) {
  			return List.of();
  		}
  		return monitoringRead.findLatestSnapshots(authorByShortCode.keySet()).stream()
  				.map(snapshot -> new WeeklyPostMetrics(snapshot.shortCode(),
  						authorByShortCode.get(snapshot.shortCode()), snapshot.contentType(),
  						snapshot.views(), snapshot.likes(), snapshot.comments()))
  				.toList();
  	}

  	/**
  	 * 지난주 미표기 판정된 <b>등록(시딩) 게시물</b>. 스윕이 발견한 제3자 게시물은 대응 불가능한
  	 * 소음이라 제외한다(설계 §2 "미표기 범위"). 등록 원장의 정본은 app.brand_direct_posts다.
  	 * 킬 스위치가 꺼져 있으면 아예 조회하지 않는다 — FE 미노출 정보가 알림으로 새는 사고 방지(설계 §8).
  	 */
  	private List<String> adNotDisclosed(long userId, WeekWindow window) {
  		if (!exposeAdDisclosure) {
  			return List.of();
  		}
  		Set<String> registered = brandDirectPosts.shortCodesByUser(userId);
  		if (registered.isEmpty()) {
  			return List.of();
  		}
  		List<String> judged = brandRead.findNotDisclosedJudgedBetween(
  				registered, window.from(), window.toExclusive());
  		if (judged.isEmpty()) {
  			return List.of();
  		}
  		Set<String> alreadyNotified = adNotices.findNotifiedInOtherWeek(userId, judged, window.startDate());
  		return judged.stream().filter(shortCode -> !alreadyNotified.contains(shortCode)).toList();
  	}

  	/** 모니터링 진행 섹션 문안에 붙일 캠페인 이름(설계 §3). 유저 스코프는 조회가 건다. */
  	private List<String> campaignNames(long userId, List<AlarmEventRow> userEvents) {
  		List<Long> targetIds = userEvents.stream()
  				.filter(event -> COLLECTION_STARTED.equals(event.eventType())
  						|| COLLECTION_ENDED.equals(event.eventType()))
  				.map(AlarmEventRow::targetId)
  				.distinct()
  				.toList();
  		return monitoringItems.findCampaignNamesByTargetIds(userId, targetIds);
  	}
  }
  ```

- [ ] 구 일일 잡 제거
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign rm was/src/main/java/com/celfit/was/monitoring/DigestJob.java was/src/test/java/com/celfit/was/monitoring/DigestJobTest.java
  ```

- [ ] 크론 설정 추가 — `was/src/main/resources/application.yml`의 `monitoring:` 블록에서 `enabled: false` 줄 바로 아래에 아래 3줄을 삽입한다(들여쓰기는 `brand:`와 같은 2칸).
  ```yaml
    digest:
      weekly-cron: "0 0 9 * * MON"                       # 주간 다이제스트 정시 실행(KST) — 지난주(월~일)를 집계
      weekly-catchup-cron: "0 10,20,30,40,50 9-23 * * MON"   # 같은 창 재계산 따라잡기 + 메일 재시도 창(월요일 한정)
  ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyDigestJobTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`, 10개 테스트 통과.

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add -A was/src/main/java/com/celfit/was/monitoring was/src/test/java/com/celfit/was/monitoring was/src/main/resources/application.yml
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): 주간 다이제스트 잡으로 일일 DigestJob 대체

  월 09:00 KST + 따라잡기로 지난주(월~일)를 명시적 창으로 집계해 (user, 주 시작일)
  멱등 upsert한다. 브랜드 발견분·미표기 판정(킬 스위치 게이트·게시물당 1회 가드)이
  섹션으로 추가됐고, 내용이 없으면 그 주는 만들지 않는다(설계 §2·§4·§8).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---
## Task 9: 알림 설정 API 축소(주간 이메일 토글 1개)

이벤트 종류별 4토글 매트릭스를 주간 이메일 토글 하나로 바꾼다. 서비스·응답 DTO·Swagger 스키마·구 저장소 제거·테스트 2개를 **한 태스크에서** 처리한다 — 구 `EmailOptOutRepository`를 지우는 순간 그 클래스를 참조하는 테스트가 전부 컴파일되지 않으므로 나눌 수 없다. 계약 변경이라 FE 통지가 필요하다(설계 §5).

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/monitoring/NotificationSettingsService.java` (전문 교체)
- Modify: `was/src/main/java/com/celfit/was/v1/monitoring/NotificationSettingsResponse.java` (전문 교체)
- Modify: `was/src/main/java/com/celfit/was/v1/monitoring/NotificationSettingsPatchDoc.java` (전문 교체)
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringEventTypes.java` (전문 교체 — `EVENT_TYPES`·`toStorage` 제거)
- Delete: `was/src/main/java/com/celfit/was/monitoring/EmailOptOutRepository.java`
- Delete: `was/src/test/java/com/celfit/was/monitoring/EmailOptOutRepositoryTest.java`
- Modify: `was/src/test/java/com/celfit/was/auth/UserRepositoryTest.java` (9행 import, 43~44행 필드, 401행 호출)
- Modify: `was/src/test/java/com/celfit/was/v1/monitoring/NotificationSettingsServiceTest.java` (전문 교체)
- Modify: `was/src/test/java/com/celfit/was/v1/monitoring/V1NotificationSettingsControllerTest.java` (전문 교체)

**Steps:**

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/v1/monitoring/NotificationSettingsServiceTest.java`를 아래 전문으로 교체한다.
  ```java
  package com.celfit.was.v1.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  import com.celfit.was.IntegrationTest;
  import com.celfit.was.v1.common.V1ApiException;
  import java.util.HashMap;
  import java.util.Map;
  import java.util.UUID;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.jdbc.core.simple.JdbcClient;

  /** NotificationSettingsService — 주간 이메일 토글 1개(2026-08-27 개편 §5), 저장소 왕복 실사용. */
  class NotificationSettingsServiceTest extends IntegrationTest {

  	@Autowired
  	NotificationSettingsService service;
  	@Autowired
  	JdbcClient jdbcClient;

  	long userId;

  	@BeforeEach
  	void 유저_시드() {
  		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
  				.param("email", "notif-svc-" + UUID.randomUUID() + "@test.io")
  				.query(Long.class).single();
  	}

  	@Test
  	void get_옵트아웃_행이_없는_유저는_기본값_true() {
  		assertThat(service.get(userId).weeklyEmail()).isTrue();
  	}

  	@Test
  	void patch_false면_옵트아웃_행이_생기고_get에도_반영된다() {
  		assertThat(service.patch(userId, Map.of("weeklyEmail", false)).weeklyEmail()).isFalse();

  		assertThat(service.get(userId).weeklyEmail()).isFalse();
  	}

  	@Test
  	void patch_다시_true면_옵트아웃_행이_삭제된다() {
  		service.patch(userId, Map.of("weeklyEmail", false));

  		assertThat(service.patch(userId, Map.of("weeklyEmail", true)).weeklyEmail()).isTrue();
  		assertThat(service.get(userId).weeklyEmail()).isTrue();
  	}

  	@Test
  	void patch_weeklyEmail이_null이면_400() {
  		Map<String, Object> body = new HashMap<>();
  		body.put("weeklyEmail", null);

  		assertThatThrownBy(() -> service.patch(userId, body))
  				.isInstanceOf(V1ApiException.class)
  				.satisfies(e -> assertThat(((V1ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
  	}

  	@Test
  	void patch_boolean이_아니면_400() {
  		assertThatThrownBy(() -> service.patch(userId, Map.of("weeklyEmail", "false")))
  				.isInstanceOf(V1ApiException.class)
  				.satisfies(e -> assertThat(((V1ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
  	}

  	@Test
  	void patch_미지의_최상위_키는_400() {
  		assertThatThrownBy(() -> service.patch(userId, Map.of("content", Map.of())))
  				.isInstanceOf(V1ApiException.class)
  				.satisfies(e -> assertThat(((V1ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
  	}

  	@Test
  	void patch_빈_바디는_아무것도_바꾸지_않는다() {
  		service.patch(userId, Map.of("weeklyEmail", false));

  		assertThat(service.patch(userId, Map.of()).weeklyEmail()).isFalse();
  	}
  }
  ```

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/v1/monitoring/V1NotificationSettingsControllerTest.java`를 아래 전문으로 교체한다.
  ```java
  package com.celfit.was.v1.monitoring;

  import static org.mockito.ArgumentMatchers.anyLong;
  import static org.mockito.BDDMockito.given;
  import static org.mockito.BDDMockito.then;
  import static org.mockito.Mockito.never;
  import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
  import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  import com.celfit.was.auth.AppUser;
  import com.celfit.was.auth.AppUserDetails;
  import com.celfit.was.config.SecurityConfig;
  import com.celfit.was.monitoring.WeeklyEmailOptOutRepository;
  import com.celfit.was.v1.common.V1ExceptionAdvice;
  import java.time.OffsetDateTime;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
  import org.springframework.context.annotation.Import;
  import org.springframework.http.MediaType;
  import org.springframework.test.context.bean.override.mockito.MockitoBean;
  import org.springframework.test.web.servlet.MockMvc;

  /** /v1/notification-settings 계약(2026-08-27 개편 §5) — 주간 이메일 토글 1개, 400 검증. */
  @WebMvcTest(controllers = V1NotificationSettingsController.class,
  		properties = "was.cors.allowed-origins=http://localhost:3000")
  @Import({NotificationSettingsService.class, V1ExceptionAdvice.class, SecurityConfig.class})
  class V1NotificationSettingsControllerTest {

  	@Autowired
  	MockMvc mockMvc;

  	@MockitoBean
  	WeeklyEmailOptOutRepository repository;

  	private static AppUserDetails principal() {
  		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
  				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
  	}

  	@Test
  	void GET_옵트아웃이_없으면_weeklyEmail_true() throws Exception {
  		given(repository.isOptedOut(7L)).willReturn(false);

  		mockMvc.perform(get("/v1/notification-settings").with(user(principal())))
  				.andExpect(status().isOk())
  				.andExpect(jsonPath("$.data.weeklyEmail").value(true));
  	}

  	@Test
  	void GET_옵트아웃이_있으면_weeklyEmail_false() throws Exception {
  		given(repository.isOptedOut(7L)).willReturn(true);

  		mockMvc.perform(get("/v1/notification-settings").with(user(principal())))
  				.andExpect(status().isOk())
  				.andExpect(jsonPath("$.data.weeklyEmail").value(false));
  	}

  	@Test
  	void PATCH_false는_optOut_호출() throws Exception {
  		given(repository.isOptedOut(7L)).willReturn(true);

  		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
  						.contentType(MediaType.APPLICATION_JSON)
  						.content("""
  								{"weeklyEmail":false}"""))
  				.andExpect(status().isOk())
  				.andExpect(jsonPath("$.data.weeklyEmail").value(false));

  		then(repository).should().optOut(7L);
  	}

  	@Test
  	void PATCH_true는_optIn_호출() throws Exception {
  		given(repository.isOptedOut(7L)).willReturn(false);

  		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
  						.contentType(MediaType.APPLICATION_JSON)
  						.content("""
  								{"weeklyEmail":true}"""))
  				.andExpect(status().isOk())
  				.andExpect(jsonPath("$.data.weeklyEmail").value(true));

  		then(repository).should().optIn(7L);
  	}

  	@Test
  	void PATCH_구_계약인_content_키는_400() throws Exception {
  		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
  						.contentType(MediaType.APPLICATION_JSON)
  						.content("""
  								{"content":{"collection_ended":{"email":false}}}"""))
  				.andExpect(status().isBadRequest())
  				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

  		then(repository).should(never()).optOut(anyLong());
  		then(repository).should(never()).optIn(anyLong());
  	}

  	@Test
  	void PATCH_weeklyEmail이_boolean이_아니면_400() throws Exception {
  		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
  						.contentType(MediaType.APPLICATION_JSON)
  						.content("""
  								{"weeklyEmail":"false"}"""))
  				.andExpect(status().isBadRequest())
  				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

  		then(repository).should(never()).optOut(anyLong());
  	}

  	@Test
  	void PATCH_빈_바디는_변경_없이_현재_상태_반환() throws Exception {
  		given(repository.isOptedOut(7L)).willReturn(true);

  		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
  						.contentType(MediaType.APPLICATION_JSON)
  						.content("{}"))
  				.andExpect(status().isOk())
  				.andExpect(jsonPath("$.data.weeklyEmail").value(false));

  		then(repository).should(never()).optOut(anyLong());
  		then(repository).should(never()).optIn(anyLong());
  	}
  }
  ```

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.v1.monitoring.NotificationSettingsServiceTest" --tests "com.celfit.was.v1.monitoring.V1NotificationSettingsControllerTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: method weeklyEmail()`(응답 DTO가 아직 4종 매트릭스), `cannot find symbol: class WeeklyEmailOptOutRepository`가 서비스 생성자와 맞지 않음.

- [ ] 구 옵트아웃 저장소 제거와 호출부 정합
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign rm was/src/main/java/com/celfit/was/monitoring/EmailOptOutRepository.java was/src/test/java/com/celfit/was/monitoring/EmailOptOutRepositoryTest.java
  ```
  이어서 `was/src/test/java/com/celfit/was/auth/UserRepositoryTest.java`를 고친다: 9행 import를 `import com.celfit.was.monitoring.WeeklyEmailOptOutRepository;`로, 43~44행 필드를 `@Autowired\n\tWeeklyEmailOptOutRepository weeklyEmailOptOutRepository;`로, 401행 호출을 `weeklyEmailOptOutRepository.optOut(userId);`로 바꾼다.

- [ ] 어휘 유틸 정리 — `was/src/main/java/com/celfit/was/monitoring/MonitoringEventTypes.java`를 아래 전문으로 교체한다(`EVENT_TYPES`·`toStorage`는 소비자가 사라졌다).
  ```java
  package com.celfit.was.monitoring;

  import java.util.Map;

  /**
   * monitoring 알람 이벤트 어휘 변환(저장 대문자 → 프론트 소문자). 저장 어휘의 정본은 monitoring
   * 모듈의 {@code AlarmEventType}이고, 프론트 계약(다이제스트 items[].type)은 소문자다.
   * metrics_private↔METRICS_HIDDEN, content_issue↔CONTENT_UNAVAILABLE만 이름이 다르고
   * 나머지는 대소문자만 다르다.
   *
   * <p>2026-08-27 주간 개편으로 역방향(toStorage)과 "4종 완전체 순서"(EVENT_TYPES)는 소비자가
   * 사라졌다 — 알림 설정은 주간 토글 1개가 됐고(설계 §5), 다이제스트 항목 순서·문안의 정본은
   * {@link WeeklyDigestAssembler}다.
   */
  public final class MonitoringEventTypes {

  	private static final Map<String, String> STORAGE_TO_FRONT = Map.of(
  			"COLLECTION_STARTED", "collection_started",
  			"COLLECTION_ENDED", "collection_ended",
  			"METRICS_HIDDEN", "metrics_private",
  			"CONTENT_UNAVAILABLE", "content_issue");

  	private MonitoringEventTypes() {
  	}

  	/** 저장(monitoring AlarmEventType) 대문자 어휘 → 프론트 소문자 어휘. 미지 값은 예외. */
  	public static String toFront(String storageType) {
  		String front = STORAGE_TO_FRONT.get(storageType);
  		if (front == null) {
  			throw new IllegalArgumentException("알 수 없는 저장 이벤트 유형: " + storageType);
  		}
  		return front;
  	}
  }
  ```

- [ ] 알림 설정 서비스를 주간 토글로 교체 — `was/src/main/java/com/celfit/was/v1/monitoring/NotificationSettingsService.java`를 아래 전문으로 교체한다(계약 축소의 본체).
  ```java
  package com.celfit.was.v1.monitoring;

  import com.celfit.was.monitoring.WeeklyEmailOptOutRepository;
  import com.celfit.was.v1.common.V1ApiException;
  import java.util.Map;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;

  /**
   * 알림 설정(2026-08-27 주간 개편 §5) - 이벤트 종류별 4토글 매트릭스를 <b>주간 이메일 수신
   * 토글 1개</b>로 축소했다. 저장은 옵트아웃 행(행 없음 = 수신)이고 이 서비스가 그 위에
   * `weeklyEmail` boolean 계약을 얹는다. 옵트아웃 행이 없는 유저도 get()이 기본값(true)을 내린다 -
   * 별도 유저 행 생성은 하지 않는다(행 없음 자체가 수신 상태를 뜻하므로 저장할 것이 없다).
   */
  @Service
  public class NotificationSettingsService {

  	private static final String WEEKLY_EMAIL_KEY = "weeklyEmail";
  	private static final String VALIDATION_MESSAGE = "올바른 형식이 아니에요.";

  	private final WeeklyEmailOptOutRepository repository;

  	public NotificationSettingsService(WeeklyEmailOptOutRepository repository) {
  		this.repository = repository;
  	}

  	public NotificationSettingsResponse get(long userId) {
  		return new NotificationSettingsResponse(!repository.isOptedOut(userId));
  	}

  	/**
  	 * PATCH — body는 `{"weeklyEmail": <bool>}`. 그 밖의 최상위 키·boolean 아닌 값은 400
  	 * VALIDATION_FAILED. 빈 바디는 아무것도 바꾸지 않고 현재 상태를 돌려준다.
  	 * 검증을 먼저 끝낸 뒤 옵트아웃 행을 갱신한다(SignupService.register와 동일 관례).
  	 */
  	@Transactional
  	public NotificationSettingsResponse patch(long userId, Map<String, Object> body) {
  		Map<String, Object> safeBody = body == null ? Map.of() : body;
  		for (String key : safeBody.keySet()) {
  			if (!WEEKLY_EMAIL_KEY.equals(key)) {
  				throw V1ApiException.validation(VALIDATION_MESSAGE);
  			}
  		}
  		if (safeBody.containsKey(WEEKLY_EMAIL_KEY)) {
  			if (!(safeBody.get(WEEKLY_EMAIL_KEY) instanceof Boolean weeklyEmail)) {
  				throw V1ApiException.validation(VALIDATION_MESSAGE);
  			}
  			if (weeklyEmail) {
  				repository.optIn(userId);
  			} else {
  				repository.optOut(userId);
  			}
  		}
  		return get(userId);
  	}
  }
  ```

- [ ] 응답 DTO 교체 — `was/src/main/java/com/celfit/was/v1/monitoring/NotificationSettingsResponse.java`를 아래 전문으로 교체한다(서비스가 이 형태를 반환한다).
  ```java
  package com.celfit.was.v1.monitoring;

  /**
   * GET·PATCH /v1/notification-settings 응답(2026-08-27 주간 개편 §5) — 주간 리포트 메일 수신
   * 여부 한 개. 이벤트 종류별 4토글 매트릭스는 폐지됐다(FE 통지 필요).
   */
  public record NotificationSettingsResponse(boolean weeklyEmail) {
  }
  ```

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/v1/monitoring/NotificationSettingsPatchDoc.java`를 아래 전문으로 교체한다.
  ```java
  package com.celfit.was.v1.monitoring;

  import io.swagger.v3.oas.annotations.media.Schema;

  /**
   * PATCH /v1/notification-settings의 Swagger 문서 전용 스키마(2026-08-27 주간 개편 §5) —
   * <b>런타임 역직렬화에는 쓰지 않는다.</b> 실제 컨트롤러 파라미터 타입은 여전히
   * {@code Map<String,Object>}이며(V1NotificationSettingsController 참조), 이 레코드는
   * {@code @RequestBody(content=@Content(schema=@Schema(implementation=...)))}로 springdoc이
   * 뽑는 필드 스키마만 대체한다.
   *
   * <p>필드 정의의 정본은 {@link NotificationSettingsService#patch}의 검증 분기다. 이벤트 종류별
   * 4토글 매트릭스(구 {@code content} 맵)는 폐지됐고 주간 이메일 수신 토글 하나만 남았다.
   */
  public final class NotificationSettingsPatchDoc {

  	private NotificationSettingsPatchDoc() {
  	}

  	@Schema(name = "NotificationSettingsPatchRequest", description = "주간 리포트 메일 수신 여부만 바꾼다. "
  			+ "키를 생략하면 아무것도 바뀌지 않는다. weeklyEmail 밖의 최상위 키, boolean이 아닌 값은 "
  			+ "전부 400 VALIDATION_FAILED.")
  	public record Request(
  			@Schema(description = "주간 리포트 메일 수신 여부. false면 인앱 알림만 받는다.")
  			Boolean weeklyEmail) {
  	}
  }
  ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.v1.monitoring.NotificationSettingsServiceTest" --tests "com.celfit.was.v1.monitoring.V1NotificationSettingsControllerTest" --tests "com.celfit.was.auth.UserRepositoryTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`, 서비스 7개 + 컨트롤러 7개 + 탈퇴 이관 테스트 통과.

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add -A was/src/main/java/com/celfit/was/v1/monitoring was/src/test/java/com/celfit/was/v1/monitoring was/src/main/java/com/celfit/was/monitoring was/src/test/java/com/celfit/was/monitoring was/src/test/java/com/celfit/was/auth/UserRepositoryTest.java
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): 알림 설정 API를 주간 이메일 토글 1개로 축소

  이벤트 종류별 4토글 매트릭스를 폐지하고 weeklyEmail boolean 하나로 바꿨다.
  Swagger 스키마와 서비스·컨트롤러 테스트를 새 계약에 맞췄다(설계 §5). FE 통지 필요.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 10: 주간 리포트 메일 문안·발송

설계 §6. 발송 트리거는 "주간 다이제스트 생성 직후"이며, 발송 상태는 다이제스트 행이 들고 있어 따라잡기 틱이 실패분만 재시도한다(at-least-once, 유저 단위 격리, 시도 상한). 설계 §8의 "발송 시각 집중"은 발송 간 최소 간격으로 완화한다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestMailComposer.java`
- Create: `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestMailer.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/DigestRepository.java` (`upsert` 뒤에 메서드 3개 추가)
- Modify: `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestJob.java` (생성자·`upsertWeekly`에 mailer 배선)
- Modify: `was/src/main/resources/application.yml` (`was:` 블록에 `web.base-url`, `monitoring.digest`에 `email` 하위 추가)
- Test: `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestMailComposerTest.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestMailerTest.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestJobTest.java` (생성자 인자 1개 추가)

**Steps:**

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestMailComposerTest.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;

  import java.time.LocalDate;
  import java.util.List;
  import org.junit.jupiter.api.Test;

  /** 주간 리포트 메일 문안(설계 §6) — 섹션 순서·합산 한 줄·딥링크·엠대시 금지. */
  class WeeklyDigestMailComposerTest {

  	private final WeeklyDigestMailComposer composer = new WeeklyDigestMailComposer("https://hypenow.io");
  	private final WeekWindow window = new WeekWindow(LocalDate.of(2026, 8, 17));

  	@Test
  	void 제목에_지난주_기간이_들어간다() {
  		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
  				new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 2, null)));

  		assertThat(mail.subject()).isEqualTo("[hypenow] 지난주 모니터링 요약 (8월 17일 - 8월 23일)");
  	}

  	@Test
  	void 섹션_제목과_건수가_본문에_들어간다() {
  		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
  				new DigestItem("action_needed", "ad_not_disclosed", "광고 표기가 없는 등록 게시물이 있어요", 3, null),
  				new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 2, null)));

  		assertThat(mail.text())
  				.contains("[확인 필요]")
  				.contains("- 광고 표기가 없는 등록 게시물이 있어요 : 3건")
  				.contains("[모니터링 진행]")
  				.contains("- 새로 수집을 시작한 콘텐츠가 있어요 : 2건");
  	}

  	@Test
  	void 내용이_없는_섹션은_본문에_나오지_않는다() {
  		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
  				new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 2, null)));

  		assertThat(mail.text()).doesNotContain("[확인 필요]").doesNotContain("[브랜드 소식]");
  	}

  	@Test
  	void 합산_지표는_항목_아래_한_줄로_붙는다() {
  		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
  				new DigestItem("brand", "brand_new_posts", "브랜드를 언급한 새 게시물을 찾았어요", 12,
  						new DigestItem.Metrics(123456L, 7890L, 123L))));

  		assertThat(mail.text()).contains("  조회수(릴스) 123,456 · 좋아요 7,890 · 댓글 123");
  	}

  	@Test
  	void 조회수가_없으면_그_숫자를_빼고_렌더링한다() {
  		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
  				new DigestItem("brand", "brand_new_posts", "브랜드를 언급한 새 게시물을 찾았어요", 3,
  						new DigestItem.Metrics(null, 30L, 3L))));

  		assertThat(mail.text()).contains("  좋아요 30 · 댓글 3").doesNotContain("조회수");
  	}

  	@Test
  	void 하이라이트는_건수와_지표_줄_없이_요약만_적는다() {
  		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
  				new DigestItem("highlight", "top_post", "@big 게시물 · 조회수 12.3만", 1,
  						new DigestItem.Metrics(123456L, 10L, 1L))));

  		assertThat(mail.text())
  				.contains("[이번 주 하이라이트]")
  				.contains("- @big 게시물 · 조회수 12.3만")
  				.doesNotContain("1건")
  				.doesNotContain("조회수(릴스)");
  	}

  	@Test
  	void 딥링크와_수신_해지_안내가_들어간다() {
  		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
  				new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 1, null)));

  		assertThat(mail.text())
  				.contains("https://hypenow.io/notifications")
  				.contains("https://hypenow.io/settings/notifications");
  	}

  	@Test
  	void 문안에_엠대시가_없다() {
  		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
  				new DigestItem("action_needed", "ad_not_disclosed", "광고 표기가 없는 등록 게시물이 있어요", 1, null),
  				new DigestItem("brand", "brand_new_posts", "브랜드를 언급한 새 게시물을 찾았어요", 1,
  						new DigestItem.Metrics(1L, 1L, 1L)),
  				new DigestItem("highlight", "top_post", "@a 게시물 · 좋아요 1", 1, null)));

  		assertThat(mail.subject()).doesNotContain("—");
  		assertThat(mail.text()).doesNotContain("—");
  	}
  }
  ```

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyDigestMailComposerTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: class WeeklyDigestMailComposer`.

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestMailComposer.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import java.time.format.DateTimeFormatter;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.Locale;
  import java.util.Map;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.stereotype.Component;

  /**
   * 주간 리포트 메일 문안(설계 §6) — 인앱 다이제스트와 <b>같은 항목</b>을 텍스트로 편다.
   * 구 임시 카피(monitoring AlarmMailComposer)를 대체하는 정식 문안이며 딥링크를 포함한다.
   *
   * <p>사용자向 문안이라 엠대시를 쓰지 않는다(프로젝트 규칙) - 구분은 " - "와 가운뎃점(·)이다.
   * 항목 문안·건수·합산 지표의 정본은 {@link WeeklyDigestAssembler}가 만든 항목 그 자체다.
   * 이 클래스는 표현만 담당한다.
   */
  @Component
  public class WeeklyDigestMailComposer {

  	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREA);

  	/** 렌더 순서 - 조립기의 섹션 순서와 같아야 인앱·메일이 어긋나지 않는다. */
  	private static final List<String> SECTION_ORDER = List.of(
  			WeeklyDigestAssembler.CATEGORY_ACTION, WeeklyDigestAssembler.CATEGORY_BRAND,
  			WeeklyDigestAssembler.CATEGORY_CONTENT, WeeklyDigestAssembler.CATEGORY_HIGHLIGHT);

  	private static final Map<String, String> SECTION_TITLES = Map.of(
  			WeeklyDigestAssembler.CATEGORY_ACTION, "확인 필요",
  			WeeklyDigestAssembler.CATEGORY_BRAND, "브랜드 소식",
  			WeeklyDigestAssembler.CATEGORY_CONTENT, "모니터링 진행",
  			WeeklyDigestAssembler.CATEGORY_HIGHLIGHT, "이번 주 하이라이트");

  	private final String webBaseUrl;

  	public WeeklyDigestMailComposer(@Value("${was.web.base-url:https://hypenow.io}") String webBaseUrl) {
  		this.webBaseUrl = webBaseUrl;
  	}

  	public record Mail(String subject, String text) {
  	}

  	public Mail compose(WeekWindow window, List<DigestItem> items) {
  		String period = DAY.format(window.startDate()) + " - " + DAY.format(window.endDateInclusive());
  		StringBuilder text = new StringBuilder();
  		text.append("안녕하세요. 지난주(").append(period).append(") 하입나우 모니터링 요약이에요.\n");
  		for (String category : SECTION_ORDER) {
  			List<DigestItem> section = items.stream()
  					.filter(item -> category.equals(item.category()))
  					.toList();
  			if (section.isEmpty()) {
  				continue;   // 내용이 있는 섹션만 노출(설계 §3)
  			}
  			text.append('\n').append('[').append(SECTION_TITLES.get(category)).append("]\n");
  			boolean highlight = WeeklyDigestAssembler.CATEGORY_HIGHLIGHT.equals(category);
  			for (DigestItem item : section) {
  				text.append("- ").append(item.summary());
  				if (!highlight) {
  					// 하이라이트는 "1건"이 정보가 아니라 소음이다 - 요약 문장 자체가 내용이다.
  					text.append(" : ").append(item.count()).append("건");
  				}
  				text.append('\n');
  				String metrics = highlight ? null : metricsLine(item.metrics());
  				if (metrics != null) {
  					text.append("  ").append(metrics).append('\n');
  				}
  			}
  		}
  		text.append("\n자세한 내용은 ").append(webBaseUrl).append("/notifications 에서 확인할 수 있어요.\n");
  		text.append("주간 이메일 수신은 ").append(webBaseUrl)
  				.append("/settings/notifications 에서 끌 수 있어요.\n");
  		return new Mail("[hypenow] 지난주 모니터링 요약 (" + period + ")", text.toString());
  	}

  	/**
  	 * 섹션 합산 한 줄. 조회수는 릴스만 집계되므로 그 주에 릴스가 없으면 views가 null이고,
  	 * 그때는 그 숫자를 아예 빼고 렌더링한다(설계 §3). 셋 다 없으면 줄 자체를 만들지 않는다.
  	 */
  	private static String metricsLine(DigestItem.Metrics metrics) {
  		if (metrics == null) {
  			return null;
  		}
  		List<String> parts = new ArrayList<>();
  		if (metrics.views() != null) {
  			parts.add("조회수(릴스) " + number(metrics.views()));
  		}
  		if (metrics.likes() != null) {
  			parts.add("좋아요 " + number(metrics.likes()));
  		}
  		if (metrics.comments() != null) {
  			parts.add("댓글 " + number(metrics.comments()));
  		}
  		return parts.isEmpty() ? null : String.join(" · ", parts);
  	}

  	private static String number(long value) {
  		return String.format(Locale.KOREA, "%,d", value);
  	}
  }
  ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyDigestMailComposerTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`, 8개 테스트 통과.

- [ ] 실패하는 테스트 작성 — `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestMailerTest.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.celfit.was.IntegrationTest;
  import com.celfit.was.auth.UserRepository;
  import com.celfit.was.mail.MailSendException;
  import com.celfit.was.mail.MailSender;
  import java.time.Duration;
  import java.time.LocalDate;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.UUID;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.jdbc.core.simple.JdbcClient;

  /** 주간 리포트 메일 발송(설계 §6) — 옵트아웃·at-least-once·시도 상한·중복 발송 방지. */
  class WeeklyDigestMailerTest extends IntegrationTest {

  	private static final WeekWindow WEEK = new WeekWindow(LocalDate.of(2026, 8, 17));
  	private static final List<DigestItem> ITEMS = List.of(
  			new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 1, null));

  	/** 발송 기록만 남기는 스텁 — 실패 모드는 생성자 플래그로 켠다. */
  	private static final class RecordingMailSender implements MailSender {
  		private final List<String> sent = new ArrayList<>();
  		private boolean failing;

  		@Override
  		public void send(String to, String subject, String text) {
  			if (failing) {
  				throw new MailSendException("발송 실패(테스트)");
  			}
  			sent.add(to + "|" + subject);
  		}
  	}

  	@Autowired
  	DigestRepository digestRepository;
  	@Autowired
  	WeeklyEmailOptOutRepository optOutRepository;
  	@Autowired
  	UserRepository userRepository;
  	@Autowired
  	JdbcClient jdbcClient;

  	RecordingMailSender mailSender;
  	WeeklyDigestMailer mailer;
  	long userId;
  	long digestId;

  	@BeforeEach
  	void setUp() {
  		mailSender = new RecordingMailSender();
  		mailer = new WeeklyDigestMailer(digestRepository, optOutRepository, userRepository,
  				new WeeklyDigestMailComposer("https://hypenow.io"), mailSender, 3, Duration.ZERO);
  		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
  				.param("email", "weekly-mail-" + UUID.randomUUID() + "@test.io")
  				.query(Long.class).single();
  		digestId = digestRepository.upsert(userId, WEEK.startDate(), "[]");
  	}

  	private long attempts() {
  		return jdbcClient.sql("SELECT email_attempts FROM app.monitoring_digests WHERE id = :id")
  				.param("id", digestId).query(Long.class).single();
  	}

  	private boolean sentMarked() {
  		return Boolean.TRUE.equals(jdbcClient
  				.sql("SELECT email_sent_at IS NOT NULL FROM app.monitoring_digests WHERE id = :id")
  				.param("id", digestId).query(Boolean.class).single());
  	}

  	@Test
  	void 메일을_보내고_발송_시각을_찍는다() {
  		mailer.send(userId, digestId, WEEK, ITEMS);

  		assertThat(mailSender.sent).hasSize(1);
  		assertThat(mailSender.sent.get(0)).contains("[hypenow] 지난주 모니터링 요약 (8월 17일 - 8월 23일)");
  		assertThat(sentMarked()).isTrue();
  	}

  	@Test
  	void 이미_보낸_다이제스트는_다시_보내지_않는다() {
  		mailer.send(userId, digestId, WEEK, ITEMS);
  		mailer.send(userId, digestId, WEEK, ITEMS);

  		assertThat(mailSender.sent).hasSize(1);
  	}

  	@Test
  	void 옵트아웃_유저에게는_보내지_않고_시도도_올리지_않는다() {
  		optOutRepository.optOut(userId);

  		mailer.send(userId, digestId, WEEK, ITEMS);

  		assertThat(mailSender.sent).isEmpty();
  		assertThat(sentMarked()).isFalse();
  		assertThat(attempts()).isZero();
  	}

  	@Test
  	void 발송_실패는_시도만_올리고_다음_틱에_재시도된다() {
  		mailSender.failing = true;
  		mailer.send(userId, digestId, WEEK, ITEMS);

  		assertThat(sentMarked()).isFalse();
  		assertThat(attempts()).isEqualTo(1);

  		mailSender.failing = false;
  		mailer.send(userId, digestId, WEEK, ITEMS);

  		assertThat(mailSender.sent).hasSize(1);
  		assertThat(sentMarked()).isTrue();
  	}

  	@Test
  	void 시도_상한에_닿으면_더_이상_시도하지_않는다() {
  		mailSender.failing = true;
  		mailer.send(userId, digestId, WEEK, ITEMS);
  		mailer.send(userId, digestId, WEEK, ITEMS);
  		mailer.send(userId, digestId, WEEK, ITEMS);
  		assertThat(attempts()).isEqualTo(3);

  		mailer.send(userId, digestId, WEEK, ITEMS);

  		assertThat(attempts()).isEqualTo(3);   // 상한(3)에 닿아 더 집지 않는다
  	}

  	@Test
  	void 수신_이메일이_없으면_헛돌지_않게_종결한다() {
  		jdbcClient.sql("UPDATE app.users SET email = '' WHERE id = :id").param("id", userId).update();

  		mailer.send(userId, digestId, WEEK, ITEMS);

  		assertThat(mailSender.sent).isEmpty();
  		assertThat(sentMarked()).isTrue();
  	}
  }
  ```

- [ ] 실행해 실패 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyDigestMailerTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: class WeeklyDigestMailer`, `cannot find symbol: method isEmailPending(long,int)`.

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/DigestRepository.java`의 `upsert` 메서드 뒤에 아래 3개 메서드를 삽입한다.
  ```java
  	/**
  	 * 주간 리포트 메일 발송 대상 여부(2026-08-27 §6) — 아직 안 보냈고 시도 상한 미달인 행.
  	 * 상한을 조건에 두는 이유는 별도 "포기" 상태 전이를 만들지 않기 위해서다(monitoring
  	 * AlarmEventRepository.findDue와 같은 관용구): 상한에 닿은 행은 조회에서 자연히 빠진다.
  	 */
  	public boolean isEmailPending(long digestId, int maxAttempts) {
  		return Boolean.TRUE.equals(jdbcClient.sql("""
  				SELECT EXISTS (
  				    SELECT 1 FROM app.monitoring_digests
  				    WHERE id = :id AND email_sent_at IS NULL AND email_attempts < :maxAttempts
  				)
  				""")
  				.param("id", digestId)
  				.param("maxAttempts", maxAttempts)
  				.query(Boolean.class)
  				.single());
  	}

  	/** 발송 성공(또는 보낼 곳 없음) 종결 — 시도 횟수도 함께 올린다. 이미 찍혀 있으면 시각을 보존한다. */
  	public void markEmailSent(long digestId) {
  		jdbcClient.sql("""
  				UPDATE app.monitoring_digests
  				SET email_sent_at = now(), email_attempts = email_attempts + 1
  				WHERE id = :id AND email_sent_at IS NULL
  				""")
  				.param("id", digestId)
  				.update();
  	}

  	/** 발송 실패 — 시도만 올린다. 다음 따라잡기 틱이 이 행만 다시 집는다(at-least-once). */
  	public void markEmailAttempted(long digestId) {
  		jdbcClient.sql("""
  				UPDATE app.monitoring_digests SET email_attempts = email_attempts + 1 WHERE id = :id
  				""")
  				.param("id", digestId)
  				.update();
  	}
  ```

- [ ] 최소 구현 — `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestMailer.java`에 아래 전문을 쓴다.
  ```java
  package com.celfit.was.monitoring;

  import com.celfit.was.auth.AppUser;
  import com.celfit.was.auth.UserRepository;
  import com.celfit.was.mail.MailSender;
  import java.time.Duration;
  import java.util.List;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
  import org.springframework.stereotype.Component;

  /**
   * 주간 리포트 메일 발송(설계 §6) — 다이제스트 <b>생성 직후</b> 같은 루프에서 1통 보낸다.
   * 구 5분 틱 디스패처와 디바운스는 이 구조에서 의미가 없어 제거됐다(주간 리듬은 애초에 몰아치지
   * 않는다).
   *
   * <h2>워터마크가 없다 — 발송 대장은 다이제스트 행이다</h2>
   * 무엇을 보냈는지는 {@code monitoring_digests.email_sent_at}이 말한다. 실패는 시도만 올리고
   * 삼키므로 같은 주의 따라잡기 틱이 그 행만 다시 집는다(at-least-once — 중복이 유실보다 낫다).
   * 시도 상한이 없으면 영구 실패 수신자 하나가 월요일 내내 재시도를 돈다.
   *
   * <h2>트랜잭션을 걸지 않는다</h2>
   * 발송(외부 HTTP)이 트랜잭션 안에 들어가면 커넥션을 쥔 채 수 초를 기다리고, 커밋 직전 실패가
   * "메일은 나갔는데 SENT는 안 찍힌" 상태를 만든다(monitoring AlarmDispatchJob이 같은 이유로
   * 트랜잭션을 피했다).
   *
   * <h2>발송 시각 집중 완화</h2>
   * 월요일 09:00에 전 유저 발송이 몰린다(설계 §8). 발송 사이에 최소 간격을 둬 Resend 호출이
   * 순간적으로 몰리지 않게 한다 — 배치 API를 새로 붙이는 것보다 단순하고, 주간 1회라 총 소요가
   * 유저 수 × 간격으로 예측 가능하다. 테스트는 간격 0으로 조립한다.
   */
  @Component
  @ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
  public class WeeklyDigestMailer {

  	private static final Logger log = LoggerFactory.getLogger(WeeklyDigestMailer.class);

  	private final DigestRepository digests;
  	private final WeeklyEmailOptOutRepository optOuts;
  	private final UserRepository users;
  	private final WeeklyDigestMailComposer composer;
  	private final MailSender mailSender;
  	private final int maxAttempts;
  	private final Duration sendInterval;

  	public WeeklyDigestMailer(DigestRepository digests, WeeklyEmailOptOutRepository optOuts,
  			UserRepository users, WeeklyDigestMailComposer composer, MailSender mailSender,
  			@Value("${monitoring.digest.email.max-attempts:5}") int maxAttempts,
  			@Value("${monitoring.digest.email.send-interval:PT0.2S}") Duration sendInterval) {
  		this.digests = digests;
  		this.optOuts = optOuts;
  		this.users = users;
  		this.composer = composer;
  		this.mailSender = mailSender;
  		this.maxAttempts = maxAttempts;
  		this.sendInterval = sendInterval;
  	}

  	/**
  	 * 다이제스트 1건의 주간 리포트 메일 발송. 이미 보냈거나 시도 상한에 닿았으면 no-op이고,
  	 * 옵트아웃 유저는 시도조차 올리지 않는다(나중에 다시 켜면 그 주 리포트를 받을 수 있게 남겨 둔다).
  	 */
  	public void send(long userId, long digestId, WeekWindow window, List<DigestItem> items) {
  		if (!digests.isEmailPending(digestId, maxAttempts)) {
  			return;
  		}
  		if (optOuts.isOptedOut(userId)) {
  			return;
  		}
  		String email = users.findById(userId).map(AppUser::email)
  				.filter(value -> !value.isBlank())
  				.orElse(null);
  		if (email == null) {
  			// 유저 삭제·이메일 부재 — 재시도해도 보낼 곳이 없다(그냥 두면 매 틱 헛돈다).
  			log.info("수신 이메일 없음 — user {} 주간 리포트 종결", userId);
  			digests.markEmailSent(digestId);
  			return;
  		}
  		WeeklyDigestMailComposer.Mail mail = composer.compose(window, items);
  		try {
  			mailSender.send(email, mail.subject(), mail.text());
  		} catch (RuntimeException e) {
  			log.warn("주간 리포트 발송 실패 — user {}: {}", userId, e.toString());
  			digests.markEmailAttempted(digestId);
  			return;
  		}
  		digests.markEmailSent(digestId);
  		pace();
  	}

  	private void pace() {
  		if (sendInterval.isZero() || sendInterval.isNegative()) {
  			return;
  		}
  		try {
  			Thread.sleep(sendInterval.toMillis());
  		} catch (InterruptedException e) {
  			Thread.currentThread().interrupt();
  		}
  	}
  }
  ```

- [ ] 설정 추가 — `was/src/main/resources/application.yml`을 두 군데 고친다.
  1. `was:` 블록의 `cors:` 앞에 아래 2줄을 추가한다(들여쓰기 2칸).
     ```yaml
       web:
         base-url: ${WEB_BASE_URL:https://hypenow.io}   # 메일 딥링크 기준 주소
     ```
  2. Task 8에서 추가한 `monitoring.digest:` 블록 안에 아래 3줄을 추가한다(들여쓰기 4칸).
     ```yaml
       email:
         max-attempts: 5              # 주간 리포트 발송 시도 상한 - 닿으면 그 주는 포기한다
         send-interval: PT0.2S        # 발송 사이 최소 간격 - 월요일 09:00 호출 집중 완화(설계 §8)
     ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyDigestMailerTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`, 6개 테스트 통과.

- [ ] 잡에 발송 배선 — `was/src/main/java/com/celfit/was/monitoring/WeeklyDigestJob.java`를 3군데 고친다.
  1. 필드에 `private final WeeklyDigestMailer mailer;`를 `assembler` 아래에 추가한다.
  2. 생성자 파라미터에 `WeeklyDigestAssembler assembler` 다음으로 `WeeklyDigestMailer mailer`를 넣고 `this.mailer = mailer;`를 대입 목록에 추가한다.
  3. `upsertWeekly`의 마지막 3줄을 아래로 교체한다.
     ```java
     		// 이력은 실제로 알림에 실릴 때만 남긴다 — 조립 결과가 비면(도달 불가하지만 방어) 다음 주에 다시 기회를 준다.
     		adNotices.markNotified(userId, adShortCodes, window.startDate());
     		long digestId = digests.upsert(userId, window.startDate(), objectMapper.writeValueAsString(items));
     		// 발송은 같은 try 블록(유저 단위 격리) 안이다 — 한 유저의 메일 실패가 다음 유저의 다이제스트를 막지 않는다.
     		mailer.send(userId, digestId, window, items);
     		return true;
     ```

- [ ] 잡 테스트 생성자 정합 — `was/src/test/java/com/celfit/was/monitoring/WeeklyDigestJobTest.java`의 `newJob`을 아래로 교체하고, 필드·`setUp`에 메일러 조립을 더한다.
  ```java
  	@Autowired
  	WeeklyEmailOptOutRepository weeklyEmailOptOutRepository;
  	@Autowired
  	com.celfit.was.auth.UserRepository userRepository;

  	private WeeklyDigestJob newJob(boolean exposeAdDisclosure) {
  		WeeklyDigestMailer mailer = new WeeklyDigestMailer(digestRepository, weeklyEmailOptOutRepository,
  				userRepository, new WeeklyDigestMailComposer("https://hypenow.io"),
  				(to, subject, text) -> { }, 5, java.time.Duration.ZERO);
  		return new WeeklyDigestJob(monitoringReadRepository, brandReadRepository, brandLinkRepository,
  				brandDirectPostRepository, monitoringItemRepository, adDisclosureNoticeRepository,
  				digestRepository, new WeeklyDigestAssembler(), mailer, new ObjectMapper(),
  				Clock.fixed(NOW, ZoneOffset.UTC), exposeAdDisclosure);
  	}
  ```

- [ ] 실행해 통과 확인
  ```
  ./gradlew :was:test --tests "com.celfit.was.monitoring.WeeklyDigestJobTest" --tests "com.celfit.was.monitoring.WeeklyDigestMailerTest" --tests "com.celfit.was.monitoring.WeeklyDigestMailComposerTest"
  ```
  기대 출력: `BUILD SUCCESSFUL`, 24개 테스트 통과.

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add -A was/src/main/java/com/celfit/was/monitoring was/src/test/java/com/celfit/was/monitoring was/src/main/resources/application.yml
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(was): 주간 리포트 메일 문안·발송 추가

  다이제스트 생성 직후 유저당 1통을 was Resend 스택으로 보낸다. 발송 대장은 다이제스트
  행(email_sent_at·email_attempts)이라 따라잡기 틱이 실패분만 재시도하고, 발송 간
  최소 간격으로 월요일 호출 집중을 완화한다(설계 §6·§8).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---
## Task 11: monitoring 이메일 디스패처·즉시 레인 폐지

설계 §6 "기존 5분 틱 + 디바운스 디스패처는 불필요해져 제거·단순화"와 §2 "즉시 레인 폐지". 발송이 was로 옮겨갔으므로 monitoring 모듈의 발송 스택 전체(디스패처·디바운스·수신자 조회·Resend 사본)를 지운다. **`alarm_event` 적재는 유지한다** — 지표 숨김 같은 상태 전이는 소급 조회가 불가능해 원장이 계속 필요하다(설계 §4). `email_status`·`dispatch_after` 컬럼도 지우지 않는다(expand-contract — 컬럼 정리는 별도 릴리스).

**Files:**
- Delete: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmDispatchJob.java`
- Delete: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmDispatchScheduler.java`
- Delete: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmMailComposer.java`
- Delete: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmRecipientReader.java`
- Delete: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEmailStatus.java`
- Delete: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEvent.java`
- Delete: `monitoring/src/main/java/com/celfit/monitoring/mail/` 5개 파일 전부
- Delete: `monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmDispatchJobTest.java`
- Delete: `monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmDispatchSchedulerTest.java`
- Delete: `monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmMailComposerTest.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEventRepository.java` (전문 교체 — `insert`만 남긴다)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/alarm/DispatchLane.java` (전문 교체 — `immediate` 제거)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmRecorder.java` (50~62행 — 진입점 2개를 1개로)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/RegistrationService.java` (128~131행 호출부)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/DailySweepJob.java` (429행 호출부)
- Modify: `monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmRecorderTest.java` (86·99·110·243·244행)
- Modify: `monitoring/src/test/java/com/celfit/monitoring/alarm/DispatchLaneTest.java` (43행 부근 `immediate` 테스트 제거)
- Modify: `monitoring/src/main/resources/application.yml` (127~139행 `alarm:`·`mail:` 블록 정리)

**Steps:**

- [ ] 실패하는 테스트 작성 — 이 태스크는 **삭제가 본체**라 새 동작을 추가하지 않는다. 대신 남길 계약을 못박도록 `monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmRecorderTest.java`의 레인 테스트 2개를 하나로 합친다. 기존 테스트 `수집_시작_즉시_레인은_발송_시각이_발생_시각과_같다`(84~95행)와 `자동_전환은_아침_레인이라_발송_시각이_발생_시각과_다르다`(97~104행)를 **둘 다 지우고** 그 자리에 아래 하나를 넣는다.
  ```java
  	/**
  	 * 즉시 레인 폐지(2026-08-27 주간 개편 §2) — 직접 등록발이든 스윕 자동 전환이든 진입점이
  	 * 하나이고 레인도 아침 하나다. 구 테스트 2개(즉시/자동 전환)를 이 하나가 대체한다.
  	 */
  	@Test
  	void 수집_시작은_아침_레인으로_적재된다() {
  		long id = tracking(7L, "SC1", "rk-1");

  		recorder.collectionStarted(id, 7L, "acct_a", "SC1");

  		var row = allEvents().getFirst();
  		assertThat(row.get("event_type")).isEqualTo("COLLECTION_STARTED");
  		assertThat(row.get("user_id")).isEqualTo(7L);
  		assertThat(row.get("email_status")).isEqualTo("PENDING");
  		Timestamp occurredAt = (Timestamp) row.get("occurred_at");
  		assertThat(row.get("dispatch_after"))
  				.isEqualTo(Timestamp.from(DispatchLane.morning(occurredAt.toInstant())));
  	}
  ```
  이어서 같은 파일의 나머지 2곳을 고친다.
  - `수신자_없는_캠페인은_적재하지_않는다`(110행): `recorder.collectionStartedImmediate(id, null, "acct_a", "SC1");` → `recorder.collectionStarted(id, null, "acct_a", "SC1");`
  - `적재_실패는_밖으로_던지지_않고_삼킨다`(243~244행): 두 줄(`throwingRecorder.collectionStartedImmediate(...)`·`throwingRecorder.collectionStartedScheduled(...)`)을 `throwingRecorder.collectionStarted(id, 7L, "acct_a", "SC1");` 한 줄로 합친다. 바로 위 Javadoc의 "4개 단순 진입점"은 "3개 단순 진입점"으로 바꾼다.

  `import java.sql.Timestamp;`가 없으면 추가한다.

- [ ] 실행해 실패 확인
  ```
  ./gradlew :monitoring:test --tests "com.celfit.monitoring.alarm.AlarmRecorderTest"
  ```
  기대 출력: 컴파일 실패 — `cannot find symbol: method collectionStarted(long,Long,String,String)`.

- [ ] 최소 구현 — `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmRecorder.java`의 50~62행(`collectionStartedImmediate`·`collectionStartedScheduled` 두 메서드)을 아래 하나로 교체한다.
  ```java
  	/**
  	 * 수집 시작 적재 — 직접 등록발이든 스윕 첫 감지 자동 전환이든 같은 아침 레인이다.
  	 * 즉시 레인은 2026-08-27 주간 개편에서 폐지됐다(설계 §2): 소비가 주간 다이제스트 하나뿐이라
  	 * 레인 구분이 의미를 잃었고, 직접 등록 즉시 알림은 등록 처리 내역(acknowledged) 표면과 중복이었다.
  	 */
  	public void collectionStarted(long targetId, Long userId, String username, String shortCode) {
  		Instant now = Instant.now();
  		record(targetId, userId, AlarmEventType.COLLECTION_STARTED,
  				basePayload(username, shortCode), now, DispatchLane.morning(now));
  	}
  ```

- [ ] 호출부 정합 — 두 파일을 고친다.
  - `monitoring/src/main/java/com/celfit/monitoring/service/RegistrationService.java` 131행:
    `alarms.collectionStartedImmediate(id, cmd.userId(), post.username(), shortCode);`
    → `alarms.collectionStarted(id, cmd.userId(), post.username(), shortCode);`
    바로 위 128행 주석의 `alarms.collectionStartedImmediate는`도 `alarms.collectionStarted는`으로 바꾼다.
  - `monitoring/src/main/java/com/celfit/monitoring/service/DailySweepJob.java` 429행:
    `alarms.collectionStartedScheduled(t.id(), t.userId(), t.username(), detected.shortCode());`
    → `alarms.collectionStarted(t.id(), t.userId(), t.username(), detected.shortCode());`

- [ ] 즉시 레인 제거 — `monitoring/src/main/java/com/celfit/monitoring/alarm/DispatchLane.java`를 아래 전문으로 교체하고, `monitoring/src/test/java/com/celfit/monitoring/alarm/DispatchLaneTest.java`에서 `immediate`를 검증하는 테스트(43행 부근)를 삭제한다.
  ```java
  package com.celfit.monitoring.alarm;

  import java.time.Instant;
  import java.time.LocalTime;
  import java.time.ZoneId;

  /**
   * 발송 레인 — 이벤트의 {@code dispatch_after}를 정한다.
   *
   * <p>2026-08-27 주간 개편으로 <b>레인은 하나만 남았다</b>(설계 §2 즉시 레인 폐지). 소비는
   * was의 주간 다이제스트 잡뿐이고 그 잡은 {@code occurred_at}의 주만 본다 - dispatch_after는
   * 이제 어느 소비자도 읽지 않지만 컬럼이 NOT NULL이라 값은 계속 채워야 한다(컬럼 정리는
   * expand-contract상 별도 릴리스).
   *
   * <p>아침 레인은 <b>적재 시점 기준 당일 09:00 KST</b>다. 이미 지났으면 그 시각을 그대로 저장한다.
   */
  public final class DispatchLane {

  	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  	private static final LocalTime MORNING = LocalTime.of(9, 0);

  	private DispatchLane() {
  	}

  	public static Instant morning(Instant occurredAt) {
  		return occurredAt.atZone(KST).toLocalDate().atTime(MORNING).atZone(KST).toInstant();
  	}
  }
  ```

- [ ] 발송 스택 삭제
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign rm \
    monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmDispatchJob.java \
    monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmDispatchScheduler.java \
    monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmMailComposer.java \
    monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmRecipientReader.java \
    monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEmailStatus.java \
    monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEvent.java \
    monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmDispatchJobTest.java \
    monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmDispatchSchedulerTest.java \
    monitoring/src/test/java/com/celfit/monitoring/alarm/AlarmMailComposerTest.java
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign rm -r monitoring/src/main/java/com/celfit/monitoring/mail
  ```

- [ ] 저장소 축소 — `monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmEventRepository.java`를 아래 전문으로 교체한다(발송 대상 조회·상태 종결은 소비자가 사라졌다).
  ```java
  package com.celfit.monitoring.alarm;

  import java.sql.Timestamp;
  import java.time.Instant;
  import org.springframework.jdbc.core.JdbcTemplate;
  import org.springframework.stereotype.Repository;

  /**
   * alarm_event 테이블 접점 — <b>적재 전용</b>이다.
   *
   * <p>2026-08-27 주간 개편으로 발송 대상 조회(findDue)와 행 단위 상태 종결(updateStatus)은
   * 소비자가 사라졌다: 메일 발송이 was의 주간 리포트로 옮겨갔고, was는 이 원장을
   * {@code occurred_at} 기간 조회로만 읽는다(설계 §4·§6). {@code email_status}·
   * {@code email_attempts}·{@code dispatch_after} 컬럼은 expand-contract상 남겨 둔다.
   */
  @Repository
  public class AlarmEventRepository {

  	private final JdbcTemplate db;

  	public AlarmEventRepository(JdbcTemplate db) {
  		this.db = db;
  	}

  	/**
  	 * occurredAt을 애플리케이션에서 명시해 싣는다(테이블 DEFAULT now()에 맡기지 않는다) —
  	 * 주간 창 판정이 이 값의 KST 주에 달려 있어, DB 클록과 JVM 클록이 갈리면 경계 이벤트가
  	 * 어느 주에 속하는지 재현 불가능해진다.
  	 */
  	public long insert(long targetId, long userId, AlarmEventType type, String payloadJson,
  			Instant occurredAt, Instant dispatchAfter) {
  		return db.queryForObject("""
  				INSERT INTO alarm_event (target_id, user_id, event_type, payload, occurred_at, dispatch_after)
  				VALUES (?, ?, ?, ?::jsonb, ?, ?)
  				RETURNING id""",
  				Long.class, targetId, userId, type.name(), payloadJson,
  				Timestamp.from(occurredAt), Timestamp.from(dispatchAfter));
  	}
  }
  ```

- [ ] Clock 빈 소비자 확인 후 정리 — 남은 소비자가 없으면 `AlarmConfig`도 지운다.
  ```
  grep -rn "Clock" --include='*.java' monitoring/src/main/java
  ```
  기대 출력: `AlarmConfig.java`만 매치. 그 경우:
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign rm monitoring/src/main/java/com/celfit/monitoring/alarm/AlarmConfig.java
  ```
  다른 파일이 매치되면 `AlarmConfig`는 그대로 두고 클래스 Javadoc의 "발송 잡의 디바운스·due 판정" 문장만 "테스트가 시각을 고정하기 위한 공용 Clock"으로 고친다.

- [ ] 설정 정리 — `monitoring/src/main/resources/application.yml`의 `alarm:` 블록(127~136행)과 `mail:` 블록(137~139행)을 통째로 지운다. `monitoring.alarm.*`·`monitoring.mail.*` 키는 남은 코드가 하나도 읽지 않는다.

- [ ] 잔여 참조 확인
  ```
  grep -rn "AlarmDispatch\|AlarmMailComposer\|AlarmRecipientReader\|AlarmEmailStatus\|celfit.monitoring.mail\|collectionStartedImmediate\|collectionStartedScheduled\|DispatchLane.immediate" --include='*.java' --include='*.yml' monitoring/src was/src
  ```
  기대 출력: 매치 없음(exit code 1).

- [ ] 실행해 통과 확인
  ```
  ./gradlew :monitoring:test
  ```
  기대 출력: `BUILD SUCCESSFUL`. `MigrationTest`는 `alarm_event` DDL을 그대로 검증하므로 영향이 없어야 한다.

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add -A monitoring
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  feat(monitoring): 이메일 디스패처·즉시 레인 폐지

  5분 틱 디스패처·디바운스·수신자 조회·Resend 사본을 전부 제거하고 alarm_event는
  적재 전용으로 축소했다. 수집 시작 진입점도 아침 레인 하나로 합쳤다.
  발송은 was 주간 리포트가 담당한다(설계 §2·§6).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 12: 운영 설정 변경(compose·README)

코드와 분리된 배포 설정 태스크(설계 §7 "이메일 디스패처 크론 등 운영 compose 설정 변경 동반"). **여기서 실제 배포는 하지 않는다** — 파일 변경만 하고 승격은 정규 CD 경로(develop→staging→main)로 간다.

**Files:**
- Modify: `deploy/compose.yaml` (was 서비스 env, monitoring 서비스 328~336행)
- Modify: `deploy/compose.test.yaml` (was 서비스 env, monitoring 서비스 192~200행)
- Modify: `deploy/README.md` (682~703행, 734~735행)

**Steps:**

- [ ] `deploy/compose.yaml` monitoring 서비스 정리 — 328~336행의 알람 발송 관련 env 6개(`MONITORING_ALARM_DISPATCH_CRON`, `RESEND_API_KEY`, `MONITORING_ALARM_READER_URL`, `MONITORING_ALARM_READER_USERNAME`, `MONITORING_ALARM_READER_PASSWORD`, `MONITORING_ALARM_ALLOWED_RECIPIENTS`)와 그 위 설명 주석을 지우고, 그 자리에 아래 주석 한 줄을 남긴다.
  ```yaml
        # 알람 메일 발송은 2026-08-27 주간 개편으로 was(주간 리포트)로 이관됐다 - monitoring은 alarm_event 적재만 한다.
  ```

- [ ] `deploy/compose.yaml` was 서비스에 딥링크 기준 주소 추가 — `RESEND_API_KEY: ${RESEND_API_KEY:-}`(245행) 바로 아래에 추가한다.
  ```yaml
        WEB_BASE_URL: ${WEB_BASE_URL:-https://hypenow.io}   # 주간 리포트 메일 딥링크 기준 주소
  ```

- [ ] `deploy/compose.test.yaml`도 같은 방식으로 고친다 — monitoring 서비스의 192~200행 `DEV_ALARM_*` env 블록 전체를 지우고 위와 같은 주석 한 줄을 남긴다. was 서비스의 `RESEND_API_KEY` 아래에 아래를 추가한다.
  ```yaml
        WEB_BASE_URL: ${DEV_WEB_BASE_URL:-https://dev.hypenow.io}   # 주간 리포트 메일 딥링크 기준 주소(스테이징)
  ```

- [ ] `deploy/README.md` 알람 발송 절 갱신 — 682~703행의 개통 절차(발송 크론 켜기·허용목록·`DEV_ALARM_*`)와 734~735행의 "알람 발송은 컨테이너 env `MONITORING_ALARM_DISPATCH_CRON`" 문단을 아래로 교체한다.
  ```markdown
  4. 주간 리포트 메일은 **was**가 보낸다(2026-08-27 주간 개편). monitoring의 5분 틱 디스패처와
     `MONITORING_ALARM_*`·`alarm_reader` 롤은 폐지됐다 - monitoring은 `alarm_event` 적재만 한다.
     - 발송 스케줄: 매주 월요일 09:00 KST 다이제스트 생성 직후(따라잡기 09:10~23:50, 10분 간격).
       크론은 was env `MONITORING_DIGEST_WEEKLY_CRON`·`MONITORING_DIGEST_WEEKLY_CATCHUP_CRON`로 덮을 수 있다.
     - 발송 계정: was의 `RESEND_API_KEY`(이미 배선돼 있다). test 환경의 실사용자 오발송 방지는
       기존 `WAS_MAIL_ADMIN_ONLY=true`(ADMIN 수신자만 실발송)가 그대로 담당한다.
     - 딥링크 기준 주소: `WEB_BASE_URL`(운영 https://hypenow.io, 스테이징 `DEV_WEB_BASE_URL`).
     - 임시 중단: `MONITORING_DIGEST_WEEKLY_CRON`을 `"-"`로 두고 재기동한다. 인앱 다이제스트까지
       함께 멈추므로(생성과 발송이 같은 잡이다) 재개하면 그 주 창을 다시 집계해 만들어 낸다.
     - 수신 해지: 사용자가 `PATCH /v1/notification-settings {"weeklyEmail": false}`로 끈다
       (구 4종 매트릭스는 폐지, 기존 옵트아웃은 하나라도 꺼져 있으면 off로 이관됐다).
  ```
  같은 파일 676~679행의 `alarm_reader` 롤 생성 안내에는 아래 한 줄을 덧붙인다(운영 롤 회수는 별도 작업이라 여기서 실행하지 않는다).
  ```markdown
      > 2026-08-27 주간 개편으로 `alarm_reader` 롤은 더 이상 쓰이지 않는다. 롤 회수(REVOKE·DROP ROLE)는
      > 운영 배포가 끝난 뒤 별도 작업으로 처리한다 - 롤링 창에서 구버전 monitoring이 아직 붙어 있다.
  ```

- [ ] 잔여 참조 확인
  ```
  grep -rn "MONITORING_ALARM\|DEV_ALARM" deploy/
  ```
  기대 출력: 매치 없음(exit code 1). `alarm_reader`는 위 안내 문단에만 남는다.

- [ ] 커밋
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add deploy
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  chore(deploy): 알람 발송 크론 제거, 주간 리포트 딥링크 주소 추가

  monitoring의 MONITORING_ALARM_* env를 전부 걷어내고 was에 WEB_BASE_URL을 넣었다.
  README의 발송 개통 절차도 주간 리포트 기준으로 갱신했다(설계 §7).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## 마무리: 전체 검증과 문서 갱신

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 행 추가)
- Move: `docs/superpowers/specs/2026-08-27-weekly-notification-digest-design.md` → `docs/superpowers/specs/archive/`
- Move: `docs/superpowers/plans/2026-08-27-weekly-notification-digest.md` → `docs/superpowers/plans/archive/`

**Steps:**

- [ ] 전체 테스트 1회 — PR 직전 단 한 번만 돈다(모듈 4개가 각자 Testcontainers를 띄우므로 로컬 자원 경합이 크다. `colima stop && colima start --cpu 8 --memory 12`가 선행돼야 한다).
  ```
  ./gradlew test
  ```
  기대 출력: `BUILD SUCCESSFUL`. 실패가 대량이면 먼저 `echo $DOCKER_HOST`부터 확인한다(미설정이 대량 실패의 가장 흔한 원인).

- [ ] 마이그레이션 안전 가드 로컬 확인
  ```
  ./.github/scripts/check-migration-safety.sh
  ```
  기대 출력: 새 마이그레이션이 파괴적 구문·미래 채번·역전 없음으로 통과. 스크립트가 base 브랜치를 요구하면 `develop` 기준으로 돈다.

- [ ] `DECISIONS.md` 맨 위에 아래 행을 추가한다.
  ```markdown
  | 2026-08-27 | 알림을 주간 1건으로 통일 | 일일·즉시 레인 폐지, 매주 월 09:00 KST 지난주 요약 1건(인앱+메일). 브랜드 새 게시물 발견·등록 게시물 광고 미표기 판정을 신규 섹션으로 추가. 알림 설정은 주간 이메일 토글 1개로 축소(FE 통지 필요). 메일 발송은 monitoring에서 was로 이관. [설계](docs/superpowers/specs/archive/2026-08-27-weekly-notification-digest-design.md) |
  ```

- [ ] 완료 문서 아카이브 — 스펙과 계획을 같은 커밋에서 옮기고, 이 두 문서를 가리키는 링크를 고친다.
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign mv docs/superpowers/specs/2026-08-27-weekly-notification-digest-design.md docs/superpowers/specs/archive/2026-08-27-weekly-notification-digest-design.md
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign mv docs/superpowers/plans/2026-08-27-weekly-notification-digest.md docs/superpowers/plans/archive/2026-08-27-weekly-notification-digest.md
  grep -rn "2026-08-27-weekly-notification-digest" --include='*.md' . | grep -v "/archive/"
  ```
  마지막 grep이 매치를 내면 그 링크를 `archive/` 경로로 고친다(아카이빙이 링크를 조용히 깨뜨린 전력이 있다). 두 문서의 상태 헤더도 `> 상태: ✅ 구현됨`으로 바꾼다.

- [ ] 커밋과 push (PR은 열지 않는다 — 사용자 승인 후에만)
  ```
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign add -A
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign commit -m "$(cat <<'EOF'
  docs: 주간 알림 다이제스트 개편 결정 기록 + 완료 문서 아카이브

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  git -C /Users/woomin/Project/hypenow-backend/.worktrees/notification-weekly-redesign push -u origin HEAD
  ```

---

## 스펙 ↔ 태스크 매핑(셀프 리뷰 체크리스트)

| 설계 요구 | 담당 태스크 |
|---|---|
| §2 주간 통일, 월 09:00 KST, 지난주(월~일) 1건 | 1, 8 |
| §2 이벤트 0건이면 그 주 미생성 | 6(빈 목록), 8(빈 목록이면 upsert 스킵) |
| §2 신규 이벤트 ① 등록 게시물 광고 미표기 | 4(조회), 8(등록 원장 대조·킬 스위치·1회 가드) |
| §2 신규 이벤트 ② 브랜드 새 게시물 발견 | 4(조회), 8(태그+해시태그 병합) |
| §2 기존 4종을 주간 리듬으로 | 8(`findAlarmEventsBetween` 주간 창) |
| §2 미표기 범위 = 등록 게시물만 | 8(`brandDirectPosts.shortCodesByUser` 교집합) |
| §3 섹션 3개, 내용 있는 섹션만 노출 | 6 |
| §3 하이라이트 1건(조회수 우선, 없으면 좋아요) | 6 |
| §3 섹션별 합산 한 줄, 개별 나열 없음 | 6, 10 |
| §3 피드 조회수 NULL 규칙, 전부 NULL이면 숫자 제외 | 6(합산), 10(문안) |
| §3 캠페인 이름 문맥 | 5(조회), 6(문안) |
| §3 items jsonb 확장 | 6(`DigestItem`), 7(`DigestResponse.Item`) |
| §4 주간 조회 파이프라인(이벤트 원장 없이) | 4, 8 |
| §4 (user, 주 시작일) 멱등 upsert | 8 |
| §4 크로스 DB 읽기 전용 롤 패턴 | 4(monitoring-ro JdbcClient 재사용), 8(조합은 was 코드) |
| §4 `dispatch_after` 레인 구분 정리 | 11 |
| §5 `/v1/notifications` 계약 유지 | 7(계약 유지 테스트 3개) |
| §5 알림 설정 = 주간 이메일 토글 1개 | 9 |
| §6 주간 리포트 메일 1통, 생성 직후 발송 | 10 |
| §6 5분 틱·디바운스 제거 | 11 |
| §6 유저 단위 격리·재시도 상한·at-least-once | 10 |
| §6 정식 문안 + 딥링크, 엠대시 금지 | 10 |
| §7 `monitoring_digests` 재사용, digest_date = 주 시작일 | 2, 8 |
| §7 옵트아웃 보수적 이관 | 2 |
| §7 운영 compose 설정 변경 | 12 |
| §8 미표기 재판정 중복 가드 | 2(테이블), 3(저장소), 8(적용) |
| §8 주간 경계는 명시적 기간 파라미터 | 1, 8 |
| §8 킬 스위치 정합(잡 레벨 게이트) | 8 |
| §8 발송 시각 집중 | 10(발송 간 최소 간격) |
| §9 비범위(공지·성과 리포트·워치리스트·읽음 모델 통합·푸시) | 어느 태스크도 건드리지 않음 |

