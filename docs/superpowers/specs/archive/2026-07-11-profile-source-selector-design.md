# 프로필 소스 셀렉터 + HikerAPI 보충 설계

> 상태: ✅ 구현됨 — 단 게시물 보충(`HikerMediasSupplement`·`profile.supplement.posts`)은
> 07-14 인플루언서 파이프라인 전환에서 폐기(play_count=0·캡션 누락으로 탈락), 현재는
> suggested 보충만 잔존

**날짜:** 2026-07-11
**범위:** 프로필(Qualify) 단계 한정. 발굴·상세·댓글은 이번 범위 밖.

## 배경 / 목표

현재 `QualifyJob.profileMissingAccounts`는 Apify 프로필 액터(`Actors.PROFILE`)를 하드코딩 호출한다. 이를 **런타임에 선택 가능한 다중 소스**로 바꾼다. 기존 댓글 토글(`CommentFetcher`/`CommentSource`/`CommentSourceSelector`/`CommentSourceSetting`) 패턴을 그대로 복제한다.

동기: 비용 절감 + 데이터 소스 A/B 비교. 프로필 코어 지표(`followersCount`)는 어느 소스든 재현 가능하며, 소스별로 부가 데이터(최근 게시물 조회수, 관련계정) 확보 여부·비용이 다르다.

## 소스 모델: 베이스 4 + 독립 보충 2

### 베이스 프로필 소스 (`profile.source`, 기본값 `SELF`)

| enum | 엔드포인트 | 자체 제공 | 보충 노출 |
|---|---|---|---|
| `SELF` (기본) | `web_profile_info` (로그아웃, x-ig-app-id) | 코어 + 최근게시물(조회수=0) | ✅ posts, related |
| `ACTOR` | Apify `instagram-profile-scraper` (기존) | 코어 + 게시물+조회수 + relatedProfiles | ❌ (이미 번들) |
| `HIKER_MOBILE` | HikerAPI `GET /v2/user/by/username` | 코어(250필드), 게시물·related 없음 | ✅ posts, related |
| `HIKER_WEB_GQL` | HikerAPI `GET /gql/user/web_profile_info` | 코어 + 게시물 + related 번들 | ❌ (이미 번들) |

> `HIKER_WEB_GQL`은 실측 시점(2026-07-11) HikerAPI가 `500 InstagramServerError`를 일관 반환. 구현은 하되 런타임 실패 가능 — 실패 시 로그 + 해당 계정 미프로필(기존 deferred 로직) 처리.

### 보충 (HikerAPI 추가 호출, 서로 독립 · 각 on/off) — **부족한 베이스(SELF·HIKER_MOBILE)에서만 UI 노출**

| 설정 | API | 채우는 것 | 병합 키 |
|---|---|---|---|
| `profile.supplement.posts` (bool, 기본 false) | HikerAPI `GET /v1/user/medias/chunk?user_id=` | 게시물 실제 조회수(play_count) | payload `latestPosts` |
| `profile.supplement.related` (bool, 기본 false) | HikerAPI `GET /v2/user/suggested/profiles?user_id=&expand_suggestion=true` | 관련계정(니치) | payload `relatedProfiles` |

- 두 보충은 **완전 독립**. 둘 다/하나만/안 함 자유.
- 각 보충은 **독립 try/catch**: 하나가 실패해도 나머지 보충과 베이스 프로필 저장은 진행. 동시 실행 아님(개별 호출).
- `ACTOR`·`HIKER_WEB_GQL` 선택 시 보충 설정은 UI에서 비활성/무시(이미 번들 제공).

### 동작 예시
- `SELF` → web_profile_info만 (공짜, 조회수0·related없음)
- `SELF` + related → self 코어·게시물 + HikerAPI 관련계정 ($0.001)
- `SELF` + posts + related → self + 조회수 채움 + 관련계정 (self 공짜 + $0.002)
- `HIKER_MOBILE` + posts + related → 완전 재현 ($0.003)

## 아키텍처

기존 헥사고날/댓글 패턴 복제 + 보충 레이어 추가.

```
QualifyJob.profileMissingAccounts()
  └─ ProfileSourceSelector.current()          // profile.source 읽어 베이스 선택
       ├─ ProfileFetcher (베이스, 4구현)        // fetch(usernames, trigger) → Execution
       │    Self / Actor / HikerMobile / HikerWebGql
       └─ ProfileSupplementer.apply(payload, account)   // 부족 베이스만
            ├─ if profile.supplement.posts  → HikerMediasSupplement (try/catch)
            └─ if profile.supplement.related→ HikerSuggestedSupplement (try/catch)
```

### 새 컴포넌트

| 컴포넌트 | 위치 | 역할 |
|---|---|---|
| `ProfileSource` (enum) | `settings/domain` | SELF·ACTOR·HIKER_MOBILE·HIKER_WEB_GQL |
| `ProfileFetcher` (port) | `crawling/application/port/out` | `Execution fetch(List<String> usernames, TriggerType); ProfileSource source();` |
| `SelfProfileFetcher` | `crawling/application/service` | web_profile_info (InstagramWebClient 재사용) |
| `ActorProfileFetcher` | 〃 | 기존 Apify 경로 래핑 (Actors.PROFILE) |
| `HikerMobileProfileFetcher` | 〃 | `/v2/user/by/username` |
| `HikerWebGqlProfileFetcher` | 〃 | `/gql/user/web_profile_info` |
| `ProfileSupplementer` | 〃 | 설정 읽어 posts/related 보충 호출·병합 |
| `HikerMediasSupplement` / `HikerSuggestedSupplement` | 〃 | 각 보충 API 1종 (독립) |
| `ProfileSourceSelector` | 〃 | `List<ProfileFetcher>`→Map, `current()` + 보충 적용 |
| `ProfileSourceSetting` | `settings/application/service` | `profile.source` 문자열 (app_setting) |
| `ProfileSupplementSetting` | 〃 | `profile.supplement.posts`/`.related` bool 2개 |
| `ProfileMapper` | `crawling/application/service` | 각 소스 응답 → raw_profile payload 정규화 |
| `HikerHttp` / `JdkHikerHttp` | `crawling/adapter/out/hiker` | base `api.hikerapi.com`, 헤더 `x-access-key` |
| `HikerProperties` | 〃 | `@ConfigurationProperties("crawler.hiker")`: apiKey, baseUrl, requestTimeout |
| `ProfileSourceUiController` | `crawling/adapter/in/web` | 설정 저장 |

### 페이로드 계약 (하드)

`raw_profile` generated column: `username`, `followersCount`(::bigint). **모든 소스/보충은 이 두 키를 반드시 채운다.** `ProfileMapper`가 소스별 필드명 차이 흡수:
- SELF: `edge_followed_by.count` → followersCount
- HIKER_*: `follower_count` → followersCount
- ACTOR: `followersCount`(그대로)
보충 병합 키: `latestPosts`(배열), `relatedProfiles`(배열) — generated column 없음, jsonb 안에만 저장(미래용).

### 실행/기록
모든 fetch·보충은 `CrawlExecutor` Supplier 오버로드로 감싸 `crawl_run` + `raw_run_item` 자동 기록. 라벨: `profile-self`, `profile-hiker-mobile`, `profile-hiker-medias`, `profile-hiker-suggested` 등.

## UI (설정 화면)

```
[프로필 소스]  (•) SELF   ( ) Apify   ( ) HikerAPI 모바일   ( ) HikerAPI 웹gql
[보충 API]  (SELF·HikerAPI모바일 선택 시만 활성)
   [ ] 게시물 조회수 채우기 (HikerAPI medias/chunk)
   [ ] 관련계정 채우기 (HikerAPI suggested?expand)
예상 비용: 프로필당 $0.00X    [저장]
```
- `ACTOR`/`HIKER_WEB_GQL` 선택 시 보충 체크박스 비활성(회색).
- 저장 → `ProfileSourceSetting.update` + `ProfileSupplementSetting.update` → redirect.
- 댓글 소스 토글 카드(`CommentSourceUiController`)와 동일 스타일.

## 설정 (application.yml)

```yaml
crawler:
  hiker:
    api-key: ${HIKER_API_KEY:}
    base-url: https://api.hikerapi.com
    request-timeout: 15s
```
`CrawlerConfig`의 `@EnableConfigurationProperties`에 `HikerProperties` 추가.

## 통합 지점

`QualifyJob.profileMissingAccounts`: `Actors.PROFILE` 직접 execute → `profileSourceSelector.current().fetchAndSupplement(usernames, trigger)`로 교체. 나머지(자격 판정, `followersCount` 읽기, `lastProfiledAt` 세팅)는 불변. 매칭은 `payload.username` 유지.

## 테스트

- 단위: `ProfileMapper`가 각 소스(SELF/HIKER_MOBILE/HIKER_WEB_GQL/ACTOR) 샘플 JSON을 `username`+`followersCount` 담긴 payload로 정규화.
- 단위: `ProfileSupplementer` — posts만/related만/둘다/실패 시나리오(한 보충 예외 시 나머지·베이스 보존).
- 단위: `ProfileSourceSelector` — 설정별 베이스 선택 + 미등록 소스 폴백(SELF).
- 통합(가능시): 각 fetcher가 `crawl_run`+`raw_profile` 저장, `QualifyJob`이 followers 게이트 정상 판정.
- 수동 스모크: 실제 HikerAPI 키로 각 소스 1건 (기존 저장된 `~/Desktop/hiker_vs_self` 샘플과 대조).

## 비범위 (Non-goals)
- 발굴/상세/댓글 소스 전환 (별도 sub-project).
- 관련계정을 발굴 파이프라인으로 되먹이는 자동화 (추후).
- `HIKER_WEB_GQL` 500 문제의 근본 해결(벤더 측 이슈).

## 리스크
- `HIKER_WEB_GQL` 현재 IG500 — 런타임 실패 시 기존 deferred 경로로 안전 흡수.
- HikerAPI 키 노출/과금 — env var, 선충전 잔액 소진 시 402/에러 → 로그 + deferred.
- 페이로드 키 계약 위반 시 INSERT 실패 → 매퍼 단위테스트로 방지.
