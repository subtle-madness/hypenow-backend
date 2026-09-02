# 대시보드 뷰티∪F&B 중복 제거 타일 그룹 설계

> 상태: 🟢 활성 · ✅ 구현/실행/반영됨

## 목적

뷰티 축과 F&B 축의 수집 대상이 일부 겹친다(2026-08-25 운영 실측: 겹침 73명). 대시보드의
③(뷰티 판정)·③-2(F&B 판정) 그룹은 축별 카운트만 보여줘서, "실제로 방문하게 될 계정이 총
몇 명인가"(유니온)와 "두 축이 얼마나 겹치나"를 알 수 없다. fnb.pipeline-enabled 토글을 켠
상태(2026-08-25~)에서 수집 규모를 오독하지 않도록 중복 제거 뷰를 추가한다.

## 설계

**새 타일 그룹 "③-3 수집 모수 — 뷰티 ∪ F&B (중복 제거)"** 를 ③-2와 ④ 사이에 추가. 타일 4개:

| 타일 | 정의 |
|---|---|
| 뷰티만 | 뷰티 수집 대상 ∧ F&B 수집 대상 아님 |
| F&B만 | F&B 수집 대상 ∧ 뷰티 수집 대상 아님 |
| 겹침 | 둘 다 수집 대상 |
| 합계 | 셋의 합 = 유니온, 실제 방문 계정 총수 |

"수집 대상" 술어는 `findCollectTargets`와 동일: `beauty = true ∧ (beauty_company IS NULL OR
false)` / `fnb = true ∧ (fnb_company IS NULL OR false)`, 모수는 QUALIFIED. 토글 상태와
무관하게 항상 표시한다(③-2와 동일 방침 — 토글 off일 때도 "켜면 이만큼 합류"를 보여주는 용도).

## 구현 주의점

- **NULL 3치 논리**: "뷰티만"의 부정절을 `NOT (fnb = true AND …)`로 쓰면 미판정(`fnb IS
  NULL`) 계정이 NULL 평가로 빠진다. 반드시 명시 분해형으로 쓴다:
  `(fnb IS NULL OR fnb = false OR fnb_company = true)`.
- 합계는 별도 쿼리 없이 StatusService에서 세 카운트를 합산한다(같은 트랜잭션이 아니어도
  타일 용도로는 오차 허용).
- 배지 색은 기존 클래스 재사용(③-2 관례): 뷰티만·F&B만 = BEAUTY, 겹침 = BEAUTY_SERVICE,
  합계 = QUALIFIED. label로 의미를 준다.

## 변경 파일

- `InfluencerRepository`: count 쿼리 3개(뷰티만·F&B만·겹침).
- `StatusService.StatusSummary`: 필드 3개 추가(`beautyOnlyCollectable`, `fnbOnlyCollectable`,
  `bothCollectable`).
- `UiController.statusTilesFragment`: 타일 그룹 1개 추가(템플릿 변경 없음 — 서버 렌더 반복문).
- 테스트: 통합 테스트로 NULL 미판정 계정이 "뷰티만"에 포함되는 것을 고정(핵심 함정),
  겹침·단독 카운트 검증.
