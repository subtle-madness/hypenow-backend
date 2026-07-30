# hypenow 백엔드 — 메인 설계 문서

> **살아있는 문서.** 구조·결정이 바뀌면 이 문서를 먼저 고친다. 상세한 시점 기록(왜 그렇게 정했는지의
> 전말)은 `docs/superpowers/specs/`의 dated 문서에 남기고, 여기서는 **현재 유효한 그림**만 유지한다.
> 각 섹션을 고칠 때 하단 [결정 기록](#7-결정-기록)에 한 줄을 추가한다.
>
> 마지막 갱신: 2026-07-30

## 1. 제품 한 장 요약

**hypenow** — 인스타그램 뷰티 인플루언서 콘텐츠 분석 툴.
타깃: **마이크로인플루언서를 발굴하려는 뷰티 브랜드 마케터.**

MVP 범위 (07-14 정정 — 댓글 제외):
- 콘텐츠 랭킹 페이지 (운영 중 — was 대시보드)
- **게시물 상세 드로어** — 랭킹에서 클릭 시 (성과·벤치마크 + 감지·"왜 잘됐나" — 댓글 분석은 제외)
- **인플루언서 상세 페이지** — 드로어에서 진입 (정체성·성과·일관성·커머셜 + 페르소나·AI 브리핑)
- **후보 관리** — 후보 저장·상태(검토중/컨택 예정/협업 중)·메모
- ※ **댓글은 수집·분석 모두 MVP 제외**(07-14) — B2 구현은 보존, MVP 이후 재개.
  댓글 외 LLM 산출(콘텐츠 감지·종합 텍스트·계정 카피)은 전부 MVP 포함

기준 기획: 상세 분석 확정안 (2026-07-10 Artifact, 게시물 드로어 v3 + 인플루언서 상세 v4)
프론트: www.hypenow.io (Vercel, 별도 저장소 celfit-front)

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
| `analytics` | raw 읽기 → 분석 결과 쓰기 | 분석 뷰 정의 + **미러**(분석 결과를 analysis DB에 채움). LLM 분석도 이 층 소속 | 상주 서버(8082, 어드민 `/ui`) + one-shot CLI(cloud), JdbcTemplate ×2 |
| `was` | 분석 결과 읽기 + 서비스 데이터 읽기/쓰기 | REST API 서빙 + 서비스 기능(로그인·후보 관리 등) | Spring Boot, JdbcClient |
| `contract-analysis` *(신설 예정)* | — | 분석 결과의 record·enum — 순수 JDK 계약 타입 (§4-4). analytics·was가 의존, crawler 무관 | Java record |
| `monitoring` *(07-29 구현, 07-30 알람 이관)* | 사설 monitoring DB 읽기/쓰기 (raw·analysis DB 무권한. was는 `public` 조회 표면만 읽기 전용) + analysis DB `app` 스키마 두 객체만 읽기 전용(`alarm_reader`, 계약 v2 §6) | 시딩 캠페인 모니터링 — 계정 키워드 감시→후보 감지→**첫 감지 자동 추적**→게시물 추적 상태 기계(target=캠페인 단위), Hiker-only 일일 수집·추이 적재. **was 명령은 내부 HTTP API(DB-only 원칙의 명시적 예외 — §7 07-28), 조회는 was가 읽기 전용 SELECT**. **알람(이벤트 대장·이메일 발송)도 monitoring 소유** — was는 앱 내 알림 서빙만 | Spring Boot (8083) |

**데이터 배치**: 저장 영역은 세 가지 — raw(크롤링 원본) / 분석 결과(미러 테이블) / **서비스 데이터**(was가
쓰는 일반 앱 데이터: 로그인·후보 관리 등). 서비스 데이터는 분석 결과와 **스키마로 분리**(analysis DB 내
`app` 스키마)하고, 로컬은 셋 모두 **한 Postgres 인스턴스**(포트 5433)에 논리 분리만 되어 있다. 부하를 보고
물리 분리를 결정한다 — 접근 규율(§4-4)을 지키는 한 어느 경계든 설정 변경으로 분리 가능하다.
운영 서버(오라클)는 컨테이너로 물리 분리: `postgres`(analysis DB — 분석 결과 public + 서비스 app 스키마,
루프백 5432)와 `postgres-raw`(raw crawler DB, 루프백 5433 — 분석 잡 전용, was 접근 금지).

**미러란**: raw DB에 정의된 분석 뷰(`analytics.*`)를 실행해 결과를 analysis DB의 테이블로 채우는
배치. 레플리카가 아니라 **분석 층이 결과물을 내놓는 행위 그 자체** — 뷰는 DB를 못 넘으므로 이 잡이
tier 경계다. 방식은 명시적·타입 기반(§4-3). ※ 과거의 `MaterializationService`(메타데이터 기반 제네릭
복사)는 잘못된 작업 지시로 생긴 산출물이라 07-12에 삭제했고, 태스크 A에서 §4-3 방식으로 새로 만든다.

## 3. 데이터

### raw DB (crawler 소유 — 분석 작업에서 불변)

| 테이블 | 내용 |
|---|---|
| `influencer` | 계정 (username, status, followers, 뷰티 판정 5분류 beauty_class(+파생 beauty/beauty_company)/beauty_judged_at) |
| `content` | 게시물 제어 (short_code, content_type, owner, uploaded_at, origin DISCOVERY/ENUMERATION, status) — 캡션·지표 없음 |
| `raw_media_page` | 릴스 페이지 원형(HIKER_V2_CLIPS jsonb) — 릴스 캡션·지표·썸네일의 소스 |
| `raw_profile` | 프로필 원형(SELF_GQL·HIKER_MOBILE 등 source별 jsonb) — SELF_GQL엔 내장 타임라인 12개(피드 캡션·지표의 소스) |
| `raw_post_detail` | 구 시대 상세 payload — 신 파이프라인 미사용(LEGACY). 07-22 열람 화면 제거로 접근 코드도 삭제, 테이블만 잔존 |
| `raw_comment` | 댓글 원문 (writer/text/written_at 실컬럼) — 수집 게이트 off, 신규 유입 없음 |
| `app_setting` | 런타임 설정 key-value (분석 뷰도 여기서 임계값을 읽음) |

### 분석 뷰 (raw DB의 `analytics` 스키마)

`analytics/views/NN_*.sql` 번호순 적용 컨벤션. 2026-07-18 신 crawler 스키마(V15) 기준으로
전면 재구축 — base 층(00)이 raw 접촉을 격리하고, 서빙 모수는 뷰티 인플루언서
(QUALIFIED ∧ beauty ∧ ¬beauty_company). 04는 LLM 캡션 선분석 후보 뷰(미러 안 함).

### analysis DB

- **분석 결과** — 뷰 결과가 미러되는 테이블(Flyway로 명시 정의 — §4-3). analytics가 쓰고 was가 읽는다.
  - **파생 뷰(미러 아님)** — 입력이 전부 analysis DB 안에 있는 집계는 미러를 거치지 않고 이 DB의 뷰로
    둔다. raw를 볼 필요가 없으니 뷰가 DB 경계를 넘을 일이 없고, 미러 지연 없이 항상 최신이며 과거
    적재분에도 자동 소급된다. 현재 `account_category_stats`(계정 카테고리 믹스 — V35) 하나.
- Flyway 이력은 스키마별 분리 소유 — 분석 결과는 analytics가, `app` 스키마는 was가 관리.
- **서비스 데이터 (`app` 스키마)** — 로그인·후보 관리 등 was가 직접 읽고 쓰는 일반 앱 데이터.
  분석 결과와 스키마로 격리, 나중에 물리 분리 가능. 테이블(태스크 G + P2 확장): `users`(이메일 lower
  정규화·BCrypt 해시 + P2 프로필 15필드 V3 — name·nickname·user_type·signup_route·phone_*·company_*·
  industry·job_title·agreed_*·marketing_updated_at·profile_image_url) / `saved_influencers`(user_id+handle
  PK, status 어휘 reviewing·contact_planned·collaborating + memo) / `saved_contents`(user_id+short_code PK,
  P2에서 memo 추가 V4) / `spring_session`·`spring_session_attributes`(P2 Spring Session JDBC 세션 영속화 V2,
  `initialize-schema=never`로 Flyway가 유일 DDL 원천) / `gate_events`(P2 게이트/잠금 측정 이벤트 V5 —
  user_id nullable 익명 허용·payload jsonb·append-only) / `app_setting`(V6 — was 런타임 설정 key-value.
  `signup.code` 단일 공용 가입 코드는 V8로 폐기 — 행은 무해하게 잔존) / `email_verifications`(V7 — 이메일 소유권
  인증 코드 해시·만료·시도·verified 상태였으나 **07-29 이메일 인증 기능 제거로 미사용** — V7은 적용된
  이력이라 파일·테이블 잔존, 무해) / `signup_codes`(V8 — 클로즈베타
  배치 1회용 가입 코드, 채널별 발급·가입 트랜잭션 원자 선점, 소진 정본은 used_at·used_by는 ON DELETE SET NULL,
  빈 테이블=가입 차단 fail-closed) / `inquiries`(V10 — 도입문의, uuid PK로 순번 노출 회피). users는 V9 가입
  경량화 — 선택 필드(signup_route~job_title) NULL 허용 + `usage_purpose` 추가. handle·short_code는 분석 결과
  **논리 참조만** (FK·조인 금지 §4-4), Flyway 이력은 `app.flyway_schema_history`(was 소유, V1~V10).

## 4. 관통하는 설계 원칙

### 4-1. 최근 N개 윈도우

모든 계정 단위 지표는 계정별 최신 게시물 N개(기본 12 — 07-15 API 스펙 정렬로 **12 확정**, 07-14의 24 전환 계획은 철회)만 잘라 계산한다. 재크롤링이 누적돼도
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

**운영 중**: crawler 파이프라인(discover→qualify→beauty→collect·reels — 07-22부터 qualify·beauty·collect·reels는
새벽 윈도우 반복 크론 자동 실행, discover·similar만 어드민 수동 트리거. 어드민은 대시보드 단일
화면으로 개편: 잡 실행 스트립·예상 비용·실행 로그 통합, 잡 실행·수집 게시물 탭 제거), analytics 상주 어드민(8082 `/ui` —
미러·LLM 잡 트리거, 태스크 I + `/ui/coverage` 커버리지 매트릭스 — celfit-front **배포본(origin/main)**
실소비 필드 기준(07-18 재정의) + 수집 모수(raw 서빙 뷰) 타일, 07-19 was에서 이전), was `/v1` API(스펙
v1 P1~P3 + 로그인 월 — 커버리지는 어드민 소속, was는 고객 서비스 표면만).
구 랭킹 대시보드(`/dashboard`)와 게시물 데모(`/posts/{shortCode}`)는 프론트 전환 완료까지 잔존 —
`/dashboard`는 옛 산출물(`content_ranking`)을 읽는다(정리 §8).
수동 발굴 등록(07-22): 크롬 익스텐션(별도 저장소 `hypenow-extension`) →
`POST /crawler/api/manual-discoveries`(Caddy가 이 경로만 crawler 공개, X-Api-Token 인증) →
DISCOVERED 유입, 이후 qualify→beauty는 기존 파이프라인 동일 처리.

**상세 분석 작업 트랙** (구조 설계: [specs/2026-07-12-detail-analysis-design.md](docs/superpowers/specs/2026-07-12-detail-analysis-design.md) ·
데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md)):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| A | 분석 기반 | base 뷰·최근 N개 윈도우 뷰 재작성(raw 접촉은 base 뷰만) + 설정 키 + `contract-analysis` 골격 + 타입 미러·SQL 테스트 하니스 구축 | — | ✅ |
| F | LLM 공통 | 호출 골격 + **정확도/비용 스파이크** + 모듈 소속 확정 — F-2(VLM)는 B3에서 실험 | — | ✅ |
| B1 | 드로어 비LLM 집계 | 서빙 뷰·미러 4종 (accounts·contents·content_comments + 지표 스냅샷 이력 `content_metric_snapshots` — 07-13 개통). **07-30 스냅샷 미러만 중단** — 유일 소비처였던 D3·H 제거로 소비자 부재, 미러 12분 30초 중 6~7분을 차지. 뷰(`v_content_metric_snapshots`)는 raw에 존속(이력 조회·향후 추이 그래프 재료), analysis 테이블은 TRUNCATE 후 다음 릴리스 DROP 예정 | A | ✅ (스냅샷 미러 🗑 07-30) |
| B2 | 드로어 댓글 LLM | 감성·키워드·구매의도 → 집계 + 미러 — **댓글 수집 MVP 제외(07-14)로 신규 유입 없음** | F | ✅ |
| B3 | 드로어 콘텐츠 LLM | 감지 + 콘텐츠 속성 + "왜 잘됐나" (07-14 VLM 잔여분 개통 — 어휘는 celfit-front 계약, 유통사 감지 포함) | F, B2 | ✅ |
| C1 | 인플루언서 비LLM 집계 | AccountReport 결정 지표 — 계정 요약·게시물 시계열 2종 뷰 + 미러. 카테고리 믹스는 07-21에 analysis DB 파생 뷰(V35)로 이관 — 소스인 캡션 분류가 analysis DB라 raw 뷰로는 만들 수 없다 | A | ✅ |
| C2 | 인플루언서 계정 LLM | AccountReport 카피 5종(tagline·traits·요약 3분할 — perf/content/ad, 07-27 개편 V40) — stale+쿨다운 재분석·이력 INSERT. 구 7종 컬럼(summary·trend_note·chart_note·ad_headline·pace_note)은 이력 보존용으로 남되 신규 행은 미기록. 캡션 분류(브랜드·광고·카테고리)는 별도 후속(B4) | F, C1 | ✅ |
| D | 드로어 API | `GET /api/posts/{shortCode}` — post/account/comments + analysis 블록·댓글 aiCategory(B2·B3 산출물 포함, 1회 호출). 댓글 수집 제외(07-14)로 comments·aiCategory는 유입 없음. **07-30 제거** — 소비자 부재(운영 caddy 로그 `/api/*` 0건, celfit-front는 `API_PREFIX="/v1"` 하드코딩으로 `/api` 경로 생성 자체가 불가, `/api/**`는 로그인 월 뒤). 후속은 `/v1/contents/{id}`(+ai-report)가 담당 | B1, B2·B3(확장분) | 🗑 제거(07-30) |
| D3 | 드로어 as-of | `GET /api/posts/{shortCode}?endDate=` — 집계 기간 끝 시점 스냅샷으로 지표 재구성(captured_at ≤ endDate의 KST 하루 끝 중 최신), 스냅샷 없으면 404(그 시점 화면에 부재). 생략 시 최신. **07-30 제거**(D와 함께) — 스냅샷 미러의 유일한 소비처였고, 프론트에 시점 단건 조회 UI가 존재한 적이 없다. as-of 재도입 시 raw 뷰 직접 조회가 전제 | D, B1(스냅샷 미러) | 🗑 제거(07-30) |
| H | 랭킹 목록 API | `GET /api/contents` — 프론트 URL 파라미터 계약(start_date·end_date·main/mid/sub_category·content_type·follower·ad_type·distributor·sort·q) 그대로. 기간=게시일 필터, 지표=end_date 시점 스냅샷, 분석 완료 콘텐츠만, 기본 정렬 hype. 유통사 필터는 컬럼 신설(VLM 개통) 전까지 매칭 0. **07-30 제거**(D·D3와 함께 — 07-21 결정이 예고한 "정리 대기" 해소). 랭킹 정본은 `/v1/contents` | D3(as-of 규칙 공유), B3(카테고리·광고·유통사 어휘) | 🗑 제거(07-30) |
| E | 인플루언서 API | `GET /api/influencers/{handle}` — profile + report(AccountReport 결정 지표 + C2 카피 7종) 조합 서빙. **07-27 제거** — 소비자 부재(celfit-front는 `/v1` 프록시 전용, `/api/**`는 로그인 월 뒤라 외부 접근 불가) + 카피 7종→5종 개편(V40)으로 구 카피 컬럼이 NULL로 쌓여 점차 빈 카피를 서빙하게 되는 표면이었음. 후속은 `/v1/influencers/{id}`(+ai-report)가 담당 | C1, C2 | 🗑 제거(07-27) |
| B4 | 캡션 분류·숙성 가드 | 속성 분석을 캡션 주·썸네일 보조로 전환(5종: 광고·카테고리·브랜드·제품·유통사, `detected_products` 신설) + 어휘 DB화(V30 `beauty_taxonomy`) + 분석 대상 "게시 후 3일" 가드 | B3 | ✅ |
| B5 | 콘텐츠 뷰티 판별 + 정합 픽스 | 통합 1콜에 `isBeauty` 신설(content_analyses.is_beauty) + sanitize 어휘 밖 대분류 서브라벨 역유도 복구 + 뷰티·미분류는 행 미기록 재대상·비뷰티는 저장(루프 이탈) + was 서빙 비뷰티 제외(랭킹 is_beauty=true·recentContents IS DISTINCT FROM false) + 342건 self-heal ops. 커버리지 최신12 확장은 자매 세션 | B4 | ✅ (V34, PR 대기) |
| G | 서비스 데이터 | `app` 스키마 신설(was 소유 Flyway) + 이메일+비밀번호 로그인(Spring Security 세션 쿠키·CSRF) + 이메일 소유권 인증(6.17 — 07-19 구현, **07-29 제거**: [specs/2026-07-18-email-verification-design.md](docs/superpowers/specs/2026-07-18-email-verification-design.md) 🗄) + 저장 2종(`/api/saved/influencers` 상태·메모, `/api/saved/contents` 북마크) | 독립 | ✅ |
| I | analytics 어드민 | 상주 서버(8082) `/ui` — 07-19 파이프라인 관측 대시보드 재설계([specs/2026-07-19-analytics-dashboard-design.md](docs/superpowers/specs/2026-07-19-analytics-dashboard-design.md)) → **07-21 v3 모델 재설계**: 퍼널 폐기 → 계정 보드·콘텐츠 보드 2축(모수=현재 raw 스냅샷), 크로스 DB 잔여 대조(G1 — 후보 ∩ 미분석, 트랙별 4분할)·커버리지 현 서빙 모수 재정의(G2), 누적 분석 수는 각주 강등 — [plans/2026-07-21-analytics-dashboard-v3-data-model.md](docs/superpowers/plans/2026-07-21-analytics-dashboard-v3-data-model.md). 잡 카드·실행 피드·폴링·집계 3상태는 v2 유지 | A | ✅ |
| A2 | 뷰 신 스키마 재구축 | 분석 뷰 00~20을 신 crawler 스키마(V15 인플루언서 개편) 기준 재구축 — base 소스 교체(raw_media_page clips·SELF_GQL 내장 타임라인), 뷰티 인플루언서 모수 필터, 04 LLM 후보 뷰 신설, 하니스 신 스키마 시드 재작성. 07-18 구현 완료 | [PR #30](https://github.com/subtle-madness/hypenow-backend/pull/30) 머지 | ✅ |
| L | LLM Gemini 전환 | 전 분석 축(판정·속성+종합 통합 1콜·카피)을 `gemini-3.1-flash-lite`로 — 프로바이더 선택 `analytics.llm-provider`(기본 gemini, anthropic 롤백), 무료 키 페이싱(15RPM, 일 예산은 batch-limit) + 한도 소진 시 배치 이월, 문구 절제 규칙(LlmGuard). 크롤러 판정은 `crawler.beauty.judge`(기본 claude-api, gemini는 롤백, 팀 프롬프트·파서 재사용). 백필은 유료 키 Batch one-shot(submit/collect) — [plans/archive/2026-07-18-gemini-llm-stack.md](docs/superpowers/plans/archive/2026-07-18-gemini-llm-stack.md) | F, B4, C2 | ✅ (백필 경로는 M에서 Vertex로 대체) |
| M | Vertex 전환 + 백필 재도입 | 일상 경로를 AI Studio 무료 키 → Vertex AI(SA OAuth)로 완전 전환(`analytics.llm-provider=vertex`, `VertexTokenProvider`+`VertexHttpApi`), 배치도 GCS 경유로 Vertex 전환(상관관계는 에코 파싱으로 재설계). crawler 뷰티 판정은 무접촉(뷰티 판정 v2에서 claude-api 구독으로 별도 전환). 04 뷰·`ContentAnalysisJob` 자격에 "최근 N개 윈도우 포함" OR 추가로 07-19 백필 MVP 제외를 번복, `metric_timeliness`를 timely/late_backfill로 직접 분기. (07-23 개정: `ContentAnalysisJob`의 OR 결합 단일 쿼리를 `run()`/`runLateBackfill()` 두 진입점으로 분리 — 예산 공유 문제 해소, LIMIT 완전 제거·실질 상한은 LLM 쿼타로 대체. [specs/2026-07-23-content-analysis-timely-backfill-split-design.md](docs/superpowers/specs/2026-07-23-content-analysis-timely-backfill-split-design.md). 같은 날 후속: LIMIT 폐지로 드러난 순차 처리 병목을 `runQuery()` 동시 처리(병렬)로 해소 — `analytics.analyze-concurrency`(기본 8) 신설. [specs/2026-07-23-content-analysis-concurrency-design.md](docs/superpowers/specs/2026-07-23-content-analysis-concurrency-design.md)) 사용자 런북: [runbooks/2026-07-20-vertex-backfill-runbook.md](docs/runbooks/2026-07-20-vertex-backfill-runbook.md) | L | ✅ (구현 완료 — GCP 준비·실 스모크·본 백필은 런북 절차로 사용자 진행 대기) |
| J | 서빙 이미지 아카이브 | CDN 만료(~4일) 전 프로필·릴스 썸네일·게시글 썸네일을 OCI `hypenow-images` 버킷에 적재하는 analytics 잡 + `image_assets`(V37, 미러 제외 누적) + was COALESCE `/img/` 상대경로 서빙(Vercel rewrite 엣지 캐시 — 프론트 배포 완료) — [specs/2026-07-21-image-archive-design.md](docs/superpowers/specs/2026-07-21-image-archive-design.md) [plans/2026-07-21-image-archive.md → archive] | B1(미러), 어드민 I | ✅ (운영 개통 완료 — 버킷 공개·PAR 등록·서버 env, 첫 실행 확인 대기) |
| K | dev 스테이징 환경 | develop 검증용 dev 스택(dev-was·dev-analytics·dev-postgres)을 운영 인스턴스 동거(`compose.dev.yaml` 분리 + `profiles: ["dev"]` — dev CD가 dev 파일만 서버 동기화, 운영 서비스 정의는 main 배포 전용) + raw는 운영 postgres-raw 공유(`analytics_dev` 스키마 격리, dev 계정 raw 읽기 전용 fail-closed) + 조회 SQL 무접두어화 + raw DataSource `connection-init-sql` search_path(`analytics.raw-schema`, dev만 env 오버라이드) + develop CI 성공 자동 `cd-dev.yml`(뷰 치환 적용·잔존 참조 검증) + `dev-api.hypenow.io`(dev 라우팅은 `caddy.d/dev-api.caddy` 분리 — 운영 Caddyfile은 main 배포로만) — [specs/2026-07-26-dev-staging-environment-design.md](docs/superpowers/specs/2026-07-26-dev-staging-environment-design.md) | 기존 CD | ✅ (07-28 개통·E2E 검증 완료 — 가입~로그인~`/v1/stats`·인플루언서 상세 실데이터 응답 확인. **07-29 트랙 S로 개편**: staging 브랜치 트리거·test-* 리네임·네트워크 분리) |
| P | 뷰티 판정 v3 한국어 필터 | `BeautyClass`에 `FOREIGN_INFLUENCER` 신설(5분류, V21) — INFLUENCER를 "한국어 콘텐츠 중심"으로 재정의해 외국인 뷰티 인플루언서를 beauty=false 세그먼트로 분리(COMPANY는 언어 무관). 프롬프트 v3 경계 규칙(캡션 최우선·영어 bio+한국어 캡션→한국어). 하류(analytics·was)는 파생 boolean만 읽어 무변경. 머지 후 일회성 운영: `deploy/scripts/reset-influencer-judgments-v3.sql`(CLAUDE 판정 INFLUENCER만 초기화, MANUAL 보존)→어드민 BEAUTY 재판정 — [specs/2026-07-28-beauty-korean-filter-design.md](docs/superpowers/specs/2026-07-28-beauty-korean-filter-design.md) | — | ⚠️ 코드 ✅·**후속 운영 미실행** (07-30 발견: `reset-influencer-judgments-v3.sql`이 운영에서 끝내 실행되지 않아 서빙 `INFLUENCER`의 92.7%가 4분류 시절 판정으로 남았다 — 트랙 CC에서 교정. 교훈: "머지 후 운영 작업"이 남은 트랙을 ✅로 닫으면 누락이 보이지 않는다) |
| N | 프로필 400 → Hiker 폴백 | crawler `ProfileSource.SELF_HIKER_FALLBACK` 신설 — SELF(`web_profile_info`)로 배치 조회하되 **IP 무관 HTTP 400**(비즈니스 카테고리 버그, 07-23 ~29% 확산) 계정만 HikerAPI `/v2/user/by/username`로 2차 조회하는 컴포지트 페처 `SelfWithHikerFallbackProfileFetcher`(라벨 `profile-self-hiker`, crawl_run 1건). 혼합 배치는 `ProfileExtractor.detect` 셰이프 감지로 아이템별 소스를 `raw_profile.source`에 기록(소비처 3곳 — CollectJob·QualifyJob·ProfileSupplementer) + 어드민 소스 라디오 노출 — [specs/2026-07-26-profile-400-hiker-fallback-design.md](docs/superpowers/specs/2026-07-26-profile-400-hiker-fallback-design.md) [plans/2026-07-26-profile-400-hiker-fallback.md → archive] | — | ✅ (옵션 추가까지 — 실제 전환은 `profile.source` 수동 UPDATE·어드민 UI로 사용자 결정) |
| O | timely 캘린더일 정합 | `ContentAnalysisJob` 후보 선정을 raw 후보 뷰(04, 캘린더일 timely) 직접 소비로 교체 — 간격식 판정 이원화 제거(일 수백 건 late_backfill 누수→랭킹 영구 제외 해소). 기존 마킹 양방향 소급 런북 포함 — [specs/2026-07-28-timely-calendar-alignment-design.md](docs/superpowers/specs/2026-07-28-timely-calendar-alignment-design.md) [plans/2026-07-28-timely-calendar-alignment.md](docs/superpowers/plans/2026-07-28-timely-calendar-alignment.md) | B1(미러), 04 뷰 | 🔨 (PR 리뷰 대기 · 소급 런북은 배포 후 실행) |
| Q | 인플루언서 리포트 개편(백엔드) | 피어 퍼센타일·중앙값 ER 파생 뷰(V39 `account_peer_stats` — 주 카테고리×팔로워 버킷) + `account_analyses` 요약 3분할(V40, perf/content/ad_summary) + 계정 카피 7종→5종(tagline 상세화, ad_headline·성장세·유효 팔로워·유사도는 LLM 제거하고 was 알고리즘 산출로 전환) + was 리포트 DTO v2(전체/광고 2행 스탯·성장세·상위%, 유효 팔로워, 미리보기 bars에 캡션·썸네일·브랜드) + 신규 `GET /v1/brands/{brand}/influencers`·`GET /v1/influencers/{id}/similar`(traits Jaccard). **07-28 프론트 확정 스펙 정렬(6.22 리포트 v2·6.23 유사 카드·6.24 이메일 중복 확인) 포함**: v1(6.5)은 프론트 라이브 소비 중이라 원형 보존하고 v2를 `/v2` 병행 신설, 브랜드 hover 엔드포인트는 6.22 `ads.brands` 인라인으로 흡수·삭제, 유효 팔로워는 발굴 목록(6.21)과 산식 단일화(`EffectiveFollowers` 유틸) — [plans/2026-07-27-influencer-report-redesign-backend.md](docs/superpowers/plans/archive/2026-07-27-influencer-report-redesign-backend.md) [plans/2026-07-28-influencer-report-v2-spec-alignment.md](docs/superpowers/plans/archive/2026-07-28-influencer-report-v2-spec-alignment.md) | C1, C2, B4 | 🔨 (PR 리뷰 대기) |
| R | 유사 인플루언서 유사도 v2 1단계 | Q가 얹은 유사 인플루언서 정렬(traits Jaccard 단독)이 운영 실측 난수화(traits 고유값 5,847/28,387, 67% 1회 등장)돼 혼합 점수(Jaccard 0.6 + 카테고리 믹스 히스토그램 교집합 0.4, 집합 기반 SQL — 운영 규모 실측 85ms)·컷 0.30(운영 dry-run 실측)·동점 시 팔로워 근접→handle 타이브레이크로 교체. 착수 중 Q 최종본이 v1 similar 표면을 삭제하고 `/v2/influencers/{id}/similar`(6.21 카드 재사용)로 신설해, 알고리즘을 `V2InfluencerReportRepository.findSimilarHandles`로 이식 — 겸사겸사 스펙 6.23의 서버 고정 9명을 **10명으로 변경**(사용자 확정). 어휘 통제(2단계)는 트랙 T로 착수 — [specs/2026-07-28-similar-influencer-similarity-v2-design.md](docs/superpowers/specs/2026-07-28-similar-influencer-similarity-v2-design.md) [plans/archive/2026-07-28-similar-influencer-similarity-v2-phase1.md](docs/superpowers/plans/archive/2026-07-28-similar-influencer-similarity-v2-phase1.md) | Q | ✅ (PR 리뷰 대기) |
| S | 모니터링 was seam | was ↔ monitoring 통신 계층 — 명령 클라이언트·읽기 전용 조회·app 매핑(V13)·조건부 활성화(monitoring.enabled). 프론트 /v1 컨트롤러·이메일 크론은 후속 — [specs/2026-07-28-monitoring-was-seam-design.md](docs/superpowers/specs/2026-07-28-monitoring-was-seam-design.md) | — | ✅ (구현 완료, PR 대기) |
| T | trait 어휘 통제(유사도 v2 2단계) | 07-29 리포트 개편 카피 백필 완결로 traits 전량이 새 프롬프트 산출로 교체됐는데도 난수화 지속(고유 4,242/25,818, 싱글톤 62.6%)이 실측돼 보류 해제. 운영 빈도 데이터 주도로 **고정 어휘 172개·13축**(V41 `trait_taxonomy` 시드, 사용자 확정) + 합성 프롬프트 어휘 주입(`instructions(vocab)` — Anthropic static 캡처 해소) + 저장 sanitize(어휘 밖 드롭·중복 제거, 전부 드롭 시 빈 배열) + 기존 데이터 이행은 어드민 원샷 잡 `TRAIT_CANON_DRY/APPLY`(LLM 배치 매핑 → `trait_canon_log` 감사 → traits in-place UPDATE). **1:N 분해 매핑 채택**(복합 trait→원자 태그 최대 2, "감성 브이로그"→[브이로그, 감성 무드]) — [specs/2026-07-29-trait-vocabulary-control-design.md](docs/superpowers/specs/2026-07-29-trait-vocabulary-control-design.md) [plans/2026-07-29-trait-vocabulary-control.md](docs/superpowers/plans/2026-07-29-trait-vocabulary-control.md) | R | 🔨 (구현 완료 — PR·배포·매핑 잡 실행 대기) |
| U | was Redis 캐싱 | 조회 4경로 캐시(목록 1h·리포트 6h)+다음 페이지 프리페치, 세션 JDBC 유지 — [specs/2026-07-28-redis-caching-design.md](docs/superpowers/specs/2026-07-28-redis-caching-design.md) [plans/2026-07-28-redis-caching.md](docs/superpowers/plans/2026-07-28-redis-caching.md) | H, P4, Q | ✅ (PR 리뷰 대기) |
| V | 발굴 목록 계정 하입 스코어 | `v_account_summaries.avg_hype_score` 신설(B안 — 미러 3곳 동시 변경) — 계정별 최근 12창 콘텐츠 `hype_score()`(신선도 감쇠 포함, 콘텐츠 함수 재사용) 단순 평균. 계약 record 확장 + analysis Flyway V42(`account_summaries.avg_hype_score` 컬럼 — V41은 trait_taxonomy가 선점, V18 경합 전례로 재번호) + was 발굴 목록(`GET /v1/influencers`, 6.21) `sort=hype` 정렬 옵션과 카드 `hypeScore` 노출 배선. 유사 카드(6.23, R)는 발굴 카드와 SELECT·DTO를 공유해 자동 포함(의도된 부수효과) — [specs/2026-07-29-influencer-avg-hype-score-design.md](docs/superpowers/specs/2026-07-29-influencer-avg-hype-score-design.md) [plans/2026-07-29-influencer-avg-hype-score.md](docs/superpowers/plans/archive/2026-07-29-influencer-avg-hype-score.md) | C1, P4 | ✅ (구현 완료 — 배포 대기) |
| W | staging 브랜치·test 스택 전환 | 승격 흐름을 develop→**staging**→main으로 개편(develop 머지는 CI만, **develop→staging 머지 = test 배포**, staging→main = 운영 배포 — 검증된 커밋만 운영 승격 보장) + dev 계열 명칭 test 통일(`test-was`·`test-analytics`·`test-postgres`·`test-redis`, `compose.test.yaml`, `--profile test`, `cd-test.yml`, `caddy.d/test-api.caddy`, 이미지 `:staging`·`staging-sha-*`) + **도커 브리지 prod/test 분리**(compose.yaml 선언, test→운영 경로 커널 차단 — 공유 접점은 caddy·postgres-raw 양쪽 소속 둘뿐). 유지(의도적 예외): 도메인 dev-api.hypenow.io·`DEV_*` env·`analytics_dev` 계정/스키마·볼륨 `dev-pg-data`. monitoring 배선 시 이름 매핑: dev-monitoring→test-monitoring, `:develop`→`:staging` (07-29 monitoring 배선에 적용 완료) — [specs/2026-07-29-staging-branch-test-stack-design.md](docs/superpowers/specs/2026-07-29-staging-branch-test-stack-design.md) | K | ✅ (07-29 전환 완료 — 운영 CD·staging 개통·cd-test 첫 배포 success, 격리 검증: test→운영 DNS 해석부터 차단·구 dev-* 컨테이너 제거 확인) |
| X | was 무중단 롤링 배포 | 운영 CD의 was 재기동을 롤링으로 전환(신 컨테이너 healthy·스모크(`/v1/stats`) 확인 후 구 제거 — `deploy/scripts/rollout.sh`, 잔재·이미지 검증 포함, 실패 시 구가 계속 서빙하는 무중단 실패) + was healthcheck·`stop_grace_period`·`server.shutdown: graceful` + Caddy `lb_try_duration` 재시도 + CD에 caddy reload 스텝(운영 Caddyfile 변경 미반영 갭 해소, 롤링보다 선행) + **expand-contract CI 가드**(`migration-guard` 잡 — analysis DB 마이그레이션의 파괴적 DDL PR 차단, `-- allow-destructive:` 해치. crawler(raw)는 대상 외. **v2**: DROP COLUMN ↔ 보정 UPDATE 짝 검사 — 컬럼 이행의 롤링 창 유실분 최종 백필을 contract 시점에 기계 강제, 예외는 `-- no-backfill:` 태그). analytics·crawler·monitoring·test 스택은 재기동 유지 — [deploy/README.md §5-1](deploy/README.md) | W | ✅ (07-30 첫 실전 롤링 성공 — 운영 무중단 실측 547/548, 유일 순단 1초는 caddy 재생성. 첫 시도의 이미지 검증 오탐은 PR #186으로 수정) |
| Y | 성과 요약 통계 왜곡 가드 | `perf_summary`(AI 성과 요약)가 단순 평균만 근거로 삼아 "릴스 2건 중 1건이 터진" 계정을 꾸준한 계정과 같게 서술하는 문제. 운영 실측(계정 7,033)으로 **표본 문제가 계정 단위가 아니라 지표 단위**임을 확정(`analyzed_count`=12인데 조회수 관측 1건인 계정 실존, 혼합 계정 66.8%·릴스 비중 p10=0.10)하고, **한 건 지배는 예외가 아니라 기본값**(top1 점유율 p50 0.466)임을 근거로 판정 기준을 평균→중앙값 전환. `v_account_summaries` 컬럼 9개(지표별 실질 모수 5·`median_views`·`median_er_pct`·`top_views_share_pct`·`window_span_days`, V44 미러) + Java 결정론 판정 `PerfConfidence`(임계값을 뷰에 굳히지 않음 — 운영 뷰 적용이 수동 런북이라 조정마다 DDL이 붙는 것을 피함) + 프롬프트 신뢰도 지침 주입(내부 수치는 입력 맵에서 제거) + 재생성 게이트 `account_analyses.copy_version`+`CopyRules.VERSION`(계정 카피엔 버전 게이트가 없어 휴면 계정 문구가 영구 고정되던 것 해소). 실측이 폐기시킨 가설 2건(0뷰 필터 상향 편향·likes -1 혼입)은 스펙에 재도입 금지로 명시. 배포는 **뷰 선적용→미러→분석 잡** 순서 필수(어기면 저품질 카피가 최신 버전으로 영구 고정 — `PerfConfidence.dataIncomplete()`가 9컬럼 전부 NULL을 감지해 생성 스킵으로 차단). test 검증 중 `LlmGuard`(콘텐츠·계정 카피 공유 절제 규칙)의 "핵심 주장에는 근거 수치를 함께 인용하라"가 perfSummary의 수치 인용 금지와 상충해 `0_tsuki2`에서 "평균 좋아요 수는 1,605개"가 나온 것을 발견 — `LlmGuard`를 `RULES`(콘텐츠 전용, 인용 지시 유지)/`ACCOUNT_RULES`(계정 카피 전용, 인용 지시 제외)로 스코프 분리하고 `AccountAnalysisWriter.hasNumericCitation()` 관측 로그(차단은 안 함) 추가. **3차 test 실측(같은 날)에서 `LONG_SPAN`(창 90~365일, `trend_*` 값을 남긴 채 "완만하게 표현하라" 지시로만 통제)이 새어나오는 것을 확인**(`0205s.y`·`02_10.13`·`119irl` — 완화 없는 상승세 단정) — 값을 아예 제거한 `TOO_LONG`은 누출 0건이었던 것과 대비돼, `LONG_SPAN`을 폐지하고 90일 초과 전 구간을 `TOO_LONG`과 동일하게 `trend_*` 4컬럼 제거로 통합. `PerfConfidence.TrendValidity`는 `OK`/`UNAVAILABLE` 2상태로 단순화(정보 손실 34.6%는 의도된 수용). `WEAK` 헤지 누락·dominance 프레이밍 미반영은 값 제거를 적용할 수 없는 항목이라 알려진 한계로 남김(§3-2-1) — [specs/2026-07-30-perf-summary-statistical-guards-design.md](docs/superpowers/specs/2026-07-30-perf-summary-statistical-guards-design.md) §3-2·§8 | C2, Q | ✅ 운영 배포 완료(main `5aa340cc`, CD 성공·뷰 적용·미러 완료). 운영 실측(계정 7,033): 창 90일 초과 2,426건(34.5%)·조회수 표본 ≤2 1,965건(27.9%)·top1 점유율 ≥75% 1,299건(18.5%)·`median_views` NULL 1,019건(14.5%). 재생성 대상 7,033건(`copy_version` 게이트 단독)은 즉시 트리거하지 않고 새벽 정기 배치 대기 — `AccountAnalysisJob` 스케줄 실측 완료(cron `0 0 22 * * *`=UTC 22:00=KST 07:00, 운영 컨테이너 env 확인, batch-limit 운영값 30000으로 상한 문제 없음) → 오늘 밤(07-31 새벽 KST 07:00) 전량 자동 처리 예정 |
| Z | 하입 스코어 v3 (감쇠를 매핑 뒤로) + 계정 점수 척도 재교정 | 발굴 목록 피드 편향(피드 ≥70점 7.61% vs 릴스 3.98%, 상위 50 계정 피드비율 중앙값 0.70 vs 전체 0.18)의 원인이 `analytics.hype_score()` 앵커를 감쇠 후 `qf`에 적합시켜 캘리브레이션이 코퍼스 연령 구성에 오염된 것으로 규명. 감쇠를 앵커 매핑 **뒤로** 옮겨 `점수 = clamp(map_Q(Q), 0, 100) × 0.5^(경과일/halflife)`로 재정의(클램프는 감쇠 전), 앵커 8개는 **감쇠 전 Q 기준·전체 서빙 코퍼스**로 재적합(운영 실측이 스펙값과 n까지 정확히 일치 확인 — 07-30). app_setting 앵커 키를 `hype-anchor-q-*`로 개명(구 키는 무시 — 조용한 오염 방지). Java에는 계산 로직이 없어(계약 record는 미러 값 통과) 수정은 SQL 함수 + 계약 Javadoc뿐. **후속(같은 날, §9)**: test 반영 실측으로 계정 점수(`avg_hype_score`, 최근창 평균) 척도가 무너진 것을 발견(최대 59점·0점 계정 20.72% — 창 스팬에 걸친 개별 콘텐츠 감쇠를 평균 내다 보니 오래된 창 뒤쪽이 평균을 누름). 게시 빈도가 계정 점수를 좌우하는 것은 의도된 동작(사용자 결정) — 순위는 유지하고 척도만 콘텐츠와 동일한 4점 구간선형 매핑 함수 `analytics.hype_account_score()` 신설로 재교정(앵커는 계정 raw 평균 0점 제외 분위수로 별도 적합, `hype-anchor-acct-*`). 콘텐츠 산식·반감기는 불변. **재후속(같은 날, §9-6)**: 재교정 배포 후 발굴 목록 상위권 순서가 뒤섞임 발견 — 매핑 최상단 구간이 raw 44.86~58.92(상위 1% 전체)를 정수 97~100 4개 값으로 압축해(≥97점 54개 계정에 서로 다른 점수값 4개뿐) `ORDER BY avg_hype_score DESC, handle`의 동점 처리가 상위권을 사실상 handle 알파벳순으로 지배. 표시값과 정렬 키를 분리해 해결: `v_account_summaries`에 정렬 전용 `avg_hype_raw`(반올림 전 평균) 컬럼 신설(Flyway V49·contract `AccountSummary.avgHypeRaw`, `CREATE OR REPLACE VIEW` 중간 삽입 불가 제약으로 셋 다 맨 끝에 추가), was `V1InfluencerDiscoveryRepository`의 `sort=hype`를 `avg_hype_score`→`avg_hype_raw`로 교체. 표시는 계속 avg_hype_score, API 응답엔 avg_hype_raw 비노출. 콘텐츠 점수는 서빙 코퍼스가 110,488건이라 상위 1%가 1,100건 이상으로 퍼져 같은 문제가 없음 — [specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md §9](docs/superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md) [plans/2026-07-30-hype-score-v3-decay-after-mapping.md](docs/superpowers/plans/2026-07-30-hype-score-v3-decay-after-mapping.md) | — | 🔨 (구현 완료 — PR·운영 뷰 적용·미러·프론트 통지 대기) |
| AA | 발굴 표면 뷰티 비율 게이트 | 계정 단위 뷰티 판정(crawler `influencer.beauty_class`)의 오판(0% 구간 스팟체크 20개 중 17개)을 계정 판정 로직 변경 없이 발굴 표면에서 게시물 실측 비율로 필터. `account_beauty_ratio` 뷰(analysis Flyway V45 — 창 내 분석 완료·뷰티 판정 원시 카운트만, 정책 없음) + was 게이트(분석 8건 미만은 보류·통과, 뷰티 비율 20% 미만이면 제외, 기존 카테고리 게이트와 동일 임계값 재사용). 적용은 발굴 목록 `GET /v1/influencers`(`V1InfluencerDiscoveryRepository`)·유사 인플루언서 후보 단계(`V2InfluencerReportRepository.findSimilarHandles`)뿐 — 랭킹 `/v1/contents`·상세 직접 조회·저장 목록은 `account_summaries`를 조인하지 않아 무영향(의도적 미적용). 0으로 나누기 방어(`NULLIF` 관용구, 카테고리 게이트와 동일 패턴)를 후속 커밋으로 동봉 — [V45__account_beauty_ratio_view.sql](analytics/src/main/resources/db/migration/analysis/V45__account_beauty_ratio_view.sql) | P4, R | 🔨 (구현 완료 — PR 대기) |
| AB | 계정 뷰티 판정 품질 — 실측 캡션 기반 사후 재판정 | 서빙 중 뷰티 인플루언서 7,095개 중 게시물 뷰티 비율 0%인 886개, 스팟체크 20개 중 17개(85%)가 오판(육아·다이어트·여행·피트니스 계정이 뷰티로 분류)으로 실측 확인. 원인은 프로필 소스가 `HIKER_MOBILE`/`DATALIKERS`면 응답에 게시물이 없어 판정 캡션이 항상 0건이고, 게시물 수집(`findCollectTargets`)이 `beauty=true`만 대상이라 판정 시점에 게시물 근거가 원리적으로 존재할 수 없으며(닭-달걀), 기존 재판정(`findRejudgeTargets`)이 `beauty=false`만 대상이라 뷰티로 잘못 통과한 계정이 영구 고착되던 것. 이미 crawler DB(`raw_media_page.payload`, REELS 잡이 `HIKER_V2_CLIPS`로 적재)에 있는 게시물 캡션 원문을 폴백으로 써(추가 크롤 0) 판정 캡션 소스를 넓히고, `influencer.beauty_caption_count`·`beauty_basis`(CAPTION/BIO/CATEGORY_ONLY, V22) 컬럼으로 판정 근거를 가시화, 캡션 0건으로 판정된 뒤 릴스가 쌓인 계정을 `beauty` 값과 무관하게 재판정하는 두 번째 경로 `findCaptionRejudgeTargets`(릴스 아이템 3건 이상)를 신설. 프롬프트는 인스타그램 자기신고 `category`를 금지 근거가 아니라 우선순위 낮은 근거로 재정의하고 `reason`→`class` 출력 순서 전환, `basis` 자기보고 도입. Gemini `RESPONSE_SCHEMA`가 V21의 5분류(`FOREIGN_INFLUENCER`)를 반영 못 하던 버그도 동봉 픽스. 운영 실측(07-30): V22 백필 대상 19,093건, 현재 조건에 걸리는 재판정 대상 730건, 미판정 QUALIFIED 계정 0건, `beauty.batch-limit` 2000 — [specs/2026-07-30-beauty-judgment-quality-design.md](docs/superpowers/specs/2026-07-30-beauty-judgment-quality-design.md) [plans/2026-07-30-beauty-judgment-quality.md](docs/superpowers/plans/2026-07-30-beauty-judgment-quality.md) | P | ✅ (구현 완료 — PR 미생성·운영 미반영. 야간 `beauty` 크론에 rejudge=true 배선 완료 — 자동 자기교정이 스케줄로 돈다) |
| BB | 인플루언서 이메일(bio 정규식 파싱) | biography에서 정규식 `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`로 이메일 파싱해 발굴 목록에 노출. 운영 실측(계정 7,033 중 biography 보유 6,808·정규식 매치 2,553=37.5%, 샘플 30건 오탐 0건)으로 LLM 없이 정규식만 채택. `v_account_summaries.email` 신설(POSIX substring leftmost match로 "첫 매치만" 자연 성립, `lower()` 정규화) + analysis Flyway V46(`account_summaries.email` ADD COLUMN, record 끝 위치) + was 발굴 목록(`GET /v1/influencers`) 카드 `email` 배선(구 "크롤러 미수집(V31)이라 null" 스텁 제거) + `contactOpen` 필터를 죽은 `AND false`에서 `AND su.email IS NOT NULL`로 교체. 뷰티 필터는 기존 `v_recent_content`(QUALIFIED ∧ beauty ∧ ¬beauty_company)가 이미 적용 중이라 무변경 — [specs/2026-07-30-influencer-email-from-bio-design.md](docs/superpowers/specs/2026-07-30-influencer-email-from-bio-design.md) | C1, P4 | 🔨 (구현 완료 — PR 대기) |
| CC | 뷰티 FOREIGN_INFLUENCER 재판정 확대(프롬프트 v4) | 운영 실측: `INFLUENCER` 7,128건 중 92.7%(6,605건)가 v3(트랙 P, `FOREIGN_INFLUENCER` 도입) 컷오버(07-28 05:00 UTC) **이전** 판정 — `reset-influencer-judgments-v3.sql`이 운영 미실행된 채 남아 4분류 시절 산출물이 그대로 서빙 중이었고, `findRejudgeTargets`가 `beauty=false`만 대상이라 INFLUENCER는 자가 치유 불가. 게다가 v3 프롬프트 자체도 결함 — "한국어 콘텐츠 중심"을 LLM이 "한국 관련 콘텐츠"로 오독해 한국 화장품을 외국어로 리뷰하는 계정을 INFLUENCER로 오분류(post-v3 표본 33/33 일본 계정). 판정 기준을 **서술 언어 대 다루는 제품·주제의 국적** 축으로 명문화하는 프롬프트 v4로 교정 + `reset-influencer-judgments-v4.sql`(배치 한도 슬라이스 반복, 오래된 판정부터 소진) — [plans/2026-07-30-beauty-foreign-influencer-rejudge.md](docs/superpowers/plans/2026-07-30-beauty-foreign-influencer-rejudge.md) | P | 🔨 (구현 완료 — PR·재판정 슬라이스 실행 대기) |
| DD | 모니터링 알람 모듈 | 알람 소유를 monitoring으로 이동 — `alarm_event` 대장(워터마크 없음)·적재 5지점·발송 크론(디바운스·옵트아웃·유저당 1통) + 승인 플로우 제거(첫 감지 자동 추적) + 일시 오류 당일 재시도 + `target.user_id`(V3)·app 옵트아웃(was V15) + 계약 **v2.0**. PR②(was 클라이언트 정렬)·프론트 알림 API는 EE에서 흡수 — [specs/2026-07-30-monitoring-alarm-module-design.md](docs/superpowers/specs/2026-07-30-monitoring-alarm-module-design.md) | S | 🔨 (EE 브랜치에 병합돼 함께 PR) |
| EE | was v3 표면 트랙 | 모니터링 v3 프론트 계약(6.25~6.33) 소비 표면 — was `/v1` API 9종(캠페인 CRUD·모니터링 항목 목록(6.26 완전 어셈블러)/등록/PATCH/취소·알림 설정·알림 목록/읽음) + 크론 2종(다이제스트 생성·따라잡기) + 탈퇴 시 모니터링 해지 루프(배치 대상 즉시 제외). 승인 큐를 monitoring 자동 전환으로 흡수(FE에는 승인 없는 자동 수집으로 보임) · 프론트 상태 6종은 영속화하지 않고 monitoring 원시 상태에서 **조회 시 유도** · 다이제스트는 `alarm_event`를 유저·날짜 단위로 **멱등 재계산**(워터마크 폐지, DD의 id 대장을 단일 원천으로 소비) · 이메일 발송은 monitoring 소유로 확정해 was 발송 경로 완전 제거 · app 스키마 V13 매핑을 **V16**으로 재구성(추적·알림 실사용 테이블만 남기는 재구축) · 어휘 경계 매핑(소문자 프론트 상태 ↔ 대문자 monitoring 상태, `MonitoringEventTypes`)은 was 경계에서만 변환. 팀원 P1 표면 확장(post_meta·hidden/error 신호·sweep_run·matched_keywords, `feat/monitoring-p1` 병합)으로 개통 차단 갭 해소 — [plans/2026-07-30-monitoring-v3-was.md](docs/superpowers/plans/archive/2026-07-30-monitoring-v3-was.md), 갭 파악·해소 결과 [plans/2026-07-30-monitoring-v3-merge-gaps.md](docs/superpowers/plans/archive/2026-07-30-monitoring-v3-merge-gaps.md) | S, DD, MON-P2 | 🔨 (PR 대기) |
**API 스펙 정렬 트랙** (2026-07-15 프론트 계약 채택 — [specs/2026-07-15-hypenow-api-spec-alignment-design.md](docs/superpowers/specs/2026-07-15-hypenow-api-spec-alignment-design.md)):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| P1 | V1 읽기 API | envelope·에러 공통 + `/v1/contents`·`/v1/contents/{id}/ai-report`·`/v1/influencers/{id}`(+ai-report). 병행 데이터 보강: hypeScore 재정의(0~100)·유통사 슬러그·updatedAt·email/externalLink 조사 (07-15 개통) | H, D, E, B4 | ✅ |
| P2 | 서비스 데이터 정렬 | G 확장 — Spring Session JDBC(`app.spring_session`, 세션 목록·개별 로그아웃·hypenow-session 30일 슬라이딩), `/v1/auth`(가입·로그인·로그아웃·레이트리밋), users 프로필 15필드(V3), 저장 2종 스펙 계약화(memo upsert V4·카드 조합 목록), `/v1/me` 계정(프로필 PATCH·비번·세션·프로필 이미지·탈퇴), 게이트 이벤트(V5), P1 응답 개인화 필드(isContentsSaved/isInfluencerSaved) (07-15 개통) | G, P1 | ✅ |
| P3 | 부가 | `/v1/stats` — 랜딩 통계(스펙 6.20)를 분석 층 1행 뷰·미러(landing_stats V32)로 서빙, 모수는 자격 팔로워 구간 계정(500~5만 — 07-29 하한 3천→500 확장, V47)·강한 HTTP 캐시(1시간). 유사 콘텐츠(6.2)는 **범위 제외 확정**(제품 고려 대상 아님 — 미구현) (07-17 개통) | P1 | ✅ |
| P4 | 발굴 목록 | `GET /v1/influencers`(스펙 6.21) — 명함 카드 그리드, 서버 필터(q AND 부분일치·카테고리 3단·팔로워 5구간·활동성·협찬 구간·contact)·정렬 3종·오프셋 페이지네이션. 전부 was 조회 레이어(신규 뷰·마이그레이션 없음), 유효 팔로워는 리포트 개편 산식 인라인 CTE, 로그인 월 예외(Public). email은 크롤러 미수집이라 항상 null — [plans/2026-07-28-influencer-discovery-list.md](docs/superpowers/plans/archive/2026-07-28-influencer-discovery-list.md) | P1 | ✅ (07-28) |

기존 `/api/*`는 프론트 전환 완료까지 병존, fit(스펙 6.18)은 보류.

**클로즈베타 전환 트랙** (2026-07-19 프론트 요청서 — 초대코드 가입 전용 + 도입문의 접수):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| CB1 | 배치 1회용 가입 코드 | V8 `signup_codes`(채널별 발급) + 가입 트랜잭션 원자 선점(SignupService) + `POST /v1/auth/signup-code/verify`(관문 사전 검증) + 단일 공용 코드(app_setting) 즉시 폐기 + 코드 생성 스크립트(deploy/scripts) | — | ✅ ([PR #53](https://github.com/subtle-madness/hypenow-backend/pull/53) 머지 완료 07-19) |
| CB2 | 가입 필드 경량화 | V9 — 선택 필드 6종 NULL 허용·기본값 제거 + `usage_purpose`(대행사) + validator optionalIn. **머지·배포 후 프론트 호환 모드 기본값 제거 통지 필수** | CB1(스택) | ✅ ([PR #54](https://github.com/subtle-madness/hypenow-backend/pull/54) 머지 완료 07-19) |
| CB3 | 도입문의 API | V10 `inquiries`(uuid PK) + `POST /v1/inquiries`(Public, IP 분당 2회) — 운영자 확인은 DB 조회, Resend 알림은 후속 옵션 | CB2(스택 — V 번호 순서) | ✅ ([PR #56](https://github.com/subtle-madness/hypenow-backend/pull/56) 머지 완료 07-19) |

두 트랙 전 태스크 완료(07-17). 남은 작업은 §8 미결과 프론트 REST 전환 연동(celfit-front은 아직
Drizzle/메모리 모드 — seam만 준비됨).

**모니터링 트랙** (2026-07-28 설계 확정 — [specs/2026-07-28-monitoring-module-design.md](docs/superpowers/specs/2026-07-28-monitoring-module-design.md)):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| MON | monitoring 모듈 | 신규 4번째 모듈 — 시딩 캠페인 모니터링(계정 키워드 감시→후보 감지→FE 승인→게시물 추적 상태 기계, target=캠페인 단위·스냅샷=관측 대상 단위, Hiker-only 수집, 사설 monitoring DB 2스키마 raw/public — was는 public 읽기 전용, 명령은 내부 API + `/v1/monitoring`) | — | ✅ (구현 완료 07-29 — 개통 ops([deploy/README §13](deploy/README.md))·머지 대기 · [plans/archive/2026-07-28-monitoring-module.md](docs/superpowers/plans/archive/2026-07-28-monitoring-module.md) · Hiker 매핑 [plans/2026-07-28-monitoring-hiker-findings.md](docs/superpowers/plans/2026-07-28-monitoring-hiker-findings.md)) |
| MON-P2 | monitoring v3 P2 표면 | 프론트 v3 확장 요구의 P2 4종 — 댓글 수집(`post_comment` — 추적 게시물당 일 1콜 15건 교체 갱신, 작성자 본인 답글은 동봉 미리보기로 판정해 추가 콜 0)·계정 표시 메타(`profile_meta`)·감지 매칭 키워드(`detected_candidate.matched_keywords`)·공유 단축 링크 해소(`POST /api/share/resolve` — 등록 API와 분리된 전처리, 병행 알람 트랙과의 파일 충돌 회피). 계약 [v1.1](docs/contracts/monitoring-was-contract.md)·Hiker 실측 [findings §10](docs/superpowers/plans/2026-07-28-monitoring-hiker-findings.md)(media pk는 shortcode base64url 산술 유도 — 저장 불필요). P1 4종·승인 제거·이벤트 대장은 알람 트랙 몫 | MON | 🔵 (07-30 구현 완료 — PR #195 리뷰 대기. Flyway V4 — 알람 트랙 V3 선행 머지 전제, 어긋나면 머지 직전 재번호) |

## 6. 데이터 제약 (해석 주의 — 모든 지표 설계의 전제)

- **피드 게시물은 조회수가 항상 NULL** (인스타가 공개 안 함). 평균·히트·확산배율 계산 시 NULL 규칙 필수.
- 조회수 = 인스타 공개 재생수(`videoPlayCount`, 폴백 `videoViewCount`). 비로그인 취득 가능 실측 확인(07-10).
- 게시물 지표는 **중복 크롤링으로 스냅샷이 누적**되지만, 서빙 지표(`contents`)는 **업로드 +3일 이후
  가장 이른 스냅샷으로 고정**(07-14 정정 ③ — 키 `analytics.metric-pin-days` 기본 3, B3 숙성 가드와 같은
  3일 기준). 고정 후보가 없으면 최신 폴백 — **07-30 정정**: 이 폴백을 "구크롤러 잔재 5건, 소멸 예정"으로
  적어 뒀으나 운영 실측은 반대다. 개편 크롤러도 3일 미경과 게시물을 수집하며(열거와 지표 캡처가 같은
  단일 페치), 미성숙 폴백으로 고정된 행이 상시 존재한다(실측 lag 3일 미만 8,729건, `metric_captured_at`이
  24시간 내인 행 9,303건). 즉 `MAX(contents.metric_captured_at)`은 이 "젊은 꼬리"가 지배해 최신 크롤
  시각을 실시간으로 반영한다 — `/ui` 신선도 배지가 이 값에 기대는 근거(§7 07-30).
  메타(썸네일·캡션)는 최신 스냅샷. 시점별 조회는 스냅샷 이력 (`v_content_metric_snapshots`)으로 —
  D3·H가 쓰던 end_date as-of 경로이나 **07-30 그 두 표면 제거로 소비자 없음**(analysis 미러도 중단,
  뷰는 raw에 존속). 추이 그래프 UI는 확정안에서 제외된 상태 유지(데이터만 보존 — raw 스냅샷은 계속 누적).
- **댓글 수집은 MVP 제외**(07-14) — B2 분류·`content_comments` 경로는 신규 유입 없음(구현 보존).
  재개 시 게시물당 최대 50개 제약이 다시 적용된다("214개 분석" 카피 불가 이슈 포함).
- 저장·공유·도달·노출 지표 없음. 팔로워는 qualify 시점 값.
- LLM 댓글 분류 실측 비용: 게시물 1,000건당 Opus ≈ $61 / haiku ≈ $12.2 (동기·무캐시·무배치 기준).
  VLM(썸네일)은 건당 ≈ $0.03~0.05 (opus 4.8, 07-14 실측).
- LLM 운영 비용(07-18 확정): 전 축 gemini-3.1-flash-lite **무료 티어 $0**(분당 15콜·일 1,500콜 예산 —
  판정 ~100 / 통합 ~450 / 카피 ~150콜), 초기 백필 2만 건은 유료 프로젝트 Batch API ~$9.
  Anthropic 단가는 통합·카피 축 롤백 참고치 — 판정 축은 이제 기본이 Anthropic(구독, `crawler.beauty.judge` 기본 claude-api). 일 1,500콜 초과 성장 시 GEMINI_API_KEY만 유료 키로 교체(코드 무변경).
- **인스타 CDN 썸네일 URL은 수집 후 ~4일이면 만료**(403) — 썸네일 첨부는 최신 수집분에만 가능
  (분석 잡이 HEAD 프리체크, 만료분은 캡션 단독 분석 — B4). 썸네일 신호까지 반영하려면 크롤링 직후 분석 배치를 돌릴 것.

## 7. 결정 기록

> 정본은 [DECISIONS.md](DECISIONS.md) — 결정 이력 전체 154건(최신순, 2026-07-30 → 2026-07-07)이 있다.
> 새 결정도 여기가 아니라 그 파일 맨 위에 추가한다.

## 8. 미결 (팀 논의 대기)

| 항목 | 상태 |
|---|---|
| 계약 테스트 CI 연결 | raw 변경 PR에서 `analytics/test/run.sh` 자동 실행. 블로커였던 구 스키마 전제는 07-18 뷰 재구축으로 해소 — 하니스 시드가 신 스키마(V15)에 직접 INSERT하므로 프레시 DB + V1~V15 + run.sh 구조가 성립. CI 워크플로에 Postgres 서비스 + Flyway 적용 + run.sh 연결만 남음 |
| 구 산출물·구 화면 정리 | `content_ranking` 등 07-12 이전 산출물 테이블은 구 `/dashboard`가 아직 읽어 보류(B1 때 확인). 프론트 전환 완료 후 구 `/api/*`·`/dashboard`·`/posts/{shortCode}` 데모와 일괄 정리. **07-30 진행**: `/api/influencers/{handle}`(07-27)에 이어 `/api/contents`·`/api/posts/{shortCode}` 제거 완료 — 잔존 was `/api` 표면은 `/api/auth/{login,logout}`·`/api/me`·`/api/saved/*`(현역)와 `/dashboard`·`/posts/{shortCode}` 데모. `SecurityConfig`의 `/api/**`는 인가 화이트리스트가 아니라 CORS 등록이라 유지(현역 표면이 쓴다). 후속 릴리스에 `content_metric_snapshots` 테이블 DROP 예정(expand-contract contract 단계). 커버리지 매트릭스(07-19부터 analytics `/ui/coverage`)는 분리 조회로 테이블 부재 내성 확보(07-18, [PR #34](https://github.com/subtle-madness/hypenow-backend/pull/34)) |
| 댓글 수집 재개 | MVP 제외(07-14) — 재개 시 크롤러 댓글 액터 복원 + B2 게이트 on + "214개 분석" 카피 정정("최근 최대 50개") 일괄 처리 |
| ~~LLM 모델~~ | 해소(07-18) — 골드셋 실측으로 전 축 gemini-3.1-flash-lite 확정(§7 태스크 L), Anthropic은 app_setting 롤백 경로 |
| ~~미러 갱신 주기~~ | 해소 — 07-21 analytics 스케줄 점화(04:30~) + 07-22 크롤 자동화가 그 앞 새벽으로 정렬 |
| ~~세션·쿠키 운영 전환~~ | 해소 — HTTPS·Secure 쿠키(07-15, application-prod.yml), 세션 인메모리→spring-session-jdbc(07-15, P2 `app.spring_session`), SameSite는 Lax 확정(07-17, [PR #23](https://github.com/subtle-madness/hypenow-backend/pull/23)) |
| 감성 비율 분모 | 기본 표기는 전체(스팸 포함), 원값 제공으로 프론트 전환 가능 |
| 미러 부분 실패 시맨틱 | 러너는 fail-fast — N번째 spec 실패 시 이후 spec은 이전 실행 상태로 남음(신선/스테일 혼재). B1에서 갱신 메타 기록 or 실패 집계 방식 결정 |
| D3·H 지표 고정 정합 | 매일 재크롤 개시 후 end_date=오늘 화면의 as-of(이력 최신)가 +3일 고정과 어긋남 — 목록·상세의 "현재" 지표를 `contents`(고정) 기준으로 전환 검토. 과거 기간 화면 재현·인플루언서 상세 참조용 이력 as-of는 유지 |
| ~~Flyway missing 완화 국한~~ | 해소(07-15) — 완화를 프로퍼티(`analytics.flyway-ignore-missing`, dev 기본 true)로 전환, 클라우드 타깃은 false 엄격 검증 |

## 9. 문서 맵과 수명 규칙

- **이 문서** — 현재 유효한 구조·상태·결정. 문서의 유일한 진입점, 항상 최신 유지
- [crawler/README.md](crawler/README.md) — 수집 파이프라인 실행·운영
- [docs/hype-score.md](docs/hype-score.md) — 하입 스코어 산식·상수 정본 지도(**항상 최신**). 산식·상수·표면·배포 상태가 바뀌면 같은 PR에서 갱신. 상수의 적합 모수·근거·표류 위험이 여기 모여 있다
- `docs/superpowers/specs/` — 설계 기록(ADR 성격). **영구 보존·내용 불변** — 대체되면 첫머리 상태 헤더만 갱신
- `docs/superpowers/plans/` — 상세 구현 계획(소모품). 태스크 착수 시 작성, **실행 완료·폐기 시 `plans/archive/`로 이동**
- **상태 헤더 규칙**: 모든 dated 문서는 첫머리에 상태를 단다 —
  `> 상태: 🟢 활성 · ✅ 구현/실행/반영됨 · 🗄 대체됨 → 링크 · ⏸ 보류`
