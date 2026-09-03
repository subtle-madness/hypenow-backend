# beauty_taxonomy 에스테틱 대분류 추가 설계

> 상태: ✅ 구현됨(2026-08-09 — 시드 V20260809063533·was allowlist·ops 동봉) · 소급 실행·프론트 동기화는 후속

## 1. 배경 — 게시물 단위 분류의 사각지대

`beauty_taxonomy`(V30 시드)는 제품 유형 축의 대분류 6종(스킨케어·선케어·메이크업·클렌징·헤어케어·
향수/디퓨저, 소분류 72개)만 담는다. 뷰티 디바이스·괄사·시술 후기 콘텐츠는 이 어휘 밖이라:

- 분석 프롬프트의 `isBeauty` 기준("뷰티 제품·**시술**·루틴·리뷰")에는 걸려 `is_beauty=true`가 되지만,
- sanitize(`keepIfIn` + `deriveMain` 역유도)가 어휘 밖 값을 걸러 **`main_category=null`로 남는다.**
- 결과: 프론트 카테고리 필터 어디에도 안 잡히는 사각지대(전체 목록에만 노출).

계정 단위로는 trait 어휘(V41 '뷰티 주제' 축)에 '뷰티 디바이스'·'시술 후기'가 이미 있어
`account_analyses.traits`로 표현되지만, **게시물 단위(`content_analyses.main_category`)에는 자리가 없다** —
이 공백을 채운다.

## 2. 스코프 결정 (사용자 확정)

- **포함**: 홈케어 디바이스 · 수동 툴 · **피부 중심** 시술·관리(에스테틱샵 관리, 경락, 피부과 시술).
- **제외**: 왁싱·반영구·속눈썹, 네일·헤어 시술 — "피부"라는 일관 축을 유지해 분류 안정성 확보.
- **제외**: 에스테틱 전문 화장품(모델링팩 등) — 제품 유형으로는 스킨케어와 겹쳐 경계가 흐림.
  기존 스킨케어 어휘가 그대로 받는다.
- 시술은 서비스지만 대분류에 포함한다(사용자 결정) — 프론트 필터 관점에서 "에스테틱" 한 축이
  필요하고, 시술 후기 게시물이 null로 남는 것보다 잡히는 게 낫다.

## 3. 어휘 (확정 라인업 — 14개 소분류)

대분류 `esthetic`(라벨 '에스테틱'), `main_order=7` (향수/디퓨저 뒤).

| 중분류 (mid_order) | 소분류 (sub_order 순) |
|---|---|
| 뷰티 디바이스 (1) | LED 마스크 · 미세전류 기기 · 고주파 기기 · 클렌징 기기 · 제모 기기 |
| 뷰티 툴 (2) | 괄사 · 페이스 롤러 · 마사지 도구 |
| 피부 시술·관리 (3) | 에스테틱 관리 · 경락 마사지 · 피부과 레이저 · 스킨부스터 · 리프팅 시술 · 필링 시술 |

라벨 충돌 전수 확인(기존 72개 대비): 없음. 클렌징 대분류의 기존 소분류 '필링'과 겹치지 않도록
시술 쪽은 '필링 시술'로 명명 — 프론트 라벨 매칭·`deriveMain` 역유도 모두 정확 일치 기반이라
한 글자 차이로 분리된다. 중분류 '뷰티 디바이스'는 trait 어휘의 동명 값과 테이블이 달라 무관.

## 4. 변경 내용

### 4-1. 마이그레이션 (필수 변경 ① — ②는 §4-2 정정의 was allowlist)

analytics `db/migration/analysis`에 UTC 타임스탬프 채번으로 1개 신규:
`V<YYYYMMDDHHMMSS>__esthetic_taxonomy.sql` — §3 라인업 14행을 `beauty_taxonomy`에 INSERT.
순수 additive라 expand-contract 가드 통과, 롤링 배포 안전.

### 4-2. 자동 반영 (코드 무접촉 — 정정: was allowlist 예외)

어휘 단일 원천(ARCHITECTURE §4-4 — 프롬프트 분류표와 sanitize가 같은 `BeautyTaxonomy`
인스턴스) 덕에, 마이그레이션 + analytics 재시작만으로 전부 따라온다:

- LLM 프롬프트 분류표(`promptTable()`) · sanitize 어휘 · `deriveMain` 역유도
- `account_category_stats` 뷰(V35) — `main_label` 조인이라 카테고리 믹스에 '에스테틱' 자동 등장
- was 응답 경로는 verbatim 전달이라 무변경. 프롬프트 문구도 무변경(`isBeauty`에 시술 이미 포함).
  **정정(구현 중 발견)**: was 요청 검증 allowlist 2곳(`V1ContentQuery`·`V1InfluencerDiscoveryQuery`의
  `MAIN_CATEGORIES` 하드코딩)은 esthetic 추가 필요 — 누락 시 `?mainCategory=esthetic`이 400이라
  프론트 필터가 동작하지 않는다.

시술 콘텐츠 유입 경로 확인: BEAUTY_SERVICE 계정(피부과·에스테틱샵)은 뷰티 판정 v2(07-20)대로
계속 수집 제외 — 이 카테고리는 **인플루언서가 올리는 디바이스 리뷰·시술 후기**가 받는다.

### 4-3. 프론트 동기화 (celfit-front, 별도 저장소)

필터 어휘 1:1 계약 — '에스테틱' + 중·소분류 라벨을 표기 그대로(한 글자도 다르지 않게) 추가.
순서는 백엔드 선행이 안전: 프론트 갱신 전까지 에스테틱 콘텐츠는 카테고리 필터에 안 잡힐 뿐
(전체 목록 노출) 깨지지 않는다.

## 5. 기존 분석분 소급 — 동봉만, 실행 보류 (사용자 확정)

`content_analyses`는 INSERT-only + 분석 잡의 NOT EXISTS 제외라 **이미 분석된 디바이스·시술
게시물(`is_beauty=true AND main_category IS NULL`)은 자동 소급되지 않는다.** 신규 분석분부터
적용하고, 소급은 선례(`analytics/ops/reprocess_uncategorized_content_analyses.sql`)를 따라:

- ops SQL 동봉(dry-run ROLLBACK 형태): 대상 행 삭제 → 재자격 → 데일리 잡이 새 분류표로 재분석.
  재분석 결과는 멱등적(에스테틱이면 채워지고 아니면 다시 null).
- **⚠️ 선례와 결정적 차이 — 삭제 대상이 정상 분석 행이다.** 선례는 실패 행(is_beauty NULL)만 지워
  손실이 없었지만, 이번 대상은 브랜드·광고 신호 등이 채워진 유효 행이라 **삭제 후 후보 뷰
  (`v_analysis_candidates`, 제때창)에 되살아나지 못하면 기존 분석을 통째로 잃는다.** 따라서 ops
  SQL은 "삭제 시 반드시 재분석 후보로 복귀하는 행"으로 대상을 한정해야 한다(후보 뷰 조건과의
  교집합 — 구체 조건은 구현 계획에서 후보 뷰 정의를 읽고 확정).
- 실행은 운영 DB에서 대상 건수 확인 후 별도 결정 — 건수만큼 LLM 콜 비용 발생.

## 6. 검증

- `./gradlew :analytics:test` — FlywaySchemaTest가 새 마이그레이션 적용 검증, taxonomy 로더·
  sanitize 테스트 회귀 확인. SQL 하니스(raw 뷰 대상)는 무관.
- 배포 후 표본 확인: 어드민에서 분석 잡 트리거 → 에스테틱 콘텐츠가 `main_category='esthetic'`,
  `sub_categories`에 새 라벨로 저장되는지 확인.
