# hypenow 백엔드 — 메인 설계 문서

> **살아있는 문서.** 구조·결정이 바뀌면 이 문서를 먼저 고친다. 상세한 시점 기록(왜 그렇게 정했는지의
> 전말)은 `docs/superpowers/specs/`의 dated 문서에 남기고, 여기서는 **현재 유효한 그림**만 유지한다.
> 각 섹션을 고칠 때 하단 [결정 기록](#8-결정-기록)에 한 줄을 추가한다.
>
> 마지막 갱신: 2026-07-13 (캠페인 추천 피봇 기준으로 전면 재작성)

## 1. 제품 한 장 요약

**hypenow** — 캠페인 브리프 기반 인플루언서 추천 툴 (2026-07-13 피봇).
타깃: **마이크로인플루언서를 발굴하려는 뷰티 브랜드 마케터.**

핵심 플로우: 마케터가 **캠페인 브리프**(구조화된 폼 + 제품 이미지)를 제출 → 비동기 잡이
이미지 분석 → 후보 매칭 → 근거 생성 → **추천 리스트 + 근거**(정량 팩트 카드 + LLM 서술) 제공.

MVP 범위:
- **캠페인 추천** — 브리프 제출·진행 상태·추천 결과 (메인 화면, §3이 이 플로우의 설계)
- **근거 하위 화면** — 인플루언서 상세·게시물 드로어 (추천 근거를 파고드는 용도)
- **후보 관리** — 추천 결과에서 후보 저장·상태(검토중/컨택 예정/협업 중)·메모

보류(⏸): 레퍼런스 콘텐츠(원하는 느낌) 매칭, 브리프발 발굴(크롤링) 트리거.
기준 설계: [specs/2026-07-13-campaign-recommendation-pivot-design.md](docs/superpowers/specs/2026-07-13-campaign-recommendation-pivot-design.md)
프론트: celfit-front.vercel.app (별도 저장소)

## 2. 시스템 구조 — 두 개의 경로

3-tier. 층 사이는 DB로만 통신한다 (모듈 간 HTTP/큐 없음). 시스템은 성격이 다른 두 경로로 움직인다:

**배치 경로 (재료 생산)** — 크롤링과 분석. 사용자 요청과 무관하게 돌며, 추천의 재료인
**매칭용 인플루언서 프로필**과 근거 하위 화면 재료를 미리 만들어 둔다.

```
crawler ──쓰기──▶ raw DB ──읽기── analytics ──미러──▶ 분석 결과 (analysis DB)
        (Apify 수집 원형)          (SQL 뷰 + raw 콘텐츠 LLM)   · influencer_profiles (매칭 재료)
                                                              · accounts/contents/댓글·콘텐츠 분석 (근거 재료)
```

**요청 경로 (캠페인 플로우)** — 제품의 중심 경로. was가 브리프를 받아 분석 결과에
알고리즘을 태워 추천을 만들어낸다. 상세는 §3.

```
마케터 ──브리프──▶ was ──① VLM 이미지 분석 ──② 매칭·스코어링(분석 결과 읽기)
                       ──③ 근거 서술 LLM ──▶ app 스키마 저장 ──▶ celfit-front
```

| 모듈 | 데이터 접근 | 역할 | 기술 |
|---|---|---|---|
| `crawler` | raw DB 쓰기 | Apify로 발굴→판정→상세 수집, 원형(raw) 적재 | Spring Boot, JPA, Flyway, Thymeleaf 어드민 |
| `analytics` | raw 읽기 → 분석 결과 쓰기 | **프로필 팩토리** — 분석 뷰 정의 + **미러**(분석 결과를 analysis DB에 채움). raw 콘텐츠에 대한 LLM 분석 소속 | 헤드리스 배치, JdbcTemplate ×2 |
| `was` | 분석 결과 읽기 + 서비스 데이터 읽기/쓰기 | **캠페인 파이프라인 오케스트레이터**(§3) + REST API + 후보 관리 | Spring Boot, JdbcClient |
| `contract-analysis` | — | 분석 결과의 record·enum + **매칭 어휘**(카테고리·속성·톤) — 순수 JDK 계약 타입 (§5-4). analytics·was가 의존, crawler 무관 | Java record |
| `llm-core` *(신설 예정)* | — | LLM/VLM 호출 골격(인증·재시도·포트) — analytics·was 공용, 비즈니스 로직 금지 (§5-4) | 얇은 공유 모듈 |

**데이터 배치**: 저장 영역은 세 가지 — raw(크롤링 원본) / 분석 결과(미러 테이블) / **서비스 데이터**(was가
쓰는 앱 데이터: 캠페인·추천 결과·후보 관리·로그인 등). 서비스 데이터는 분석 결과와 **스키마로 분리**(analysis DB 내
`app` 스키마)하고, 셋 모두 현재 **한 Postgres 인스턴스**(포트 5433)에 논리 분리만 되어 있다. 부하를 보고
물리 분리를 결정한다 — 접근 규율(§5-4)을 지키는 한 어느 경계든 설정 변경으로 분리 가능하다.

**미러란**: raw DB에 정의된 분석 뷰(`analytics.*`)를 실행해 결과를 analysis DB의 테이블로 채우는
배치. 레플리카가 아니라 **분석 층이 결과물을 내놓는 행위 그 자체** — 뷰는 DB를 못 넘으므로 이 잡이
tier 경계다. 방식은 명시적·타입 기반(§5-3).

## 3. 캠페인 추천 플로우 (제품의 중심 경로)

was는 분석 결과를 그대로 내려보내는 서빙 층이 아니라, **입력(브리프)을 받아 분석 DB의
재료에 알고리즘을 태워 결과를 만들어내는 오케스트레이터**다. 캠페인 1건 = 비동기 잡 1건,
상태는 단계와 1:1 대응한다.

```
SUBMITTED ─▶ ANALYZING_BRIEF ─▶ MATCHING ─▶ NARRATING ─▶ DONE
                 │                 │            │
                 ▼                 ▼            ▼
          FAILED_BRIEF      FAILED_MATCHING  FAILED_NARRATING
```

| 단계 | 하는 일 | 읽기 | 쓰기 |
|---|---|---|---|
| ① 제출 | 폼 검증, 캠페인 저장, 잡 시작 | — | `app.campaign` (SUBMITTED) |
| ② 브리프 분석 | 제품 이미지 VLM → 제품 속성(매칭 어휘로 강제) | 캠페인 이미지 | `app.campaign.product_attrs` |
| ③ 매칭 | 하드 필터 + 축별 정규화(SQL, 파라미터 쿼리) → 가중 합산·순위(Java, 목표별 가중치 프리셋) → 상위 K 확정 | 분석 결과 `influencer_profiles` + 가중치 프리셋 | `app.campaign_recommendation` (점수 분해 + 팩트 원값 스냅샷) |
| ④ 근거 서술 | **저장된 팩트만 입력으로** LLM 서술 생성 | `app.campaign_recommendation` | 같은 행의 narrative |
| 서빙 | 진행 상태·결과 조회 (프론트 폴링), 후보 저장 연결 | `app.*` | `app.*` (후보) |

**추천을 떠받치는 세 기둥** (전말: [피봇 spec §4](docs/superpowers/specs/2026-07-13-campaign-recommendation-pivot-design.md)):

1. **매칭용 인플루언서 프로필** — analytics가 배치로 만들어 두는 단일 재료 테이블
   (`influencer_profiles`, 인플루언서당 1행, 매칭 축을 컬럼으로 평탄화).
   축: 정체성(카테고리 분포·콘텐츠 속성 태그) / 규모·성과(팔로워·참여율·히트율·벤치마크 대비) /
   상업성(광고 비율·광고 vs 비광고 성과) / 오디언스 반응(감성·구매의도) / 페르소나(라벨·광고 유형).
2. **어휘 계약** — 브리프 쪽 VLM(제품→속성, was)과 분석 쪽 LLM(콘텐츠→속성, analytics)이
   **같은 어휘**(카테고리·속성·톤 enum, `contract-analysis` 소유)만 출력하도록 강제.
   어휘가 갈리면 매칭이 조용히 죽는다 — 이 피봇의 최대 침묵 실패 지점이라 계약으로 고정.
3. **근거 확정 저장** — 추천 시점의 축별 점수 분해 + 팩트 원값을 추천 행에 스냅샷.
   프로필 미러는 계속 갱신되므로, 저장 없이는 "어제 추천의 근거"가 오늘 데이터로 바뀐다.
   LLM 서술은 이 저장된 팩트만 입력으로 받아 팩트를 벗어날 수 없다.

**실패·재시도 시맨틱**: 실패는 단계명이 박힌 상태(FAILED_*)로 고정, 재시도는 그 단계부터
(이전 단계 산출물은 저장돼 있음). NARRATING 실패는 부분 성공 — 추천 리스트·팩트 카드는
이미 확정이므로 먼저 노출하고 서술만 재시도 가능.

**캠페인 도메인 데이터** (`app` 스키마, was 소유 — 상세 DDL은 태스크 G 계획에서):

| 테이블 | 내용 |
|---|---|
| `campaign` | 브리프 폼 필드, 제품 이미지 참조, VLM 추출 제품 속성(jsonb), 상태, 실패 사유, 타임스탬프 |
| `campaign_recommendation` | campaign FK, username, 순위, 종합 점수, 축별 점수 분해(jsonb), 근거 팩트 원값 스냅샷(jsonb), LLM 서술, 서술 상태 |
| `matching_weight_preset` | 캠페인 목표별 축 가중치 — 재배포 없이 튜닝 (`app_setting` 철학의 was판) |
| 후보 연결 | 후보 관리가 `campaign_recommendation`을 참조 — "이 캠페인의 이 추천에서 저장됨" |

## 4. 데이터

### raw DB (crawler 소유 — 분석 작업에서 불변)

| 테이블 | 내용 |
|---|---|
| `content` | 게시물 메타 (short_code, owner, uploaded_at, 분류 계층, ad_marked, 상태) |
| `raw_post_detail` | Apify 상세 payload(jsonb) + generated 컬럼 (likes, comments_count, video_play_count, caption) |
| `raw_comment` | 댓글 원문 payload + generated (writer, text, written_at) |
| `raw_profile` | 프로필 스냅샷 payload + generated (username, followers) |
| `app_setting` | 런타임 설정 key-value (분석 뷰도 여기서 임계값을 읽음) |

### 분석 뷰 (raw DB의 `analytics` 스키마)

`analytics/views/NN_*.sql` 번호순 적용 컨벤션. 현재 소스: 00(base)·01(최근 윈도우)·02(서빙)·03(분석 기준선).
raw 접촉은 base 뷰(00)만 — 상세는 §5-4.

### analysis DB

- **분석 결과** — 뷰 결과가 미러되는 테이블(Flyway로 명시 정의 — §5-3). analytics가 쓰고 was가 읽는다.
  피봇 후 중심 테이블은 `influencer_profiles`(매칭 재료, R1·R2에서 구축)이고, 기존 서빙 미러
  (accounts·contents·content_comments·content_metric_snapshots·댓글/콘텐츠 분석)는 근거 하위 화면 재료.
- Flyway 이력은 스키마별 분리 소유 — 분석 결과는 analytics가, `app` 스키마는 was가 관리.
- **서비스 데이터 (`app` 스키마)** — §3의 캠페인 도메인(캠페인·추천 결과·가중치 프리셋)과
  후보 관리·로그인 등. 분석 결과와 스키마로 격리, 나중에 물리 분리 가능.

## 5. 관통하는 설계 원칙

### 5-1. 최근 N개 윈도우

모든 계정 단위 지표는 계정별 최신 게시물 N개(기본 12)만 잘라 계산한다. 재크롤링이 누적돼도
계정 간 비교가 공정해지고, UI 각주 "최근 N개 기준"이 이 한 곳을 가리킨다.
N을 포함한 숫자 경계값·임계값은 `app_setting`(key-value)이 단일 원천 — 뷰가 직접 읽어 재배포 없이 조정.

### 5-2. 로직의 자리 — 집합 연산은 SQL, 절차는 Java

비즈니스 로직의 자리는 언어가 아니라 성격으로 정한다.
**LLM은 대상 기준으로 이원화**(07-13 피봇): raw 콘텐츠(인플루언서)에 대한 LLM은 분석 층
(재현 가능한 분석 결과로 미러), 캠페인 입력(마케터 제출물)에 대한 LLM은 was
(서비스 트랜잭션의 일부로 `app` 스키마에 저장).

| 로직 성격 | 사는 곳 | 예 |
|---|---|---|
| 집합 연산 (집계·순위·비율·윈도우) | SQL 뷰 (raw DB `analytics` 스키마) | 프로필 집계, 벤치마크, 히트율 |
| 절차·외부 연동 — raw 콘텐츠 대상 | Java (분석 층) | LLM 댓글 분석, 페르소나 |
| 절차·외부 연동 — 캠페인 입력 대상 | Java (was) | 브리프 이미지 VLM, 근거 서술 LLM |
| 매칭 스코어링 | 필터·정규화는 SQL(파라미터 쿼리), 가중 합산·순위는 Java (was) | 캠페인 후보 매칭 |
| 상태 변화·트랜잭션 | Java (was) | 캠페인 잡 상태, 후보 상태 전이 |
| 표현 조립 | Java (was) | 경과일 계산, 응답 블록 조립 |

### 5-3. 미러 = 명시적 타입 기반 materialization

하나의 "형태"를 세 아티팩트가 역할을 나눠 든다. 작성자 요약 예시:

1. **뷰 SQL** — 계산 방법 (raw DB):

   ```sql
   CREATE VIEW analytics.v_author_summary AS
   SELECT owner_username, count(*) AS sample_size,
          round(avg(engagement_rate), 4) AS avg_er, round(avg(views), 1) AS avg_views
   FROM analytics.v_recent_content GROUP BY owner_username;
   ```

2. **Flyway DDL** — 저장 테이블 (analysis DB — 인덱스·제약을 걸 수 있다):

   ```sql
   CREATE TABLE author_summary (owner_username text, sample_size bigint,
                                avg_er numeric, avg_views numeric);
   ```

3. **공유 record** — 자바 그릇 (`contract-analysis` 모듈):

   ```java
   public record AuthorSummary(String ownerUsername, long sampleSize,
                               BigDecimal avgEr, BigDecimal avgViews) {}
   ```

흐름: 분석 층이 뷰를 SELECT → record 매핑 → analysis DB 테이블에 **TRUNCATE+INSERT
(한 트랜잭션 — 읽는 쪽에 공백 없음)** → was가 **같은 record**로 SELECT.
미러 시작 시 **뷰 컬럼 ↔ record 필드를 대조해 불일치면 즉시 실패**시켜 무언 드리프트를 쓰기 시점에 차단한다.

### 5-4. 모듈 공유 원칙

- **모듈은 서로 import 하지 않는다.** 예외는 공유 모듈 둘뿐:
  - **`contract-analysis`** — 분석 결과의 record·enum + **매칭 어휘**(카테고리·속성·톤 —
    브리프 VLM과 콘텐츠 LLM이 같은 어휘를 출력해야 매칭이 성립, 어휘 밖 출력은 방어).
    순수 JDK, Spring/JPA 의존 금지. 생산자 analytics와 소비자 was가 의존, crawler 무관.
    수록 기준: **"동일 형태를 다루는 Java 생산자+소비자 쌍"이 성립하는 타입만.** 한 모듈만
    쓰는 타입은 그 모듈에 둔다. util·비즈니스 로직은 넣지 않는다.
  - **`llm-core`** *(신설 예정)* — LLM/VLM 호출 골격(인증·재시도·포트). analytics·was 공용.
    호출 인프라만, 프롬프트·비즈니스 로직 금지.
- **모듈 간 계약은 전부 데이터 계약이다:**

  | 경계 | 계약 | 정의하는 쪽 |
  |---|---|---|
  | crawler → analytics | raw 스키마 (generated 컬럼 + 뷰가 쓰는 payload 키) | crawler |
  | analytics → was | 분석 결과 테이블 + 공유 record | analytics |
  | was ↔ 양쪽 LLM | 매칭 어휘 enum | contract-analysis |
  | was → front | REST JSON | was |

- **저장소 접근:** 소유한 저장소에는 엔티티 자유(JPA 가능 — crawler의 raw, was의 `app` 스키마,
  분석 층의 LLM 결과 테이블). 남의 저장소는 읽기 전용 쿼리 + record 매핑만.
- **was 접근 규율:** raw DB 접근 금지. 분석 결과는 읽기만(캠페인 매칭도 파라미터 쿼리로
  읽기만), 쓰기는 `app` 스키마에만. 분석 결과와 서비스 데이터를 SQL 조인하지 않는다
  (매칭 쿼리에 브리프 값은 바인드 파라미터로, 조합은 was 코드에서) — 물리 분리 대비.
- **raw 스키마 지식은 base 뷰에 격리** — raw 테이블·payload를 직접 만지는 SQL은 base 뷰만.
  분석 층의 Java도 crawler 코드가 아닌 SQL로 raw를 읽는다.
- **분류값·라벨은 생산자가 확정, 소비자는 전달만** — tier·감성분류 같은 어휘는 분석 층이 문자열로
  확정해 데이터에 박고, was는 해석·분기 없이 그대로 내려보낸다. 매칭 어휘만 예외적으로
  계약 모듈이 생산자다(양쪽 LLM이 모두 소비자라서).

### 5-5. 스키마 변경 절차

- 변경은 소유자가 주도하고, 소비자는 자기 접점 한 곳만 고친다. **추가는 자유, 변경·삭제는 사전 조율.**
- raw 변경 감지: `analytics/test/run.sh` 하니스 — 시드가 raw에 직접 INSERT하므로 사실상 계약 테스트.
  CI 연결 권장(§9).
- 분석 결과 변경: 뷰 SQL·record·DDL 세 곳 모두 분석 작업 소유라 한 PR에서 처리하고,
  미러의 컬럼 대조 가드가 불일치를 쓰기 시점에 검출한다.

### 5-6. 표기 원칙

표본 크기가 약점으로 안 보이게 UI는 %·라벨 중심. 백엔드는 `sampleSize`와 비율의 분자·분모 원값을
항상 제공하고, 노출·전환은 프론트가 정한다. 추천 근거도 같은 원칙 — 축별 점수 분해와
팩트 원값을 항상 제공하고, 노출 수위는 프론트가 정한다.

### 5-7. 검증 컨벤션

분석 뷰는 SQL 하니스(더미 시드 + BEGIN/ROLLBACK 격리)로 기대값을 고정.
Java는 Testcontainers/MockMvc. LLM/VLM 호출은 테스트에서 실 API를 때리지 않는다(포트 fake).
매칭 엔진은 가중치 프리셋별 기대 순위 고정 테스트 + 점수 분해·저장값 일치 검증.

## 6. 현재 상태 · 작업 트랙

> 상태가 바뀌면 이 표를 갱신한다. ✅ 완료 · 🔨 진행 중 · ⬜ 대기 · ⏸ 보류

**운영 중**: crawler 파이프라인(discover→qualify→aggregate). was 랭킹 대시보드는 피봇으로
화면 구성에서 탈락 — 제거 예정(태스크 X).

**완료된 데이터 층 기반** (피봇 후에도 유효 — 프로필·근거 화면 재료.
설계: [specs/2026-07-12-analytics-data-layer-design.md](docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md)):

| # | 태스크 | 내용 | 상태 |
|---|---|---|---|
| A | 분석 기반 | base 뷰·최근 N개 윈도우 뷰(raw 접촉은 base 뷰만) + 설정 키 + `contract-analysis` 골격 + 타입 미러·SQL 테스트 하니스 | ✅ |
| F | LLM 공통 | 호출 골격 + 정확도/비용 스파이크 (→ 태스크 L에서 `llm-core`로 분리 예정) | ✅ |
| B1 | 게시물 비LLM 집계 | 서빙 뷰·미러 4종 (accounts·contents·content_comments·content_metric_snapshots) | ✅ |
| B2 | 댓글 LLM | 감성·키워드·구매의도 → 집계 + 미러 | ✅ |
| B3 | 콘텐츠 LLM | 감지 + 콘텐츠 속성 + "왜 잘됐나" | ✅ |

**캠페인 추천 작업 트랙** (구조 설계: [specs/2026-07-13-campaign-recommendation-pivot-design.md](docs/superpowers/specs/2026-07-13-campaign-recommendation-pivot-design.md)):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| V | 어휘 계약 | 매칭 어휘(카테고리·속성·톤) enum을 `contract-analysis`에 확정 | — | ⬜ |
| L | LLM 공유 모듈 | F 산출물(호출 골격)을 `llm-core`로 분리, analytics·was 공용 | — | ⬜ |
| R1 | 프로필 비LLM 축 | `influencer_profiles` 뷰·미러 (정체성·규모·성과·상업성·오디언스 반응) — 구 C1 재정의 | V | ⬜ |
| R2 | 프로필 LLM 축 | 페르소나·광고 유형 (계정 LLM) — 구 C2 재정의 | V, L, R1 | ⬜ |
| G | 캠페인 도메인 | `app` 스키마: 캠페인·추천 결과·후보 연결 + 비동기 잡 골격(상태 머신) | — | ⬜ |
| W1 | 브리프 이미지 분석 | 제품 이미지 VLM → 제품 속성 (어휘 계약 준수) | V, L, G | ⬜ |
| M | 매칭 엔진 | 하드 필터·정규화 SQL + 가중 합산 Java + 가중치 프리셋 테이블 + 근거 확정 저장 | R1, G, W1 | ⬜ |
| W2 | 근거 서술 | 저장된 팩트 기반 LLM 서술 | M, L | ⬜ |
| API | 캠페인 API | 제출·진행 상태·결과 조회 + 후보 연결 | G, M | ⬜ |
| D·E | 근거 하위 화면 API | 게시물 드로어·인플루언서 상세 | B1, R1 | ⬜ |
| X | 랭킹 대시보드 제거 | was 랭킹 화면·전용 미러 의존 정리 | — | ⬜ |
| ⏸ | 레퍼런스 콘텐츠 매칭 | 참고 이미지/게시물 → 비주얼 톤·스타일 매칭 | — | ⏸ |
| ⏸ | 브리프발 발굴 트리거 | 풀 부족 시 crawler 발굴 연동 | — | ⏸ |

권장 순서: V·L·G(기반, 병렬 가능) → R1 → W1·R2 → M → W2 → API. X는 아무 때나.
상세 구현 계획은 태스크 착수 시 작성.

## 7. 데이터 제약 (해석 주의 — 모든 지표·매칭 설계의 전제)

- **피드 게시물은 조회수가 항상 NULL** (인스타가 공개 안 함). 평균·히트·확산배율 계산 시 NULL 규칙 필수 —
  프로필의 조회 기반 축도 동일.
- 조회수 = 인스타 공개 재생수(`videoPlayCount`, 폴백 `videoViewCount`). 비로그인 취득 가능 실측 확인(07-10).
- 게시물 지표는 **중복 크롤링으로 스냅샷이 누적**된다(2026-07-12 도입).
  서빙 기본 경로는 최신 스냅샷 기준, 시점별 조회는 스냅샷 이력(`content_metric_snapshots`)으로.
- **오디언스 인구통계(연령·성별) 없음** — 브리프 폼에 "타깃 연령대"류 필드를 넣어도 매칭할 데이터가
  없다. 폼 필드는 프로필이 답할 수 있는 축으로만 설계할 것.
- 댓글은 게시물당 **최대 50개** 수집. 저장·공유·도달·노출 지표 없음. 팔로워는 qualify 시점 값.
- LLM 댓글 분류 실측 비용: 게시물 1,000건당 Opus ≈ $61 / haiku ≈ $12.2 (동기·무캐시·무배치 기준).

## 8. 결정 기록

> 새 결정은 맨 위에 추가. 전말은 링크된 dated 문서에.

| 날짜 | 결정 | 근거/상세 |
|---|---|---|
| 2026-07-13 | **제품 피봇: 캠페인 브리프 → 인플루언서 추천 + 근거.** A안 채택(analytics=프로필 팩토리 / 캠페인 파이프라인은 was 소유 — §3 신설). LLM 소속 이원화(raw 대상=분석 층, 캠페인 입력 대상=was), `llm-core` 공유 모듈 신설 예정, 매칭 어휘는 `contract-analysis`에. 추천 3기둥(프로필 미러 / 어휘 계약 / 매칭+근거 확정 저장). C1·C2→R1·R2 재정의, G→캠페인 도메인 확장, 랭킹 대시보드 제거(X). 레퍼런스 콘텐츠 매칭·발굴 트리거는 보류. 이 문서를 캠페인 플로우 중심으로 전면 재작성(§ 번호 변경: 원칙 §4→§5) | [specs/2026-07-13-campaign-recommendation-pivot-design.md](docs/superpowers/specs/2026-07-13-campaign-recommendation-pivot-design.md) |
| 2026-07-13 | B1 잔여분 `content_metric_snapshots` 미러 개통 — base 뷰에 이력 노출(`v_base_detail_history`) 추가, 서빙은 최신(`contents`)/이력(스냅샷) 분리 완성. was의 as-of 조회(태스크 D) 재료 | [plans/2026-07-13-task-b1-snapshot-mirror.md](docs/superpowers/plans/archive/2026-07-13-task-b1-snapshot-mirror.md) |
| 2026-07-12 | LLM 코드 모듈 소속 = analytics 확정 (포트/어댑터, 테스트는 fake). 댓글 분류 배치 개통 — 기본 게이트 off, 비용 가드 app_setting | [plans/2026-07-12-task-f-b2-llm-comment-classification.md](docs/superpowers/plans/archive/2026-07-12-task-f-b2-llm-comment-classification.md) |
| 2026-07-12 | 게시물 **중복 크롤링 도입** — 지표 스냅샷 누적. 분석 층 서빙을 최신/이력으로 분리(`contents` = 최신, `content_metric_snapshots` = 시점별, B1에서 구현). as-of 선택 규칙은 D에서 | [specs/2026-07-12-analytics-data-layer-design.md](docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md) |
| 2026-07-12 | **미러를 명시적 타입 기반으로 재설계**(뷰 SQL=계산 / Flyway DDL=저장 / 공유 record=자바 그릇, TRUNCATE+INSERT, 컬럼 대조 가드) — 기존 제네릭 미러 폐기. **계약 모듈 `contract-analysis` 신설**(생산자+소비자 쌍 성립). 모듈 공유 원칙(현 §5-4) 확정. **기존 analytics 구현(뷰 소스·하니스·미러 코드) 전체 초기화 — 백지 재구축** | [specs/2026-07-12 §8](docs/superpowers/specs/2026-07-12-detail-analysis-design.md) |
| 2026-07-12 | 3-tier 확정: 미러=tier 경계(필수), LLM=분석 층 소속(07-13 이원화로 개정), 태스크 A~G 분해. **서비스 데이터**(was가 쓰는 앱 데이터)는 분석 결과와 스키마 분리(`app`), 물리 분리 고려 | [specs/2026-07-12-detail-analysis-design.md](docs/superpowers/specs/2026-07-12-detail-analysis-design.md) |
| 2026-07-10 | 상세 분석 확정안(드로어 v3·인플루언서 v4) + 구현 계획 초안 3건 — 07-13 피봇으로 화면 구도는 대체됨 | [plans/archive/2026-07-10-*](docs/superpowers/plans/archive/) |
| 2026-07-09 | 모노레포 통합(crawler/analytics/was), was 랭킹 대시보드, 미러 도입 | [plans/2026-07-09-monorepo-migration.md](docs/superpowers/plans/archive/2026-07-09-monorepo-migration.md) |
| 2026-07-09 | 분석 = SQL 뷰 방식(A안), `analytics` 스키마, 더미 시드 검증 | [specs/2026-07-09-analytics-catalog-design.md](docs/superpowers/specs/2026-07-09-analytics-catalog-design.md) |
| 2026-07-09 | 제품 방향: 분석 단위 = 크리에이터, 마이크로인플루언서 발굴 | [specs/2026-07-09-influencer-analysis-decisions.md](docs/superpowers/specs/2026-07-09-influencer-analysis-decisions.md) |
| 2026-07-07 | crawler: Apify 원형(raw) 적재 + discover→qualify→aggregate 3단계 | [specs/2026-07-07-crawler-design.md](docs/superpowers/specs/2026-07-07-crawler-design.md) |

## 9. 미결 (팀 논의 대기)

| 항목 | 상태 |
|---|---|
| 브리프 폼 필드 확정 | 프로필이 답할 수 있는 축 기준으로 — 태스크 G/M 착수 시 확정 (§7 인구통계 제약 주의) |
| 가중치 프리셋 초기값 | 캠페인 목표별 축 가중치·정규화 방식 — 태스크 M 착수 시 확정, 이후 데이터로 튜닝 |
| 제품 이미지 저장 위치 | 파일 스토리지 vs DB — 태스크 G 착수 시 결정 |
| 계약 테스트 CI 연결 | raw 변경 PR에서 `analytics/test/run.sh` 자동 실행 — CI 환경·도입 시점 |
| 드로어 댓글 카피 | "214개 분석" 불가 → "최근 최대 50개" 정정 or 상한 상향+비용 재승인 |
| LLM 모델 | F 스파이크 결과로 결정 (기본 opus, haiku는 1/5 비용) |
| 미러 갱신 주기 | 현재 수동 1회. 자동화 여부·주기 (프로필 신선도가 추천 품질에 직결 — 피봇 후 중요도 상승) |
| 감성 비율 분모 | 기본 표기는 전체(스팸 포함), 원값 제공으로 프론트 전환 가능 |
| 미러 부분 실패 시맨틱 | 러너는 fail-fast — N번째 spec 실패 시 이후 spec은 이전 실행 상태로 남음(신선/스테일 혼재). 갱신 메타 기록 or 실패 집계 방식 결정 |

## 10. 문서 맵과 수명 규칙

- **이 문서** — 현재 유효한 구조·상태·결정. 문서의 유일한 진입점, 항상 최신 유지
- [crawler/README.md](crawler/README.md) — 수집 파이프라인 실행·운영
- `docs/superpowers/specs/` — 설계 기록(ADR 성격). **영구 보존·내용 불변** — 대체되면 첫머리 상태 헤더만 갱신
- `docs/superpowers/plans/` — 상세 구현 계획(소모품). 태스크 착수 시 작성, **실행 완료·폐기 시 `plans/archive/`로 이동**
- **상태 헤더 규칙**: 모든 dated 문서는 첫머리에 상태를 단다 —
  `> 상태: 🟢 활성 · ✅ 구현/실행/반영됨 · 🗄 대체됨 → 링크 · ⏸ 보류`
