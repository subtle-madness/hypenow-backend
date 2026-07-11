# 상세 소스 셀렉터 설계 (Detail Source Selector)

**날짜:** 2026-07-11
**브랜치:** `feat/detail-source-selector` (77aa59c 기반 — 프로필·발굴과 형제)
**범위:** 상세(Aggregate) 단계의 상세 fetch 한정. 댓글·발굴·프로필은 이번 범위 밖.

## 배경 / 목표

현재 `AggregateJob.aggregateChunk`는 상세를 Apify 액터(`DETAIL_REELS`/`DETAIL_FEED`)로 하드코딩 호출한다(댓글은 이미 `CommentSourceSelector`로 전환됨). 이를 **타입별 런타임 선택 가능한 다중 소스**로 바꾼다. 기존 댓글·프로필 토글 패턴을 복제한다.

동기: 비용 절감 + 릴스 실조회수 확보. 릴스는 비로그인 self-crawl에서 조회수가 0으로 게이트되므로 HikerAPI로만 실조회수(play_count)를 얻는다. 피드는 조회수가 원래 없어 self-crawl(프록시)로 충분하다.

## 소스 모델: 타입별 소스 토글

### 설정 (`app_setting`)

| 키 | 기본값 | 허용값 |
|---|---|---|
| `detail.reels.source` | `HIKER` | `HIKER`, `ACTOR` |
| `detail.feed.source` | `SELF` | `SELF`, `ACTOR` |

`DetailSource` enum: `{ ACTOR, HIKER, SELF }`. 타입마다 한 소스를 고른다.

### 소스별 동작

| 소스 | 타입 | 엔드포인트 / 방식 | 얻는 것 |
|---|---|---|---|
| `HIKER` | REELS | HikerAPI `GET /v2/media/info/by/code?code=<shortCode>` (per-item) | 실조회수(play_count)·좋아요·댓글수·caption·clips_metadata |
| `SELF` | FEED | self-crawl GraphQL 포스트 쿼리 (HandshakeExtractor + doc_id, 프록시, per-item) | caption·좋아요·댓글수·캐러셀 이미지·taggedUsers (조회수 없음=피드 특성) |
| `ACTOR` | REELS+FEED | 기존 Apify `DETAIL_REELS`/`DETAIL_FEED` (batch, URL 배열) | 기존과 동일 |

- **릴스 self-crawl은 비범위** — 조회수 0이라 무의미.
- **피드 HIKER는 비범위** — self로 무료로 충분(YAGNI).

## 아키텍처

기존 댓글/프로필 셀렉터 패턴 복제.

```
AggregateJob.aggregateChunk(chunk, type)
  └─ DetailSourceSelector.forType(type)          // detail.<type>.source 읽어 fetcher 선택
       ├─ DetailFetcher.fetch(shortCodes, type, trigger) → Execution
       │    Actor / HikerReel / SelfFeed
       └─ ACTOR 폴백 (미등록 소스 시)
```

### 신규 컴포넌트

| 컴포넌트 | 위치 | 역할 |
|---|---|---|
| `DetailSource` (enum) | `settings/domain` | ACTOR·HIKER·SELF |
| `DetailSourceSetting` | `settings/application/service` | `detail.reels.source`/`detail.feed.source`; `sourceFor(ContentType)`, `update(reels, feed)` |
| `DetailFetcher` (port) | `crawling/application/port/out` | `Execution fetch(List<String> shortCodes, ContentType type, TriggerType); DetailSource source(); boolean supports(ContentType);` |
| `DetailMapper` | `crawling/application/service` | 소스별 raw → 하드계약 키 정규화 + `_raw` |
| `ActorDetailFetcher` | 〃 | source=ACTOR, 릴스+피드; shortCode→URL(타입별)→기존 액터 |
| `HikerReelDetailFetcher` | 〃 | source=HIKER, 릴스; per-item `/v2/media/info/by/code` |
| `SelfFeedDetailFetcher` | 〃 | source=SELF, 피드; per-item self GraphQL(doc_id·프록시) |
| `DetailSourceSelector` | 〃 | `List<DetailFetcher>`→(type,source)맵, `forType(type)` + ACTOR 폴백 |
| `DetailSourceUiController` | `crawling/adapter/in/web` | `POST /ui/detail-source` |

### 재사용 (프로필/댓글에서 이미 존재, 77aa59c 포함)
- `HikerHttp`/`JdkHikerHttp`/`HikerProperties` (HikerAPI 호출)
- `InstagramWebClient` + `HandshakeExtractor` (self-crawl page GET·lsd·GraphQL)
- `CrawlExecutor` (Map/Supplier 오버로드 — crawl_run·raw_run_item 기록)

### 신규 설정 (application.yml)
```yaml
crawler:
  direct-detail:
    post-doc-id: ${IG_POST_DOC_ID:}   # self 피드 GraphQL 포스트 쿼리 doc_id
```
(HikerAPI 키·프록시는 기존 `crawler.hiker`/`crawler.direct-comment` 재사용.)

## 페이로드 계약 (하드)

`raw_post_detail` generated column: `short_code`(=payload `shortCode`)·`caption`·`likes`(=`likesCount`)·`comments_count`(=`commentsCount`)·`video_play_count`(=`videoPlayCount`). **모든 소스 매퍼는 이 키를 채운다.** 광고 판정(`AdSignals.adMarked`)은 `caption`(마커 정규식) + `isPaidPartnership`/`paidPartnership`(불리언)을 읽으므로 매퍼가 함께 채운다.

### 매퍼 매핑표

| 정규화 키 | HikerAPI(릴스) | self GraphQL(피드) | Apify |
|---|---|---|---|
| `shortCode` (필수·인덱싱·컬럼) | `code` | `shortcode` | `shortCode` |
| `caption` (컬럼·광고판정) | `caption_text` | `edge_media_to_caption.edges[0].node.text` | `caption` |
| `likesCount` | `like_count` | `edge_media_preview_like.count` | `likesCount` |
| `commentsCount` | `comment_count` | `edge_media_to_comment.count` | `commentsCount` |
| `videoPlayCount` | `play_count` | (피드=null) | `videoPlayCount` |
| `isPaidPartnership` (광고판정) | `is_paid_partnership` | `is_paid_partnership` | (액터 미제공) |
| `_rawDetail` | 원본 통째 | 원본 통째 | (아이템 자체가 원본 — 별도 키 불필요) |

`AggregateJob.indexDetails`는 `shortCode`(없으면 `url`)로 인덱싱하므로 **`shortCode`는 필수**.

## crawl_run 기록

`executor.execute` 호출 1번 = `crawl_run` 1행. `AggregateJob`은 **(타입 × 청크)마다 상세 fetch 1 + 댓글 fetch 1**을 별도로 호출하므로, 한 번의 집계 트리거가 **여러 crawl_run 행**을 만든다(상세·댓글 합쳐지지 않음 — 소스별 건수 추적 유지가 의도). 셀렉터 도입 후에도 이 입도는 동일.

- `actor_id`(대시보드 "액터" 칸) 라벨: `detail-hiker-reels` / `detail-self-feed` / `detail-actor-reels` / `detail-actor-feed`.
- 각 fetcher는 `CrawlExecutor`로 감싼다: HIKER/SELF는 Supplier 오버로드(per-item 루프), ACTOR는 Map 오버로드(batch).
- `raw_post_detail.crawl_run_id` → crawl_run 라벨로 **어떤 소스로 수집됐는지 추적 가능**.

### 소스 변경 시
- 설정은 **fetch 시점에 읽음** → **다음 집계 실행부터** 새 소스 적용. 과거 crawl_run 불변.
- 이미 `AGGREGATED`된 콘텐츠는 `findDue`(QUALIFIED·미집계) 대상이 아니라 **재수집되지 않음**. 새 소스로 다시 뽑으려면 상태 리셋 필요(현재 그런 경로 없음, 비범위).

## 통합 지점

`AggregateJob.aggregateChunk`: `executor.execute(...detailActor, ActorInputs.detailUrls(...))` **한 줄**을 `detailSource.forType(type).fetch(shortCodes, type, trigger)`로 교체. 나머지(`indexDetails` 인덱싱, 빈 응답 소프트실패 `bumpAttempts` 재시도, 댓글 `commentSource`, raw 저장, `AdSignals` 광고마크, `AGGREGATED` 전환)는 불변. `AggregateJob`은 `detailActor`/`ActorInputs.detailUrls` 직접 참조를 제거하고 `DetailSourceSelector` 주입.

## 에러 처리

- **per-item skip**(HIKER/SELF): shortCode 하나 실패해도 나머지 진행(프로필 `HikerMobileProfileFetcher` 방식). 청크 전체가 한 항목 때문에 죽지 않음.
- **청크 레벨**: fetcher가 `ApifyException`을 던지면 `AggregateJob` 기존 catch → `bumpAttempts` 재시도. 빈 `detailByCode`도 기존 소프트실패 재시도.
- **HikerHttp**: 키 미설정 시 호출 시점 `ApifyException`(프로필 픽스 — 부팅은 됨) → 청크 재시도. 릴스 기본 HIKER라 릴스 상세엔 키 필요.
- **self doc_id 미설정**: self 피드 fetch 실패 → 청크 재시도. `IG_POST_DOC_ID` 필요.
- **`/v2/media/info/by/code`는 단건 미디어 객체**(튜플 아님) — 매퍼가 `{media:…}`/직접 방어 파싱. (튜플 함정은 chunk/list 계열만 — 메모 `hikerapi-chunk-tuple-response`.)

## 테스트

- 단위: `DetailMapper` — 저장된 `~/Desktop/hiker_vs_self/detail_*` 픽스처(HikerAPI 릴스·self 피드·Apify)를 하드계약 키+`_rawDetail`로 정규화, `isPaidPartnership` 매핑.
- 단위: `DetailSourceSelector` — `forType(REELS)`→HIKER fetcher, `forType(FEED)`→SELF fetcher, 미설정 시 ACTOR 폴백.
- 단위: fetcher per-item skip(한 shortCode 실패 시 나머지 보존).
- 통합(가능시): `AggregateJob`이 릴스→hiker·피드→self 라우팅, `raw_post_detail` generated column 채워짐, caption 광고마크 정상.
- 수동 스모크: 실제 HikerAPI 키·프록시로 릴스1·피드1 수집, `~/Desktop/hiker_vs_self` 샘플과 대조.

## 비범위 (Non-goals)

- 릴스 self-crawl(조회수 0 무의미)·피드 HIKER(불필요).
- 발굴/프로필/댓글 소스 전환(별도 sub-project — 발굴은 형제 브랜치에서 진행 중).
- 이미 집계된 콘텐츠의 소스 변경 재수집(상태 리셋 경로 없음).
- `IG_POST_DOC_ID` 실측 doc_id 확보(구현 단계 정찰에서 확정).

## 리스크

- `IG_POST_DOC_ID` 미확정 — self 피드 GraphQL 포스트 쿼리 doc_id를 구현 정찰에서 실측 확보해야 함. 없으면 피드 상세는 ACTOR로 폴백 운영.
- HikerAPI 릴스 per-item 호출 비용 — 집계 배치(월 ~200)면 미미($0.001/건).
- 페이로드 키 계약 위반 시 generated column INSERT 실패 → 매퍼 단위테스트로 방지.
- self GraphQL 응답 형태 변화(IG 측) — HandshakeExtractor·doc_id 의존, 실패 시 청크 재시도로 흡수.
