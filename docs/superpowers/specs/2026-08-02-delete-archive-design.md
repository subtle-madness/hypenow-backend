# 삭제 데이터 아카이브 — 설계

> 상태: 🟢 활성
> 트랙: NN · 작성일 2026-08-02

## 1. 배경

was의 삭제 로직은 사실상 전부 hard delete다. 사용자가 만든 데이터가 삭제되는 순간
복구 경로 없이 사라지고, 그중 상당수는 서비스 개선에 쓸 수 있는 행동 시그널이다.

**목표**: 삭제되는 행을 원본 그대로 축적한다. 집계·요약이 아니라 행 단위 보존이다.

**비목표**: 사용자에게 "복구" 기능을 제공하는 것. 이번 트랙은 축적까지만 하고,
불필요 데이터 정리 배치와 복원 도구는 후속이다.

### 현황 (2026-08-02 조사)

| 방식 | 대상 |
|---|---|
| hard delete | `users` 탈퇴, `saved_contents`/`saved_influencers`, `monitoring_campaigns`, `monitoring_items`(등록 실패 롤백), crawler `app_setting`·`search_keyword` |
| soft delete(상태 전이) | `monitoring_items` 취소(`canceled_at`), monitoring 모듈 `target`(`TargetStatus`) |
| 재적재(삭제 아님) | analytics `MirrorJob` TRUNCATE, `comment_classifications` DELETE+재삽입 |

`is_deleted`/`deleted_at` 같은 범용 소프트델리트 컬럼은 저장소 어디에도 없다.
`gate_events.user_id`는 FK 없는 논리 참조로 "탈퇴 후에도 이벤트 보존"이 이미
의도돼 있다(V5 주석) — 자산 보존 선례가 하나 존재하는 셈이다.

## 2. 결정 — 전면 soft delete 대신 아카이브 이관

최초 검토안은 "모든 테이블을 soft delete로 전환"이었으나 기각했다.

**기각 사유**

- was 조회는 JdbcClient 생 SQL이라 ORM 글로벌 필터 같은 안전망이 없다. 모든 SELECT에
  `deleted_at IS NULL`을 손으로 붙여야 하고, **하나만 누락되면 삭제한 데이터가 사용자에게
  다시 보인다.** 컴파일러가 못 잡는 결함이다.
- `users.email` UNIQUE 때문에 탈퇴 이메일로 재가입이 막힌다 → partial unique index 재작업.
- `ON DELETE CASCADE` 5종이 soft delete에선 동작하지 않는다 → 연쇄 처리를 전부 코드로
  재구현해야 한다.
- expand-contract 제약상 컬럼 추가 → 코드 배포 → 필터 적용이 릴리스 여러 번으로 쪼개진다.

**채택안**: 삭제 직전 같은 트랜잭션으로 아카이브 테이블에 이관하고, 원본 삭제는
**지금 그대로 둔다.** 라이브 조회 경로가 한 줄도 바뀌지 않으므로 위 결함이 원리적으로
발생하지 않고, 작업량이 삭제 지점 수에만 비례한다.

### 아카이브 방식 선택 — 코드 명시(A) vs DB 트리거(B)

| | A 코드 명시 (채택) | B 트리거 |
|---|---|---|
| 새 테이블 등록 누락 | 인벤토리 테스트가 차단 | 인벤토리 테스트가 차단 (동일) |
| 코드 경로 삭제 | 잡힘 | 잡힘 |
| 운영 중 수동 SQL 삭제 | **못 잡음** | 잡힘 |
| 삭제 이유 기록 | 자연스러움 | `SET LOCAL` 우회 필요 |
| 디버깅 | 코드에 다 보임 | 숨은 동작 |

트리거가 "원리적으로 누락 불가능"한 것은 아니다 — 새 테이블에 트리거를 거는 것을
잊으면 A와 똑같이 샌다. 등록 누락 리스크는 동일하고, 이는 §7 인벤토리 테스트로 막는다.
남는 차이는 코드 밖 삭제를 잡느냐인데, 운영 DB 수동 DELETE는 상시 경로가 아니므로
프로젝트에 없던 트리거 관용구를 도입하는 인지 비용이 이득보다 크다고 판단했다.

`ON DELETE CASCADE`를 제거하고 전부 코드로 삭제하는 3안은 FK 변경이 파괴적 변경이라
`migration-guard`에 걸리고 릴리스가 여러 번으로 쪼개져 기각했다.

## 3. 데이터 모델

`app` 외 스키마가 없던 was에 `archive` 스키마를 신설한다. 같은 DB여야 이관과 삭제가
한 트랜잭션에 묶이고, 스키마를 분리해두면 축적량이 커졌을 때 통째로 옮기기 쉽다.

```sql
CREATE SCHEMA IF NOT EXISTS archive;

CREATE TABLE archive.archived_rows (
    id              bigserial PRIMARY KEY,
    table_name      text        NOT NULL,   -- 'app.saved_contents'
    row_pk          jsonb       NOT NULL,   -- {"user_id":12,"short_code":"ABC"}
    user_id         bigint,                 -- 조회 편의용 승격 (NULL 가능)
    payload         jsonb       NOT NULL,   -- to_jsonb(원본 행) 전체
    archived_at     timestamptz NOT NULL DEFAULT now(),
    archived_reason text        NOT NULL
);

CREATE INDEX idx_archived_rows_table_time ON archive.archived_rows (table_name, archived_at);
CREATE INDEX idx_archived_rows_user       ON archive.archived_rows (user_id) WHERE user_id IS NOT NULL;
```

**단일 범용 테이블을 택한 이유**: 원본 테이블별 미러 테이블은 원본 컬럼이 추가·변경될
때마다 아카이브 DDL이 따라가야 하고, 안 따라가면 조용히 컬럼이 유실된다. expand-contract
하에서 스키마가 자주 움직이는 현 상황에서는 이 부담이 계속 붙는다. `payload jsonb`는
원본 스키마가 어떻게 바뀌든 **그 시점의 행을 있는 그대로** 담으므로 "원본 행 보존"이라는
목표에 더 충실하고, 대상 테이블 추가에 마이그레이션이 필요 없다.

**`row_pk`가 jsonb인 이유**: `app` 스키마에 복합 PK가 많다 — `saved_contents(user_id,
short_code)`, `saved_influencers(user_id, handle)`, `monitoring_email_opt_outs(user_id,
event_type)`, `monitoring_registration_entries(registration_id, seq)`. 단일 컬럼으로 담을
수 없다. `inquiries`는 `app` 유일의 uuid PK다(이번 범위 밖이지만 기록해둔다).

`payload` GIN 인덱스는 실제 분석 쿼리 패턴이 나온 뒤에 붙인다.

`archived_reason` 값(문자열 상수, enum 테이블 두지 않음):

| 값 | 경로 |
|---|---|
| `ACCOUNT_DELETION` | 회원 탈퇴 |
| `SAVED_REMOVED` | 저장 개별 해제 |
| `CAMPAIGN_DELETED` | 캠페인 삭제 |
| `REGISTRATION_ROLLBACK` | 모니터링 등록 실패 롤백 |

## 4. 이관 대상

### 4-1. 탈퇴 경로 — 테이블 9개

`users` 삭제로 사라지는 테이블은 8개이며, 그중 하나는 **2단계 연쇄**라 직접 FK만 훑으면
놓친다.

| 테이블 | 삭제 원인 | ON DELETE | 근거 |
|---|---|---|---|
| `saved_influencers` | 코드가 명시 삭제 | NO ACTION | V1:11, `UserRepository.deleteAccount` |
| `saved_contents` | 코드가 명시 삭제 | NO ACTION | V1:23, 동일 |
| `monitoring_campaigns` | CASCADE | CASCADE | V16:10 |
| `monitoring_email_opt_outs` | CASCADE | CASCADE | V15:5 |
| `monitoring_items` | CASCADE | CASCADE | V16:28 |
| `monitoring_registrations` | CASCADE | CASCADE | V16:52 |
| `monitoring_digests` | CASCADE | CASCADE | V16:78 |
| `monitoring_registration_entries` | **간접 CASCADE** — `registration_id → monitoring_registrations → users` | CASCADE | V16:57 |
| `users` | 코드가 삭제 | — | V1 |

`signup_codes.used_by`는 `ON DELETE SET NULL`(V8:8)이라 행이 남는다. 다만 탈퇴하면
**"이 가입 코드를 누가 썼는가"가 끊긴다.** 이번 아카이브 대상은 아니지만 이력이 유실되는
지점이므로 기록해둔다. `gate_events.user_id`는 FK가 없어 영향 없다.

**순서**: 자식 8개를 먼저 이관 → `users` 이관 → `DELETE FROM app.users`.
실제 삭제는 지금처럼 CASCADE가 수행하고, 삭제 로직 자체는 손대지 않는다.

기존 탈퇴 순서 계약(외부 monitoring target 해지 → DB 삭제, `AccountDeletionService`
주석)은 그대로 유지한다. 아카이브는 그 뒤 DB 삭제 직전에 끼어든다.

### 4-2. 나머지 3개 경로

| 경로 | 대상 | 위치 |
|---|---|---|
| 저장 해제 | `saved_contents` 또는 `saved_influencers` 1행 | `SavedRepository`, `V1SavedRepository` |
| 캠페인 삭제 | `monitoring_campaigns` 1행 | `CampaignRepository.delete` |
| 등록 실패 롤백 | `monitoring_items` 1행 | `MonitoringItemRepository.delete` |

캠페인 삭제 시 `monitoring_items.campaign_id`는 `SET NULL`이라 item은 남는다 —
아카이브 대상 아님.

### 4-3. `app` 스키마 전체 분류 (17개)

§7-1 인벤토리 테스트가 이 표를 코드로 강제한다. 모든 테이블이 둘 중 하나여야 한다.

| 테이블 | 분류 | 사유 |
|---|---|---|
| `users` | ARCHIVED | 탈퇴 (가명화) |
| `saved_influencers` | ARCHIVED | 저장 해제 + 탈퇴 |
| `saved_contents` | ARCHIVED | 저장 해제 + 탈퇴 |
| `monitoring_campaigns` | ARCHIVED | 캠페인 삭제 + 탈퇴 |
| `monitoring_items` | ARCHIVED | 등록 롤백 + 탈퇴 |
| `monitoring_registrations` | ARCHIVED | 탈퇴 |
| `monitoring_registration_entries` | ARCHIVED | 탈퇴 (간접 CASCADE) |
| `monitoring_digests` | ARCHIVED | 탈퇴 |
| `monitoring_email_opt_outs` | ARCHIVED | 탈퇴. 단 `optIn` 경로의 DELETE는 삭제가 아니라 알림 재활성화이므로 아카이브하지 않는다 |
| `spring_session` | EXCLUDED | 세션 토큰. 자산 가치 없음 |
| `spring_session_attributes` | EXCLUDED | 위와 동일 |
| `gate_events` | EXCLUDED | 삭제 경로 없음. FK도 없어 탈퇴에도 보존된다(V5 주석의 의도) |
| `app_setting` | EXCLUDED | was 런타임 설정값 |
| `email_verifications` | EXCLUDED | 만료성 인증 코드 |
| `signup_codes` | EXCLUDED | 삭제 경로 없음(`used_by`가 SET NULL로 끊길 뿐) |
| `signup_events` | EXCLUDED | 삭제 경로 없음 |
| `inquiries` | EXCLUDED | 삭제 경로 없음 |

**타 모듈 제외** (인벤토리 테스트 범위 밖, 판단만 기록)

| 대상 | 제외 사유 |
|---|---|
| analytics `MirrorJob` 대상 전체 | 정기 재적재. 아카이브하면 매 실행마다 수십만 행이 쌓인다 |
| analytics `comment_classifications` | 재분류 시 DELETE+재삽입. 위와 동일 |
| crawler `app_setting`, `search_keyword` | 운영 설정값. 자산 가치 없음 |

프로필 이미지 파일은 DB 밖 자원이고 탈퇴 트랜잭션과 원자성이 없다(커밋 후 삭제).
이번 범위 밖이다.

## 5. 구현 방식

`ArchiveWriter` 컴포넌트 하나가 전담한다. 행을 애플리케이션으로 끌어올리지 않고
`INSERT … SELECT`로 DB 안에서 끝낸다.

```sql
INSERT INTO archive.archived_rows (table_name, row_pk, user_id, payload, archived_reason)
SELECT 'app.saved_contents',
       jsonb_build_object('user_id', t.user_id, 'short_code', t.short_code),
       t.user_id,
       to_jsonb(t),
       :reason
  FROM app.saved_contents t
 WHERE t.user_id = :userId;
```

### 가명화

`users`만 특별 취급한다. 행은 보존하되 직접 식별 컬럼을 payload에서 제거한다.

```sql
to_jsonb(t) - 'email' - 'password_hash' - 'name' - 'profile_image_path'
```

`id`는 남기므로 자식 행과의 조인이 살아 있다. 즉 **익명화가 아니라 가명화**이며,
아카이브 내부에서 유저 단위 행동 분석이 가능하다. 실제 제거 대상 컬럼 목록은 구현
시점의 `users` 스키마로 확정한다(V3 프로필 필드, V9 usage_purpose, V11 role 포함 여부
판단 필요 — role·usage_purpose는 식별 정보가 아니므로 보존이 기본).

## 6. 오류 처리

**fail-closed** — 아카이브 INSERT가 실패하면 트랜잭션이 롤백되고 삭제도 일어나지 않는다.
자산 보존이 목적인데 조용히 유실되면 의미가 없기 때문이다.

대가는 아카이브 테이블 장애 시 탈퇴·저장해제가 막힌다는 것이다. 같은 DB 같은 트랜잭션이라
"아카이브만 죽는" 상황은 디스크 고갈 정도로 한정되며, 그 경우 서비스 쓰기 전반이 이미
불가능하다. 따라서 실질 가용성 손실은 없다고 본다.

이는 탈퇴 경로의 기존 fail-open 결정(외부 monitoring 해지 실패를 무시하고 진행)과 방향이
다르다. 외부 API는 우리 통제 밖이라 fail-open이 맞고, 같은 트랜잭션 내 DB 쓰기는
fail-closed가 맞다.

## 7. 가드 테스트

누락을 사람의 기억이 아니라 CI가 막는 것이 이 설계의 핵심이다.

**7-1. 분류 완전성** — `information_schema`에서 `app` 스키마 테이블 전체를 읽어,
각 테이블이 `ARCHIVED` 집합 또는 `EXCLUDED`(사유 포함 맵) 중 하나에 등재돼 있는지
검사한다. 미분류가 하나라도 있으면 실패하며, 실패 메시지가 어디에 무엇을 추가해야
하는지 알려준다. 새 테이블을 만들고 마이그레이션을 올리는 순간 CI가 깨진다.

**7-2. CASCADE 도달성(재귀)** — `users`에서 `ON DELETE CASCADE`를 타고 도달 가능한
모든 테이블을 재귀로 수집해, 전부 탈퇴 아카이브 대상인지 검사한다. 이번에 놓쳤던
`monitoring_registration_entries` 같은 간접 연쇄를 잡는 테스트다. **직접 FK만 보면 안 된다.**

**7-3. 경로별 통합 테스트** — 4개 경로 각각에 대해 삭제 후:
- `archived_rows`에 기대한 행 수가 있고
- `payload`가 삭제 전 원본과 일치하며
- `users`의 `payload`에는 식별 컬럼이 없고 `id`는 있다

## 8. 마이그레이션·배포

Flyway 파일은 UTC 타임스탬프 채번(`V<YYYYMMDDHHMMSS>__delete_archive.sql`). was의
현재 최신은 `V20260730160000__monitoring_registrations_acknowledged_at.sql`이다.

expand-contract 원칙상 2단계로 나눈다.

1. **릴리스 1** — `archive` 스키마 + `archived_rows` 생성만. 순수 추가라 롤링 중
   구 코드와 충돌 없음
2. **릴리스 2** — `ArchiveWriter` 및 4개 경로 배선. 롤링 창에서 구 인스턴스가 처리한
   삭제분은 아카이브되지 않는다(수용 — 소급 복구 불가, 창이 짧음)

## 9. 범위 밖 (후속 트랙)

- 불필요 데이터 정리 배치 잡
- 아카이브 → 라이브 복원 도구
- crawler·analytics 삭제 지점 아카이브
- 아카이브 데이터 분석 표면(어드민 조회 등)

## 10. 리스크·미해결

| 항목 | 내용 | 대응 |
|---|---|---|
| Flyway 스키마 설정 | `app`이 유일 스키마였으므로 `CREATE SCHEMA archive`가 Flyway 설정·DB 권한상 통과하는지 미확인 | 구현 착수 시 최우선 확인. 불가하면 `app.archived_rows`로 후퇴 |
| V1 테이블 스키마 한정자 부재 | `users`/`saved_*` DDL에 `app.` 접두사가 없다(V2부터는 명시). 실질 `app` 소속임은 이후 파일들이 `app.users`로 참조하는 것으로 확인되나 원인 미규명 | 조사만. 이번 트랙에서 건드리지 않는다 |
| 디스크 증가 | 서비스 DB에 무한 축적. 오라클 서버 디스크가 한 번 찬 이력 있음 | 별도 스키마라 분리는 쉬움. 크기 모니터링 필요 |
| 개인정보 보유 | 가명화해도 탈퇴 유저의 행동 데이터를 보유한다. 파기 의무와의 관계는 법률 판단 영역 | 법무 확인 권고. 확인 전까지는 이 설계가 최소 보유안 |
| 롤링 창 유실 | 릴리스 2 배포 중 구 인스턴스 처리분은 아카이브되지 않음 | 수용 |
