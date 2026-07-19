# hypenow 백엔드 — 메인 설계 문서

> **살아있는 문서.** 구조·결정이 바뀌면 이 문서를 먼저 고친다. 상세한 시점 기록(왜 그렇게 정했는지의
> 전말)은 `docs/superpowers/specs/`의 dated 문서에 남기고, 여기서는 **현재 유효한 그림**만 유지한다.
> 각 섹션을 고칠 때 하단 [결정 기록](#7-결정-기록)에 한 줄을 추가한다.
>
> 마지막 갱신: 2026-07-18

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
| `influencer` | 계정 (username, status, followers, 뷰티 판정 beauty/beauty_company/beauty_judged_at) |
| `content` | 게시물 제어 (short_code, content_type, owner, uploaded_at, origin DISCOVERY/ENUMERATION, status) — 캡션·지표 없음 |
| `raw_media_page` | 릴스 페이지 원형(HIKER_V2_CLIPS jsonb) — 릴스 캡션·지표·썸네일의 소스 |
| `raw_profile` | 프로필 원형(SELF_GQL·HIKER_MOBILE 등 source별 jsonb) — SELF_GQL엔 내장 타임라인 12개(피드 캡션·지표의 소스) |
| `raw_post_detail` | 구 시대 상세 payload — 신 파이프라인 미사용(LEGACY, 크롤러 대시보드 전용) |
| `raw_comment` | 댓글 원문 (writer/text/written_at 실컬럼) — 수집 게이트 off, 신규 유입 없음 |
| `app_setting` | 런타임 설정 key-value (분석 뷰도 여기서 임계값을 읽음) |

### 분석 뷰 (raw DB의 `analytics` 스키마)

`analytics/views/NN_*.sql` 번호순 적용 컨벤션. 2026-07-18 신 crawler 스키마(V15) 기준으로
전면 재구축 — base 층(00)이 raw 접촉을 격리하고, 서빙 모수는 뷰티 인플루언서
(QUALIFIED ∧ beauty ∧ ¬beauty_company). 04는 LLM 캡션 선분석 후보 뷰(미러 안 함).

### analysis DB

- **분석 결과** — 뷰 결과가 미러되는 테이블(Flyway로 명시 정의 — §4-3). analytics가 쓰고 was가 읽는다.
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
  인증 코드 해시·만료·시도·verified 상태, 이메일당 1행·가입 성공 시 소비) / `signup_codes`(V8 — 클로즈베타
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

**운영 중**: crawler 파이프라인(discover→qualify→aggregate), analytics 상주 어드민(8082 `/ui` —
미러·LLM 잡 트리거, 태스크 I), was `/v1` API(스펙 v1 P1~P3 + 로그인 월) + 검증용 내부 페이지
`/coverage`(celfit-front **배포본(origin/main)** 실소비 필드 기준 커버리지 매트릭스 — 07-18 재정의).
구 랭킹 대시보드(`/dashboard`)와 게시물 데모(`/posts/{shortCode}`)는 프론트 전환 완료까지 잔존 —
`/dashboard`는 옛 산출물(`content_ranking`)을 읽는다(정리 §8).

**상세 분석 작업 트랙** (구조 설계: [specs/2026-07-12-detail-analysis-design.md](docs/superpowers/specs/2026-07-12-detail-analysis-design.md) ·
데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md)):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| A | 분석 기반 | base 뷰·최근 N개 윈도우 뷰 재작성(raw 접촉은 base 뷰만) + 설정 키 + `contract-analysis` 골격 + 타입 미러·SQL 테스트 하니스 구축 | — | ✅ |
| F | LLM 공통 | 호출 골격 + **정확도/비용 스파이크** + 모듈 소속 확정 — F-2(VLM)는 B3에서 실험 | — | ✅ |
| B1 | 드로어 비LLM 집계 | 서빙 뷰·미러 4종 (accounts·contents·content_comments + 지표 스냅샷 이력 `content_metric_snapshots` — 07-13 개통) | A | ✅ |
| B2 | 드로어 댓글 LLM | 감성·키워드·구매의도 → 집계 + 미러 — **댓글 수집 MVP 제외(07-14)로 신규 유입 없음** | F | ✅ |
| B3 | 드로어 콘텐츠 LLM | 감지 + 콘텐츠 속성 + "왜 잘됐나" (07-14 VLM 잔여분 개통 — 어휘는 celfit-front 계약, 유통사 감지 포함) | F, B2 | ✅ |
| C1 | 인플루언서 비LLM 집계 | AccountReport 결정 지표 — 계정 요약·카테고리 믹스·게시물 시계열 3종 뷰 + 미러 | A | ✅ |
| C2 | 인플루언서 계정 LLM | AccountReport 카피 7종(tagline~paceNote) — stale+쿨다운 재분석·이력 INSERT. 캡션 분류(브랜드·광고·카테고리)는 별도 후속(B4) | F, C1 | ✅ |
| D | 드로어 API | `GET /api/posts/{shortCode}` — post/account/comments + analysis 블록·댓글 aiCategory(B2·B3 산출물 포함, 1회 호출). 댓글 수집 제외(07-14)로 comments·aiCategory는 유입 없음 | B1, B2·B3(확장분) | ✅ |
| D3 | 드로어 as-of | `GET /api/posts/{shortCode}?endDate=` — 집계 기간 끝 시점 스냅샷으로 지표 재구성(captured_at ≤ endDate의 KST 하루 끝 중 최신), 스냅샷 없으면 404(그 시점 화면에 부재). 생략 시 최신 | D, B1(스냅샷 미러) | ✅ |
| H | 랭킹 목록 API | `GET /api/contents` — 프론트 URL 파라미터 계약(start_date·end_date·main/mid/sub_category·content_type·follower·ad_type·distributor·sort·q) 그대로. 기간=게시일 필터, 지표=end_date 시점 스냅샷, 분석 완료 콘텐츠만, 기본 정렬 hype. 유통사 필터는 컬럼 신설(VLM 개통) 전까지 매칭 0 | D3(as-of 규칙 공유), B3(카테고리·광고·유통사 어휘) | ✅ |
| E | 인플루언서 API | `GET /api/influencers/{handle}` — profile(accounts 조합) + report(AccountReport 결정 지표: stats·trend·chart·contentMix·ads·activity). 표현 조립(경과일·isActive 14일·lastAdNote·strip)은 was 몫. **C2 카피 조립 완료** — account_analyses 최신 1행의 카피 7종을 additive 서빙(이력 없으면 null) | C1, C2 | ✅ |
| B4 | 캡션 분류·숙성 가드 | 속성 분석을 캡션 주·썸네일 보조로 전환(5종: 광고·카테고리·브랜드·제품·유통사, `detected_products` 신설) + 어휘 DB화(V30 `beauty_taxonomy`) + 분석 대상 "게시 후 3일" 가드 | B3 | ✅ |
| G | 서비스 데이터 | `app` 스키마 신설(was 소유 Flyway) + 이메일+비밀번호 로그인(Spring Security 세션 쿠키·CSRF) + 이메일 소유권 인증(6.17 — V7 `email_verifications`·send/confirm·가입 전 강제, 07-19 구현: [specs/2026-07-18-email-verification-design.md](docs/superpowers/specs/2026-07-18-email-verification-design.md)) + 저장 2종(`/api/saved/influencers` 상태·메모, `/api/saved/contents` 북마크) | 독립 | ✅ |
| I | analytics 어드민 | 상주 서버(8082) `/ui` — **07-19 파이프라인 관측 대시보드로 재설계**: 퍼널(수집→후보→분석→서빙, 무거운 집계는 비동기 캐시)·잡 카드 3종(진행률·최근/다음 실행)·실행 피드(인메모리 이력)·접이식 로그. 비용 카드 폐지(Gemini 무료 전환). 잡별 락·수동 트리거·스케줄러 골격 유지 — [specs/2026-07-19-analytics-dashboard-design.md](docs/superpowers/specs/2026-07-19-analytics-dashboard-design.md) | A | ✅ |
| A2 | 뷰 신 스키마 재구축 | 분석 뷰 00~20을 신 crawler 스키마(V15 인플루언서 개편) 기준 재구축 — base 소스 교체(raw_media_page clips·SELF_GQL 내장 타임라인), 뷰티 인플루언서 모수 필터, 04 LLM 후보 뷰 신설, 하니스 신 스키마 시드 재작성. 07-18 구현 완료 | [PR #30](https://github.com/subtle-madness/hypenow-backend/pull/30) 머지 | ✅ |
| L | LLM Gemini 전환 | 전 분석 축(판정·속성+종합 통합 1콜·카피)을 `gemini-3.1-flash-lite`로 — 프로바이더 선택 `analytics.llm-provider`(기본 gemini, anthropic 롤백), 무료 키 페이싱(15RPM, 일 예산은 batch-limit) + 한도 소진 시 배치 이월, 문구 절제 규칙(LlmGuard). 크롤러 판정은 `crawler.beauty.judge`(기본 gemini, 팀 프롬프트·파서 재사용). 백필은 유료 키 Batch one-shot(submit/collect) — [plans/archive/2026-07-18-gemini-llm-stack.md](docs/superpowers/plans/archive/2026-07-18-gemini-llm-stack.md) | F, B4, C2 | ✅ (백필 실행은 GEMINI_API_KEY_PAID 등록 대기) |

**API 스펙 정렬 트랙** (2026-07-15 프론트 계약 채택 — [specs/2026-07-15-hypenow-api-spec-alignment-design.md](docs/superpowers/specs/2026-07-15-hypenow-api-spec-alignment-design.md)):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| P1 | V1 읽기 API | envelope·에러 공통 + `/v1/contents`·`/v1/contents/{id}/ai-report`·`/v1/influencers/{id}`(+ai-report). 병행 데이터 보강: hypeScore 재정의(0~100)·유통사 슬러그·updatedAt·email/externalLink 조사 (07-15 개통) | H, D, E, B4 | ✅ |
| P2 | 서비스 데이터 정렬 | G 확장 — Spring Session JDBC(`app.spring_session`, 세션 목록·개별 로그아웃·hypenow-session 30일 슬라이딩), `/v1/auth`(가입·로그인·로그아웃·레이트리밋), users 프로필 15필드(V3), 저장 2종 스펙 계약화(memo upsert V4·카드 조합 목록), `/v1/me` 계정(프로필 PATCH·비번·세션·프로필 이미지·탈퇴), 게이트 이벤트(V5), P1 응답 개인화 필드(isContentsSaved/isInfluencerSaved) (07-15 개통) | G, P1 | ✅ |
| P3 | 부가 | `/v1/stats` — 랜딩 통계(스펙 6.20)를 분석 층 1행 뷰·미러(landing_stats V32)로 서빙, 모수는 마이크로 구간 계정(3천~5만)·강한 HTTP 캐시(1시간). 유사 콘텐츠(6.2)는 **범위 제외 확정**(제품 고려 대상 아님 — 미구현) (07-17 개통) | P1 | ✅ |

기존 `/api/*`는 프론트 전환 완료까지 병존, fit(스펙 6.18)은 보류.

**클로즈베타 전환 트랙** (2026-07-19 프론트 요청서 — 초대코드 가입 전용 + 도입문의 접수):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| CB1 | 배치 1회용 가입 코드 | V8 `signup_codes`(채널별 발급) + 가입 트랜잭션 원자 선점(SignupService) + `POST /v1/auth/signup-code/verify`(관문 사전 검증) + 단일 공용 코드(app_setting) 즉시 폐기 + 코드 생성 스크립트(deploy/scripts) | — | ✅ ([PR #53](https://github.com/subtle-madness/hypenow-backend/pull/53) 머지 대기) |
| CB2 | 가입 필드 경량화 | V9 — 선택 필드 6종 NULL 허용·기본값 제거 + `usage_purpose`(대행사) + validator optionalIn. **머지·배포 후 프론트 호환 모드 기본값 제거 통지 필수** | CB1(스택) | ✅ ([PR #54](https://github.com/subtle-madness/hypenow-backend/pull/54) 머지 대기) |
| CB3 | 도입문의 API | V10 `inquiries`(uuid PK) + `POST /v1/inquiries`(Public, IP 분당 2회) — 운영자 확인은 DB 조회, Resend 알림은 후속 옵션 | CB2(스택 — V 번호 순서) | ✅ ([PR #56](https://github.com/subtle-madness/hypenow-backend/pull/56) 머지 대기) |

두 트랙 전 태스크 완료(07-17). 남은 작업은 §8 미결과 프론트 REST 전환 연동(celfit-front은 아직
Drizzle/메모리 모드 — seam만 준비됨).

## 6. 데이터 제약 (해석 주의 — 모든 지표 설계의 전제)

- **피드 게시물은 조회수가 항상 NULL** (인스타가 공개 안 함). 평균·히트·확산배율 계산 시 NULL 규칙 필수.
- 조회수 = 인스타 공개 재생수(`videoPlayCount`, 폴백 `videoViewCount`). 비로그인 취득 가능 실측 확인(07-10).
- 게시물 지표는 **중복 크롤링으로 스냅샷이 누적**되지만, 서빙 지표(`contents`)는 **업로드 +3일 이후
  가장 이른 스냅샷으로 고정**(07-14 정정 ③ — 키 `analytics.metric-pin-days` 기본 3, B3 숙성 가드와 같은
  3일 기준). 고정 후보가 없으면 최신 폴백(구크롤러 조기 수집 잔재 5건 보호 — 개편 크롤러는 3일 미경과를
  수집하지 않아 소멸 예정). 메타(썸네일·캡션)는 최신 스냅샷. 시점별 조회는 스냅샷 이력
  (`content_metric_snapshots`, B1)으로 — D3·H의 end_date as-of는 이력 조회로 고정 기준과 공존
  (기간 화면 재현·인플루언서 상세 참조용). 추이 그래프 UI는 확정안에서 제외된 상태 유지(데이터만 보존).
- **댓글 수집은 MVP 제외**(07-14) — B2 분류·`content_comments` 경로는 신규 유입 없음(구현 보존).
  재개 시 게시물당 최대 50개 제약이 다시 적용된다("214개 분석" 카피 불가 이슈 포함).
- 저장·공유·도달·노출 지표 없음. 팔로워는 qualify 시점 값.
- LLM 댓글 분류 실측 비용: 게시물 1,000건당 Opus ≈ $61 / haiku ≈ $12.2 (동기·무캐시·무배치 기준).
  VLM(썸네일)은 건당 ≈ $0.03~0.05 (opus 4.8, 07-14 실측).
- LLM 운영 비용(07-18 확정): 전 축 gemini-3.1-flash-lite **무료 티어 $0**(분당 15콜·일 1,500콜 예산 —
  판정 ~100 / 통합 ~450 / 카피 ~150콜), 초기 백필 2만 건은 유료 프로젝트 Batch API ~$9.
  Anthropic 단가는 롤백 참고치. 일 1,500콜 초과 성장 시 GEMINI_API_KEY만 유료 키로 교체(코드 무변경).
- **인스타 CDN 썸네일 URL은 수집 후 ~4일이면 만료**(403) — 썸네일 첨부는 최신 수집분에만 가능
  (분석 잡이 HEAD 프리체크, 만료분은 캡션 단독 분석 — B4). 썸네일 신호까지 반영하려면 크롤링 직후 분석 배치를 돌릴 것.

## 7. 결정 기록

> 새 결정은 맨 위에 추가. 전말은 링크된 dated 문서에.

| 날짜 | 결정 | 근거/상세 |
|---|---|---|
| 2026-07-19 | **클로즈베타 전환(초대코드 가입 전용)** — 단일 공용 코드를 배치 1회용 코드(V8 `signup_codes`)로 **즉시 전환**(병행 없음 — 검증 로직 두 벌 유지가 더 위험, 기존 코드를 넣으면 1회용으로 의미가 바뀜). 소진 정본은 `used_at`(used_by는 ON DELETE SET NULL — 탈퇴해도 소진 유지), 선점은 가입 INSERT와 **한 트랜잭션의 조건부 UPDATE**(동시 가입 레이스 1명만 통과, 409 실패 시 미소진 롤백). 관문 UX용 사전 검증 `POST /v1/auth/signup-code/verify`는 소진하지 않음(TOCTOU는 가입 재검증이 흡수). 가입 필드 경량화(V9 — 필수는 이메일·비밀번호·이름·userType·companyName·약관 3종, 값 있으면 어휘 검사 유지). 도입문의 V10 `inquiries`는 **uuid PK**(공개 응답 순번 노출 회피), 레이트리밋은 시간 윈도우 확장 없이 분당 2회로 갈음. ⚠️ 배포 순서: 코드 배치 적재 → 프론트 env 코드 교체·호환 모드 기본값 제거 통지 | [PR #53](https://github.com/subtle-madness/hypenow-backend/pull/53)·[#54](https://github.com/subtle-madness/hypenow-backend/pull/54)·[#56](https://github.com/subtle-madness/hypenow-backend/pull/56) (스택, 순서대로 머지) |
| 2026-07-19 | **어드민 대시보드 재설계 (태스크 I 개편)** — 운영 첫날 피드백(단계 추적 불가·비용 카드 무의미·자동/수동 구분 불가)으로 관측 대시보드 전환. 잡→ProgressReporter(analyze 경계 인터페이스)→JobProgressRegistry 진행률, RunHistory 인메모리 피드(이력 DB 없음 유지), PipelineStatsService(빠른 동기+무거운 비동기 30분 캐시), ScheduleInfo(크론 다음 발화 — base는 시스템 존, @Scheduled 해석과 정합·표시만 KST). JobCostEstimator 삭제. 리뷰에서 '다음 예정' 9시간 존 오차 잡아 수정 | [specs/2026-07-19-analytics-dashboard-design.md](docs/superpowers/specs/2026-07-19-analytics-dashboard-design.md) |
| 2026-07-19 | **이메일 소유권 인증 구현(6.17 [TBD] 해소)** — 가입 전 강제(스텝5), 6자리 코드(TTL 10분·오입력 5회), Resend HTTPS 발송(키 미설정 시 로깅 폴백 + 기동 로그, connect 5s/read 10s 타임아웃), 서버 상태 방식(V7 `email_verifications`, verified 30분·가입 성공 시 1회 소비). signup 검증 순서에 403 EMAIL_NOT_VERIFIED 삽입(429→가입 코드→필드→이메일 인증→409). 운영 개통은 Resend 도메인 인증(DNS SPF/DKIM) + RESEND_API_KEY 등록 필요 — 프론트 배선(REST 전환) 전 배포 시 운영 signup은 인증 선행 없이는 403 | [specs/2026-07-18-email-verification-design.md](docs/superpowers/specs/2026-07-18-email-verification-design.md) |
| 2026-07-19 | **운영 서버에 raw DB 상주 컨테이너 신설 + e2e 실데이터 이전** — 오라클 운영 compose에 `postgres-raw`(crawler DB, 루프백 5433, 계정 .env `RAW_DB_*`) 추가. 07-17 일회성 LLM 실행(e2e-*)에 올라가 있던 raw 실데이터 2.4GB(콘텐츠 27,093·인플루언서 12,837)를 postgres-raw로, 분석 결과 public 스키마(계정 12,638·콘텐츠 16,686·LLM 분석 account 77/content 88·landing_stats)를 운영 analysis DB로 이전(구 114건 미러 대체, `app` 스키마·세션은 보존 — e2e의 검증용 유저 3건은 미이관). 운영 /v1/contents 실데이터 응답 검증 완료. was의 raw 접근 금지 규율은 유지(같은 compose 네트워크지만 접속 정보 미주입). 미결: backup.sh는 analysis만 백업(raw 백업 여부), e2e-* 컨테이너 정리 | deploy/compose.yaml |
| 2026-07-18 | **LLM 스택 Gemini 3.1 Flash-Lite 전환 (태스크 L)** — 골드셋 40건 실측(Opus 기준 mainCategory 90%·adType 98%·subCat Jaccard 0.62·브랜드 88%, Haiku 4.5보다 우수·5.5배 저렴)으로 전 분석 축 통일. ②속성+③종합은 통합 1콜(`ContentInsightPort` — Anthropic은 기존 어댑터 2콜 컴포지트로 보존해 app_setting `analytics.llm-provider` 롤백 경로), 문구 프롬프트에 절제 규칙(`LlmGuard` — 표본 3건 미만 단정 금지·조언 금지·수치 인용, 골드셋 문구 검증 통과본) 필수. 이원 운영: 일상=무료 키(`GEMINI_API_KEY`) 동기+RPM 페이싱, 429/일한도 소진은 에러 아닌 배치 이월 / 백필 2만 건=유료 키(`GEMINI_API_KEY_PAID`) Batch one-shot(`analytics.backfill-submit`→`-collect`, ~$9). ①판정은 크롤러 BeautyJudge 포트 뒤 Gemini 어댑터(팀 프롬프트·파서 재사용, `crawler.beauty.judge` 기본 gemini). 댓글 분류는 MVP 휴면이라 Anthropic 유지. 구모델(2.5)은 신규 API 키에서 404 — 3.1이 유일 선택지 | [plans/archive/2026-07-18-gemini-llm-stack.md](docs/superpowers/plans/archive/2026-07-18-gemini-llm-stack.md) |
| 2026-07-18 | **설계-구현 전수 감사 + 문서 드리프트 정비** — 백엔드·프론트 specs 35건 전수 대조 결과 백엔드는 무동작 스텁 0건·트랙 표와 실체 일치. 정비: §5 G 표현 명확화(이메일 **소유권 인증**(6.17)은 [TBD] 미구현 — G의 "이메일 인증"은 이메일+비밀번호 로그인 의미), 대체·현행과 어긋난 spec 7건 상태 헤더 갱신(detail-source-selector 🗄 등), `/coverage` 진입점 제거로 고아가 된 `PostDemoRepository.analyzedPosts()` 죽은 쿼리 삭제(`/posts/{shortCode}` 페이지 자체는 URL 직접 접근으로 잔존). 주요 잔여 갭은 프론트 측: 마스터 비밀번호 백도어(celfit-front core.ts — 제거 대기), 이메일 인증 실구현(설계 착수), REST 배선(PR #18 draft) | 감사 세션 리포트 |
| 2026-07-18 | **/coverage 매트릭스를 celfit-front 실소비 필드 기준으로 재정의** — 기준 코드는 celfit-front 배포본(origin/main). 구 content-ranking 카드 14행을 프론트가 실제 렌더·필터에 쓰는 /v1 필드 27행(카드·필터 6.1 → 드로어 리포트 6.3 → 인플루언서 6.4/6.5)으로 교체. 타입에만 있고 UI 미소비인 필드(email·external_link)와 /v1 미사용 미러(content_metric_snapshots — 단 metric_captured_at 원천이라 coverage.sql 골격 가드는 유지)는 제외. 구 산출물 content_ranking 행은 본 쿼리에서 분리 조회(테이블 부재에도 매트릭스 생존 — was는 폴백 행, coverage.sql은 to_regclass DO 블록). 매트릭스 정의 쌍(CoverageRepository ↔ analytics/check/coverage.sql) 컨벤션 유지 | feat/coverage-v1-fields 브랜치 |
| 2026-07-17 | **분석 뷰 신 스키마 재구축 설계 확정 (A2)** — 신 크롤러는 상세 수집 없이 열거만 하므로(raw_post_detail 소멸) 캡션·지표 소스를 raw_media_page clips 아이템·SELF_GQL 내장 타임라인 노드로 교체(플랫 뷰 체인, base 층에 평탄화 뷰 2종 신설). 서빙 모수는 뷰티 인플루언서만(QUALIFIED ∧ beauty ∧ ¬company, 1,496계정), 미러 계약은 형태 유지+우아한 공백(피드 광고 false·카테고리 믹스 0행 — B4 캡션 분류가 대체 소스), 04_analysis_candidates 신설(캡션 선분석 Haiku+Batch 입구 — 숙성 가드 3일·캡션 필수까지 뷰 담당, 분석됨 대조·상한은 Java). 기반은 PR #30 머지 후 develop | [specs/2026-07-17-analytics-views-new-schema-design.md](docs/superpowers/specs/2026-07-17-analytics-views-new-schema-design.md) |
| 2026-07-17 | **analytics 어드민 UI + 스케줄러 골격 설계 확정 (태스크 I 신설)** — analytics를 상주 웹 서버(8082)로 전환, 크롤러 어드민 패턴 이식(`/ui` 잡 버튼 4종 + LLM 예상 비용 카드 + LogBuffer 로그 패널 + 잡별 락·비동기 트리거). 실행 이력 DB 테이블은 두지 않음(로그로 충분 — 사용자 확정). 스케줄러는 크롤러 동일 게이트(`analytics.schedule.enabled`, 기본 off) 골격만. `mirror-on-startup` 기본 false로 전환하되 cloud push는 one-shot CLI 보존(cloud 프로파일만 true) | [specs/2026-07-17-analytics-admin-ui-design.md](docs/superpowers/specs/2026-07-17-analytics-admin-ui-design.md) |
| 2026-07-17 | **로그인 월 + 가입 코드 도입** — 제품 구조 변경(사용자 확정): 모든 조회는 로그인 필수, 가입은 단일 공용 코드 필수. SecurityConfig를 화이트리스트로 전환(기본 authenticated, 열린 경로는 /v1/auth·/v1/events/gate(익명 측정 유지)·/v1/stats(랜딩 통계 — P3와 병합 시 추가, 로그인 전 랜딩이 소비)·/health·swagger(로컬)만) — /v1 읽기 4종·구 /api·내부 페이지·프로필 이미지 전부 잠금, 401 계약은 기존 유지(/v1 envelope). 가입 코드는 `app.app_setting`(V6) `signup.code`와 trim 정확 일치, 불일치·미설정 403 INVALID_SIGNUP_CODE(fail-closed — 빈 값 시드로 배포되며 운영자 UPDATE로 개통). 레거시 /api/auth/signup은 코드 우회 뒷문이라 폐쇄(인증 입구 /v1 일원화) | [specs/2026-07-17-login-wall-signup-code-design.md](docs/superpowers/specs/2026-07-17-login-wall-signup-code-design.md) |
| 2026-07-17 | **P3 랜딩 통계 개통 + 유사 콘텐츠 제외 확정** — GET /v1/stats(스펙 6.20)를 분석 층 1행 뷰·미러(landing_stats V32)로 서빙, 강한 HTTP 캐시(1시간). **모수는 마이크로 구간 계정(팔로워 3천~5만)과 그 콘텐츠로 통일** — 랜딩 카피·스펙 분포 합계 100과 일치(수집 114 중 55). 조회수는 릴스만(회신표 #16). 분포 %·합계 100 보정은 was 표현 계층(최대 잔여). updatedAt은 미러 실행 시각. **유사 콘텐츠(스펙 6.2)는 제품 고려 대상이 아니라 구현하지 않기로 확정** — 스펙 6.2·회신표 #3은 미구현으로 남김 | [plans/archive/2026-07-17-p3-landing-stats.md](docs/superpowers/plans/archive/2026-07-17-p3-landing-stats.md) |
| 2026-07-17 | **정적분석 Error Prone 도입** — 전 모듈(4개) 컴파일에 Error Prone 단독 적용(1인 팀 시그널/노이즈 기준으로 SpotBugs 조합 대신 선택). ERROR 등급만 빌드 실패로 걸고 WARNING은 비활성(`disableAllWarnings`), 한국어 테스트 메서드명 컨벤션과 충돌하는 `UnicodeInCode`만 체크 해제. 초기 지적은 전 모듈에서 실질 1건(SecurityConfig `csrfToken.get()` 반환값 무시 — 의도적 지연 발급 해제 호출이라 `unused` 관용구로 정리, baseline/suppress 파일 불필요). 컴파일 단계에 걸리므로 CI(PR #22)의 `./gradlew test`가 그대로 정적분석 체크가 된다 | [PR #25](https://github.com/subtle-madness/hypenow-backend/pull/25) |
| 2026-07-17 | **GitHub Actions CI 도입** — develop 대상 push·PR마다 `./gradlew test` 전체 실행(.github/workflows/ci.yml). Testcontainers는 러너 기본 Docker로 충분, 외부 키 불필요. Gradle 캐시는 gradle/actions/setup-gradle(develop push에서 쓰기, PR은 읽기). SQL 하니스는 제외 — 뷰가 실DB(V6 시점) 스키마를 전제해 리포 마이그레이션(V8+ influencer 개편)과 불일치, 프레시 DB 재현 불가 확인(§8에 블로커 기록) | .github/workflows/ci.yml |
| 2026-07-17 | **P2 운영 배포 + 세션 쿠키 SameSite=Lax 확정** — P2(가입·로그인·/v1/me·저장·레이트리밋)를 api.hypenow.io에 배포, 운영 curl E2E(가입 201→me 200→로그아웃 204→재로그인 200, 쿠키·CSRF 왕복)와 로그인 레이트리밋(분당 10 초과 429) 실측 통과. 미결이던 prod 쿠키 SameSite는 **Lax로 확정**(사용자 확인) — www↔api는 hypenow.io 하위 same-site라 전송 손실 없고 스펙과 일치, CSRF 방어 이중화. 테스트 계정은 검증 후 DB 삭제 | [PR #23](https://github.com/subtle-madness/hypenow-backend/pull/23) |
| 2026-07-17 | **Swagger(springdoc-openapi) 도입 — prod 미노출** — was에 springdoc-openapi-starter-webmvc-ui 3.0.3(Boot 4 지원 라인) 추가, `paths-to-match=/v1/**`로 스펙 v1 표면 17경로만 자동 문서화(구 /api·내부 페이지 제외). **정본은 프론트 API 스펙 문서** — Swagger는 서버 시그니처 확인용 보조 문서라 컨트롤러 어노테이션 없이 자동 스캔만. prod에서는 api-docs·swagger-ui 비활성(사용자 결정) — 문서는 로컬·개발 전용. UI /swagger-ui · 스키마 /v3/api-docs | [PR #26](https://github.com/subtle-madness/hypenow-backend/pull/26) |
| 2026-07-15 | **P2 서비스 데이터 정렬 개통** — /v1 인증·계정·저장·이벤트를 스펙 계약으로. HttpSession→Spring Session JDBC(app.spring_session, 세션 목록·개별 로그아웃, hypenow-session 30일 슬라이딩). 세션 principal은 안정 형상(userId·email — CredentialsContainer로 해시 미영속). users 프로필 15필드(V3), 저장 memo(V4), gate_events(V5). 세션 노출 id는 sha256 alias, 타 세션 삭제는 404 은닉. 프로필 이미지 로컬 저장. 레이트리밋 인메모리(분당 스윕). 게이트 이벤트는 CSRF 면제(익명 첫 방문자가 XSRF 쿠키 선행 없이 이벤트를 쏘도록 — append-only 측정 로그라 표적 가치 없음, 완전 익명 curl E2E 403→204 확인) | [plans/archive/2026-07-15-p2-service-data-alignment.md](docs/superpowers/plans/archive/2026-07-15-p2-service-data-alignment.md) |
| 2026-07-15 | **P1 V1 읽기 API 개통** — `/v1` envelope 계약으로 리더보드·콘텐츠 AI 리포트·인플루언서 프로필/리포트 4종 서빙. hypeScore 스펙 5.4 산식(0~100) 재정의 — 피드는 views 부재로 팔로워 ER 축 대체 산식(cbrt(axis²×fresh)×100, 축=min(min(ER,0.3)/0.10,1)), 산식은 `analytics.hype_score()` SQL 함수 단일 원천. 유통사 필터는 `beauty_distributors.slug` 사전 해석, 카테고리 확장 매칭은 `beauty_taxonomy` SQL 처리. email은 미수집 null 확정, 매핑 단계 405/미존재 경로 404는 envelope 미적용(수용) | [plans/archive/2026-07-15-p1-v1-read-api.md](docs/superpowers/plans/archive/2026-07-15-p1-v1-read-api.md) |
| 2026-07-15 | **프론트 API 스펙 v1 전체 채택** (fit 6.18 제외·보류) — `/v1` prefix + envelope 계약을 was 정본으로, 기존 `/api/*`와 병존 후 전환. 분석 윈도우는 스펙과 12로 정렬(develop 기본값 그대로). 인증은 G 구현 유지+확장: HttpSession→Spring Session JDBC(세션 목록·개별 로그아웃), 쿠키 `hypenow-session` 슬라이딩 30일, same-site 전제(도메인 확보로 Vercel rewrite 동일 오리진 — 아래 배포 행 참조). hypeScore는 스펙 5.4 산식(0~100)으로 재정의(현행 원값 방식 대체). 트랙 P1(V1 읽기)→P2(서비스 데이터 정렬)→P3(stats·유사도) 분해 | [specs/2026-07-15-hypenow-api-spec-alignment-design.md](docs/superpowers/specs/2026-07-15-hypenow-api-spec-alignment-design.md) |
| 2026-07-15 | **was+DB 오라클 배포 체계 + 도메인 hypenow.io** — 배포 범위는 was+analysis DB만(크롤·분석은 로컬 유지, 미러가 SSH 터널로 push). 오라클 A1 무료(도쿄, 2/12) + docker compose 3컨테이너(postgres 루프백/was/caddy HTTPS), 이미지 GHCR multi-arch로 타사 30분 이사 가능 구조. 일일 pg_dump(서버 7일)+rclone Google Drive(30일) 백업. 도메인 확보: 프론트 `www.hypenow.io`(Vercel) / API `api.hypenow.io` — 프론트 연동은 Vercel rewrite로 같은 오리진화(CSRF 쿠키), prod CORS는 www.hypenow.io만. was `prod`·analytics `cloud` 프로파일 신설 — Flyway `*:missing` 완화는 dev 기본값으로 국한(§8 해소) | [specs/2026-07-15-oracle-deploy-design.md](docs/superpowers/specs/2026-07-15-oracle-deploy-design.md) |
| 2026-07-15 | **태스크 G: 서비스 데이터 완료** — `app` 스키마(was 소유 Flyway, 이력 `app.flyway_schema_history`) + 이메일+비밀번호 인증(Spring Security DaoAuthenticationProvider·BCrypt·세션 쿠키, 미인증 401 고정) + 저장 2종(인플루언서 후보 상태·메모 부분 갱신 upsert / 콘텐츠 북마크 멱등 upsert). 상태 어휘는 was가 생산자로 확정: `reviewing·contact_planned·collaborating`. CSRF는 XSRF-TOKEN 쿠키 + **SpaCsrfTokenRequestHandler**(지연 발급 해제 + raw 헤더 허용 — 기본 Xor 핸들러만으론 SPA가 첫 쓰기 전 쿠키를 못 받고 raw 값도 403, 실 curl E2E에서 발견). CORS는 WebConfig GET-only를 걷어내고 SecurityConfig `CorsConfigurationSource`로 일원화(allowCredentials). 저장 기술은 JdbcClient 유지(기존 관용구·최소 의존). 확장점: spring-session-jdbc(현 인메모리 세션)·운영 HTTPS 쿠키(Secure·SameSite=None). 같은 태스크의 딴 갈래였던 후보 관리 단독 구현은 닫힌 [PR #13](https://github.com/subtle-madness/hypenow-backend/pull/13)에 보존 — app V1 충돌·후보 기능 중복으로 이 설계(07-14 사용자 확정)로 일원화 | [plans/archive/2026-07-14-task-g-service-data.md](docs/superpowers/plans/archive/2026-07-14-task-g-service-data.md) |
| 2026-07-16 | **프로필 404 = 소프트 딜리트(InfluencerStatus.DELETED)** — 계정 단위 404(삭제·개명)를 페처(SELF·HIKER_MOBILE)가 `Execution.notFound`로 표면화하고, qualify·collect·resnapshot이 해당 인플루언서를 DELETED로 전환. 데이터는 보존하되 모든 선정 쿼리(상태 필터)에서 자동 제외돼 매 실행 재시도·재과금이 끊긴다. Hiker 404는 전용 `NotFoundException`(ApifyException 하위)으로 구분 — 릴스의 기존 404 처리(수확 완료 마킹)는 유지. collect는 방문 트랜잭션 롤백과 분리하기 위해 run 루프(트랜잭션 밖)에서 저장 | feat/beauty-captions 브랜치 |
| 2026-07-16 | **비뷰티 재검 파이프라인 — RESNAPSHOT 잡 신설 + rejudge 선정 좁힘** — 캡션 유무 실험(60계정×4판정)에서 캡션이 비뷰티→뷰티로만 안정적으로 뒤집음(안정 판정의 ~10%, 비뷰티의 ~30%)을 확인. RESNAPSHOT은 캡션 없는 소스(HIKER_MOBILE·DATALIKERS)가 최신인 CLAUDE 비뷰티 판정분을 로컬 GQL로 재수집(비공개는 요청 없이 스킵, `resnapshot.batch-limit` 기본 200), beauty rejudge는 "판정 후 재료가 갱신된 비뷰티"로 선정을 좁힘(뷰티 판정분은 재검 안 함 — 역방향 뒤집힘 미관측). 재수집 완료 계정은 최신 스냅샷이 SELF_GQL이 되어 자연 소진 | feat/beauty-captions 브랜치 |
| 2026-07-16 | **뷰티 판정 3분류 — 비뷰티 / 뷰티 인플루언서 / 뷰티 회사** — `beauty_company`(V14) 신설, Claude 판정을 class(INFLUENCER/COMPANY/NOT_BEAUTY) 출력으로 전환. **회사는 명단 리스트업 전용** — 수집(collect)·릴스(reels)·유사발굴(similar) 대상에서 전부 제외(명시적 회사만 제외, NULL은 인플루언서 취급). 기존 판정분은 V14가 인플루언서(false)로 백필 — rejudge가 회사를 정정. 대시보드 BEAUTY_COMPANY 타일·명단 3버튼(뷰티/회사/뷰티 아님) 추가 | feat/beauty-captions 브랜치 |
| 2026-07-15 | **collect 프로필 실패(401 등) = 방문 실패·재시도, Hiker 피드 폴백 제거** — 프로필 원형이 방문의 유일한 재료(팔로워 추이 + 내장 피드 12개)이므로, 프로필을 못 찍으면 유료 피드 폴백으로 방문을 "성공" 처리하지 않고 북키핑 미갱신으로 남겨 다음 실행 재시도(추이 스냅샷 구멍·헛돈 방지). collect는 미디어 페처 의존이 사라져 정상·실패 경로 모두 피드 별도 요청 0 | feat/beauty-captions 브랜치 |
| 2026-07-15 | **collect 분리 — 게시물을 위한 프로필 수집(COLLECT) / 릴스 수집(REELS)** — 유료 HikerAPI 구간(릴스 1페이지)을 별도 REELS 잡으로 분리해 무료 구간(SELF 프로필 + 내장 피드 12개)과 독립 운용. REELS는 `last_reels_at` 북키핑(V13)·`reels.batch-limit`(기본 10)·주기는 collect와 공유, pk 미보유는 해석 없이 스킵 — **계정당 정확히 Hiker 1요청** 보장. content upsert 규칙은 ContentUpserter로 공유 | [specs/2026-07-15-collect-reels-split-design.md](docs/superpowers/specs/2026-07-15-collect-reels-split-design.md) |
| 2026-07-15 | **collect는 뷰티 계정 전용** — 수집 대상을 QUALIFIED 전체에서 `beauty=true`로 좁힘(비뷰티·미판정은 방문 안 함). 파이프라인이 발굴→판정→**뷰티 판정**→수집으로 확정되고 대시보드도 뷰티 판정 그룹(뷰티/비뷰티/미판정)·READY(뷰티만) 분리 표기. 같은 세션에서 뷰티 판정 재료에 최근 게시물 캡션(계정당 5개·100자) 추가, `beauty.batch-limit`(기본 500) 신설, 계정별 판정 로그 추가 | feat/beauty-captions 브랜치 |
| 2026-07-14 | was에 검증용 내부 페이지 2종 — `/coverage`(필드 커버리지 라이브 추적)·`/posts/{shortCode}`(게시물 드로어 시안형 상세 데모). 분석 결과 읽기 전용, 태스크 D(API)와 별개의 데모/점검 화면. 데모 세션의 VLM base64 전환은 B3 개통(아래 행)과 동일 결론으로 합류 | [specs/2026-07-14-was-coverage-page-design.md](docs/superpowers/specs/2026-07-14-was-coverage-page-design.md) |
| 2026-07-14 | **E에 C2 카피 additive 조립 완료** — `account_analyses` 계정별 최신 1행(계약 record `AccountAnalysis`)을 report의 tagline·summary·trend.note·chart.note·contentMix.traits·ads.headline·activity.paceNote로 서빙. 미러와 SQL 조인 없이 was 코드 조합(§4-4), traits(jsonb)는 was 매핑 계층 파싱. 이력 없으면 카피 전부 null(블록 형태 유지), adHeadline은 이력 있어도 null 허용. ads.brands는 캡션 분류 후속(§8)까지 필드 부재 유지 | [specs/2026-07-14-c2-account-llm-design.md §1](docs/superpowers/specs/2026-07-14-c2-account-llm-design.md) |
| 2026-07-14 | **캡션 분류 + B3 숙성 가드 (B4)** — 캡션 5종(광고 구분·카테고리·브랜드·제품·유통사)을 별도 잡이 아닌 기존 속성 콜 전환(캡션 항상·썸네일 생존 시만 첨부, 병합은 모델 안에서)으로. 어휘는 analysis DB `beauty_taxonomy`·`beauty_distributors`(V30 시드)로 이동 — BeautyTaxonomy는 로더 스냅샷, 프롬프트·sanitize 동일 원천 유지, main_category CHECK는 sanitize로 이관. `detected_products jsonb`([{name,brand}]) 신설. 분석 대상에 게시 후 3일 숙성 가드(`analytics.analyze-maturity-days`) | [specs/2026-07-14-caption-classification-design.md](docs/superpowers/specs/2026-07-14-caption-classification-design.md) |
| 2026-07-14 | **게시물 지표 +3일 고정 구현(정정 ③)** — `v_contents` 지표를 업로드 +3일 이후 **가장 이른** 스냅샷으로 고정(키 `analytics.metric-pin-days` 기본 3 — B3 숙성 가드의 "게시 후 3일"과 같은 기준, 키 공유 권장). 고정 후보 없으면 최신 폴백(구크롤러 조기 수집 잔재 5건 보호, 개편 크롤러는 3일 미경과 미수집이라 소멸 예정). 메타(썸네일·캡션)는 최신 유지(서명 URL ~4일 만료 대응). 적용은 `v_contents`만 — 계정 집계(01·10)·B3 기준선(03)은 최신 기준 유지, 미러 DDL·record 무변경. 실데이터 137건 전행 동일 확인(회귀 0). D3·H의 end_date as-of는 이력 조회로 공존 — 매일 재크롤 개시 후 정합은 §8 | [PR #14](https://github.com/subtle-madness/hypenow-backend/pull/14) |
| 2026-07-14 | **MVP 범위·데이터 정책 정정 4건** — ① 크롤링 개편의 "최근 3개월"은 **초기 확보(백필) 시작 범위**이고 지표 윈도우와 무관 — 계정 지표 윈도우는 **최근 24개**(개수 기반 유지, `analytics.recent-window` 12→24). ② **댓글은 수집·분석 모두 MVP 제외** — B2·`content_comments` 경로 신규 유입 없음(구현 보존). 댓글 외 LLM 산출(콘텐츠 감지·종합 텍스트·계정 카피)은 전부 MVP 포함, 게시물 상세 드로어도 MVP 유지(댓글 분석 탭만 데이터 부재). ③ **게시물 지표는 업로드 +3일 시점 고정** — `contents`가 최신 스냅샷을 따라가는 구조 폐기, 재크롤 스냅샷은 이력 보존(인플루언서 상세 조회 참조용). ④ **캡션 LLM 산출 5종 확정: 광고 구분·카테고리·브랜드·제품·유통사** — 항목 목록은 수정 용이 구조로(하드코딩 대신 설정/데이터 기반) | 2026-07-14 방향 정리 세션 |
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
| 계약 테스트 CI 연결 | raw 변경 PR에서 `analytics/test/run.sh` 자동 실행. 블로커였던 구 스키마 전제는 07-18 뷰 재구축으로 해소 — 하니스 시드가 신 스키마(V15)에 직접 INSERT하므로 프레시 DB + V1~V15 + run.sh 구조가 성립. CI 워크플로에 Postgres 서비스 + Flyway 적용 + run.sh 연결만 남음 |
| 구 산출물·구 화면 정리 | `content_ranking` 등 07-12 이전 산출물 테이블은 구 `/dashboard`가 아직 읽어 보류(B1 때 확인). 프론트 전환 완료 후 구 `/api/*`·`/dashboard`·`/posts/{shortCode}` 데모와 일괄 정리. `/coverage` 매트릭스는 분리 조회로 테이블 부재 내성 확보(07-18, [PR #34](https://github.com/subtle-madness/hypenow-backend/pull/34)) |
| 댓글 수집 재개 | MVP 제외(07-14) — 재개 시 크롤러 댓글 액터 복원 + B2 게이트 on + "214개 분석" 카피 정정("최근 최대 50개") 일괄 처리 |
| ~~LLM 모델~~ | 해소(07-18) — 골드셋 실측으로 전 축 gemini-3.1-flash-lite 확정(§7 태스크 L), Anthropic은 app_setting 롤백 경로 |
| 미러 갱신 주기 | 어드민 UI 수동 트리거(8082 `/ui`, 태스크 I). 스케줄 골격 있음(`analytics.schedule.enabled`, 기본 off) — 크론 켜는 시점·주기만 미결(크롤 일일 자동화와 함께 결정) |
| ~~세션·쿠키 운영 전환~~ | 해소 — HTTPS·Secure 쿠키(07-15, application-prod.yml), 세션 인메모리→spring-session-jdbc(07-15, P2 `app.spring_session`), SameSite는 Lax 확정(07-17, [PR #23](https://github.com/subtle-madness/hypenow-backend/pull/23)) |
| 감성 비율 분모 | 기본 표기는 전체(스팸 포함), 원값 제공으로 프론트 전환 가능 |
| 미러 부분 실패 시맨틱 | 러너는 fail-fast — N번째 spec 실패 시 이후 spec은 이전 실행 상태로 남음(신선/스테일 혼재). B1에서 갱신 메타 기록 or 실패 집계 방식 결정 |
| D3·H 지표 고정 정합 | 매일 재크롤 개시 후 end_date=오늘 화면의 as-of(이력 최신)가 +3일 고정과 어긋남 — 목록·상세의 "현재" 지표를 `contents`(고정) 기준으로 전환 검토. 과거 기간 화면 재현·인플루언서 상세 참조용 이력 as-of는 유지 |
| ~~Flyway missing 완화 국한~~ | 해소(07-15) — 완화를 프로퍼티(`analytics.flyway-ignore-missing`, dev 기본 true)로 전환, 클라우드 타깃은 false 엄격 검증 |

## 9. 문서 맵과 수명 규칙

- **이 문서** — 현재 유효한 구조·상태·결정. 문서의 유일한 진입점, 항상 최신 유지
- [crawler/README.md](crawler/README.md) — 수집 파이프라인 실행·운영
- `docs/superpowers/specs/` — 설계 기록(ADR 성격). **영구 보존·내용 불변** — 대체되면 첫머리 상태 헤더만 갱신
- `docs/superpowers/plans/` — 상세 구현 계획(소모품). 태스크 착수 시 작성, **실행 완료·폐기 시 `plans/archive/`로 이동**
- **상태 헤더 규칙**: 모든 dated 문서는 첫머리에 상태를 단다 —
  `> 상태: 🟢 활성 · ✅ 구현/실행/반영됨 · 🗄 대체됨 → 링크 · ⏸ 보류`
