# analytics 어드민 UI + 스케줄러 골격 설계

> 상태: 🟢 활성
> 작성: 2026-07-17 · 브레인스토밍 세션 결정 기록

## 1. 배경과 목표

analytics는 현재 `web-application-type: none`인 one-shot 배치다 — 트리거는 "사람이
`./gradlew :analytics:bootRun`을 실행하는 것" 자체이고, 관찰 수단은 터미널 로그뿐이다.
크롤러가 새 데이터를 적재해도 누군가 손으로 analytics를 돌리기 전까지 분석 결과는 갱신되지
않는다(ARCHITECTURE §8 "미러 갱신 주기: 현재 수동 1회").

목표:

1. **UI 트리거** — crawler 어드민(`/ui`)처럼 브라우저에서 잡을 실행하고 작업을 관찰한다.
2. **스케줄러 골격** — 나중에 크론으로 돌릴 수 있는 자리를 지금 만들어 둔다(기본 off).

## 2. 결정 요약 (브레인스토밍 확정)

| 질문 | 결정 |
|---|---|
| UI 위치 | **analytics 자체를 상주 웹 서버화**(포트 8082). crawler 어드민 통합은 §4-4 경계 위반, 별도 어드민 모듈은 과잉이라 기각 |
| 잡 범위 | 로컬 4잡 전부(미러 + LLM 3종). **cloud push는 후순위** — 지금처럼 CLI(cloud 프로파일) 유지 |
| 실행 이력 | **영속 이력 테이블 없음** — 관찰은 로그로 충분(크롤러 `LogBuffer` 패턴의 인메모리 로그 패널 + 잡별 실행 중 배지). crawl_run 같은 DB 이력은 만들지 않는다 |
| 스케줄러 | **골격까지 이번에 포함, 기본 off** — 크롤러 `crawler.schedule.enabled` 패턴 동일 |
| 구현 접근 | **크롤러 잡 패턴 이식**(JobService·JobLock·TaskExecutor·htmx 폴링). 최소 컨트롤러 방식·Spring Batch는 기각 |

## 3. 구조 전환 — 상주 서버 + one-shot 병존

- 기본 프로파일: `web-application-type: servlet`, **포트 8082** (crawler 8080 · was 8081 다음).
  `analytics/build.gradle`에 `spring-boot-starter-web`·`spring-boot-starter-thymeleaf` 추가.
- **`analytics.mirror-on-startup` 기본값 `true` → `false`.** 서버 모드에서는 기동 시 아무 잡도
  자동 실행되지 않고 UI 트리거가 정본.
- **cloud push는 현행 one-shot 보존**: `application-cloud.yml`에
  `web-application-type: none` + `mirror-on-startup: true`를 명시. 사용법(터널 후 bootRun) 무변경.
- 기존 CommandLineRunner 6종(mirror·classify·analyze·account-analyze + 스파이크 2종)은
  무변경 — CLI one-shot 경로 보존. 로컬에서 "예전처럼 미러만 1회"는
  `--analytics.mirror-on-startup=true --spring.main.web-application-type=none`.

## 4. 트리거 층 — 크롤러 JobService 패턴 복제

`com.celfit.analytics.admin` 패키지(analytics는 평탄 패키지 컨벤션) 신설:

- `JobName` enum: `MIRROR` · `CLASSIFY` · `ANALYZE` · `ACCOUNT_ANALYZE`
- `TriggerType` enum: `MANUAL` · `SCHEDULED`
- `AnalyticsJobService.trigger(JobName, TriggerType)` → `TriggerResult { ACCEPTED, BUSY }`
  - 잡별 락(크롤러 `JobLock` 방식 — `ConcurrentHashMap<JobName, AtomicBoolean>`)으로 중복 실행 차단
  - `jobTaskExecutor`(전용 TaskExecutor 빈)로 비동기 실행, `finally`에서 락 해제
  - 기존 `MirrorJob`·`CommentClassificationJob`·`ContentAnalysisJob`·`AccountAnalysisJob`을
    그대로 호출 — **잡 코드 자체는 무변경**
- 락은 잡별 독립. 미러 대상 테이블과 LLM 결과 테이블(`content_analyses`·`account_analyses`)은
  분리돼 있어 동시 실행 무해.

## 5. 어드민 UI — `/ui` 한 페이지

크롤러 jobs.html 구성을 그대로 이식:

- **잡 버튼 4개** — POST `/ui/jobs/{mirror|classify|analyze|account-analyze}` →
  플래시 메시지("실행 시작" / "이미 실행 중") 후 `/ui` 리다이렉트
- **예상 비용 카드**(LLM 잡 3종) — 각 잡의 기존 대상 선정 쿼리로 대상 건수 집계 × 실측 단가
  범위 표시. 단가는 ARCHITECTURE §6 실측값(댓글 분류 게시물 1,000건당·VLM 건당 $0.03~0.05)을
  상수로 박는다(추정치임을 카드에 명시). 실행 전에 비용을 보고 누르는 크롤러 UX 동일.
  미러는 비용 0이라 카드 없음
- **현재 상태 배지** — 잡별 실행 중/유휴(락 상태 노출), htmx 3초 폴링
- **실행 로그 패널** — 크롤러 `LogBuffer`(logback 인메모리 appender, 최근 200줄,
  `com.celfit.analytics` 로거)를 analytics에 복제. §4-4가 모듈 간 util 공유를 금지하므로
  ~70줄 중복은 의도된 비용
- 정적 자원(admin.css·htmx 로드)도 크롤러에서 복사

## 6. 스케줄러 골격 — 기본 off

크롤러 `ScheduleRunner` 패턴 동일:

- `analytics.schedule.enabled=true`일 때만 빈 활성(`@ConditionalOnProperty`), 기본 off
- 잡별 크론 프로퍼티 4개(`analytics.schedule.mirror-cron` 등), 기본 `"-"`(Spring 크론 비활성 값)
- 실행은 `jobService.trigger(job, SCHEDULED)` — 수동과 같은 유스케이스·같은 락을 탄다

## 7. 에러 처리 · 테스트

- 잡 예외는 `AnalyticsJobService`가 error 로그 + 락 해제(크롤러 방식). 부분 실패는 잡 내부
  warn 로그 → 로그 패널에 그대로 노출
- 테스트: `AnalyticsJobService` 단위(BUSY/ACCEPTED·예외 시 락 해제), `@WebMvcTest`
  (Spring Boot 4 — `org.springframework.boot.webmvc.test.autoconfigure` 패키지)로 트리거
  엔드포인트, `LogBuffer` 단위. LLM은 기존 fake 포트 컨벤션 유지 — UI·서비스 테스트가
  실 API를 때리지 않는다

## 8. 비범위 (이번에 안 하는 것)

- cloud push의 UI화(터널 의존 — 필요해지면 후속)
- 실행 이력 DB 테이블(결정으로 기각 — 로그로 충분)
- Spring Batch/Quartz 도입
- 스케줄 기본 on(켜는 시점은 크롤 일일 자동화와 함께 별도 결정)
