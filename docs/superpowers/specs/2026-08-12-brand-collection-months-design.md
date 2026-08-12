# 브랜드 계정 수집 범위 선택 설계 — collectionMonths(1/3/6/12)

> 상태: 🟢 활성

## 배경

브랜드 계정 등록은 지금 무조건 최근 12개월치를 수집한다(전역 설정
`monitoring.brand.registration-window-days: 365`). 게시물이 많은 계정은 첫 수집이 5분을
넘겨(FE 실측: 1,364건 = 5분 45초) 등록 직후 이탈이 생기고, 경쟁사 모니터링에는 12개월이
과하다. FE 요청서 2026-08-12 수신 — 등록 요청·계정 응답에 `collectionMonths`(1|3|6|12)를
추가하고, 더 큰 값 재등록을 기간 확장(증분 수집)으로 처리한다.

FE는 등록 모달에 범위 선택을 이미 붙였고, 서버 배포 전에는 필드가 무시돼 현행(12개월)과
동일하게 동작한다(하위 호환 안전).

## 결정 요약

| 항목 | 결정 |
|---|---|
| 범위 귀속(요청서 §5) | **자산 레벨 max** — `brand_account.collection_months` 하나로 관리, 커지기만 한다. 응답도 자산 값 그대로(3개월 유저가 12개월치를 보게 되는 쪽은 무해, FE 잠금이 자연히 풀림). 구독 레벨 별도 관리는 표시 제한 로직 비용 대비 이득 없음 — 기각(사용자 결정 08-12) |
| 값 공간 | 1 \| 3 \| 6 \| 12 (CHECK 제약 + was 400) |
| 기존 데이터 백필 | `DEFAULT 12` — 전부 12개월 수집이라 사실과 일치(요청서 §3) |
| 확장 판정 위치 | was 사전 게이트(자산 값 읽고 클 때만 monitoring 호출) + monitoring replay가 정본 판정 |
| 축소 재등록 | no-op — months 불변, 데이터 보존(요청서 권장) |
| 확장 중 상태 | `collecting` — 유도 규칙에 `last_swept_on null && backfill_completed_at 있음` 분기 추가 |
| collectionStartedAt | 신규 컬럼 `collection_started_at`(확장 시 갱신) — `registered_at` 재사용은 `createdAt` 동요 때문에 기각 |
| 전역 설정 | `registration-window-days` **제거** — 브랜드별 컬럼이 대체(진실 이원화 방지) |
| nextScheduledAt(요청서 §6) | 서버 크론(KST 02:00)이 정본 — 표기 기본값 3→2 정정 + 레포 compose를 현실(UTC 17:00)에 정렬(사용자 결정 08-12: 캠페인 스윕과의 동시 실행 수용) |

## 1. 데이터 모델 — monitoring `brand_account`

UTC 타임스탬프 채번 마이그레이션 1개:

```sql
ALTER TABLE brand_account ADD COLUMN collection_months int NOT NULL DEFAULT 12;
ALTER TABLE brand_account ADD CONSTRAINT brand_account_collection_months_chk
    CHECK (collection_months IN (1, 3, 6, 12));
ALTER TABLE brand_account ADD COLUMN collection_started_at timestamptz;
UPDATE brand_account SET collection_started_at = registered_at;
```

- **expand-contract 안전**: 신규 컬럼 + DEFAULT/nullable뿐이라 롤링 중 구버전 INSERT·SELECT가
  깨지지 않는다. `SET NOT NULL` 없음 — `collection_started_at`은 nullable로 두고 읽기에서
  `COALESCE(collection_started_at, registered_at)`로 접는다.
- `collection_started_at`의 역할은 FE 수집 폴링의 30분 상한 앵커다(요청서 §4 조건 ②) —
  확장 시작 시 `now()`로 갱신돼 폴링이 다시 돈다. `registered_at`을 갱신하는 대안은 같은
  값을 내보내는 `createdAt`까지 흔들어 기각.
- `monitoring.brand.registration-window-days` 설정과 `BrandCollectService`의
  `registrationWindowDays` 필드는 제거한다. 열거 컷·편입 컷 모두 브랜드 행의
  `collection_months`로 계산한다.

## 2. 등록 요청 — was → monitoring 전파

### was `POST /v1/brand-monitoring/accounts`

```json
{ "username": "lagom.official", "accountType": "competitor", "collectionMonths": 3 }
```

- 생략 시 12(하위 호환). `{1,3,6,12}` 밖은 400 `VALIDATION_FAILED` — 검증은 리포지토리 도달
  전에(`BrandAccountType` 관용구와 동형의 `BrandCollectionMonths` 상수 클래스: `orDefault` +
  `isValid`). 정수가 아닌 값(문자열 등)은 기존 역직렬화 규약대로 400.
- `MonitoringCommandClient.registerBrand(username, brandName, collectionMonths)`로 전달.

### monitoring `POST /api/brands`

`BrandRegisterRequest`에 `collectionMonths`(Integer, nullable → 12) 추가. monitoring도
방어적으로 값 공간을 검증한다(밖이면 400 — 내부 API지만 CHECK 위반 500을 막는다).

신규·재가입 경로: `insertOrReactivate`가 `collection_months` 저장 +
`collection_started_at = now()`. 백필 열거 컷이 이 값으로 돈다(§4).

## 3. 기간 확장 — 재등록 replay의 새 분기

### was: 사전 게이트

이미 연결된 계정의 재-POST(현행 `precheck` 멱등 경로)에서:

| 케이스 | 동작 |
|---|---|
| 요청 months > 자산 months | monitoring 등록 API 재호출(확장) → 갱신된 계정 객체 반환 |
| 같거나 작은 값 | 현행 그대로 — monitoring 호출 없는 멱등 반환(accountType 변경만 있으면 현행 타입 변경 계약) |

자산 months는 was가 이미 쓰는 `BrandReadRepository.findAccount`(monitoring DB 읽기 전용)에서
읽는다 — 추가 인프라 없음. 판정 정본은 monitoring replay가 한 번 더 쥔다(사전 게이트는
`precheck` 관용구와 같은 구조 — 경합으로 게이트가 낡아도 monitoring이 올바르게 판정한다).

미연결 상태의 등록(다른 유저가 이미 수집 중인 브랜드에 연결)도 항상 monitoring을 호출하므로
(현행), 그 replay에서 같은 확장 판정이 돈다 — 유저 B가 12개월로 처음 연결하면 3개월짜리
자산이 12개월로 확장된다(자산 레벨 max의 자연 귀결).

**brandName 규칙은 불변**: 확장 호출에도 own 연결일 때만 brandName을 넘긴다(#406 경쟁사
게이트 — 경쟁사 브랜드에 내 회사명 태그 시드 금지).

### monitoring: replay 확장 처리

`BrandRegistrationService.register`의 replay 분기(ACTIVE 기존 행)에서:

```
requested > stored 이면(확장):
  UPDATE brand_account
     SET collection_months = requested, last_swept_on = NULL,
         collection_started_at = now(), backfill_error = NULL
   WHERE id = ?
  backfill executor에 즉시 제출(기존 runBackfillSafely 경로 재사용)
requested <= stored 이면: 순수 replay(현행 — 변경 없음, months 축소 없음)
```

- **`last_swept_on = NULL`이 기존 백스톱 구조를 그대로 상속한다**: 확장 백필이 죽어도 다음
  새벽 스윕이 `enumerationCutoff`의 백필 분기(전체 창 열거)로 자동 복구한다. 별도 재시도
  장치를 만들지 않는다.
- 증분 수집의 실체는 "새 컷까지 재열거"다 — IG 태그 열거는 최신부터 커서로 내려가는
  단방향이라 3~6개월 구간만 골라 받을 수 없다. 기지 게시물은 insert 스킵(멱등 upsert),
  스냅샷·last_crawled_at만 갱신되므로 일일 스윕과 동일한 비용 구조다. 기존 데이터는 어떤
  경로로도 지워지지 않는다(요청서 §4 "기존 데이터 유지").
- Hiker 콜은 확장 판정 자체에는 0(프로필 콜 없는 replay) — 백필 executor 안에서만 발생한다.
- 확장 중 새벽 스윕이 겹치면 백필과 스윕이 같은 창을 이중 열거할 수 있다 — 현행 등록 백필
  진행 중 스윕과 같은 기존 시나리오이고, 전 구간 멱등이라 무해(수용).

## 4. 수집 창 반영 — `BrandCollectService`

- `BrandRow`에 `collectionMonths` 추가(스윕·백필 조회 단면).
- 열거 컷(`enumerationCutoff`)의 백필 분기: `now - 365d` → **KST 캘린더 개월**
  `ZonedDateTime.now(KST).minusMonths(months).toInstant()`. 요청서 표현("게시물 taken_at 기준
  최근 N개월")과 일치하고, 12개월 ≈ 365일이라 기존 동작과 실질 동일.
- 편입 컷(`processPage`의 `enrollCutoff`)도 같은 계산 — 함수 하나로 묶는다. 일일 스윕에서도
  이 컷이 돌므로, 소급 태그가 창 밖(예: 3개월 설정 브랜드의 5개월 전 게시물)이면 편입되지
  않는다 — collectionMonths 의미론과 정합.
- 티어 정책(`BrandCrawlPolicy`)은 불변 — 추적 상한 180일은 수집 창과 독립된 갱신 정책이다.

## 5. 확장 중 상태 — 유도 규칙 분기 추가

FE 판정 계약(요청서 §4): "collecting인데 게시물이 있으면 확장" — 기존 데이터 위에 진행
배너만 띄운다. `BrandAccountAssembler` 유도 규칙:

| 조건 | 상태 | 케이스 |
|---|---|---|
| `last_swept_on != null` | ready | 평시(완주 후 매일 스윕이 갱신) |
| `last_swept_on == null && backfill_completed_at != null` | **collecting** | **확장/재수집 진행 — 데이터는 서빙 중** |
| 둘 다 null | 현행 규칙(`last_swept_at` ? ready : error/collecting) | 첫 등록 — 스트리밍 fast-ready(08-12 개정) 보존 |

성립 근거:

- `markServing`은 `WHERE last_swept_at IS NULL`이라 확장 중엔 no-op(이미 값 있음) — 서빙 창
  커버 시점에 ready로 일찍 튀지 않는다. ready 복귀는 완주 시점의 `touchSwept`
  (`last_swept_on` 기록)뿐이다.
- `backfill_completed_at`은 `COALESCE`로 최초 완주 시각을 보존하므로 "한 번이라도 완주한
  적 있음 = 지금 last_swept_on이 비어 있으면 재수집 중"이라는 판별이 성립한다. 재가입은
  `insertOrReactivate`가 `backfill_completed_at`을 리셋해 첫 등록 분기로 돌아간다(현행 유지).
- 게시물 조회 API는 상태 게이트가 없다(`V1BrandPostsController` — `last_swept_at`은 meta
  표시용뿐) — 확장 중 정상 서빙(요청서 §4 조건 ①)은 구조상 이미 충족.
- 08-10 결정("last_swept_on이 비어도 데이터가 있으면 보여준다")과의 관계: 그 결정의 목적은
  FE 로딩 화면이 데이터를 가리는 것 방지였다. FE가 이제 collecting+데이터를 배너로 처리하므로
  (요청서 §4), collecting 전이가 그 목적과 충돌하지 않는다 — 오히려 "재수집 중"이 사실이다.

**의도된 한계**: 확장 백필 실패 시 다음 스윕(최대 24h)까지 collecting으로 남는다. FE 폴링
상한 30분이 "수집 지연" 처리를 하므로 수용 — error로 바꾸면 기존 데이터 위에 "초기 수집
실패" 오보가 뜬다(`markBackfillError` 문구는 첫 등록 전제).

`collectionCompletedAt`(= `backfill_completed_at`)은 확장 완료 후에도 최초 완주 시각
그대로다 — 확장 완료 판정은 status의 collecting→ready 전이로 한다(FE 회신 ③).

## 6. 응답 — `collectionMonths` 필드

`BrandAccountResponse`에 `accountType` 뒤로 `collectionMonths`(int) 추가 — 목록·단건·등록
응답 전부(같은 셰이프). `BrandReadRepository`의 brand_account SELECT에 `collection_months`,
`COALESCE(collection_started_at, registered_at) AS collection_started_at` 추가,
`BrandAccountRow`에 두 필드 추가. `collectionStartedAt` 출처를 `registeredAt`에서
`collectionStartedAt`으로 바꾼다(기존 계정은 백필 UPDATE로 값이 같아 관찰 동작 불변).

값이 없으면 FE가 12로 폴백하므로 배포 순서 제약 없음(요청서 §3).

## 7. nextScheduledAt 표기 정정(요청서 §6)

원인 확인(08-12, 운영 서버 읽기 전용 조회): 브랜드 스윕 크론이 서버 env
`MONITORING_BRAND_SCHEDULE_SWEEP_CRON=0 0 17 * * *`(UTC 17:00 = **KST 02:00**)로, 레포
설계값 `0 0 18 * * *`(KST 03:00 — 캠페인 스윕과 분리 의도)에서 드리프트돼 캠페인 스윕과
겹쳐 돌고 있다. 표기(03:00)는 레포 설계값 기준이라 어긋났다 — 타임존 변환 실수 아님.

사용자 결정(08-12): **02:00을 현실로 수용**하고 표기를 정정한다.

- `BrandAccountAssembler`의 `was.brand.sweep-hour-kst` 기본값 3 → 2.
- 레포 `deploy/compose.yaml`의 `MONITORING_BRAND_SCHEDULE_SWEEP_CRON`을 `0 0 17 * * *`로
  정렬 + 캠페인 스윕과의 동시 실행을 수용한다는 주석 — 안 하면 다음 monitoring 재배포가
  크론을 조용히 03:00으로 되돌려 표기가 다시 어긋난다. `application.yml`의 낡은 주석
  ("UTC 18:00 env 주입")도 함께 고친다.

FE 회신: 이번 배포부터 응답 `nextScheduledAt`이 02:00으로 정정되니, FE 자체 계산("새벽
2시") 대신 응답 값을 쓰면 된다.

## 요청서와 다른 점 / 회신 포인트

1. **§6은 타임존 실수가 아니라 서버 크론 드리프트** — 위 §7. 응답 값이 02:00으로 정정된다.
2. **확장 완료 신호는 status 전이** — `collectionCompletedAt`은 최초 완주 시각을 유지한다.
3. §5는 FE 권장안(자산 레벨 max) 채택 — 응답 `collectionMonths`는 자산 값이다.

## 테스트 계획

| 대상 | 케이스 |
|---|---|
| 마이그레이션 | 기존 행 `collection_months=12`·`collection_started_at=registered_at` 백필 / CHECK 밖 값 INSERT 거절 |
| was POST | 생략=12 / 1·3·6·12 통과 / 2·24·0 → 400 `VALIDATION_FAILED` |
| was 확장 | 큰 값 재등록 → monitoring 재호출 / 같은 값 no-op(monitoring 콜 0, 현행 멱등 회귀) / 작은 값 no-op + months 불변 / 미연결 유저의 큰 값 첫 연결 → 확장 |
| monitoring replay | 확장 시 months·collection_started_at 갱신 + last_swept_on 클리어 + 백필 제출 / 축소 무시 / brandName 게이트 불변(competitor 확장에 태그 시드 없음) |
| 수집 창 | months=3 신규 백필 컷 = KST 3개월 전 / processPage 편입 필터 동일 컷 / registration-window-days 참조 잔재 없음 |
| 상태 유도 | 3분기 매트릭스(평시 ready / 확장 collecting / 첫 등록 last_swept_at 규칙) / 확장 중 markServing no-op 확인 |
| 응답 | `collectionMonths` 3면 전부(목록·단건·등록) / `collectionStartedAt` COALESCE / `nextScheduledAt` 02:00 |
| 게시물 API | collecting(확장) 상태에서 정상 응답 회귀 |
