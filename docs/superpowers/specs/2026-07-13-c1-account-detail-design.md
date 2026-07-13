# 태스크 C1 — 인플루언서 상세 비LLM 집계 설계

> 상태: 🟢 활성 — C1 구현·E(인플루언서 API)의 데이터 계약 기준
2026-07-13 (설계 세션 기록)

- 구조 기준: [ARCHITECTURE.md](../../../ARCHITECTURE.md) §4 (타입 미러·contract-analysis·로직의 자리)
- 기준 기획: 상세 분석 확정안 Artifact의 **인플루언서 상세 v4** (2026-07-10,
  https://claude.ai/code/artifact/696bd39e-0f53-4bc5-916e-d4adbf013658)
- 상세 초안: [plans/2026-07-10-plan2-influencer-detail-api.md](../plans/2026-07-10-plan2-influencer-detail-api.md) —
  지표 산식·더미 기대값·임계값 설계를 재사용한다. 단 미러 방식·네이밍·ER 정의는 본 문서가 우선.

## 1. 범위

인플루언서 상세 v4의 **비LLM 블록 전부**를 한 번에: 헤더 프로필(팔로워 구간 포함)·정체성(카테고리/형식
비중·게시 빈도)·성과(평균·중앙값·도달 효율·구간 포지션·모멘텀)·일관성(히트율·변동성·최저 성과)·
커머셜(광고 비율·광고 vs 비광고·협업 이력 골격·광고 간격) + 1:N 자식 2종(차트용 게시물 시계열·협업 이력).

**범위 밖**: 페르소나·AI 브리핑·브랜드 적합성·광고유형 라벨(C2), 브랜드/제품 감지 결과(B3 산출물 —
협업 이력에의 병합은 C2에서 additive), 서빙 API(E — 다른 세션).

## 2. 핵심 결정

1. **미러 구조 = 평탄 자식 테이블 (A안).** 배열성 데이터(비중·시계열·이력)는 jsonb 내장이 아니라
   자식 미러 테이블로 푼다. 근거: 현행 `MirrorJob`은 플레인 `setObject` insert라 jsonb 컬럼을 못 옮기고,
   공용 미러 기계 수정은 다른 세션과의 충돌 지점이며, 배열 조립은 was 코드 몫(§4-4)이라 평탄 행이 원칙에도 맞다.
2. **ER = (likes+comments)/views — B3와 동일 정의.** plan2 초안은 followers 분모였으나
   `03_analysis_baseline.sql`("노션 확정안" 주석)과 D의 was 어셈블러가 views 분모를 확정했다.
   제품 전체에서 "참여율"은 한 가지 정의만 존재해야 하므로 C1도 따른다.
   → 피드(views NULL)·views 0은 ER NULL. ER 파생 지표(평균·중앙값·구간 포지션)는 NULL 전파 규칙 필수.
   plan2의 ER 관련 기대값은 재산출하고, 조회수 기반 기대값(평균·CV·모멘텀·히트·커머셜)은 그대로 재사용.
3. **번호대 예약**: 뷰 파일 `10_account_detail.sql`, Flyway `V10__account_detail_tables.sql`.
   00~03·V1~V3은 사용 중, 한 자릿수 나머지는 다른 트랙 몫으로 비워둔다.
4. **네이밍은 B1 계열(`account_*`)로 통일** — plan2의 `creator_*`는 버린다. 계정 식별자는
   `handle`(자기 자신)·`account_handle`(자식의 참조) — `accounts`·`contents` 관례와 동일.
5. **기존 파일 무수정.** `v_recent_content`에 없는 `followers`·`engagement_rate`는
   10번 파일 안의 밑판 뷰(`v_account_recent`)에서 `v_base_profile` 조인으로 파생한다.
   유일한 공유 파일 접점은 `MirrorConfig` 등록부 5줄 append.

## 3. 뷰 (raw DB `analytics` 스키마 — `analytics/views/10_account_detail.sql`)

### 밑판 (미러 안 함)

- `v_account_recent` — `v_recent_content` + `v_base_profile`(followers) 조인,
  `engagement_rate = round((likes+comments_count)/NULLIF(views,0), 4)` 추가. 이 파일 전용 밑판.

### 서빙 뷰 5종 (미러 1:1 — 컬럼 이름·순서 = Flyway DDL = record)

| 뷰 → 테이블 | 내용 | PK |
|---|---|---|
| `v_account_summaries` → `account_summaries` | 계정 1행 스칼라 전부 (아래 컬럼 명세) | `handle` |
| `v_account_category_stats` → `account_category_stats` | 카테고리 비중: `account_handle, main_group, content_count, share_pct, avg_views` | `(account_handle, main_group)` |
| `v_account_format_stats` → `account_format_stats` | 형식 비중: `account_handle, content_type, content_count, share_pct, avg_views` | `(account_handle, content_type)` |
| `v_account_content_series` → `account_content_series` | 차트용 시계열: `short_code, account_handle, posted_at, content_type, views, is_hit` | `short_code` |
| `v_account_ad_history` → `account_ad_history` | 협업 이력 골격(광고 게시물만): `short_code, account_handle, posted_at, main_group, views, is_hit` | `short_code` |

`account_summaries` 컬럼 (섹션 순):

- 식별·프로필: `handle, followers, follows_count, posts_count, biography, follower_band, tier`
- 정체성: `primary_category, posts_per_week, first_posted_at, last_posted_at`
- 표본: `sample_size, views_sample_size`
- 성과: `avg_views, median_views, min_views, avg_engagement_rate, median_engagement_rate,
  reach_efficiency, reach_efficiency_pct, tier_avg_views, position_percentile,
  momentum_ratio, momentum_pct, momentum_warning`
- 일관성: `hit_count, hit_rate, views_cv, volatility_label`
- 커머셜: `ad_count, ad_ratio, ad_avg_views, ad_avg_engagement, non_ad_avg_views,
  ad_avg_gap_days, last_ad_posted_at`

### 산식 (plan2 검증분 계승 + ER 정의 교체)

- **윈도우**: 전 지표가 `v_recent_content`(계정별 최신 N개, N=`analytics.recent-window` 기본 12) 기준.
- **tier**(coarse): followers <1만 micro / <10만 mid / 이상 macro. **follower_band**(세분):
  1만 미만 / 1만~3만 / 3만~5만 / 5만~10만 / 10만~30만 / 30만~50만 / 50만~100만 / 100만 이상.
- **primary_category**: 윈도우 내 게시물 수 최다 `main_group`(동률 시 알파벳).
- **posts_per_week**: `count*7 / GREATEST(업로드 스팬 일수, 7)`.
- **도달 효율**: `avg_views / tier_avg_views`(같은 coarse tier 전 계정 윈도우 게시물의 평균 조회수).
- **구간 포지션**: `percent_rank()` over (`follower_band × primary_category`) ORDER BY `avg_engagement_rate`.
  ER NULL 계정(피드 전용 등)은 순위에서 제외하고 `position_percentile` NULL.
- **모멘텀**: 업로드 오름차순 앞 절반 vs 뒤 절반 평균 조회수 비율(홀수 중앙 제외).
  `sample_size < detail-min-sample`이면 NULL. `momentum_pct ≤ -momentum-drop-threshold`면 warning true(그 외 false).
- **히트**: `views ≥ 2 × avg_views`(views NULL 제외). hit_rate = hit_count/sample_size.
- **변동성**: `views_cv = stddev_samp(views)/avg(views)`. 라벨은 `views_sample_size ≥ detail-min-sample`일 때만
  low(<mid-cv) / mid / high(≥high-cv), 미만이면 NULL.
- **커머셜**: `ad_marked` 기준. `ad_avg_engagement = avg(likes+comments)`(절대값 — CPE 계산기 재료),
  `ad_avg_gap_days`는 광고 2건 이상일 때만, 아니면 NULL.
- **NULL 규칙**(피드 views NULL — CLAUDE.md 함정): avg/median/min/cv/도달효율/모멘텀은 views NULL 행을
  집계에서 자연 제외(`views_sample_size`로 모수 노출). 전부 피드인 계정은 해당 지표 전면 NULL.

## 4. 저장·계약

- Flyway `V10__account_detail_tables.sql`(analytics 소유 이력): 테이블 5종 + 인덱스
  (자식 4종의 `account_handle`). 미러 테이블이므로 FK 없음(§4-3, TRUNCATE 충돌).
- `contract-analysis` record 5종(신규 파일): `AccountSummary`, `AccountCategoryStat`,
  `AccountFormatStat`, `AccountContentPoint`, `AccountAdPost`.
  컴포넌트 순서 = 뷰 컬럼 순서(미러 대조 가드). numeric→BigDecimal, bool→Boolean 등 타입 패밀리 일치.
- `MirrorConfig` 등록부에 `MirrorSpec` 5건 append.

## 5. 설정 키 (`app_setting` — 뷰가 직접 COALESCE로 읽음, plan2 그대로)

| 키 | 기본 | 의미 |
|---|---|---|
| `analytics.volatility-mid-cv` | 0.5 | CV < 0.5 → 'low' |
| `analytics.volatility-high-cv` | 1.0 | CV ≥ 1.0 → 'high' |
| `analytics.detail-min-sample` | 4 | 모멘텀·변동성 라벨 최소 표본 |
| `analytics.momentum-drop-threshold` | 15 | 모멘텀 -15% 이하 하락 시 경고 |

`analytics.recent-window`(12)는 기존 키 재사용. 신설 키는 시드하지 않고 뷰의 COALESCE 기본값으로 동작.

## 6. 검증

- **SQL 하니스** `analytics/test/10_account_detail.test.sql` — 기존 컨벤션(BEGIN + seed/dummy.sql + ASSERT + ROLLBACK).
  plan2의 더미 4계정 기대값 + 추가 픽스처 3계정(9005~9007: 모멘텀 상승/하락·CV 라벨 3종·히트 검증)을 이식하되
  ER 파생 기대값은 views 분모로 재산출. 테스트 첫머리에 이 플랜이 읽는 설정 키 DELETE로 결정성 확보.
  NULL 함정 검증은 dummy_mid(피드 1건·views NULL·광고) 케이스가 담당.
- **미러**: 기존 `MirrorJobTest`·`FlywaySchemaTest` 패턴으로 5종 스펙의 뷰↔DDL↔record 정합 검증(Testcontainers).
- 검증 명령: `./analytics/test/run.sh` + `./gradlew :analytics:test`.

## 7. 작업 방식·다른 트랙과의 관계

- 브랜치 `feat/task-c1-account-detail` — B3 완료 커밋(`d81e0d2`) 위에 스택
  (B1~B3 analytics 산출물 필요, 전 트랙이 develop 미병합이라 선행 트랙 병합 전제).
  작업은 전용 워크트리 `../hypenow-backend-c1`에서 — 메인 체크아웃은 다른 세션과 공유되어 브랜치가 수시로 바뀐다.
- E(다른 세션)는 이 문서의 테이블 5종 + record 5종을 계약으로 소비한다.
- C2는 `account_ad_history`에 브랜드·광고유형을 **additive**로 확장(§4-5 추가는 자유).
- 완료 시 ARCHITECTURE.md §5(C1 ✅)·§7(결정 기록: ER 정의 통일·자식 테이블 방식) 갱신.
