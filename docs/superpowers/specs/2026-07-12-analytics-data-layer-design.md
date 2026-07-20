# analytics 데이터 층 재구축 — 설계

> 상태: 🟢 활성 — 태스크 A·B1·F·B2·B3의 설계 기준
2026-07-12 (설계 세션 기록)

- 구조 기준: [ARCHITECTURE.md](../../../ARCHITECTURE.md) §4 (타입 기반 미러·contract-analysis·로직의 자리)
- 스키마 참고: 팀 노션 "콘텐츠 상세분석 모달 DB 프론트 가상 스키마" (프론트 필드 인벤토리 + 컬럼 제안).
  **구조·태스크 경계는 ARCHITECTURE.md를 따르고, 노션 문서는 목표 데이터 형태로만 참조한다.**

> 배경: 기존 analytics 구현(뷰 00~08·SQL 하니스·제네릭 MaterializationService)은 잘못된 작업
> 지시로 생긴 산출물이라 2026-07-12 전체 삭제했다(git 이력에 보존). 이 문서는 백지에서
> 재구축하는 데이터 층의 설계다.

## 1. 범위

ARCHITECTURE.md §5 트랙 중 **데이터 층 태스크만**: A(분석 기반) → B1(서빙 뷰·미러) + F(스파이크)
→ B2(댓글 LLM) → B3(콘텐츠 분석·스냅샷). **D·E(API), C1·C2(인플루언서), G(서비스 데이터)는 범위 밖.**

완료 시점의 상태: 프론트 게시물 상세 모달이 요구하는 모든 데이터가 analysis DB에 준비되어 있다
(서빙은 후속 태스크 D가 담당).

산출 모듈:
- **analytics 재구축** — 뷰 SQL + 미러 잡 + 분석(LLM/VLM) 잡
- **contract-analysis 신설** — 분석 결과 record·enum (순수 JDK)

## 2. 데이터 배치 — analysis DB에 쓰는 두 경로

| | 미러 잡 | 분석(analyze) 잡 |
|---|---|---|
| 대상 테이블 | `accounts`, `contents`, `content_comments`, `content_metric_snapshots` | `content_analyses`, `comment_classifications` |
| 내용 | raw 원지표의 서빙용 사본 + 행 단위 계산(hype_score) | LLM/VLM 산출물 + **그 분석이 참조한 기준선 수치** |
| 성격 | 항상 최신 → 매 실행 TRUNCATE+INSERT 전체 교체 | 분석 시점 고정 → 한 번 쓰고 불변 |
| 재실행 이유 | 새 게시물 유입 + 재수집으로 갱신·누적된 지표 반영 (중복 크롤링 도입 — 2026-07-12) | 재실행 없음. 미분석 콘텐츠만 추가 분석 |
| 방식 | §4-3 타입 미러 (뷰 SQL / Flyway DDL / 공유 record, 컬럼↔필드 대조 가드) | 분석 층 소유 테이블에 Java가 직접 쓰기 (JPA 허용) |

경계 기준은 "LLM 여부"가 아니라 **"항상 최신으로 덮어쓸 것 vs 분석 시점에 고정할 것"**이다.
기준선 스냅샷(최근 12개 평균 ER 등)은 비LLM SQL 집계지만, AI 요약이 참조한 수치와 저장된 수치가
같은 시점이어야 하므로 미러가 아니라 `content_analyses`에 고정 저장한다.

## 3. 테이블 설계 (analysis DB)

컬럼 상세는 노션 문서의 제안을 따른다. 여기서는 소유·키·노션과 달라진 점만 고정한다.

### 미러 테이블 (Flyway 이력: analytics 소유)

- `accounts` — handle, display_name, profile_image_url, followers
- `contents` — account 참조, thumbnail_url, caption, posted_at, content_type(reels|feed),
  video_duration, original_url, views, likes, comments, **hype_score**(뷰에서 계산:
  릴스=조회수, 피드=좋아요+댓글). rank 컬럼 없음 — 순위는 필터 결과의 정렬 인덱스로 파생.
- `content_comments` — content 참조, author_masked, body, like_count.
  작성자 답글은 수집하지 않음(팀 확정) — 관련 컬럼 없음.
- `content_metric_snapshots` — 게시물 × 수집 시점 1행: content 참조, captured_at,
  views, likes, comments, hype_score. **중복 크롤링(2026-07-12 도입)이 쌓는 지표 이력의 서빙 사본** —
  집계 기간의 as-of 조회("9일 기준 화면엔 9일 수치")와 향후 추이 그래프의 재료.
  `contents`는 이 중 **최신 스냅샷 1개**를 편 것(랭킹 기본 경로), 이력 조회만 이 테이블을 쓴다.

**id는 raw의 자연키를 그대로 쓴다** (콘텐츠=short_code, 계정=username, 댓글·지표 스냅샷=raw id).
미러가 전체 교체돼도 id가 안정적이어야 분석 층 테이블의 참조가 살아남는다.

### 분석 층 소유 테이블 (Flyway 이력: analytics 소유, Java 직접 쓰기)

- `content_analyses` (콘텐츠 1:1, analyzed_at 포함)
  - LLM 텍스트: ai_content_summary(요약), contents_pattern(계정 패턴 해석), ai_comment_insight(댓글 인사이트)
  - 기준선 스냅샷: recent_reels_avg_views, rank_in_recent_reels, recent_contents_count,
    recent12_avg_engagement_rate, recent12_avg_like_count, recent12_avg_comment_count
  - 카테고리 맥락 스냅샷: category_top_percentile, category_avg_views, category_sample_size
  - VLM 산출물(전부 NULL 허용 — F-2 스파이크 결과 반영 전까지 채우지 않을 수 있음):
    detected_brands jsonb, sponsored_signal_level, sponsored_signal_reasons, ad_disclosure,
    detected_product_categories, vlm_attributes jsonb, main_category, sub_categories, ad_type
  - 댓글 종합 판정: comment_authenticity_grade(high|normal|suspect), comment_authenticity_note
- `comment_classifications` (댓글 1:1) — ai_category(purchase|question|positive|adAware|friendTag|etc)

### 노션 스키마와 다르게 가는 결정

1. **AI 컬럼을 미러 테이블에 두지 않는다.** 노션은 contents.main_category·ad_type,
   content_comments.ai_category를 같은 테이블에 제안했으나, 미러는 TRUNCATE+INSERT라 재실행마다
   AI 산출 값이 소실된다. AI 산출물은 전부 분석 층 소유 테이블로 분리하고, 화면용 조합은 was 코드에서
   한다(§4-4 조합 규율의 연장).
2. **브랜드/제품/유통사는 content_analyses의 jsonb로 시작한다.** 노션 좌측 패널의
   content_brands 조인 테이블은 브랜드 정규화 문제가 함께 오므로 MVP에서 도입하지 않는다.
   "브랜드별 콘텐츠 역검색" 요구가 생기면 그때 추가.
3. **미러 테이블 ↔ 분석 층 테이블 사이 FK 제약을 걸지 않는다** (TRUNCATE와 충돌). 자연키 기반
   논리 참조만 유지한다.

## 4. 뷰 구성 (raw DB `analytics` 스키마)

역할별 3층. 파일은 `analytics/views/NN_*.sql` 번호순 적용 컨벤션 유지.

1. **base 뷰 — payload 해부 전담.** raw 테이블·jsonb를 만지는 유일한 곳(§4-4 격리 원칙).
   계정별 최신 프로필 / 콘텐츠별 최신 상세(조회수 = videoPlayCount, 폴백 videoViewCount) / 댓글 평탄화.
2. **서빙 형태 뷰 — 미러 대상과 1:1.** `v_accounts`, `v_contents`, `v_content_comments`,
   `v_content_metric_snapshots`(전 스냅샷 평탄화 — base 뷰에 이력용 노출 추가 필요, §4-5 추가는 자유).
   노션 컬럼 형태로 정리 + hype_score 등 행 단위 계산. 미러는 이 뷰를 SELECT해 같은 이름 테이블에 붓는다.
   `v_contents`는 최신 스냅샷 기준(base 뷰의 DISTINCT ON) — 랭킹 기본 경로는 이력을 모른다.
3. **분석용 집계 뷰 — 미러 안 함, 분석 잡이 읽음.** 계정별 최근 N개 윈도우(N은 `app_setting`
   `analytics.recent-window`, 기본 12), 기준선 집계(계정별 최근 릴스 평균 조회수·최근 N개 평균
   ER/좋아요/댓글), 카테고리 맥락(카테고리 내 조회수 백분위·평균·모수).

## 5. 미러 잡

ARCHITECTURE.md §4-3 그대로: 뷰 SELECT → 공유 record 매핑 → TRUNCATE+INSERT 한 트랜잭션 →
시작 시 뷰 컬럼↔record 필드 대조 가드(불일치 즉시 실패). 테이블당 record 하나가
contract-analysis에 산다.

갱신 주기는 미결(§8) — crawler 수집 주기에 맞추면 충분하다. 지금은 수동 실행.

## 6. 분석 잡 (Java, analytics 모듈)

대상 = `content_analyses`에 행이 없는 콘텐츠. 콘텐츠 1건당:

1. **댓글 분류 (LLM)** — 수집된 댓글(최대 50개)을 6분류 → `comment_classifications` 저장
2. **기준선 스냅샷** — 분석용 집계 뷰를 해당 계정·카테고리로 SELECT
3. **콘텐츠 분석 (VLM)** — 썸네일/영상 기반 브랜드 감지·광고성 판정·속성 추출 (F-2 검증 전 스킵 가능)
4. **종합 텍스트 (LLM)** — 1~3 결과를 입력으로 AI 요약·계정 패턴·댓글 인사이트 생성
5. `content_analyses` 한 행으로 저장 (analyzed_at 기록)

- **멱등**: 부분 실패 시 행을 만들지 않는다 → 다음 실행에서 자동 재대상. `comment_classifications`는
  콘텐츠 단위로 삭제 후 재삽입해 중복을 막는다.
- **비용 가드**: 1회 실행당 분석 콘텐츠 수 상한을 `app_setting`으로. 모델명도 설정으로(스파이크 결과 반영).

## 7. 스파이크 (태스크 F)

- **F-1. 댓글 분류 정확도/비용** — 실 댓글 100~200개 수동 라벨 골드셋 → 6분류 프롬프트를
  opus vs haiku 비교. 비용 실측은 있음(1,000게시물당 opus ≈ $61 / haiku ≈ $12.2) → 관건은 정확도 차이.
- **F-2. VLM 콘텐츠 분석** — 미검증 영역. 입력 방식(썸네일 1장 vs 영상 프레임 vs 영상 전체,
  raw payload 미디어 URL의 유효성 포함)과 항목별 출력 품질(브랜드 감지·광고성·속성·무드)을
  콘텐츠 5~10건으로 실험 → 항목별 가능/불가 판정 + 건당 비용. 불가 판정 항목은 컬럼만 남기고(NULL) 보류.

스파이크 결과에 따라 B3 범위가 줄 수 있다. LLM 코드의 모듈 소속(analytics 제안)도 F에서 확정.

## 8. 검증

| 대상 | 방법 |
|---|---|
| 뷰 SQL 3층 | SQL 하니스 재구축 — 더미 시드를 raw에 INSERT → 뷰 결과 ASSERT → ROLLBACK, 기대값 수동 산출 고정 |
| 미러 잡 | Testcontainers — 전체 교체 동작 + 컬럼↔record 대조 가드의 불일치 검출 확인 |
| 분석 잡 | LLM/VLM 포트 fake(실 API 호출 금지) — 파싱·저장·멱등·비용 가드 검증 |
| 프롬프트 품질 | 코드 테스트가 아니라 F 스파이크 골드셋으로 |

## 9. 실행 순서

```
A (base 뷰·윈도우 뷰·설정 키 + contract-analysis 골격 + 미러 잡 + SQL 하니스)
│
├─→ B1 (서빙 형태 뷰 3종 + 미러 테이블·record — 여기까지로 랭킹 실데이터 서빙 가능)
│
└─→ F (F-1 댓글 골드셋 · F-2 VLM — A와 병렬 가능)
      └─→ B2 (댓글 분류 배치 + comment_classifications)
            └─→ B3 (기준선 스냅샷 + VLM + 종합 텍스트 → content_analyses)
```

B3가 마지막인 이유: 종합 텍스트의 프롬프트 입력이 B2 결과와 기준선 집계를 필요로 한다.
상세 구현 계획은 태스크 착수 시 작성(A부터).

## 10. 미결

| 항목 | 상태 |
|---|---|
| 미러 갱신 주기 | crawler 수집 주기에 연동하는 방안 유력, 자동화 시점 미정 |
| LLM/VLM 모델 | F 스파이크 결과로 결정 |
| VLM 항목별 채택 여부 | F-2 결과로 결정 (불가 항목은 컬럼 NULL 보류) |
| as-of 선택 규칙 | 집계 기간에 대응하는 스냅샷 선택(기간 끝 시점 값 vs 기간 내 최신 등)은 D(API) 설계에서 확정 — 데이터 층은 전 스냅샷 보존만 책임 |
| 추이 그래프 UI | 데이터(`content_metric_snapshots`)는 준비되나 확정안은 추이 제외 — UI 도입 여부는 기획 결정 |
| ARCHITECTURE.md 갱신 | §5 태스크 표(B1~B3 내용)·§9 문서 맵(analytics/README.md 링크)을 이 설계에 맞춰 갱신 필요 |

## 11. 기존 문서와의 관계

- [2026-07-12-detail-analysis-design.md](2026-07-12-detail-analysis-design.md) — 층 구조·태스크
  경계의 기준(§8 후속 논의 포함). 본 문서는 그 안에서 데이터 층의 상세를 확정한다.
- `plans/2026-07-10-*` 3건 — 이전 설계의 초안. 뷰 SQL·테스트 기대값·스파이크 설계·비용 산정은
  참고 가치가 있으나, 스키마 형태는 본 문서(노션 참조)가 우선한다.
