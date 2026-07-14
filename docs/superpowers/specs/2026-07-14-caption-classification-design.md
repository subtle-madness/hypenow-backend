# 캡션 분류 + B3 숙성 가드 설계 (2026-07-14)

> 상태: 🟢 활성

## 1. 배경과 목표

크롤링 개편(§7 07-14)으로 게시물 분류가 discovery 키워드에서 **caption 감지**로 이관된다.
캡션 LLM 산출은 5종 확정: **광고 구분·카테고리·브랜드·제품·유통사**.

현재 상태의 문제:

- VLM 콜([AnthropicVisionAnalyzer])이 이미 캡션+썸네일로 5종에 해당하는 산출을 내고 있으나
  **썸네일이 필수 입력** — 인스타 CDN 서명 URL이 수집 후 ~4일 만료라, 썸네일이 죽으면
  5종 전부 NULL로 영구 고정된다(content_analyses 불변). 캡션은 raw에 영구 보존되므로
  **캡션이 주 경로**가 되어야 한다.
- 분류 어휘(`BeautyTaxonomy`)가 Java 하드코딩 — 항목 목록은 수정 용이해야 한다.
- 매일 크롤 구조에서 게시 직후 분석되면 지표·댓글이 덜 여문 상태로 영구 고정된다(B3 숙성 가드).

이 산출물이 크롤 개편 후 `content.main_group` 결측 대응과 인플루언서 패널 `ads.brands` 칩 재료다.

## 2. 잡 구조 — 기존 VLM 콜을 "콘텐츠 속성 분석 콜"로 전환

별도 캡션 잡·포트를 신설하지 않는다. 기존 VLM 콜을 전환한다:

- **캡션은 항상 입력, 썸네일은 살아있을 때만 첨부** (기존 HEAD 프리체크 재사용).
  썸네일 만료 시에도 캡션만으로 5종을 산출한다 — "VLM만 NULL" 경로가 "이미지 없이 분석"으로 바뀐다.
- 병합 규칙(캡션 주·VLM 보조)은 **모델 안에서 자연히 일어나므로 Java 병합 로직 불요.**
  한 콜이 한 세트의 산출을 내고, 컬럼도 기존 것을 전량 재사용한다.
- 콘텐츠당 LLM 호출은 2회 유지(종합 텍스트 + 속성 분석). 속성 콜은 분석의 **기본 경로**가 된다
  (기존 기본 게이트 vlm off 대비 콘텐츠당 +1콜 — `analytics.analyze-batch-limit` 가드는 그대로).
- `analytics.vlm-enabled` 프로퍼티는 **썸네일 첨부 여부 게이트**로 의미가 바뀐다(기본 false 유지 —
  false여도 캡션 기반 5종은 산출된다). 프로퍼티명은 배포 설정 호환을 위해 유지.
- 이름 정리: `VisionPort`→`ContentAttributePort`, `AnthropicVisionAnalyzer`→`AnthropicContentAttributeAnalyzer`,
  `VlmResult`→`ContentAttributes` (DB 컬럼명 `vlm_attributes` 등은 서빙 계약이라 불변).
- 기존 content_analyses 행은 불변 유지 — 새 구조는 신규 분석분부터 적용, 재분석 없음.

## 3. 산출 5종 ↔ 컬럼 매핑

| 산출 | 컬럼 | 비고 |
|---|---|---|
| 광고 구분 | `ad_type` + `sponsored_signal_level/reasons`, `ad_disclosure` | 재사용 |
| 카테고리 | `main_category`, `sub_categories` | 재사용. CHECK 제약은 삭제(§5) |
| 브랜드 | `detected_brands` jsonb [{name, evidence}] | 재사용 — E 패널 ads.brands 칩 재료 |
| 제품 | `detected_product_categories`(소분류 라벨) **+ `detected_products` jsonb 신설** [{name, brand}] | 제품명은 자유 텍스트(어휘 없음) — sanitize 대상 아님, brand는 미상 시 null |
| 유통사 | `detected_distributors` jsonb | 재사용 — 어휘(올리브영/다이소) sanitize 유지 |

## 4. 어휘 DB화 — analysis DB 전용 테이블 + V30 시드

- `beauty_taxonomy`(main_value, main_label, mid_label, sub_label, 정렬 컬럼 — 소분류당 1행)와
  `beauty_distributors`(name, 정렬) 테이블을 V30 마이그레이션으로 생성하고 **현재 어휘를 verbatim 시드**.
- `BeautyTaxonomy`는 정적 상수 → **DB에서 조립되는 불변 스냅샷 인스턴스**로,
  로더(`BeautyTaxonomyLoader`, analysis DB 읽기·부팅당 1회 메모이즈)가 만든다.
  프롬프트 분류표(`promptTable()`)와 sanitize 어휘 집합이 **같은 인스턴스**에서 나오는 구조 유지 —
  어휘는 celfit-front 배포본과 verbatim 계약이므로 원천 일치가 필수.
- 어휘 수정 = 테이블 행 수정(마이그레이션으로) — 코드 변경 불요. 단 프론트 필터 어휘와 함께 갱신할 것.
- `BeautyTaxonomyTest`는 시드가 프론트 배포본 어휘와 일치하는지 검증하는 계약 테스트로 전환
  (Testcontainers + Flyway 적용 후 로더로 검증).

## 5. 스키마 변경 (V30 — §4-5 번호대 예약, 착수 시 flyway_schema_history 확인: V0~4·V10·V11·V20 사용 중)

`V30__caption_classification.sql` 하나로:

1. `beauty_taxonomy`·`beauty_distributors` 생성 + 시드
2. `ALTER TABLE content_analyses ADD COLUMN detected_products jsonb`
3. `ALTER TABLE content_analyses DROP CONSTRAINT content_analyses_main_category_check` —
   어휘가 데이터가 되므로 DB CHECK는 수정 용이 목적과 상충. 어휘 방어는 Java sanitize가 담당
   (이제 DB 어휘와 같은 원천, 쓰는 쪽은 analytics뿐).

## 6. B3 숙성 가드

ContentAnalysisJob eligible 쿼리(analysis DB)에 추가:

```sql
AND c.posted_at <= now() - make_interval(days => ?)
```

- 일수는 app_setting `analytics.analyze-maturity-days` **기본 3** (07-14 확정 — 게시 후 3일 경과).
  기존 AnalyticsSettings 패턴 그대로.
- `posted_at` NULL은 제외(분석 안 함) — 실데이터 140건 중 NULL 0건.
- 재분석은 없다 — 게시 직후 분석·영구 고정을 막는 것이 목적.

## 7. 검증

- LLM 호출은 테스트에서 실 API 금지 — 기존 포트 fake 패턴.
  ContentAnalysisJobTest: 시드에 posted_at 보강 + 숙성 미달 제외 케이스,
  썸네일 만료 시 캡션만으로 속성 산출(이미지 미첨부) 케이스, detected_products 저장 케이스.
- 실 실행 검증은 게이트 옵션(analyze-on-startup)으로 소량만(비용 주의).
- `./gradlew test` 그린 후 develop 대상 PR.

## 8. 문서 갱신

ARCHITECTURE.md: §5 신규 태스크 행 추가, §7 결정 1줄, §8에서 "B3 숙성 가드"·"캡션 분류 태스크" 두 행 제거.
표는 병렬 PR과 충돌 가능 — 자기 행만 최소 수정.

## 9. 후속과의 경계

윈도우 24개 전환(recent12_* 네이밍) 세션이 이 작업 머지 후 같은 파일(ContentAnalysisJob·content_analyses)을
만질 예정 — 이 PR을 먼저 완결한다.
