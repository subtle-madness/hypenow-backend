# analytics — 분석 층

raw DB(crawler)를 읽어 분석 결과를 analysis DB에 내놓는 모듈.
설계: [../docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md](../docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md)

## 구성

- `views/` — raw DB `analytics` 스키마의 뷰. 파일명 번호순 적용. 2026-07-18 신 crawler
  스키마(V15 인플루언서 개편) 기준으로 전면 재구축 — 릴스 캡션·지표는 `raw_media_page`의
  HIKER_V2_CLIPS jsonb, 피드 캡션·지표는 `raw_profile`(SELF_GQL) 내장 타임라인이 소스다
  (`raw_post_detail`은 구 파이프라인 유물이라 신 뷰에서 미사용). 서빙 모수는 뷰티
  인플루언서(QUALIFIED ∧ beauty ∧ ¬beauty_company)로 필터.
  - `00_base.sql` — base 뷰 9종(`v_base_influencer`·`v_base_profile`·`v_base_reel_item`·
    `v_base_timeline_item`·`v_base_content`·`v_base_content_snapshot`·`v_base_detail`·
    `v_base_comment`·`v_base_crawl_run`). **raw 테이블·payload를 만지는 유일한 SQL.**
  - `01_recent_window.sql` — 계정별 최근 N개 윈도우 (`v_recent_content`)
  - `02_serving.sql` — 서빙 형태 뷰 (`v_serving_content`·`v_accounts`·`v_contents`·
    `v_content_comments`·`v_content_metric_snapshots`) — 미러 대상과 1:1.
    단 `v_content_metric_snapshots`는 07-30 미러 중단(소비자 부재) — 뷰만 존속하고 미러는 안 한다.
  - `03_analysis_baseline.sql` — 콘텐츠별 기준선 뷰 (`v_analysis_baseline`, 분석 잡 전용, 미러 안 함)
  - `04_analysis_candidates.sql` — LLM 캡션 선분석 후보 뷰 (`v_analysis_candidates`, 숙성 가드 3일·캡션 필수, 미러 안 함)
  - `10_account_detail.sql` — 계정 상세 뷰 3종 (`v_account_recent`·`v_account_summaries`·
    `v_account_content_series`). 카테고리 믹스는 07-21에 analysis DB 파생 뷰
    `account_category_stats`(V35)로 이관 — 소스인 캡션 분류가 analysis DB라 여기선 못 만든다.
  - `20_landing_stats.sql` — 랜딩 통계 뷰 (`v_landing_stats`)
  - `30_crawl_cost.sql` — 크롤러 파이프라인 유료 요청 일별 집계 (`v_crawl_call_daily`) —
    `crawl_run.request_count > 0`인 실행을 잡·KST 달력일로 접는다. was 어드민 전역 크롤링 비용
    API가 미러를 읽는다([설계](../docs/superpowers/specs/archive/2026-08-13-admin-global-crawling-cost-design.md)).
- `mirror/` — 타입 기반 미러: 뷰 SELECT → 공유 record 매핑 → analysis DB 테이블
  TRUNCATE+INSERT (한 트랜잭션, 컬럼↔record 대조 가드). 대상 등록은 `MirrorConfig`.
  대상: accounts·contents·content_comments·account_summaries·account_content_series·
  landing_stats·crawl_call_daily (등록: MirrorConfig).
  `content_metric_snapshots`는 07-30 제외 — 소비자(was 레거시 `/api`)가 사라졌는데 약 70만 행으로
  미러 12분 30초 중 6~7분을 차지했다. 지표 이력은 raw의 `analytics.v_content_metric_snapshots`로 직접 조회.
- `test/` — SQL 하니스. 더미 시드를 BEGIN/ROLLBACK으로 격리해 뷰 기대값을 고정.
- `check/` — 실DB 상태 점검. `coverage.sh`: celfit-front 실소비 /v1 필드별로
  analysis DB 미러의 채움율을 보고 (골격 미러가 비면 실패, LLM 분석·랭킹 구간은 보고만).
  매트릭스 정의는 어드민 `/ui/coverage` 페이지(`CoverageRepository`)와 쌍.
- `export/` — `front_seed.py`: analysis DB(분석 완료분)를 celfit-front 실데이터셋
  3종(dataset/deep-dives/account-reports)으로 변환해 프론트 시드를 덮어씀 —
  프론트 실제 뷰(로컬 `pnpm dev`, 인메모리 모드)로 데이터 확인하는 데모용.

## 실행

    ./test/run.sh                    # 뷰 적용 + SQL 테스트 전체 (실데이터 postgres 컨테이너 필요 — 기본 crawler-postgres-1, PG_CONTAINER로 오버라이드)
    ./test/run.sh test/00_base.test.sql   # 지정 테스트
    ./check/coverage.sh              # 미러 결과 필드 커버리지 보고 (실DB)
    python3 export/front_seed.py [celfit-front 경로]   # 프론트 실뷰 데모 시드 생성
    ../gradlew :analytics:test       # Java 테스트 (Docker 필요)
    ../gradlew :analytics:bootRun    # 상주 서버 기동 (8082) — one-shot 배치는 아래처럼 web-application-type=none 오버라이드
    ../gradlew :analytics:bootRun --args='--analytics.mirror-on-startup=true --spring.main.web-application-type=none'   # 미러 1회 실행

### LLM 인증 (둘 중 하나)

    # 방법 1 — Claude 구독(OAuth): 실행 직전 단기 토큰 발급 (자동화 전 수동 실행용)
    export ANTHROPIC_AUTH_TOKEN=$(ant auth print-credentials --access-token)
    # 방법 2 — API 키 (자동화·운영용)
    export ANTHROPIC_API_KEY=sk-ant-...

둘 다 설정돼 있으면 구독(OAUTH_TOKEN)이 우선한다. 토큰은 단기 만료라 배치 실행 직전에 발급할 것.
(위 Claude 인증은 `provider=anthropic` 롤백 경로에서만 쓴다 — 기본은 아래 vertex/gemini.)

### LLM 프로바이더 (vertex / gemini / anthropic)

프로바이더는 `app_setting.analytics.llm-provider`로 고른다 — baseline은 **`vertex`**(V16이 gemini 시드 → V17이 vertex로 승격).
- **vertex**(기본): Vertex AI로 동기·배치 호출. GCP 서비스계정(SA) 키 필요, `$300` 크레딧 사용.
- **gemini**: AI Studio 무료 키(`GEMINI_API_KEY`).
- **anthropic**: Claude 롤백 경로. 모델은 `analytics.llm-model`(폴백 기본 haiku).

**그레이스풀 폴백**: `provider=vertex`라도 `GOOGLE_APPLICATION_CREDENTIALS`가 없으면 자동으로
무료 gemini로 폴백한다(`LlmConfig.useVertex`, 로그 경고). 그래서 SA 키가 없는 로컬은 baseline이
vertex여도 죽지 않고 gemini로 돈다. 프로바이더는 빈 생성 시점에 읽으므로 **전환은 재기동 필요**.

**로컬에서 Vertex로 돌리려면** (SA 키가 있을 때):

    # 1) GCP 프로젝트 hypenow-llm-prod SA 키 JSON을 로컬에 둔다 (커밋 금지)
    export GOOGLE_APPLICATION_CREDENTIALS=~/.gcp/hypenow-vertex-sa.json   # .env 자동로드 안 됨 — 셸 export
    # 2) 로컬 crawler DB에 provider=vertex 확인 (V16→V17 마이그레이션이 적용됐으면 이미 vertex)
    docker exec crawler-postgres-1 psql -U crawler -d crawler -c \
      "INSERT INTO app_setting(key,value) VALUES('analytics.llm-provider','vertex') \
       ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;"
    # vertex-project(hypenow-llm-prod)·gcs-bucket은 V16이 시드, location은 기본 global.
    # 3) analytics 재기동 → 로그에 vertex 폴백 경고가 없으면 Vertex로 도는 것.

아래 one-shot 배치는 전부 `--spring.main.web-application-type=none`을 붙인다
(기본 프로파일이 상주 서버(8082)로 바뀌어, 없으면 배치 후 서버가 종료되지 않고 상주한다 — cloud 프로파일만 기본 one-shot):

    ../gradlew :analytics:bootRun --args='--analytics.classify-on-startup=true --spring.main.web-application-type=none'   # 댓글 분류 배치
    ../gradlew :analytics:bootRun --args='--analytics.goldset-path=/path/goldset.csv --spring.main.web-application-type=none'  # F-1 스파이크
    ../gradlew :analytics:bootRun --args='--analytics.analyze-on-startup=true --spring.main.web-application-type=none'   # 콘텐츠 분석 배치 (VLM off)
    ../gradlew :analytics:bootRun --args='--analytics.analyze-on-startup=true --analytics.vlm-enabled=true --spring.main.web-application-type=none'  # +VLM
    ../gradlew :analytics:bootRun --args='--analytics.vlm-spike-limit=8 --spring.main.web-application-type=none'         # F-2 VLM 스파이크

⚠️ `analyze-on-startup`·`vlm-enabled`는 스프링 프로퍼티(`application.yml`/CLI 인자)이지 `app_setting` 키가 아니다.
분석 대상은 "최근 N개 윈도우 안 + 분류 완료(또는 댓글 0)" 콘텐츠만 (classify 선행을 강제).

### VLM (썸네일 분석) 주의

- **썸네일 서명 URL은 수집 후 ~4일이면 만료**(403 — 2026-07-14 실측). 분석 잡은 수집 최신순으로
  돌며 호출 전 HEAD 프리체크로 만료 썸네일은 VLM만 스킵(컬럼 NULL)한다 — **VLM 데이터를 채우려면
  크롤링 후 며칠 안에 분석 배치를 돌려야 한다.**
- 이미지는 직접 내려받아 base64로 입력한다 — URL 입력은 Anthropic이 인스타 CDN을 robots.txt
  사유로 거부(F-2 실측).
- 분류 어휘(대분류 slug·중분류/소분류 한글 라벨·유통사 올리브영/다이소)는 celfit-front 배포본과의
  계약 — `BeautyTaxonomy`가 단일 원천이고 프론트 필터 어휘가 바뀌면 함께 갱신한다.
- 비용 실측: VLM 건당 ≈ $0.03~0.05 (opus 4.8, input 3~5.5k/output 0.5~0.9k tok).

## app_setting 런타임 키 (뷰·함수가 직접 읽음)

| 키 | 기본 | 의미 |
|---|---|---|
| `analytics.recent-window` | 12 | 계정 단위 지표의 최근 N개 윈도우 (§4-1) |
| `analytics.metric-pin-days` | 3 | 서빙 지표(v_contents) 고정 시점 — 업로드 +N일 이후 가장 이른 스냅샷 |
| `analytics.hype-fresh-halflife-days` | 14 | hype_score 신선도 반감기(일) — 클수록 감쇠 완화. 미설정·0이면 함수가 14 적용 |
| `analytics.hype-reels-e0` | 0.02 | hype_score v2 릴스 참여 로그 스케일 상수 (`ln(1+참여율/e0)`) |
| `analytics.hype-feed-f0` | 0.03 | hype_score v2 피드 참여 로그 스케일 상수 (`ln(1+참여율/f0)`) |
| `analytics.hype-reach-weight` | 1 | hype_score v2 릴스 도달 축 가중 (`Q = wr·reach + we·engage`) |
| `analytics.hype-engage-weight` | 1 | hype_score v2 릴스 참여 축 가중 |
| `analytics.hype-anchor-{reels,feed}-{p05,p50,p90,p99}` | (아래) | hype_score v2 0~100 매핑 앵커 — qf 값 → 10·45·80·97. 모집단 이동 시 재보정(스펙 §6). 기본: 릴스 0.2405/0.9091/1.8845/2.9835, 피드 0.0111/0.1398/0.6746/1.4890 |
| `analytics.analyze-maturity-days` | 3 | LLM 후보(v_analysis_candidates) 숙성 가드 — 업로드 +N일 경과분만 |
| `analytics.analyze-timely-slack-days` | 2 | LLM 후보 제때 크롤 판정 여유 — 고정 지표가 업로드 +(pin+N)일 안에 잡힌 것만 (늦크롤 백필 제외) |
| `analytics.trend-threshold` | 0.15 | 계정 트렌드 up/down 판정 임계(v_account_summaries) — 이 키만 numeric |
| `analytics.llm-model` | claude-haiku-4-5-20251001 | anthropic 프로바이더 호출 모델 — 폴백 기본은 비용 가드로 haiku. 상위 모델은 이 키로 명시 전환 |
| `analytics.analyze-batch-limit` | 10 | 1회 실행당 LLM 분석 콘텐츠 수 상한 (비용 가드) |

⚠️ 값은 **평문 숫자만** — 잘못된 값이 들어가면 해당 뷰와 그 위의 모든 뷰가 캐스팅 에러로
깨진다 (뷰가 `value::int`/`value::numeric`으로 직접 읽음, 앱 레벨 검증 없음).
