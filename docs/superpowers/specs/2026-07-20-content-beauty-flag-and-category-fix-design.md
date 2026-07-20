> 상태: 🟢 활성

# 콘텐츠 단위 뷰티 판별 신설 + 분석 정합(main_category NULL) 버그픽스 — 설계

작성: 2026-07-20 · 대상 브랜치: `feat/content-beauty-flag` → develop PR

## 0. 세션 분할 결정 (통합 + 자매 태스크)

이 세션은 뿌리가 같은 두 건을 **한 번에** 처리한다(사용자 07-20 B안 승인):

1. **콘텐츠 단위 뷰티 여부 판별 신설** — 통합 1콜 속성 분석에 `isBeauty`를 추가하고 `content_analyses`에 컬럼을 둔다.
2. **분석 정합 버그픽스** — 운영 `content_analyses` 811건 중 main_category NULL 342건(42%)의 두 원인을 고친다.

**자매 태스크(분석 커버리지 최신 12개 확장)는 이 작업이 develop에 머지된 뒤 별도 세션**에서 진행한다.
근거: 확장 백필을 이 세션의 새 프롬프트·스키마(isBeauty 포함)로 **한 번만** 돌리기 위해. Flyway 번호는
이 세션이 **V34**(자매 세션은 V36+, V35는 예비로 남김)를 쓴다.

## 1. 문제 — main_category NULL 342건의 두 갈래

운영 실측(analysis DB): main_category NULL 342건, ad_type NULL 0건 — 속성 분석 자체는 성공하는데 카테고리만 빈다.

- **(a) 비뷰티 콘텐츠(대부분)** — 뷰티 인플루언서의 일상글 등. LLM이 뷰티 분류표에서 고를 값이 없어
  mainCategory=null, sub_categories=`[]`. **현재 스키마에 "비뷰티라 분류 없음"을 표현할 자리가 없어
  분류 실패와 구분 불가.** 게다가 행이 생기면 후보 쿼리의 `NOT EXISTS` 때문에 영영 재분석 제외되고,
  랭킹(INNER JOIN)에는 카테고리 없는 채로 노출된다.
- **(b) sanitize 드랍(소수)** — LLM이 어휘 밖 main_category를 반환하면 `sanitize`가 복구 없이 NULL로 버린다.
  예: `DaxiOQelPMJ`는 sub_categories `["선크림","컬러립밤"]`(유효 소분류)인데 main_category NULL.

## 2. 설계 원칙 정렬

- 04 후보 뷰는 **raw만 보는 계층**이라 `is_beauty`(analysis DB·LLM 산출)를 알 수 없다 → **04 뷰 무변경**.
  뷰티 게이팅은 전부 **Java 잡 + analysis DB 컬럼**에서 처리한다. (SQL 하니스 회귀 불필요 — 뷰 미변경.)
- 분류값·라벨은 **생산자(analytics)가 확정**, 소비자(was)는 **boolean 필터만**(ARCHITECTURE §4-4).
- `content_analyses`는 **append-only·불변**(분석 시점 고정). 잘못된 행 교정은 **ops reconcile 스크립트**
  (삭제→후보 재자격→재분석, self-healing) 전례를 따른다.

## 3. 핵심 결정 (사용자 확인 07-20)

### 3-1. `is_beauty` 컬럼 신설 + 통합 프롬프트에 `isBeauty`

- 별도 LLM 콜 신설 없이 **통합 1콜 속성 분석**에 `isBeauty` boolean을 추가한다.
- 용도: (i) 랭킹/목록에서 **비뷰티 콘텐츠 제외**, (ii) **"뷰티인데 카테고리 없음 = 실패"의 기준**.
- 이는 **콘텐츠 단위** 판별이다 — 뷰티 인플루언서라도 일상글은 `isBeauty=false`가 될 수 있다.

### 3-2. sanitize 복구 = 서브카테고리 → 대분류 역유도 (결정론적·무료)

어휘 밖 mainCategory를 드랍하는 대신 **보정**한다. **재질의(추가 LLM 콜) 없음** — 1콜 설계와 정합.

- `sanitize`에서 mainCategory가 어휘 밖/null이면, sanitize를 통과한 `subCategories` + `detectedProductCategories`의
  유효 라벨을 **분류표에서 대분류(main_value)로 역유도**한다.
- 역유도 규칙(`BeautyTaxonomy.deriveMain`):
  - 라벨→대분류 매핑은 분류표(mid_label·sub_label → main_value)에서 만든다.
  - 여러 대분류에 걸치는 애매한 라벨은 **투표에서 제외**(단일 대분류로만 매핑되는 라벨만 집계).
  - 매칭 라벨들이 가리키는 대분류를 **최다 득표**로 고르고, **동점은 main_order 최소(분류표 앞선 대분류)**로
    결정론적 tie-break. 예: `["선크림","컬러립밤"]` → suncare(2) vs makeup(3) 1:1 동점 → **suncare**.
  - 매칭 라벨이 없으면 역유도 실패(null 유지).
- 복구는 **sanitize 안**에서 일어나고(어휘 있음), 실패 판정은 **잡**이 복구된 값으로 한다.

### 3-3. 실패 시맨틱 — 행 미기록 → 자동 재대상 (요구사항 명시안)

`sanitize`(역유도 포함) 후에도:

| isBeauty | mainCategory | 처리 |
|---|---|---|
| `false` | null(자연) | **행 기록** (is_beauty=false). 랭킹·recentContents에서 제외. `NOT EXISTS`로 재분석 안 함(루프 이탈). |
| `true` | 있음 | **행 기록** (is_beauty=true, 정상). |
| `true` | null(복구 실패) | **행 미기록** → 콘텐츠 단위 try/catch가 skip → 다음 실행 **자동 재대상**(self-heal). |
| null(캡션·썸네일 모두 없음) | — | 후보 뷰가 캡션 필수라 데일리 경로엔 없음(엣지). attrs=null이면 종합만 기록(기존 동작). |

- 잡의 write/skip 판정에 가드 1줄 추가: `isBeauty==TRUE && mainCategory==null` → `throw`(기존 빈-종합 가드와 동일 패턴).
- **트레이드오프(수용)**: LLM이 결정론적으로 sub 없이 `isBeauty=true`만 주는 극소수는 매 실행 슬롯 1개씩
  무한 재시도. 무료 쿼터(일 ~1,500콜)라 무해하나 낭비. 복구(3-2)가 대다수 케이스(b)를 흡수하므로 잔여는 소수.
  **잔여 집합이 커지면 시도 상한(attempt cap) 도입은 후속** — 모니터링 쿼리로 추적.

### 3-4. 서빙 반영 — 비뷰티는 API에서 아예 제외 (계약 무변경)

사용자 결정: 비뷰티는 프론트에 **아예 안 보여준다** → 플래그를 계약에 노출하지 않고 **API WHERE에서 제외**.

- **랭킹 `/v1/contents`** (`V1ContentRepository`, INNER JOIN): `AND an.is_beauty = true` 추가.
  - `= true`는 null·false를 함께 제외 → V34 백필 전 잔재(main_category 있는데 is_beauty 미백필)도 안전
    (백필이 main_category 있는 행을 is_beauty=true로 채우므로 정상 노출 복구).
- **인플루언서 상세 recentContents** (`V1InfluencerRepository`, LEFT JOIN): `AND (an.is_beauty IS DISTINCT FROM false)` 추가.
  - **미분석(is_beauty null, LEFT JOIN)은 노출 유지** — "실제 최신 12개" 목적. **확정 비뷰티(false)만 제외.**
  - LIMIT 12는 필터 후 채운다(비뷰티가 걸리면 그 다음 게시물이 채움).
- **카테고리 믹스**(`V1InfluencerReportRepository`): 이미 `an.main_category IS NOT NULL` 필터라 비뷰티 자동 제외 → **무변경**.
- **DTO·계약 무변경** — `ContentCardRow`에 isBeauty 필드 추가 안 함, 프론트 협의 불필요.

### 3-5. 기존 342건 재분석 (self-healing ops 스크립트)

`ops/reprocess_uncategorized_content_analyses.sql` 신설(전례 `ops/reconcile_content_analyses.sql`):

- 대상: **`is_beauty IS NULL AND main_category IS NULL`** — V34 백필 후 남는 pre-V34 미분류 실패분(342).
  - `is_beauty=false`(재분석으로 생긴 정상 비뷰티 행)는 **건드리지 않음** → 재실행 멱등.
- 처리: 삭제 → 후보 뷰가 재자격(여전히 후보인 것만) → 데일리 잡이 **새 프롬프트·스키마로 재분석**.
  - 재분석 결과: is_beauty=true+카테고리(복구 포함) / is_beauty=false(비뷰티) / 재대상(행 없음).
  - 더 이상 후보 아닌 것(예: 늦크롤)은 삭제만 되고 유입 없음 — 어차피 실패/비뷰티라 손실 아님.
- dry-run ROLLBACK 기본, 실제 반영은 COMMIT. 실행 위치: analysis DB. 무료 쿼터 내 소화.

## 4. 변경 좌표

### analytics (생산자)
- `db/migration/analysis/V34__content_is_beauty.sql` — `ALTER TABLE content_analyses ADD COLUMN is_beauty boolean;`
  + `UPDATE ... SET is_beauty = true WHERE main_category IS NOT NULL;`(기존 뷰티 확정분 백필).
- `llm/ContentAttributes.java` — record에 `Boolean isBeauty` 추가(mainCategory 앞 위치).
- `llm/BeautyTaxonomy.java` — `deriveMain(List<String> labels)` + 라벨→대분류 맵.
- `llm/AnthropicContentAttributeAnalyzer.java` — `instructions()`에 isBeauty 문항 추가, `sanitize`에 역유도·isBeauty 통과.
- `llm/GeminiContentAnalyzer.java` — `RESPONSE_SCHEMA`(properties·required·propertyOrdering에 isBeauty, mainCategory 앞),
  `instructions()` 문항 추가, `Output` record·`parse()`에 isBeauty.
- `analyze/ContentAnalysisWriter.java` — INSERT에 `is_beauty` 컬럼·바인딩 추가.
- `analyze/ContentAnalysisJob.java` — `analyzeOne`에 실패 가드(`isBeauty==TRUE && mainCategory==null` → throw), Writer 호출에 is_beauty 전달.
- `ops/reprocess_uncategorized_content_analyses.sql` — 신설.

### was (소비자)
- `v1/content/V1ContentRepository.java` — 랭킹 WHERE에 `AND an.is_beauty = true`.
- `v1/influencer/V1InfluencerRepository.java` — recentContents WHERE에 `AND (an.is_beauty IS DISTINCT FROM false)`.

### 테스트 (실 API 호출 금지 — 포트 fake)
- `GeminiContentAnalyzerTest` / `AnthropicContentAttributeAnalyzerTest` — sanitize 역유도(어휘밖 main+유효 sub→복구, 동점 tie-break, 매칭 없음→null), isBeauty 통과.
- `BeautyTaxonomyTest` — `deriveMain` 단위(단일/최다/동점/애매 라벨 제외).
- `ContentAnalysisJobTest` — 실패 가드(뷰티+미분류→skip/재대상), 비뷰티→is_beauty=false 기록, 정상 기록.
- `V1ContentRepositoryTest` / `V1InfluencerRepositoryTest` — is_beauty 필터(비뷰티 제외, 미분석 recentContents 유지).
- **SQL 하니스: 04 뷰 미변경이라 회귀 추가 없음.**

## 5. 마이그레이션·문서

- Flyway analysis: **V34**(이 세션). V35 예비, V36+ 자매 세션.
- 완료 시 ARCHITECTURE.md §5(트랙)·§7(결정 기록) 갱신 + 이 spec 링크.
- 운영 반영(뷰 무변경이므로 was/analytics 배포 + V34 마이그레이션 + ops 재분석)은 **사용자 승인 후** 런북 따라.

## 6. 비목표 (YAGNI)
- 04 후보 뷰 변경 — 불필요(raw 계층).
- 재질의(추가 LLM 콜) 복구 — 역유도로 충분.
- 시도 상한(attempt cap) — 잔여 루프 집합 관측 후 후속.
- 커버리지 최신 12개 확장 — 자매 세션.
- 계정 요약·카테고리 믹스 뷰 변경 — 이미 main_category NULL 자동 제외.
