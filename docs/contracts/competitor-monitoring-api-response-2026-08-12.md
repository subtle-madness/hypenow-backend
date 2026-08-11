# 경쟁사 모니터링 API 스펙 — 백엔드 회신

> 회신일 2026-08-12 / 대상: 프론트엔드
> 원 요청서: 경쟁사 모니터링 API 스펙 요청서(2026-08-11)
> 설계 문서: [specs/2026-08-12-competitor-account-type-design.md](../superpowers/specs/2026-08-12-competitor-account-type-design.md)

## 0. 결론 세 줄

- **요청서 §1 설계 원칙은 이미 그렇게 구현돼 있습니다.** 타입은 관계 테이블에 붙습니다. 그래서 이번 작업이 컬럼 하나로 끝납니다.
- **§2-5 DELETE는 이미 구독 해지입니다.** 다른 유저의 수집·화면에 영향 없습니다. 변경 없음.
- P0~P2를 **한 번에 전부** 구현합니다. 요청서와 다르게 가는 지점이 넷이고, 그중 FE 작업이 생기는 건 **세 개(전부 한 줄짜리)**입니다.

---

## 1. 먼저 확인 답변

### §1 — 타입을 관계에 둬야 한다

**맞고, 이미 그렇습니다.** 요청서가 그린 구조가 정확히 현재 스키마입니다.

```
brand_account (monitoring)        app.brand_monitorings (was)
──────────────────────────        ──────────────────────────
id                     ←───────── brand_id
username                          user_id
profile / 수집 상태...             username
                                  account_type   ★ 이번에 추가
                                  created_at / deleted_at
```

수집은 브랜드당 전역 1행이고 여러 유저가 공유합니다. 한 유저의 지정이 다른 유저 화면을 바꾸는 일은 구조적으로 일어나지 않습니다.

### §2-5 — DELETE가 구독 해지인가

**예.** 링크만 soft-delete하고, 그 브랜드에 남은 활성 링크가 0일 때만 monitoring 탈퇴를 부릅니다. 다른 유저가 같은 계정을 보고 있으면 수집이 그대로 유지됩니다. 이번 변경으로도 달라지지 않습니다(타입과 무관하게 동작 동일).

### 한 가지 사실관계 정정

**현재 서버 상한은 합산 6개가 아니라 10개입니다.** FE가 6으로 막고 있어서 6으로 보였을 겁니다. own 6 / competitor 3으로 가면 합산 상한이 실질적으로 줄어드는데, **운영 DB를 확인해 보니 최대 보유가 정확히 6건이라 깨지는 유저는 없습니다.**

다만 **own 6개를 이미 채운 유저가 2명 있습니다.** 이들은 경쟁사 3칸은 새로 열리지만 own은 더 못 넣습니다. 배포 후 안내를 설계하실 때, 이 유저들은 **기존 계정 하나를 경쟁사로 옮기면**(재등록 또는 PATCH) 그 순간 own 칸이 하나 비는 구조입니다 — 삭제 후 재등록을 안내할 필요는 없습니다.

---

## 2. 확정 계약

### 2-1. GET /v1/brand-monitoring/accounts

```json
{
  "success": true,
  "data": [
    {
      "id": "22",
      "accountType": "own",
      "profile": { "…": "기존 그대로" },
      "collectionStatus": "ready",
      "…": "나머지 기존 필드 그대로"
    }
  ],
  "meta": {
    "total": 7,
    "limit": 9,
    "limits": { "own": 6, "competitor": 3 },
    "counts": { "own": 5, "competitor": 2 }
  }
}
```

- `id` 의미 변경 없음(현행 monitoring 브랜드 id).
- 기존 등록분은 전부 `"own"`으로 백필됩니다. 경쟁사 지정은 배포 후 유저가 다시 합니다.
- **`meta`가 바뀝니다** — 아래 §3-③ 참조.

### 2-2. POST /v1/brand-monitoring/accounts

```json
{ "username": "cclime_official", "accountType": "competitor" }
```

`accountType` 생략 시 `"own"`(하위 호환). 값 공간 밖이면 400 `VALIDATION_FAILED`.

| 상황 | 응답 |
|---|---|
| 신규 | 202 + 계정 객체 (현행 그대로) |
| 다른 유저가 이미 수집 중 | 재수집 없이 구독만 생성, 기존 데이터 즉시 반환 (현행 그대로) |
| 이미 내 목록에 있음 + 같은 타입 | 202 멱등, 기존 객체 (현행 그대로) |
| 이미 내 목록에 있음 + 다른 타입 | **202 + 타입이 변경된 객체** (재수집 없음) |
| 대상 타입 상한 초과 | **409** (§3-① 참조) |

요청서 §2-2의 "이미 등록된 계정을 넣으면 경쟁사로 옮겨진다" UX 그대로입니다.

### 2-3. PATCH /v1/brand-monitoring/accounts/{id} (신규)

```
요청:  { "accountType": "competitor" }
응답:  200 + 갱신된 계정 객체 (2-1과 같은 셰이프)
```

`{id}`는 목록 응답의 `id`와 같은 값입니다. 재수집 없음. 남의 계정은 403, 숫자가 아닌 id는 404, 값 공간 밖 `accountType`은 400, 상한 초과는 409입니다.

### 2-4. 상한

| 타입 | 상한 |
|---|---|
| own | 6 |
| competitor | 3 |

서버가 하드 강제합니다. API 직접 호출도 막힙니다. 초과 응답은 §3-① 참조.

### 2-5. DELETE — 변경 없음

### 3-1. GET /v1/performance-dashboard/comparison

```json
{
  "accounts": [
    { "brandAccountId": "18", "username": "dr.wellmadeone", "accountType": "own",        "collectionStartedAt": "…", "buckets": [] },
    { "brandAccountId": "2",  "username": "cclime.beauty",  "accountType": "competitor", "collectionStartedAt": "…", "buckets": [] }
  ]
}
```

**own·competitor 둘 다 포함됩니다** — 요청서 요구대로이고, 사실 지금도 유저의 활성 구독 전량이 들어갑니다. `accountType` 필드만 새로 생깁니다.

### 3-2. GET /v1/performance-dashboard/contents — 집계 범위

쿼리 파라미터 `accountType`을 추가합니다.

| 값 | 범위 |
|---|---|
| **미지정 (기본)** | **own 브랜드 + 개인 추적(individual)** |
| `own` | 위와 동일 |
| `competitor` | 경쟁사만 |
| `all` | 전부 |

FE 의견(기본 own, 명시 시 competitor 포함) 그대로입니다. 한 가지만 다릅니다 — **기본값에 `source=individual` 콘텐츠가 포함됩니다.** 이 응답에는 브랜드에 귀속되지 않는 레거시 개인 추적 콘텐츠가 섞여 있어서, "own만"을 문자 그대로 구현하면 그것들이 통째로 사라지고 경쟁사를 등록하지도 않은 유저의 성과 요약 숫자가 이유 없이 줄어듭니다. 즉 **기본값 = "competitor만 제외"** 입니다.

**결과적으로 성과 요약·TOP 블록의 기존 호출은 손댈 필요가 없습니다.** 파라미터를 안 붙여도 지금과 같은 숫자가 나오고, 경쟁사가 섞여 들어오지 않습니다. TOP 블록에서 경쟁사를 보여줄 때만 `accountType=competitor` 또는 `all`을 붙이시면 됩니다. 계정별로 좁히는 건 기존 `brandAccountId` 파라미터 그대로입니다.

`meta.statusCounts`에도 이 필터가 적용됩니다(`source`·`sponsorship`과 같은 분류 필터 취급). 업로드 기간과 `status`만 모수에서 빠지는 기존 규칙은 그대로입니다.

### 3-3. 캠페인 연결 방어

`POST /v2/monitoring/campaigns/{campaignId}/contents`에서 막습니다. 응답은 §3-② 참조.

---

## 3. 요청서와 다르게 가는 지점

### ① 상한 초과는 400이 아니라 409

요청서는 §2-3(PATCH)에 `400 + ACCOUNT_LIMIT_EXCEEDED`를 적으셨는데, **POST는 지금도 409 `BRAND_ACCOUNT_LIMIT_REACHED`를 내리고 있고 FE가 그 코드로 분기 중**입니다. PATCH만 400으로 하면 "own이 6개 찼다"는 같은 사건이 경로에 따라 다른 상태로 옵니다 — 요청서 §2-2가 "재등록 = 타입 변경"을 §2-3과 동일 동작으로 명시하셨으니 특히 그렇습니다. POST까지 400으로 바꾸는 건 이미 배포된 계약을 깹니다.

그래서 **양쪽 다 409**로 통일하고, 코드로 구분합니다:

| 상황 | HTTP | code |
|---|---|---|
| own 6개 초과 | 409 | `BRAND_ACCOUNT_LIMIT_REACHED` (기존 코드 그대로) |
| competitor 3개 초과 | 409 | `COMPETITOR_ACCOUNT_LIMIT_REACHED` (신규) |
| `accountType` 값 오류 | 400 | `VALIDATION_FAILED` |

이러면 상태 코드만으로 **"입력을 고쳐라"(400) vs "자리를 비워라"(409)** 를 가를 수 있습니다. 한도 초과는 계정 하나를 지우거나 타입을 옮기면 같은 요청이 성공하는, 상태와의 충돌이니까요.

> **FE 작업**: 에러 분기에 `COMPETITOR_ACCOUNT_LIMIT_REACHED` 한 줄 추가. PATCH는 기존 409 처리기를 그대로 씁니다.
> 409여서 곤란한 사정이 있으면(예: 공용 인터셉터가 409를 특정 방식으로 가로챈다) 알려주세요 — PATCH만 400으로 바꾸는 건 한 줄입니다.

### ② 캠페인 연결 차단은 요청 전체 400이 아니라 건별 실패

그 엔드포인트는 contentIds를 **최대 100건 받아 건별 판정을 배열로 돌려주는 부분 성공 API**입니다. 지금 400이 나는 경우는 요청 형태 자체가 틀렸을 때뿐이고(100건 초과, trackingDays 범위 밖), 개별 콘텐츠 문제(중복·없는 게시물)는 한 건도 400이 아닙니다. 전체 400으로 하면 **경쟁사 1건 때문에 정상 99건이 통째로 거절됩니다.**

그래서 기존 `CAMPAIGN_CONTENT_ALREADY_EXISTS`와 같은 자리에 사유 코드를 하나 더 냅니다:

```json
{ "contentId": "C4jkl", "result": "failed", "monitoringItemId": null,
  "reasonCode": "COMPETITOR_CONTENT_NOT_ALLOWED",
  "reason": "경쟁사 계정의 게시물은 캠페인에 연결할 수 없어요." }
```

> **FE 작업**: 사유 코드 맵에 `COMPETITOR_CONTENT_NOT_ALLOWED` 한 줄 추가.
> 진입점을 이미 막아 두셨으니 정상 UI에서는 볼 일이 없는, API 직접 호출용 이중 방어입니다.

**한계 하나를 명시해 둡니다**: 유저가 경쟁사 게시물 URL을 **개인 추적으로 직접 등록**해 둔 경우는 막지 않습니다. 그건 구독과 무관하게 만들어진 본인의 개인 추적 아이템이라, 구독 타입만으로는 경쟁사 게시물이라고 판정할 근거가 없습니다.

### ③ meta.limit 값이 바뀝니다

타입별로 갈리면 단일 `limit`은 의미를 잃습니다. `limits`·`counts`를 추가하고, `limit` 키는 호환용으로 남기되 값은 **합산 최대인 9**가 됩니다.

> **FE 작업(권장, 강제 아님)**: 한도를 하드코딩하는 대신 `meta.limits`를 읽으면 나중에 서버가 한도를 바꿔도 FE 배포가 필요 없습니다. 요청서대로 하드코딩을 유지해도 동작합니다.

### ④ `/contents` 기본 범위에 개인 추적 포함

§2 3-2에서 설명했습니다. FE 작업 없음 — 오히려 **기존 호출을 안 고쳐도 되는 이유**입니다.

---

## 4. FE 체크리스트

| # | 할 일 | 크기 |
|---|---|---|
| 1 | 에러 분기에 `COMPETITOR_ACCOUNT_LIMIT_REACHED`(409) 추가 | 한 줄 |
| 2 | 캠페인 사유 코드 맵에 `COMPETITOR_CONTENT_NOT_ALLOWED` 추가 | 한 줄 |
| 3 | 한도를 `meta.limits`에서 읽기 (권장) | 선택 |
| 4 | `competitor-accounts.ts`가 서버 `accountType`을 읽도록 전환 | 요청서 §5 계획 그대로 |
| 5 | 배포 후 "경쟁사를 다시 지정해 주세요" 안내 | 요청서 §2-1 계획 그대로. own 6개 채운 2명은 타입 이동으로 해결됨 |

성과 요약·TOP·comparison의 기존 호출은 **바꿀 것이 없습니다.**
