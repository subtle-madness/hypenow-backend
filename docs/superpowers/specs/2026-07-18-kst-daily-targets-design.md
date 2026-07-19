# 데일리 수집 대상 선정 — KST 달력일 기준 전환 설계

> 상태: ✅ 구현됨

## 배경·문제

- 수집(collect)·릴스(reels) 잡의 대상 선정이 **"마지막 방문 후 24시간(N일) 경과"** 기준
  (`revisitBefore = now − revisitIntervalDays`)이라, 어제 오후 3시에 돈 계정은 오늘 오전에
  대상이 되지 않는다. 사용자는 "오늘 하루치"를 자정 기준으로 다시 돌리고 싶다.
- Clock 빈이 `Clock.systemUTC()`라서 데일리 대시보드의 "오늘(자정 기준)"이 실제로는
  **UTC 자정 = KST 오전 9시**에 리셋된다. 사용자는 한국 기준으로 보고 있다.

## 결정 (A안)

1. **Clock 존을 `Asia/Seoul`로 변경** — `CrawlerConfig.clock()`을
   `Clock.system(ZoneId.of("Asia/Seoul"))`로. 존 의존 코드는 `StatusService.daily()`의
   자정 계산 한 곳뿐이라 파급 없음. 대시보드 "오늘" 타일이 KST 자정 기준이 된다.
2. **대상 선정 경계를 달력일 기준으로** — `revisitBefore = 오늘(KST) 자정 − (N−1)일`
   (N = `collect.revisit-interval-days`, 기본 1). N=1이면 "오늘 아직 방문 안 한 계정 전부"가
   대상이고, KST 자정에 자동으로 전원 리셋된다. 수동 리셋 버튼은 두지 않는다.
3. 설정 키 `collect.revisit-interval-days`는 유지하되 의미가
   "마지막 방문 후 N일 경과" → **"달력 기준 N일마다(마지막 방문이 N−1일 전 자정 이전)"**로
   바뀐다. 설정 설명·UI 문구를 새 의미로 갱신한다.

## 적용 지점

경계값 계산이 바뀌는 곳 (리포지토리 쿼리 `lastCollectedAt < :revisitBefore` /
`lastReelsAt < :revisitBefore`는 그대로, 넘기는 값만 변경):

- `CollectJob.run()` — 수집 대상 선정
- `ReelsJob.run()` — 릴스 대상 선정
- `JobCostEstimator` — collect/reels 예상 비용의 due 계산 2곳
- `StatusService.summary()` — trackDue/reelsDue 카드 수치

경계 계산은 한 곳(예: `SettingsService` 또는 공용 헬퍼)에 모아 4곳이 같은 값을 쓰게 한다.

## 기존 데이터·전환 영향

- `last_collected_at`·`last_reels_at`은 `timestamptz`(Instant, 절대 시각) — 존과 무관하므로
  **마이그레이션 불필요**, 저장된 값 그대로 유효.
- 배포 직후 1회성 효과: "오늘" 창이 UTC 자정→KST 자정으로 9시간 넓어져 완료 수치가 한 번
  튀어 보일 수 있고, 어제 방문한 계정이 즉시 대상에 들어온다(의도된 동작). 조치 불필요.

## 테스트

- 고정 Clock(KST)으로: 어제 오후(예: KST 어제 15:00) 방문한 계정이 오늘 대상에 포함되는지,
  오늘 새벽(KST 오늘 01:00) 방문한 계정은 제외되는지 검증.
- N=2 등 다일 주기에서 경계가 `자정 − (N−1)일`로 계산되는지 단위 검증.
