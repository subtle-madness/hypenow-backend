# 어드민 전역 크롤링 비용 API 설계 — 파이프라인별 분해 + 크롤러 몫 미러 경유

> 상태: ✅ 구현됨 (2026-08-13) · 서버 반영은 develop→staging 승격 후

[2026-08-12 유저별 크롤링 사용량 카드](2026-08-12-admin-crawling-usage-design.md)의 전역판.
그 카드가 "이 유저가 얼마 썼나"를 답한다면, 이 API는 **"서비스 전체가 크롤링에 얼마 쓰고 있고,
그 돈이 어느 파이프라인에서 나가나"** 를 답한다. 프론트는 어드민 앱의 전역 비용 화면.

## 1. 범위

집계 대상은 **유료 요청이 실제로 나간 크롤링 콜** 전부 — 두 갈래다.

| 갈래 | 산지 | 비고 |
|---|---|---|
| 모니터링 | monitoring DB `brand_call_count`(브랜드 태그) · `target_call_count`(캠페인·콘텐츠) | 08-12 카드가 이미 쓰는 표면 |
| 크롤러 파이프라인 | raw DB `crawl_run.request_count`(discover·qualify·collect·similar·reels) | 신규 — 미러 경유로 가져온다(§4) |

**명시적 제외**:

- **Apify 액터 과금** — 결과 건당 과금이라 `request_count`가 null이고, 액터마다 단가가 달라
  콜 기반 산출이 구조적으로 불가능하다. 운영에서 사실상 이탈한 경로라 추정 행조차 두지 않는다.
- **무료 소스** — `profile-self`(instagram web_profile_info)는 과금이 없어 `request_count`가 0/null.
- **BEAUTY 잡** — 판정이 로컬 LLM이고 인스타그램 호출이 없다.
- **프록시 비용**(DataImpulse 등)·서버 비용 — 콜 단위로 귀속되지 않는 고정비다.

**유료 공급자를 Hiker 단일로 접는다.** 단가도 기존 키 하나(`crawling.unit-price-usd`)를 전 소스에
곱한다.

> **2026-08-13 갱신 — 이 전제가 좁아졌다.** 최초안은 "DataLikers 경로는 운영에서 쓰지 않아"를
> 근거로 들었으나, 후속 PR #472가 `CountingDataLikersHttp`를 추가해 **DataLikers 콜도
> `crawl_run.request_count`에 함께 쌓인다**. 즉 이 API는 단가가 다른 두 공급자
> (Hiker $0.001 / DataLikers $0.0006 — 각 모듈 설정값)를 **하나의 전역 단가로 뭉개** 계산한다.
> 세지 않으면 요청이 0으로 사라져 오차가 더 커지므로 세는 쪽이 맞지만, **공급자별 정산에는
> 쓸 수 없는 근사**라는 뜻이다. 공급자별 분해가 실제로 필요해지면 확장 지점은 두 곳이 아니라
> 세 곳이다 — `v_crawl_call_daily`가 **job 단위 집계라 공급자 축이 아예 없어**(§6) 집계 축부터
> 손대야 한다.
>
> 단가 자체도 근사다: Hiker 요금은 물량 구간제라 공개 표기가 "From $0.0006/Request"이고
> (hikerapi.com/pricing), crawler 설정의 `hiker.cost-per-request-usd: 0.001`은 하위 구간 값이다.
> 이 API의 전역 단가 기본값 `0.0006`은 그 하한에 해당하므로, **실제 구간이 확인되기 전까지는
> 하한 추정**으로 읽어야 한다.

## 2. API 계약

### GET /v1/admin/crawling-cost/summary (ADMIN 전용)

인가는 기존 `SecurityConfig`(`/v1/admin/**` hasRole ADMIN + 신선도 필터)가 그대로 처리한다.
표준 envelope의 `data`:

```json
{
  "totals": {
    "totalCalls": 812043, "monthCalls": 41220, "todayCalls": 1508,
    "totalCostUsd": 487.2258, "monthCostUsd": 24.7320, "todayCostUsd": 0.9048
  },
  "breakdown": [
    { "key": "BRAND_MONITORING", "label": "브랜드 태그 모니터링",
      "totalCalls": 12043, "monthCalls": 3220, "todayCalls": 108,
      "totalCostUsd": 7.2258, "monthCostUsd": 1.9320, "todayCostUsd": 0.0648 },
    { "key": "CAMPAIGN_MONITORING", "label": "캠페인·콘텐츠 모니터링", "…": "…" },
    { "key": "CRAWLER_DISCOVER",    "label": "해시태그 발굴",         "…": "…" },
    { "key": "CRAWLER_QUALIFY",     "label": "프로필 판정",           "…": "…" },
    { "key": "CRAWLER_COLLECT",     "label": "게시물 수집",           "…": "…" },
    { "key": "CRAWLER_SIMILAR",     "label": "유사 계정 발굴",        "…": "…" },
    { "key": "CRAWLER_REELS",       "label": "릴스 수집",             "…": "…" }
  ],
  "unitPriceUsd": 0.0006,
  "sources": [
    { "key": "MONITORING", "available": true, "latestCallOn": "2026-08-13" },
    { "key": "CRAWLER",    "available": true, "latestCallOn": "2026-08-13" }
  ]
}
```

- `totals`는 `breakdown` 전 행의 합과 항상 일치한다(같은 누적기에서 나온다).
- `breakdown`은 **콜이 0인 구간도 행을 유지**한다 — 행이 사라지면 FE가 "그 파이프라인이
  없어졌다"와 "안 썼다"를 구분할 수 없다. 순서는 위 고정 순서.
- 알려지지 않은 크롤러 잡(향후 추가분)은 `key: "CRAWLER_<JOB>"`, `label`은 잡 코드명 그대로
  노출한다 — 매핑 누락이 비용을 조용히 삼키지 않게 한다.
- 비용은 **반올림하지 않는다** — `calls × unitPriceUsd`의 BigDecimal 결과 그대로. 표시 자리수는
  FE 몫이다(반올림을 서버가 하면 구간 합과 총합이 어긋난다).
- **이 GET은 404를 내지 않고, 못 읽은 "구간"은 500 대신 열화로 접는다** — 08-12 카드와 같은
  규율(§5). 단 "어떤 경우에도 500이 없다"는 뜻은 아니다 — 기본 데이터소스 전면 불통은 예외(§5 말미).

### 단가 수정 — 신규 엔드포인트 없음

기존 `PUT /v1/admin/crawling-cost/unit-price`가 그대로 정본이다. 유료 공급자가 Hiker 단일이라
공급자별 단가가 필요 없고, 이 화면과 유저별 카드가 **같은 키를 읽으므로 두 화면의 단가가
갈라질 수 없다**.

## 3. 집계 규칙

### 3-1. 콜의 정의가 두 갈래로 다르다

- **모니터링**: Hiker HTTP 교환 1회 = 1콜(전송 계층 계수 — 08-12 설계 §2).
- **크롤러**: `crawl_run.request_count` = 그 실행이 산 유료 요청 수. 실행 1건이 여러 요청을 산다.

둘을 더하지만 **단위가 같다**(둘 다 "유료 요청 1회") — 집계 지점만 다를 뿐이다. 화면에도
이 정의를 병기해 "게시물 수"로 오해되지 않게 한다.

### 3-2. 크롤러 몫: `request_count > 0`인 실행만

Apify 실행(`request_count IS NULL` — 결과 건당 과금)과 무료 소스(`profile-self` — 0)가 이 한
조건으로 자연히 빠진다(§1). 별도 액터 라벨 화이트리스트를 두지 않는 이유가 이것이다 — 새 무료
경로가 생겨도 규칙을 고칠 필요가 없다.

**`IS NOT NULL`이 아니라 `> 0`이다**: 두 술어는 같지 않다. `IS NOT NULL`은 `profile-self`
실행(`request_count = 0`)을 남겨 콜이 0인 유령 행을 만들고, 하니스는 그런 행이 없어야 한다고
단언한다.

`status`는 거르지 않는다. 요청이 나간 뒤 404로 끝난 실행(ReelsJob·SimilarJob의 '콘텐츠 없음')도
**요청은 이미 샀으므로** 값을 유지한다 — 과금 실체와 일치한다.

**한계 — 이 API는 과소 보고 쪽으로 치우친다.** 이 브랜치 시점의 `request_count`는
`CrawlExecutor`의 **성공 경로(`finishOk`)에서만, 그것도 페처가 스스로 보고할 때만** 기록된다.
누락이 두 갈래였다:

1. **실패로 끝난 실행** — `finishFailed`가 값을 건드리지 않아 NULL로 남는다. 페이지 4개를 사고
   5번째에서 던진 `DISCOVER` 실행이 0으로 잡힌다. 유실이 장애 구간(예: 2026-07-24 Hiker IP 차단)에
   집중되는데 하필 그때가 비용을 묻게 되는 시점이라 오차 방향이 나쁘다.
2. **성공했는데도 보고하지 않는 페처** — 프로필 조회 4종(`profile-hiker-mobile`·
   `profile-hiker-webgql`·`profile-datalikers`·SELF의 Hiker 폴백)이 `requestCount` 없는
   `ApifyResult` 생성자를 써서 전부 NULL이었다. **이쪽이 훨씬 컸다**: 로컬 raw DB 4일치
   (2026-07-16~19) 실측으로 `profile-hiker-mobile` 1,252실행 / 13,084계정 조회가 **0으로 집계**됐고,
   같은 기간 이 API 전체 보고액보다 큰 금액이다. 게다가 그게 07-23 이후 **운영 프로필 소스**다.

**둘 다 후속 PR로 해소된다**(머지 전까지는 위 한계가 유효):
[#470](https://github.com/subtle-madness/hypenow-backend/pull/470)이 전송 계층 카운터
(`CountingHikerHttp` + 실행 스코프 `PaidCallCounter`)로 실패 경로를 메우고,
[#472](https://github.com/subtle-madness/hypenow-backend/pull/472)가 워커 풀 팬아웃 전파와
"페처가 보고 안 했을 때만 실측치로 대체" 규칙으로 성공 경로를 메운다.

**미해결 쟁점 — 4xx 과금분 미계수.** 두 모듈의 `CountingHikerHttp` 모두 **예외로 빠지는 응답을
세지 않는다.** 그런데 HikerAPI의 과금 정책은 이렇다([hikerapi.com/faq](https://hikerapi.com/faq),
"What requests do you charge for?" — 2026-08-13 확인):

> "We charge for any successful response (such as 200, 400, 403, 404). Deprecated endpoints
> respond with 410 Gone and are never charged. For 50x errors we do not charge."

**핵심은 Hiker가 쓰는 "successful"이 HTTP 2xx가 아니라는 것이다** — 괄호가 400·403·404를 그
예로 명시한다. 즉 "응답을 돌려받았다"는 뜻이다. 두 모듈의 주석에 적힌 "Hiker 과금은 성공 응답
기준"은 **인용이 틀린 게 아니라 그 단어를 2xx로 읽은 해석이 좁은 것**이다(문서가 유도하는
오독에 가깝다). 결과적으로 집계 규칙이 실제 과금보다 좁아 **과소 보고가 한 겹 더 남는다.**

프로필 경로 한정으로는 영향이 미미하다(계정 소멸 404가 ~12,800건 중 5건, 0.04%). 다만 페이지
반복 중 실패가 잦은 발굴(`HikerDiscoverFetcher`)과, **'chaining 불가' 404를 이미 soft-404로 1로
세고 있는** 유사계정(`SimilarJob`)은 성격이 다르다 — 특히 후자는 4xx 계수를 켜면 **그 경로만
이중 계상**이 될 수 있다. **규모 측정이 선행돼야 하는 별도 작업**으로 분리돼 있다(§8).

### 3-3. KST 달력일

`(started_at AT TIME ZONE 'Asia/Seoul')::date`. `brand_call_count.called_on`·
`target_call_count.called_on`이 이미 KST 달력일이라 세 소스의 경계가 한 시간대로 정렬된다.

`monthCalls` = 이번 달 1일 0시(KST) 이후, `todayCalls` = 오늘 0시(KST) 이후. 미래 날짜 행(이론상)은
month·day에서 제외한다(08-12 `PeriodSums`와 같은 규칙).

실행이 자정을 넘겨 끝나면 **시작일 몫**으로 계상된다. `crawl_run`은 실행 단위라 요청을 시각별로
쪼갤 수 없고, 크롤 잡이 01:00~03:55 KST 창에서 도는 짧은 실행들이라 오차가 무의미하다.

### 3-4. 전역 합계는 유저별 값의 합이 아니다

`brand_call_count`를 **브랜드 축에서 직접 합산**한다. 유저별 카드는 연결 기간으로 자른
유저 몫이고, 같은 브랜드를 여러 유저가 공유하면 그 콜이 유저마다 계상된다(08-12 설계 §3의
"비용 상한 관점"). 따라서 **유저별 카드를 전부 더한 값 > 이 API의 합계**가 정상이며, **브랜드
축에서는** 실제로 나간 돈이 이 API 쪽이다. 계약 문서와 화면 툴팁 양쪽에 명시한다.

**캠페인 축에는 같은 보장이 없다.** `target_call_count`는 monitoring이 콜 시점에 유저 키로
적재하는데, `CountingHikerHttp`는 HTTP 교환 1회에 대해 **서빙 유저마다** +1한다
(`for (Long userId : userIds) targetCounts.add(userId, today, 1)`). 그래서 이 테이블을 전역
합산하면 한 번 과금된 콜이 팬아웃 배수만큼 곱해져 **`CAMPAIGN_MONITORING` 구간은 상한 쪽으로
치우칠 수 있다** — 브랜드 축처럼 원본 축 합산으로 팬아웃을 제거할 수 없다(원본 자체가 이미
유저별로 쪼개져 적재된다). 08-12 실측에서 콜당 유저 중복이 관측되지 않아 현재 오차는 0이지만,
그건 데이터의 우연이지 집계 구조의 보장이 아니다. 콜 축 원본(교환 1회 = 1행)이 필요해지면
monitoring의 적재 스키마를 바꾸는 별도 작업이다.

같은 이유로 이 API는 유저 축 분해를 제공하지 않는다 — 제공하면 합이 안 맞는 두 숫자가
한 화면에 놓인다.

## 4. 데이터 경로 — 크롤러 몫을 was로 가져오기

```
crawler ──▶ raw.crawl_run
                 │
                 └─ analytics: 뷰 v_crawl_call_daily ──미러──▶ analysis.crawl_call_daily(job, called_on, calls)
                                                                        │
monitoring ──▶ monitoring.brand_call_count / target_call_count          │
                                     │                                  │
                                     └──────────┬───────────────────────┘
                                   was AdminCrawlingCostSummaryService
```

**채택: analytics 미러 경유.** ARCHITECTURE §2의 데이터 흐름(analytics = raw 읽기 → analysis 쓰기,
was = analysis 읽기)을 그대로 탄다. was는 기본 데이터소스에서 스키마 접두어 없이 `crawl_call_daily`를
읽는다 — `contents`·`account_summaries`와 같은 자리라 새 커넥션이 없다. 미러의 컬럼 대조 가드가
뷰↔record 드리프트를 쓰기 시점에 잡는다.

**대가는 신선도**: 미러가 04:30 KST 하루 1회다. 정기 수집(01:00~03:55)은 당일 아침에 반영되지만,
**낮에 수동 트리거한 발굴(discover·similar)은 다음 미러까지 안 보인다.** 응답의
`sources[].latestCallOn`이 이 지연을 드러내는 신호다(§5).

**기각한 대안**:

- **crawler 내부 HTTP API** (was → `http://crawler:8080`) — 실시간이지만 ARCHITECTURE §2의
  "3-tier, 층 사이는 DB로만 통신(모듈 간 HTTP/큐 없음)" 원칙을 깬다. monitoring↔was HTTP는
  나중에 붙은 별도 모듈의 예외였지 코어 3층의 선례가 아니다. 더 결정적으로 **스테이징에는
  test-crawler 자체가 없어**(deploy/compose.test.yaml) dev-api에서 이 구간이 영구 부분 응답이 되고,
  crawler 재기동 중에는 운영 카드도 열화한다. 비용 카드는 실시간성을 요구하는 표면이 아니고,
  단가 자체가 운영자가 손으로 조정하는 근사값이라 이 대가가 얻는 것보다 크다.
- **was가 crawler DB 직접 읽기** — CLAUDE.md 시스템 경계 위반(`was → raw DB 접근 금지`).

## 5. 열화 규칙 — 0과 "모름"을 구분한다

08-12 카드의 "404는 프론트가 미배포로 해석하는 예약 신호" 규율을 이어받아, **이 GET도 404를
내지 않고, 못 읽은 "구간"은 500 대신 열화로 접는다.** 못 읽은 구간은 `sources[].available: false`로
표시하고 그 구간의 집계는 0으로 둔다.

**"어떤 경우에도 500이 없다"는 뜻은 아니다.** 단가 조회(`app.app_setting`)는 구간 열화의 대상이
아니라 응답 조립의 전제라 열화 경로 밖에 있고, 그래서 **기본 데이터소스 자체가 불통이면 이 API도
500이 된다**(실은 그 전에 죽는다 — 세션 저장소가 같은 데이터소스라(Spring Session JDBC,
`app.spring_session`) 인증 단계에서 이미 실패한다). 08-12 유저별 카드도 같은 모양이고, 이 경계는
의도된 것이다 — DB 전면 불통은 어드민의 다른 모든 표면과 함께 죽는 장애이지 이 화면만의 열화
사유가 아니다.

| 상황 | 결과 |
|---|---|
| `monitoring.enabled=false`(로컬 기본) | `MONITORING.available=false`, 모니터링 2행 0 |
| monitoring DB 조회 예외(DataAccessException) | 같음 + WARN 로그. 카드 전체를 장애로 만들지 않는다 |
| `crawl_call_daily` 비어 있음(미러 이전) | `CRAWLER.available=true`, `latestCallOn=null`, 크롤러 5행 0 |
| `crawl_call_daily` 조회 예외(DataAccessException) | `CRAWLER.available=false` + WARN 로그, 크롤러 구간 0. **부분 누적분은 되돌린다** — 매핑 밖 잡이 `computeIfAbsent`로 중간에 만든 키까지 제거한다 |
| 미러가 멈춤(스테일) | `latestCallOn`이 과거 날짜로 굳는다 — FE가 "N일 전 기준"을 표시 |
| `crawling.unit-price-usd` 값이 숫자 아님 | 기본값 `0.0006` 폴백 + WARN(기존 `currentUnitPrice()` 그대로) |

`latestCallOn`은 각 소스가 가진 **최신 `called_on`** 이다. 미러 시각을 따로 적재하지 않고
데이터에서 유도하므로 스키마에 메타 컬럼이 늘지 않는다.

**`CRAWLER.available`도 실제로 `false`가 될 수 있다**(2026-08-13 구현 중 교정 — 최초안은 "미러
테이블이 was 기본 데이터소스에 있으니 항상 true"라고 적었는데, 이는 *데이터소스 다운*과 *테이블
부재*를 뭉갠 것이었다). `crawl_call_daily`의 Flyway는 was가 아니라 **analytics 모듈 소관**이다
(`db/migration/analysis`). analytics가 그 마이그레이션을 적용하기 전에 was가 뜨거나 analytics를
롤백하면, 데이터소스는 멀쩡한데 `relation "crawl_call_daily" does not exist`가 난다 — 어드민의
나머지는 정상인데 이 화면만 500으로 죽는다. **이 기능이 새로 들여온 크로스 모듈 배포 스큐가 정확히
이 모양이라, 두 소스를 대칭으로 열화 처리한다.**

## 6. 구현 자리

| 모듈 | 파일 | 내용 |
|---|---|---|
| contract-analysis | `CrawlCallDaily` (신규 record) | `(String job, LocalDate calledOn, long calls)` — 미러 그릇 |
| analytics | `views/30_crawl_cost.sql` (신규) | `v_crawl_call_daily` — §3-2·3-3 규칙 |
| analytics | `test/30_crawl_cost.test.sql` (신규) | SQL 하니스 |
| analytics | `db/migration/analysis/V<UTC>__crawl_call_daily.sql` (신규) | 미러 테이블 DDL |
| analytics | `mirror/MirrorConfig` | 등록부에 `MirrorSpec<>("v_crawl_call_daily", "crawl_call_daily", CrawlCallDaily.class)` 1행 |
| was | `v1/admin/AdminCrawlingCostController` | GET summary 추가(기존 GET·PUT 불변) |
| was | `v1/admin/AdminCrawlingCostSummary` (신규 record) | 응답 계약 |
| was | `v1/admin/AdminCrawlingCostSummaryService` (신규) | 3소스 합산·단가 곱셈·열화 판정 |
| was | `crawlcost/CrawlCallDailyRepository` (신규) | `analysis.crawl_call_daily` 조회(기본 JdbcClient) |
| was | `monitoring/BrandReadRepository` | `sumDailyCallCounts()` 추가 — 전 브랜드 날짜별 합 |
| was | `monitoring/MonitoringReadRepository` | `sumDailyCallCounts()` 추가 — 전 유저 날짜별 합 |

마이그레이션 없음(was) — 단가 키는 `V20260812100500`이 이미 시드했다. analytics 신규 마이그레이션은
**UTC 타임스탬프 채번**(`date -u +%Y%m%d%H%M%S`).

전역 합산 쿼리는 `GROUP BY called_on`이라 행 수가 날짜 수(수백)로 접힌다 — 유저별 카드처럼
전량을 Java로 끌어오지 않는다.

## 7. 검증

- **SQL 하니스** `analytics/test/30_crawl_cost.test.sql` — KST 자정 경계(23:59:59Z / 00:00:01Z 쌍),
  `request_count > 0` 모수(Apify의 NULL과 self의 0을 둘 다 제외 — 0짜리 유령 행이 없어야 한다),
  요청이 나간 뒤 404로 끝난 실행의 `request_count` 유지분 포함, 잡별 그룹핑.
  시드 `started_at`은 **실데이터가 도달할 수 없는 미래(KST 2099-06-05·06)** 로 둔다 — 하니스는
  실데이터 컨테이너에서 돌기 때문에, 과거 날짜로 모수를 좁히면 운영 덤프를 복원한 머신에서
  진짜 `crawl_run` 행이 그 날에 걸려 거짓 실패가 난다.
- **`AdminCrawlingCostSummaryServiceTest`** — 월초·자정 경계 전후 1초 고정 Clock 쌍, 세 소스
  합산이 `totals` = `breakdown` 합을 만족, 미러 부재·`monitoring.enabled=false`·monitoring 조회
  예외 각각의 열화 응답, 단가 폴백.
- **`AdminCrawlingCostSummaryIntegrationTest`** — 실 DB. 인가 401/403, 200 응답 형태(0 구간도 행
  유지), 단가 PUT 후 GET에 즉시 반영.

## 8. 후속 (이번 범위 밖)

- ~~**실패 실행의 유료 요청 기록**~~ → [#470](https://github.com/subtle-madness/hypenow-backend/pull/470)에서 해소(전송 계층 `CountingHikerHttp` + `PaidCallCounter`).
- ~~**성공했는데 보고 안 하는 프로필 페처**~~ → [#472](https://github.com/subtle-madness/hypenow-backend/pull/472)에서 해소(워커 풀 sink 전파 + null일 때만 실측치 대체). #470 위에 스택.
- **4xx 과금분 계상**(§3-2 미해결 쟁점) — HikerAPI가 400·403·404도 과금하므로 두 모듈의
  `CountingHikerHttp`가 그만큼 덜 센다. **먼저 규모를 측정할 것** — 프로필 경로는 0.04% 수준이라
  블라스트 반경(`ApifyException`에 status를 실어 crawler 전체 실패 경로 집계 규칙을 바꿔야 함) 대비
  값이 작다. 발굴·유사계정 경로부터 재 보고 판단하되, **`SimilarJob`은 이미 soft-404를 1로 세고
  있어 이중 계상 위험**이 있다. 고치는 사람이 FAQ 첫 문장("we only charge for successful
  requests")만 보면 "이미 맞게 세고 있다"고 오판하기 쉬우니, **괄호의 400·403·404까지 읽을 것**.
- **공급자별 단가 분해**(§1 갱신) — Hiker와 DataLikers가 한 단가로 뭉개져 있다. 필요해지면
  `v_crawl_call_daily`에 공급자 축을 추가하는 것부터 시작해야 한다(현재는 job 단위).
- 일별 추이 그래프 — 미러 테이블이 이미 날짜 축을 갖고 있어 API만 열면 된다.
- 상위 소비자 랭킹 — §3-4의 이중 계상 때문에 "합계와 안 맞는 숫자"를 화면에 올리는 문제를
  먼저 정리해야 한다.
- 미러 주기 단축 — 낮 수동 발굴의 당일 반영이 필요해지면 비용 집계만 별도 크론으로 분리.
