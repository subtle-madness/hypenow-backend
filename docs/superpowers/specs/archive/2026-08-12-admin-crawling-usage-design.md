# 어드민 크롤링 비용 카드 API 설계 — 유저별 사용량 집계 + 전역 단가

> 상태: ✅ 구현/반영됨 (2026-08-12)

프론트 요청서(celfit-front `apps/admin/docs/backend-request-2026-08-12-crawling-usage.md`)에 대한
백엔드 설계·응답 계약 정본. 프론트는 이 계약대로 이미 구현·배포돼 있어 **응답 형태는 변경 불가**다.

## 1. API 계약

### GET /v1/admin/users/{id}/crawling-usage (ADMIN 전용)

표준 envelope의 `data`:

```json
{ "totalCalls": 2541, "monthCalls": 300, "todayCalls": 12, "unitPriceUsd": 0.001 }
```

- 집계 단위: 브랜드 태그 모니터링 파이프라인의 **Hiker HTTP 교환 1번 = 1콜**
  (수집 게시물 수 아님 — 열거·댓글은 페이지마다 1콜씩 센다).
- `monthCalls` = 이번 달 1일 0시(KST) 이후, `todayCalls` = 오늘 0시(KST) 이후.
  미래 날짜 행(이론상)은 month에서 제외된다.
- **이 GET은 404를 내지 않는다** — 프론트가 404를 "엔드포인트 미배포"(카드 열화)로 해석하는
  예약 신호라서다. 크롤링 이력이 없는 유저, 존재하지 않는 유저, 숫자가 아닌 id 전부
  200 + 전부 0으로 응답한다(유저 존재 검증은 유저 상세 GET 담당).
- **`totalCalls`는 과거 콜 소급 백필을 포함한다**(같은 날 사용자 결정 — 최초안 "집계 시작 이후
  누적"을 대체). 콜 원형 `raw.fetch_payload`(성공 콜 1행 = 1콜)에서 브랜드 귀속을 복원해
  배포 시 1회 소급한다(`V20260812153000` — 원형 적재 개시 07-30 이후분). 귀속은 근사다 — §2-1.

### PUT /v1/admin/crawling-cost/unit-price (ADMIN 전용)

body `{ "unitPriceUsd": 0.001 }` — 전역 단가(유저별 아님) 수정, 즉시 이후 GET에 반영.

- 0 이상의 유한한 숫자만. 음수·누락·null → 400 `VALIDATION_FAILED`(서비스 판정),
  `"NaN"`·`"abc"` 등 숫자가 아닌 본문 → 400 `VALIDATION_FAILED`(본문 파싱 단계).
- 저장은 `app.app_setting['crawling.unit-price-usd']` — 초기 시드 `0.0006`
  (V20260812100500, Hiker 계약 단가 실측치 — DECISIONS 08-06 비용 산정과 동일 값. 운영자가 PUT으로 즉시 조정 가능).
- 감사 흔적은 애플리케이션 로그(`AdminCrawlingCostController` — Loki `{service="was"}`)로
  남긴다. `app.admin_audit_logs`는 act-as 전용(target_user_id NOT NULL)이라 쓰지 않는다.

## 2. 콜 카운트의 출처 — 시스템 경계 결정

크롤링 호출은 monitoring 모듈(브랜드 태그 파이프라인, Hiker) 소속이고 was는 raw/monitoring
내부에 못 들어간다. 기존 계약 표면을 그대로 따른다:

```
monitoring: CountingHikerHttp(전송 데코레이터) ──upsert──▶ brand_call_count(brand_id, called_on KST, calls)
was:        읽기 전용 SELECT(was_reader, V2 기본권한 자동 적용) + app.brand_monitorings(연결 기간) → Java 합산
```

- **집계 지점은 전송 계층**(`HikerConfig`의 데코레이터 체인, RecordingHikerHttp와 같은 근거) —
  "HTTP 교환 1번 = 1콜"이 구조적으로 성립하고, 파싱 계층에선 페이지 수가 사라진다.
- **성공 콜만 센다** — Hiker 과금이 성공 응답 기준이라 실패(재시도 소진·404)까지 세면 비용
  추정이 부풀어 오차 방향이 나쁘다. 재시도는 JdkHikerHttp 내부라 논리 콜 1로 접힌다.
- **브랜드 귀속은 ThreadLocal 스코프**(`BrandCallContext`) — sweepCore/enrich·해시태그 스윕
  (`BrandHashtagCollectService.sweep`)이 스코프를 열고, enrich의 워커 풀 팬아웃은 태스크 본문을
  `runScoped`로 재전파한다(ThreadLocal은 풀을 못 넘는다).
  - 등록 검증 프로필 1콜은 콜 시점에 brand_id가 없어 **등록 성공 직후 +1 사후 계상**.
    등록 실패(계정 부재·비공개) 콜은 귀속 브랜드가 없어 미집계.
  - 캠페인(시딩) 모니터링 콜은 스코프 밖 — 집계 대상 아님(요청서 범위 = 브랜드 모니터링).
  - 게시자 프로필은 브랜드 간 전역 캐시지만 콜을 유발한 브랜드 몫으로 계상.
- 쓰기는 콜당 +1 upsert(콜 간격 ~1.5초라 부하 무시 가능), 집계 실패는 삼킨다 — 비용 관측이
  수집을 죽이면 안 된다.

### 2-1. 과거 콜 소급 백필 (V20260812153000)

실시간 집계 개시 이전 콜은 원형 `raw.fetch_payload`에서 1회 복원한다(배포 시 Flyway).
귀속 규칙과 한계(전부 근사 — 실측 로컬 검증: 원형 21,073행 중 13,435콜 귀속):

| kind | 귀속 | 비고 |
|---|---|---|
| `TAGGED` | subject(브랜드 ig_user_id) → 정확 | |
| `PROFILE` | subject(username)가 brand_account에 있는 것만 | 캠페인 감시 계정과 이름이 겹치면 캠페인 콜도 계상될 수 있음(수용) |
| `PROFILE_BY_ID` | 그 게시자를 태그한 브랜드 중 최소 id 대표 | 전역 캐시라 유발 브랜드 사후 특정 불가 — 이중 계상은 없음 |
| `COMMENTS` | subject(media pk) → 숏코드 산술 복원 → 태그 링크 최소 brand_id | 캠페인 전용 게시물은 자연 제외, 캠페인·브랜드 겹침은 브랜드로 계상(수용) |
| 해시태그 열거(`OTHER`)·캠페인 kind | 소급 안 함 | 해시태그는 08-11 개통이라 잔량 미미 — 향후분은 스코프가 실시간 계상 |

## 3. 유저 귀속 — 연결 기간 기준

`brand_call_count`는 브랜드 단위다. 유저 몫은 was가 `app.brand_monitorings`(해제분 포함)의
**연결 기간(연결일~해제일, KST 달력일, 양끝 포함)** 으로 자른다:

- 연결 전 이력(같은 브랜드를 먼저 쓰던 다른 유저의 콜, 예: 847콜 백필)을 물려받지 않는다.
- 해제 후 콜(다른 유저 몫으로 계속 도는 수집)을 떠안지 않는다. 해제한 유저의 과거 기간 콜은 남는다.
- 같은 날 여러 연결이 겹쳐도 행 단위 포함 판정이라 이중 계상 없음.
- **같은 브랜드를 여러 유저가 공유하면 그 기간의 콜은 양쪽 모두에 계상된다**(유저별 비용 상한 관점 —
  전사 합계 용도로 유저 합산을 쓰면 중복이 낀다는 뜻. 카드는 유저 단건 조회라 문제 없음).
- 합산은 was Java에서 한다 — 링크는 app 스키마, 콜은 monitoring DB라 SQL 조인 불가(크로스 DB
  조인 금지 원칙과 정합), 행 수도 브랜드당 하루 1행이라 전량 조회가 싸다.
- monitoring.enabled=false(로컬 기본)면 집계는 0, 단가는 정상 서빙.

## 4. 구현 자리

| 모듈 | 파일 |
|---|---|
| monitoring | `V20260812100000__brand_call_count.sql`·`V20260812153000__brand_call_count_backfill.sql` / `hiker/BrandCallContext` / `hiker/CountingHikerHttp` / `store/BrandCallCountRepository` / `config/HikerConfig`(체인 조립) / `BrandCollectService`·`BrandRegistrationService`·`BrandHashtagCollectService`(스코프 배선) |
| was | `v1/admin/AdminCrawlingCostController`·`AdminCrawlingUsageService`·`AdminCrawlingUsage` / `monitoring/BrandReadRepository.findDailyCallCounts`·`BrandLinkRepository.findAllByUser` / `setting/AppSettingRepository.upsert` / `V20260812100500__crawling_unit_price_setting.sql` |

검증: `AdminCrawlingUsageServiceTest`(KST 자정·월초 경계 — 경계 전후 1초 고정 Clock 쌍, 기간 귀속),
`AdminCrawlingUsageIntegrationTest`(실 DB — 인가 401/403, 이력 없음 200+0, 기간 집계, PUT 반영·유효성),
`CountingHikerHttpTest`·`BrandCollectServiceTest`(스윕 콜 1:1 계상·워커 전파·실패 미집계)·
`BrandRegistrationServiceTest`(등록 1콜 계상·replay 0콜).
