# 성과 대시보드 조건부 요청(ETag / 304) 설계

> 상태: ✅ 구현/반영됨 (2026-08-28, PR ④ — 표면 4종(/contents·/comparison·/influencers·/growth) 확장·아카이브 워터마크 생략. 운영 승격 전 §5 스테이징 검증 8종 필수)
>
> 선행: [2026-08-12 성과 대시보드 고정 지연 원인 규명 회신](2026-08-12-perf-dashboard-fixed-latency-reply.md)
> (슬림 조립으로 평균 7초 → 1.1초). 이 문서는 그 다음 단계다.

## 배경

08-12 슬림 조립 이후 남은 비용의 **성격이 바뀌었다**. 08-13 운영 실측:

| 측정 | 값 | 출처 |
|---|---|---|
| `/contents` 평균 | 1,085ms | Prometheus, 배포 후 10시간 |
| `/comparison` 평균 | 821ms | 〃 |
| 액세스 로그 누적 | 819건 / **1.6GB** | Caddy `access.log` 전량 |
| 동일 응답 크기 반복률 | `/contents` **61%**, `/comparison` 59% | 〃 |
| 재요청 중앙 간격 | `/contents` **2.0분** (1분 이내 38%) | 〃 |

두 가지 관찰이 이 설계의 출발점이다.

**① 조립 비용이 분리 측정됐다.** `/comparison`은 응답이 10KB라 전송 비용이 사실상 0인데
821ms가 걸린다 — 이 값이 곧 **조립(DB 왕복 30~40회 + 5,000건 객체 생성) 비용의 순수
측정치**다. 같은 조립을 쓰는 `/contents`(1,085ms)를 분해하면 조립 ~800ms + 직렬화·전송
~285ms다.

**② 그 조립이 바뀌지도 않은 데이터에 대해 반복된다.** 이 데이터의 원천은 새벽 스윕
한 번이라, 하루 중 재요청은 사실상 전부 같은 내용이다. 그런데 지금 응답 헤더가 캐시를
원천 차단한다:

```
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
(ETag 없음)
```

Spring Security가 인증 응답에 거는 기본값이다. `no-store`는 **저장 자체를 금지**하고
검증자(ETag)가 없어 "나 이 버전 갖고 있다"고 말할 방법도 없다. 그래서 프론트가 재조회할
때마다 전량이 다시 조립되고 다시 전송된다.

**이 표면은 조건부 요청에 이상적인 형태다.** 프론트는 필터를 서버로 보내지 않고
`accountType=all` 전량을 한 번 받아 브라우저에서 필터한다(celfit-front
`PerformanceDashboardClient.tsx:446`). `/comparison`도 `("all","all","all")` 고정이다.
즉 **유저당 URL이 정확히 하나**이고 그것이 반복 호출된다.

## 결정 요약

| 항목 | 결정 |
|---|---|
| 적용 표면 | `/v1/performance-dashboard/contents` · `/comparison` 2개 (1차) |
| 검증자 | **약한 ETag**(`W/"..."`) — 의미적 동등성만 주장, 바이트 동일성은 주장하지 않음 |
| 버전키 산출 | **데이터 유래 지문** — 유지보수 규율에 의존하는 워터마크·카운터 방식 기각(§2) |
| 계산 시점 | **조립 전** — 304면 조립·직렬화·전송을 전부 건너뛴다(이 설계의 핵심 이득) |
| 캐시 헤더 | `no-store` 제거 → `private, no-cache`. **해당 2개 표면만**, 전역 변경 아님 |
| 프론트 변경 | **불필요** — `fetch()`에 `cache` 옵션이 없어 브라우저가 `If-None-Match`를 자동 처리 |
| 페이지네이션 | **명시적 기각** — 클라이언트 필터 설계와 충돌(§7) |

## 1. 적용 표면

1차 범위는 두 개다.

- `GET /v1/performance-dashboard/contents`
- `GET /v1/performance-dashboard/comparison`

`GET /v1/brand-monitoring/accounts/{accountId}/posts`는 **반복률이 78%로 가장 높고 누적
전송도 790MB로 맞먹지만**(액세스 로그 실측) 소유권 스코프와 파라미터 공간이 달라 1차에서
제외한다. 같은 관용구를 그대로 확장할 수 있으므로 후속 과제로 남긴다(§8).

단건 조회 `/contents/{contentId}`는 응답이 작고 호출이 드물어 제외한다.

## 2. 버전키 설계 — 이 설계의 핵심

### 2-1. 응답을 바꾸는 입력

ETag가 놓치면 **낡은 데이터를 조용히 서빙**하게 되므로, 입력을 빠짐없이 세는 것이 정확성의
전부다. 다섯 가지다.

| # | 입력 | 산지 | 언제 바뀌나 |
|---|---|---|---|
| ① | 레거시 스윕 결과 | monitoring DB (`post_meta`·`post_snapshot` 등) | 새벽 스윕 |
| ② | 브랜드 스윕 결과 | monitoring DB (`brand_*`) | 새벽 스윕 |
| ③ | 이미지 아카이브 | `brand_post_meta.image_object_path` 등 | 스윕 직후 아카이브 잡 |
| ④ | **유저 자신의 쓰기** | analysis DB `app` 스키마 | 등록·취소·기간변경·캠페인변경·브랜드 연결/해제 |
| ⑤ | **KST 날짜** | 서버 시계 | 자정 — 파생값이 바뀐다 |

⑤를 빠뜨리기 쉽다. `ItemStatus.derive(item, target, today)`가 오늘 날짜로 상태를 유도하고
`BrandPostAssembler.windowCutoff()`가 365일 창을 오늘 기준으로 자른다. **데이터가 하나도
안 바뀌어도 자정을 넘기면 응답이 달라진다.**

### 2-2. 제약: `app.monitoring_items`에 `updated_at`이 없다

운영 스키마 확인 결과 `app.monitoring_items`의 타임스탬프는 `created_at`과 `canceled_at`
둘뿐이다. 그런데 `MonitoringItemRepository`는 이런 UPDATE를 한다:

```java
UPDATE app.monitoring_items SET tracking_days = :trackingDays WHERE id = :itemId
UPDATE app.monitoring_items SET campaign_id   = :campaignId   WHERE id = :itemId
UPDATE app.monitoring_items SET target_id     = :targetId     WHERE id = :itemId
```

**어떤 타임스탬프도 갱신하지 않는다.** 따라서 흔히 쓰는 `max(updated_at)` 워터마크
방식으로는 기간 변경·캠페인 변경을 감지할 수 없다. 이 제약이 아래 선택을 강제했다.

### 2-3. 선택: 데이터 유래 지문

**유저 소유 행의 가변 필드를 그대로 해싱한다.** 별도 타임스탬프도, 쓰기 경로에서의 명시적
bump도 두지 않는다.

이게 가능한 이유는 **유저당 행이 매우 작기 때문**이다(운영 실측):

| 테이블 | 유저당 최대 | 평균 | 전체 |
|---|---|---|---|
| `app.monitoring_items` | 33행 | 12행 | 72행 |
| `app.brand_monitorings` | 6행 | — | 40행 |
| `app.brand_direct_posts` | — | — | 0행(현재) |

수십 행 해싱은 비용이 무시할 수준이라, 정확성을 규율이 아니라 **데이터 자체**에서 얻을 수
있다.

```sql
-- 유저 지문 (app DataSource, 유저 1명 스코프)
SELECT md5(
         coalesce(string_agg(
           i.id || ':' || i.tracking_days || ':' || coalesce(i.campaign_id::text,'-')
                || ':' || coalesce(i.target_id::text,'-') || ':' || coalesce(i.canceled_at::text,'-'),
           ',' ORDER BY i.id), '')
       )
FROM app.monitoring_items i
WHERE i.user_id = :userId
```

`brand_monitorings`(연결·`account_type`·`deleted_at`)와 `brand_direct_posts`에도 같은
관용구를 적용해 합친다. **해싱 대상 컬럼은 응답에 영향을 주는 컬럼과 1:1로 맞춰야 하며,
컬럼이 추가될 때 여기도 같이 늘어난다** — 이 결합은 §6의 통합 테스트로 고정한다.

### 2-4. 기각한 대안

| 대안 | 기각 사유 |
|---|---|
| `updated_at` 컬럼 추가 + 모든 UPDATE에서 설정 | 마이그레이션 + 모든 쓰기 경로 수정. **한 곳만 빠뜨려도 낡은 데이터를 조용히 서빙**한다(에러가 아니라 침묵하는 오류라 발견이 늦다) |
| 별도 버전 카운터 테이블 + 쓰기 시 bump | 같은 규율 문제. 테이블만 하나 더 는다 |
| 버전키에 N분 시간 버킷 삽입 | 놓친 무효화가 자동 복구되는 장점은 있으나, 실측 재요청 중앙 간격이 2분이라 버킷이 짧으면 이득이 반감되고 길면 취소·등록 반영이 그만큼 늦어진다 |

### 2-5. 데이터 워터마크(①②③)

- ① 레거시: `MonitoringReadRepository.lastSuccessfulSweepAt()` — 이미 존재하고, 조립도
  이미 매번 호출한다(추가 비용 0).
- ② 브랜드: 내 연결 브랜드의 `max(brand_account.last_swept_at)` — 유저당 최대 6행 스코프.
- ③ 아카이브: `max(image_archived_at)`을 넣는 것이 정확하지만 `brand_post_meta`가 22,003행
  이라 인덱스 없이는 순차 스캔이다. **구현 시 비용을 재고 결정한다** — 비싸면 생략하고
  "썸네일 경로 갱신이 최대 24시간 늦게 반영됨"을 수용한다(스윕 워터마크가 매일 바뀌므로
  상한은 하루이고, 그동안 서빙되는 값은 지금도 쓰고 있는 원본 CDN URL이라 회귀가 아니다).

### 2-6. 배포 세대

응답 스키마가 바뀐 배포에서 옛 ETag가 그대로 맞으면 **새 필드가 영영 안 나가는** 사고가
난다. `CacheConfig`가 이미 쓰는 빌드 시각(`cacheEpoch`) 관용구를 그대로 버전키에 포함해
배포마다 전 ETag를 무효화한다.

### 2-7. 최종 형태

```
version = md5( cacheEpoch, userId, kstToday,
               legacySweepAt, brandSweepAt, [archivedAt],
               userFingerprint )
ETag: W/"<version 앞 16자>"
```

약한 검증자(`W/`)를 쓰는 이유는 **바이트 동일성을 주장하지 않기 위해서**다. gzip 인코딩
변형이나 직렬화 순서 차이로 바이트가 달라져도 의미는 같으며, 조건부 GET에는 약한 검증자로
충분하다(강한 검증자는 Range 요청에만 필요한데 이 표면은 쓰지 않는다).

## 3. 조기 반환 흐름

```
요청 도착
  ↓
[CORS 필터 · 인증 필터 통과]          ← 조기 반환은 반드시 이 뒤 (§5-①)
  ↓
버전키 계산 (쿼리 2~3개, 유저 스코프, 수 ms)
  ↓
If-None-Match == ETag ?
  ├ 예 → 304 Not Modified, 본문 없음      [조립·직렬화·전송 전부 생략]
  └ 아니오 → 기존 조립 → 200 + ETag 헤더
```

`ETag`를 **응답 본문을 만든 뒤 해싱하는 방식(`ShallowEtagHeaderFilter`)은 쓰지 않는다.**
그 방식은 (a) 응답 전체를 메모리에 버퍼링하므로 27MB 응답이 동시에 몇 개만 들어와도 2코어
운영 서버의 힙이 위험하고, (b) 조립은 그대로 다 한 뒤 버리는 것이라 이 설계 이득의 절반
(서버 시간)을 못 얻는다.

## 4. 캐시 헤더 변경

| | 현재 | 변경 후 |
|---|---|---|
| `Cache-Control` | `no-cache, no-store, max-age=0, must-revalidate` | `private, no-cache` |
| `Pragma` | `no-cache` | 제거 |
| `ETag` | 없음 | `W/"..."` |

- **`no-store` 제거가 핵심이다.** 저장이 금지되면 브라우저가 사본을 들고 있을 수 없어
  304가 성립하지 않는다. 이름과 달리 `no-cache`는 "쓰지 말라"가 아니라 **"쓰기 전에 매번
  재검증하라"**는 뜻이라 우리 용도에 정확히 맞는다.
- **`private`은 필수다.** 인증 응답이므로 공유 캐시(Caddy·CDN·사내 프록시)에 남으면 다른
  유저에게 새어 나갈 수 있다. `private`은 최종 사용자 브라우저 캐시만 허용한다.
- `max-age`는 두지 않는다 — 수집 완료 시각이 유동적이라 "언제까지 신선하다"를 약속할 수
  없다. 매 요청 재검증 + 304가 이 데이터의 성격에 맞는 형태다.
- **적용 범위를 두 표면으로 한정한다.** 전역으로 바꾸면 로그인·세션 등 저장되면 안 되는
  표면까지 저장 가능해진다.

## 5. 구현 시 함정 — **스테이징에서 확인할 항목**

아래는 전부 **`dev-api.hypenow.io`(develop→staging 머지로 배포되는 test 스테이징)에서
운영 반영 전에 실제로 확인한다.** 단위·통합 테스트로는 잡히지 않는 항목이 다수다(브라우저
동작·CORS·프록시가 얽힌 계층이라 Testcontainers 밖에 있다).

### ① CORS 응답에 CORS 헤더 누락 — 최우선 확인

프론트는 `www.hypenow.io`에서 `api.hypenow.io`를 직접 호출하는 CORS 구도다. **304 응답에도
`Access-Control-Allow-Origin`·`Access-Control-Allow-Credentials`가 실려야** 브라우저가
받아들인다. 빠지면 "서버는 304를 정상 반환했는데 브라우저에서 fetch가 실패"하는 형태로
나타나 원인 추적이 매우 어렵다.

Spring의 CORS 필터는 보통 상태 코드와 무관하게 앞단에서 헤더를 붙이지만, **조기 반환을
CORS 필터보다 앞에서 하면 건너뛴다.** 조기 반환 지점이 CORS·인증 필터 **뒤**에 오도록
배치한다(§3).

- 확인: 스테이징 프론트에서 대시보드를 열고 새로고침 → 두 번째 요청이 304이면서 화면이
  정상 렌더되는지. 브라우저 콘솔에 CORS 에러가 없어야 한다.
- 확인: `curl -H 'Origin: https://www.hypenow.io' -H 'If-None-Match: ...' -i` 로 304 응답
  헤더에 CORS 3종이 있는지 직접 확인.

### ② `Vary` 정합

현재 응답은 `Vary: origin, access-control-request-method, access-control-request-headers,
accept-encoding`이다. 브라우저 캐시 키가 이걸 포함하므로 `Vary` 구성이 요청마다 흔들리면
캐시가 매번 미스난다. 변경 전후로 `Vary`가 동일한지 확인한다.

### ③ gzip과의 상호작용

톰캣 응답 압축이 켜져 있다(08-12 도입, `server.compression`). ETag를 직접 붙이므로 압축
전/후 어느 쪽 기준인지 문제가 생길 수 있다. 약한 검증자를 쓰고 `Content-Encoding`에 따라
값이 달라지지 않게 한다(`Accept-Encoding`이 `Vary`에 있어 브라우저 캐시 키는 이미 분리된다).

- 확인: `Accept-Encoding: gzip`인 경우와 아닌 경우 **양쪽 모두** 304가 나오는지.

### ④ 304 응답의 본문·`Content-Length`

304는 본문을 실을 수 없다. 프레임워크가 `Content-Length`를 잘못 남기면 일부 클라이언트가
응답을 기다리며 멈춘다.

- 확인: 304 응답의 본문 길이가 0이고 `Content-Length`가 남아 있지 않은지.

### ⑤ 세션 슬라이딩

세션은 Spring Session JDBC(`app.spring_session`)다. **304로 조기 반환할 때도 세션
`lastAccessedTime`이 갱신되는지** 확인해야 한다. 갱신되지 않으면, 대시보드만 열어 두고
폴링하는 유저의 세션이 활동 중인데도 만료될 수 있다.

- 확인: 스테이징에서 304만 반복되는 상태로 세션 타임아웃 구간을 넘겨 로그인이 유지되는지.

### ⑥ 버전키 누락 검증 (정확성)

§2-1의 입력 다섯 가지를 각각 바꾼 직후 **즉시 200**(새 데이터)이 나오는지 확인한다.
하나라도 304가 나오면 지문 대상에서 빠진 컬럼이 있다는 뜻이다.

- 추적 등록 / 취소 / 기간 변경 / 캠페인 변경 / 브랜드 연결 / 브랜드 해제 각각 직후

### ⑦ 자정 경계

KST 자정 전후로 ETag가 바뀌는지(§2-1 ⑤). 스테이징에서 서버 시계를 기다리기 어려우면
통합 테스트의 시각 주입으로 대신하되, **확인했다는 사실을 남긴다.**

### ⑧ 지표 해석 주의 (사고 방지용 기록)

304를 받아도 브라우저가 본문을 이미 축출했으면 곧바로 200 전량 요청이 뒤따른다. 정상
동작이지만, 이 때문에 **운영 304 비율이 예상(61%)보다 낮게 나올 수 있다.** 낮다고 해서
구현 결함으로 오진하지 않도록 여기 적어 둔다.

## 6. 검증 계획

| 층 | 내용 |
|---|---|
| 단위 | 버전키 계산 — 입력 5종 각각이 바뀌면 키가 바뀌는지(테이블 주도 테스트). §2-3의 "해싱 컬럼 ↔ 응답 영향 컬럼" 결합을 여기서 고정한다 |
| 통합(Testcontainers) | 같은 상태로 두 번 요청 → 두 번째 304. 각 쓰기 직후 요청 → 200. 시각 주입으로 자정 경계 |
| 스테이징 수동 | **§5의 8개 항목 전부** — 운영 반영 전 필수 |
| 운영 관측 | `http_server_requests_seconds_count{status="304"}` 비율, Caddy 액세스 로그 누적 전송량(현재 1.6GB 기준선) |

## 7. 기대 효과 (측정 기반 추정)

| 경로 | 현재 | 도입 후 | 배수 |
|---|---|---|---|
| 반복 요청(무변경) — 전체의 ~61% | 1,085ms / 3.6MB | **~15ms / ~0.3KB** | 약 70배 |
| 최초 진입(콜드) | 1,085ms | **1,085ms** | **변화 없음** |

한 유저가 하루 5회 열어보는 시나리오(`/contents`+`/comparison` 쌍) 누적:
**9,530ms → 약 1,600ms(6배), 전송 18MB → 3.6MB(5배)**.

**한계를 명시한다.**

- **최초 진입은 전혀 빨라지지 않는다.** 이 설계는 재방문 최적화다. 첫인상 개선은
  이미지 아카이브 수렴(페이로드 축소)과 조립 캐시 몫이다(§8).
- **61%는 상한이지 보장이 아니다.** 304는 브라우저가 본문을 갖고 있어야 성립하는데,
  브라우저 HTTP 캐시는 용량 기반 축출이라 3.6MB 항목의 잔존이 보장되지 않는다. 이를
  확정적으로 만드는 것은 프론트의 IndexedDB 영속화이며(§8), **백엔드만으로는 상당 부분이
  커버되지만 전부는 아니다.**

### 페이지네이션을 기각한 이유

> ⚠️ 이 절의 기각은 2026-08-27 [대시보드 목록 최적화 설계](2026-08-27-perf-dashboard-list-api-optimization-design.md)로 대체됐다 — 전제였던 "전량 수신 후 클라이언트 필터" 구조가 UI 개편으로 소멸.

목록 상한 철폐(08-10) 이후 "페이지네이션 복원"이 자연스러운 대안으로 보이나, **이 화면과
충돌한다.** 프론트는 필터를 서버로 보내지 않고 전량을 한 번 받아 브라우저에서 필터하므로
(`filterPerformanceContents`), 필터 조작에 네트워크가 0회다. 페이지네이션을 넣으면 필터
칩 하나 누를 때마다 왕복이 생겨 **탐색 체감이 지금보다 나빠진다.** 게시물이 현재(유저당
약 5,000건)의 10배쯤 되어 캐시로도 첫 로딩을 못 버티는 시점에 재검토한다.

## 8. 후속

| 항목 | 소관 | 비고 |
|---|---|---|
| 조립 캐시(Redis 또는 인프로세스) | 백엔드 | 별도 문서. `PerformanceContentAssembler.assembleSlim(userId)` 자리에 걸어 `/contents`·`/comparison` 쌍이 조립을 공유. **직렬화 비용(12MB 객체 그래프)을 먼저 측정**하고 Redis/Caffeine을 정한다 |
| `/brand-monitoring/.../posts`로 확장 | 백엔드 | 반복률 78%·누적 790MB로 단일 항목 최대. 같은 관용구 |
| 영속화 화이트리스트 버그 | **프론트(celfit-front)** | `persist.ts:37`의 `queryKey.length === 2`가 대시보드의 길이 4 키를 떨궈 6MB가 IndexedDB에 저장되지 않는다. 별도 전달 필요 |
| `STALE_TIME.performanceContents` 상향 | 프론트 | 5분 → 30분(같은 파일 `keys.ts:86`에 이미 선례) |
