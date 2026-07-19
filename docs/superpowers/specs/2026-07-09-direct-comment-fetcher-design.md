# 비로그인 자체 댓글 크롤러 (DirectCommentFetcher) 설계

> 상태: ✅ 구현됨 · ⏸ 운영 비활성 — 댓글 수집 자체가 MVP 제외(07-14)로
> `crawler.collect.comments-enabled=false`(yml 전용, UI 없음). 재개 여부 미결.
> §설정의 `direct-comment.proxy-url`은 이후 `crawler.proxy.*` 다중 소스 체계로 이동

**작성일:** 2026-07-09
**브랜치:** `feature/direct-comment-fetcher`

## 배경 / 동기

동기는 **비용 절감**이다. Apify 액터는 결과 건수로 과금되는데, `crawl_run` 집계상 댓글 액터(`apify~instagram-comment-scraper`)가 전체 수집 건수의 절반 이상(3,168 / 약 5,700)을 차지해 비용 1위다.

| 액터 | 역할 | 누적 수집 건수 |
|---|---|---|
| comment-scraper | 댓글 | 3,168 |
| hashtag-scraper | 디스커버리 | 1,567 |
| profile-scraper | 프로필 | 888 |
| reel/post-scraper | 상세 | 128 |

따라서 **댓글 수집만** 자체 크롤로 대체하고, 나머지 액터(디스커버리·프로필·상세)는 유지한다. 프록시는 Apify Proxy를 사용한다.

## 핵심 제약 / 결정

- **비로그인**: 인스타 로그인 세션을 쓰지 않는다.
- **범위**: 댓글만. 다른 액터는 손대지 않는다.
- **방식**: 순수 HTTP(헤드리스 브라우저 아님). 비용·자동화 무인 운영에 유리.
- **프록시**: Apify Proxy 경유.
- **비교 가능성**: 액터 방식과 자체 크롤 방식을 UI 토글로 런타임 전환해 A/B 비교한다.

### 검증된 사실 (스파이크)

- 정적 `curl`은 JS 셸(596KB)만 받아 댓글이 없다. 브라우저는 이후 `POST https://www.instagram.com/api/graphql`를 **익명 세션 쿠키로** 호출해 댓글을 받아온다 — **비로그인으로 댓글 수집이 가능함을 실측 확인**.
- REST 경로 `/api/v1/media/{id}/comments/`는 로그인으로 302 리다이렉트되므로 사용 불가. GraphQL 경로를 써야 한다.
- shortCode → media_id 는 base64 디코딩으로 로컬 계산 가능(네트워크 불필요), 검증 완료.

### 취약점 인식

`/api/graphql` 호출은 인스타의 핸드셰이크(익명 쿠키 + `lsd` 토큰 + `doc_id`)를 재현해야 한다. `lsd`·쿠키는 매 세션 새로 받는 게 정상이라 취약점이 아니고, `doc_id`는 인스타 배포 시 불규칙하게 바뀐다. **하드코딩하지 않고 페이지 HTML에서 동적 추출**하여 대부분의 변경에 자동 대응한다. 남는 유일한 실질 리스크는 응답 JSON 구조 변경(수개월~연 단위, 드묾)이며 그때만 매퍼를 수정한다.

## 아키텍처

### 전략 분리 (섹션 1)

현재 `AggregateJob.aggregateChunk()`가 댓글 액터를 직접 호출하는 부분을 포트 뒤로 숨긴다.

**새 포트** (`crawling/application/port/out`):

```java
public interface CommentFetcher {
    /**
     * 청크(포스트 여러 개)의 댓글을 포스트당 최대 limit개 수집한다.
     * 청크 전체를 하나의 crawl_run 으로 감싸 기록·아카이브한다.
     */
    CrawlExecutor.Execution fetch(List<String> shortCodes, int limit, TriggerType trigger);
    CommentSource source();   // ACTOR | DIRECT
}
```

**배치 단위인 이유:** 현재 `aggregateChunk`는 청크(기본 `chunk-size`=50개 포스트)를 **한 번의 댓글 액터 호출**로 처리한다. 포트를 포스트 단위로 두면 액터 경로가 청크당 50회 호출로 바뀌어 기존 동작·비용이 달라지고 A/B 비교가 왜곡된다. 따라서 포트는 청크(shortCode 리스트)를 받아 **방식과 무관하게 청크당 crawl_run 1건**을 남긴다.

- `ActorCommentFetcher`: 청크를 기존처럼 액터 1회 호출로 처리(자연스럽게 `CrawlExecutor.execute` 위임).
- `DirectCommentFetcher`: 청크 안에서 포스트별로 순회 수집하되, 결과를 모아 **청크 전체를 crawl_run 1건**으로 기록한다. `CrawlExecutor.execute`가 단일 `ApifyRunnerPort.run` 호출을 감싸는 구조와 맞지 않으므로, 직접 방식은 자체적으로 crawl_run RUNNING→OK/FAILED 기록과 아카이브를 수행한다(구체 방식은 구현 계획에서 확정).

**두 구현체:**

| 구현체 | 동작 | crawl_run.actor_id |
|---|---|---|
| `ActorCommentFetcher` | 기존 그대로 — `ApifyRunnerPort` + COMMENT 액터 | `apify~instagram-comment-scraper` |
| `DirectCommentFetcher` | 신규 — 비로그인 `/api/graphql` + 프록시 + 동적 추출 | `direct-comment-crawler` |

**전략 선택기:** `CommentSourceSelector`가 런타임 설정 `comment.source`(`ACTOR`/`DIRECT`, 기본 `ACTOR`)를 읽어 구현체를 반환. `AggregateJob`은 `selector.current().fetch(...)`를 호출한다.

**비교가 성립하는 이유:** 두 경로 모두 `CrawlExecutor`를 통과해 `crawl_run` + `raw_run_item`에 동일하게 기록되고, `actor_id`만 다르게 라벨링된다. 따라서 아래 쿼리로 방식별 수집 건수·성공률·소요시간을 나란히 비교할 수 있다.

```sql
SELECT actor_id, count(*) runs, sum(item_count), avg(item_count)
FROM crawl_run WHERE job='AGGREGATE' GROUP BY actor_id;
```

**기존 코드 영향:**
- `AggregateJob`: 댓글 호출부 1줄 → 전략 호출로 교체. 상세·집계 로직은 그대로.
- `Actors.COMMENT`, `ActorInputs.comments()`: `ActorCommentFetcher` 내부로 캡슐화(삭제 아님).
- 디스커버리·프로필·상세 액터: 무변경.

### DirectCommentFetcher 내부 (섹션 2)

**수집 흐름 (포스트 1개당):**

```
1. [세션 확보]  GET https://www.instagram.com/p/{shortCode}/  (프록시 경유)
                → 익명 쿠키(csrftoken, mid) + HTML에서 lsd·doc_id 동적 추출
2. [댓글 요청]  POST https://www.instagram.com/api/graphql    (프록시 경유)
                헤더: x-ig-app-id, x-fb-lsd, x-csrftoken, cookie
                본문: variables={media_id, sort_order:"popular"}, doc_id
                → 댓글 JSON (첫 페이지 ~12개)
3. [페이지네이션] end_cursor 로 has_next_page 동안 반복 → limit 도달까지
4. [정규화]     액터와 동일 필드로 매핑
```

**구성 요소** (`crawling/adapter/out/instagram` 신설):

| 클래스 | 책임 |
|---|---|
| `DirectCommentFetcher` | 흐름 오케스트레이션, `CommentFetcher` 구현 |
| `InstagramWebClient` | 프록시 물린 `HttpClient` 래퍼 (GET/POST) |
| `HandshakeExtractor` | HTML → `lsd`·`doc_id`·`media_id` 추출 (순수 파서) |
| `CommentMapper` | GraphQL 응답 → 액터와 동일 스키마 Map |

**핵심 결정:**
1. shortCode → media_id 는 로컬 base64 계산. 세션 GET에서 얻은 media_id와 교차검증.
2. **스키마 호환 최우선**: `raw_comment` 생성 컬럼(`writer`=`ownerUsername`, `text`, `written_at`=`timestamp`)이 그대로 채워지도록 매핑 → 다운스트림 무변경, 비교 성립.
3. **실패는 `ApifyException`으로 통일**: 차단(429/403)·추출 실패·타임아웃을 모두 기존 예외로 던짐 → `CrawlExecutor` FAILED 기록 + `AggregateJob`의 `bumpAttempts`(다음 사이클 재시도, `max-attempts` 상한) 로직이 코드 변경 없이 적용.

**설정 (config):**

```yaml
crawler:
  direct-comment:
    proxy-url: http://groups-RESIDENTIAL:{password}@proxy.apify.com:8000
    request-timeout: 15s
    page-delay: 1s      # 페이지네이션 간 지연
```

**차단·레이트리밋 대응:** 포스트 간/페이지 간 소량 지연(설정값), 429·403 → 즉시 `ApifyException` → 해당 포스트만 재시도. 봇 감지 회피를 완벽 보장하지 않으며, 이는 A/B 비교로 실측 판단한다(토글이 중요한 이유).

### UI 토글 + 설정 확장 (섹션 3)

**설정 저장:** 기존 `SettingsService`는 정수 전용이므로 건드리지 않고, **별도 `CommentSourceSetting` 서비스**를 신설한다. `app_setting` 테이블(key/value 문자열)은 공유하므로 `comment.source=DIRECT` 한 행만 추가하면 되고 **마이그레이션 불필요**.

**UI 배치:** 기존 `/ui/settings` 상단에 토글 카드 추가.

```
┌─ 댓글 수집 방식 ──────────────────────┐
│  ( ) 액터 (Apify)   (•) 자체 크롤(직접)  │
│  현재: DIRECT · 기본값: ACTOR   [저장]   │
└──────────────────────────────────────┘
```

`UiSettingsController`에 토글 POST 핸들러 1개 추가. 기존 정수 설정 폼은 아래 유지.

**비교 대시보드 카드 (nice-to-have):** `crawl_run`을 방식별 집계해 성과 비교 표시. 우선순위 낮음 — 핵심 완성 후 붙인다.

## 테스트 전략

| 대상 | 방식 |
|---|---|
| `HandshakeExtractor` | 순수 단위 — 저장된 실제 HTML 샘플로 lsd·doc_id·media_id 추출 검증 |
| `CommentMapper` | 순수 단위 — 실제 GraphQL 응답 샘플 → raw_comment 스키마 매핑 검증 |
| `DirectCommentFetcher` | Fake `InstagramWebClient` — 페이지네이션·limit·에러→ApifyException 검증 |
| `CommentSourceSelector` | 설정값에 따라 올바른 구현체 반환 |
| `AggregateJob` | 기존 테스트 유지 + `CommentFetcher` fake 주입 |
| 실제 인스타 연동 | 수동 스모크(자동 아님) — 실제 shortCode로 봇 감지·응답 구조 실측 |

네트워크 없는 순수 테스트로 파서·매퍼·페이지네이션을 커버하고, 인스타 실연동은 수동 스모크로 둔다(액터 fake 패턴과 동일 철학).

## 구현 순서

1. `CommentFetcher` 포트 + `ActorCommentFetcher`(기존 동작 이관) + 셀렉터 → 기존과 동일 동작 유지 확인(리팩터링 안전판).
2. `comment.source` 토글 설정(`CommentSourceSetting`) + UI.
3. `HandshakeExtractor` + `CommentMapper` (순수, 테스트 먼저).
4. `InstagramWebClient`(프록시) + `DirectCommentFetcher` 조립.
5. 수동 스모크로 실측 → A/B 비교.
6. (선택) 대시보드 비교 카드.

## 범위 밖 (YAGNI)

- 로그인 세션/계정 풀 관리 — 비로그인 원칙.
- 디스커버리·프로필·상세 액터의 자체 크롤 전환 — 이번 범위 아님.
- 헤드리스 브라우저 방식 — 비용·자동화 목표와 상충하여 제외.
- 봇 감지 완전 회피 보장 — 실측으로 판단.
