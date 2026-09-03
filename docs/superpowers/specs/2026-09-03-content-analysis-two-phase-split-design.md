# 콘텐츠 AI 분석 2단계 분리 설계 (파트 A 사실 D+1 · 파트 B 해석 D+4)

> 상태: ✅ 구현됨 (2026-09-03 구현 완료 · 운영 전환은 [deploy/README.md §17](../../../deploy/README.md#17-콘텐츠-분석-2단계-분리-파트-a-사실-d1--파트-b-해석-d4) 런북, 토글 기본 unified)

## 0. 배경

- **증상**: 게시물 업로드 후 4일간 광고 판정(`content_analyses.ad_type`)과 카테고리(`main_category`)가
  화면에 비어 보인다. 인플루언서 상세의 최근 콘텐츠(`V1/V2InfluencerReportRepository.findSeries`,
  `content_analyses` LEFT JOIN)는 `ad_type` NULL로 내려가고, 랭킹(6.1)·드로어(6.3)는 분석 행이
  없으면 아예 안 보인다(INNER JOIN, 6.3은 404).
- **원인**: 분석 후보 뷰 `analytics/views/04_analysis_candidates.sql`의 성숙 게이트
  `업로드일(KST) + metric-pin-days(3) + analyze-timely-slack-days(1) <= 오늘(KST)`. 즉 D+4 새벽에야
  후보가 되고, 그날 배치 수거 후에야 화면에 뜬다. 09-03 운영 전수 점검으로 파이프라인·판정 품질은
  정상 확인됐다. 품질 문제가 아니라 **게이트 위치** 문제다.
- **구조적 근거**: `GeminiContentAnalyzer`의 통합 콜 하나가 성격이 다른 두 산출을 같이 만든다.
  - **파트 A(사실)**: adType·mainCategory·subCategories·detectedBrands·detectedProducts·
    detectedProductCategories·detectedDistributors·adDisclosure·sponsoredSignalLevel/Reasons·
    vlmAttributes·isRelevant(→ is_beauty). **캡션(+유료 파트너십 태그)만 의존**한다.
  - **파트 B(해석)**: aiContentSummary·contentsPattern·aiCommentInsight·commentAuthenticityGrade·
    commentAuthenticityNote. **3일 고정 지표·계정 기준선·댓글 분포를 인용**하므로 성숙이 필요하다.
  - 게이트가 필요한 건 파트 B뿐인데, 통합 콜이라 파트 A까지 D+4를 기다린다.
- **이미 있는 분리선**: `GeminiContentSynthesizer`(파트 B 전용 어댑터. 분류표·썸네일 없음,
  MAX_OUTPUT 2048, `GeminiContentAnalyzer.SYNTHESIS_RULES`를 그대로 공유)와
  `ContentSynthesisRefreshJob`(저장된 사실을 "확인된 사실"로 넣고 해석 5필드만 재생성,
  `ContentAnalysisWriter.updateSynthesis`로 UPDATE, `synthesis_version` 게이트). V38 마이그레이션
  주석이 이미 ①사실/②해석의 분리를 명문화해 두었다.

## 1. 결정 요약

1. **파트 A를 D+1 새벽 배치로 먼저 실행·저장한다.** 후보는 성숙 조건 없이 "모수 ∩ ENUMERATION ∩
   캡션 존재 ∩ 미분석". 캡션 전용(배치는 원래 캡션 전용이라 입력 손실 없음).
2. **파트 B는 성숙(D+4) 시 별도 배치로 채운다.** `GeminiContentSynthesizer` 프롬프트를 Vertex 배치로
   제출하고, 수거 시 `ContentAnalysisWriter.updateSynthesis` 계열로 5필드+기준선 스냅샷을 UPDATE한다.
   `ContentSynthesisRefreshJob`은 온라인 전용(배치 미지원, 성숙 가드 없음, 운영 크론 미배선 - 어드민
   수동 트리거만)이라 그대로 쓰지 않고, **동형의 배치 경로를 `ContentAnalysisJob`에 phase로 추가**한다.
3. **`metric_timeliness` 기록 시점을 파트 B로 옮긴다.** 파트 A 시점 값은 신규 어휘 **`'pending'`**
   (NULL 아님 - §4-2). 파트 B 수거 시 timely/late_backfill로 확정한다.
4. **제외 게이트 재정의**: "이미 분석됨"이 "파트 A 행 존재"(A 잡)와 "파트 B 완료"(B 잡)로 갈린다.
   댓글 게이트는 파트 B에만 남긴다. 미러 미도달 게이트는 08-31에 이미 제거됐다.
5. **하지 않는 것**: D+0 무LLM 라벨(정규식·`ad_marked`만으로 광고 표시), 파트 B 성과 문구
   템플릿화. 파트 B는 LLM 유지.
6. **롤백은 `app_setting` 한 줄**(`analytics.analyze-mode` = `unified`)로 현행 통합 콜 복귀.

## 2. 흐름 타임라인 (KST · 업로드일 D)

운영 크론 정본은 `deploy/compose.yaml`(UTC)이다: MIRROR 19:30 · ANALYZE 20:00 · LATE_BACKFILL_ANALYZE
21:00 · ACCOUNT_ANALYZE 22:00 · BATCH_COLLECT `0 10,40 20-23,0-2`(KST 05:10~11:40 30분 간격).
콘텐츠 분석 전송은 `analytics.analyze-transport=batch`.

### 2-1. 현행

| 시각 | 단계 | 비고 |
|---|---|---|
| D+0 | 게시 | |
| D+1 01~03 | 크롤(열거+지표 캡처) | `content_snapshot_cache`에 첫 스냅샷 |
| D+1 04:30 | 미러 | 후보 뷰는 raw를 직접 읽어 미러와 무관(08-31) |
| D+1 ~ D+3 | **대기** | 성숙 게이트 미충족. 인플루언서 상세엔 ad_type NULL, 랭킹·드로어 부재 |
| D+4 01~03 | 크롤 | D+3 캘린더일 스냅샷이 있어야 timely |
| D+4 05:00 | ANALYZE(timely) 통합 배치 제출 | 사실+해석 1콜 |
| D+4 05:10~ | BATCH_COLLECT 수거·INSERT | `metric_timeliness` timely 확정 |
| D+4 06:00 | LATE_BACKFILL_ANALYZE(늦크롤·최근 12 윈도우) | 마킹 late_backfill |
| D+4 07:00 | 계정 카피 | 콘텐츠 사실 파생 뷰를 입력으로 |

### 2-2. 신 흐름

| 시각 | 단계 | 비고 |
|---|---|---|
| D+1 01~03 | 크롤 | 무변경 |
| D+1 04:30 | 미러 | 무변경 |
| D+1 05:00 | **FACT_ANALYZE(파트 A) 배치 제출** | 후보 = 파트 A 뷰(§4-1) − 기존 행. 캡션·`ad_marked`만 입력 |
| D+1 05:10~ | BATCH_COLLECT 수거 → `content_analyses` INSERT | 사실 채움, 해석·기준선 NULL, `metric_timeliness='pending'` |
| D+1 오전 | **화면**: 인플루언서 상세 ad_type·카테고리 표시. 발굴 파생 MV(뷰티 비율·카테고리 믹스·협찬 수) 반영 | 랭킹·드로어 노출 규칙은 §4-3 |
| D+1 07:00 | 계정 카피 | 무변경으로 콘텐츠 사실이 3일 일찍 입력됨 |
| D+4 05:00 | **ANALYZE(파트 B, timely)** 배치 제출 | 후보 = 04 뷰(성숙) ∩ A 행 존재 ∩ B 미완 ∩ 댓글 게이트 통과 |
| D+4 05:10~ | BATCH_COLLECT 수거 → 해석 5필드+기준선 UPDATE, `metric_timeliness` timely 확정 | 랭킹 진입 시점은 현행과 동일 |
| D+4 06:00 | **LATE_BACKFILL_ANALYZE(파트 B, 늦크롤)** | 마킹 late_backfill |

파트 A와 파트 B는 `JobName`이 달라 `JobLock`이 잡별로 독립이므로 같은 시각에 걸어도 서로 막지 않는다.
크론은 A 05:00 · B 05:30 · 늦크롤 B 06:00으로 15~30분 간격을 두는 것을 기본값으로 한다(compose 변경).

> **구현 시 정정(2026-09-03)**: 배치 제출 직전 pending 수거는 `ContentAnalysisJob.runQuery`가
> `ContentBatchCollectJob.run()`을 **직접 호출**한다 - `JobLock`을 거치지 않는다(스케줄러가
> `JobName.BATCH_COLLECT`에 거는 락은 `AnalyticsJobService` 경유 호출에만 적용되고, 이 인라인
> 호출은 그 경로를 타지 않는다). 즉 "BATCH_COLLECT 락에서 한쪽이 BUSY로 건너뛴다"는 원안의 설명은
> 틀렸다 - FACT_ANALYZE(05:00) 직전 스윕·정기 BATCH_COLLECT(05:10)·ANALYZE(05:30) 직전 스윕이
> 겹치면 **셋 다 그대로 실행**된다. 데이터는 안전하다(INSERT는 `ON CONFLICT DO NOTHING`, UPDATE는
> 멱등) - 대가는 같은 배치를 최대 3번 중복 조회하는 비용뿐이다(수용, §9-5). 이 스윕은 `resolveTargets`
> **앞에서** 실행되도록 순서가 고정됐다(2026-09-03 리뷰 C1) - 순서가 뒤바뀌면 방금 수거해 timely로
> 확정한 short_code가 여전히 SYNTHESIS 대상 목록에 남아 빈 "확인된 사실"로 재제출될 수 있어서다.
> 2차 방어선으로 `requireStoredFacts`(제출 직전 storedFacts 재조회 후 없는 대상을 드롭 + 경고 로그)가
> 붙었다.

## 3. 데이터 계약 - `content_analyses` 행의 상태 전이

행은 여전히 `short_code` 1행이다. 컬럼 추가 없이 **기존 컬럼의 채움 조합**으로 상태를 표현한다.

| 상태 | 판별식 | 사실(파트 A) | 해석 5필드·기준선 10컬럼 | `metric_timeliness` | `synthesis_version`·`synthesized_at` |
|---|---|---|---|---|---|
| (없음) | 행 없음 | | | | |
| **A만** | `metric_timeliness = 'pending'` | 채움 | NULL | `'pending'` | NULL |
| **A+B** | `synthesized_at IS NOT NULL` | 채움 | 채움 | `timely` / `late_backfill` | `Synthesis.VERSION`·시각 |
| 레거시(통합 콜) | 위와 동일 형태 | 채움 | 채움 | timely/late_backfill/immature/NULL | 1 또는 NULL |

- **A만 → A+B 전이는 UPDATE 1회**(`ContentAnalysisWriter.updateSynthesis` 확장: 기존 5필드+기준선
  10컬럼+model+version에 **`metric_timeliness` SET 추가**). 파트 A 컬럼은 건드리지 않는다.
- **A만 상태의 정본 판별은 `metric_timeliness = 'pending'`** 하나로 통일한다. `synthesis_version IS NULL`은
  V38 이전 레거시 행(해석은 있으나 버전 미기록)과 겹치므로 상태 판별에 쓰지 않는다.
- `analyzed_at`은 파트 A INSERT 시각(DEFAULT now())으로 두고 파트 B에서 갱신하지 않는다. "분석 완료
  시각"이 필요한 소비처는 `synthesized_at`을 본다.
- `model`은 파트 A 모델로 INSERT되고 파트 B UPDATE가 덮는다(현 `updateSynthesis` 동작 유지 - 둘 다
  같은 모델이라 실질 무의미, 기록 규칙만 명시).
- 파트 A 행에 기준선 스냅샷을 넣지 않는 이유: D+1 기준선은 미성숙 지표를 포함해 화면(드로어 벤치마크)에
  하향 편향을 주고, 어차피 파트 B가 D+4 기준선으로 덮는다. `V1ContentReportAssembler.comparableMetric`은
  `metric_timeliness`가 timely 또는 NULL일 때만 비교 블록을 만들므로 `'pending'`이면 자동 억제된다.

## 4. 변경점

### 4-1. 후보 뷰 (`analytics/views/04_analysis_candidates.sql`)

현재 04는 안쪽 서브쿼리(`OFFSET 0` 배리어) 안에서 캡션·성숙 가드를 WHERE로 자르고 timely·in_window를
계산한 뒤, 바깥에서 `timely OR in_window`를 건다. 변경:

1. 안쪽 서브쿼리에서 **성숙 WHERE를 제거하고 `mature boolean` 컬럼으로 승격**한다(식은 동일:
   `업로드일 + pin + slack <= 오늘`).
2. 새 뷰 **`analytics.v_fact_candidates`**(파트 A 입구) = 배리어 바깥에서
   `WHERE NOT mature OR timely OR in_window`. 컬럼은 04와 동일 + `mature`. `timely`도 그대로 노출한다
   (진단용. A 잡은 안 읽는다).
3. **`analytics.v_analysis_candidates`는 `SELECT (기존 컬럼) FROM v_fact_candidates WHERE mature`**로
   재정의한다. 컬럼·행 집합·`timely` 의미가 현행과 동치라 기존 소비자(파트 B 잡·`pending.sh`)는
   무수정이다.

> **구현 시 정정(2026-09-03)**: 어드민 퍼널 `PipelineStatsService`는 "무수정"이 아니다 - 이 트랙에서
> **`v_fact_candidates`를 조건 없이 직접 읽도록** 바뀌었다(`SELECT short_code, timely, mature FROM
> v_fact_candidates`, 미성숙(mature 후보 뷰(`v_analysis_candidates`)에 있는 rn=1)까지 노출해
> "미성숙 풀" 지표를 계산한다). 따라서 **04 뷰(`v_fact_candidates` 포함)를 코드 배포보다 먼저
> 적용해야 하는 순서 의존성이 하드하다** - 뷰 적용 전에 이 코드가 배포되면 `v_fact_candidates`가
> 없어 어드민 퍼널 화면이 즉시 깨진다(`deploy/README.md` §17 배포 순서 ①→③ 참고). 또한 미성숙 행에도 timely
> LATERAL(`content_snapshot_cache` EXISTS 세미조인)이 계산되므로, 어드민 경로도 파트 A 잡과 같은
> 뷰 비용 증분(§4-1 플랜 주의)을 그대로 진다.

왜 "성숙 무관 전량"이 아니라 `NOT mature OR timely OR in_window`인가: 성숙했는데 timely도 아니고 최근
12 윈도우 밖인 게시물은 현행에서도 영구 제외 대상이다. 이걸 파트 A에 열면 운영 백로그(계정당 12개
밖 옛 게시물 수만 건)가 한 번에 후보가 된다. 신규 게시물은 D+1에 rn=1이라 `NOT mature`로 잡히므로
"제때창을 놓쳐 영구 제외되던 게시물"(§7 부수 효과)은 이 규칙만으로 전부 파트 A가 채워진다. 옛 백로그
개방은 별도 결정(§9).

플랜 주의: 미성숙 행에도 timely LATERAL(EXISTS on `content_snapshot_cache`)이 계산된다. 미성숙 행은
최근 3일치(일 ~3,500건 × 3)뿐이고 EXISTS는 인덱스 세미조인이라 비용 증분은 작지만, 07-20의 배리어
회귀 전례가 있으므로 **적용 후 실데이터 EXPLAIN으로 04 뷰 실행 시간을 재확인**한다(기준 ~150ms 대,
9초대로 튀면 배리어 무력화).

첫 배포일에는 미성숙 3일치(D+1~D+3, 약 1만 건)가 한꺼번에 파트 A 후보가 된다. 배치 청크 상한
(`analytics.batch-chunk-size` 3,000)으로 자동 분할되며 1회성이다.

### 4-2. `metric_timeliness` 어휘 - `'pending'` 추가

V33 CHECK는 `('timely','late_backfill','immature')`다. 파트 A 시점 값의 후보 세 가지를 비교했다.

| 안 | 장점 | 단점 |
|---|---|---|
| NULL | 마이그레이션 없음 | **랭킹 6.1·카테고리 벤치마크(6.3)가 NULL을 "레거시 timely"로 취급해 노출**(`= 'timely' OR IS NULL`). 미성숙 지표 행이 랭킹에 들어가 하향 편향 - V33이 막으려던 것 |
| `'immature'` 재사용 | 마이그레이션 없음, 랭킹 제외 | V33 정의는 "가드 도입 전 영구 고정 누수"(종결 상태). 전이 상태로 쓰면 어드민 퍼널 `immaturePool`·`pending.sh` 레거시 집계가 오염 |
| **`'pending'` 신설(채택)** | 랭킹 6.1·6.3·assembler 모두 무수정으로 제외(timely도 NULL도 아님). 의미가 정직("지표 시점 미확정") | CHECK 재정의 마이그레이션 1건 |

마이그레이션은 CHECK 확장(DROP CONSTRAINT + ADD CHECK). `check-migration-safety.sh`의 파괴 패턴
(DROP TABLE/COLUMN·RENAME·타입 변경·SET NOT NULL)에 해당하지 않고, 롤링 창에서 구 코드는 `'pending'`을
쓰지 않으며 읽어도 제외 분기로 떨어지므로 expand 단계 안전.

**소비처 계약** (`grep -rn metric_timeliness --exclude-dir=docs` 전수):

| 소비처 | 현행 | `'pending'` 행 | 변경 |
|---|---|---|---|
| was `V1ContentRepository` 6.1 랭킹 | `= 'timely' OR IS NULL` | 제외 | 없음 |
| was `V1ContentReportRepository` 6.3 카테고리 벤치마크 | 동일 필터 | 제외 | 없음 |
| was `V1ContentReportAssembler.comparableMetric` | NULL 또는 timely만 비교 | 비교 블록 억제 | 없음 |
| was `V1/V2InfluencerReportRepository.findSeries` | 필터 없음, ad_type만 | **D+1부터 ad_type 표시**(목표) | 없음 |
| analysis DB 파생 뷰·MV(`account_category_stats`·`account_beauty_ratio`·`account_category_share`·`account_sponsored_counts`) | 필터 없음(is_beauty·main_category·ad_type만) | D+1부터 집계 포함 | 없음(`DERIVED_INPUT_JOBS`에 FACT_ANALYZE 추가해 수거 후 REFRESH) |
| analytics `ContentAnalysisWriter.insert` | timely/late_backfill 기록 | 파트 A는 `'pending'` | `insertFacts` 신설(§4-4) |
| analytics `ContentSynthesisRefreshJob` | `synthesis_version IS DISTINCT FROM ?` | **A만 행을 잡아 온라인으로 파트 B를 돌려버림** | `AND metric_timeliness IS DISTINCT FROM 'pending'` 가드 추가(재생성 전용 유지) |
| analytics 어드민 퍼널 `PipelineStatsService`·`AdminUiController`(immature·NULL 레거시 집계) | 행 존재=기분석 | A만 행이 "기분석"으로 잡힘 | "사실만(pending)" 칩 분리 |
| `analytics/check/pending.sh` | `_analyzed(short_code, metric_timeliness)` | 20/30 "기분석"에 A만 포함 | 상태 `'pending'`을 "사실만·해석 대기" 코드로 분리(11/24/34/42) |
| analytics `/ui/coverage` `CoverageRepository` copy3·cauth | `count(ai_content_summary…)` | 분모 대비 상시 "일부 누락" | 파트 B 행만 분모로(`synthesized_at IS NOT NULL`) 또는 A/B 행 분리 표기 |
| 하입 스코어 | raw 뷰(02)에서 계산, `content_analyses` 무관 | 영향 없음 | 없음 |
| `v_analysis_candidates.timely` | raw 스냅샷으로 판정, 마킹과 무관 | 영향 없음 | 없음 |

> **구현 시 정정(2026-09-03)**:
> - `ContentSynthesisRefreshJob`의 가드는 `<>`가 아니라 **`IS DISTINCT FROM 'pending'`**(NULL-safe)로
>   구현됐다. `metric_timeliness`가 NULL인 레거시 행(V38 이전, 해석은 있으나 버전 미기록)에서 `<>`는
>   NULL과 비교해 UNKNOWN이 되어 그 행이 재생성 대상에서 조용히 빠지는데, `IS DISTINCT FROM`은 NULL을
>   "pending과 다름"으로 정확히 판정해 레거시 행도 그대로 대상에 남는다.
> - `CoverageRepository`는 "파트 B 행만 분모"가 아니라 **분자·분모를 동일 모집단으로 통일**했다 -
>   copy3(18행)·baseline(19행, §4-3 소개 이후 신설된 행)·cauth(22행) 세 지표 모두
>   `content_analyses WHERE metric_timeliness IS DISTINCT FROM 'pending'` 서브쿼리(`anb`)를 분모로
>   쓴다(분자도 같은 서브쿼리에서 집계). 즉 분자·분모가 같은 "파트 B 완료분" 모집단이 되어, 배포
>   즉시 이 세 행은 (분리 자체와 무관하게) "준비됨"으로 뛴다 - 이전에는 `contents` 미러 전체가
>   분모라 파트 B 미완 행이 항상 분모에 섞여 있었다.
> - **`ContentAnalysisWriter`는 파트 B 배치/온라인 쓰기를 `updateSynthesisPending`(WHERE
>   `metric_timeliness = 'pending'`)으로 추가 가드한다** - `updateSynthesis`는 재생성 잡 전용으로
>   남기고(WHERE 없음, "이미 확정된 행"도 갱신 가능해야 하므로), 배치 SYNTHESIS 수거 경로는 새
>   메서드로 "아직 pending인 행만" UPDATE해 늦게 도착한 파트 B 배치가 이미 완료된 행을 덮어쓰지
>   못하게 한다(§4-7 롤백 순서와 연동).
> - `pending.sh`의 상태 코드 우선순위(랭킹·상세 트랙 분기): 기분석(20/30, 파트 B 완료) → 댓글 게이트
>   보류(22/32) → 미러 갭(23/33) → **사실만(24/34)** → 분석 대기(21/31) 순으로 판정한다. 즉 파트 A 행이
>   있어도 댓글 게이트나 미러 갭에 걸려 있으면 그 차단 사유(22/23)로 집계되고 "사실만"으로 뭉개지지
>   않는다 - split 모드에선 거의 모든 후보가 파트 A 행을 가지므로, 차단 사유가 상위 정보다(최종 리뷰
>   반영). 11(미성숙·사실만)·42(영구 제외·사실만)는 각 분기에서 그대로 최상단이다.

### 4-3. was 노출 규칙의 변화 (FE 계약 영향)

- **6.1 랭킹**: 무변경. 파트 B 완료(timely) 시점부터 노출 - 현행과 같은 D+4.
- **6.3 드로어 `GET /v1/contents/{shortCode}`**: 현행은 분석 행이 없으면 404. 신 흐름에서는 **D+1부터
  200이 되며 비교 블록 자체는 항상 내려간다 - 그중 특정 필드만 null**이다. FE가 null 문구를 "해석 준비
  중"으로 렌더링하는지 확인이 필요하다(§9 미해결 1). 필요하면 was가 `'pending'` 행을 404로 유지하는
  옵션도 가능하나, 광고 판정·브랜드를 드로어에 먼저 보여주는 것이 이 트랙의 목적이므로 기본은 200.
- **6.4 / v2 인플루언서 상세 최근 콘텐츠**: ad_type·카테고리가 D+1부터 채워진다(목표 증상 해소).
- 계약 문서 `docs/contracts/`에 6.3 응답의 null 가능 필드 목록을 명시한다(09-02 지침: FE 노출 변경은
  계약 문서 동반).

> **구현 시 정정(2026-09-03)**: "해석 5필드·기준선·비교 블록이 null"은 부정확하다 - **비교 블록
> (`comparison`)은 항상 내려가고, 그 안에서 개별 필드만 null**이다. null인 필드는 `aiContentSummary`,
> `comparison.narrative`, `comparison.engagementRate.baseline`,
> `comparison.engagementQuality.likes.baseline`/`.comments.baseline`, `commentAnalysis.insight`,
> `commentAnalysis.signals.authenticity.grade`/`.note`, `categoryContext.percentile`뿐이다.
> `comparison.views`(조회수·순위·최근 릴스 차트)는 라이브 재계산이라 D+1부터 채워진다(파트 B 지표가
> 아니라 별도 소스). 전체 계약은 `docs/contracts/v1-content-report-nullable-fields.md`가 정본이다.
>
> **추가로 원안에 없던 사실**: 성숙했으나 timely도 아니고 최근 12 윈도우도 벗어나 파트 B 후보가 영영
> 안 되는 콘텐츠(§4-1 "영구 제외", `pending.sh` 42/34 중 42)는 위 null 필드가 **"D+4까지"가 아니라
> 영구히** null로 남는다. 6.3은 이 경우에도 계속 200을 주므로, FE 자리표시 문구가 "곧 채워짐"을
> 암시하면 안 된다(계약 문서 §롤백/§영구 null 절 참고).

### 4-4. Java - analytics

**`ContentAnalysisJob`에 phase 축 추가** (새 클래스 대신 - 후보 조회·제외 게이트·기준선 로딩·배치
제출·429 이월이 이미 여기 있고, 파트 B 배치도 같은 배관을 탄다):

```
enum Phase { UNIFIED, FACTS, SYNTHESIS }
JobName.FACT_ANALYZE           → FACTS      (신설, timely 무관)
JobName.ANALYZE                → mode=split ? SYNTHESIS(timely=true)  : UNIFIED(timely=true)
JobName.LATE_BACKFILL_ANALYZE  → mode=split ? SYNTHESIS(timely=false) : UNIFIED(timely=false)
```

`analytics.analyze-mode`(app_setting, 잡 시작마다 읽음)가 `unified`면 FACT_ANALYZE는 no-op 로그만
남기고 끝난다.

> **구현 시 정정(2026-09-03)**: `runFacts()`는 `runQuery(Phase.FACTS, timely=false, ...)`를 호출한다.
> FACTS에는 timely 개념이 없어(파트 A는 성숙 무관 후보를 전량 대상으로 한다) 이 `false`는 의미 없는
> 자리값이다. `content_batch_jobs.timely` 컬럼에도 FACTS 청크는 `false` 고정으로 쓰이고 수거 시
> 읽히지 않으며, 로그는 `timely=n/a`로 표기해 "timely=false"로 오인되지 않게 한다
> (`ContentAnalysisJob.timelyLogValue`).

**`resolveTargets(phase, timely)`**:

| phase | 후보 소스 | 제외 |
|---|---|---|
| UNIFIED(현행) | `v_analysis_candidates WHERE timely = ?` | ① 행 존재 ② 댓글 미분류 |
| FACTS | `v_fact_candidates`(timely 무관) | ① 행 존재(A만 포함 - 어떤 상태든) |
| SYNTHESIS | `v_analysis_candidates WHERE timely = ?` | ① **A 행 부재**(다음 A 수거 후 자연 재대상) ② **B 완료**(`metric_timeliness <> 'pending'`) ③ 댓글 미분류 |

SYNTHESIS의 "B 완료" 제외는 `SELECT short_code FROM content_analyses WHERE metric_timeliness = 'pending'`
집합(부분 인덱스로 좁힘)을 **포함 집합**으로 쓰는 편이 싸다: 후보 ∩ pending 집합 − 댓글 게이트.

**프롬프트·파서**:

- 파트 A: `GeminiContentAnalyzer`에 `instructions(tx, includeSynthesis=false)`·`RESPONSE_SCHEMA_FACTS`
  (통합 스키마에서 파트 B 5필드 제거)·`userTextFacts`(콘텐츠·캡션·유료 파트너십 태그 3줄, 지표·기준선·
  댓글 분포 줄 삭제)를 추가한다. 파싱은 기존 `parse()`의 `ContentAttributes` 부분만. MAX_OUTPUT은
  8192 유지(사실 배열이 출력의 대부분이라 여유를 줄일 근거가 없음 - §8 실측 참고).
- 파트 B: `GeminiContentSynthesizer.instructions()`·`userText(ContentToSynthesize)`를 그대로 배치
  JSONL로 싣는다. "확인된 사실"은 A 행에서 `ContentSynthesisRefreshJob.facts()`와 같은 9키로 조립.
  지표(views/likes/comments)는 04 뷰의 핀 지표, 기준선은 `BaselineLoader`(raw), 댓글 분포는 현행
  경로(댓글 미수집이라 대개 빈 값). 응답 파싱은 `GeminiContentAnalyzer.parseSynthesis()`.
- `GeminiBatchLines`에 phase별 request line 조립을 추가한다(sidecar에 phase·timely·기준선·사실 스냅샷).

**`ContentAnalysisWriter`**:

- `insertFacts(...)`: 파트 A 컬럼 + `is_beauty` + `metric_timeliness='pending'`, 나머지 NULL,
  `ON CONFLICT (short_code) DO NOTHING`(A 잡 중복 제출 방어).
- `updateSynthesis(...)`에 `metric_timeliness = ?` SET 추가. 재생성 잡(`ContentSynthesisRefreshJob`)은
  기존 값을 그대로 넘기면 된다(동작 불변). `WHERE short_code = ? AND metric_timeliness = 'pending'`
  같은 조건은 걸지 않는다 - 재생성 잡이 같은 메서드를 쓰기 때문.

**`ContentBatchCollectJob`**: `content_batch_jobs.kind`(§4-5)로 분기 - `analyze`(레거시 통합, 현 파서),
`facts`(→ `insertFacts`), `synthesis`(→ `updateSynthesis`, sidecar의 timely로 마킹). 배치 상태 전이
(pending → collected/failed)는 동일. `DERIVED_INPUT_JOBS`에 `FACT_ANALYZE` 추가(수거 후 발굴 MV
REFRESH는 BATCH_COLLECT가 이미 트리거하므로, 실제 갱신은 수거 시점에 일어난다 - 온라인 폴백 대비).

**온라인 폴백**: `analyze-transport=online`이거나 배치 미지원 프로바이더면 phase별 온라인 루프
(FACTS는 `api.generateJson` 캡션 전용, SYNTHESIS는 `ContentSynthesisPort.synthesize`). 썸네일 첨부
(`thumbnailEnabled`)는 파트 A 온라인 경로에만 의미가 있다(사실 추출이 썸네일 소비자). 배치 시 온라인
폴백 규칙은 현행 유지.

**`ContentSynthesisRefreshJob`**: 대상 SQL에 `AND metric_timeliness IS DISTINCT FROM 'pending'`
추가(NULL-safe - `<>`는 레거시 NULL 행을 조용히 빠뜨린다, 위 정정 참고). 역할은 "이미 해석이 있는
행의 재생성"으로 한정한다(온라인 유지).

### 4-5. `content_batch_jobs` - 파트 구분 컬럼

수거 잡이 응답 스키마를 알아야 하므로 컬럼이 필요하다.

```sql
ALTER TABLE content_batch_jobs ADD COLUMN kind text NOT NULL DEFAULT 'analyze'
    CHECK (kind IN ('analyze', 'facts', 'synthesis'));
```

DEFAULT `'analyze'`라 구 코드가 제출한 pending 행(롤링 창·롤백 직후)은 신 수거 잡이 통합 파서로 처리한다.
`timely` 컬럼은 `facts`에서 의미가 없으므로 false 고정으로 넣고 무시한다(NOT NULL 유지 - 컬럼 완화는
contract 단계 얘기라 안 한다). `batch_name` 접두사는 `hypenow-facts-`/`hypenow-synth-`로 구분해 GCS
콘솔에서도 식별 가능하게 한다.

### 4-6. 마이그레이션 (전부 expand, UTC 타임스탬프 채번 `date -u +%Y%m%d%H%M%S`)

| 버전 공간 | 파일 | 내용 |
|---|---|---|
| analytics `db/migration/analysis` | `V<UTC>__content_analyses_timeliness_pending.sql` | `metric_timeliness` CHECK를 `('timely','late_backfill','immature','pending')`로 재정의 + 부분 인덱스 `idx_content_analyses_timeliness_pending ON content_analyses (short_code) WHERE metric_timeliness = 'pending'` |
| analytics `db/migration/analysis` | `V<UTC>__content_batch_jobs_kind.sql` | §4-5 |
| crawler(raw `app_setting` 시드) | `V<UTC>__analytics_analyze_mode.sql` | `('analytics.analyze-mode','unified') ON CONFLICT DO NOTHING` - 기본은 현행. 전환은 운영 UPDATE |
| 뷰 | `analytics/views/04_analysis_candidates.sql` | §4-1. 운영 적용은 뷰 수동 적용 런북(`analytics-prod-view-apply-mirror` 메모리) |
| compose | `deploy/compose.yaml` | `ANALYTICS_SCHEDULE_FACT_ANALYZE_CRON: "0 0 20 * * *"`, ANALYZE를 `"0 30 20 * * *"`로 |

Flyway 채번은 PR 직전 `flyway_schema_history` 최대값 대조(메모리 `flyway-version-collision-check`).
DROP·RENAME·타입 변경 없음. contract 단계(구 `analyze` kind 제거, UNIFIED phase 삭제)는 split 운영이
안정된 뒤 별도 릴리스.

### 4-7. 롤백

`UPDATE app_setting SET value='unified' WHERE key='analytics.analyze-mode'` 한 줄. 다음 잡부터
FACT_ANALYZE는 no-op, ANALYZE/LATE_BACKFILL은 통합 콜로 복귀한다. 재기동 불필요(잡 시작마다 읽음).

롤백이 되돌리지 않는 것: 이미 만들어진 **A만(`pending`) 행**. 통합 잡은 "행 존재"를 제외로 보므로 이
행들은 파트 B를 영영 못 받는다. 롤백 런북에 두 갈래를 적는다 - (a) split 재전환 시 SYNTHESIS가 자연
재대상하므로 그냥 둔다, (b) 통합으로 영구 복귀면 `DELETE FROM content_analyses WHERE
metric_timeliness='pending'`(사용자 확인 후, 파트 A는 다음 통합 잡이 다시 만든다). 뷰·마이그레이션은
롤백 불요(추가만이라 구 코드와 호환).

> **구현 시 정정(2026-09-03)**: (b) 영구 복귀 갈래는 **DELETE 전에 진행 중인 배치를 먼저 취소**해야
> 한다 - 순서는 (1) 토글을 `unified`로 되돌린다 (2)
> `UPDATE content_batch_jobs SET status='failed', note='롤백 취소', sidecar_jsonl=NULL WHERE
> status='pending' AND kind IN ('facts','synthesis')`로 아직 수거되지 않은 파트 A/B 배치를 죽인다
> (3) 그 다음에야 `DELETE FROM content_analyses WHERE metric_timeliness='pending'`을 실행한다.
> 이 순서가 필요한 이유: `ContentAnalysisWriter`는 파트 B 배치/온라인 쓰기를 `updateSynthesisPending`
> (`WHERE metric_timeliness = 'pending'`)으로 가드하므로, DELETE **이후에** 늦게 도착한 파트 B 배치가
> 수거되면 이미 지워진 행에 대한 UPDATE는 0행으로 조용히 무시된다(안전). 반대로 늦게 도착한 **파트 A**
> 배치(`insertFacts`, `ON CONFLICT DO NOTHING`)는 DELETE 이후 수거되면 방금 지운 것과 같은
> `short_code`로 **고아 pending 행을 다시 만들어 버린다** - 통합 잡이 이후 이 행을 "이미 분석됨"으로
> 보고 건너뛰므로 조용히 재발한다. (2)에서 pending 배치를 먼저 취소하는 것이 이 재발을 막는 유일한
> 방어선이다.

## 5. 배치 잡 상세 (수거 분기)

```
BATCH_COLLECT
  for job in content_batch_jobs WHERE status='pending':
    state = getBatch(job.batch_name)
    SUCCEEDED →
      switch job.kind:
        'analyze'   → 현행: parse → ContentAnalysisWriter.insert(…, sidecar.timely ? timely : late_backfill)
        'facts'     → parseFacts → insertFacts(…, 'pending')          [ON CONFLICT DO NOTHING]
        'synthesis' → parseSynthesis → updateSynthesis(…, sidecar.timely ? timely : late_backfill)
                      (0행 갱신이면 warn - 행이 사이에 사라진 경우)
      status=collected, sidecar_jsonl=NULL, 파생 MV REFRESH
    FAILED/CANCELLED → status=failed (재시도 없음 - 다음 날 후보 diff가 자연 재대상)
    RUNNING → no-op
```

sidecar(JSONL, DB 저장)에 phase별로 싣는 것: `facts` = short_code·is_beauty 판정에 필요한 어휘 버전;
`synthesis` = short_code·timely·기준선 스냅샷 10컬럼(제출 시점 고정 - 현행과 같은 이유로 수거 시점
재계산 금지). 현행 sidecar 구조(`GeminiBatchLines.sidecarLine`) 확장.

## 6. 테스트 계획

**뷰 SQL 하니스** `analytics/test/04_analysis_candidates.test.sql` (기존 케이스 전부 유지 + 추가):

- `v_fact_candidates`: 미성숙(어제 업로드 `dummy_rn`) **포함**, 성숙·timely 포함, 성숙·늦크롤·윈도우 안
  포함, 성숙·늦크롤·윈도우 밖 **제외**, 캡션 결측 제외, `mature` 컬럼 값 경계(오늘-3 → false, 오늘-4 → true).
- `v_analysis_candidates`: 기존 기대값 전부 불변(회귀 고정) - 특히 `dummy_rn` 제외·`dummy_op` 제외·
  slack=2 케이스.
- 두 뷰의 컬럼 목록 대조(`v_analysis_candidates`는 `mature` 미노출).

**`:analytics:test`** (Testcontainers):

- `ContentAnalysisWriterTest`: `insertFacts` → 상태 "A만"(pending·해석 NULL·기준선 NULL) →
  `updateSynthesis` → 상태 "A+B"(timely 마킹·version) 전이. 재생성 경로가 기존 마킹을 보존하는지.
- `ContentAnalysisJobTest`: phase별 `resolveTargets` - FACTS는 행 존재만 제외, SYNTHESIS는 A 부재·B 완료·
  댓글 게이트 제외, UNIFIED 현행 동일. `analyze-mode=unified`에서 FACT_ANALYZE no-op.
  **추가: "FACTS는 댓글 게이트를 적용하지 않는다"**(댓글 미분류인 콘텐츠도 파트 A 후보에 포함 -
  댓글 게이트는 SYNTHESIS 전용, `resolveTargets`의 FACTS 분기는 `analyzedShortCodes()`만 본다) +
  **"null 포트는 조용히 no-op이 아니라 크게 실패한다"**(`analyze-mode=split`인데
  프로바이더가 gemini/vertex가 아니라 `factsPort`/`synthesisPort`가 null이면 `IllegalStateException`
  - anthropic 프로바이더에서 split을 켜면 "왜 안 도는지" 알 수 없는 조용한 no-op 대신 잡이 명시적으로
  죽는다).
- `ContentBatchCollectJobTest`: kind 3종 분기, `synthesis` 0행 갱신 경고, kind 기본값 `analyze` 호환.
- `ContentSynthesisRefreshJobTest`: pending 행 미대상.
- `GeminiContentAnalyzerTest`: `RESPONSE_SCHEMA_FACTS`에 파트 B 키 없음, `userTextFacts`에 지표 줄 없음.
- 마이그레이션: CHECK가 `'pending'` 허용·기타 값 거부.

**was**: `V1ContentRepositoryTest`에 `'pending'` 행 랭킹 제외 케이스 1건(기존 late_backfill 케이스 복제).
6.3 드로어는 pending 행 200 + null 필드 케이스 1건.

**운영 검증(승격 후)**: 첫 FACT_ANALYZE 다음 날 아침 `pending.sh`로 "사실만" 건수 = 전날 크롤 신규분,
`SELECT count(*) FROM content_analyses WHERE metric_timeliness='pending'` 추이가 3일 후 정체(D+4마다
B로 빠짐), 04 뷰 EXPLAIN 시간.

## 7. 부수 효과

- **커버리지 잔여 해소**: 제때창(D+3)을 놓치고 최근 12 윈도우도 벗어나 영구 제외되던 게시물
  (커버리지 잔여 8~15%)도 D+1에 파트 A가 채워진다. 파트 B는 여전히 안 채워지므로 이 행들은
  `'pending'`으로 남는다 - 랭킹 비노출은 현행과 같고, 인플루언서 상세·발굴 집계엔 사실이 반영된다.
- **계정 카피 3일 단축**: `AccountAnalysisJob`은 `content_analyses`를 직접 읽지 않고 파생 뷰
  (`account_category_stats` 등 사실 컬럼 집계)를 입력으로 쓰므로 무변경으로 3일 일찍 최신 게시물이
  반영된다. 재대상 조건(`input_last_posted_at` 변경 + 쿨다운)은 그대로.
- **발굴 게이트 가속**: `account_beauty_ratio`(분석 8건 이상·뷰티 20% 이상)가 파트 A 행을 세므로 신규
  계정의 발굴 노출이 3일 빨라진다.
- **광고 표기·브랜드 모니터링**: monitoring 모듈은 자체 판정(`brand_tagged_post`)이라 무관.

## 8. 비용

조회수 수집(크롤러·`v_pinned_metrics`)은 무변경. 증가분은 **파트 B 콜 1회분**이고, 통합 콜이 파트 A 콜로
줄어드는 만큼 일부 상쇄된다. 단가는 Vertex 배치 gemini-3.1-flash-lite 입력 $0.125/M·출력 $0.75/M,
일 ~3,500건 기준.

**실측(09-03 로컬)**: Vertex `countTokens` REST(무과금)로 운영 프로젝트·`gemini-3.1-flash-lite`에 대해
`GeminiContentSynthesizer.instructions()`와 `GeminiContentAnalyzer.instructions(tx)`(로컬 DB 실 시드
분류표 110엔트리·유통사 13) + 합성 유저텍스트(한국어 협찬 리뷰 캡션 ~280자·해시태그 3·지표·최근 12
기준선·댓글 5분류)를 셌다. 출력은 실제 결과 JSON 형태의 대표 샘플. 파트 A 행은 통합에서 파트 B 규칙·
지표 줄을 뺀 **추정**이다(측정값 아님).

| 프롬프트 | 입력 system | 입력 user | 입력 합 | 출력(대표) | 건당 | 일(3,500건) | 월(×30) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 통합(현행, 캡션만) | 2,313 | 348 | 2,661 | 495 | $0.00070 | $2.46 | $73.9 |
| 파트 B(`GeminiContentSynthesizer`) | 548 | 308 | **856** | 244 | **$0.00029** | **$1.02** | **$30.5** |
| 파트 A(추정: 통합 − 파트 B 규칙 − 지표·기준선·댓글 줄) | ~1,950 | ~200 | ~2,150 | ~250 | ~$0.00046 | ~$1.61 | ~$48 |

- **증분**: 파트 B 콜 1회분 그대로면 **+$1.02/일(+$30.5/월)**. 통합 콜이 파트 A 콜로 줄어드는 상쇄를
  넣으면 순증 **약 +$0.2/일(+$5/월)** 수준이다. 어느 쪽이든 월 LLM 예산 안에서 무시할 만한 규모다.
- 1회성: 첫 배포일 미성숙 3일치(~1만 건) 파트 A ≈ $5.
- 썸네일 첨부(온라인 폴백 시 inlineData)는 측정 범위 밖 - 배치 경로는 캡션 전용이라 해당 없음.
- 파트 B system 548 토큰 중 `SYNTHESIS_RULES`·`LlmGuard.RULES`는 통합 프롬프트와 단일 상수를 공유
  한다(복제 없음, 07-21 복제 사고 방지 주석 확인).

## 9. 미해결 질문

1. **FE 6.3 null 렌더링**: 드로어가 D+1에 200으로 오면서 해석 5필드·기준선·비교 블록이 null인 응답을
   FE가 어떻게 그리는지(현재는 404 → "분석 준비 중"으로 추정). 계약 문서 갱신 + FE 확인 후 배포.
   대안: was가 `'pending'` 행을 404 유지(설정 토글) - 목적 대비 후퇴라 기본 아님.
2. **옛 백로그 개방 여부**: 성숙·비timely·윈도우 밖 게시물(계정당 12개 밖)까지 파트 A를 열 것인가.
   열면 1회성 수만 건(파트 A 콜당 비용은 §8), 인플루언서 상세 최근 콘텐츠는 어차피 12개라 화면 이득은
   발굴 집계뿐. 기본은 안 연다.
3. **파트 A 정확도 회귀 여부**: 통합 프롬프트에서 지표·기준선 줄이 빠진 입력으로 사실 판정이 달라지는지.
   설계상 파트 A 규칙은 캡션만 근거로 하지만, 골드셋(광고 표기 트랙 골드셋 재사용) 20~30건으로 통합 vs
   분리 출력을 대조하고 나서 split 전환한다.
4. **파트 B 댓글 분포 입력**: 댓글 수집이 MVP 제외라 분포가 빈 값인데, `aiCommentInsight`·
   `commentAuthenticityGrade`가 빈 입력에서 무엇을 내는지는 현행과 동일 문제(이 트랙 범위 밖). 파트 B
   프롬프트를 손볼 기회이므로 "댓글 분포 없음이면 해당 항목은 '데이터 없음'으로" 규칙 추가 여부.
5. **크론 간격**: ✅ **확정(구현 반영)** - A(FACT_ANALYZE) 05:00 · B(ANALYZE) 05:30 · 늦크롤 B
   (LATE_BACKFILL_ANALYZE) 06:00 KST(`deploy/compose.yaml`). 정기 BATCH_COLLECT(05:10~11:40 30분
   간격)가 A와 B 사이(05:10)에 낀다. 겹침은 BUSY로 스킵되는 게 아니라(§2 정정 참고) 셋 다 그대로
   실행되는 것으로 수용 확정 - 비용은 중복 다운로드뿐, 데이터는 멱등이라 안전.
6. **어드민 `/ui` 표기**: 잡 카드에 FACT_ANALYZE 추가, 퍼널에 "사실만" 칩 - 범위에 포함하되 화면 설계는
   구현 시 결정.
7. **contract 단계 시점**: `kind='analyze'`·UNIFIED phase·`analyze-mode` 토글 제거는 split 2주 안정 후.

## 10. 검토했다 접은 대안

- **D+0 무LLM 라벨**(정규식·`ad_marked`로 임시 광고 표시 후 LLM이 덮어쓰기): 표시 값이 하루 뒤 바뀌는
  이중 상태가 FE·사용자 혼란을 부르고, 파트 A가 D+1이면 하루 차이라 이득이 작다. 안 한다(사용자 확정).
- **파트 B 템플릿화**(성과 문구를 수치 템플릿으로): 비용은 0이지만 문구 품질 하락, 파트 B 콜 비용이
  §8 수준이라 정당화 안 됨. LLM 유지(사용자 확정).
- **`ContentSynthesisRefreshJob`을 그대로 D+4 크론에 거는 안**: 온라인 전용(일 3,500건을 동기 호출),
  성숙 가드 없음, `contents` 미러 폴백 경로가 raw 후보 뷰 규칙과 이원화(07-28의 수식 이원화 사고 재현).
  재생성 도구로 남기고 배치 경로를 따로 둔다.
- **`metric_timeliness` NULL 또는 `'immature'` 재사용**: §4-2.
- **04 뷰에 `mature` 컬럼만 추가하고 소비자가 필터**: 기존 소비자 3곳(잡·pending.sh·어드민 퍼널)이
  전부 `AND mature`를 붙여야 하고 하나라도 빠지면 미성숙 행이 파트 B 후보로 새어 07-28 계열 사고.
  뷰를 둘로 나눠 기존 이름의 의미를 고정하는 쪽을 택했다.
