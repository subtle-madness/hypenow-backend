> 상태: 🟢 활성 · ⏸ 계획 수립 중 (구현 전)

# ContentAnalysisJob timely/late_backfill 후보 선정 분리 설계

## 배경

`ContentAnalysisJob.run()`(`analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java`
116~148행)은 분석 대상 후보를 한 SQL로 뽑는다. `base` CTE가 미분석·댓글 가드·숙성일을 걸고,
바깥 WHERE가 `timely OR (계정별 최근 N개 윈도우 안)`으로 OR 결합한 뒤 `ORDER BY
metric_captured_at DESC NULLS LAST`로 정렬해 **단일 LIMIT**(`analytics.analyze-batch-limit`,
운영값 2000)을 건다.

두 부류의 성격이 다르다:

- **timely**: 게시 후 pin~pin+slack일 사이에 지표가 잡힌 "일상" 콘텐츠 — 매일 갱신돼야 함.
- **late_backfill**: timely 조건은 못 채웠지만 계정별 최근 12개 윈도우 안에 있는 콘텐츠 — 신규
  계정의 과거 이력 캐치업과 기존 계정의 크롤 밀림을 모두 포함.

문제: 둘이 같은 LIMIT 예산을 공유해서, late_backfill 후보가 갑자기 많아지면(신규 계정 대량 유입
등) 매일 갱신돼야 할 timely 분석이 밀릴 수 있다.

별도의 "초기 백필" 원샷 배치(`GeminiBackfillRunner`, CLI 플래그 트리거, Vertex/Gemini Batch API)가
이미 있지만 이건 상시 잡이 아닌 별개 트랙이다. 상시로 도는 late_backfill 처리는 지금 이 잡 안에만
있다.

## 확정 결정

### 1. 스코프 = late_backfill 전체를 별도 예산·트리거로

late_backfill의 두 원인(신규 계정 캐치업 / 기존 계정 크롤 밀림)을 원인별로 나누지 않는다. 원인
무관하게 late_backfill 전체를 timely와 분리된 잡으로 뗀다 — "계정 생성 이벤트 트리거"는 시스템에
없다(crawler→analytics는 raw DB 읽기뿐, 모듈 간 Java 공유는 분석 결과 계약 모듈 `contract-analysis`
뿐이라 이벤트 push 경로가 없음). 따라서 "트리거"는 실질적으로 **별도 스케줄(cron)**로 구현한다.

### 2. SQL 후보 선정 = 상호 배타적인 두 쿼리로 분리

기존 `base` CTE(미분석·댓글 가드·숙성일)와 `ranked`(계정별 최근 N개 순위), 닫힘 게이트는 그대로
공유한다. 바깥 WHERE만 둘로 나눈다:

```sql
-- timely 쿼리 (기존 run(), 축소)
... WHERE timely
ORDER BY metric_captured_at DESC NULLS LAST, short_code

-- late_backfill 쿼리 (신규 runLateBackfill())
... WHERE NOT timely AND short_code IN (
  SELECT short_code FROM ranked
  WHERE rn <= ? AND posted_at <= now() - make_interval(days => ?)
)
ORDER BY metric_captured_at DESC NULLS LAST, short_code
```

`NOT timely`를 backfill 쪽에 명시한다. 기존 코드는 `timely OR 윈도우`라 어떤 콘텐츠가 timely이면서
동시에 최근창 안에도 있을 수 있었고(그 경우 `timely=true`로 마킹), 이제 그런 콘텐츠는 timely
쿼리가 가져가므로 backfill 쿼리에서 제외해야 한다. 이렇게 하면 두 쿼리가 뽑는 `short_code` 집합이
항상 서로소(disjoint)라:

- 같은 콘텐츠를 두 잡이 동시에 집어 `content_analyses` INSERT가 경합할 일이 없다(두 잡은
  `JobName`이 달라 락도 별개라 이론상 동시 실행 가능하지만 안전).
- `metric_timeliness` 마킹 결과는 기존과 100% 동일 — 원래도 timely가 우선이었으므로 동작 변경
  없음, "어느 잡이 처리하느냐"만 나뉜다.

### 3. LIMIT 완전 제거 (양쪽 다)

`analytics.analyze-batch-limit` 같은 배치 상한을 두 쿼리 어느 쪽에도 걸지 않는다. 실질적인 상한은
이미 LLM 제공자 429(일 한도 소진) 예외가 잡고 있다 — `LlmQuotaExhaustedException` →
`carriedOver`로 자연 이월(`ContentAnalysisJob.run()` 159~163행 기존 로직, 그대로 재사용). LIMIT을
지우면 각 잔은 자격 후보 전량을 돌리다 실제 쿼타를 맞으면 멈추는 구조가 된다. 이 결정으로:

- 예산 배분(얼마씩 나눌지) 고민 자체가 없어진다 — timely는 자격 전량, backfill도 자격 전량을
  각자 처리하고 real quota가 자연스럽게 두 잡 사이의 총량 상한 역할을 한다.
- `analytics.analyze-batch-limit` 키는 유지한다(`CommentClassificationJob`,
  `PipelineStatsService`가 계속 참조) — `ContentAnalysisJob` 내부에서만 참조를 제거한다.
- 신규 app_setting 키·Flyway 마이그레이션이 필요 없다.

### 4. 클래스 구조 = 진입점만 분리, 클래스는 하나

`ContentAnalysisJob`에 기존 `run()`(timely 전용으로 축소) 옆에 `runLateBackfill()`을 추가한다.
기준선 로딩(`accountBaseline`/`withBaseline` 조회)과 `analyzeOne()`은 그대로 공유 — 두 진입점이
SQL만 다르게 돌리고 나머지(기준선 적용, LLM 호출, 저장, 콘텐츠 단위 실패 격리, quota carryover)는
동일 로직을 재사용한다.

별도 클래스로 쪼개는 대안도 검토했으나, 기준선 로딩 코드(~30줄)를 복붙하거나 상위 추상클래스를
새로 만들어야 해서 지금 규모엔 과하다(불필요한 추상화 지양). 진입점 분리로 간다.

## 배선 변경 지점

기존 컨벤션(`JobName`/`ScheduleRunner`/`AnalyticsJobService`/`JobConfig`) 그대로 확장한다:

- `JobName`에 `LATE_BACKFILL_ANALYZE("늦크롤 백필 분석 (LLM)")` 추가 → slug
  `late-backfill-analyze`.
- `ScheduleRunner`에 `@Scheduled(cron = "${analytics.schedule.late-backfill-analyze-cron:-}")
  lateBackfillAnalyze()` 메서드 추가. cron 실값은 기존 패턴대로 리포 밖 운영 env로 설정(리포에는
  `application.yml`에 주석 예시만 추가). 기본 주기는 timely와 동일(매일) — 운영에서 필요하면
  `-cron` 값만 바꿔 조정.
- `AnalyticsJobService.run()` switch에 `case LATE_BACKFILL_ANALYZE ->
  analyzeJob.getObject().runLateBackfill();` 추가. 새 Spring 빈은 만들지 않는다 — 기존
  `ObjectProvider<ContentAnalysisJob>`을 그대로 재사용해서 메서드만 다르게 호출한다.
- `JobConfig`의 `contentAnalysisJob` 빈: 현재 생성자가 `ProgressReporter` 하나만 받아
  `JobName.ANALYZE`에 고정 바인딩돼 있다. 두 진행률(타임리/백필)을 어드민에 각각 보여주려면
  생성자가 `JobProgressRegistry`를 받아 메서드별로 올바른 reporter를 골라 쓰도록 소폭 리팩터한다.
- 어드민 대시보드(`AdminUiController.DASHBOARD_JOBS` 등)에 카드는 노출하되(목록에 추가),
  `scopeLine`/`scopeSubLine` 커스텀 문구는 이번 스코프에서 생략한다(기본 `default -> null`로
  처리 — 러닝 상태·이력 피드는 자동으로 나온다). 커스텀 문구는 후속 과제.

## 테스트

기존 `ContentAnalysisJobTest`(`analytics/src/test/java/com/celfit/analytics/analyze/`)의
timely/late_backfill 마킹 케이스들을 `run()`/`runLateBackfill()` 각각을 호출하도록 재구성한다.
특히 "timely이면서 동시에 최근창 안"인 콘텐츠가 `run()`에서만 나오고 `runLateBackfill()`에서는
나오지 않는 상호 배타성 회귀 테스트를 추가한다.

## 스코프 제외

- 계정 생성 시점을 실시간으로 감지해 백필을 즉발 트리거하는 이벤트 경로는 만들지 않는다(위 결정
  1). 순수 스케줄(cron) 분리로 대체.
- `analytics.analyze-batch-limit` 값 자체의 조정(예: 다른 잡에서의 값)은 이번 스코프 밖.
- `GeminiBackfillRunner`(초기 백필 원샷 Vertex/Gemini Batch 잡)는 무관 — 변경 없음.

## 남은 것 (구현 계획에서)

`JobConfig` 생성자 리팩터 상세, `AdminUiController` 카드 추가 위치, 어드민 UI 텍스트(라벨 등)
확정, 테스트 재구성 목록.
