# analytics 어드민 대시보드 재설계 — 파이프라인 관측

> 상태: 🟢 활성 · 설계 확정 (2026-07-19, 비주얼 목업 승인)

**작성일:** 2026-07-19
**브랜치:** `feat/analytics-dashboard`

## 1. 배경·목적

운영 첫날(07-19) 사용자 피드백: 현 어드민(8082 `/ui`)은 ① 단계별 현황(몇 건 중 몇 건이
어느 단계인지) 추적 불가, ② 예상 비용 카드는 Gemini 무료 전환으로 무의미, ③ 스케줄
자동화가 켜져 있는데 화면엔 수동 버튼만 보여 자동/수동 구분 불가, ④ 실행 진행률 없음,
⑤ 레이아웃 품질이 운영 도구 수준 미달.

목표: **운영 서비스급 파이프라인 관측 대시보드** — "지금 어디까지 왔고, 무엇이 돌고
있고, 다음에 무엇이 예정돼 있나"를 한 화면에서.

## 2. 레이아웃 (목업 승인분 — A안 퍼널 + C안 실행 피드)

위에서 아래로 4개 영역, 단일 페이지(사이드바 없음):

1. **헤더**: 서비스명 + `자동화 ON/OFF` 배지 + "잡 3종 독립 실행 · 각 카드에 다음 예정"
   보조 배지. 단일 파이프라인으로 오해되지 않게 잡별 독립성을 명시한다.
2. **파이프라인 퍼널 카드**: 캡션에 "데이터가 흐르는 단계 (잡 실행 순서 아님)" 명시.
   - 콘텐츠: `수집(raw) → LLM 후보 → 분석 완료 → 서빙 미러` 4단 숫자 + 커버리지
     진행 바 + 해석 문장("오늘 +450 예정, 전량까지 약 N일 — 무료 예산 기준").
   - 계정: `카피 보유 / 뷰티 모수` 진행 바.
   - 무거운 집계 값 옆에 "집계 HH:MM 기준" 표기.
3. **잡 카드 3종** (미러·콘텐츠 분석·계정 카피 — 댓글 분류는 휴면이라 제외):
   - 상태 배지(실행 중/유휴), 실행 중이면 진행 바 `processed/total · 실패 n · 경과 ·
     예상 완료`(선형 외삽), 유휴면 `최근 실행 시각·건수·결과`.
   - `다음 예정: <크론 다음 발화 시각, KST>` — 스케줄 비활성이면 "수동 전용".
   - 수동 트리거 버튼(실행 중이면 비활성). 기존 POST `/ui/jobs/{slug}` 유지.
4. **실행 피드 카드**: 최근 이벤트 타임라인(시작/완료/실패/쿼터 이월/중단, 트리거 구분
   수동/스케줄) + 접이식 라이브 로그(기존 LogBuffer 프래그먼트 재사용).

비용 카드는 제거한다.

## 3. 컴포넌트 설계

### 3-1. ProgressReporter + JobProgressRegistry (진행률)

- `analyze` 패키지에 함수형 인터페이스 `ProgressReporter { void report(int processed, int failed, int total); }`
  — 잡이 admin에 의존하지 않게 하는 경계. 미배선 컨텍스트(one-shot CLI)는 no-op 주입.
- 잡 3종이 루프에서 보고: ContentAnalysisJob(대상 확정 직후 total, 건마다 processed/failed),
  AccountAnalysisJob(계정 단위 동일), 미러(뷰 단위 — total=대상 뷰 수, 뷰 완료마다 +1).
- `admin.JobProgressRegistry`가 구현: 잡별 volatile 스냅샷 `{running, processed, failed,
  total, startedAt}`. AnalyticsJobService 시작/종료 시 running 갱신, finally에서 반드시 해제.

### 3-2. RunHistory (실행 피드)

- `admin.RunHistory`: 인메모리 링 버퍼(최근 50건), 항목 = `{job, trigger, startedAt,
  endedAt, outcome(SUCCESS|FAILED|QUOTA_CARRYOVER|ERROR), processed, failed, note}`.
- AnalyticsJobService가 시작·종료 훅에서 기록. 잡 반환값(처리 건수)·예외 종류로 outcome 판정
  (LlmQuotaExhaustedException → QUOTA_CARRYOVER — 단 현재 잡이 예외를 삼키고 정상 반환하므로
  잡 반환에 이월 여부를 실어야 함 → 잡 반환형을 `JobResult(processed, failed, carriedOver)`
  record로 확장, 기존 int 반환 호출부 함께 갱신).
- **재시작 시 소실 수용** — 기존 "실행 이력 DB 테이블 없음" 결정(07-17 태스크 I) 유지.
  피드 비어 있을 때 "서버 시작 후 실행 없음 (이력은 메모리 보관)" 안내.

### 3-3. PipelineStatsService (퍼널)

- **빠른 집계(매 요청 동기, ms)** — analysis DB: 분석 완료 수(content_analyses), 서빙 미러
  수(contents), 카피 보유 계정 수(account_analyses DISTINCT handle), 뷰티 모수(accounts).
- **무거운 집계(비동기 + TTL 30분 캐시)** — raw DB 중 뷰 스캔인 것만: LLM 후보 수
  (v_analysis_candidates — 운영 실측 3.5분). 수집 수(raw `content` 단순 카운트, ms)는
  빠른 집계로 분류한다.
  JobCostEstimator(PR #45)의 가상 스레드 + AtomicBoolean 패턴 재사용, `computedAt` 노출.
  실패 시 이전 캐시 유지. 캐시 비어 있으면 해당 숫자 "집계 중…" 표시.
- 해석 문장: `오늘 예정 = min(후보-완료 잔여, analyze-batch-limit)`,
  `전량까지 일수 = ceil(잔여 / analyze-batch-limit)`.

### 3-4. ScheduleInfo (다음 예정)

- 크론 프로퍼티(`analytics.schedule.*-cron`, 비활성 `-`)와 `analytics.schedule.enabled`를
  읽어 잡별 다음 발화 시각 계산 — Spring `CronExpression.parse().next(now)`. 표시 타임존
  Asia/Seoul 고정(서버 UTC와 무관하게 사용자 기준). 골격은 기존 ScheduleRunner 프로퍼티 재사용.

### 3-5. AdminUiController + 템플릿

- 모델: `funnel`(PipelineStats), `jobs`(카드 3종 뷰모델 — 상태·진행·최근 이력·다음 예정),
  `feed`(RunHistory 최근 20), `scheduleEnabled`.
- 부분 갱신: 기존 5초 폴링 프래그먼트를 `fragments/board`(잡 카드 + 피드) 하나로 통합,
  로그 프래그먼트는 유지. 퍼널은 페이지 로드 시만(무거운 값은 어차피 캐시).
- 템플릿·CSS 전면 재작성: 크롤러 어드민 디자인 토큰(딥 로즈 + 웜 뉴트럴, Pretendard,
  다크모드 미디어쿼리) 이식. 정적 CSS 파일(analytics/static/admin.css) 신설.

### 3-6. 삭제

- `JobCostEstimator` 제거(비용 카드 폐지). PR #45의 비동기 캐시 패턴은 PipelineStatsService가
  승계. 대상 카운트 로직 중 "오늘 예정" 계산에 필요한 부분만 PipelineStats로 흡수.

## 4. 데이터 플로우

```
잡 스레드 ──report()──▶ JobProgressRegistry ─┐
AnalyticsJobService ──기록──▶ RunHistory ────┤
PipelineStatsService ──집계(동기+비동기캐시) ─┼──▶ AdminUiController ──▶ admin.html
ScheduleRunner 프로퍼티 ──▶ ScheduleInfo ────┘         └ fragments/board (5s 폴링)
```

## 5. 에러 처리

- 진행률 보고·이력 기록 실패는 잡을 깨지 않는다(베스트 에포트, try 내 로깅).
- 무거운 집계 실패 → 이전 캐시 유지 + 로그 WARN.
- 스케줄 프로퍼티 파싱 실패(잘못된 크론) → 해당 잡 "다음 예정" 미표시, 페이지는 정상.

## 6. 테스트

- 단위: JobProgressRegistry(동시 갱신·finally 해제), RunHistory(링 버퍼 상한·outcome 판정),
  ScheduleInfo(크론→KST 다음 시각), PipelineStats 해석 문장 계산.
- 잡 보고 배선: ContentAnalysisJobTest 기존 fake 포트 테스트에 reporter 호출 검증 추가.
- 컨트롤러: 기존 스타일 유지(@WebMvcTest — Spring Boot 4 패키지 주의).
- LLM·실 DB 미접촉(포트 fake) 컨벤션 유지.

## 7. 배포·후속

- PR → develop 머지 → `deploy.sh <host> analytics`.
- 후속(범위 외): 기준선·후보 뷰 풀스캔 분 단위의 근본 처치(물질화), 파이프라인 단계별
  드릴다운(어떤 콘텐츠가 어느 단계에 있는지 목록), 크롤러 어드민과의 홈 링크 상호 연결.
