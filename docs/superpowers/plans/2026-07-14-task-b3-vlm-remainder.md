# 태스크 B3 잔여분: VLM 개통 (F-2 스파이크 + 유통사 감지 + 어휘 확정 + 게이트 on) Implementation Plan

> 상태: 🟢 활성
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** content_analyses의 VLM 컬럼(전부 NULL 상태)을 실데이터로 개통한다 — F-2 스파이크 검증, 유통사 감지 컬럼 신설, 분류 어휘를 celfit-front 배포본과 일치시키고, 비용 가드 하에 게이트를 켠다.

**Architecture:** [스펙](../specs/2026-07-12-analytics-data-layer-design.md) §3·§6·§7. 분류값·라벨은 생산자(분석 층)가 확정하고 was는 verbatim 전달만 한다(ARCHITECTURE §4-4). was 목록 API(태스크 H, 별도 세션)가 이 어휘로 필터·카드 칩을 서빙한다.

**Tech Stack:** Spring Boot 4.1 (analytics 모듈), Anthropic SDK structured outputs, Flyway, Testcontainers(포트 fake — 실 API 금지), SQL 하니스.

---

## 정찰 결과 (계획의 전제)

1. **celfit-front 배포본 어휘** (JS 번들 `cf7a1fa707df8b5e.js`에서 추출, 2026-07-14 기준):
   - 대분류 value(slug) ↔ 중분류 라벨 ↔ 소분류 라벨:
     - `skincare`(스킨케어): 스킨/토너[스킨·토너] · 에센스/세럼/앰플[에센스·세럼·앰플] · 크림[크림·아이크림] · 로션[로션·올인원] · 미스트/오일[미스트·페이스오일]
     - `suncare`(선케어): 선크림[선크림] · 선스틱[선스틱] · 선쿠션[선쿠션] · 선스프레이/선패치[선스프레이·선패치] · 태닝/애프터선[태닝·애프터선]
     - `makeup`(메이크업): 립메이크업[립틴트·립스틱·립라이너·립케어·컬러립밤·립글로스] · 베이스메이크업[쿠션·파운데이션·블러셔·파우더·팩트·컨실러·프라이머·쉐딩·하이라이터·메이크업 픽서] · 아이메이크업[아이라이너·마스카라·아이브로우·아이섀도우·아이래쉬 케어]
     - `cleansing`(클렌징): 클렌징폼/젤[클렌징폼·클렌징젤·팩클렌저·클렌징 비누] · 오일/밤[클렌징오일·클렌징밤] · 워터/밀크[클렌징워터·클렌징밀크·클렌징크림] · 필링&스크럽[스크럽·필링·파우더워시] · 티슈/패드[클렌징티슈·클렌징패드] · 립&아이리무버[립&아이리무버]
     - `haircare`(헤어케어): 샴푸/스케일러[샴푸] · 트리트먼트/팩[린스·컨디셔너·헤어 트리트먼트·헤어팩·노워시 트리트먼트] · 두피에센스[두피토닉·두피앰플] · 헤어에센스[헤어세럼·헤어오일]
     - `fragrance`(향수/디퓨저): 향수[향수·헤어퍼퓸] · 홈프래그런스[디퓨저·캔들·인센스·룸스프레이·탈취제·차량용방향제]
   - 유통사 필터: **올리브영 · 다이소** (+ 전체/없음). 프론트는 서빙된 유통사 데이터에서 이 두 이름만 노출한다.
   - 중분류·소분류 필터는 콘텐츠의 sub_categories **배열 포함 여부**로 매칭 → 배열에 중분류 라벨과 소분류 라벨을 **모두** 넣는다 (예: `["립메이크업","립틴트"]`).
   - ad_type: `organic|sponsored` — 기존 CHECK 그대로.
2. **썸네일 URL은 수집 후 ~4일이면 만료**(403): 07-09 수집분 123건 전부 만료, 07-10(7건)·07-11(4건)은 유효(200) 실측. → VLM은 **최신 수집분 우선 + 호출 전 유효성 프리체크**가 필수. 만료 콘텐츠는 VLM 컬럼 NULL로 저장(분석 시점 고정·불변이므로 소급 불가, 손실 아님 — 썸네일 원본이 이미 소실).
3. **crawler payload 형식 변화**: 07-11 수집분부터 최상위 `displayUrl` 없음 → `_rawDetail.data.xig_polaris_media.if_not_gated_logged_out.display_uri`에 존재. base 뷰에 COALESCE 폴백 추가(§4-4: raw 접촉은 base 뷰만, 추가는 자유).
4. **게이트 on 검증 재료**: 07-10 수집 7건(DKL7qEapf1i·DJrMYkeyMXL·DCWwaflT0bN·DAK73S3PsEk·C_ExNJFMyYz·C-hT2FWsxGJ·DGZlVX6JzTA)은 기준선 뷰 안 + 댓글 미수집(분류 선행 불필요) + 썸네일 유효 → 즉시 분석 가능. 07-11 5건은 댓글 미분류라 대상 아님(classify 비용은 이 태스크 범위 밖).
5. LLM 인증: `export ANTHROPIC_AUTH_TOKEN=$(ant auth print-credentials --access-token)` (README 방식).

## 설계 결정

- **유통사 컬럼**: `content_analyses.detected_distributors jsonb` (배열은 jsonb 컨벤션). 어휘는 프론트 필터값 고정 — `올리브영`/`다이소`만 저장(그 외 상호는 프롬프트+sanitize에서 배제).
- **main_category**: DB CHECK로도 고정(`skincare|suncare|makeup|cleansing|haircare|fragrance`) — 현재 전부 NULL이라 제약 추가 안전.
- **어휘의 단일 원천**: `BeautyTaxonomy`(analytics llm 패키지) — 프롬프트 분류표 생성과 sanitize 필터가 같은 상수를 쓴다. contract-analysis에는 넣지 않는다(생산자+소비자 Java 쌍 미성립 — was는 jsonb를 verbatim 서빙).
- **sanitize 정책**(기존 level/adType 방어의 확장): main_category는 slug 집합 밖이면 null, sub_categories는 전체 중분류+소분류 라벨 집합 밖 값 제거, detected_product_categories는 전체 소분류 라벨 집합 밖 값 제거, detected_distributors는 {올리브영,다이소} 밖 값 제거. 배열은 필터 후 남는 것만 저장(빈 배열 허용).
- **분석 대상 정렬 변경**: short_code 순 → **수집 시각 최신순**(raw 기준). 썸네일 서명 URL이 살아있을 때 VLM을 시도하기 위함. `v_recent_content`·`v_analysis_baseline`에 `captured_at` 노출 추가(뷰 끝 컬럼 추가 — CREATE OR REPLACE 안전).
- **썸네일 프리체크**: VLM 호출 전 HEAD 요청으로 2xx 확인. 실패 시 VLM만 스킵(컬럼 NULL)하고 나머지 분석은 저장 — 만료 썸네일 콘텐츠가 매 실행 실패로 배치 슬롯을 잠식하는 것을 차단. 체크 함수는 `Predicate<String>`으로 주입(테스트 fake).
- **VLM API 예외는 기존대로 콘텐츠 실패**(일시 장애는 다음 실행 재대상) — 프리체크 실패(구조적·영구)와 구분.

---

## File Structure

```
analytics/views/00_base.sql                                  [수정] thumbnail_url 폴백 (신형 payload)
analytics/views/01_recent_window.sql                         [수정] captured_at 노출
analytics/views/03_analysis_baseline.sql                     [수정] captured_at 노출
analytics/test/00_base.test.sql                              [수정] 폴백 검증 시드+ASSERT
analytics/test/03_analysis_baseline.test.sql                 [수정] captured_at ASSERT
analytics/src/main/resources/db/migration/analysis/
  V11__detected_distributors.sql                             [신규] 유통사 컬럼 + main_category CHECK
analytics/src/main/java/com/celfit/analytics/llm/
  BeautyTaxonomy.java                                        [신규] 프론트 어휘 상수 (단일 원천)
  VlmResult.java                                             [수정] detectedDistributors 추가
  AnthropicVisionAnalyzer.java                               [수정] 프롬프트 어휘 교체 + sanitize 확장 + usage 로그
analytics/src/main/java/com/celfit/analytics/analyze/
  ContentAnalysisJob.java                                    [수정] 유통사 저장 + 프리체크 + 최신순 정렬
  AnalyzeRunner.java                                         [수정] HEAD 프리체크 배선
analytics/src/main/java/com/celfit/analytics/spike/
  VlmSpikeRunner.java                                        [신규] F-2 스파이크 (실 API, 수동 실행)
analytics/src/test/java/com/celfit/analytics/llm/
  BeautyTaxonomyTest.java                                    [신규]
  AnthropicVisionAnalyzerTest.java                           [수정] sanitize 확장 케이스
analytics/src/test/java/com/celfit/analytics/analyze/
  ContentAnalysisJobTest.java                                [수정] vlm on 저장·프리체크·정렬 케이스
analytics/README.md, ARCHITECTURE.md                         [수정] 상태·결정 기록
```

---

### Task 1: BeautyTaxonomy + VlmResult 확장 + sanitize (TDD)

- [ ] Step 1: BeautyTaxonomyTest — slug 집합·라벨 조회·프롬프트 분류표 생성 검증 (실패 확인)
- [ ] Step 2: BeautyTaxonomy 구현 (정찰 결과의 어휘 그대로)
- [ ] Step 3: AnthropicVisionAnalyzerTest에 sanitize 확장 케이스 추가 — 어휘 밖 main_category/sub/유통사 제거 (실패 확인)
- [ ] Step 4: VlmResult에 `List<String> detectedDistributors` 추가(adType 앞), sanitize 확장, 프롬프트를 BeautyTaxonomy 분류표로 교체, usage 로그 추가
- [ ] Step 5: `./gradlew :analytics:test` 그린
- [ ] Step 6: Commit `feat(analytics): VLM 어휘를 celfit-front 배포본과 일치 — BeautyTaxonomy 단일 원천 + 유통사 감지`

### Task 2: V11 마이그레이션 + 저장 배선 (TDD)

- [ ] Step 1: ContentAnalysisJobTest에 "vlm on이면 VLM 컬럼(유통사 포함)이 저장된다" 케이스 추가 (실패 확인)
- [ ] Step 2: V11__detected_distributors.sql + ContentAnalysisJob INSERT 배선
- [ ] Step 3: 테스트 그린 → Commit `feat(analytics): content_analyses.detected_distributors jsonb + main_category CHECK`

### Task 3: 썸네일 프리체크 + 최신 수집순 정렬

- [ ] Step 1: 00_base·01·03 뷰 수정(폴백·captured_at) + SQL 하니스 테스트 갱신 → `./test/run.sh` ALL GREEN
- [ ] Step 2: ContentAnalysisJobTest — 프리체크 실패 시 VLM 스킵·나머지 저장 / 대상이 captured_at 최신순 케이스 (실패 확인)
- [ ] Step 3: Job 구현(Predicate 주입·정렬 변경) + AnalyzeRunner HEAD 배선
- [ ] Step 4: 전체 테스트 그린 → Commit `feat(analytics): VLM 썸네일 프리체크 + 분석 대상 최신 수집순 — 서명 URL 만료 대응`

### Task 4: F-2 스파이크 (실 API — 비용 승인 범위: 5~10건)

- [ ] Step 1: VlmSpikeRunner (`--analytics.vlm-spike-limit=N` 게이트, 최신 수집·썸네일 유효 콘텐츠 N건 → 항목별 산출 출력)
- [ ] Step 2: 실행 + 썸네일 원본 대조로 항목별 품질 판정 (가능/불가 + 건당 비용 기록)
- [ ] Step 3: 판정 결과를 이 문서 하단에 기록, 불가 항목은 프롬프트/저장에서 제외(NULL 유지)
- [ ] Step 4: Commit `feat(analytics): F-2 VLM 스파이크 러너 + 판정 결과`

### Task 5: 게이트 on 검증 (실 API — 7건)

- [ ] Step 1: `app_setting analytics.analyze-batch-limit=7` 설정(검증 후 원복) — 07-10 유효 썸네일 7건만
- [ ] Step 2: `--analytics.analyze-on-startup=true --analytics.vlm-enabled=true` 실행
- [ ] Step 3: content_analyses에서 VLM 컬럼 채움 확인 (main_category·sub_categories·detected_distributors 등)
- [ ] Step 4: Commit (검증 결과를 이 문서에 기록)

### Task 6: 문서 갱신

- [ ] Step 1: analytics/README — vlm-enabled·스파이크 러너·썸네일 만료 주의
- [ ] Step 2: ARCHITECTURE §5 B3 행(VLM 개통 반영)·§7 결정 기록(컬럼명·어휘 계약·만료 정책)
- [ ] Step 3: 이 계획 ✅ 후 plans/archive/로 이동, 전체 테스트 최종 확인
- [ ] Step 4: Commit `docs: B3 VLM 잔여분 개통 반영` → develop 대상 PR

## 완료 기준 (DoD)

- `./gradlew test` 전 모듈 그린(실 API 0) + SQL 하니스 ALL GREEN
- content_analyses에 VLM 컬럼이 실데이터로 채워진 행 존재 (유통사 포함)
- 어휘가 프론트 배포본과 일치(verbatim 매칭 가능): slug 6종·한글 라벨·올리브영/다이소
- ARCHITECTURE 결정 기록에 detected_distributors 확정 기록

## 다루지 않는 것

- 기존 만료-썸네일 콘텐츠 123건의 백필(VLM NULL로 분석됨 — 비용·정책은 팀 결정)
- 07-11 5건의 댓글 분류 선행(classify 배치 비용)
- was 목록 API(태스크 H — 별도 세션)

---

## F-2 스파이크 판정 기록 (Task 4에서 기입)

(실행 후 기입)
