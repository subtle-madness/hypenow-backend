> 상태: 🟢 활성 · ✅ 구현됨 (PR 대기 — develop 머지 전)

# ContentAnalysisJob 동시 처리(병렬화) 설계

## 배경

`ContentAnalysisJob`의 timely/late_backfill 분리(2026-07-23,
[specs/2026-07-23-content-analysis-timely-backfill-split-design.md](2026-07-23-content-analysis-timely-backfill-split-design.md))로
LIMIT을 완전히 없앴다. 그런데 운영 배포 직후 `runLateBackfill()`의 크론이 아직 안 걸려 있던 걸
확인하는 과정에서, 실제 밀린 backfill 후보가 26,167건인데 처리 루프가 **완전 순차**(한 건씩 LLM
호출이 끝나야 다음 건으로 넘어감)라는 게 드러났다. 건당 1~2초만 잡아도 26,167건은 7~14시간이
걸린다 — LIMIT을 없애 "예산 배분 고민"은 없앴지만 처리 속도 자체는 여전히 느리다.

Vertex AI(현재 운영 프로바이더)는 RPM 페이싱을 이미 빼뒀다(DSQ 기반이라 고정 한도가 없어 페이싱이
무의미 — `VertexHttpApi` 클래스 주석). 순차 루프가 이 여유를 못 쓰고 있었다.

## 사전 조사 (동시성 안전성)

병렬화 전에 관련 코드가 이미 동시 호출에 안전한지 확인했다:

- **DB 커넥션**: `analysisDataSource`는 `DataSourceBuilder.create().build()`(HikariCP 기본,
  Spring Boot 4). `analyzeOne()`은 DB 커넥션을 LLM 호출(초 단위 지연) 동안 붙잡지 않는다 —
  SELECT·INSERT만 짧게 쓰고 반환하므로 기본 풀 크기(10)로도 병렬도 8 정도는 여유 있다.
- **Vertex/Gemini HTTP 클라이언트**: `VertexHttpApi`·`GeminiContentAnalyzer`는 전부 `final` 필드에
  불변 값만 가지고 있어 스레드 세이프. `java.net.http.HttpClient`·Jackson `ObjectMapper`도 동시
  사용 가능하도록 설계된 컴포넌트.
- **`VertexTokenProvider.get()`**: 이미 `synchronized` — 토큰 갱신 경합 안전.
- **`GeminiHttpApi.pace()`**: 이미 `synchronized`(RPM 페이싱용 `nextAllowedAt` 필드 보호) — 나중에
  provider를 gemini로 되돌려도 병렬 호출이 자동으로 안전하게 감속된다.

→ **`ContentAnalysisJob.runQuery()` 한 곳만 고치면 된다.** 다른 파일은 이미 준비돼 있다.

## 확정 결정

### 1. 병렬도는 app_setting으로 조정 가능

새 키 `analytics.analyze-concurrency`(기본값 8). 운영에서 429 빈도를 보며 재배포 없이 조정할 수
있어야 한다. `AnalyticsSettings.analyzeConcurrency()` 추가, 기존 시드 컨벤션대로 crawler Flyway
마이그레이션에서 `ON CONFLICT DO NOTHING`으로 기준값 등록.

### 2. `run()`/`runLateBackfill()` 둘 다 적용

`runQuery()`가 공유 헬퍼라 어차피 같이 바뀐다. timely도 후보가 많은 날엔 같은 이점을 본다 —
차등 적용할 로직상 이유가 없다.

### 3. 순서는 제출 순서로 유지, 완료 순서만 섞임

대상 목록은 지금처럼 `ORDER BY metric_captured_at DESC`로 조회한 순서 그대로 스레드풀에
`invokeAll`로 제출한다. 고정 크기 스레드풀의 작업 큐는 FIFO라 "최신 수집분부터"(썸네일 서명 URL
생존 우선순위, B3 리뷰 근거) 우선순위는 유지된다 — 어느 게 먼저 *끝나는지*만 동시성 때문에
섞인다.

### 4. 쿼타 소진 처리 — 플래그 기반 조기 중단

기존 순차 로직은 429를 만나면 그 자리에서 `break`하고 나머지를 통째로 이월했다. 병렬에서는 여러
스레드가 거의 동시에 429를 만날 수 있어 그대로 못 쓴다.

`AtomicBoolean quotaExhausted`를 두고:
- 각 작업은 시작 전에 플래그를 확인한다. 이미 세워져 있으면 LLM 호출 없이 바로 반환(스킵) —
  쿼타 소진 후 큐에 남은 작업들이 괜히 추가로 429를 만들며 시간을 낭비하지 않게.
- 어느 스레드든 `LlmQuotaExhaustedException`을 잡으면 플래그를 세운다(멱등 — 여러 스레드가
  동시에 세워도 문제없음, `AtomicBoolean.set(true)`).
- **이미 진행 중이던 호출은 끝까지 완료시킨다** — 강제 취소 안 함. 이유: 콜 자체는 짧고(초 단위),
  중간에 끊으면 DB 쓰기 여부가 애매해지는데 얻는 이득(수 초)이 그 복잡도를 정당화하지 못한다.
- 최종 `carriedOver = quotaExhausted.get()`. 잔여 대상은 `NOT EXISTS` 필터로 다음 실행에서 자연
  재대상되는 기존 이월 시맨틱 그대로.

### 5. 카운터·진행률 — Atomic + 최종 report 1회 추가

`processed`/`failed`를 `AtomicInteger`로 바꾼다. 각 작업 완료 시 `progress.report(...)` 호출은
유지하되, 동시 완료 시 "마지막 호출 = 진짜 최종값"이 보장되지 않으므로 **풀 종료 후 최종 수치로
한 번 더 report를 호출**해 어드민 진행률 UI가 정확한 값으로 끝나게 한다.

### 6. 스레드풀 생명주기

`runQuery()` 호출마다 `Executors.newFixedThreadPool(concurrency)`를 새로 만들고
`try { invokeAll(tasks) } finally { pool.shutdown() }`로 정리한다. 잡 인스턴스 레벨로 풀을
재사용하지 않는다(단순함 우선 — 이 잡은 스케줄 간격이 넓어 풀 생성 비용이 무시할 수준).

## 스코프 제외

- `analysisDataSource`의 Hikari 풀 크기 조정 — 기본값(10)이 권장 병렬도(8)를 충분히 감당해
  이번 범위에서 안 건드림. 병렬도를 크게 올리는 운영 튜닝이 생기면 별도로 검토.
- `loadBaselines()`는 그대로 순차(각 `runQuery()` 호출당 1회, 병렬 구간 진입 전) — 병렬화 대상
  아님.
- `GeminiBackfillRunner`(Batch API 경로)는 무관 — 변경 없음.

## 테스트

`ContentAnalysisJobTest`의 기존 케이스들은 동시성 도입 후에도 최종 결과(저장된 행·개수)가
동일해야 한다 — 처리 순서에 의존하는 테스트가 있다면(`분석_대상은_수집_최신순이다` 등, `insightCalls`
호출 *순서*를 검증) 병렬 실행에서는 완료 순서가 섞일 수 있으므로, 검증 방식을 "포함 여부"나
"제출 순서(큐 투입 순서)" 기준으로 조정하거나 해당 테스트만 동시성 1(직렬)로 고정해 순서 검증을
유지한다. 신규: 쿼타 소진 시 진행 중이던 작업은 완료되고 이후 작업은 스킵됨을 검증하는 테스트.

## 남은 것 (구현 계획에서)

정확한 `Callable`/`invokeAll` 코드, `AnalyticsSettings` getter 시그니처, 테스트별 동시성 처리
방식(순서 의존 테스트 조정 목록), Flyway 마이그레이션 번호.
