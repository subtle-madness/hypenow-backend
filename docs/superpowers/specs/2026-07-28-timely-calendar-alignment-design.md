# timely 판정 캘린더일 정합 + 양방향 소급 설계

> 상태: 🟢 활성 · 2026-07-28 · PO 결정: 캘린더일 기준으로 정합, 소급은 양방향 전체

## 1. 문제

timely(제때 크롤) 판정 기준이 두 곳에서 다르다.

- **후보 뷰** `analytics/views/04_analysis_candidates.sql` — **캘린더일(KST)** 기준:
  캡처 캘린더일 ∈ [업로드일+pin, 업로드일+pin+slack). 2026-07-20에 date-bleed 해소를
  근거로 재정정된 정본.
- **Java 잡** `ContentAnalysisJob` — **시간 간격** 기준:
  `metric_captured_at ∈ [posted_at+pin일, posted_at+(pin+slack)일)`. 뷰 정정 때
  "판정식 07-20 보존"으로 의도적으로 남겨둔 구식.

크롤이 KST 새벽 01~03시에 하루 1회 돌기 때문에 두 식은 구조적으로 갈린다:
뷰는 D+3일 크롤 1회면 timely인데, Java는 낮 업로드 기준 **D+4 새벽 크롤에도 잡혀야**
간격 [3,4)일에 든다(핀 규칙 v_pinned_metrics의 성숙도 같은 간격식이라 낮 업로드의
핀은 보통 D+4 새벽 스냅샷).

**07-28 운영 실측 증상** (pin=3·slack=1):

- 대시보드(뷰 기준) timely 미분석 1,262건인데 잡 기준 timely 잔여 0 —
  "새벽 배치가 돌았는데 안 줄어든" 것처럼 보임.
- 잔여 1,262 분해: 간격 [3,4) **0건**(잡은 제 몫을 다함) / 간격 2.x일 925건
  (성숙 스냅샷 없음 — 계정은 어제 크롤됐으나 그 게시물만 수집 범위에서 빠짐 745
  + 계정째 크롤 안 됨 180) / 간격 4일+ 337건(D+4를 건너뛰고 D+5+에 첫 성숙 스냅샷).
- 이 누수는 일 ~350–770건 규모로, late_backfill 분기로 흘러 분석돼도
  `late_backfill` 마킹 → **랭킹(timely만 노출)에서 영구 제외**된다.
- 역방향 표류도 존재: D+3 크롤은 놓치고 D+4에만 잡힌 게시물은 간격식으론 timely로
  마킹돼 랭킹에 남는다(캘린더 기준으론 늦크롤). 기존 마킹 전수 대조:
  캘린더-timely인데 late_backfill 마킹 2,444 / 캘린더-not-timely인데 timely 마킹
  4,144(+뷰 이탈 861) / 레거시 NULL·immature 중 캘린더-timely 369.

## 2. 결정

1. **캘린더일 기준이 정본** — Java 잡을 뷰에 맞춘다(뷰 무변경).
2. **소급은 양방향 전체 정합** — 랭킹 모수 순감소(~-2.5천, 뷰티 필터 전)를 감수하고
   데이터 정직성을 택한다.

## 3. 접근 선택 (B안 채택)

캘린더일 판정은 "D+3일에 usable 스냅샷이 존재했는가"라 스냅샷 이력(raw)이 필요 —
핀 1개만 가진 analysis 미러로는 계산 불가능하다. 따라서:

- **B안(채택): Java 잡이 raw 후보 뷰를 입구로 복원.** 뷰 주석의 원 설계
  ("이 뷰를 입구로 배치 구성, '이미 분석됨' 제외는 Java 몫")로 회귀. timely 수식은
  뷰 한 곳에만 존재. 잡은 이미 baseline을 raw에서 읽으므로 크로스 DB 패턴 기존재.
  스키마·뷰·계약 모듈·미러 전부 무변경.
- A안(기각): 미러에 timely 컬럼 운반 — 접점 4곳(계약 record·Flyway·미러 소스 뷰·잡)
  + 분석 관심사가 서빙 미러에 스며드는 경계 문제.
- C안(기각): Java에 캘린더 수식 복제 — 수식 이원화가 이번 사고의 근본 원인.

## 4. 잡 변경 상세 — `ContentAnalysisJob`

`TIMELY_SQL`/`LATE_BACKFILL_SQL`(analysis 미러 기반, 간격식) 삭제. 두 진입점 공통:

- **후보(raw)**: `SELECT short_code, timely FROM analytics.v_analysis_candidates
  WHERE [NOT] timely ORDER BY metric_captured_at DESC NULLS LAST, short_code`.
  성숙(창닫힘 포함)·최근12 윈도우·rn≤12는 뷰가 담당 — Java의 간격식 timely,
  `analyzeMaturityDays` 게이트, rn≤12 서브쿼리 전부 삭제.
- **제외(analysis, Java diff)**: ① 기분석 set(`content_analyses.short_code`)
  ② 댓글 게이트 차단 set(`content_comments`에 있는데 `comment_classifications`에
  없는 short_code) ③ 미러 존재 set(`contents.short_code`) — ③은 라이브 뷰(20:00)와
  미러(19:30) 간극으로 `analyzeOne`이 미러에서 행을 못 찾아 실패 카운트가 오염되는
  것을 막는 가드(부재 시 스킵, 다음 미러 후 자연 재대상).
- **정렬 유지**: 최신 수집분부터(썸네일 서명 URL 생존 우선순위, B3 의도 보존).
- `analyzeOne`·마킹 분기(`timely ? "timely" : "late_backfill"`)·쿼터 이월·병렬
  처리·baseline 로딩 무변경.
- `analyzeMaturityDays` 설정은 이 잡에서 미사용이 됨 — 다른 소비자가 없으면
  설정 메서드는 남기되 Javadoc에 사용처 없음을 명시(삭제는 하지 않음, 뷰가
  app_setting 키를 직접 읽는 구조와의 혼동 방지).

## 5. 소급 런북 (1회성, 배포·야간 배치 정상 확인 후)

dry-run → 사용자 확인 → COMMIT 순서(self-heal 관용). 위치는 plans 문서에 포함.

1. **추출(raw)**: 전 콘텐츠의 캘린더일 timely를 1회 계산해 COPY로 analysis 임시
   테이블 적재. 후보 뷰는 caption·창 게이트로 기분석 행 일부(뷰 이탈 861)가 빠지므로,
   뷰의 LATERAL 판정식만 떼어 **전 콘텐츠에 게이트 없이** 적용한다 — 1회성 스크립트라
   수식 복제 허용.
2. **정정(analysis, 한 트랜잭션)**: 뷰티 여부와 무관하게
   - ⓐ 캘린더-timely ∧ 마킹 ∈ {late_backfill, NULL, immature} → `'timely'` (예상 ~2.8천)
   - ⓑ 마킹 = timely ∧ 캘린더-not-timely → `'late_backfill'` (예상 ~5.0천)
3. **검증**: 정정 후 "마킹 == 캘린더 판정" 항등식 카운트 0건 확인 + 랭킹 모수
   (`is_beauty AND (metric_timeliness='timely' OR NULL)`) 전후 비교 기록.
   WAS는 라이브 조회라 즉시 반영.

## 6. 파급

- **대시보드**: 잔여 카드는 이미 뷰 기준(07-28 실측 1,262/8,597 일치) — 이번 변경으로
  잡과 대시보드가 같은 숫자를 보게 됨. `PipelineStatsService`에 간격식 잔재가 없는지
  구현 중 확인.
- **PR #144(백필 크론 21:00Z)와 독립** — 머지 순서 무관. 크론이 켜지면 backfill
  분기가 매일 뷰 기준으로 소진.
- **랭킹 순효과**: 소급으로 순 ~-2.5천(뷰티 필터 전) — 캘린더 정의에 맞춘 정직한
  결과. §7 결정 기록에 남긴다.

## 7. 테스트

- 기존 `ContentAnalysisJob` Testcontainers 테스트를 raw 후보 뷰 입구로 재배선
  (baseline이 이미 raw 뷰를 쓰는 하니스 존재).
- 캘린더 경계 케이스: 낮 업로드 + D+3 새벽 캡처만 = timely 마킹 /
  D+4 새벽 캡처만 = late_backfill 마킹 / 미러 부재 후보 = 스킵.
- 댓글 게이트·기분석 제외가 Java diff로 옮겨져도 동작 동일함을 단언.

## 8. 문서

ARCHITECTURE §5(트랙)·§7(결정: 캘린더일 정본 + 양방향 소급) 갱신. 구현 계획은
`docs/superpowers/plans/2026-07-28-timely-calendar-alignment.md`.
