# celfit crawler — 인스타그램 콘텐츠 raw 수집 시스템 설계

2026-07-07 확정. celfit 피벗 후 신규 시스템 — **기존 인플루언서 파이프라인(legacy-extension·backend·tools)과 완전히 독립**이며 코드·스키마를 공유하지 않는다.

## 1. 개요 · 스코프

카테고리 키워드로 인스타그램 콘텐츠(릴스/피드)를 발굴하고, 업로드 3일 후 게시물 상세·댓글·작성자 프로필을 **Apify 응답 원형(raw) 그대로** 수집·적재하는 시스템. 관리 UI 포함.

**이번 MVP에 포함:**
- 발굴(discover) → 자격판정(qualify) → 확정수집(aggregate) 3단계 수집 파이프라인
- raw 적재 (Apify 아이템 verbatim, 액터별 테이블)
- 카테고리·키워드·수집 규칙(팔로워 수 등)의 DB 관리
- 관리 UI: 잡 실행/모니터링, 카테고리·규칙 관리, 수집 데이터 열람
- 수동 트리거(초기 운영) + 스케줄 실행(검증 후 활성화)

**포함하지 않음 (다음 단계):**
- 분석·정규화 파이프라인, LLM 분류(카테고리 태깅·광고 판별·브랜드/제품 추출)
- 스코어링·랭킹 계산·랭킹 화면

**파일럿 범위:** 카테고리 1–2개(예: 메이크업)로 시작. 카테고리·키워드는 UI에서 추가.

## 2. 기술 스택 · 프로젝트

- Java 21 · Spring Boot 단일 앱 · Gradle. 패키지 루트 **`com.celfit.crawler`**.
- 위치: `~/Desktop/Project/celfit/crawler` (새 git repo). 옛 크롬 확장 repo는 `celfit/legacy-extension`으로 이동(보존).
- DB: PostgreSQL — `spring-boot-docker-compose`가 `compose.yaml`의 컨테이너 자동 기동. 스키마는 Flyway 마이그레이션.
- 수집 API: **Apify** 인스타그램 스크레이퍼 액터. (인스타 공식 Graph API는 본인 소유 계정 콘텐츠만 조회 가능해 키워드 수집에 부적합)

| 용도 | 액터 |
|---|---|
| 발굴 | `apify~instagram-hashtag-scraper` (한글 키워드는 `keywordSearch: true`) |
| 게시물 상세 | `apify~instagram-post-scraper` |
| 댓글 | `apify~instagram-comment-scraper` |
| 프로필 | `apify~instagram-profile-scraper` |

## 3. 실행 모델

**잡과 트리거를 분리한다.** 각 단계는 독립 실행 가능한 잡이고, 트리거는 두 가지:

1. **수동** — 관리 UI 버튼 / `POST /admin/jobs/{job}`. 초기 DB 구축(백필)·디버깅용.
2. **스케줄** — `@Scheduled`가 같은 잡을 매일 호출. 설정으로 on/off. **초기값 off** — 수동 운영으로 파이프라인 검증 후 켠다.

- 잡은 멱등: 두 번 돌려도 중복 등록/중복 마킹 없음 (자연 키 unique + 상태 컬럼).
- 같은 잡의 동시 실행은 인프로세스 락으로 차단(단일 인스턴스 전제). 실행 중이면 409.
- 실행 1회 = `crawl_run` 기록 (Apify run id, 건수, 실패 사유).

## 4. 데이터 모델

세 그룹: **제어 인덱스**(파이프라인 구동용) · **규칙**(UI 편집) · **raw**(분석의 원천, 무가공).

```
category ──< category_keyword                account ──< raw_profile
    │                                            │
    └── collection_rule (1:1)                    │
                                                 │
crawl_run ──< (각 raw 테이블)     content(owner_username→account)
                                     ├──< raw_discovery_post
                                     ├──< raw_post_detail
                                     └──< raw_comment
```

### 4.1 제어 인덱스 (분석용 아님 — 잡 구동에 필요한 최소한)

**`content`**
| 컬럼 | 설명 |
|---|---|
| `short_code` | 자연 키, unique — 재발굴 중복 방지 |
| `content_type` | `REELS` / `FEED` (발굴 응답의 productType으로 판정: clips→REELS) |
| `owner_username` | 작성자 (account·프로필 수집 연결고리) |
| `uploaded_at` | **+3일 도래 판정의 유일한 근거** (발굴 응답에서 추출) |
| `category_id` | 어느 카테고리 수집에서 등록됐나 |
| `discovery_keyword` | 어느 키워드에서 처음 발견됐나 (추적용) |
| `status` | `PENDING`(발굴됨) → `QUALIFIED`(규칙 통과) / `EXCLUDED`(규칙 탈락) → `AGGREGATED`(확정수집 완료) / `GONE`(삭제·비공개) / `FAILED`(재시도 소진) |
| `first_seen_at`, `qualified_at`, `aggregated_at` | 단계 타임스탬프 |
| `aggregate_attempts` | 재시도 카운터 (상한 초과 시 FAILED) |

**`account`**
| 컬럼 | 설명 |
|---|---|
| `username` | 자연 키, unique |
| `last_profiled_at` | 프로필 재수집 중복 방지 (같은 계정이 여러 콘텐츠에 걸려도 1회만 수집) |

**`crawl_run`**
| 컬럼 | 설명 |
|---|---|
| `job` | `DISCOVER` / `QUALIFY` / `AGGREGATE` |
| `trigger` | `MANUAL` / `SCHEDULED` |
| `category_id`, `keyword` | 발굴 실행의 입력 |
| `actor_id`, `apify_run_id` | Apify 추적 (콘솔에서 과금·로그 확인) |
| `status`, `item_count`, `error_message`, `started_at`, `finished_at` | 실행 결과 |

### 4.2 규칙 (UI에서 편집, 잡이 읽어 적용)

**`category`** — `name`(예: 메이크업), `enabled`
**`category_keyword`** — `category_id`, `keyword`, `enabled` (키워드 추가·중지)
**`collection_rule`** — category 1:1. **타입드 컬럼** 방식: 규칙이 늘면 마이그레이션으로 컬럼 추가. (generic rule_type+jsonb보다 검증·UI 폼·적용 로직이 단순)

| 컬럼 | 적용 시점 |
|---|---|
| `min_followers`, `max_followers` | qualify (프로필 수집 후) |
| `content_types` | discover (REELS/FEED/ALL) |

### 4.3 raw (Apify 액터 1개 = 테이블 1개, payload 무가공)

공통 골격: `payload`(jsonb — Apify 아이템 1건 verbatim) + FK + `crawl_run_id` + `captured_at`.

| 테이블 | 담당 액터 | 아이템 단위 | FK |
|---|---|---|---|
| `raw_discovery_post` | hashtag/keyword | 발굴된 게시물 1건 | `content_id` |
| `raw_post_detail` | post | 게시물 상세 1건 (+3일 확정 지표 포함) | `content_id` |
| `raw_comment` | comment | 댓글 1개 | `content_id` NOT NULL |
| `raw_profile` | profile | 계정 1건 (팔로워 수 포함) | `account_id` NOT NULL |

**조회 편의는 Postgres `GENERATED ALWAYS AS ... STORED` 컬럼으로** — 저장 원본은 payload 하나뿐이고 자주 보는 필드를 DB가 자동 파생. 적재 코드는 "payload 통째 insert"만 한다.

```sql
CREATE TABLE raw_comment (
    id           bigserial PRIMARY KEY,
    content_id   bigint NOT NULL REFERENCES content(id),
    crawl_run_id bigint NOT NULL REFERENCES crawl_run(id),
    payload      jsonb  NOT NULL,
    captured_at  timestamptz NOT NULL,
    writer     text GENERATED ALWAYS AS (payload->>'ownerUsername') STORED,
    text       text GENERATED ALWAYS AS (payload->>'text') STORED,
    written_at text GENERATED ALWAYS AS (payload->>'timestamp') STORED
);
```

(다른 raw 테이블도 동일 패턴 — post는 조회수·좋아요·댓글수·캡션, profile은 팔로워 수 등. Apify가 필드명을 바꾸면 generated column 정의만 마이그레이션으로 수정.)

동일 콘텐츠 재수집 시 raw 행이 추가로 쌓이는 것은 **의도된 이력** — 분석 시 `captured_at` 최신본 사용.

## 5. 수집 흐름 (잡 3개)

```
[discover] → [qualify] → [aggregate]
 발굴·등록     프로필+규칙    +3일 상세·댓글
```

### Apify 실행 공통 규칙

- **비동기 실행만**: run 시작 → 상태 폴링(설정 간격) → `SUCCEEDED` → dataset 수신. 타임아웃 시 abort. run-sync 엔드포인트는 장시간 실행에서 게이트웨이가 먼저 끊겨 **과금+결과 유실** 위험 → 금지.
- 액터 실행 1회 = `crawl_run` 1행.
- 한글 키워드: Instagram 비로그인 해시태그 페이지가 한글 태그를 차단 → 비ASCII 키워드면 `keywordSearch: true`로 자동 전환.
- `APIFY_TOKEN` 없으면 기동 즉시 실패.

### [discover]

```
입력: 카테고리 (enabled 키워드 목록을 DB에서 로드)
키워드마다 hashtag 액터 실행:
  { hashtags:[kw], resultsType:"posts", resultsLimit:설정, keywordSearch:한글여부 }
아이템마다:
  1. shortCode·content_type·uploaded_at 추출
  2. 규칙 중 discover 시점 적용분 체크 (content_types) —
     불일치 아이템은 여기서 완전히 skip (content 등록·raw 저장 모두 안 함)
  3. content upsert (short_code unique — 기존이면 신규 등록 skip)
  4. raw_discovery_post insert (중복 발굴도 항상 저장 — 발견 이력)
  5. account upsert (username만)
```

### [qualify]

```
대상: content WHERE status='PENDING'
  1. owner 중 last_profiled_at 없는 계정 → profile 액터 배치 실행 → raw_profile
  2. 규칙 적용 (팔로워 범위 등, raw_profile 최신본 기준)
     통과 → status='QUALIFIED' / 탈락 → status='EXCLUDED'
```

- 탈락 콘텐츠에는 aggregate 크레딧을 쓰지 않는다 — 규칙이 비용 필터.
- 규칙 변경 후 재판정(re-qualify)은 raw_profile 재사용 → Apify 재호출 없음.

### [aggregate]

```
대상: content WHERE status='QUALIFIED'
             AND aggregated_at IS NULL
             AND uploaded_at <= now() - delay_days(3)
      (배치 상한 설정)
액터 2종:
  1. post 액터 ← 게시물 URL 청크 → raw_post_detail
  2. comment 액터 ← 게시물당 최대 N개(설정) → raw_comment
콘텐츠별 마무리:
  - 둘 다 적재 성공 → status='AGGREGATED', aggregated_at=now()
  - 응답에 없는 shortcode → 삭제·비공개 간주 → status='GONE'
  - 일부 실패 → 미마킹 + aggregate_attempts++ → 다음 실행 때 자동 재시도
  - attempts > max_attempts → status='FAILED' (무한 재시도 방지)
```

### 에러 처리

- 액터 실패/타임아웃 → `crawl_run.status='FAILED'` + error_message. 해당 배치는 미마킹 → 재시도 경로.
- 부분 응답(요청 100건 중 80건) → 받은 것만 적재, 빠진 건 재시도 대상 유지.

## 6. 관리 UI

**Thymeleaf + htmx 서버 렌더링** (Spring 모놀리스 내장 — 별도 프론트 빌드·배포 없음).

| 화면 | 내용 |
|---|---|
| 대시보드 | status별 카운트, 오늘 aggregate 예정 건수, 최근 crawl_run |
| 잡 실행 | discover/qualify/aggregate 실행 버튼(카테고리 선택), 실행 이력·실패 사유 |
| 카테고리·규칙 | 카테고리/키워드 추가·중지, 팔로워 범위 등 규칙 편집 |
| 데이터 열람 | 콘텐츠 테이블(썸네일·계정·상태·수집 시각) → 상세: 댓글·프로필·raw JSON 뷰 |

REST(관리 API, UI와 공용):
- `POST /admin/jobs/{discover|qualify|aggregate}` — 202 수락 (실행 중이면 409). 잡 1회가 액터를 여러 번 돌리므로 crawl_run은 여러 행 생길 수 있음 — 이력은 `GET /admin/runs`로 확인
- `GET /admin/runs`, `GET /admin/status`
- 카테고리·키워드·규칙 CRUD

raw 데이터의 자유 질의는 API로 만들지 않는다 — psql/DBeaver 직접 (generated column으로 일반 테이블처럼 조회 가능).

## 7. 프로젝트 구조 · 설정

```
crawler/
├─ src/main/java/com/celfit/crawler/
│  ├─ apify/    ApifyClient(비동기 run→폴링→dataset·abort), Actors, ActorInputs
│  ├─ job/      DiscoverJob, QualifyJob, AggregateJob, JobLock, ScheduleRunner
│  ├─ domain/   엔티티(제어·규칙·raw) + Spring Data JPA 리포지토리
│  ├─ admin/    잡 트리거·운영 조회·규칙 CRUD 컨트롤러
│  ├─ ui/       Thymeleaf 컨트롤러 + templates/
│  └─ config/   ApifyProperties, ScheduleProperties
├─ src/main/resources/application.yml
├─ src/main/resources/db/migration/   ← Flyway (generated column DDL 포함)
├─ compose.yaml                        ← Postgres
└─ src/test/java/...
```

```yaml
crawler:
  apify:
    token: ${APIFY_TOKEN}
    poll-interval: 5s
    run-timeout: 10m
  discover:
    results-limit: 100        # 키워드당 발굴 상한
  aggregate:
    delay-days: 3             # "+3일" 규칙
    batch-limit: 200
    comments-per-post: 50
    max-attempts: 3
  schedule:
    enabled: false            # 초기 수동 운영
    discover-cron: "0 0 6 * * *"
    qualify-cron: "0 30 6 * * *"
    aggregate-cron: "0 0 7 * * *"
```

(카테고리·키워드·규칙은 yml이 아니라 DB — UI에서 편집.)

## 8. 테스트 전략

- **단위**: Apify 호출부를 인터페이스로 격리, fake로 잡 로직 검증 — upsert 멱등성, +3일 대상 선정, 규칙 적용(통과/탈락), 재시도 카운터→FAILED, GONE 판정, 한글 키워드→keywordSearch 전환.
- **ApifyClient**: HTTP 스텁으로 run 시작→폴링→dataset 수신·타임아웃 시 abort 검증.
- **DB 통합**: Testcontainers Postgres 소수 — jsonb·generated column은 H2 검증 불가.
- **스모크**: 실 토큰, 키워드 1개·limit 5 소량 실행 수동 절차 (과금 → CI 제외, README 기록).

## 9. 이번 결정의 근거 (요약)

- **raw verbatim + 액터별 테이블**: 분석 스키마를 지금 확정하지 않기 위해. 액터별 분리는 FK NOT NULL 무결성·액터별 인덱스/파생컬럼의 자연스러운 자리 확보.
- **generated column**: "스키마는 API 그대로" 원칙과 조회 편의의 양립.
- **qualify 단계 분리**: 팔로워 규칙은 발굴 응답만으로 판정 불가(팔로워 수 없음) → 프로필 수집을 앞당기고, 탈락분에 상세·댓글 크레딧을 아낀다.
- **규칙 = 타입드 컬럼**: 규칙 증가가 예정돼 있어도 generic 설계보다 단순성이 우선.
- **모놀리스 + 서버렌더 UI**: 파일럿 규모에 프로세스 1개. 패키지 경계(apify/job/domain/admin/ui)를 지켜 추후 분리 여지 확보.
