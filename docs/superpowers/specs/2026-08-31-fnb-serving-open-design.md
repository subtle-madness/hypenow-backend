# F&B 서빙 개방 — 기본 화면 무변경, 명시 필터에서만

> 상태: 🟢 활성 · 작성 2026-08-31 · 선행: [2026-08-31-fnb-content-taxonomy-design.md](2026-08-31-fnb-content-taxonomy-design.md)(LLM 계층, 배포됨)

## 1. 목표와 원칙

FE에 F&B 필터 UI가 아직 없다. 그래서 **F&B는 명시 쿼리 파라미터(`mainCategory` 등)로만
나오고, 무필터 기본 화면(랭킹·발굴·랜딩)은 바이트 단위로 불변**이어야 한다 — FE 배포 전에
백엔드를 먼저 열어도 사용자 화면이 바뀌지 않는 조건이다(사용자 확정).

FE 명세 부재로 백엔드가 가정한 것(전부 "뷰티와 대칭"):
- 발굴 `mainCategory`의 의미 = 그 축 분류 비중 20% 이상(사용자 확정)
- 유통사 필터값은 축 무관 통과(어휘 해석이 자연 정합 — F&B 유통사는 F&B 콘텐츠에만 붙는다)
- `meta.distributors` 옵션은 **뷰티 유지**(축 선택 방식은 FE 명세 후)

## 2. 랭킹 `/v1/contents`

- allowlist에 F&B 6종 추가(`beverage`·`alcohol`·`convenience`·`snack`·`health-food`·`recipe`).
- **`mainCategory` 필터가 있으면 `an.is_beauty = true` 조건을 제거**하고
  `an.main_category = :main`만 남긴다. 근거는 생산자 불변식 "main_category 있음 ⇒ 축 확정":
  - 뷰티 slug: `main='skincare' ⇒ is_beauty=true`(V34 백필로 과거분 포함 전역 성립) — **동치**
  - F&B slug: `main='beverage' ⇒ is_beauty=false` — 기존 조건과 모순이라 제거해야 나온다
  - 축 조회·분기 코드가 아예 불필요해진다
- 무필터는 기존 그대로(`is_beauty = true`) — 기본 화면 불변의 핵심.
- `metric_timeliness`·팔로워·키워드·adType·유통사 조건 무변경.

## 3. 발굴 `/v1/influencers`

모수가 `account_summaries ⋈ accounts`라, F&B가 필터에 응답하려면 F&B 계정이 미러에
들어가야 하고 — 들어가는 순간 무필터 목록에 섞일 경로가 생긴다. 축 컬럼으로 명시 차단한다.

- **`accounts`에 `beauty boolean`·`fnb boolean` 추가**(nullable — 롤링 창에서 구 analytics
  미러가 안 채워도 무해). 계약 3종 동기: 02 `v_accounts` 노출(맨 끝) + Flyway DDL +
  contract-analysis `Account` record.
- allowlist에 F&B 6종 추가. 축 판정은 Java 상수(F&B slug 셋)로 — 요청당 DB 조회 없음.
- 분기:
  - **무필터·뷰티축 필터**: `AND COALESCE(a.beauty, true)` — 미러 갱신 전 기존 행은 전부
    뷰티 모수 출신이라 true 간주. 뷰티 비율 게이트(V45)·기존 비중 게이트 유지 → **결과 불변**.
  - **F&B축 필터**: `AND COALESCE(a.fnb, false)` + **뷰티 비율 게이트 미적용**(F&B 계정은
    뷰티 비율 0이라 걸면 전멸) — 오판 계정 방어는 F&B 비중 20% 게이트가 같은 역할(실측
    게시물 기반이라는 원리가 동일).
- `account_category_share`(matview) 재정의 — **축별 분모**:
  ```sql
  PARTITION BY s.account_handle, t.axis   -- t = beauty_taxonomy 대분류→축
  WHERE an.main_category IS NOT NULL      -- 구 게이트 is_beauty IS TRUE 대체
  ```
  뷰티 행의 pct는 분모가 "그 계정의 뷰티 분류분"으로 기존과 **동치**(불변식으로 증명:
  `main NOT NULL ∧ axis=beauty ⟺ is_beauty=true ∧ main NOT NULL`). F&B 행이 새로 생기고
  분모는 F&B 분류분. 유니크 인덱스 `(account_handle, main_category)`는 대분류가 축을
  결정하므로 그대로 유효(REFRESH CONCURRENTLY 유지).
- `account_category_stats`(V35, 카드 표시용)·`findShares`는 **무변경**(뷰티 게이트 유지) —
  무필터 카드에 표시되는 값이라 바꾸면 화면이 바뀐다. F&B 비중 표시는 FE 명세 후.

## 4. 분석 뷰 모수

- **01 `v_recent_content` · 02 `v_serving_content`·`v_accounts`**: 모수를
  `(beauty ∧ ¬beauty_company) ∨ (fnb ∧ ¬fnb_company)`로 확장. `v_analysis_source`(04)와
  동일 술어 — 분석 모수와 서빙 모수가 다시 일치한다(04 분리는 유지 — 서로 독립일 수 있는
  구조 자체가 가치).
- **20(랜딩 통계) 무변경** — 랜딩 노출 숫자는 기본 화면이다.
- 결과: 미러에 F&B 유입 — `contents` +10만 게시물, `accounts`·`account_summaries`
  +5,575계정. 뷰티 계정의 지표는 계정별 파티션이라 불변.

## 5. 수용한 비용 (사용자 확정)

| 항목 | 규모 |
|---|---|
| 미러 잡 | 게시물 183,391 → +10만 (~60% 증가) |
| 이미지 아카이브 | 대상 +10만 (GCS) |
| 계정 카피 LLM | 대상 +5,575계정 (일회성 등반 후 stale 주기) |

## 6. 기본 화면 불변 증명 지점 (테스트가 지켜야 할 것)

1. 랭킹 무필터: `is_beauty=true` 유지 — F&B 분석분(`is_beauty=false`)은 미러에 있어도 안 나옴
2. 랭킹 뷰티 필터: is_beauty 조건 제거 후에도 결과 동치(불변식)
3. 발굴 무필터: `COALESCE(a.beauty, true)` — F&B 단독 계정 미노출
4. `account_category_share` 뷰티 pct 동치(축별 분모)
5. 랜딩(20) 모수 불변
6. `meta.distributors` 뷰티만(기존 오염 차단 유지)

## 7. 범위 밖 / 후속

- FE 명세 후: `meta.distributors` 축 선택, 카드 categoryShares에 F&B 표시, 무필터 의미
  재정의(탭 구조에 따라), allowlist의 어휘 테이블화
- **계정 카피 프롬프트의 뷰티 전제** — F&B 계정 카피가 뷰티 문구로 생성될 수 있다. 품질
  확인 후 프롬프트 축 중립화는 별도 트랙
- hype 앵커는 뷰티 코퍼스 적합값 그대로 — F&B 필터 내 상대 순위는 유지되므로 초기 수용,
  재적합은 F&B 물량이 쌓인 뒤
- 홈/리빙 개방 = influencer 축 컬럼·accounts 컬럼·allowlist에 각 1항 추가(같은 패턴)
