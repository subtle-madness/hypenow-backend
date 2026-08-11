# 야간 콘텐츠 분석 — Vertex 배치 전송 전환 설계

> 상태: ✅ 구현됨 (2026-08-11)

## 0. 한 줄 요약

야간 콘텐츠 분석(ANALYZE·LATE_BACKFILL_ANALYZE)이 온라인 동기 LLM 콜 대신 Vertex 배치 API로
제출할 수 있게 한다 — 배치는 온라인 대비 50% 할인. 전송 방식은 app_setting 토글
(`analytics.analyze-transport`)로 즉시 전환·롤백 가능하고, 기본값은 기존 동작(online)을
그대로 유지한다.

## 1. 배경

- Vertex AI 배치 예측은 온라인 대비 절반 가격이다. 현재 무료 크레딧이 9월 중순 소진 예상이라,
  소진 후 유료 과금이 시작되면 월 비용이 약 25만원 → 12.5만원으로 절감된다.
- 배치 인프라 자체는 이미 있다 — `GeminiBackfillRunner`(초기 백필 one-shot)가 JSONL 제출·
  GCS 업로드·결과 다운로드·파싱을 전부 구현해 뒀다. 이번 작업은 그 경로를 **상시 야간 잡**으로
  확장하는 것이지 새 배치 클라이언트를 만드는 게 아니다.
- 범위는 **콘텐츠 분석만**(ANALYZE·LATE_BACKFILL_ANALYZE) — 계정 카피 분석(ACCOUNT_ANALYZE)은
  대상 밖이다. 콘텐츠 분석이 물량·비용의 대부분을 차지하고, 계정 카피는 별도 트랙으로 미룬다.

## 2. 설계

### 2-1. 전송 토글

app_setting 키 `analytics.analyze-transport` — `online`(기본) | `batch`.
`AnalyticsSettings.analyzeTransport()`/`batchTransportEnabled()`가 잡 실행 시점마다 매번 읽는다
(캐시 없음 — 재기동 불필요). 콘텐츠 분석에만 적용, 계정 카피 로직은 무변경.

### 2-2. 배치 제출 상태 테이블

`content_batch_jobs`(analysis DB, V20260811021726) — 잡이 배치를 제출할 때마다 pending 행을
남기고, 수거 잡이 상태를 확인해 collected|failed로 전이시킨다.

```
id, batch_name, timely, submitted_count, status(pending|collected|failed),
submitted_at, collected_at, note
```

### 2-3. 제출 경로

`ContentAnalysisJob.run()`/`runLateBackfill()`이 `runQuery()`에서 transport=batch면
`submitBatch()`로, 아니면 기존 `runOnline()`으로 분기한다. 후보 선정 + 3종 제외 게이트
(이미 분석됨·댓글 미분류·미러 미도달)는 `resolveTargets()`로 추출해 온라인·배치 양쪽이
동일 코드를 공유한다 — 대상 집합이 전송 방식에 따라 갈릴 여지가 없다.

JSONL 라인 조립(requestLine/sidecarLine)은 `GeminiBatchLines`로 추출해 `GeminiBackfillRunner`와
공유한다. 배치 제출도 백필과 동일하게 **캡션 단독**(썸네일 미첨부) — 익일 수거 시점엔 서명
URL이 대부분 만료돼 있어 애초에 시도하지 않는다.

댓글 분류 분포(`ai_category`별 건수)는 온라인 경로(`analyzeOne`)와 동일하게 shortCode별로 조회해
JSONL 요청에 싣는다 — 후보 게이트가 댓글 분류 완료를 보장하므로 여기 도달한 대상은 빈 분포가
나올 수 없는 구조다. `GeminiBackfillRunner`(일회성 초기 백필)는 이 분포를 조회하지 않는 기존
계약을 그대로 유지한다(`GeminiBatchLines.requestLine`에 명시적으로 빈 맵을 넘긴다).

제출 성공 시 `content_batch_jobs`에 pending 행 기록. 후보 0건이면 제출 생략.

**배치 미지원 프로바이더 폴백**: `GeminiApi` 빈이 `GeminiBatchApi`를 구현하지 않으면(무료 gemini
폴백 상태 등) 배치 제출이 불가능하다 — 이 경우 경고 로그만 남기고 온라인 경로로 내려가 잡이
죽지 않는다(`JobConfig.batchApiOrNull()`). provider=anthropic(롤백 경로)이면 GeminiApi 빈 자체를
건드리지 않아 GEMINI_API_KEY 부재로 인한 불필요한 예외를 막는다.

**VLM 게이트 제약**: 배치 JSONL은 캡션 전용이라 `vlm-enabled=true`(썸네일 첨부 게이트)와 양립하지
않는다 — transport=batch인데 vlm-enabled=true면 경고 로그를 남기고 온라인 경로로 폴백해 멀티모달
분석을 보존한다(운영은 현재 vlm-enabled=false라 당장은 무해하지만, 나중에 켜져도 배치가 조용히
이미지 없이 분석하는 일이 없도록 하는 안전장치).

**제출 전 pending 수거**: 제출 경로 시작 시 pending 잔여가 있으면 먼저 수거를 시도한다(전날
미수거분 회수 — 이미 분석됨 diff + `content_analyses` INSERT의 `ON CONFLICT DO NOTHING`이
이중 안전장치라 설령 겹쳐도 무해).

### 2-4. 수거 경로

신규 `ContentBatchCollectJob` — `content_batch_jobs`의 pending 행을 순회해:

- **SUCCEEDED**: 결과 다운로드 스트리밍 + `GeminiContentAnalyzer.parse` + `ContentAnalysisWriter.insert`
  (`conflictIgnore=true`, 사이드카의 timely 마킹 승계) → status=collected·collected_at 기록.
- **FAILED/CANCELLED/EXPIRED**: status=failed·note에 사유 기록. **재시도하지 않는다** — 해당
  건들은 다음날 후보 diff(이미 분석됨 제외 게이트)에 여전히 걸리지 않아 자연히 재대상되므로
  별도 재시도 로직이 불필요하다.
- **실행 중**: no-op, 다음 수거 사이클에서 재확인.

신규 `JobName.BATCH_COLLECT` + `AnalyticsJobService` 등록 + `ScheduleRunner.batchCollect()`
(환경변수 `ANALYTICS_SCHEDULE_BATCH_COLLECT_CRON`, 미설정 시 비활성 — 기존 크론과 동일 패턴).

사이드카(제출 시 실은 기준선 스냅샷)는 배치별 파일(`BatchSidecarStore`, 배치 이름을 안전한
파일명으로 치환)로 저장한다 — 백필 러너의 고정 파일명과 달리 하루에도 timely·late_backfill
두 배치가 동시에 pending일 수 있어서다.

수거는 멱등이다: pending 행만 대상이라 이미 collected/failed로 전이된 배치는 재처리하지 않는다.

### 2-5. deploy 배선

`deploy/compose.yaml`에 `ANALYTICS_SCHEDULE_BATCH_COLLECT_CRON: "0 10,40 20-23,0-2 * * *"` 추가
(KST 05:10~11:40, 30분 간격 — UTC로 20-23시·0-2시). transport 자체는 app_setting 수동 UPDATE로
전환하므로 compose에는 넣지 않는다.

## 3. 공유 리팩터링

`GeminiBackfillRunner`의 인스턴스 메서드였던 requestLine/sidecarLine·결과 파싱 로직을
`GeminiBatchLines`(정적 유틸)로 추출 — 백필 러너의 기존 동작·테스트는 변경 없음(위임만).
상시 배치 제출·수거 경로가 같은 유틸을 재사용해, 요청 스키마·응답 파싱이 두 곳에 복제되지 않는다.

## 4. 롤백

app_setting UPDATE 한 줄로 즉시 온라인 복귀:

```sql
UPDATE app_setting SET value = 'online' WHERE key = 'analytics.analyze-transport';
```

이미 제출된 pending 배치는 수거 크론이 그대로 회수한다(전송 방식과 무관하게 독립 동작).

## 5. 범위 밖

- 계정 카피 분석(ACCOUNT_ANALYZE) 배치 전환 — 후속 과제.
- 배치 실패 건 자동 재시도 — 다음날 후보 diff가 자연 재대상하므로 불필요 판단.
