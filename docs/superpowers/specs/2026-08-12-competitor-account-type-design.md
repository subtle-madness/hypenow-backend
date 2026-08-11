# 경쟁사 모니터링 계정 타입 설계 — accountType(own/competitor)

> 상태: 🟢 활성

## 배경

경쟁사 모니터링이 FE에서 별도 메뉴(`/monitoring/competitor`)로 분리됐는데, 경쟁사 여부가
브라우저 `localStorage`에만 있어 기기를 바꾸면 사라진다. 이 분류를 서버로 옮긴다.
FE 요청서 2026-08-11 수신(대체: `backend-request-2026-08-11-account-type.md`, 08-10 성과
대시보드 요청서 7-2·7-3).

**요청서 §1의 설계 원칙("타입은 계정이 아니라 유저-계정 관계의 속성")은 이미 그렇게
구현돼 있다.** `app.brand_monitorings`가 그 구독 테이블이고(08-07 다계정 개정), 수집은
monitoring 쪽 `brand_account` 전역 1행을 여러 유저가 공유한다. 따라서 이번 작업의 본체는
**그 관계 테이블에 컬럼 하나를 추가하고 읽는 지점들을 잇는 것**이다. 크롤링 대상 테이블은
건드리지 않는다.

FE가 확인을 요청한 §2-5(DELETE가 구독 해지인가)도 **이미 그렇다** — 링크만 soft-delete하고
그 브랜드의 마지막 활성 링크였을 때만 monitoring 탈퇴를 부른다
(`V1BrandAccountService.deregisterIfLast`). 다른 유저의 수집·화면에 영향이 없다.

## 결정 요약

| 항목 | 결정 |
|---|---|
| 저장 위치 | `app.brand_monitorings.account_type` — 관계(구독) 테이블. 신규 테이블 없음 |
| 값 공간 | `own` \| `competitor` (CHECK 제약) |
| 기존 데이터 백필 | `DEFAULT 'own'`이 대신한다 — 별도 UPDATE 없음 |
| 동시 보유 금지 | 기존 부분 유니크 `(user_id, brand_id) WHERE deleted_at IS NULL`이 이미 강제 — 변경 없음 |
| 상한 | own 6 / competitor 3, 하드 강제. 강제 지점은 기존 `users` 행 `FOR UPDATE` 직렬화 그대로 |
| 상한 초과 응답 | **409** — own `BRAND_ACCOUNT_LIMIT_REACHED`(기존 유지) / competitor `COMPETITOR_ACCOUNT_LIMIT_REACHED`(신규). 요청서의 400과 다름(§요청서와 다른 점 ①) |
| 타입 변경 경로 | POST 재등록·PATCH 둘 다 — 같은 트랜잭션 메서드 하나를 공유. 재수집 없음 |
| `/contents` 기본 범위 | `accountType` 미지정 = own 브랜드 + individual(브랜드 미귀속 개인 추적) |
| comparison | own·competitor 둘 다 포함(현행 그대로 — 활성 링크 전량 순회). `accountType` 필드만 추가 |
| 캠페인 연결 방어 | 요청 전체 400이 아니라 **건별 실패** `COMPETITOR_CONTENT_NOT_ALLOWED`(§요청서와 다른 점 ②) |

## 1. 데이터 모델

`V20260811164500__brand_monitorings_account_type.sql` (was `app` 스키마, UTC 타임스탬프 채번):

```sql
ALTER TABLE app.brand_monitorings
    ADD COLUMN account_type text NOT NULL DEFAULT 'own';
ALTER TABLE app.brand_monitorings
    ADD CONSTRAINT brand_monitorings_account_type_chk
    CHECK (account_type IN ('own', 'competitor'));
```

**expand-contract 안전성**: 신규 컬럼 + DEFAULT라 롤링 중 구버전 코드의
`INSERT (user_id, brand_id, username)`에 기본값이 그대로 먹는다. 구버전은 이 컬럼을 읽지
않으므로 신구 공존 창에서 깨지는 지점이 없다. migration-guard의 차단 패턴(DROP·RENAME·타입
변경·SET NOT NULL)에도 해당하지 않는다.

**백필**: 요청서 §2-1대로 기존 등록분은 전부 `own`이다. DEFAULT가 그 일을 하므로 보정
UPDATE를 동봉하지 않는다. 경쟁사 지정은 지금까지 브라우저에만 있었으므로 서버에 없는 게 맞고,
배포 후 유저가 경쟁사 화면에서 다시 지정한다(FE 안내).

**유니크는 추가하지 않는다**: 요청서가 "(username, user) 유니크"로 표현한 제약은 실제로
`brand_monitorings_active_user_brand_uidx`(부분 유니크)가 이미 강제한다. username↔brand는
monitoring 쪽에서 1:1이라 효과가 같다. 즉 한 유저가 같은 계정을 own·competitor로 동시에
가질 수 없다는 요구는 DB 레벨에서 이미 성립한다.

`BrandLinkRow`에 `accountType` 필드를 더하고, `BrandLinkRepository`의 `SELECT_COLUMNS`에
`account_type`을 넣는다. 타입 변경용 UPDATE 하나(`updateAccountType`)를 추가한다.

## 2. 상한 — own 6 / competitor 3

`BrandLinkTransaction.ACCOUNT_LIMIT = 10`(08-07 다계정 개정)을 타입별 둘로 나눈다:

```java
static final int OWN_LIMIT = 6;
static final int COMPETITOR_LIMIT = 3;
```

강제 방식은 지금과 같다 — 한도는 유니크 인덱스로 표현할 수 없으므로
`BrandLinkRepository.lockUser`(유저 행 `FOR UPDATE`) 직렬화 아래에서 앱이 타입별로 센다.
`precheck`(monitoring 호출 전 빠른 판정)와 `link`/`changeType`(정본 재확인) 두 곳 모두
타입별 카운트로 바뀐다.

**기존 데이터 안전성 실측(08-12, 운영 DB 읽기 전용 조회)**: 유저별 활성 링크 분포가
`1건×3명, 3건×2명, 6건×2명`으로 **최대가 정확히 6**이다. 전부 own으로 백필해도 하드 강제로
깨지는 유저가 0명이라 별도 정리 절차가 필요 없다. 6건 보유 2명은 own 추가만 막히고
competitor 3칸이 새로 열린다(그 중 하나를 경쟁사로 옮기면 own 칸이 즉시 하나 빈다).

목록 응답 `meta`:

```json
{ "total": 7, "limit": 9, "limits": { "own": 6, "competitor": 3 },
  "counts": { "own": 5, "competitor": 2 } }
```

`limit`은 호환용으로 키를 남기되 값은 합산 최대(9)다 — FE가 `total > limit`으로 판정해도
오탐이 없다. 실제 게이트는 `limits`·`counts`다.

## 3. 등록·타입 변경

### POST /v1/brand-monitoring/accounts

요청 본문에 `accountType` 추가. 생략 시 `own`(하위 호환), 값 공간 밖이면 400
`VALIDATION_FAILED`.

| 상황 | 동작 |
|---|---|
| 신규 | 현행 흐름(monitoring 동기 검증·등록 → was 링크 커밋) + 타입 저장. 202 |
| 다른 유저가 이미 수집 중 | 재수집 없이 링크만 생성, 기존 데이터 즉시 반환 (현행 유지) |
| 이미 연결 + 같은 타입 | 멱등 — monitoring 호출 없이 기존 객체 반환 (현행 유지) |
| 이미 연결 + 다른 타입 | **monitoring 호출 없이 타입만 UPDATE** 후 반환. 대상 타입 한도 초과면 409 |

마지막 행이 요청서 §2-2의 "이미 등록된 계정을 넣으면 경쟁사로 옮겨진다" UX다. 409가 아니라
타입 변경으로 처리한다. 구현상 `precheck`가 이미 "같은 username의 활성 링크"를 찾아 멱등
경로로 접고 있으므로, 그 지점에서 타입이 다르면 `changeType`으로 분기한다.

### PATCH /v1/brand-monitoring/accounts/{id} (신규)

```
요청:  { "accountType": "competitor" }
응답:  200 + 갱신된 계정 객체(GET과 같은 셰이프)
```

`{id}`는 목록 응답의 `id`와 같은 값(= monitoring 브랜드 id)이다. 소유권은 활성 링크로
검증하고 남의 것은 403(기존 `requireOwnership` 관용구 — 존재 여부를 흘리지 않는다).
숫자가 아니면 404(`parseAccountId` 관용구). 재수집은 일어나지 않는다.

POST 재등록 경로와 **같은 트랜잭션 메서드**(`BrandLinkTransaction.changeType`)를 쓴다 —
한도 판정·잠금 규율이 한 곳에만 있게 한다.

## 4. 응답 필드 accountType

`BrandAccountResponse`에 `id` 바로 뒤로 `accountType`을 추가한다. 값의 출처가 브랜드 행이
아니라 **링크 행**이므로 조립기 시그니처가 바뀐다:

```java
BrandAccountResponse toResponse(BrandAccountRow row, String accountType)
```

`BrandAccountAssembler`는 계속 순수 변환이다(DB·외부 호출 없음). 호출부(list·get·register·
patch)가 각자 이미 들고 있는 링크 행에서 타입을 넘긴다. `list()`는 이미 링크를 순회하므로
추가 쿼리가 없고, 단건 경로는 소유권 검증에서 읽은 링크를 재사용한다.

## 5. /performance-dashboard/contents 집계 범위

쿼리 파라미터 `accountType` 추가:

| 값 | 범위 |
|---|---|
| 미지정 (기본) | own 브랜드 콘텐츠 + individual(`brandAccountId` null) |
| `own` | 위와 동일 |
| `competitor` | 경쟁사 링크 소속만 |
| `all` | 전부 |
| 그 외 | 400 `VALIDATION_FAILED` (`normalizeFilter` 관용구) |

**individual을 기본에 포함하는 이유**: 이 응답에는 브랜드에 귀속되지 않는 레거시 개인 추적
콘텐츠가 섞여 있다. "own만"을 문자 그대로 구현하면 이것들이 통째로 사라져, 경쟁사를 하나도
등록하지 않은 유저의 성과 요약 숫자가 이유 없이 줄어든다. 요청서의 의도(경쟁사가 내 성과를
오염시키지 않게)는 "competitor만 빼는 것"으로 온전히 충족된다.

**statusCounts 모수**: `accountType`은 `source`·`sponsorship`과 같은 **분류 필터**로 취급해
`meta.statusCounts` 모수에도 적용한다. 기존 규칙(업로드 기간과 `status` 자신만 모수에서
빠진다)은 그대로다.

**판정 방법**: 유저의 활성 링크에서 경쟁사 brandId 집합을 만들고, 콘텐츠의
`brandAccountId`가 그 집합에 들면 competitor다. 어셈블러가 이미 링크 기반으로 콘텐츠를
조립하므로 추가 쿼리가 없다.

## 6. /performance-dashboard/comparison

`AccountComparison`에 `accountType` 필드 추가. 그 외에 바꿀 게 없다 —
`PerformanceComparisonAssembler`가 이미 유저의 활성 링크 전량을 순회하므로 **경쟁사 계정이
자동으로 포함돼 있다**. 요청서 §3-1의 "comparison에는 own·competitor 둘 다 포함"은 현행
동작 그대로다.

## 7. 캠페인 연결 서버 방어

방어 대상 경로는 `POST /v2/monitoring/campaigns/{campaignId}/contents`다. 현재
`V2CampaignContentService.taggedPostUrls(userId)`가 **활성 링크를 타입 구분 없이** 순회해
게시물 맵을 만들기 때문에, 경쟁사 게시물 shortcode를 API로 직접 보내면 캠페인에 붙고 추적
아이템까지 생성된다. 이 구멍을 막는다.

맵을 만들 때 링크 타입을 함께 실어, 경쟁사 소속으로만 해석되는 contentId를 건별 실패로
판정한다:

```json
{ "contentId": "C4jkl", "result": "failed", "monitoringItemId": null,
  "reasonCode": "COMPETITOR_CONTENT_NOT_ALLOWED",
  "reason": "경쟁사 계정의 게시물은 캠페인에 연결할 수 없어요." }
```

이미 링크를 순회하는 자리라 쿼리가 늘지 않는다. 대안이었던 "경쟁사 링크를 맵에서 아예
제외"는 한 줄로 끝나지만 경쟁사 게시물이 기존 `NOT_FOUND`("게시물을 찾을 수 없습니다")로
떨어져 **존재하는 게시물을 없다고 말하게 된다** — 나중에 이 응답을 보고 수집 누락을 의심하게
만드므로 기각했다.

### 한계 (의도적)

유저가 경쟁사 게시물 URL을 **개인 추적으로 직접 등록**해 둔 경우는 막지 않는다. 그 콘텐츠는
이미 본인의 레거시 추적 아이템이라 `add()`의 첫 갈래(아이템 존재 → 캠페인만 연결)로
통과한다. 구독 타입만으로는 "이게 경쟁사 게시물"이라고 판정할 근거가 없고, 게시물 작성자
계정을 경쟁사 구독과 대조하는 별도 판정은 요청서 범위를 넘으며 개인 추적의 기존 자유도를
깎는다. FE는 진입점을 이미 막아 뒀으므로 이 방어선은 API 직접 호출에 대한 이중 방어다.

## 요청서와 다른 점

의도는 그대로 지키되 표현을 이 코드베이스의 기존 관용구에 맞춘 지점이 넷이다.
FE 회신 문서: [contracts/competitor-monitoring-api-response-2026-08-12.md](../../contracts/competitor-monitoring-api-response-2026-08-12.md)

### ① 한도 초과는 400이 아니라 409

요청서가 400을 요구한 곳은 §2-3 PATCH 하나다. POST는 **이미 409
`BRAND_ACCOUNT_LIMIT_REACHED`를 내리고 있고 FE가 그 코드로 분기 중**이다. PATCH만 400으로
하면 "own이 6개 찼다"는 동일한 사건이 경로에 따라 다른 상태 코드로 온다 — 요청서 §2-2가
"재등록 = 타입 변경"을 §2-3과 동일 동작으로 명시했으므로 특히 어색하다. POST까지 400으로
바꾸는 것은 배포된 계약을 깬다.

의미론적으로도 409가 맞다: 400 `VALIDATION_FAILED`는 요청 자체가 잘못된 경우(값 공간 밖
`accountType`)로 이미 쓰이고, 한도 초과는 **현재 상태와의 충돌**이라 계정 하나를 지우거나
타입을 옮기면 같은 요청이 성공한다. FE는 이 둘을 상태 코드만으로 "입력을 고쳐라" vs "자리를
비워라"로 가를 수 있다.

요청서가 요구한 "구분 가능한 값"은 competitor 전용 코드 신설로 충족한다.

### ② 캠페인 방어는 요청 전체 400이 아니라 건별 실패

해당 엔드포인트는 contentIds를 최대 100건 받아 **건별 판정을 배열로 돌려주는 부분 성공
API**다. 현재 400을 내는 경우는 요청 형태 자체가 잘못됐을 때뿐이고(100건 초과,
trackingDays 범위 밖), 개별 콘텐츠 문제(중복·없는 게시물)는 한 건도 400이 아니다.

전체 400으로 하면 경쟁사 1건 때문에 정상 99건이 통째로 거절되고, "개별 콘텐츠 문제는 건별
판정"이라는 이 API의 규칙에 예외가 하나 생긴다.

### ③ /contents 기본 범위에 individual 포함

§5 참조. "own만"을 문자 그대로 구현하면 브랜드 미귀속 개인 추적 콘텐츠가 사라진다.

### ④ meta.limit 값 변경 (요청서에 없는 부수 변경)

§2 참조. 타입별로 갈리면 단일 값이 의미를 잃어 `limits`·`counts`를 추가하고 `limit`은
합산 최대(9)로 남긴다.

### 사실관계 정정

요청서 §2-4는 "지금은 합산 6개"라고 했으나 **서버 강제 한도는 10이다**(`ACCOUNT_LIMIT = 10`,
08-07 다계정 개정). FE가 6으로 막고 있어 6으로 보였다. own 6 / competitor 3으로 가면 합산
상한이 실질적으로 줄지만, 위 실측대로 깨지는 유저는 없다.

## 테스트 계획

기존 `V1BrandAccountsControllerTest`·`V1PerformanceDashboardController` 계열 테스트를 잇는다.

| 대상 | 케이스 |
|---|---|
| 마이그레이션 | 기존 행이 `own`으로 백필된다 / CHECK 밖 값 INSERT 거절 |
| POST | `accountType` 생략 → own / competitor 등록 / 값 공간 밖 400 |
| POST 재등록 | 같은 타입 = 멱등, 다른 타입 = 타입 변경(monitoring 재호출 없음) |
| 상한 | own 6개 후 7번째 409 `BRAND_ACCOUNT_LIMIT_REACHED` / competitor 3개 후 4번째 409 `COMPETITOR_ACCOUNT_LIMIT_REACHED` / own 6 + competitor 3 공존 가능 |
| 상한(타입 변경) | competitor 3개 찬 상태에서 own→competitor 변경 409 |
| PATCH | 타입 변경 200 / 남의 계정 403 / 숫자 아닌 id 404 / 값 공간 밖 400 |
| GET | 응답에 `accountType` / `meta.limits`·`counts` |
| DELETE | 타입과 무관하게 구독 해지(회귀 — 기존 동작 보존 확인) |
| contents | 기본이 competitor 제외 + individual 포함 / `competitor` / `all` / statusCounts 모수에도 적용 |
| comparison | 응답에 `accountType`, own·competitor 둘 다 포함 |
| 캠페인 | 경쟁사 게시물 → `failed` + `COMPETITOR_CONTENT_NOT_ALLOWED`, 같은 요청의 정상 콘텐츠는 그대로 성공 |
