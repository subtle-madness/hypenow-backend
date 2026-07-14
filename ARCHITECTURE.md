# hypenow 백엔드 — 메인 설계 문서

> **살아있는 문서.** 구조·결정이 바뀌면 이 문서를 먼저 고친다. 상세한 시점 기록(왜 그렇게 정했는지의
> 전말)은 `docs/superpowers/specs/`의 dated 문서에 남기고, 여기서는 **현재 유효한 그림**만 유지한다.
> 각 섹션을 고칠 때 하단 [결정 기록](#7-결정-기록)에 한 줄을 추가한다.
>
> 마지막 갱신: 2026-07-14

## 1. 제품 한 장 요약

**hypenow** — 인스타그램 뷰티 인플루언서 콘텐츠 분석 툴.
타깃: **마이크로인플루언서를 발굴하려는 뷰티 브랜드 마케터.**

MVP 범위:
- 콘텐츠 랭킹 페이지 (운영 중 — was 대시보드)
- **게시물 상세 드로어** — 랭킹에서 클릭 시 (성과·벤치마크 + 댓글 분석·감지·"왜 잘됐나")
- **인플루언서 상세 페이지** — 드로어에서 진입 (정체성·성과·일관성·커머셜 + 페르소나·AI 브리핑)
- **후보 관리** — 후보 저장·상태(검토중/컨택 예정/협업 중)·메모

기준 기획: 상세 분석 확정안 (2026-07-10 Artifact, 게시물 드로어 v3 + 인플루언서 상세 v4)
프론트: celfit-front.vercel.app (별도 저장소)

## 2. 시스템 구조

3-tier. 층 사이는 DB로만 통신한다 (모듈 간 HTTP/큐 없음).

```
[크롤링]  crawler  ──쓰기──▶  raw DB (crawler)          크롤링 원본. 분석의 고정 입력
[분석]    analysis ──읽기── raw DB
                   ──쓰기──▶  분석 결과 (analysis DB)    was가 보여줄 데이터
[서빙]    was      ──읽기── 분석 결과 ──▶ celfit-front
                   ──읽기/쓰기──▶  서비스 데이터 (app 스키마)   로그인·후보 관리 등 일반 앱 데이터
```

| 모듈 | 데이터 접근 | 역할 | 기술 |
|---|---|---|---|
| `crawler` | raw DB 쓰기 | Apify로 발굴→판정→상세 수집, 원형(raw) 적재 | Spring Boot, JPA, Flyway, Thymeleaf 어드민 |
| `analytics` | raw 읽기 → 분석 결과 쓰기 | 분석 뷰 정의 + **미러**(분석 결과를 analysis DB에 채움). LLM 분석도 이 층 소속 | 헤드리스 배치, JdbcTemplate ×2 |
| `was` | 분석 결과 읽기 + 서비스 데이터 읽기/쓰기 | REST API 서빙 + 서비스 기능(로그인·후보 관리 등) | Spring Boot, JdbcClient |
| `contract-analysis` *(신설 예정)* | — | 분석 결과의 record·enum — 순수 JDK 계약 타입 (§4-4). analytics·was가 의존, crawler 무관 | Java record |

**데이터 배치**: 저장 영역은 세 가지 — raw(크롤링 원본) / 분석 결과(미러 테이블) / **서비스 데이터**(was가
쓰는 일반 앱 데이터: 로그인·후보 관리 등). 서비스 데이터는 분석 결과와 **스키마로 분리**(analysis DB 내
`app` 스키마)하고, 셋 모두 현재 **한 Postgres 인스턴스**(포트 5433)에 논리 분리만 되어 있다. 부하를 보고
물리 분리를 결정한다 — 접근 규율(§4-4)을 지키는 한 어느 경계든 설정 변경으로 분리 가능하다.

**미러란**: raw DB에 정의된 분석 뷰(`analytics.*`)를 실행해 결과를 analysis DB의 테이블로 채우는
배치. 레플리카가 아니라 **분석 층이 결과물을 내놓는 행위 그 자체** — 뷰는 DB를 못 넘으므로 이 잡이
tier 경계다. 방식은 명시적·타입 기반(§4-3). ※ 과거의 `MaterializationService`(메타데이터 기반 제네릭
복사)는 잘못된 작업 지시로 생긴 산출물이라 07-12에 삭제했고, 태스크 A에서 §4-3 방식으로 새로 만든다.

## 3. 데이터

### raw DB (crawler 소유 — 분석 작업에서 불변)

| 테이블 | 내용 |
|---|---|
| `content` | 게시물 메타 (short_code, owner, uploaded_at, 분류 계층, ad_marked, 상태) |
| `raw_post_detail` | Apify 상세 payload(jsonb) + generated 컬럼 (likes, comments_count, video_play_count, caption) |
| `raw_comment` | 댓글 원문 payload + generated (writer, text, written_at) |
| `raw_profile` | 프로필 스냅샷 payload + generated (username, followers) |
| `app_setting` | 런타임 설정 key-value (분석 뷰도 여기서 임계값을 읽음) |

### 분석 뷰 (raw DB의 `analytics` 스키마)

`analytics/views/NN_*.sql` 번호순 적용 컨벤션. **기존 소스(00~08)는 2026-07-12 초기화** —
로컬 DB에 적용된 뷰는 남아 있으나 소스는 백지이며, 태스크 A부터 §4 원칙대로 재작성한다.
과거 뷰 정의는 git 이력과 `docs/superpowers/plans/2026-07-10-*` 문서에 보존돼 있다.

### analysis DB

- **분석 결과** — 뷰 결과가 미러되는 테이블(Flyway로 명시 정의 — §4-3). analytics가 쓰고 was가 읽는다.
- Flyway 이력은 스키마별 분리 소유 — 분석 결과는 analytics가, `app` 스키마는 was가 관리.
- **서비스 데이터 (`app` 스키마)** — 로그인·후보 관리 등 was가 직접 읽고 쓰는 일반 앱 데이터.
  분석 결과와 스키마로 격리, 나중에 물리 분리 가능.

## 4. 관통하는 설계 원칙

### 4-1. 최근 N개 윈도우

모든 계정 단위 지표는 계정별 최신 게시물 N개(기본 12)만 잘라 계산한다. 재크롤링이 누적돼도
계정 간 비교가 공정해지고, UI 각주 "최근 N개 기준"이 이 한 곳을 가리킨다.
N을 포함한 숫자 경계값·임계값은 `app_setting`(key-value)이 단일 원천 — 뷰가 직접 읽어 재배포 없이 조정.

### 4-2. 로직의 자리 — 집합 연산은 SQL, 절차는 Java

비즈니스 로직의 자리는 언어가 아니라 성격으로 정한다. LLM 분석도 별도 트랙이 아니라 분석 층의 일부다.

| 로직 성격 | 사는 곳 | 예 |
|---|---|---|
| 집합 연산 (집계·순위·비율·윈도우) | SQL 뷰 (raw DB `analytics` 스키마) | 랭킹, 벤치마크, 히트율, 모멘텀 |
| 절차·외부 연동 (호출→파싱→저장) | Java (분석 층) | LLM 댓글 분석 |
| 상태 변화·트랜잭션 | Java (was) | 후보 상태 전이(검토중→컨택 예정→협업 중) |
| 표현 조립 | Java (was) | 경과일 계산, 응답 블록 조립 |

### 4-3. 미러 = 명시적 타입 기반 materialization

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

### 4-4. 모듈 공유 원칙

- **모듈은 서로 import 하지 않는다.** 유일한 예외는 계약 모듈 **`contract-analysis`** —
  분석 결과의 record·enum만 담고(순수 JDK, Spring/JPA 의존 금지), 생산자 analytics와 소비자 was가
  의존한다. crawler와는 무관.
  수록 기준: **"동일 형태를 다루는 Java 생산자+소비자 쌍"이 성립하는 타입만.** 한 모듈만 쓰는 타입은
  그 모듈에 둔다. util·비즈니스 로직은 넣지 않는다.
- **모듈 간 계약은 전부 데이터 계약이다:**

  | 경계 | 계약 | 정의하는 쪽 |
  |---|---|---|
  | crawler → analytics | raw 스키마 (generated 컬럼 + 뷰가 쓰는 payload 키) | crawler |
  | analytics → was | 분석 결과 테이블 + 공유 record | analytics |
  | was → front | REST JSON | was |

- **저장소 접근:** 소유한 저장소에는 엔티티 자유(JPA 가능 — crawler의 raw, was의 `app` 스키마,
  분석 층의 LLM 결과 테이블). 남의 저장소는 읽기 전용 쿼리 + record 매핑만.
- **was 접근 규율:** raw DB 접근 금지. 분석 결과는 읽기만, 쓰기는 `app` 스키마에만.
  분석 결과와 서비스 데이터를 SQL 조인하지 않는다(조합은 was 코드에서) — 물리 분리 대비.
- **raw 스키마 지식은 base 뷰에 격리** — raw 테이블·payload를 직접 만지는 SQL은 base 뷰만
  (재구축 시 처음부터 이 규칙 적용). 분석 층의 Java도 crawler 코드가 아닌 SQL로 raw를 읽는다.
- **분류값·라벨은 생산자가 확정, 소비자는 전달만** — tier·감성분류 같은 어휘는 분석 층이 문자열로
  확정해 데이터에 박고, was는 해석·분기 없이 그대로 내려보낸다.

### 4-5. 스키마 변경 절차

- 변경은 소유자가 주도하고, 소비자는 자기 접점 한 곳만 고친다. **추가는 자유, 변경·삭제는 사전 조율.**
- raw 변경 감지: `analytics/test/run.sh` 하니스 — 시드가 raw에 직접 INSERT하므로 사실상 계약 테스트.
  CI 연결 권장(§8).
- 분석 결과 변경: 뷰 SQL·record·DDL 세 곳 모두 분석 작업 소유라 한 PR에서 처리하고,
  미러의 컬럼 대조 가드가 불일치를 쓰기 시점에 검출한다.
- Flyway 버전 번호는 공유 DB에서 세션 간 충돌 자원 — 태스크 착수 시 `flyway_schema_history`를
  확인하고 트랙별 번호대를 간격을 두고 예약한다(예: B트랙 한 자릿수, 인플루언서 트랙 V10·V20대).

### 4-6. 표기 원칙

표본 크기가 약점으로 안 보이게 UI는 %·라벨 중심. 백엔드는 `sampleSize`와 비율의 분자·분모 원값을
항상 제공하고, 노출·전환은 프론트가 정한다.

### 4-7. 검증 컨벤션

분석 뷰는 SQL 하니스(더미 시드 + BEGIN/ROLLBACK 격리, 태스크 A에서 재구축)로 기대값을 고정.
Java는 Testcontainers/MockMvc. LLM 호출은 테스트에서 실 API를 때리지 않는다(포트 fake).

## 5. 현재 상태 · 작업 트랙

> 상태가 바뀌면 이 표를 갱신한다. ✅ 완료 · 🔨 진행 중 · ⬜ 대기 · ⏸ 보류

**운영 중**: crawler 파이프라인(discover→qualify→aggregate), was 랭킹 대시보드(analysis DB의 기존
미러 테이블을 읽음). ※ analytics 구현은 2026-07-12 초기화 — DB에 남은 뷰·미러 테이블은 동작하지만
소스는 백지, 태스크 A부터 재구축.

**상세 분석 작업 트랙** (구조 설계: [specs/2026-07-12-detail-analysis-design.md](docs/superpowers/specs/2026-07-12-detail-analysis-design.md) ·
데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md)):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| A | 분석 기반 | base 뷰·최근 N개 윈도우 뷰 재작성(raw 접촉은 base 뷰만) + 설정 키 + `contract-analysis` 골격 + 타입 미러·SQL 테스트 하니스 구축 | — | ✅ |
| F | LLM 공통 | 호출 골격 + **정확도/비용 스파이크** + 모듈 소속 확정 — F-2(VLM)는 B3에서 실험 | — | ✅ |
| B1 | 드로어 비LLM 집계 | 서빙 뷰·미러 4종 (accounts·contents·content_comments + 지표 스냅샷 이력 `content_metric_snapshots` — 07-13 개통) | A | ✅ |
| B2 | 드로어 댓글 LLM | 감성·키워드·구매의도 → 집계 + 미러 | F | ✅ |
| B3 | 드로어 콘텐츠 LLM | 감지 + 콘텐츠 속성 + "왜 잘됐나" (07-14 VLM 잔여분 개통 — 어휘는 celfit-front 계약, 유통사 감지 포함) | F, B2 | ✅ |
| C1 | 인플루언서 비LLM 집계 | AccountReport 결정 지표 — 계정 요약·카테고리 믹스·게시물 시계열 3종 뷰 + 미러 | A | ✅ |
| C2 | 인플루언서 계정 LLM | AccountReport 카피 7종(tagline~paceNote) — stale+쿨다운 재분석·이력 INSERT. 캡션 분류(브랜드·광고·카테고리)는 별도 후속(§8) | F, C1 | ✅ |
| D | 드로어 API | `GET /api/posts/{shortCode}` — post/account/comments + analysis 블록·댓글 aiCategory(B2·B3 산출물 포함, 1회 호출) | B1, B2·B3(확장분) | ✅ |
| D3 | 드로어 as-of | `GET /api/posts/{shortCode}?endDate=` — 집계 기간 끝 시점 스냅샷으로 지표 재구성(captured_at ≤ endDate의 KST 하루 끝 중 최신), 스냅샷 없으면 404(그 시점 화면에 부재). 생략 시 최신 | D, B1(스냅샷 미러) | ✅ |
| H | 랭킹 목록 API | `GET /api/contents` — 프론트 URL 파라미터 계약(start_date·end_date·main/mid/sub_category·content_type·follower·ad_type·distributor·sort·q) 그대로. 기간=게시일 필터, 지표=end_date 시점 스냅샷, 분석 완료 콘텐츠만, 기본 정렬 hype. 유통사 필터는 컬럼 신설(VLM 개통) 전까지 매칭 0 | D3(as-of 규칙 공유), B3(카테고리·광고·유통사 어휘) | ✅ |
| E | 인플루언서 API | `GET /api/influencers/{handle}` — profile(accounts 조합) + report(AccountReport 결정 지표: stats·trend·chart·contentMix·ads·activity). 표현 조립(경과일·isActive 14일·lastAdNote·strip)은 was 몫, LLM 카피 7종은 C2 additive | C1, C2(확장분) | ✅ |
| G | 서비스 데이터 | `app` 스키마 신설 + 후보 저장·상태·메모 (로그인 등 일반 앱 데이터의 기반) | 독립 | ⬜ |

권장 순서: A → B1, 병렬로 F(스파이크). 상세 구현 계획은 태스크 착수 시 작성.

## 6. 데이터 제약 (해석 주의 — 모든 지표 설계의 전제)

- **피드 게시물은 조회수가 항상 NULL** (인스타가 공개 안 함). 평균·히트·확산배율 계산 시 NULL 규칙 필수.
- 조회수 = 인스타 공개 재생수(`videoPlayCount`, 폴백 `videoViewCount`). 비로그인 취득 가능 실측 확인(07-10).
- 게시물 지표는 **중복 크롤링으로 스냅샷이 누적**되지만, 서빙 지표(`contents`)는 **업로드 +3일 이후
  가장 이른 스냅샷으로 고정**(07-14 정정 ③ — 키 `analytics.metric-pin-days` 기본 3, B3 숙성 가드와 같은
  3일 기준). 고정 후보가 없으면 최신 폴백(구크롤러 조기 수집 잔재 5건 보호 — 개편 크롤러는 3일 미경과를
  수집하지 않아 소멸 예정). 메타(썸네일·캡션)는 최신 스냅샷. 시점별 조회는 스냅샷 이력
  (`content_metric_snapshots`, B1)으로 — D3·H의 end_date as-of는 이력 조회로 고정 기준과 공존
  (기간 화면 재현·인플루언서 상세 참조용). 추이 그래프 UI는 확정안에서 제외된 상태 유지(데이터만 보존).
- 댓글은 게시물당 **최대 50개** 수집 → 목업의 "214개 분석"은 불가, 카피 정정 필요(미결).
- 저장·공유·도달·노출 지표 없음. 팔로워는 qualify 시점 값.
- LLM 댓글 분류 실측 비용: 게시물 1,000건당 Opus ≈ $61 / haiku ≈ $12.2 (동기·무캐시·무배치 기준).
  VLM(썸네일)은 건당 ≈ $0.03~0.05 (opus 4.8, 07-14 실측).
- **인스타 CDN 썸네일 URL은 수집 후 ~4일이면 만료**(403) — VLM은 최신 수집분에만 가능(분석 잡이
  프리체크로 만료분은 VLM 컬럼 NULL 저장). VLM 데이터를 채우려면 크롤링 직후 분석 배치를 돌릴 것.

## 7. 결정 기록

> 새 결정은 맨 위에 추가. 전말은 링크된 dated 문서에.

| 날짜 | 결정 | 근거/상세 |
|---|---|---|
| 2026-07-14 | **게시물 지표 +3일 고정 구현(정정 ③)** — `v_contents` 지표를 업로드 +3일 이후 **가장 이른** 스냅샷으로 고정(키 `analytics.metric-pin-days` 기본 3 — B3 숙성 가드의 "게시 후 3일"과 같은 기준, 키 공유 권장). 고정 후보 없으면 최신 폴백(구크롤러 조기 수집 잔재 5건 보호, 개편 크롤러는 3일 미경과 미수집이라 소멸 예정). 메타(썸네일·캡션)는 최신 유지(서명 URL ~4일 만료 대응). 적용은 `v_contents`만 — 계정 집계(01·10)·B3 기준선(03)은 최신 기준 유지, 미러 DDL·record 무변경. 실데이터 137건 전행 동일 확인(회귀 0). D3·H의 end_date as-of는 이력 조회로 공존 — 매일 재크롤 개시 후 정합은 §8 | 07-14 정정 ③ 구현 (이 PR) |
| 2026-07-14 | **태스크 E: 인플루언서 상세 API 계약 확정** — 응답 = profile(accounts ⊕ account_summaries, accounts 부재 시 표시 필드 null) + report(C1 지표 전달). 주 리소스는 account_summaries(부재 404). 표현 조립 이행: 경과일 24h 단위·isActive=14일 미만·lastAdNote 문구("마지막 광고 오늘"/"N일 전")·광고 strip(시계열 순 bool). comparison은 organic/ad 평균 한쪽이라도 null이면 블록 null. LLM 카피 7종(summary·trend.note·traits·headline·brands·paceNote·tagline)은 필드 부재 → C2 additive | [plans/2026-07-14-task-e-influencer-api.md](docs/superpowers/plans/2026-07-14-task-e-influencer-api.md) |
| 2026-07-14 | **크롤링 구조 개편 방향 확정** — 인플루언서 리스트를 먼저 확보 → 계정별 최근 3개월 게시물 크롤 → 매일 신규 게시물만 추가 크롤(기존 게시물 재크롤은 조회수 등 지표 갱신 수준, 콘텐츠 재분석 없음). 게시물 분류는 discovery 키워드 대신 **caption 감지**로 가는 방향. 파생 후속 3건(윈도우 기간 전환·B3 숙성 가드·캡션 분류 태스크)은 §8 | [specs/2026-07-14-c2-account-llm-design.md §6](docs/superpowers/specs/2026-07-14-c2-account-llm-design.md) |
| 2026-07-14 | **태스크 C2 설계 확정** — AccountReport 카피 7종을 계정당 LLM 1콜로 생성, `account_analyses`(V20 — V11에서 renumber, B3 잔여분 선점 충돌) 이력 INSERT. 재분석 = 신규 즉시 / stale(새 게시물)+**쿨다운 7일**(매일 크롤 대비 비용 가드). adHeadline은 광고 비교 데이터 있을 때만. 계약 record `AccountAnalysis` 신설 — 분석 층 테이블도 생산자가 record로 조립·소비하면 §4-4 쌍 성립 | [specs/2026-07-14-c2-account-llm-design.md](docs/superpowers/specs/2026-07-14-c2-account-llm-design.md) |
| 2026-07-14 | **B3 VLM 잔여분 개통** — F-2 스파이크(실 8건) 전 항목 채택: 입력은 URL 불가(인스타 CDN을 Anthropic이 robots.txt로 거부)→직접 다운로드+base64. `content_analyses.detected_distributors jsonb` 신설(유통사 감지 — 어휘 올리브영/다이소 고정). **분류 어휘 = celfit-front 배포본 verbatim 계약**: main_category 영문 slug 6종(CHECK 추가), sub_categories = 중분류+소분류 한글 라벨 배열(프론트가 배열 포함으로 매칭), detected_product_categories = 소분류 라벨 — 단일 원천은 analytics `BeautyTaxonomy`(프롬프트+sanitize 공용). 썸네일 서명 URL ~4일 만료 대응: 분석 대상 수집 최신순 + HEAD 프리체크(만료는 VLM만 NULL). 게이트 on 실행으로 실데이터 7건 채움 확인 | [plans/archive/2026-07-14-task-b3-vlm-remainder.md](docs/superpowers/plans/archive/2026-07-14-task-b3-vlm-remainder.md) |
| 2026-07-14 | **as-of 선택 규칙 확정 + 서빙 트랙 신설(D3·H)** — celfit-front 실동작 확인: 랭킹 집계 기간 = **게시일(postedAt) 범위 필터**(URL `start_date`·`end_date`), "그 기간 화면"의 지표 시점 = **end_date**. 상세·목록 모두 `captured_at ≤ end_date(KST 하루 끝)` 중 최신 스냅샷으로 지표 구성, 그 시점 스냅샷이 없는 콘텐츠는 화면에 부재(목록에서 필터링, 상세는 404). §5에 D3(상세 as-of)·H(랭킹 목록 API — 프론트 URL 파라미터가 사실상 확정 계약) 신설 | 프론트 URL 계약·기간 필터 실측 (2026-07-14 세션) |
| 2026-07-14 | **캠페인 추천 피봇 검토 후 취소** — "브리프 제출→인플루언서 추천+근거"로의 전환을 07-13~14 검토(구조 설계·어휘 계약 계획까지 작성)했으나 기존 방향(콘텐츠 랭킹+상세 분석 MVP) 유지로 결정. 검토 산출물은 develop 미머지 — 닫힌 PR #5·로컬 브랜치 `docs/campaign-recommendation-pivot`에 보존, 본 문서 기준 태스크 트랙(§5)은 변동 없음 | 닫힌 [PR #5](https://github.com/subtle-madness/hypenow-backend/pull/5) |
| 2026-07-13 | **태스크 D2: 상세 API에 B2·B3 산출물 additive 확장** — comments.items[].aiCategory(분류 LEFT JOIN, 미분류 null) + analysis 블록(content_analyses 1행: AI 텍스트·기준선 스냅샷·카테고리 맥락·VLM·댓글 진정성, 미분석 null). 읽기 record는 was 로컬(분석 층 소유 테이블은 공유 형태 미성립 — §4-4), jsonb는 실 JSON 구조로 서빙. 릴스 개별 바 차트는 인플루언서 상세(E) 소관. as-of 서빙은 `content_metric_snapshots` 미러(별도 세션 분리) 후 was에서 | [plans/2026-07-13-task-d2-analysis-block.md](docs/superpowers/plans/archive/2026-07-13-task-d2-analysis-block.md) |
| 2026-07-13 | C1은 **celfit-front 실계약(AccountReport) 기준**으로 구현 — v4 목업 지표(중앙값·히트율·변동성·구간포지션 등) 폐기. 계정 평균 ER은 **followers 분모**(`avg_er_pct`, 게시물 ER의 views 분모와 공존), 기준 지표 폴백 `metric`('views'\|'likes')은 데이터에 확정 | [specs/2026-07-13-c1-account-detail-design.md](docs/superpowers/specs/2026-07-13-c1-account-detail-design.md) |
| 2026-07-13 | B1 잔여분 `content_metric_snapshots` 미러 개통 — base 뷰에 이력 노출(`v_base_detail_history`) 추가, 서빙은 최신(`contents`)/이력(스냅샷) 분리 완성. was의 as-of 조회(태스크 D) 재료 | [plans/2026-07-13-task-b1-snapshot-mirror.md](docs/superpowers/plans/archive/2026-07-13-task-b1-snapshot-mirror.md) |
| 2026-07-12 | **태스크 D: 상세 API 계약 확정** — 응답 블록=소스 테이블 1:1(post/account/comments), 참여율=(좋아요+댓글)/조회수(피드는 조회수 NULL이라 null)·경과일은 was 표현 조립, 댓글은 수집분 전체 서빙(좋아요순). LLM 블록(모달 우측 탭 5종·댓글 ai_category)은 필드 부재→B2·B3 산출물의 additive 확장은 후속 태스크. as-of 규칙은 스냅샷 미러 도입 시로 보류 유지. CORS `/api/**` GET(localhost:3000·celfit-front.vercel.app) | [plans/2026-07-12-task-d-post-detail-api.md](docs/superpowers/plans/archive/2026-07-12-task-d-post-detail-api.md) |
| 2026-07-12 | LLM 코드 모듈 소속 = analytics 확정 (포트/어댑터, 테스트는 fake). 댓글 분류 배치 개통 — 기본 게이트 off, 비용 가드 app_setting | [plans/2026-07-12-task-f-b2-llm-comment-classification.md](docs/superpowers/plans/archive/2026-07-12-task-f-b2-llm-comment-classification.md) |
| 2026-07-12 | 게시물 **중복 크롤링 도입** — 지표 스냅샷 누적. 분석 층 서빙을 최신/이력으로 분리(`contents` = 최신, `content_metric_snapshots` = 시점별, B1에서 구현). as-of 선택 규칙은 D에서 | [specs/2026-07-12-analytics-data-layer-design.md](docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md) |
| 2026-07-12 | **미러를 명시적 타입 기반으로 재설계**(뷰 SQL=계산 / Flyway DDL=저장 / 공유 record=자바 그릇, TRUNCATE+INSERT, 컬럼 대조 가드) — 기존 제네릭 미러 폐기. **계약 모듈 `contract-analysis` 신설**(생산자+소비자 쌍 성립). 모듈 공유 원칙(§4-4) 확정. **기존 analytics 구현(뷰 소스·하니스·미러 코드) 전체 초기화 — 백지 재구축** | [specs/2026-07-12 §8](docs/superpowers/specs/2026-07-12-detail-analysis-design.md) |
| 2026-07-12 | 3-tier 확정: 미러=tier 경계(필수), LLM=분석 층 소속, 태스크 A~G 분해. **서비스 데이터**(로그인·후보 관리 등 was가 쓰는 앱 데이터)는 분석 결과와 스키마 분리(`app`), 물리 분리 고려 | [specs/2026-07-12-detail-analysis-design.md](docs/superpowers/specs/2026-07-12-detail-analysis-design.md) |
| 2026-07-10 | 상세 분석 확정안(드로어 v3·인플루언서 v4) + 구현 계획 초안 3건(현재는 참고 자료) | [plans/2026-07-10-*](docs/superpowers/plans/) |
| 2026-07-09 | 모노레포 통합(crawler/analytics/was), was 랭킹 대시보드, 미러 도입 | [plans/2026-07-09-monorepo-migration.md](docs/superpowers/plans/2026-07-09-monorepo-migration.md) |
| 2026-07-09 | 분석 = SQL 뷰 방식(A안), `analytics` 스키마, 더미 시드 검증 | [specs/2026-07-09-analytics-catalog-design.md](docs/superpowers/specs/2026-07-09-analytics-catalog-design.md) |
| 2026-07-09 | 제품 방향: 분석 단위 = 크리에이터, 마이크로인플루언서 발굴 | [specs/2026-07-09-influencer-analysis-decisions.md](docs/superpowers/specs/2026-07-09-influencer-analysis-decisions.md) |
| 2026-07-07 | crawler: Apify 원형(raw) 적재 + discover→qualify→aggregate 3단계 | [specs/2026-07-07-crawler-design.md](docs/superpowers/specs/2026-07-07-crawler-design.md) |

## 8. 미결 (팀 논의 대기)

| 항목 | 상태 |
|---|---|
| 계약 테스트 CI 연결 | raw 변경 PR에서 `analytics/test/run.sh` 자동 실행 — CI 환경·도입 시점 |
| 드로어 댓글 카피 | "214개 분석" 불가 → "최근 최대 50개" 정정 or 상한 상향+비용 재승인 |
| LLM 모델 | F 스파이크 결과로 결정 (기본 opus, haiku는 1/5 비용) |
| 미러 갱신 주기 | 현재 수동 1회. 자동화 여부·주기 |
| 서비스 데이터 상세 | `app` 스키마 구성·로그인 방식 등은 G 착수 시 설계 |
| 감성 비율 분모 | 기본 표기는 전체(스팸 포함), 원값 제공으로 프론트 전환 가능 |
| 미러 부분 실패 시맨틱 | 러너는 fail-fast — N번째 spec 실패 시 이후 spec은 이전 실행 상태로 남음(신선/스테일 혼재). B1에서 갱신 메타 기록 or 실패 집계 방식 결정 |
| 윈도우 기간 전환 | 크롤링 개편(최근 3개월 확정 — §7 07-14) 착지 시 `v_recent_content`를 개수(12)→기간 기반으로 전환. B3 `recent12_*` 네이밍·프론트 "최근 12개" 표기 동반 수정 |
| B3 숙성 가드 | 매일 크롤 시 게시 직후 분석·영구 고정 방지 — 분석 대상 조건에 "게시 후 3일 경과" 추가 (07-14 확정, 재분석은 없음). 지표 +3일 고정과 같은 3일 기준 — `analytics.metric-pin-days` 키 재사용 권장 |
| D3·H 지표 고정 정합 | 매일 재크롤 개시 후 end_date=오늘 화면의 as-of(이력 최신)가 +3일 고정과 어긋남 — 목록·상세의 "현재" 지표를 `contents`(고정) 기준으로 전환 검토. 과거 기간 화면 재현·인플루언서 상세 참조용 이력 as-of는 유지 |
| 캡션 분류 태스크 | 게시물 분류(카테고리·브랜드·광고 여부)를 caption 감지로 — 신규 태스크. 인플루언서 패널 `ads.brands` 칩과 크롤 개편 후 main_group 결측 대응 포함 |
| Flyway missing 완화 국한 | `*:missing` 검증 완화(FlywayConfig)는 공유 dev DB 전용 양보 — 운영 프로파일 도입 시 dev 국한/제거. B3 잔여분 브랜치도 동일 완화 필요(머지 순서에 따라 develop 경유 해소) |

## 9. 문서 맵과 수명 규칙

- **이 문서** — 현재 유효한 구조·상태·결정. 문서의 유일한 진입점, 항상 최신 유지
- [crawler/README.md](crawler/README.md) — 수집 파이프라인 실행·운영
- `docs/superpowers/specs/` — 설계 기록(ADR 성격). **영구 보존·내용 불변** — 대체되면 첫머리 상태 헤더만 갱신
- `docs/superpowers/plans/` — 상세 구현 계획(소모품). 태스크 착수 시 작성, **실행 완료·폐기 시 `plans/archive/`로 이동**
- **상태 헤더 규칙**: 모든 dated 문서는 첫머리에 상태를 단다 —
  `> 상태: 🟢 활성 · ✅ 구현/실행/반영됨 · 🗄 대체됨 → 링크 · ⏸ 보류`
