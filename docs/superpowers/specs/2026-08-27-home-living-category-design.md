# 홈/리빙 카테고리 추가 설계 — 판정 3축화(뷰티+F&B+홈/리빙)

> 상태: 🟢 활성 · ✅ 구현됨 (2026-08-27)

## 0. 배경·목적

뷰티·F&B 2축 판정(스펙 2026-08-23) 체제에 홈/리빙 축을 추가한다. F&B 축 병렬 복제
패턴을 세 번째 축으로 반복한다 — 구조·가드·어드민 배선 전부 대칭.

- **홈/리빙 타깃**: 리빙 제품 시딩·협찬 대상 한국어 개인 크리에이터. 경계는
  **제품 리뷰·공구형 + 집꾸미기·홈스타일링형**(2026-08-27 사용자 확정 — featuring 수집
  CSV 실계정 예시로 검토). 순수 일상·가족형(집은 배경)은 NONE.
- **복수 카테고리 허용**: 세 축은 독립 판정 — 한 계정이 여러 축에 해당할 수 있다.
- **서빙 무변경**: 랭킹·상세·was API의 서빙 모수는 뷰티 유지(기존 방침).
- **기존 판정분 전체 홈/리빙 백필**: F&B 백필과 동일 — 판정완료 백로그(~4만)에서
  홈/리빙 모수를 발굴한다. 기존 뷰티·F&B 판정은 보존한다.
- **수집·시드 편입은 토글, 기본 off**: Hiker 과금이 걸리므로 모수·비용 확인 후 켠다.

**범용 `influencer_category` 테이블 이관은 이번에도 하지 않는다** — F&B 스펙이 "3개
이상이면 그때 이관"이라 적었지만, 이관은 뷰티·F&B 축 전체(스키마+코드+대시보드) 리팩토링이라
축 1개 추가 비용을 한참 넘는다. 4번째 카테고리나 카테고리 서빙 개편 트랙에서 함께 이관한다.

## 1. 데이터 모델 — `influencer`에 홈/리빙 축 병렬 컬럼

crawler Flyway 마이그레이션(UTC 채번) 1건, 컬럼 추가만(expand — 파괴 없음).
F&B 마이그레이션(V20260824082708)과 대칭:

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `home_living_class` | text | 공용 5분류(CategoryClass — enum 재사용, 신규 enum 없음) |
| `home_living` / `home_living_company` | boolean | 파생 boolean (fnb/fnb_company와 대칭) |
| `home_living_judged_at` | timestamptz | 홈/리빙 축 판정 시각 |
| `home_living_source` / `home_living_reason` / `home_living_basis` | text | 판정 출처(CLAUDE/MANUAL)·근거·주근거 |
| `home_living_caption_count` | smallint | 판정에 쓴 캡션 수 (정착 규칙 재료) |

- CHECK 제약은 F&B 관용구 그대로: class 5분류, basis `CAPTION/BIO/CATEGORY_ONLY`.
- 같은 마이그레이션에서 `app_setting` 키 `home-living.pipeline-enabled` = `false` 시드
  (`ON CONFLICT DO NOTHING`, V16 패턴).
- Java: `Influencer`에 `homeLiving*` 필드 + `classifyHomeLiving()` (classifyFnb와 대칭).

## 2. 판정 — 프롬프트 1콜에 3축 동시 판정

- `BeautyJudge.Verdict`에 `homeLivingClass`/`homeLivingReason`/`homeLivingBasis` 추가.
  포트·잡 이름(`BeautyJob`, `JobName.BEAUTY`)은 계속 유지.
- 프롬프트(`ClaudeCliBeautyJudge.buildPrompt` — claude-api·claude-cli·gemini 3개 어댑터가
  공유하는 단일 원천)에 `[home_living 축 분류]` 문단 추가. 출력은 계정당
  `{username, beauty:{...}, fnb:{...}, home_living:{...}}`.
- **홈/리빙 판정 기준(프롬프트)**:
  - INFLUENCER: 캡션·bio를 한국어로 쓰는 홈/리빙 개인 크리에이터 — 리빙 제품(가구·
    인테리어 소품·주방/생활용품·홈데코) 리뷰·공구·추천 계정 **그리고** 집꾸미기·홈스타일링·
    살림·정리수납·홈카페 콘텐츠 중심 계정(제품 리뷰가 주업이 아니어도 집·공간·살림이
    콘텐츠의 중심이면 포함 — 오늘의집류 집기록 계정이 시딩 핵심 수요층).
  - 경계(NONE 쪽): 집이 배경으로만 등장하는 일상·가족·육아 계정은 홈/리빙이 아니다 —
    콘텐츠의 주제가 집·공간·살림·리빙 제품인지로 판정.
  - COMPANY: 가구·리빙 제품을 제조·판매하는 회사(브랜드·쇼핑몰) 공식 계정 — 언어 무관.
  - SERVICE: 인테리어 시공·리모델링·이사·입주청소·정리수납 대행·부동산 등 서비스 업체
    공식 계정, 그리고 시공 사례·견적 홍보 위주의 서비스 중심 개인.
  - FOREIGN_INFLUENCER: 홈/리빙 개인 크리에이터지만 한국어 콘텐츠가 아님.
  - 언어 규칙·basis 규칙·category 자기신고 주의는 기존 공유 문단 그대로(추가 없음).
- 출력이 계정당 ~1.5배 → `MAX_TOKENS` 16384→24576 상향(claude-api·gemini 두 어댑터),
  `JUDGE_CHUNK` 50 유지. 응답 길이 문제가 실측되면 청크만 낮춘다.
- parse: `home_living` 노드 파싱 추가. 세 축 모두 무효일 때만 건너뜀(기존 "양축 무효" 규칙의
  3축 확장) — 한 축만 무효면 그 축만 null.

## 3. 신규 유입·백필 — 선정·적용 규칙

선정 순서(배치 한도 내): **신규 → F&B 백필 잔여 → 홈/리빙 백필 → rejudge 2종**.

- **신규**(`beauty IS NULL`): 판정 결과를 세 축 모두 적용. 콜 수 증가 없음.
- **홈/리빙 백필**(`beauty IS NOT NULL ∧ home_living IS NULL`): `findHomeLivingBackfillTargets`
  신설(F&B 백필 쿼리와 대칭). 백필 선정 계정은 **뷰티 축 미적용 마스크**에 넣는다 — 기존
  `fnbOnly` 세트를 `backfillOnly`(백필 경로 공용, 의미: 뷰티 축 미적용)로 이름만 일반화하고
  F&B 백필·홈/리빙 백필 둘 다 여기 담는다. 뷰티 판정(MANUAL 포함)은 절대 덮지 않는다.
  F&B·홈/리빙 축은 적용 시점 정착 가드가 보호하므로 축별 마스크 불요(아래).
- **정착 규칙을 홈/리빙 축에 처음부터 적용**(스펙 2026-08-27 F&B 재판정 안정화 §1과 대칭):
  ```
  applyHomeLiving = 이번 응답에 homeLivingClass 있음
      AND home_living_source != MANUAL
      AND ( home_living_class IS NULL                       (첫 판정)
            OR (home_living_caption_count = 0 AND 이번 캡션 수 > 0) )  (업그레이드 1회)
  ```
  홈/리빙 토글을 켜면 F&B가 겪은 "수집 계정 매 주기 재판정→판정 뒤집힘"이 재현되므로
  예방적으로 동일 가드를 건다.
- 기존 뷰티 rejudge 경로·F&B 적용 가드는 무변경.

## 4. 수집·시드 게이트 — 토글, 기본 off

- `SettingsService`에 `homeLivingPipelineEnabled()` 추가 (`home-living.pipeline-enabled`).
- off(기본): collect·reels·similar 선정 현행 그대로. 홈/리빙은 판정 명단만 쌓인다.
- on: `findCollectTargets`/`countBackfillPending`/`findReelsTargets`/`countReelsDue`/
  SIMILAR 시드 선정·카운트 쿼리(기존 `:includeFnb` 7곳)에 `:includeHomeLiving` 파라미터와
  `OR (home_living ∧ ¬home_living_company)` 술어를 추가. 호출부(CollectJob·ReelsJob·
  SimilarJob·StatusService·JobCostEstimator)가 토글을 읽어 전달.

## 5. crawler 운영자 대시보드

**대시보드 타일** (`StatusService` + `UiController.statusTilesFragment`)
- **새 타일 그룹 "③-4 홈/리빙 판정 — beauty 잡의 홈/리빙 축 (QUALIFIED 내)"**: 6타일 —
  홈/리빙 인플루언서 · 회사 · 서비스 · 외국인 · 아님 · 미판정(백필 잔여 = 백필 진행률).
  배지 색은 뷰티 클래스 재사용(③-2 관례), label만 홈/리빙.
- **③-3 수집 모수 그룹을 3축 유니온으로 개편**: 타일 5개 — 뷰티만 / F&B만 / 홈·리빙만 /
  겹침(2축 이상) / 합계(유니온 = 실제 방문 총수). 각 카운트는 축별 "수집 대상" 술어의
  조합이고, 부정절은 **명시 분해형** `(x IS NULL OR x = false OR x_company = true)`로 쓴다
  (NULL 3치 논리 함정 — 스펙 2026-08-25 구현 주의점 승계). "겹침"은 2축 이상 동시 해당
  전체를 하나로 묶는다(2^3 세분화는 타일 낭비). 합계는 StatusService에서 합산.
- 수집 대기열·백필 타일: §4의 쿼리 확장에 따라 토글 on 시 홈/리빙이 자동 편입.

**명단 페이지** (`influencers.html` + `UiController`)
- 홈/리빙 필터 행 추가(5분류 + 미판정 — `HOME_LIVING_FILTERS`, F&B 필터와 대칭 레포 쿼리
  3종 신설). 필터 우선순위는 뷰티 > F&B > 홈/리빙 — 축 교차 조합은 계속 미지원(단순성 우선).
- 행별 수동 오버라이드(`InfluencerBeautyController`)에 홈/리빙 축 엔드포인트·셀렉트 추가 —
  MANUAL 저장·자동 판정 보호는 F&B와 동일.

**잡 관측**
- `BeautyJob.Summary`에 `homeLivingApplied`/`homeLivingPositive` 추가 — 배치 로그·완료
  메시지·실행 이력에 표시. 계정별 로그 라벨에 홈/리빙 결과 병기(F&B 라벨과 같은 형식).
- `logResponseGaps`에 홈/리빙 축 부분 무응답 카운트 추가.
- `JobCostEstimator`: 예상 비용 카드가 토글을 반영(§4 쿼리 공유로 자동).

## 6. 분석 뷰 — 노출만

- `analytics/views/00_base.sql` `v_base_influencer`에 `home_living`, `home_living_company`,
  `home_living_judged_at` 노출 추가(F&B 노출과 대칭 — 추가는 자유, ARCHITECTURE §4-5).
  현재 소비자 없음. 서빙 뷰·미러·was API 무변경.

## 7. 테스트

- `BeautyJobTest`(통합): 신규 3축 적용 / 홈/리빙 백필은 홈/리빙만 적용·뷰티/F&B 보존 /
  백필 선정 순서(F&B 백필 → 홈/리빙 백필) / 정착 규칙 4케이스(첫 판정 적용·캡션 기반 불변·
  캡션 0→N 업그레이드·0→0 미적용) / MANUAL 보호.
- parse 테스트: 3축 JSON·한 축 누락·5분류 외 값 방어(기존 패턴 확장).
- 수집 게이트: 토글 off 선정 무변경, on이면 홈/리빙 포함 — Collect/Reels/Similar 테스트 케이스 추가.
- 대시보드: 3축 유니온 타일의 NULL 미판정 처리(미판정이 "뷰티만"에 포함) 고정.
- SQL 하니스: `00_base` 테스트에 home_living 컬럼 노출 확인.

## 8. 배포 순서

한 PR로 가능(expand만·토글 off라 롤링 안전): 마이그레이션(컬럼+토글 시드) → 코드 →
develop → staging → main. 배포 직후부터 새벽 판정 크론이 신규 3축 판정 + 홈/리빙 백필을
자동 시작(여러 날 소화). 수집 편입은 모수·비용 확인 후 토글 on.
