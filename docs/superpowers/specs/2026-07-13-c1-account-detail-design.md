# 태스크 C1 — 인플루언서 상세 비LLM 집계 설계

> 상태: 🟢 활성 — C1 구현·E(인플루언서 API)의 데이터 계약 기준
2026-07-13 (설계 세션 기록. 같은 날 celfit-front 실계약 확인으로 v4 목업 기준 초안을 대체)

- 구조 기준: [ARCHITECTURE.md](../../../ARCHITECTURE.md) §4 (타입 미러·contract-analysis·로직의 자리)
- **기준 계약: celfit-front의 인플루언서 패널 실구현** —
  `src/components/ranking/overlays/influencer/report-types.ts`(`AccountReport`)와
  결정 지표 산식의 정본 `scripts/real-data-pipeline/parse_accounts_recent.py`·`assemble_reports.py`.
  기획 확정안 Artifact의 v4 목업(2026-07-10)은 이 실구현으로 대체됨 — 목업의 중앙값·히트율·변동성 라벨·
  구간 포지션·tier 도달 효율·팔로워 밴드·형식 비중·협업 이력 리스트는 실 프론트에 없다.
- plan2 초안(2026-07-10)은 임계값 키 방식·더미 시드 구성의 참고 자료로만 유지 — 지표 세트는 본 문서가 우선.

## 1. 범위

celfit-front 인플루언서 패널(`AccountReport`)의 **결정적(비LLM) 필드 전부**를 analysis DB에 준비한다:

- 프로필 헤더: 팔로워·팔로잉·게시물 수·bio (+ 랭킹 쪽 `accounts` 미러 재사용)
- 스탯: 기준 지표(metric) 폴백, 평균 조회수, 팔로워 대비 배수, 평균 ER(팔로워 기준), 평균 좋아요·댓글
- 트렌드: 방향(up/flat/down) + 근거 원값(변화율·앞/뒤 절반 평균)
- 차트·광고 스트립 재료: 윈도우 내 게시물별 시계열(조회수·좋아요·댓글·광고 여부)
- 콘텐츠 믹스: 카테고리별 게시물 수
- 광고: 광고 수, 오가닉 vs 광고 평균·낙폭·비교 모수, 마지막 광고 시점
- 활동성: 마지막 업로드 시점, 평균 업로드 간격

**범위 밖**: LLM 카피 전부(tagline·summary·trendNote·chartNote·traits·adHeadline·paceNote — C2),
브랜드 감지(B3/C2 additive), 광고 여부의 LLM 보강(C2 — C1은 `ad_marked` 마커 기준),
표현 조립(경과일·isActive 14일 판정·lastAdNote 문구 — was, D의 경과일 관례와 동일), 서빙 API(E — 다른 세션).

## 2. 핵심 결정

1. **미러 구조 = 평탄 자식 테이블.** 배열성 데이터(시계열·카테고리 믹스)는 jsonb가 아니라 자식 미러
   테이블로. 현행 `MirrorJob`이 jsonb를 못 옮기고, 배열 조립은 was 몫(§4-4)이라 원칙에도 맞다.
2. **ER은 제품에 두 정의가 공존한다 — 계정 평균 ER은 followers 분모.**
   게시물 단위 ER = (likes+comments)/views (B3 `v_analysis_baseline`·D 어셈블러·랭킹 카드).
   계정 평균 ER = `avg((likes+comments)/followers) × 100` (% 1자리) — 프론트 `stats.avgEr`의 실산식.
   피드 게시물도 좋아요·댓글은 있으므로 계정 ER엔 NULL 문제가 없다. 컬럼명은 `avg_er_pct`로 분모 혼동을 차단.
3. **기준 지표(metric) 폴백은 데이터에 박는다(생산자가 어휘 확정 — §4-4).**
   조회수 있는 게시물이 `max(3, n/2)` 이상이면 `'views'`, 아니면 `'likes'`.
   트렌드·광고 비교는 이 metric 값(>0인 것만)으로 계산한다. was는 해석 없이 전달만.
4. **번호대 예약**: 뷰 `10_account_detail.sql`, Flyway `V10__account_detail_tables.sql`
   (00~03·V1~V3 사용 중, 나머지 한 자릿수는 다른 트랙 몫).
5. **네이밍 `account_*`**, 식별자 `handle`/`account_handle` — `accounts`·`contents` 관례와 동일.
6. **기존 파일은 base 뷰 additive 확장만.** `followers`는 10번 파일 안의 밑판 뷰(`v_account_recent` =
   `v_recent_content` + `v_base_profile` 조인)에서 파생. 프로필 payload 키 3개(`followsCount`·
   `postsCount`·`biography`)는 raw 접촉이라 `00_base.sql` v_base_profile 끝에 컬럼 추가(§4-5 추가는
   자유 — raw 접촉은 base 뷰만 원칙 준수). 그 외 공유 파일 접점은 `MirrorConfig` 3줄 append뿐.
7. **views NULL 규약 유지.** raw의 피드 게시물 views NULL은 미러에도 NULL로 보존(프론트 시드의
   "0 = 미공개"는 프론트 사정) — was가 응답 조립 시 규약을 정한다. 집계에서는 NULL/0 조회수를
   프론트 파이프라인과 동일하게 제외한다(`> 0` 필터).

## 3. 뷰 (raw DB `analytics` 스키마 — `analytics/views/10_account_detail.sql`)

### 밑판 (미러 안 함)

- `v_account_recent` — `v_recent_content` + `v_base_profile`(followers) 조인. 이 파일 전용 밑판.

### 서빙 뷰 3종 (미러 1:1 — 컬럼 이름·순서 = Flyway DDL = record)

| 뷰 → 테이블 | 내용 | PK |
|---|---|---|
| `v_account_summaries` → `account_summaries` | 계정 1행 스칼라 (아래 명세) | `handle` |
| `v_account_category_stats` → `account_category_stats` | 카테고리 믹스: `account_handle, main_group, content_count`. 라벨은 crawler 분류(`main_group`) 어휘 — 프론트 시드는 게시물별 LLM 분류였으나 비LLM 층에선 crawler 어휘를 쓰고, LLM 재분류는 C2 여지 | `(account_handle, main_group)` |
| `v_account_content_series` → `account_content_series` | 게시물 시계열: `short_code, account_handle, posted_at, content_type, views, likes, comments, sponsored` | `short_code` |

`account_summaries` 컬럼 (프론트 `AccountReport` 대응 순):

- 프로필: `handle, followers, follows_count, posts_count, biography`
- 표본: `analyzed_count`(윈도우 내 게시물 수 ≤N), `views_count`(views>0 게시물 수 — metric 판정 근거이자 모수)
- 스탯: `metric`('views'|'likes'), `avg_views`(views>0 평균, 없으면 NULL),
  `views_per_follower`(avg_views/followers, 1자리, avg_views 없으면 NULL),
  `avg_er_pct`(§2-2, % 1자리), `avg_likes`, `avg_comments` (정수 반올림)
- 트렌드: `trend_direction`('up'|'flat'|'down'), `trend_change_pct`(정수 %),
  `trend_older_avg`, `trend_newer_avg` (비교 원값 — 표기 원칙 §4-6: 원값 제공)
- 광고: `sponsored_count`(윈도우 내 광고 수), `organic_avg`, `ad_avg`,
  `ad_drop_pct`(정수 % — 음수면 광고가 더 잘 나옴), `comparison_organic_count`, `comparison_ad_count`
  (비교는 metric>0 게시물만 — 모수 별도 노출), `last_ad_posted_at`
- 활동성: `last_posted_at`, `avg_interval_days`(연속 업로드 간격 평균 일수, 1자리)

### 산식 (parse_accounts_recent.py 정본을 SQL로 이식)

- **윈도우**: 전 지표가 `v_recent_content`(계정별 최신 N개, N=`analytics.recent-window` 기본 12) 기준.
  올린 순 정렬 = `uploaded_at ASC, content_id ASC`.
- **metric 폴백**: `views_count >= GREATEST(3, analyzed_count/2)`(정수 나눗셈)이면 'views', 아니면 'likes'.
- **avg_views**: views>0인 게시물 평균(정수 반올림). 해당 게시물 없으면 NULL.
- **avg_er_pct**: `round(avg((likes+comments)::numeric / followers) * 100, 1)`. followers 0/NULL 계정은 NULL.
- **트렌드**: 올린 순 앞 절반(floor(n/2)개) vs 뒤 절반(나머지), 각 구간의 metric>0 평균.
  둘 다 >0이면 `change = newer/older - 1`, `|change| > trend-threshold(0.15)`로 up/down, 이내면 flat.
  한쪽이라도 0이면 flat + change_pct 0. (프론트 산식과 동일 — 홀수 중앙 게시물은 뒤 절반에 포함)
- **광고 여부**: `ad_marked` (crawler 캡션 마커 기준. LLM 보강 판정은 C2가 additive로).
- **광고 비교**: 광고/오가닉 각각 metric>0 게시물의 metric 평균(정수). 양쪽 다 표본이 있어야
  `ad_drop_pct = round((1 - ad_avg/organic_avg)*100)`, 아니면 없는 쪽 컬럼 NULL.
- **avg_interval_days**: `(max(uploaded_at)-min(uploaded_at))의 일수 / (n-1)`, 1자리. n=1이면 NULL.
  (프론트 파이프라인은 간격별로 일수 절사 후 평균이라 소수점이 미세하게 다를 수 있다 —
  백엔드는 절사 없는 스팬/(n-1)을 정의로 채택.)
- **경과일 계산은 저장하지 않는다** — `lastUploadDaysAgo`·`isActive`(14일)·`lastAdNote` 문구는
  was가 `last_posted_at`/`last_ad_posted_at`으로 조립(D의 경과일 관례).

## 4. 저장·계약

- Flyway `V10__account_detail_tables.sql`(analytics 소유 이력): 테이블 3종 +
  자식 2종의 `account_handle` 인덱스. 미러 테이블이므로 FK 없음(§4-3, TRUNCATE 충돌).
- `contract-analysis` record 3종(신규 파일): `AccountSummary`, `AccountCategoryStat`, `AccountContentPoint`.
  컴포넌트 순서 = 뷰 컬럼 순서(미러 대조 가드). numeric→BigDecimal, bool→Boolean 등 타입 패밀리 일치.
- `MirrorConfig` 등록부에 `MirrorSpec` 3건 append.

## 5. 설정 키 (`app_setting` — 뷰가 직접 COALESCE로 읽음)

| 키 | 기본 | 의미 |
|---|---|---|
| `analytics.trend-threshold` | 0.15 | 트렌드 up/down 판정 변화율 경계 (프론트 ±15%) |

`analytics.recent-window`(12)는 기존 키 재사용. metric 폴백 경계(`max(3, n/2)`)와 isActive 14일은
프론트 코드 상수라 키로 빼지 않는다(전자는 뷰에 주석, 후자는 was 몫).
plan2의 변동성·모멘텀·최소표본 키는 해당 지표가 실 프론트에서 빠져 폐기.

## 6. 검증

- **SQL 하니스** `analytics/test/10_account_detail.test.sql` — 기존 컨벤션(BEGIN + seed/dummy.sql + ASSERT + ROLLBACK).
  기대값은 parse_accounts_recent.py 산식으로 수동 산출해 고정. 케이스:
  더미 4계정(특히 dummy_mid: 피드 1건·views NULL·광고 → metric 'likes' 폴백·avg_views NULL·비교 한쪽 NULL)
  + 추가 픽스처(표본 4+ 계정: metric 'views'·트렌드 up/down/flat 3방향·광고 비교 양쪽 표본·간격 평균).
  테스트 첫머리에 `analytics.recent-window`·`analytics.trend-threshold` DELETE로 결정성 확보.
- **미러**: 기존 `MirrorJobTest`·`FlywaySchemaTest` 패턴으로 3종 스펙의 뷰↔DDL↔record 정합(Testcontainers).
- 검증 명령: `./analytics/test/run.sh` + `./gradlew :analytics:test`.

## 7. 작업 방식·다른 트랙과의 관계

- 브랜치 `feat/task-c1-account-detail` — B3 완료 커밋(`d81e0d2`) 위에 스택
  (B1~B3 analytics 산출물 필요, 전 트랙이 develop 미병합이라 선행 트랙 병합 전제).
  작업은 전용 워크트리 `.worktrees/c1`에서 — 메인 체크아웃은 다른 세션과 공유되어 브랜치가 수시로 바뀐다.
- E(다른 세션)는 이 문서의 테이블 3종 + record 3종을 계약으로 소비한다.
  프로필 헤더는 기존 `accounts` 미러와 조합(display_name·profile_image_url은 거기 있음).
- C2는 LLM 카피 7종(tagline~paceNote)·브랜드·광고 여부 보강을 **additive**로 확장(§4-5 추가는 자유).
- 완료 시 ARCHITECTURE.md §5(C1 ✅)·§7(결정 기록: 계정 ER=followers 분모·metric 폴백·실 프론트 계약 채택) 갱신.
