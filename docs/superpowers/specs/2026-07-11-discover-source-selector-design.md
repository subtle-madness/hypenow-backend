# 발굴 소스 셀렉터 설계 (ACTOR / HIKER 해시태그)

> 상태: ✅ 구현됨 — `DiscoverSourceSelector` 현재도 사용 중(변경 없음)

**날짜:** 2026-07-11
**범위:** 발굴(Discover) 단계 한정. 상세는 병행 세션이 별도 진행(아래 조율 섹션), 프로필·댓글은 완료.

## 배경 / 목표

`DiscoverJob`은 Apify 해시태그 액터(`Actors.DISCOVERY`)를 하드코딩 호출한다. 이를 런타임 선택 가능한 토글로 바꾸고 **기본값을 HikerAPI 해시태그 인기(top)** 로 둔다. 댓글(`comment.source`)·프로필(`profile.source`)과 동일한 토글 패턴의 세 번째 복제.

**소스 선택 근거 (2026-07-11 실측, `~/Desktop/hiker_vs_self/hashtag_*.json`, `search_*.json`):**
- 해시태그 TOP: 32개/콜, 30일 이내 50%, 전 아이템 like_count, 미디어당 128필드
- 검색 topsearch: 20개/콜, 30일 이내 30%, 피드 like 없음 → 해시태그 채택
- 해시태그 TOP은 "지금 인기" 랭킹(에버그린 히트 포함) — 사용자 요구(인기+최신성) 충족
- fbsearch 계열은 비범위(추후 옵션), hashtag clips 계열은 벤더 deprecated

## 소스 모델

| enum `DiscoverSource` | 경로 | 페이지당 |
|---|---|---|
| `ACTOR` | Apify `instagram-hashtag-scraper` (기존 래핑) | resultsLimit |
| `HIKER` (기본) | HikerAPI `GET /v2/hashtag/medias/top?name={키워드}` | 32개 혼합(피드+캐러셀+릴스) |

- 설정 키 `discover.source` (`app_setting` 문자열), 기본값·미인식 폴백 모두 `HIKER`.
- HikerAPI 과금: 페이지당 $0.001.

## 아키텍처 (프로필 패턴 복제)

```
DiscoverJob (키워드 루프 — 변경 최소)
  └─ DiscoverSourceSelector.fetch(categoryId, keyword, trigger)
       ├─ ActorDiscoverFetcher   — 기존 executor.execute(Actors.DISCOVERY…) 이동
       └─ HikerDiscoverFetcher   — hashtag/medias/top + next_page_id 페이지네이션
            └─ HikerDiscoveryMapper — media → 파서 계약 payload 정규화
```

### 새 컴포넌트

| 컴포넌트 | 위치 | 역할 |
|---|---|---|
| `DiscoverSource` (enum) | `settings/domain` | ACTOR·HIKER |
| `DiscoverSourceSetting` | `settings/application/service` | `discover.source`, 기본 HIKER |
| `DiscoverFetcher` (port) | `crawling/application/port/out` | `Execution fetch(long categoryId, String keyword, TriggerType trigger); DiscoverSource source();` |
| `ActorDiscoverFetcher` | `crawling/application/service` | Map 오버로드 + `Actors.DISCOVERY` + `ActorInputs.discovery(keyword, settings.resultsLimit())` |
| `HikerDiscoverFetcher` | 〃 | Supplier 오버로드, 라벨 `hiker-hashtag-top`, resultsLimit 채울 때까지 페이지 반복 |
| `HikerDiscoveryMapper` | 〃 | sections 순회 + 정규화 + `_rawMedia` |
| `DiscoverSourceSelector` | 〃 | `List<DiscoverFetcher>`→Map, 설정 읽어 위임, 폴백 HIKER |
| `DiscoverSourceUiController` | `crawling/adapter/in/web` | `POST /ui/discover-source` |

기존 재사용: `HikerHttp`/`JdkHikerHttp`/`HikerProperties`(프로필 때 구축), `CrawlExecutor` 두 오버로드, `DiscoveryItemParser`(불변).

## 매핑 계약 (HikerDiscoveryMapper)

`DiscoveryItemParser` 요구 4필드를 top-level 정규화 + 원본 통째 보존(프로필 `_raw*` 컨벤션):

| payload 키 | 출처 | 비고 |
|---|---|---|
| `shortCode` | `code` | 결손 시 아이템 스킵 |
| `timestamp` | `taken_at`(epoch초) → `Instant.ofEpochSecond(v).toString()` | ISO-8601, `Instant.parse` 호환 |
| `ownerUsername` | `user.username` | 결손 시 스킵 |
| `productType` | `product_type` | "clips"→REELS, feed/carousel_container→FEED (파서 로직 그대로) |
| `likesCount` / `commentsCount` / `videoPlayCount` | `like_count` / `comment_count` / `play_count` | 부가 정규화(Long) |
| `_rawMedia` | media 원본 전체(~128필드) | jsonb 미래용 |

**sections 순회 규칙(실측):** `response.sections[].layout_content`의 `medias[]`, `fill_items[]`, `one_by_two_item.clips.items[]` — 각 원소 `.media` 언랩(없으면 원소 자체). `code` 또는 `pk` 없는 노드는 무시(캐러셀 조각 함정 — medias/chunk 튜플 교훈과 동일 계열).

**페이지네이션:** 최상위 `next_page_id`를 `&page_id=`로 전달, `response.more_available=false` 또는 수집량 ≥ `settings.resultsLimit()`이면 중단.

## 에러 처리

- HikerAPI 실패·키워드가 해시태그로 미존재 → `ApifyException` → `DiscoverJob` 기존 `failedKeywords++` 경로 (crawl_run FAILED, 다음 키워드 계속).
- `HIKER_API_KEY` 미설정 → `JdkHikerHttp.get()` 호출 시점 에러 → 동일 경로. 앱 부팅은 무관(b79f9d8).
- 페이지 중간 실패 → 이미 모은 아이템으로 정상 종료하지 않고 예외 전파(부분 발굴로 raw에 반쪽 기록 남기지 않음 — 키워드 단위 재시도가 단순).

## UI

`/ui/settings`에 "발굴 소스" 카드 — 댓글·프로필 카드와 동일 스타일:
```
[발굴 소스]  ( ) Apify 액터   (•) HikerAPI 해시태그 인기   [저장]
```
`UiSettingsController`에 `discoverSource` 모델 속성 추가, `DiscoverSourceUiController`가 저장.

## DiscoverJob 배선

`executor.execute(JobName.DISCOVER, trigger, categoryId, kw.getKeyword(), Actors.DISCOVERY, ActorInputs.discovery(...))` → `discoverSourceSelector.fetch(categoryId, kw.getKeyword(), trigger)` 교체. try/catch(ApifyException)·파서·필터·Account/Content/raw_discovery_post 저장 로직 불변.

## 병행 세션 조율 (상세 크롤링 세션 참고)

상세(Detail) 전환이 **다른 세션에서 병행** 진행된다. 충돌·중복 방지 규칙:

**공유 인프라 — 이 브랜치에 이미 존재, 재구축 금지:**
- `HikerHttp` / `JdkHikerHttp`(키 검증은 get() 시점) / `HikerProperties`(`crawler.hiker.*`, `HIKER_API_KEY`)
- `CrawlExecutor` Supplier 오버로드(`new ApifyResult(null, items)`) + raw_run_item 자동 아카이브
- 컨벤션: 소스 토글 4종 세트(XxxSource enum + XxxSourceSetting + XxxFetcher port + XxxSourceSelector), 원본 보존 `_raw*` 키, crawl_run 라벨 `hiker-*`

**충돌 주의 파일(양쪽 세션이 만질 수 있음):** `settings.html`, `UiSettingsController`, (상세가 쓸 경우) `AggregateJob`. 이 spec 범위는 `DiscoverJob`만 수정 — 상세 세션은 DiscoverJob을 건드리지 말 것.

**상세 세션에 전달할 함정 2개:** ① HikerAPI v1 chunk 계열은 `[items[], cursor]` 튜플 응답(d0c3812 교훈) ② `taken_at`이 v2는 epoch초, v1은 ISO 문자열로 엔드포인트마다 다름 — 매퍼에서 흡수.

## 테스트

- `HikerDiscoveryMapper` 단위: 실측 축약 픽스처(sections 3종 배치 + 캐러셀 조각 노이즈) → 4필드 정규화·epoch→ISO·`_rawMedia`·결손 스킵 검증.
- `DiscoverSourceSetting` 단위: 기본 HIKER, 저장/재로드, 미인식 값 폴백.
- `DiscoverSourceSelector` 단위: 설정별 위임 + 폴백.
- `DiscoverJob` 라우팅: 셀렉터 경유 확인(기존 `QualifyJobProfileSourceRoutingTest`와 동형).
- 수동 스모크: HIKER로 DISCOVER 1회 → raw_discovery_post payload에 `_rawMedia`·정규화 필드 확인, ACTOR 토글 복귀 확인.

## 비범위 (Non-goals)

- `medias/recent`(시간순 증분 발굴) — 추후 필요 시 별도.
- fbsearch(키워드 검색) 소스 추가.
- 상세/프로필/댓글 변경(상세는 병행 세션 담당).
- 발굴 payload를 쓰는 하위 소비자 변경(파서 계약 유지로 불필요).

## 리스크

- 서피스 변경(키워드 검색→해시태그 인기)으로 발굴 풀이 달라짐 — 의도된 변경, 토글로 즉시 복귀 가능.
- 키워드가 해시태그로 미존재하면 그 키워드는 FAILED — 카테고리 키워드가 일반 명사라 위험 낮음, 로그로 관찰.
- sections 레이아웃 변형(새 layout_content 키) → 알려진 3종만 순회, 미지 키는 무시(결손은 파서가 거름). 발굴량이 예상보다 적으면 여기부터 확인.
