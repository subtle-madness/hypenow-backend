# analytics — 분석 층

raw DB(crawler)를 읽어 분석 결과를 analysis DB에 내놓는 모듈.
설계: [../docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md](../docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md)

## 구성

- `views/` — raw DB `analytics` 스키마의 뷰. 파일명 번호순 적용.
  - `00_base.sql` — base 뷰 5종. **raw 테이블·payload를 만지는 유일한 SQL.**
  - `01_recent_window.sql` — 계정별 최근 N개 윈도우 (`v_recent_content`)
  - `02_serving.sql` — 서빙 형태 뷰 4종 (`v_accounts`·`v_contents`·`v_content_comments`·`v_content_metric_snapshots`) — 미러 대상과 1:1
  - `03_analysis_baseline.sql` — 콘텐츠별 기준선 뷰 (분석 잡 전용, 미러 안 함)
- `mirror/` — 타입 기반 미러: 뷰 SELECT → 공유 record 매핑 → analysis DB 테이블
  TRUNCATE+INSERT (한 트랜잭션, 컬럼↔record 대조 가드). 대상 등록은 `MirrorConfig`.
  대상: accounts·contents·content_comments·content_metric_snapshots (등록: MirrorConfig).
- `test/` — SQL 하니스. 더미 시드를 BEGIN/ROLLBACK으로 격리해 뷰 기대값을 고정.
- `check/` — 실DB 상태 점검. `coverage.sh`: content-ranking 프론트 화면 요소별로
  analysis DB 미러의 필드 채움율을 보고 (골격 미러가 비면 실패, LLM 분석·랭킹 구간은 보고만).

## 실행

    ./test/run.sh                    # 뷰 적용 + SQL 테스트 전체 (crawler-postgres-1 필요)
    ./test/run.sh test/00_base.test.sql   # 지정 테스트
    ./check/coverage.sh              # 미러 결과 필드 커버리지 보고 (실DB)
    ../gradlew :analytics:test       # Java 테스트 (Docker 필요)
    ../gradlew :analytics:bootRun    # 미러 1회 실행 (analytics.mirror-on-startup=true)

### LLM 인증 (둘 중 하나)

    # 방법 1 — Claude 구독(OAuth): 실행 직전 단기 토큰 발급 (자동화 전 수동 실행용)
    export ANTHROPIC_AUTH_TOKEN=$(ant auth print-credentials --access-token)
    # 방법 2 — API 키 (자동화·운영용)
    export ANTHROPIC_API_KEY=sk-ant-...

둘 다 설정돼 있으면 구독(OAUTH_TOKEN)이 우선한다. 토큰은 단기 만료라 배치 실행 직전에 발급할 것.

    ../gradlew :analytics:bootRun --args='--analytics.classify-on-startup=true'   # 댓글 분류 배치
    ../gradlew :analytics:bootRun --args='--analytics.goldset-path=/path/goldset.csv'  # F-1 스파이크
    ../gradlew :analytics:bootRun --args='--analytics.analyze-on-startup=true'   # 콘텐츠 분석 배치

⚠️ `analyze-on-startup`·`vlm-enabled`는 스프링 프로퍼티(`application.yml`/CLI 인자)이지 `app_setting` 키가 아니다.
분석 대상은 "최근 N개 윈도우 안 + 분류 완료(또는 댓글 0)" 콘텐츠만 (classify 선행을 강제).

## app_setting 런타임 키 (뷰가 직접 읽음)

| 키 | 기본 | 의미 |
|---|---|---|
| `analytics.recent-window` | 12 | 계정 단위 지표의 최근 N개 윈도우 (§4-1) |
| `analytics.llm-model` | claude-opus-4-8 | LLM 호출 모델 (스파이크 결과로 확정) |
| `analytics.analyze-batch-limit` | 10 | 1회 실행당 LLM 분석 콘텐츠 수 상한 (비용 가드) |

⚠️ 값은 **평문 정수만** — 비정수 값이 들어가면 `v_recent_content`와 그 위의 모든 계정 단위
뷰가 캐스팅 에러로 깨진다 (뷰가 `value::int`로 직접 읽음, 앱 레벨 검증 없음).
