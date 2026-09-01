# LL2 — F&B 콘텐츠 분류 (축을 어휘에서 유도)

- **소속 트랙군**: 분석 층 LLM 트랙 — 설계: [specs/2026-08-31-fnb-content-taxonomy-design.md](../superpowers/specs/2026-08-31-fnb-content-taxonomy-design.md)
- **의존**: 트랙 [LL](LL-fnb-카테고리.md)(계정 F&B 판정 2축화, 08-24)의 후속 — "카테고리 서빙 개편"의 **LLM 계층 절반**
- **상태**: ✅ 구현 완료(2026-08-31) — 서빙 개방 후 FE 피드백 반영(09-01, `vertical` 파라미터·subCategory 400·distributors 축·sanitize 대분류 정합)까지 완료

## 내용

F&B 계정은 이미 매일 크롤링된다(운영 `fnb.pipeline-enabled=true` — LL 문서의 "기본 off"는
토글 on이 런타임 UPDATE라 커밋에 안 남아 갱신이 누락된 것). 그런데 **LLM 분석은 0건이었다.**
08-31 운영 실측: F&B 단독 계정 5,575개 / ENUMERATION 콘텐츠 101,932건(캡션 보유 98.9%) /
분석 후보 61,619건 / `content_analyses` 0건 · 미러 0건.

크롤링 비용은 이미 쓰면서 산출물이 없는 구간이라, 이 트랙은 그 구간을 연다.
**서빙(랭킹·발굴)은 열지 않는다** — F&B는 아직 서빙 대상이 아니다.

카테고리는 앞으로 계속 는다(다음은 홈/리빙, 계정 판정 축은 08-27에 이미 3축).
그래서 축마다 컬럼·프롬프트 필드·마이그레이션이 하나씩 늘어나는 구조를 채택하지 않고,
**축 소속을 어휘 테이블(`beauty_taxonomy.axis`)이 알게** 했다.

### 막고 있던 문 세 개

| # | 위치 | 증상 |
|---|---|---|
| 1 | `04_analysis_candidates`가 서빙 뷰 `v_contents`(02) 위에 얹힘 | 뷰티 모수를 상속해 F&B가 후보에 안 오름 |
| 2 | `sanitize()`의 "비뷰티면 `main_category=null`" | 어휘를 넣어도 분류가 지워짐 |
| 3 | `resolveTargets()`의 미러 미도달 게이트 | F&B는 미러(`contents`)에 없어 100% 스킵 — 로그 한 줄 남기고 사라짐 |

문 3은 계획 작성 중에 발견했다. 1·2만 고쳤으면 **여전히 0건**이었다.

### 태스크

| # | 내용 | 상태 |
|---|---|---|
| 1 | 어휘 축 컬럼(`beauty_taxonomy.axis`·`beauty_distributors.axis`, `DEFAULT 'beauty'`) + F&B 대분류 6·소분류 24 + 유통사 11 시드 — 마이그레이션 2건, expand only | ✅ |
| 2 | `BeautyTaxonomy` 축 인지 — `Entry.axis`·`Distributor` record·`axisOf`·`distributorAxisOf`, 프롬프트 축 표기 | ✅ |
| 3 | sanitize 축 일반화 — `isRelevant`(LLM 응답) + `is_beauty`(파생) 분리, 유통사 축 정합, self-heal `asUnclassified` | ✅ |
| 4 | 프롬프트 2벌·Gemini 응답 스키마 축 중립화 + 규칙 3개(섭취 제품 F&B 우선·공구 sponsored·유통사 축 일치) | ✅ |
| 5 | was `findDistributorOptions` 축 필터 1줄 — **오염 차단**(서빙 확장 아님) | ✅ |
| 6 | `v_analysis_source` 신설 — 04를 서빙 뷰에서 분리, `recency_rank`·`content_id` 자체 보유 | ✅ |
| 7 | 분석 재료를 미러 → 후보 뷰로 전환, 미러 미도달 게이트 제거 | ✅ |
| 8 | 배치 제출 청크 분할 — `app_setting` `analytics.batch-chunk-size`(기본 3000) | ✅ |
| 9 | 문서·PR | ✅ |
| 10 | **서빙 개방**(같은 날 후속 PR) — 기본 화면 무변경·명시 필터에서만. [설계](../superpowers/specs/2026-08-31-fnb-serving-open-design.md) | ✅ |

## 주요 결정

- **축은 어휘가 안다** — `content_analyses`에 새 컬럼을 만들지 않는다. `main_category`가 있으면
  그 대분류의 `axis`가 곧 콘텐츠의 축이다. **홈/리빙 추가 = 어휘 INSERT 한 번 + 04 모수에 OR 한 항.**
  기각한 대안: `is_fnb` 컬럼 추가(crawler의 `fnb_class` 복제 방식) — 축마다 컬럼·LLM 필드·
  마이그레이션이 늘어 4번째 카테고리에서 같은 작업을 또 한다.
- **`is_beauty`의 의미를 안 바꾼다** — was 소비처 5곳이 전부 이 컬럼으로 비뷰티를 걸러낸다
  (랭킹 · 카테고리 믹스 V35 · 발굴 matview · `findShares` · `account_beauty_ratio`).
  파생으로 계속 채워 서빙을 무접촉으로 뒀다. 기각한 대안: "main_category 있음 ⇒ 뷰티" 불변식 폐기 —
  `main_category IS NOT NULL`에만 의존하는 경로가 F&B를 그대로 흡수한다.
- **테이블명 `beauty_*` 유지** — 운영 DB에 있고 소비처가 여럿(was 중분류 확장·V35·matview).
  에스테틱 추가 때와 같은 판단(이득 < 위험). 이름의 어색함은 주석으로 흡수.
- **04를 서빙 뷰에서 분리** — 미러가 `SELECT * FROM v_contents`라 02를 넓히면 랭킹이 즉시 열린다.
- **배치 청크 상한은 상수가 아니라 `app_setting`** — 백로그 소진 중 페이스를 재배포 없이 조정.
- **섭취 제품은 F&B 우선 / 공구는 sponsored** (사용자 확정) — 피처링 트리도 이너뷰티·다이어트·
  프로틴을 F&B 건강식품 아래 두고 있어 외부 기준과 일치한다.

## 함정 (구현 중 실측)

- **`in_window`·`timely` 둘 다 뷰티 게이트에 묶여 있었다.** 계획에는 `in_window`(01 뷰 위임)만
  적었는데, `timely` EXISTS도 `content_id`를 얻으려 `v_serving_content`를 조인하고 있었다.
  하나만 고쳤으면 F&B의 `timely`가 영원히 false다.
- **시드 격리 DELETE의 하드코딩 ID 범위**(`influencer_id NOT BETWEEN 99990001 AND 99990005`)가
  범위 밖 새 픽스처의 raw 원형을 조용히 지웠다. 증상이 "시드했는데 뷰에 안 보임"이라 04 로직
  결함으로 오진하기 쉽다. 더미 집합에서 유도하도록 바꿨다.
- **시드 ID 대역이 이미 개별 테스트 파일에 점유돼 있다**(99990006~는 `01b`·`10_account_email` 등).
  새 시드 픽스처는 `test/*.test.sql` 전체를 grep해 빈 대역을 골라야 한다 — 시드 헤더에 명시.

## 검증

- `./gradlew test` 4모듈 — 테스트 클래스 361개(crawler 83·analytics 51·was 162·monitoring 65) 실패 0.
- SQL 하니스 15개 ALL GREEN — **프레시 Postgres 16 컨테이너 + crawler 마이그레이션 27개**
  (CI `sql-harness` 잡과 동일 절차를 로컬 재현. 이미지만 `postgres:16-alpine`, 서버 16.14).
- 서빙 무변경: F&B 단독 계정(`dummy_fb`)을 **공용 시드**에 두었는데도 서빙 뷰 테스트
  (01·02·20)가 전부 통과 — 누출이 있으면 이 테스트들이 먼저 깨진다.
- sanitize 동치성: 뷰티 경로 `is_beauty=true` 유지 / F&B는 대분류 생존 + `is_beauty=false` /
  축 불일치 유통사 드랍 / 무관 콘텐츠는 대분류·유통사 모두 비움.
- 미러 비의존: 미러에 없는 후보도 후보 뷰 재료로 분석되는지(`fnb_only_1`).
- 배치 청크: 상한 초과 시 `content_batch_jobs` 2행 + `submitted_count` 합 일치.

## 운영 메모 (코드 아님)

- 배포 순서는 **analytics 먼저** — was의 축 필터 쿼리가 `beauty_distributors.axis`를 읽는다.
  develop→staging→main 승격에선 두 모듈이 같은 릴리스로 나가 순서 역전이 없다.
- 배포 후 analytics 어드민(`/ui`)에서 분석 잡 수동 트리거. `runLateBackfill()` 경로가
  F&B 백로그를 청크 단위로 제출한다.
- 관측:
  ```sql
  -- 제출 현황 (analysis DB)
  SELECT status, count(*), sum(submitted_count) FROM content_batch_jobs GROUP BY status;
  -- 축별 분석 누적
  SELECT t.axis, count(*) FROM content_analyses a
  JOIN (SELECT DISTINCT main_value, axis FROM beauty_taxonomy) t ON t.main_value = a.main_category
  GROUP BY t.axis;
  ```
- 제출은 당일 완료가 목표, **수거는 Vertex 배치 처리 시간에 달려 있어 며칠 걸릴 수 있다.**

## 후속

- ~~**서빙 개방**~~ — 08-31 완료. 09-01 FE 피드백으로 `vertical=beauty|fnb`(랭킹·발굴 축 전체
  조회, mainCategory와 배타), 상위 없는 subCategory 400, `meta.distributors` 축 선택,
  sanitize 대분류 정합 필터 + 프롬프트 소분류 필수 지시까지 반영. 기분석분의 얇은 소분류는
  재분석 비용 문제로 보류 — 새 분석분부터 개선 적용. 09-01 카드 categoryShares도 축 인지로
  전환(`findShares` 어휘 축 조인) — F&B 카드 "카테고리 정보 준비 중" 해소, 뷰티 표시는
  동치(운영 전량 대조 0건).
- **홈/리빙 어휘 추가** — 이 구조가 자리잡으면 어휘 INSERT + 04 모수 OR 한 항.
- **F&B 유통사 목록 확정** — 현재 11곳은 초안. 운영 데이터가 쌓이면 등장 빈도로 조정.
- **프롬프트 회귀 실측** — `isBeauty`→`isRelevant` 문구 변경이 기존 뷰티 분류를 흔들지 않는지는
  단위 테스트로 증명되지 않는다. 배포 후 뷰티 콘텐츠의 `main_category` 분포·`ad_type` 비율을
  이전 구간과 비교할 것.
- **`beauty_taxonomy` rename** — 축이 셋이 되면 더 어색해진다. 서빙 개편 때 재검토.

## 관련 문서

- [specs/2026-08-31-fnb-content-taxonomy-design.md](../superpowers/specs/2026-08-31-fnb-content-taxonomy-design.md) — 설계 전문
- [plans/archive/2026-08-31-fnb-content-taxonomy.md](../superpowers/plans/archive/2026-08-31-fnb-content-taxonomy.md) — 구현 계획(실행 완료)
- [트랙 LL](LL-fnb-카테고리.md) — 계정 판정 2축화(선행)
