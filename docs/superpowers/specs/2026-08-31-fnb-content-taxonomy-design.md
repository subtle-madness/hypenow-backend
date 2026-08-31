# F&B 콘텐츠 분류 — 축을 어휘에서 유도하는 N-카테고리 구조

> 상태: 🟢 활성 · 작성 2026-08-31

- **트랙**: LL(F&B 카테고리 추가)의 후속 — "카테고리 서빙 개편"의 **LLM 계층 절반**
- **선행**: LL(계정 F&B 판정 2축화, 08-24) · 홈/리빙 3축화(08-27) · V30 캡션 분류 · V20260809063533 에스테틱 추가
- **범위 밖**: was·미러·프론트 필터 어휘 — F&B는 **아직 서빙 대상이 아니다**(FE 명세 도착 시 별도 트랙)

## 1. 문제 — 수집만 하고 산출물이 없다

F&B 계정은 이미 매일 크롤링된다. 운영 `app_setting`의 `fnb.pipeline-enabled`는 `true`이고
(트랙 LL 문서의 "⬜ 기본 off"는 갱신 누락 — 토글 on이 런타임 UPDATE라 커밋에 안 남았다),
최근 7일 신규 열거 콘텐츠의 대부분이 F&B 단독 계정에서 나온다.

그런데 **LLM 분석은 0건이다.** 08-31 운영 실측:

| | beauty_only | both | **fnb_only** |
|---|---|---|---|
| 계정 | 5,277 | 1,211 | **5,575** |
| ENUMERATION 콘텐츠(스냅샷 보유) | 147,499 | 35,892 | **101,932** |
| ∩ 캡션 보유 | 145,927 | 35,516 | **100,850** (98.9%) |
| ∩ 성숙(D+4 경과) | 143,985 | 34,851 | **96,331** |
| **분석 후보(timely ∨ in_window)** | 70,151 | 16,182 | **61,619** |

F&B 단독 콘텐츠 최근 500건을 전수 대조한 결과 `content_analyses` 0건 / `contents` 미러 0건.
크롤링 비용은 이미 쓰고 있는데 산출물이 없는 구간이다.

**타임리 비율은 이미 뷰티와 붙었다** — 업로드 주별 timely 비율이 08-10주 0% → 08-17주 11.4% →
08-24주 23.0%(뷰티 23.8%)로, 지난주부터 동률이다. 즉 구조적 결함이 아니라 수집이 8월 중순에
켜진 흔적이고, 지금은 정상 궤도다. 그 이전 물량은 최근 12개 윈도우(late_backfill) 경로로 들어온다.

### 1-1. 막고 있는 것 — 문 두 개

**문 1. 분석 명단에 F&B가 안 오른다.**
`04_analysis_candidates`는 서빙 뷰 `v_contents`(02) 위에 얹혀 있고, 02의 모수는
`i.beauty AND NOT i.beauty_company`다. 랭킹 화면용 조건이 분석 명단까지 그대로 상속된다.
`in_window` 계산이 참조하는 `v_recent_content`(01)도 같은 조건이라 F&B는 항상 false다.

**문 2. 분류 결과가 지워진다.**
`AnthropicContentAttributeAnalyzer.sanitize()`:

```java
// 비뷰티(isBeauty≠true)는 대분류를 확정하지 않는다 — "main_category 있음 ⇒ 뷰티" 불변식을
// 생산자에서 보장해, main_category만 읽는 소비처가 별도 필터 없이 비뷰티를 자동 제외하게 한다.
if (!Boolean.TRUE.equals(raw.isBeauty())) {
    main = null;
}
```

F&B 콘텐츠는 LLM이 `isBeauty=false`로 답하므로 어휘를 넣어도 `main_category`가 전부 null이 된다.
**어휘 추가만으로는 아무 일도 일어나지 않는다.**

## 2. 설계 원칙 — 축은 어휘가 안다

카테고리는 앞으로 계속 는다(다음은 홈/리빙, 계정 판정 축은 08-27에 이미 3축이다).
그래서 **축마다 컬럼·프롬프트 필드·마이그레이션이 하나씩 늘어나는 구조를 채택하지 않는다.**

- 축 소속은 **어휘 테이블이 안다** — `beauty_taxonomy.axis`
- `content_analyses`에는 **새 컬럼을 만들지 않는다** — `main_category`가 있으면 그 대분류의
  `axis`가 곧 그 콘텐츠의 축이다
- 기존 `is_beauty`는 "축이 beauty일 때 true"로 계속 채워 **was 소비처를 무접촉으로 둔다**
- **홈/리빙 추가 = 어휘 INSERT 한 번.** 스키마·프롬프트 구조·API 계약 변경 없음

FE 계약이 이 선택을 뒷받침한다. `/v1/contents`·`/v1/influencers` 모두 축 개념 없이
**대분류 slug 하나**(`mainCategory`)로 요청하고, 그 slug가 어느 축인지는 서버가 판단한다.
서빙을 열 때 FE는 필터 옵션 목록만 늘리면 되고 요청 형태는 안 바뀐다.

## 3. 어휘 — `beauty_taxonomy`에 axis 추가 + F&B 24행

테이블명은 유지한다. 운영 DB에 있고 소비처가 여럿이며(was 랭킹 중분류 확장·V35·matview),
에스테틱 추가 때도 같은 판단을 했다. rename 이득이 위험을 넘지 않는다.

```sql
ALTER TABLE beauty_taxonomy ADD COLUMN axis text NOT NULL DEFAULT 'beauty';
```

expand only — 기존 7개 대분류는 전부 `beauty`로 자동 채워진다.

F&B 어휘는 **피처링 콘텐츠 랭킹 필터 트리**(app.featuring.co, 2026-08-31 채취)를 정본으로 한다.
경쟁 서비스가 이미 시장에서 검증한 분류라 자체 발명보다 낫고, 사용자가 이 화면을 기준으로
카테고리 확장을 요청했다.

| 중분류 | 소분류 |
|---|---|
| 음료 | 탄산 · 주스 · 기능성음료 · 커피 · 단백질음료 |
| 주류 | 소주 · 맥주 |
| 가공/간편식 | 즉석식품 · 밀키트 · 면류 · 이유식 |
| 간식류 | 과자 · 초콜릿 · 아이스크림 · 젤리 |
| 건강식품 | 영양제 · 비타민 · 유산균 · 프로틴 · 다이어트 · 이너뷰티 |
| 요리/레시피 | 요리 · 디저트/베이킹 · 음료 |

대분류 slug는 피처링 URL 파라미터 값을 그대로 쓴다 —
`beverage`·`alcohol`·`convenience`·`snack`·`health-food`·`recipe`.
`main_order`는 8~13(에스테틱이 7).

**라벨 충돌 주의**: 요리/레시피의 소분류 '음료'는 중분류 '음료'와 문자열이 같다.
`sub_categories`는 정확 일치 매칭이라 중분류 필터가 이 소분류를 오탐할 수 있다 —
요리/레시피 아래 소분류는 **'음료 레시피'**로 표기해 분리한다(에스테틱의 '필링 시술' 선례와 동일).

### 3-1. 축 겹침 — F&B 우선

건강식품의 이너뷰티·다이어트·프로틴은 뷰티 콘텐츠와 겹친다. 규칙이 없으면 LLM이 매번 다르게 답한다.

**먹는 것이면 F&B로 확정한다.** 피처링도 이 셋을 F&B 건강식품 아래 두고 있어 외부 기준과 일치하고,
"제형이 아니라 섭취 여부"라는 한 줄 규칙이라 LLM이 흔들리지 않는다. 프롬프트에 명시한다.

## 4. 유통사 — 축별로 분리한다

현재 `beauty_distributors`는 2행(올리브영·다이소)이고, 프롬프트는 이 목록만 허용한다.

문제는 was가 이 테이블을 **필터 없이 통째로** 읽는다는 점이다:

```java
// V1ContentRepository.findDistributorOptions()
"SELECT slug, name FROM beauty_distributors ORDER BY slug"
```

축 구분 없이 F&B 유통사를 넣으면 **뷰티 랭킹 화면의 유통사 드롭다운에 편의점이 뜬다.**
어휘 테이블과 같은 이유로 여기에도 축 컬럼이 필요하다.

```sql
ALTER TABLE beauty_distributors ADD COLUMN axis text NOT NULL DEFAULT 'beauty';
```

F&B 목록(초안 — 확정 전 검토 필요):

| 구분 | 상호 |
|---|---|
| 편의점 | GS25 · CU · 세븐일레븐 · 이마트24 |
| 대형마트 | 이마트 · 홈플러스 · 롯데마트 · 코스트코 |
| 온라인 | 쿠팡 · 마켓컬리 · 네이버쇼핑 |

축은 분석 시점에 아직 모른다(mainCategory가 같은 콜의 산출물이라 닭-달걀). 그래서 프롬프트에는
**전체 목록을 축 라벨과 함께** 싣고, sanitize에서 축 정합성을 검사한다:

- `main_category`가 있으면 → 그 축의 유통사만 남기고 나머지는 드랍(뷰티 게시물의 'GS25'는 드랍)
- `main_category`가 null이면(일상글) → **유통사도 빈 배열로 확정**한다. 축을 판정할 수 없는
  콘텐츠에 유통사만 남기면 어느 축의 필터에도 걸리지 않는 고아 값이 된다

`was`의 `findDistributorOptions`가 축 필터 없이 전체를 읽는 것은 **서빙을 열 때 고칠 항목**으로
남긴다(§8). 그때까지는 F&B 행이 뷰티 화면 옵션에 노출되므로, **이 마이그레이션은 서빙 개방
트랙과 같은 릴리스에 넣거나, `findDistributorOptions`에 `WHERE axis='beauty'` 한 줄을 이번에
같이 넣어야 한다.** 후자를 택한다 — 한 줄이고, 넣지 않으면 오늘 당장 뷰티 화면이 오염된다.

> ⚠️ 이 한 줄이 이번 범위에서 **유일한 was 변경**이다. 서빙 확장이 아니라 오염 차단이다.

## 5. 프롬프트·sanitize 일반화

### 5-1. `isBeauty` → `isRelevant`

지금 LLM은 "이거 뷰티야?"만 묻는다. 축이 늘면 이 질문은 의미를 잃는다.

`isBeauty`(boolean)를 **`isRelevant`**로 바꾼다 — "분류표의 대분류 중 하나에 해당하는
콘텐츠인가(제품·시술·루틴·리뷰·요리 등). 인플루언서가 뷰티/F&B여도 무관한 일상·여행이면 false".

sanitize는 이렇게 바뀐다:

```java
if (!Boolean.TRUE.equals(raw.isRelevant())) {
    main = null;                       // 어느 축에도 안 속함
}
// is_beauty는 파생 — 축이 beauty일 때만 true
boolean isBeauty = main != null && "beauty".equals(taxonomy.axisOf(main));
```

**뷰티 콘텐츠의 동작은 동치다**: `isRelevant=true` ∧ `main='skincare'` → `is_beauty=true`.
일상글은 `isRelevant=false` → `main=null` → `is_beauty=false`. 기존 저장값과 같다.

`ContentAnalysisJob`의 self-heal(뷰티인데 복구 후에도 대분류 미분류면 `asNonBeauty()`로 종결)도
축 중립으로 일반화한다 — `isRelevant=true`인데 main이 끝내 null이면 종결 저장.

### 5-2. 프롬프트 문구

- 역할 문구 "뷰티 브랜드 마케터를 위한" → **"브랜드 마케터를 위한"**
- "뷰티와 무관한 콘텐츠면 mainCategory는 null" → "분류표 어느 대분류에도 해당하지 않으면 null"
- **축 겹침 규칙 1줄** 추가 — "섭취하는 제품(건강기능식품·단백질·다이어트 식품 포함)은 뷰티 목적이어도 F&B로 분류한다"
- **공동구매 규칙 1줄** 추가 — §5-3

분류표(`promptTable()`)는 축 라벨을 포함해 렌더링한다. 소분류가 24개 늘어도 입력 토큰 증가는 미미하고
출력 스키마는 그대로다 — `MAX_OUTPUT_TOKENS` 조정 불필요.

### 5-3. 광고 구분 — 공구는 sponsored

`sponsoredSignalLevel`·`adDisclosure`·`adType` 3종은 원래 축 무관 로직이다(캡션 표기 + 인스타
유료 파트너십 태그). 운영 분포도 sponsored 90,574 / organic 75,011로 균형이 잡혀 있다.

다만 F&B·홈리빙은 **공동구매(공구)** 비중이 크고 현행 프롬프트에 지시가 없다.
**공구는 `sponsored`로 판정한다** — 인플루언서가 대가를 받고 판매하는 상업 콘텐츠이고,
organic으로 두면 "자연 콘텐츠" 지표가 부풀려진다.

`adType` 어휘는 `organic|sponsored` 2개를 유지한다(was `AD_TYPES` 계약). 공구를 별도 값으로
만드는 건 API 계약 변경이라 이번 범위 밖이다.

## 6. 분석 후보 뷰 — 서빙에서 떼어낸다

미러가 `SELECT * FROM v_contents`로 통째 복사하므로, **서빙 뷰를 조금이라도 넓히면 그 즉시
F&B가 랭킹 API에 뜬다.** 그래서 02는 손대지 않고 04에 자체 소스를 둔다.

`04_analysis_candidates.sql` 안에 `analytics.v_analysis_source`를 신설한다:

- 모수: `QUALIFIED ∧ ((beauty ∧ ¬beauty_company) ∨ (fnb ∧ ¬fnb_company))`
- 재료: `v_base_content` ⋈ `v_base_detail`(캡션·썸네일) ⋈ `v_pinned_metrics`(핀 지표) ⋈ `v_base_profile`(팔로워)
- `recency_rank`를 **자체 계산**한다 — `in_window`가 참조하던 `v_recent_content`(01)가 뷰티
  게이트라 그대로 두면 F&B는 항상 false가 된다. 이 뷰가 이번 변경의 숨은 급소다.
- `hype_score`는 계산하지 않는다(04가 안 쓴다) — v_contents보다 오히려 짧아진다

`v_analysis_candidates`는 `v_contents` 대신 이 뷰를 읽고, 나머지 자격 로직(캡션·성숙·timely·
최적화 배리어 `OFFSET 0`)은 **그대로 승계**한다.

홈/리빙 추가 시 이 뷰의 모수에 OR 한 항이 는다. 계정 축은 crawler 컬럼이라 어휘에서 유도할 수
없어 여기만 명시적 열거로 남는다 — `v_base_influencer`가 이미 3축을 노출하고 있어 한 줄이다.

## 7. 백로그 일괄 처리

`analytics.analyze-batch-limit`은 1,000,000이라 상한이 사실상 없다. 하루 2,840건은 처리 한계가
아니라 **뷰티 백로그가 이미 소진돼 신규 유입분만 도는 것**이다. 전송은 이미
`analytics.analyze-transport=batch`(Vertex 배치, 50% 할인)로 돌고 있다.

문제는 `ContentAnalysisJob.submitBatch()`가 **대상 전량을 배치 1건으로** 제출한다는 점이다.
지금까지 최대 3,063건 / 평균 1,160건이었는데, 61,619건을 한 배치로 밀면:

- `content_batch_jobs.sidecar_jsonl` 한 컬럼에 수십 MB (설계 주석의 전제는 "~450행 × 수백 바이트")
- Vertex 배치 요청 파일 크기·건수 한도
- 프롬프트 61,619건 동시 조립 시 힙 압박

→ **`submitBatch`에 청크 분할을 넣는다.** 한 실행에서 N건(기본 3,000 — 실측 최대치)씩 나눠
여러 배치로 제출하고 `content_batch_jobs`에 배치당 1행을 남긴다. 수거(`ContentBatchCollectJob`)는
이미 행 단위라 무변경이다.

**목표는 "오늘 전량 제출"이고 수거는 되는 대로**다. 제출은 코드가 통제하지만 Vertex 배치의
처리 대기 시간과 쿼터는 통제 밖이라, 오늘 안에 수거까지 끝난다는 보장은 하지 않는다.

램프업 게이트나 일일 상한은 두지 않는다(사용자 결정) — 한 번에 개방한다.

## 8. 서빙 무변경 보장

`is_beauty`의 의미를 바꾸지 않는 것이 이 설계의 안전장치다. was 소비처를 전수 확인했고
모두 `is_beauty IS TRUE`로 걸러진다:

| 소비처 | 게이트 | F&B 유입 |
|---|---|---|
| `V1ContentRepository:59` 랭킹 | `an.is_beauty = true` | 없음 |
| `account_category_stats`(V35) 카테고리 믹스 | `is_beauty IS TRUE AND main_category IS NOT NULL` | 없음 |
| `account_category_share`(matview) 발굴 게이트 | 동일 | 없음 |
| `V1InfluencerDiscoveryRepository.findShares` | 동일 | 없음 |
| `account_beauty_ratio`(V45) | `is_beauty` 카운트 | 없음 |
| `contents` 미러 | v_contents 모수(뷰티) | 없음 |

뷰티 계정이 올린 음식 게시물이 `is_beauty=false, main='beverage'`가 되어도 위 경로에는
한 건도 새지 않는다. F&B 단독 계정 콘텐츠는 애초에 미러에 없다.

**유일한 was 변경은 §4의 `findDistributorOptions`에 축 필터 한 줄**이며, 이는 서빙 확장이
아니라 오염 차단이다.

### 8-1. 서빙을 열 때 손댈 곳 (FE 명세 도착 후, 이번 범위 밖)

| 위치 | 지금 | 열 때 |
|---|---|---|
| `V1ContentQuery:25` · `V1InfluencerDiscoveryQuery:20` | 뷰티 7개 allowlist ×2 (Java 상수) | 어휘 테이블에서 읽기 |
| `V1ContentRepository:59` | `an.is_beauty = true` | 축 인지 게이트 |
| `account_category_share` · `account_category_stats` · `findShares` | `is_beauty IS TRUE` | 축 인지 |
| `account_beauty_ratio`(V45) | 뷰티 비율 20% | 축별 비율 |
| `findDistributorOptions` | `WHERE axis='beauty'`(이번에 추가) | 요청 축에 따라 |
| 분석 뷰 01/02/20 모수 | `i.beauty AND NOT i.beauty_company` | 축 유니온 |

## 8-2. 마이그레이션

analysis DB(`db/migration/analysis`) 단일 버전 공간. UTC 타임스탬프 채번(CLAUDE.md 컨벤션),
전부 **expand only**라 롤링 배포 안전하다.

| 파일 | 내용 |
|---|---|
| `V20260831030000__taxonomy_axis.sql` | `beauty_taxonomy.axis`·`beauty_distributors.axis` 추가(`DEFAULT 'beauty'`) |
| `V20260831030100__fnb_taxonomy.sql` | F&B 대분류 6·소분류 24 INSERT + F&B 유통사 11 INSERT |

두 파일을 나누는 이유: 컬럼 추가와 어휘 시드는 되돌리는 단위가 다르다. 어휘가 잘못되면
시드 파일만 후속 마이그레이션으로 고치면 되고, 컬럼은 건드릴 일이 없다.
실제 채번은 작성 시점 `date -u +%Y%m%d%H%M%S`로 다시 딴다(위는 예시).

## 9. 기각한 대안

- **`content_analyses.is_fnb` 컬럼 추가** (crawler가 `fnb_class`로 한 방식의 복제) —
  축마다 컬럼·LLM 스키마 필드·마이그레이션이 하나씩 늘어난다. 홈/리빙 때 같은 작업을 또 하고,
  4번째 카테고리면 또 한다. 어휘에서 유도하면 INSERT 한 번으로 끝난다.
- **`main_category` 존재만으로 축 판단** ("main 있음 ⇒ 뷰티" 불변식 폐기) — was 소비처 중
  `main_category IS NOT NULL`에만 의존하는 경로가 F&B를 그대로 흡수한다. 서빙 무변경 전제가 깨진다.
- **`beauty_taxonomy` → `content_taxonomy` rename** — 운영 DB에 있고 소비처가 여럿이다.
  에스테틱 때와 같은 판단(이득 < 위험). 이름의 어색함은 주석으로 흡수한다.
- **04를 v_contents에 둔 채 02 모수만 넓히기** — 미러가 `SELECT *`라 랭킹이 즉시 열린다.
- **어휘만 먼저 넣고 모수는 나중에** — sanitize 게이트와 04 모수 때문에 분석이 여전히 0건이다.
  어휘 추가가 무효가 된다.

## 10. 검증

- **SQL 하니스**: `04_analysis_candidates.test.sql` 신설/확장 — F&B 단독 계정 콘텐츠가 후보에
  포함되는가, `in_window`가 자체 계산으로 동작하는가, 뷰티 후보 집합이 변경 전과 동일한가(회귀)
- **02 서빙 회귀**: `02_serving.test.sql`로 v_contents·v_serving_content 결과 불변 확인
- **sanitize 단위 테스트**: 뷰티 콘텐츠 동치성(`isRelevant=true` ∧ beauty axis → `is_beauty=true`),
  F&B 콘텐츠(`is_beauty=false` ∧ main 유지), 일상글(main null), 축 불일치 유통사 드랍
- **프롬프트 회귀**: 기존 분석분에서 뷰티 샘플 N건을 신 프롬프트로 재실행해 `main_category`·
  `adType` 일치율 확인 — `isBeauty`→`isRelevant` 문구 변경이 뷰티 분류를 흔들지 않는지가 핵심 리스크
- **어휘 정합**: `beauty_taxonomy` 라벨 중복 검사(요리/레시피 '음료 레시피' 분리 확인)
- `./gradlew test` 4모듈 전체 — PR 직전

## 11. 후속

- **서빙 개방** — FE 명세 도착 후 §8-1 항목. 별도 트랙
- **홈/리빙 어휘 추가** — 이 구조가 자리잡으면 어휘 INSERT + 04 모수 OR 한 항
- **F&B 유통사 목록 확정** — §4 초안은 검토 대상
- **`adType`에 공구 값 신설** — API 계약 변경이라 FE 명세와 함께
