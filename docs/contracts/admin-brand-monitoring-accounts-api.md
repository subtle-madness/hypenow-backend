# 어드민 브랜드 목록 계정 API

> 상태: 🟢 활성 · 2026-09-03

어드민 화면의 "등록된 브랜드 목록" 표를 위한 조회 전용 API. `GET /v1/admin/brand-monitoring/accounts` 하나뿐이다. 구현 코드는 `was/src/main/java/com/celfit/was/v1/admin/AdminBrandAccountsController.java` · `AdminBrandAccountService.java` · `AdminBrandAccountRow.java` - 코드가 정본이고 이 문서는 옮겨 적은 것이다.

- 대상 독자: 프론트엔드 개발자, was 구현 에이전트
- 공통 규약(응답 envelope, 날짜 포맷, 인증 방식)은 [monitoring-frontend-api-spec.md](monitoring-frontend-api-spec.md) §1을 그대로 재사용한다. 이 문서는 이 표면 고유의 파라미터·필드 계약만 다룬다.

## 1. 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/v1/admin/brand-monitoring/accounts` | 등록된 브랜드 목록(연결 단위) |

목록은 서버에서 **최대 60초 인메모리 캐시**된다(2026-09-04 — monitoring-ro 커넥션 풀을 실사용자 API와 공유해서 매 요청 재조회를 피했다). 정렬·검색·페이지는 캐시된 목록에서 동작해 DB에 새로 쿼리를 날리지 않는다 - 등록·해지 직후 이 목록에 반영되기까지 최대 60초 지연될 수 있다.

## 2. 인증·인가

세션 쿠키 인증 + ADMIN 롤. `SecurityConfig`가 `/v1/admin/**` 전체에 `hasRole("ADMIN")`을 앞단에서 걸어서, 이 컨트롤러는 조회 로직만 담당하고 인가 코드를 따로 두지 않는다.

| 상황 | 응답 |
|---|---|
| 미인증(세션 없음) | 401, `error.code = "UNAUTHORIZED"` |
| 인증됐지만 ADMIN 아님 | 403, `error.code = "FORBIDDEN"` |

## 3. 쿼리 파라미터

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `offset` | int (≥0) | - | 시작 오프셋. 주어지면 `page`보다 우선한다. 음수·비정상값은 0으로 방어(400 아님). |
| `page` | int (≥1) | 1 | `offset`이 없을 때만 쓰인다. `(page-1) * limit`으로 환산. page<1은 1로 방어. |
| `limit` | int | 20 | 페이지 크기. 1~100으로 클램프(범위 밖 값을 줘도 400이 아니라 잘려서 적용). |
| `sort` | string | `registeredAt:desc` | `<key>:<asc\|desc>` 형식. 아래 §4 참조. 형식·키·방향이 잘못되면 400. |
| `q` | string | - | 검색어. 앞뒤 공백 제거 후 빈 문자열이면 필터 없음 취급. §5 참조. |

`offset`과 `page`를 둘 다 주면 `offset`이 이긴다(`page`는 완전히 무시).

## 4. 정렬(`sort`)

형식은 정확히 `<key>:<asc|desc>` - 콜론 하나로 키와 방향을 구분한다. 지원 키는 다음 6개뿐이고, 그 외 문자열이나 콜론이 없는 값, `asc`/`desc`가 아닌 방향은 전부 `400 VALIDATION_FAILED`다.

| 키 | 정렬 기준 |
|---|---|
| `user` | 등록한 유저의 이메일, 대소문자 무시 |
| `username` | 브랜드 계정 아이디, 대소문자 무시 |
| `postCount` | §6 `postCount` 값 |
| `crawlingCalls` | §6 `crawlingCalls.total` 값(월간 값이 아니라 전체 누적 기준) |
| `collectionStatus` | 상태 문자열 알파벳순(`collecting` < `error` < `ready`). 값이 `null`인 행(monitoring 비활성 또는 계정 미확인)은 가장 작은 값으로 취급한다(오름차순이면 맨 앞, 내림차순이면 맨 끝) |
| `registeredAt` | 이 유저가 이 계정을 등록한 시각(연결 생성 시각) |

**동점 처리(타이브레이크)** - 지정한 정렬 키로 값이 같은 행들은 정렬 방향과 무관하게 항상 다음 순서로 갈린다: `registeredAt` 내림차순 → `accountId` 오름차순 → `user.id` 오름차순. 그래서 같은 조건으로 여러 번 조회해도 페이지 경계에서 행 순서가 흔들리지 않는다.

## 5. 검색(`q`)

브랜드 계정 아이디(`username`) **또는** 등록한 유저의 이메일에 대소문자 무시 부분일치(substring)로 매칭한다. 둘 중 하나만 맞아도 포함된다. 공백만 있는 값은 검색 없음과 동일하게 취급한다.

## 6. 응답

```jsonc
{
  "success": true,
  "data": [ /* AdminBrandAccountRow[] */ ],
  "meta": { "total": 128, "limit": 20, "offset": 0 }
}
```

`meta.total`은 `q` 필터를 적용한 **뒤**, 페이지 슬라이스를 적용하기 **전** 전체 건수다.

### 6.1 행 단위 - 연결 1개 = 1행

이 API의 행은 브랜드 계정이 아니라 **연결**(`app.brand_monitorings` 활성 행)이다. 같은 브랜드 계정을 여러 유저가 각자 등록해 놓았으면, 그 계정은 유저 수만큼 여러 행으로 나온다. 이때 계정 단위 값(`postCount`, `crawlingCalls`)은 **행마다 그대로 중복 표시**된다 - 두 유저가 같은 계정을 보고 있으면 postCount·crawlingCalls는 두 행에서 완전히 같은 값이다. 행을 유일하게 식별하려면 `accountId` + `user.id` 조합을 써야 한다(accountId만으로는 여러 행에 걸칠 수 있다).

### 6.2 필드

| 필드 | 타입 | null 가능 | 설명 |
|---|---|---|---|
| `accountId` | string | - | 브랜드 계정 id(monitoring `brand_account.id`를 문자열로). |
| `username` | string | - | 브랜드 계정 아이디. monitoring이 살아 있으면 계정의 최신 관측값(스윕이 매일 갱신), monitoring이 꺼져 있으면 등록 시점 스냅샷(연결 행에 저장된 값)으로 대체된다. |
| `mode` | string | - | `"own"` 또는 `"competitor"`. 계정이 아니라 **이 연결**의 속성이라, 같은 계정이라도 유저마다 다를 수 있다. |
| `user.id` | number | - | 등록한 유저 id. |
| `user.email` | string | - | 등록한 유저 이메일. |
| `user.name` | string | O | 유저 이름. DB 기본값이 빈 문자열이라, 빈 문자열은 `null`로 바꿔 내려준다. |
| `user.orgName` | string | O | 소속명(`app.users.company_name`). 위와 같은 규칙으로 빈 문자열은 `null`. |
| `postCount` | number | - | **총 수집량**. `brand_tagged_post`(tagged + direct + hashtag 통합 풀) 전체 행 수 - 삭제·비공개(`unavailable_at` 有) 행도 포함한다. 사용자 화면(브랜드 상세 게시물 목록)의 365일 창 컷·정산(enriched) 필터가 적용된 개수와는 **다른 지표**다. monitoring이 꺼져 있거나 계정이 monitoring DB에서 확인되지 않으면 0. |
| `crawlingCalls.total` | number | - | 이 계정에 쌓인 Hiker 콜 누적 합(`brand_call_count` 전체). 계정 단위 값이라 이 계정을 보는 모든 행에서 동일하다. |
| `crawlingCalls.month` | number | - | 이번 달(KST 기준 이번 달 1일 0시부터 지금까지) 콜 합. 단가(`unitPriceUsd`)는 이 응답에 없다 - 어드민 크롤링 비용 카드 API(`GET /v1/admin/users/{id}/crawling-usage`)의 전역 단가를 프론트가 곱해서 쓴다. |
| `collectionStatus` | string | O | `"collecting"` \| `"ready"` \| `"error"` 중 하나. 사용자 화면(`BrandAccountResponse.collectionStatus`)과 완전히 같은 유도 규칙(`BrandAccountAssembler.collectionStatus`) - `last_swept_on` 있으면 ready, 없어도 `last_swept_at`이 있으면 ready(첫 백필 완결·재가입·기간 확장 중), 그 외엔 `backfill_error` 유무로 error/collecting을 가른다. monitoring이 꺼져 있거나 계정이 monitoring DB에서 확인되지 않으면 `null`. |
| `collectionMonths` | number | - | 이 연결이 신청한 표시 기간(1\|3\|6\|12개월). 계정 자산 값(유저 간 max)이 아니라 **이 연결**에 저장된 값이다. |
| `backfillCompletedAt` | string(KST ISO) | O | 최초 백필 완료 시각. 미완료면 `null`. |
| `registeredAt` | string(KST ISO) | - | 이 유저가 이 계정을 등록(연결)한 시각. |
| `lastCollectedAt` | string(KST ISO) | O | 계정의 마지막 스윕 완료 시각(`brand_account.last_swept_at`) - 사용자 화면 `BrandAccountResponse.lastTrackedAt`과 같은 의미. 아직 한 번도 스윕이 돌지 않았으면 `null`. |

## 7. monitoring 비활성 시 동작

`monitoring.enabled=false`면 monitoring DB 조회 빈이 아예 없다. 이때도 API는 500이 아니라 200을 낸다 - 행은 `app.brand_monitorings`(app 스키마, monitoring과 무관)와 `app.users`만으로 구성되고, monitoring 유래 필드는 다음처럼 빈 값으로 내려간다.

- `postCount` = 0
- `crawlingCalls.total` / `crawlingCalls.month` = 0
- `collectionStatus` = `null`
- `backfillCompletedAt` / `lastCollectedAt` = `null`
- `username`은 이때만 연결 행에 저장된 등록 시점 스냅샷 값을 쓴다(§6.2 참조).

## 8. 예시

```
GET /v1/admin/brand-monitoring/accounts?limit=2&sort=postCount:desc&q=beauty
```

```jsonc
{
  "success": true,
  "data": [
    {
      "accountId": "42",
      "username": "beauty_official",
      "mode": "own",
      "user": { "id": 7, "email": "marketer@brand.io", "name": "김마케팅", "orgName": "하입나우" },
      "postCount": 318,
      "crawlingCalls": { "total": 5210, "month": 340 },
      "collectionStatus": "ready",
      "collectionMonths": 6,
      "backfillCompletedAt": "2026-08-10T09:12:00+09:00",
      "registeredAt": "2026-08-10T09:00:00+09:00",
      "lastCollectedAt": "2026-09-02T02:14:00+09:00"
    }
  ],
  "meta": { "total": 1, "limit": 2, "offset": 0 }
}
```
