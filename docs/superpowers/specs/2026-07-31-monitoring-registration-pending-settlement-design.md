# 모니터링 등록 entry 영구 pending 정산 — 설계

> 상태: 🟢 활성
> 트랙: LL · 작성일 2026-07-31

## 1. 문제

유저가 모니터링 등록을 취소하면 `app.monitoring_registration_entries.result`가 `pending`에
영구히 갇힌다. 운영 실측(2026-07-31 조회, `registration id=2` / `user_id=6` / 입력
`hince_official` / kind `account`):

| 시각(KST) | 사건 |
|---|---|
| 07-30 19:37:24 | 등록 요청 → `monitoring_items` id=2 생성, entry `result='pending'` |
| 07-30 19:44:59 | 유저 취소 → `canceled_at` 세팅, `canceled_from='detecting'` |
| 07-30 19:46:23 | 같은 계정 재등록 → item id=3, 정상 성공 |

item은 취소됐는데 entry는 `pending` 그대로다. 조회 시점 기준 11시간 36분째.

**파급**

- `RegistrationRepository.markCompletedIfAllSettled()`는 pending entry가 하나라도 있으면
  `completed_at`을 안 채운다 → `monitoring_registrations.completed_at`이 영구 NULL
- 유저의 등록 내역 화면에서 이 등록이 영원히 "처리 중"으로 보인다
- `RecoverStalePendingScheduler`는 `monitoring_items` 기준(`findPendingOlderThan`이
  `canceled_at IS NULL`로 필터)이라 **취소된 item은 복구 대상이 아니어서** 자동 복구도 안 된다

## 2. 근본 원인 — 누락이 아니라 어휘 부재

이 구멍은 실수로 빠진 게 아니라 의도적으로 남겨졌다.
`MonitoringRegistrationExecutor.processItem()`에 주석으로 명시돼 있다:

> entry는 success/failed/duplicate 어휘에 "취소" 개념이 없어 원상태(pending) 그대로 둔다

즉 `result` 어휘에 취소를 표현할 값이 없다는 것이 근본 원인이고, 취소 API 경로
(`V1MonitoringItemUpdateService.cancel()`)는 entry를 아예 건드리지 않는다.

**갇히는 경로 3종**

1. `cancel()` → `markCanceled()`만 하고 entry 무처리
2. 실행기 `processItem()`의 취소 감지 분기 → 로그만 남기고 return
3. `findPendingOlderThan`이 취소된 item을 제외 → 복구 대상에서 탈락

### 2-1. 같은 뿌리의 더 나쁜 구멍 (이번 조사에서 신규 발견)

share 링크 entry가 `resolveShare` 단계에서 `MonitoringUnavailableException`으로 끝나면
**`monitoring_items` 행 자체가 아직 없다**. `recoverStalePending()`은 item 기준이므로 이 entry는
원리적으로 복구 대상이 될 수 없다 — 취소 케이스와 달리 재시도 경로가 **아예 존재하지 않는**
영구 pending이다. 아직 운영에서 터지지 않았을 뿐이다.

같은 계열로, `process()`의 미분류 `RuntimeException`과 `recoverItem()`의 무한 replay에도
종결 조건이 없다.

### 2-2. 세 번째 구멍 — target은 붙었는데 entry가 pending (설계 리뷰 중 발견)

`MonitoringRegistrationExecutor`에는 `@Transactional`이 없어 리포지토리 호출마다 개별 커밋된다.
따라서 `confirmTarget()`과 `updateEntryResult(SUCCESS)`는 **서로 다른 트랜잭션**이고, 그 사이에
프로세스가 죽으면 **target은 붙었는데 entry는 `pending`**인 행이 남는다. `findPendingOlderThan`은
`target_id IS NULL`로 필터하므로 이 행은 복구 대상에서도 빠진다 — 또 하나의 영구 pending이다.

이 구멍은 §4-3의 설계를 바꾼다. **나이만 보고 일괄 `failed`로 확정하면 실제로는 등록에 성공한
건을 실패로 거짓 보고하게 된다.** 스윕은 나이가 아니라 item 실제 상태로 판정해야 한다.

## 3. 결정

| # | 결정 | 대안 | 근거 |
|---|---|---|---|
| 1 | `result`에 새 값 `canceled` 추가 (+ `reason_code='canceled'`) | `failed`+reason_code / `success`+reason_code | `failed`는 유저 본인이 누른 취소를 "실패"로 표시해 오해를 부른다. `success`는 실측 케이스가 target 미확정 상태라 사실과 다르다 |
| 2 | 소급 보정을 Flyway 마이그레이션으로 동봉 | 코드만 고치고 수동 SQL | 멱등하고, 배포 시점까지 새로 갇히는 건까지 커버한다. UPDATE라 migration-guard 대상 아님 |
| 3 | 나이 기반 entry 스윕 추가 (임계 24시간), 확정 값은 item 실제 상태로 판정 | 별도 트랙 분리 / 재시도 횟수 컬럼 / 나이 초과분 일괄 failed | 스키마 변경 없이 §2-1·§2-2 두 구멍을 함께 막는다. 일괄 failed는 §2-2에서 성공 건을 실패로 오보한다 |

### 3-1. 임계시간의 의미 (24시간)

**"등록 요청이 접수된 순간부터 그 entry가 아직 `pending`인 채로 흐른 시간"**이다. 기준점은
`monitoring_registrations.requested_at`.

```sql
result = 'pending' AND requested_at < now() - <임계시간>
```

- 재시도 횟수·마지막 시도 시각 기준이 **아니다**. entry 테이블에 시각 컬럼이 없어
  (`registration_id, seq, input, kind, result, reason_code, reason, resolved_url, item_id`가 전부)
  쓸 수 있는 앵커가 registration 헤더의 `requested_at`뿐이다.
- 앵커가 registration 단위라 같은 요청의 entry들이 같은 시계를 쓴다. 같이 접수돼 같이 처리되므로
  실무상 문제없다.
- 기존 `recoverStalePending`의 5분(`STALE_AGE`)과 **다른 축**이다. 그쪽은
  `monitoring_items.created_at` 기준으로 "복구를 **시도**할 대상"을 고르는 값이고(10분 주기 재시도),
  이 임계시간은 "그만 시도하고 **실제 상태에 맞춰 확정**할" 시점이다. 항상 5분보다 훨씬 커야 하고,
  그 간격이 곧 재시도에 허용하는 총 시간이다.
- 값의 의미는 **"monitoring 서브시스템 장애를 몇 시간까지 견디며 재시도할 것인가"**. 24시간이면
  하루짜리 장애도 흡수한다. 프로퍼티(`monitoring.registration.stale-entry-timeout`)로 조정 가능.

## 4. 설계

### 4-1. 어휘 확장 — Flyway `V<UTC타임스탬프>__monitoring_registration_entry_canceled.sql`

- `result` CHECK에 `'canceled'` 추가
- `reason_code` CHECK에 `'canceled'` 추가
- 소급 보정 UPDATE: 취소된 item에 매달린 `pending` entry → `canceled`
- 소급 `completed_at` 채우기: 위 보정으로 전부 정산된 registration

**롤링 안전성**: 값 추가는 제약을 넓히는 방향이라 구 코드가 위배할 일이 없다. 구 코드는 `result`를
String으로 통과시키기만 하므로(`RegistrationResponse.Entry.from`) `canceled`를 읽어도 깨지지
않는다. expand 단계로 안전하다.

migration-guard의 `DESTRUCTIVE` 정규식에 `DROP CONSTRAINT`는 없다(스크립트 주석에 알려진 한계로
명시됨). 차단되지 않지만, 위 근거를 마이그레이션 파일 주석에 남긴다.

### 4-2. 취소 시점 정산 — 신규 `RegistrationRepository.settleCanceledByItem(itemId)`

기존 `findEntryByItemId()` + `updateEntryResult()` 조합을 쓰지 않고 **조건부 UPDATE 한 방**으로 간다:

```sql
UPDATE app.monitoring_registration_entries
SET result = 'canceled', reason_code = 'canceled', reason = <취소 안내 문구>
WHERE item_id = :itemId AND result = 'pending'
RETURNING registration_id
```

**이유는 경합**이다. 실측 케이스가 정확히 "실행기 스레드와 취소 요청이 같은 entry를 동시에 만지는"
창이었고, 기존 `updateEntryResult`는 무조건 덮어써서 이미 `success`로 정산된 entry를 `canceled`로
되돌릴 수 있다. `result = 'pending'` 조건을 SQL에 박아 원자적으로 막는다.

호출부 2곳 — 둘 다 뒤이어 `markCompletedIfAllSettled` 호출:

- `V1MonitoringItemUpdateService.cancel()` — `markCanceled` 직후, 같은 트랜잭션
- `MonitoringRegistrationExecutor.processItem()`의 취소 감지 분기 — 로그만 남기던 자리를 교체

### 4-3. 나이 기반 entry 스윕 — 신규 `RegistrationRepository.settleStaleEntries(Duration)`

`requested_at` 기준 임계 초과 `pending` entry를 확정하고 영향받은 `registration_id`를 반환한다.
호출부가 각 registration에 `markCompletedIfAllSettled`를 돌린다.

**나이는 "이제 판정할 때가 됐다"는 트리거일 뿐이고, 확정 값은 item의 실제 상태로 정한다**
(§2-2 — 나이만 보고 일괄 `failed`로 밀면 성공한 건을 실패로 오보한다):

| entry의 item 상태 | 확정 값 |
|---|---|
| `target_id` 있음 (등록은 실제로 성공, §2-2 크래시 창) | `success` |
| `canceled_at` 있음 (§2-1 취소 경로 누락분의 최종 안전망) | `canceled` + `reason_code='canceled'` |
| item 없음(share-resolve 실패) / target 미확정 | `failed` + `reason_code='internal_error'` |

이 판정은 `entries LEFT JOIN monitoring_items ON entries.item_id = items.id` 한 문장의 CASE로
표현한다 — item이 없으면(`item_id IS NULL` 또는 삭제됨) LEFT JOIN이 NULL을 주므로 자연히 세 번째
행으로 떨어진다.

즉 이 스윕은 "포기 선언"이 아니라 **실제 상태와의 정합 맞추기**이고, 그래서 §2-1(share-resolve)과
§2-2(크래시 창) 두 구멍을 함께 메운다.

**배선 순서**: `RecoverStalePendingScheduler`의 같은 틱 안에서 **item 복구 → 나이 확정** 순서.
역순이면 복구 가능한 건을 먼저 죽인다.

### 4-4. result·reason_code 상수 홀더 신설

현재 `result` 상수가 `V1MonitoringRegistrationService`(3개)와 `MonitoringRegistrationExecutor`(4개)에
중복 정의돼 있고, `canceled` 추가로 세 번째 소비처가 생긴다. `reason_code`도 executor private 상수라
`RegistrationResponse`가 `@Schema`에 문자열 리터럴을 박아 두고 그 사정을 주석으로 변명하고 있다.

`com.celfit.was.monitoring`에 상수 홀더 `RegistrationResult`를 만들어 양쪽을 모은다.

enum이 아니라 상수 홀더인 이유: DB·JSON 모두 문자열 그대로 통과하는 계약이고 기존 코드가 전부
String으로 흐른다. enum 전환은 별개의 큰 리팩터링이고, 같은 패키지의 `ItemStatus`가 이미 상수 홀더
패턴이다.

### 4-5. 범위에서 뺀 것

`processEntry()`의 `itemOpt.isEmpty()` 분기는 손대지 않는다. item 삭제 시 FK `ON DELETE SET NULL`로
`entry.item_id`가 먼저 null이 되어 실제로는 share 분기로 빠지는, 사실상 도달 불가 경로다.
§4-3의 나이 기반 스윕이 최종 안전망으로 덮는다.

## 5. 계약 영향 — DB엔 `canceled`, API엔 `failed`로 접는다

**결정(설계 리뷰 최종)**: `result` 컬럼에는 `canceled`를 그대로 저장하되, API 응답에서만 `failed`로
매핑한다. 매핑 지점은 `RegistrationResponse.Entry.from()` 한 곳이다(전수 확인 — `RegistrationEntryRow`의
`result`를 외부로 내보내는 seam은 여기뿐. 다이제스트·이메일은 이 테이블을 참조하지 않는다).

| 층 | `result` | `reason_code` |
|---|---|---|
| DB | 5종 — `canceled` 포함 | 7종 — `canceled` 포함 |
| API | **4종 — 기존 그대로** | 7종 — `canceled` 노출 |

**왜 접는가**: item 쪽 status가 이미 취소를 `not_uploaded`(미업로드)로 접어 보여준다
(`ItemStatus.derive` 1번 규칙 — `canceled_from='detecting'` → `not_uploaded`). entry가 `canceled`로
정산되는 케이스는 정확히 그 미업로드로 보이는 케이스들이므로, registration 쪽만 새 어휘를 노출해
프론트 계약을 넓힐 실익이 적다. 발생 빈도도 좁다(§3-1 — 계정 모드 등록이 처리 중일 때의 취소, 정상이면
수 초짜리 창).

**왜 DB엔 남기는가**: `failed`로 접어 저장하면 원본이 원천에서 사라져 "취소 때문에 안 끝난 건이 몇
건인가"를 나중에 물을 수 없다. DB에 남겨 두면 운영·분석 질의가 취소를 구분해 셀 수 있고, 노출 정책이
바뀌어도 이 매핑만 걷어내면 된다 — 소급 마이그레이션이 필요 없다.

**왜 `reason_code`는 안 접는가**: `internal_error` 등으로 같이 접으면 운영 지표에서 취소가 시스템
오류로 오독된다. `result='failed'` + `reason_code='canceled'`로 두면 프론트는 "실패했고 사유는 취소"로
읽을 수 있고, 원인 정보가 API 표면에 남는다.

**프론트 영향**: `result`는 기존 4종 그대로라 변경 없음. `reason_code`만 6종 → 7종이며, 이는 선택적
상세 필드다. 프론트가 `canceled` 사유를 구분해 표시할지는 선택이고, 안 해도 기존 실패 표시로 폴백된다.
`result`에 `@Schema(allowableValues)`를 새로 명시해 "`canceled`는 API에 안 나온다"를 계약에 못박았다.

## 6. 검증

TDD로 진행한다.

| 대상 | 케이스 |
|---|---|
| `settleCanceledByItem` | pending만 정산 / 이미 success인 entry 불변 / 없는 itemId no-op / registration_id 반환 |
| `settleStaleEntries` | 임계 미달 불변 / item 없는 share entry → failed / target 붙은 entry → success(§2-2) / 취소된 item → canceled / 반환 목록 |
| 취소 API 통합 | cancel → entry `canceled` + `completed_at` 채워짐 |
| 실행기 | 취소된 item 분기에서 entry가 `canceled`로 정산 |
| 스윕 배선 | item 복구가 나이 확정보다 먼저 도는지 |

## 7. 관측

PR #286(Grafana 대시보드)의 패널 1·2와 "미완료 등록 30분 초과" 알림이 이 상태를 감시한다.
**수정 후 그 패널이 비게 되는 것이 정상이다.**
