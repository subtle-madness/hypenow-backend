# 브랜드 direct 게시물 파이프라인 통합 설계 (2026-08-18)

> 상태: 🟢 활성

브랜드 모니터링의 **직접 등록(direct) 게시물**을 레거시 추적 파이프라인(`monitoring_items` →
monitoring `target`/`post_snapshot`)에서 떼어내 **브랜드 수집 파이프라인**(`brand_account` /
`brand_tagged_post` / `brand_post_*`)으로 합류시킨다. 등록 경로(tagged·direct)에 따라 카드의
셰이프·수집 주기·표시 규칙이 갈리는 현행 이중 시스템을 없애는 것이 목적이다.

---

## 0. 한 장 요약

| 축 | 현행 | 통합 후 |
|---|---|---|
| direct 링크 저장 | `app.brand_direct_posts`(유저 스코프) → `app.monitoring_items` → monitoring `target` | monitoring `brand_tagged_post.direct_registered_at`(브랜드 스코프). `app.brand_direct_posts`는 **유저 귀속 원장**으로만 존치 |
| direct 지표·메타·댓글 | 레거시 `post_snapshot`·`post_meta`·`post_comment` | `brand_post_snapshot`·`brand_post_meta`·`brand_post_comment` (tagged와 같은 테이블) |
| direct 수집 | 레거시 일일 스윕(KST 02:00) — 추적 기간 내 매일 프로필 열거 + 단건 콜 | 브랜드 스윕(KST 03:00) 2단계 — `BrandCrawlPolicy` 나이 티어로 단건 콜 |
| 카드 조립 | `taggedPost()` / `directPost()` 두 벌 + `mergeByShortcode` | 한 벌(`brandPost()`), 병합 소멸 |
| `source` 값 | 산지 테이블이 다름 | `direct_registered_at IS NOT NULL ? "direct" : "tagged"` 파생값 |
| 등록 상태 폴링 | 레거시 `monitoring_registrations` 위임 + seq 인덱스 매칭 + share 지연 매핑 | 전용 `app.brand_post_registrations` + 전용 실행기(브랜드 id가 등록 행에 있음) |
| 캠페인 연결 | `monitoring_items.campaign_id` 1개, direct 전용 | `app.brand_post_campaigns` N:M, tagged·direct 공통 |

**FE 계약 변경 4건**(§6): `trackingDays` 무시 · `trackingStatus` 항상 `tracking` ·
`monitoringItemId` 항상 null · 성과 대시보드 direct 콘텐츠의 `item.id`가 `bt_<shortcode>`로 변경.

---

## 1. 배경 — 현행 비대칭

`BrandPostAssembler`는 같은 `BrandPostResponse` 셰이프를 두 산지에서 각각 만든다. 실제로 값이
갈리는 지점은 아래와 같다(전부 코드에서 확인, 근거 파일 병기).

| 필드·동작 | tagged | direct(현행) | 근거 |
|---|---|---|---|
| `videoUrl`·`videoDuration` | `brand_post_meta`에서 채움 | **null 고정** | `BrandPostAssembler.directPost` — "레거시 수집엔 영상 원본 URL·길이가 없다" |
| `authorIsVerified` | `author_profile.is_verified` | **false 고정** | 같은 곳 — "거짓 배지를 띄우지 않기 위해 false 고정" |
| `isPaidPartnership` | `brand_post_meta.is_paid_partnership` | **null**(캡션 키워드만) | 같은 곳 + `mergeByShortcode`의 협찬 승격 |
| `authorFollowers`·`authorFullName`·프로필 이미지 | `author_profile`(전역 캐시, 30일 stale 갱신) | 레거시 `profile_meta`/`profile_snapshot` 수집 여부에 좌우 | `TrackingItemAssembler` |
| 스냅샷 노출 컷 | 없음(`brand_post_snapshot` 전량) | **전역 `sweep_run` 워터마크**(`max(completed_at) WHERE ok`)로 절단 | `MonitoringReadRepository.lastSuccessfulSweepAt` + `findSnapshots(codes, lastCollectedDate)` |
| `campaignIds` | **항상 빈 목록** | `monitoring_items.campaign_id` 1개 | `BrandPostAssembler` |
| `trackingStatus` | 항상 `tracking` | 레거시 상태기계(`collecting`/`tracking`/`ended`/`error`/…) | `ItemStatus.derive` |
| 표시 창 | 링크 창(`brand_monitorings.collection_months`) ∩ 365일 자산 창 | **창 무관 항상 포함** | MON-BT 트랙 08-17 |
| 취소 | 불가(400 `TAGGED_POST_NOT_CANCELABLE`) | 가능(204, 매핑 hard delete) | 계약 v2.10 §8-2 |
| 겹침 시 카드 | — | **direct가 이김** → 위 null·false 고정이 tagged 관측을 덮음 | `mergeByShortcode` |

마지막 줄이 핵심 해악이다. 사진 태그 + 직접 등록이 겹치면, 브랜드 스윕이 이미 관측한
`videoUrl`·`isPaidPartnership`·인증 배지가 있는데도 카드가 **영구히 direct 셰이프로 고정**된다.
`V1BrandDirectPostService.brandShortCodes`의 "미정산분까지 전량을 중복으로 잡는다"는 방어도 이
고정을 피하려는 우회였다.

부수적으로 얽혀 있는 것들:

- **share 지연 매핑**: 레거시 등록 행에 브랜드가 없어서, share 단축 링크로 등록한 건의 브랜드를
  폴링 시점에 추정한다(`resolveLazyMappingBrand` — 형제 매핑 → 단일 활성 링크 폴백). PP 트랙
  후속 #1이 지적한 대로 이 폴백에는 구독 타입 검사가 없어, 유일한 링크가 competitor인 유저가
  폴링만으로 **새 경쟁사 매핑**을 만든다.
- **seq 인덱스 매칭**: 레거시 `register`가 돌려주는 `items`가 입력과 1:1이 아니어서
  `monitoring_registration_entries.seq == 위임 목록 인덱스`라는 암묵 규약에 의존한다.
- **성과 대시보드**: `PerformanceContentAssembler`가 individual·direct·tagged 3계열을 shortcode로
  합치고 스냅샷을 지표별로 병합한다(`mergeSnapshots`) — direct가 브랜드 파이프라인으로 오면 이
  3계열이 2계열(individual·브랜드 풀)이 된다.

---

## 2. 결정

### 결정 1 — direct는 `brand_tagged_post`에 두 개의 시각 컬럼으로 합류한다 (별도 테이블 아님)

`brand_tagged_post`에 컬럼 2개를 추가한다. `source`라는 단일 enum 컬럼은 **두지 않는다**.

```sql
ALTER TABLE brand_tagged_post
    ADD COLUMN tag_detected_at      timestamptz DEFAULT now(),  -- 태그 열거가 이 링크를 처음 만난 시각
    ADD COLUMN direct_registered_at timestamptz;                -- 직접 등록된 시각(취소 시 NULL로 되돌림)
UPDATE brand_tagged_post SET tag_detected_at = first_seen_at WHERE tag_detected_at IS NULL;
```

- **`source`는 파생값이다**: `direct_registered_at IS NOT NULL → "direct"`, 아니면 `"tagged"`.
  현행 `mergeByShortcode`의 "direct 우선" 규칙이 그대로 표현되고, 두 컬럼과 따로 관리해야 하는
  세 번째 상태가 생기지 않는다.
- **두 컬럼이 독립인 이유**: 한 게시물이 태그 발견분이면서 동시에 직접 등록분일 수 있다.
  PK가 `(brand_id, short_code)`라 행은 하나뿐이므로, "어떻게 들어왔는가"를 단일 값으로 접으면
  취소 시 "태그 발견 사실"을 잃는다.
- `tag_detected_at`의 `DEFAULT now()`는 **롤링 배포 전용 장치**다. 구버전 monitoring 파드의
  `TaggedPostRepository.insert`는 이 컬럼을 모르므로 DEFAULT가 값을 채워야 한다. direct 경로는
  명시적으로 `NULL`을 써서 DEFAULT를 무력화한다. contract 단계에서 DEFAULT를 제거한다.

**기각한 대안 — monitoring에 `brand_direct_post` 별도 테이블**: `brand_post_snapshot`·
`brand_post_meta`·`brand_post_comment`·`author_profile`은 이미 **게시물 전역**(브랜드 무관, PK가
`short_code`)이다. 즉 지표·메타·댓글·게시자 계층은 통합에 아무 변경도 필요 없고, 브랜드별
링크 테이블 하나만 갈라진다. 별도 테이블은 모든 조회에 UNION을 강제하고 `BrandCrawlPolicy`의
due 판정·깊이 컷 로직을 두 벌로 만든다. 얻는 것이 없다.

**기각한 대안 — direct 링크를 유저 스코프로 유지**(`app.brand_direct_posts`를 서빙 조인으로 존치):
수집(monitoring)과 가시성(app)의 정본이 두 DB로 갈려, "아무도 안 보는 direct 행의 수집을 언제
멈추는가"가 크로스 DB 조율 문제가 된다. 취소 때마다 "이 유저가 마지막인가"를 판정해 monitoring에
해제 명령을 보내야 하고, 그 명령이 유실되면 콜이 최대 180일간 조용히 샌다.

#### 1-1. direct 등록은 브랜드 스코프로 승격한다

통합 후 direct 행은 **그 브랜드에 활성 연결된 모든 유저**에게 보인다(tagged와 동일). 취소 권한도
같은 집합이다.

- 근거: tagged 풀은 이미 브랜드 스코프 공유다. "측정 풀 게시물은 유저 관점 단일 개념"이라는 제품
  방향에서, 같은 브랜드에 붙은 두 사람이 서로 다른 풀을 보는 것이 오히려 예외다. 경쟁사 구독
  브랜드는 직접 등록 자체가 403(`COMPETITOR_ACCOUNT_NOT_ALLOWED`)이라 타 팀 오염 경로가 없다.
- 대가: 같은 브랜드에 유저가 둘 이상이면 **A가 등록한 게시물을 C가 취소할 수 있다**. 이는 실제
  동작 변경이므로 §7 운영 확인 항목에 등재한다.
- `app.brand_direct_posts`는 **누가 등록했는가**의 원장으로 남긴다(탈퇴 아카이브 대상 유지,
  향후 "등록자 표시" 여지). 서빙 조인에서는 빠진다.

### 결정 2 — 등록 UX 계약은 유지하고, 실행 경로만 전용 표면으로 바꾼다

FE가 보는 계약(`POST /v1/brand-monitoring/accounts/{accountId}/direct-posts` 202 +
`GET /v1/brand-monitoring/direct-registrations/{registrationId}` 폴링, entry 4종 어휘,
`POST /v1/brand-monitoring/posts/{postId}/cancel` 204/400/404)은 **바뀌지 않는다**. 바뀌는 것은
그 뒤다.

#### 2-1. 저장소 — 레거시 등록 테이블 위임을 끊고 전용 테이블을 쓴다

```sql
CREATE TABLE app.brand_post_registrations (
    id           bigserial   PRIMARY KEY,
    user_id      bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id     bigint      NOT NULL,          -- monitoring brand_account.id 논리 참조
    campaign_id  bigint      REFERENCES app.monitoring_campaigns(id) ON DELETE SET NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE TABLE app.brand_post_registration_entries (
    registration_id bigint NOT NULL REFERENCES app.brand_post_registrations(id) ON DELETE CASCADE,
    seq             int    NOT NULL,
    input           text   NOT NULL,
    short_code      text,                       -- 파싱·share 해소 결과(미해소면 null)
    result          text   NOT NULL,            -- pending|success|failed|duplicate
    reason_code     text,
    reason          text,
    settled_at      timestamptz,
    PRIMARY KEY (registration_id, seq)
);
```

- **`brand_id`가 등록 행에 있다** → `resolveLazyMappingBrand`(형제 매핑 → 단일 링크 폴백) 전체가
  삭제된다. PP 트랙 후속 #1의 "타입 검사 없는 폴백으로 경쟁사 매핑이 생긴다"가 구조적으로 소멸한다.
- **입력마다 우리가 직접 entry를 만든다** → `seq == 위임 인덱스` 암묵 규약이 사라진다.
- 결과 어휘(`pending|success|failed|duplicate`)와 `reasonCode` 문자열은 **레거시 상수를 그대로
  재사용**한다(`RegistrationResult`, `MonitoringInput.Invalid`의 reasonCode). FE 분기가 그대로 돈다.
- `canceled` → `failed` 접기 규칙은 산지 자체가 없어져 불필요해진다.

**기각한 대안 — `app.monitoring_registrations`에 `brand_id` 컬럼 추가해 재사용**: 통합의 목적이
레거시 결합을 끊는 것인데, 브랜드 등록 상태를 레거시 테이블에 계속 실으면 레거시 철거가 브랜드
트랙에 발목을 잡힌다. 전용 테이블은 실행기 코드가 한 벌 늘어나는 대신 두 계보를 완전히 분리한다.

#### 2-2. 실행 — was 전용 실행기가 monitoring 명령 API를 게시물 단위로 호출한다

`BrandDirectRegistrationExecutor`(was, `MonitoringRegistrationExecutor`와 같은 구조:
`afterCommit` 제출, 전용 풀, 5분 stale 복구)가 entry를 순서대로 처리한다.

```
entry 1건 처리:
  ① input 파싱 → shortCode. 파싱 실패면 즉시 failed(레거시 reasonCode 재사용)
  ② share 단축 링크면 POST /api/share/resolve (기존 API) → shortCode
  ③ 브랜드 풀 중복 재검사(§2-3) → duplicate면 확정
  ④ POST /api/brands/{brandId}/direct-posts  {shortCode}   ← 신규 monitoring 명령
       201 → success       404 POST_NOT_FOUND → failed
       200 → success(이미 풀에 있음, 멱등)    422 PRIVATE_ACCOUNT → failed
       503/타임아웃 → pending 유지(stale 복구가 재시도)
```

monitoring 쪽 `POST /api/brands/{brandId}/direct-posts`는 **동기로 완결**한다:

```
HikerClient.fetchPost(shortCode)          … 1콜
  → BrandSnapshotWriter.savePost(today, post)      (brand_post_snapshot + brand_post_meta)
  → brand_tagged_post upsert(direct_registered_at = now(), author_username/ig_user_id/taken_at)
  → BrandCollectService.enrich(brand, [post])      (게시자 0~1콜 + 댓글 0~3콜 + markEnriched)
```

- 게시물당 최대 5콜(≈7초). was 실행기가 비동기라 유저 응답은 막지 않고, entry가 하나씩 정산되며
  FE 폴링에 순차 반영된다.
- **`enrich`가 `enriched_at`을 찍고 끝나므로, entry가 success가 된 순간 그 카드는 이미 목록에
  뜬다.** 08-13 완결 배치 서빙 게이트(`enriched_at IS NOT NULL`)와 정합한다. 현행처럼 "등록은
  성공인데 카드는 collecting"인 중간 상태가 없어진다.
- `PostInfo`는 단건 응답과 태그 열거 응답이 **같은 레코드**다(`HikerClient.toPost` 공용). 즉
  `videoUrl`·`videoDuration`·`isPaidPartnership`·`ownerUserId`·`views`가 direct에도 그대로 실린다
  — §1의 비대칭 전부가 이 한 지점에서 해소된다. 단건 응답은 `viewsTrusted=true`라 조회수 신뢰도도
  열거 경로보다 낫다.

> **"단건 게시물 콜 전면 금지"(08-06·08-09) 결정과 충돌하지 않는다.** 그 결정은 *태그 열거로 이미
> 얻은 게시물에 단건 콜을 덧붙이는 것*(정책 문서의 "게시물당 단건 상세 콜 1회" 제안)을 기각한
> 것이고, 근거는 "열거 대비 추가 지표가 없다"였다. direct 게시물은 애초에 열거에 실리지 않으므로
> 그 근거가 성립하지 않는다. 게다가 레거시 url 모드는 지금도 같은 콜(`CollectService.collectPost`
> /`collectTrackedPost` → `/v2/media/info/by/code`)을 쓰고 있다 — 통합은 그 콜의 소유자를 옮기는
> 것이지 새 콜을 도입하는 것이 아니다.

#### 2-3. 중복 판정 — 브랜드 풀 기준 한 줄로 접는다

```
duplicate ⟺ (brand_id, short_code) 행이 존재하고
            ( direct_registered_at IS NOT NULL              -- 이미 직접 등록됨
              OR taken_at >= 링크 표시 창 컷 )                -- 이미 목록에 보이는 tagged
```

- 링크 표시 창 컷 = `max(오늘 − collection_months, 오늘 − 365일)` (현행 `brandShortCodes`와 동일).
- **현행 대비 개선 1**: 현행은 `directPostRepository.shortCodesByUser(userId)`로 **그 유저의 모든
  브랜드** direct 매핑을 중복 모수에 넣는다. 브랜드 A에 등록한 게시물이 브랜드 B 등록에서 중복으로
  거절되는 크로스 브랜드 누수였다. 브랜드 스코프 판정이 이를 없앤다.
- **현행 대비 개선 2**: 창 밖 tagged는 duplicate가 아니라 **승격 대상**이 된다. 등록하면
  `direct_registered_at`이 채워지고, direct 행은 표시 창 예외(§3-3)라 그 자리에서 보이기 시작한다.
  08-17 "데드엔드" 우회(창 밖 tagged를 direct 셰이프로 고정하는 의도된 대가)가 대가 없이 해소된다.
- **현행 대비 개선 3**: 현행이 "미정산 tagged까지 중복으로 잡아야 한다"고 방어한 이유(카드가 영구
  direct 셰이프로 고정)는 셰이프가 하나가 되면서 사라진다. 미정산 tagged를 직접 등록해도 같은 행에
  `direct_registered_at`이 붙을 뿐이다.

#### 2-4. 취소 — 매핑 삭제가 아니라 direct 표식 해제

```
POST /v1/brand-monitoring/posts/{postId}/cancel
  행 없음                                        → 404
  direct_registered_at IS NULL(순수 tagged)      → 400 TAGGED_POST_NOT_CANCELABLE
  direct + tag_detected_at IS NOT NULL           → direct_registered_at = NULL  … 204, 카드는 tagged로 잔존
  direct + tag_detected_at IS NULL               → 행 DELETE                    … 204, 목록에서 즉시 제거
```

이것은 **현행 동작의 정확한 재현**이다. 지금도 겹침 게시물을 취소하면 `brand_direct_posts` 행만
지워지고 tagged 행이 그대로 남아 카드가 tagged로 잔존한다(`assembleForBrand`의 direct 목록에서만
빠진다). 계약 v2.10 §8-2의 "취소 후 즉시 사라진다"는 순수 direct 건에 대한 서술이다.

- 행 DELETE는 링크만 지운다. `brand_post_snapshot`·`brand_post_meta`·`brand_post_comment`는
  게시물 전역 자산이라 **보존**한다(브랜드 스키마의 "윈도우 이탈 후에도 영구 보존" 규칙 유지).
  재등록 시 이력이 그대로 되살아나는 부수 효과가 있다.
- 재등록 가능성은 유지된다: 행이 없거나 `direct_registered_at`이 NULL이므로 §2-3의 duplicate 조건에
  걸리지 않는다(창 안 tagged 잔존 건은 여전히 duplicate — 이미 보이므로 정상).
- was는 `DELETE /api/brands/{brandId}/direct-posts/{shortCode}`(신규)를 호출하고, `app.brand_direct_posts`의
  유저 원장 행도 함께 지운다. **원격 실패는 삼키지 않고 전파**한다(현행 `cancelLegacyIfPossible`의
  판단 근거를 그대로 승계 — 원장만 지우면 monitoring은 계속 수집하는데 화면에서만 사라진다).

#### 2-5. 발견 카드(`hashtag-posts`)의 `brandPostId`

`BrandHashtagPostAssembler`의 08-18 규칙(tagged 겹침 행 제외, direct 매핑 행은 유지, `brandPostId`는
direct 승격분에만)을 통합 스키마로 그대로 번역한다.

| 현행 판정 | 통합 후 판정 |
|---|---|
| `directCodes` = `brand_direct_posts` 중 이 유저·이 브랜드 | `direct_registered_at IS NOT NULL` |
| `taggedCodes` = `findExistingTaggedShortCodes` | `tag_detected_at IS NOT NULL` |
| 제외 조건: `!direct && tagged` | `tag_detected_at IS NOT NULL AND direct_registered_at IS NULL` |
| `brandPostId`: direct면 shortcode | `direct_registered_at IS NOT NULL`이면 shortcode |

조회는 브랜드 풀 1회(`SELECT short_code, tag_detected_at, direct_registered_at ...`)로 줄어든다
(현행은 app 매핑 조회 + monitoring 존재 조회 2회).

### 결정 3 — 캠페인 연결은 was 소유의 N:M 링크 테이블로 옮기고 tagged에도 연다

```sql
CREATE TABLE app.brand_post_campaigns (
    brand_id    bigint      NOT NULL,          -- monitoring brand_account.id 논리 참조
    short_code  text        NOT NULL,
    campaign_id bigint      NOT NULL REFERENCES app.monitoring_campaigns(id),  -- CASCADE 금지
    user_id     bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, short_code, campaign_id)
);
```

- **경계**: 캠페인은 서비스 데이터다. monitoring `brand_tagged_post`에 `campaign_id`를 얹으면
  monitoring이 app 개념을 알게 되므로 하지 않는다. `BrandPostResponse.campaignIds`는 이미 **목록
  타입**이라 응답 계약 변경이 없다.
- **`campaign_id` FK에 CASCADE를 걸지 않는다.** `ArchiveCascadeReachabilityTest`가
  `app.monitoring_campaigns`의 CASCADE 자식이 0개일 것을 강제한다(`CampaignRepository.delete`가
  캠페인 1행만 아카이브·삭제한다는 전제). 캠페인 삭제 경로에서 이 테이블을 **명시적으로 아카이브
  후 삭제**한다 — `brand_direct_posts`가 `monitoring_items`에 대해 이미 쓰는 패턴(V20260811090500)과
  같다.
- **`user_id`는 CASCADE 유지 + 아카이브 카탈로그 등재** — 탈퇴 시 아카이브 없이 사라지면 CI가
  잡는다.
- **이관**: 기존 direct 건의 캠페인은 `monitoring_items.campaign_id`에 있다. 이관 잡이
  `brand_direct_posts` ⋈ `monitoring_items`로 읽어 이 테이블에 1행씩 옮긴다(§결정 4).
- **tagged도 캠페인에 붙일 수 있게 된다**(현행은 `campaignIds` 항상 빈 목록). 다만 **부착·해제 API는
  이번 범위 밖**이다 — 기존 v2 경로(`V2CampaignContentService` → `CampaignItemLinker` →
  `monitoring_items.campaign_id`)를 브랜드 풀로 옮기는 것은 별도 태스크다. 이번 릴리스에서
  `brand_post_campaigns`에 행을 만드는 경로는 **직접 등록 시 `campaignId` 파라미터**와 **이관 잡**
  둘뿐이다.

**성과 대시보드 이행**(`PerformanceContentAssembler`):

- 3계열(individual·direct·tagged) → **2계열**(individual = 브랜드 풀에 없는 레거시 아이템,
  브랜드 풀 = tagged ∪ direct)이 된다.
- `fromTagged`를 `fromBrandPost`로 일반화하고, `source`는 `BrandPostResponse.source`를 그대로 쓴다
  (`SOURCE_DIRECT`/`SOURCE_TAGGED`). `directMapping(userId)` 색인은 삭제한다.
- `campaignId`/`campaignName`은 `brand_post_campaigns`에서 채운다(다중이면 첫 번째 — 응답 필드가
  단수라 목록의 head를 쓰고, 다중 부착 UI는 후속).
- `attributedBrandAccountId`의 "direct(own)가 경쟁사 tagged를 이긴다" 예외는 **불필요해진다**:
  direct와 tagged가 한 행이므로 귀속 브랜드가 곧 그 행의 `brand_id`다. 같은 shortcode가 여러
  브랜드 풀에 있는 경우의 `ownFirst` 규칙은 그대로 유지한다.
- `mergeSnapshots`는 **유지**한다 — 브랜드 풀 게시물이 동시에 개인 캠페인(individual) 아이템일 수
  있고, 그때는 여전히 두 산지의 스냅샷을 합쳐야 한다.
- **`item.id` 변경**: direct 콘텐츠의 아이템 id가 레거시 숫자 id에서 `bt_<shortcode>`로 바뀐다.
  레거시 아이템이 없어지면 그 숫자 id의 산지가 없다 — 통합의 필연적 귀결이므로 FE 통지 항목이다(§6).

### 결정 4 — expand-contract 4단계, 이관은 재수집 방식

배포 순서는 **monitoring → was**로 고정한다(08-13과 같은 의존 — 역순이면 was가 없는 컬럼을 조회해
브랜드 목록이 전면 500. 롤백도 같은 의존).

| 단계 | 내용 | 릴리스 |
|---|---|---|
| **E1 expand — monitoring** | 컬럼 2개 추가 + 기존 행 백필, 리포지토리 소스 분리, `BrandDirectCollectService`, 스윕 2단계, 명령 API 2종(+import 모드) | 이번 |
| **E2 expand — was** | 전용 등록 테이블·실행기, 서비스 재작성, 어셈블러 통합, `brand_direct_posts.migrated_at` 추가·`monitoring_item_id` **DROP NOT NULL** | 이번 |
| **M 이관** | 운영 일회성 잡 실행 + 이력 복사 | 이번 릴리스 배포 직후 |
| **C contract** | 레거시 폴백 조립 제거, `monitoring_item_id`·`migrated_at` DROP, `tag_detected_at` DEFAULT 제거 | 다음 릴리스 |
| **X 철거** | 레거시 추적 화면·`V1MonitoringRegistrationService` 브랜드 경로 잔재 정리 | 별도 트랙 |

#### 4-1. 롤링 배포 창의 공존 규칙

was 롤링 중에는 구 파드(레거시 조립)와 신 파드(브랜드 풀 조립)가 같은 DB를 본다. 이관 잡은 was가
완전히 롤아웃된 **뒤** 돌므로, 그 사이 신 파드가 아직 이관되지 않은 direct 게시물을 잃으면 안 된다.

- `app.brand_direct_posts`에 `migrated_at timestamptz`를 추가한다. 의미: **"이 매핑의 정본은
  monitoring 통합 풀이다"**.
- 신 파드의 `assembleDirect`는 **`migrated_at IS NULL`인 행만** 레거시 셰이프로 조립하고,
  나머지는 브랜드 풀에서 온다. 즉 E2 배포 직후에는 현행과 동일하게 보이고, 이관이 진행되는 만큼
  카드가 하나씩 통합 셰이프로 바뀐다.
- 신규 등록은 `migrated_at = now()`로 원장 행을 만든다(레거시 조립 대상이 아님).
- `monitoring_item_id`는 `DROP NOT NULL`한다(신규 행은 NULL). `DROP NOT NULL`은 구코드를 죽이지
  않으므로 `migration-guard`의 금지 목록(`SET NOT NULL`)에 해당하지 않는다. 구 파드가 NULL 행을
  읽으면 `itemsById.get("0")` 미스로 이미 있는 warn 스킵 경로를 탄다(목록에서 잠시 빠질 뿐).
- C 단계에서 폴백 분기와 두 컬럼을 함께 제거한다. `DROP COLUMN` 파일에는 가드 v2 짝 검사가
  걸리므로 `-- no-backfill: 통합 풀이 정본이며 이 컬럼은 이관 진행 표식일 뿐, 보정 대상 데이터가
  없다` 주석을 단다.

#### 4-2. 이관 방식 — 링크 복제가 아니라 재수집

이관 잡(was 어드민 트리거, 멱등)은 `app.brand_direct_posts`를 순회하며 건별로:

1. monitoring `POST /api/brands/{brandId}/direct-posts` 를 `mode=import` 로 호출
   (`registeredAt` = 원 `created_at` 을 함께 전달 → `direct_registered_at`에 그 값을 넣는다).
2. monitoring이 그 안에서 **레거시 이력을 브랜드 테이블로 복사**한다(같은 DB라 순수 SQL):
   ```sql
   INSERT INTO brand_post_snapshot (…) SELECT … FROM post_snapshot WHERE short_code = ?
     ON CONFLICT (short_code, captured_on) DO NOTHING;
   INSERT INTO brand_post_meta      (…) SELECT … FROM post_meta      WHERE short_code = ?
     ON CONFLICT (short_code) DO NOTHING;
   INSERT INTO brand_post_comment   (…) SELECT … FROM post_comment   WHERE short_code = ?
     ON CONFLICT (short_code, id) DO NOTHING;
   ```
3. 이어서 평소 경로(`fetchPost` + `savePost` + `enrich`)를 그대로 태워 오늘자 스냅샷과 게시자
   프로필·`enriched_at`을 확보한다.
4. was가 `brand_direct_posts.migrated_at = now()`를 찍고, `monitoring_items.campaign_id`가 있으면
   `app.brand_post_campaigns`에 1행 만든다.

- **레거시 원본은 지우지 않는다.** 같은 게시물이 다른 유저의 개인 캠페인에서도 추적 중일 수 있다.
  복사는 비파괴·재실행 안전(`ON CONFLICT DO NOTHING`)하다.
- **"링크만 옮기고 다음 스윕에 맡기기"를 기각한 이유**: 그러면 이관 직후 구간이 가장 비대칭인
  상태가 된다(게시자 프로필·`videoUrl`·`isPaidPartnership`이 비고 `enriched_at`이 없어 카드가 아예
  안 보인다). 통합의 목적과 정반대다.
- 컬럼 동형성은 구현 시 실제 DDL로 대조한다: `post_snapshot` ↔ `brand_post_snapshot`(likes_hidden·
  shares_hidden·fb_plays 포함), `post_meta` ↔ `brand_post_meta`(image_object_path 계열 3컬럼 +
  브랜드 전용 `video_url`·`video_duration`·`is_paid_partnership`은 레거시에 없으므로 NULL로 두고
  3단계 재수집이 채운다).

#### 4-3. 철거 순서

1. **C 단계까지**: 레거시 파이프라인 자체는 그대로 산다. `individual`(브랜드 무관 개인 캠페인
   등록)이 여전히 `monitoring_items` → `target`을 쓰기 때문이다. 이번 트랙이 끊는 것은 **브랜드
   direct의 레거시 의존**뿐이다.
2. **X 단계(별도 트랙)**: 레거시 추적 화면을 유저 개념에서 제거하는 작업. 그때 `monitoring_items`·
   `target`·`post_snapshot` 계열의 운명을 결정한다. 이 스펙은 그 트랙의 선행 조건을 만들 뿐
   그 결정을 선취하지 않는다.

### 결정 5 — 나이 티어를 direct에도 그대로 적용한다. 순 콜 증분은 0 이하로 예상한다

#### 5-1. 정책 적용

`BrandCrawlPolicy`(14일 이하 매일 / ~30일 3일 / ~90일 7일 / ~180일 30일 / 180일 초과 영구 제외)를
direct 행에 **예외 없이** 적용한다.

- 근거: 지표 변화율은 게시 후 경과 시간의 함수이지 등록 시점의 함수가 아니다 — 정책 v1의 전제
  그대로다. "등록 후 N일은 매일" 같은 예외를 두면 정책이 두 벌이 되고, 비용이 등록 건수에 선형으로
  붙는다.
- 귀결: 180일을 넘긴 게시물을 직접 등록하면 **등록 시점 스냅샷 1행**만 남고 이후 추적이 없다.
  현행 레거시는 `trackingDays`(최대 90일) 동안 매일 수집했으므로 이는 동작 변경이다 → §6 FE 통지.
- 귀결: `trackingDays` 파라미터는 **하위 호환으로 계속 받되 무시**한다(검증 1~90은 유지 — 기존 FE
  검증 회귀 방지). 브랜드 화면에 "추적 종료" 개념이 없어지고 종료는 취소뿐이다.

#### 5-2. 콜 회계

콜당 $0.0006 (정책 v1 스펙 §8 기준).

| 경로 | 현행(레거시 url 모드, 게시물 1건 기준) | 통합 후 |
|---|---|---|
| 등록 시 | 프로필 1 + 열거 N + clips + 단건 1 + 댓글 ≤3 | 단건 1 + 게시자 0~1 + 댓글 0~3 = **1~5콜** |
| 추적 중 1일 | 프로필 1 + 열거 N + clips + 단건 1 + 댓글 ≤3 (추적 기간 내 **매일**) | due인 날만 단건 1 + 댓글 0~3 |
| 0~180일 누적 | 최대 90일 × (4~8콜) ≈ **360~720콜** | 단건 ≈ 14 + 6 + 9 + 3 = **32콜** + 댓글 게이트 통과분 |

- 게시자 프로필은 `author_profile` 전역 캐시(30일 stale)라 브랜드·게시물 간 공유된다 — direct
  합류로 인한 증분은 사실상 0이다.
- 댓글은 `comment_count` 증가 게이트라 정체된 게시물은 콜이 나가지 않는다.
- **결론: direct 합류의 순 Hiker 콜 증분은 0 또는 음수로 예상한다.** 레거시가 게시물 1건을 위해
  계정 열거를 통째로 돌리고 있었기 때문이다. 다만 계정 열거는 같은 계정의 여러 게시물이 분모를
  나눠 갖는 구조라 **실측 확인이 필요하다**(§7).
- 실측 방법이 이미 준비돼 있다: `target_call_count`(레거시, V20260812160000)와 `brand_call_count`
  (브랜드, V20260812100000)를 이관 전후로 비교한다.

#### 5-3. 열거 깊이 오염 방지 (통합에서 가장 깨지기 쉬운 지점)

`BrandCollectService.enumerationCutoff`는 `trackedPosts(brandId, now−180d)`의 due 행 중 가장 오래된
`taken_at`까지 열거 깊이를 넓힌다. **direct-only 행은 태그 열거에 절대 나타나지 않으므로**,
아무 조치 없이 합류시키면:

1. direct-only 행이 due로 잡혀 열거 깊이를 자기 `taken_at`까지 끌어내린다.
2. 열거는 그 게시물을 못 만나므로 `touchCrawled`가 안 걸린다.
3. 다음 날도 due → **매일 최대 180일 깊이를 여는 요청량 누수**가 영구화된다.

반대로 `touchCrawledDepth`(자연 종료 시 커버 깊이 전체를 touch)를 그대로 두면, direct-only 행이
"수집된 적 없는데 크롤됨"으로 마킹돼 **단건 수집이 영영 안 돈다**.

따라서 두 쿼리 모두 `AND tag_detected_at IS NOT NULL` 가드를 **반드시** 추가한다.

```
trackedPosts        … WHERE brand_id=? AND taken_at>=? AND tag_detected_at IS NOT NULL
touchCrawledDepth   … WHERE brand_id=? AND taken_at>=? AND tag_detected_at IS NOT NULL
directDuePosts (신규) WHERE brand_id=? AND direct_registered_at IS NOT NULL
                            AND tag_detected_at IS NULL AND taken_at>=now()-180d
```

direct + tag_detected 겹침 행은 태그 열거가 커버하므로 1단계에서 자연히 처리되고, 2단계 모수에서
빠진다(중복 콜 없음).

---

## 3. 수집·서빙 상세

### 3-1. monitoring 스키마 변경 총계

```sql
-- expand (E1)
ALTER TABLE brand_tagged_post
    ADD COLUMN tag_detected_at      timestamptz DEFAULT now(),
    ADD COLUMN direct_registered_at timestamptz;
UPDATE brand_tagged_post SET tag_detected_at = first_seen_at WHERE tag_detected_at IS NULL;
CREATE INDEX brand_tagged_post_direct_idx
    ON brand_tagged_post (brand_id) WHERE direct_registered_at IS NOT NULL;
```

`brand_post_snapshot`·`brand_post_meta`·`brand_post_comment`·`author_profile`·`brand_account`는
**변경 없음**. 이것이 결정 1을 고른 실질적 이유다.

`CHECK (tag_detected_at IS NOT NULL OR direct_registered_at IS NOT NULL)`은 expand에서 걸지 않는다
(롤링 중 구 파드 insert가 DEFAULT에 의존하므로 안전하긴 하나, 무결성 이득 대비 롤백 리스크가 크다).
C 단계에서 DEFAULT 제거와 함께 검토한다.

### 3-2. 브랜드 스윕 2단계

```
BrandSweepJob.runSweep() → 브랜드마다:
  ① collect.sweep(brand)          … 태그 열거 (현행 그대로, 깊이 판정에 tag_detected_at 가드)
  ② collect.sweepDirect(brand)    … 신규: directDuePosts를 단건 콜로 수집
  ③ hashtagCollect.sweep(brand)   … 현행 그대로
```

`sweepDirect`는 `sweep`과 같은 격리 규율을 승계한다.

- `BrandCallContext.scoped(brand.id(), …)`로 감싸 콜을 브랜드 몫에 계상한다.
- 게시물 단위 격리 — 한 건의 404·타임아웃이 나머지를 죽이지 않는다.
- 페이지 배치와 같은 의미로 **N건 묶음마다 `enrich`를 부르고 `markEnriched`를 `finally`로 보장**한다
  (08-13 완결 배치 서빙 규율. 180일 초과 구간은 재열거 백스톱이 없다는 논거가 direct에도 그대로
  적용된다 — 오히려 direct-only 행은 태그 열거 백스톱 자체가 없으므로 더 엄격히 지켜야 한다).
- 게시물이 삭제·비공개 전환되어 `fetchPost`가 `SubjectNotFoundException`을 던지면: **행을 지우지
  않고 로그만 남긴다**. 브랜드 파이프라인은 상태 전이를 하지 않는다는 규칙(스펙 §8, `BrandSweepJob`
  주석)을 승계한다. 카드는 마지막 스냅샷으로 남는다.
- 게시자 프로필 병렬화(`brandEnrichWorkerPool`)를 그대로 재사용한다.

### 3-3. was 서빙 규칙

`BrandPostAssembler`는 조립 함수 한 벌만 남는다.

```
findBrandPostsInWindow(brandId, cutoff, scope):
  SELECT short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
         comments_collected_count, last_crawled_at,
         tag_detected_at, direct_registered_at
    FROM brand_tagged_post
   WHERE brand_id = :brandId
     AND ( taken_at >= :cutoff OR direct_registered_at IS NOT NULL )   -- direct는 창 예외
     [AND enriched_at IS NOT NULL]                                     -- scope=ENRICHED_ONLY
   ORDER BY taken_at DESC
```

| 응답 필드 | 규칙 |
|---|---|
| `source` | `direct_registered_at IS NOT NULL ? "direct" : "tagged"` |
| `trackingStatus` | 항상 `"tracking"`(§6 통지) |
| `trackingStartedAt` | `COALESCE(direct_registered_at, first_seen_at)` |
| `createdAt` | `first_seen_at`(발견 사실은 등록과 무관하게 보존) |
| `trackingEndedAt` | 항상 null(종료 개념 없음) |
| `updatedAt`(마지막 수집) | `GREATEST(brand_account.last_swept_at, 행의 last_crawled_at)` — direct 등록 직후 카드가 "어젯밤"으로 보이지 않게 행 단위 값을 함께 본다 |
| `contentType` | `"reels"`/`"feed"` 어휘 유지 — 현행 필드 주석이 "direct 산지가 레거시 값을 그대로 싣기 때문"이라 설명하는데, 통합 후엔 산지가 하나라 그 이유가 소멸한다. **값은 그대로 두고 주석만 고친다**(FE 어휘 변경 금지) |
| `campaignIds` | `app.brand_post_campaigns` 배치 조회 |
| 나머지 전부 | tagged 조립 규칙 그대로(`brand_post_meta`·`author_profile`·`brand_post_snapshot`·`brand_post_comment`) |

- `mergeByShortcode`·`promoteSponsorship`·`directPost`는 **삭제**한다. 협찬 승격은 한 행에 한
  관측값만 있으므로 불필요하다.
- `TaggedScope{ENRICHED_ONLY, ALL}` 계약은 **유지**한다(표시 vs 판정·집계). 이름만
  `BrandPostScope`로 정리한다.
- E2~M 구간 한정으로 `assembleDirect`가 `migrated_at IS NULL` 행만 조립해 결과에 얹는다(§4-1).
  이 폴백은 C 단계에서 제거한다.

---

## 4. 해소되는 기존 미결 항목

| 미결 | 어떻게 해소되나 |
|---|---|
| 겹침 게시물이 영구 direct 셰이프로 고정(MON-BT §게이트) | 셰이프가 하나 — 소멸 |
| 창 밖 tagged 데드엔드 우회의 "의도된 대가"(08-17) | direct 승격이 창 예외를 주므로 대가 없이 해소 |
| PP 후속 #1 — `resolveLazyMappingBrand`의 타입 검사 없는 폴백으로 경쟁사 매핑 생성 | 지연 매핑 자체가 삭제(등록 행에 `brand_id`가 있다) |
| `campaignIds`가 tagged에서 항상 빈 목록 | 산지가 산지-중립 테이블로 이동(부착 API는 후속) |
| direct 스냅샷이 `sweep_run` 워터마크로 잘림 | 브랜드 스냅샷 경로에는 그 컷이 없다 — 소멸 |
| reasonCode 어휘 이원화(v2 대문자 vs 레거시 소문자) | **해소되지 않는다** — 이번엔 레거시 소문자 어휘를 그대로 승계한다(FE 무회귀 우선) |

---

## 5. 명시적 비적용 범위

- 레거시 개인 캠페인 등록(`individual`) 경로와 `/v1/monitoring/**` 표면은 손대지 않는다.
- tagged 게시물의 캠페인 **부착·해제 API**는 만들지 않는다(테이블만 준비).
- `brand_hashtag_post`(발견 목록)의 수집·보강 파이프라인은 변경 없다.
- 레거시 추적 화면 철거는 별도 트랙(§결정 4-3의 X 단계).
- `V1BrandDirectPostService.cancel`이 아카이브 없이 hard delete하는 현행 문제는 이번에 고치지
  않는다(§7에 등재).

---

## 6. FE 통지가 필요한 계약 변경 (4건)

1. **`trackingDays`는 무시된다.** 값은 계속 받고 1~90 검증도 유지하지만 추적 기간을 정하지 않는다.
   추적 종료는 게시물 나이 180일 초과 시 자동, 또는 사용자 취소뿐이다.
2. **`BrandPostResponse.trackingStatus`는 direct도 항상 `"tracking"`이다.** `collecting`·`ended`·
   `error`가 브랜드 화면에서 사라진다. 등록 진행 상태는 등록 폴링 응답(entry `result`)이 정본이다.
3. **`BrandDirectRegistrationResponse.Entry.monitoringItemId`는 항상 null이다.** 레거시 아이템이
   생성되지 않는다. 취소는 `POST /v1/brand-monitoring/posts/{postId}/cancel`(계약 v2.10 §8-2)만 쓴다.
4. **성과 대시보드에서 direct 콘텐츠의 `item.id`가 `bt_<shortcode>` 형태로 바뀐다**(기존 숫자 id에서
   변경). `canonicalPostId`(=shortcode)는 불변이므로 shortcode 기반 참조는 영향이 없다.

부수 표시 변경(계약 변경은 아니나 화면에 보임):

- 같은 브랜드에 연결된 다른 유저가 등록한 direct 게시물이 내 목록에도 보인다(결정 1-1).
- 180일이 지난 게시물을 직접 등록하면 스냅샷이 1행만 쌓인다(결정 5-1).

---

## 7. 열린 리스크·운영 확인 필요

| # | 항목 | 성격 | 대응 |
|---|---|---|---|
| R1 | **이관 대상 규모 미상** — `app.brand_direct_posts` 행 수, 그중 `migrated_at` 대상, shortcode 중복 제거 후 실제 Hiker 콜 수 | 운영 확인 필요 | 배포 전 `SELECT count(*), count(DISTINCT short_code) FROM app.brand_direct_posts` 실행. 건당 최대 5콜 × $0.0006으로 예산 산정 후 잡 실행 |
| R2 | **다중 유저 브랜드에서의 취소 권한 완화** — A가 등록한 것을 C가 취소 가능 | 동작 변경 | 배포 전 `SELECT brand_id, count(*) FROM app.brand_monitorings WHERE deleted_at IS NULL GROUP BY brand_id HAVING count(*) > 1` 로 영향 브랜드 수 확인. 0이면 무영향 |
| R3 | **콜 증분이 예상(0 이하)과 다를 가능성** — 레거시 계정 열거는 같은 계정의 여러 게시물이 분모를 나눈다. 한 계정에 여러 direct 게시물이 몰려 있으면 통합이 오히려 늘 수 있다 | 실측 필요 | 이관 전후 `target_call_count`(url 모드) vs `brand_call_count` 일별 비교 2주 |
| R4 | **180일 초과 게시물 등록의 UX 후퇴** — 스냅샷 1행 | 제품 판단 | FE와 협의. "이미 오래된 게시물은 현재 지표만 기록됩니다" 류 안내가 필요한지 결정 |
| R5 | **`enumerationCutoff`/`touchCrawledDepth` 가드 누락 시 조용한 요청량 누수** — 테스트로만 잡힌다 | 구현 리스크 | direct-only 행이 열거 깊이를 넓히지 않는다는 단위 테스트를 **가드보다 먼저** 작성(깨뜨려 확인) |
| R6 | **`cancel`의 무아카이브 hard delete** — 08-17 신설 시 아카이브 경로가 붙지 않았다(등록 롤백·탈퇴 두 경로만 아카이브) | 기존 결함 | 이번 재작성 시 `brand_direct_posts` 원장 삭제에 아카이브를 붙일지 판단. 붙인다면 새 `ArchiveReason` 필요 |
| R7 | **`brand_post_registrations`에 stale pending이 남는 경로** — monitoring 장기 불능 시 | 운영 | 레거시 `settleStaleRegistrationEntries`와 같은 만료 정산(예: 24시간 초과 pending → failed)을 동형 이식 |
| R8 | **성과 대시보드 `statusCounts` 분포 변화** — direct가 항상 `tracking`이 되면서 `ended` 카운트가 준다 | 표시 | FE 확인. 필터 UI가 빈 그룹을 어떻게 그리는지 |
| R9 | **레거시 이력 복사의 컬럼 동형성** — `post_meta` ↔ `brand_post_meta`의 이미지 아카이브 3컬럼·브랜드 전용 3컬럼 차이 | 구현 리스크 | 이관 잡 구현 전 실제 DDL을 컬럼 단위로 대조하고, 대조 결과를 잡 SQL 주석에 남긴다 |
