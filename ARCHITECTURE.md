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

> 트랙 목록은 [`docs/tracks/`](docs/tracks/)를 참조한다 — **트랙 1개 = 파일 1개**
> (`docs/tracks/<트랙문자>-<슬러그>.md`). 새 트랙은 다음 미사용 문자로 파일을 새로 만든다
> (파일 존재 여부가 곧 문자 선점 대장 — 여러 세션이 동시에 새 트랙을 만들어도 서로 다른
> 파일이라 머지 충돌이 나지 않는다). 상태가 바뀌면 해당 트랙 파일만 갱신한다.
> 상태 기호: ✅ 완료 · 🔨 진행 중 · ⬜ 대기 · ⏸ 보류.

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

**트랙군**(그룹 배경·설계 문서 링크 — 그룹 내 개별 트랙은 `docs/tracks/`의 해당 파일 참조):

- **상세 분석 작업 트랙**(A~EE) — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](docs/superpowers/specs/2026-07-12-detail-analysis-design.md) ·
  데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **API 스펙 정렬 트랙**(P1~P4) — 2026-07-15 프론트 계약 채택: [specs/2026-07-15-hypenow-api-spec-alignment-design.md](docs/superpowers/specs/2026-07-15-hypenow-api-spec-alignment-design.md).
  기존 `/api/*`는 프론트 전환 완료까지 병존, fit(스펙 6.18)은 보류.
- **클로즈베타 전환 트랙**(CB1~CB3) — 2026-07-19 프론트 요청서(초대코드 가입 전용 + 도입문의 접수).
  두 트랙 전 태스크 완료(07-17). 남은 작업은 §8 미결과 프론트 REST 전환 연동(celfit-front은 아직
  Drizzle/메모리 모드 — seam만 준비됨).
- **모니터링 트랙**(MON, MON-P2) — 2026-07-28 설계 확정: [specs/2026-07-28-monitoring-module-design.md](docs/superpowers/specs/2026-07-28-monitoring-module-design.md)

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

> 정본은 [DECISIONS.md](DECISIONS.md) — 결정 이력 전체 156건(최신순)이 있다.
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
