# 크롤링 스케줄 자동화 + 크롤러 어드민 대시보드 개편 — 설계

> 상태: 🟢 활성 · 2026-07-22 브레인스토밍 확정

## 배경·목표

크롤링은 07-19부터 오라클 서버(6컨테이너)가 수집 주체지만, 실행은 어드민 UI(SSH 터널 8080 `/ui`)
수동 트리거였다. 이 문서는 두 가지를 확정한다:

1. **크롤링 데일리 자동화** — 이미 있는 `ScheduleRunner`(crawler)를 운영 compose env로 점화.
2. **크롤러 어드민 대시보드 개편** — 잡 실행 버튼을 대시보드로 통합하고, "잡 실행"·"수집 게시물"
   탭을 코드까지 완전 제거.

## 1. 크롤링 스케줄 자동화

### 결정 사항

- **자동화 대상**: qualify · beauty · collect · reels 4종. **discover·similar는 수동 유지**
  (발굴은 비용·모수 통제를 위해 사람이 트리거).
- **방식**: 코드 무변경. crawler에 이미 있는 `ScheduleRunner`
  (`crawler.schedule.enabled` 게이트, 잡별 크론)를 [deploy/compose.yaml](../../../deploy/compose.yaml)
  env로 켠다 — analytics 스케줄 점화와 동일한 패턴(UTC 크론 + KST 주석).
- **실패 알림 없음**: 잡 실패는 대시보드 "최근 실행" FAILED 행으로 사후 확인. 컨테이너·인스턴스
  다운 감지는 기존 OCI 알람(디스코드)이 이미 담당. 운영해보고 필요 시 디스코드 잡 실패 알림을
  후속으로 붙인다.

### 윈도우 반복 크론 — 실패·다운 흡수 장치

크롤링은 일시 실패(프록시 401·API 장애)가 잦고 서버도 종종 내려간다. 단발 크론 대신
**밤 시간대 윈도우 안에서 반복 발사**한다. 네 잡 모두 "남은 대상만" 선정하는 구조라
(collect·reels는 재방문 컷오프 `RevisitCutoff`, qualify는 DISCOVERED 잔여, beauty는 미판정 잔여)
반복 발사가 안전하다:

| 잡 | KST 윈도우 | 크론(UTC) | 비고 |
|---|---|---|---|
| collect | 01:00~03:30, 30분 간격 | `0 0/30 16-18 * * *` | 첫 성공 후엔 잔여 0 → 즉시 무동작 |
| reels | 01:10~03:55, 15분 간격 | `0 10/15 16-18 * * *` | 회당 10계정 한도 × 12회 = 최대 120계정/밤 |
| qualify | 02:00~03:30, 30분 간격 | `0 0/30 17-18 * * *` | DISCOVERED 잔여만 |
| beauty | 03:00 · 03:30 | `0 0,30 18 * * *` | 미판정 잔여만 |
| discover | — | `-` (비활성) | 수동 유지. similar는 ScheduleRunner에 아예 없음 |

동작 원리:

- **일시 실패** — 한 발이 실패해도 15~30분 뒤 다음 발사가 남은 대상을 재시도. 실행 중 겹치면
  잡 락(`JobLock`, 잡별 분리)이 BUSY로 스킵.
- **컨테이너 다운** — `restart: unless-stopped`로 살아나면 윈도우 안의 남은 발사가 그 밤에 따라잡는다.
  윈도우를 통째로 놓치면 그날 하루 건너뜀 — qualify·beauty 큐는 다음 날로 자연 이월, collect
  스냅샷만 하루 공백(허용).
- **무한 실행 아님** — 발사 시각이 윈도우로 못박혀 있고(마지막 발사 후 다음 날까지 무동작),
  각 발사는 배치 상한만큼 처리하고 스스로 끝나는 일회성 실행. 실패 재시도도 윈도우가 끝나면 끝.
- **analytics 정렬** — 수집이 04:30 미러 전에 완결되어 신규 판정·수집분이 **같은 날 새벽**
  분석·아카이브에 반영된다(썸네일 CDN ~4일 만료 전 처리 원칙 부합). 마지막 발사의 배치가 04:30을
  넘겨도 다음 날 미러가 수습.

### 변경 파일

- [deploy/compose.yaml](../../../deploy/compose.yaml) — crawler 서비스에
  `CRAWLER_SCHEDULE_ENABLED: "true"` + 위 표의 잡별 크론 + `CRAWLER_SCHEDULE_DISCOVER_CRON: "-"`.
  서비스 주석의 "스케줄은 기본 off(수동 운영)" 문구 갱신.
- [crawler/src/main/resources/application.yml](../../../crawler/src/main/resources/application.yml) —
  기본 크론값을 같은 타임라인으로 정렬(enabled=false라 동작 무변경, 문서 역할).

## 2. 크롤러 어드민 대시보드 개편

### 결정 사항 (브레인스토밍 시각 목업으로 B안 선택)

대시보드(`dashboard.html`)를 다음 순서로 재구성:

1. 현재 작업 바(기존) + **flash 메시지 영역**(트리거 결과 표시 — 신규)
2. 파이프라인 구조 SVG(**기존 그대로 유지**) + **바로 아래 잡별 실행 스트립**:
   discover · qualify(☐ 전체 재판정) · beauty(☐ 재판정) · collect · reels · similar —
   각각 실행/중지 버튼. 기존 `POST /ui/jobs/*` 트리거·중지 엔드포인트 재사용,
   체크박스 `data-persist`(admin.js) 유지 — dashboard.html에 admin.js 로드 추가.
3. **예상 비용 카드**(잡 실행 페이지에서 이동 — 수동 트리거 전 확인 동선이라 스트립 직하)
4. 상태 타일(기존)
5. **실행 로그**(잡 실행 페이지에서 이동, 3초 폴링)
6. 최근 실행(기존)

컨트롤러:

- `UiJobController`의 트리거·중지 리다이렉트를 `/ui/jobs` → `/ui`로 변경.
- `UiController`: 대시보드 라우트에 비용 모델(`jobCostEstimator.estimates()`) 추가.

### 탭 완전 제거 (코드 포함)

- **수집 게시물**: nav 링크 + `contents.html` · `content-detail.html` 템플릿 +
  `GET /ui/contents` · `GET /ui/contents/{id}` 라우트 + 전용 쿼리 코드 삭제.
  (상세 페이지 진입 경로가 수집 게시물 목록뿐이라 함께 제거 — 사전 확인됨)
- **잡 실행**: nav 링크 + `jobs.html` + `GET /ui/jobs` 라우트 삭제.
  `POST /ui/jobs/*`(트리거·중지)는 대시보드 스트립이 계속 사용하므로 유지.
- 관련 테스트(`UiSmokeTest` · `UiJobControllerTest` 등) 갱신.

## 3. 문서·검증

- ARCHITECTURE.md §5(크롤러 "수동 운영" 문구 → 스케줄 운영) · §7 결정 기록 한 줄, deploy/README 갱신.
- 검증: `./gradlew :crawler:test` + 레포 `verify` 스킬(실제 앱 기동)로 대시보드 화면·트리거
  동작 확인. 배포는 develop→main 머지(CD)로만.
