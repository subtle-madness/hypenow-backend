# 브랜드 tagged 게시물 삭제 감지 — 열거 부재 검증 콜 설계

> 상태: 🟢 활성

## 배경

[2026-08-25 direct 삭제 감지 설계](2026-08-25-brand-post-deletion-hidden-design.md)(운영 반영됨)는
단건 콜 404가 있는 direct 게시물만 감지한다. 운영 실측(꽁냥마켓 `DcNqk6TEeIw`)에서 브랜드
게시물 대부분이 tagged-only라 사용자 체감 커버리지가 낮았다 — tagged까지 확장한다(2026-08-25
사용자 확정: **검증 콜 방식**).

tagged 게시물은 태그 열거로만 관측된다. 삭제되면 열거에서 사라질 뿐 404가 없고, 부재는 태그
해제·미커버 종료와 구분되지 않는다. 그래서 **열거 부재를 후보 신호로만 쓰고, 확정은 단건
조회(`fetchPost`)의 404로만 한다** — 오탐 0, 비용은 "사라진 게시물"에만 발생(건당 $0.00069,
404도 과금).

## 설계

### 1. 후보 판정 (BrandCollectService.doSweepCore 끝단)

열거 종료 후 `coveredCutoff=true`인 실행에서만(③커서 미전진·④안전 밸브로 끊긴 미커버 실행은
부재가 증거가 아니다):

- **검증 하한(verifyFloor)**: ①②(진짜 컷 커버·커서 소진)는 `cutoff`, ⑤(수집 개수 상한)는
  **실제 커버 깊이**(`oldestTakenAt(collected)`) — 목표 컷을 쓰면 미도달 구간 전체를 부재로
  오판해 검증 콜이 폭주한다. ⑤인데 깊이 미상(편입 0건)이면 검증 자체를 건너뛴다.
- **후보 SQL**(신규 `TaggedPostRepository.tagVerifyCandidates`): `tag_detected_at IS NOT NULL
  AND direct_registered_at IS NULL`(겹침 행은 2단계 direct 단건 수집이 이미 404를 잡는다 —
  중복 과금 방지) `AND taken_at >= :verifyFloor AND unavailable_at IS NULL AND
  (absence_checked_at IS NULL OR absence_checked_at < :recheckBefore)`.
- Java에서 이번 열거 관측분(`seen`) 제외 후, **스윕당 브랜드당 상한 30건**(상수) — 초과분은
  개수와 함께 log.warn(침묵 컷 금지), 다음 스윕이 이어받는다.

### 2. 검증 콜

후보마다 `hiker.fetchPost` 1콜(게시물 단위 격리, `doSweepCore` 안이라 브랜드 비용 계상 유지):

- `SubjectNotFoundException`(404) → `markUnavailable`(기존) — was가 hidden으로 노출.
- 성공(살아있음 — 태그 해제·열거 요동) → `absence_checked_at = now()`만 기록. 스냅샷 저장은
  하지 않는다(존재 확인 전용 — YAGNI).
- 그 외 실패(5xx·타임아웃) → 아무것도 안 찍고 격리(다음 스윕 재시도).

### 3. 재검증 스로틀·자가 치유

- `brand_tagged_post.absence_checked_at timestamptz` 신설(expand-only). 검증 완료 시각 — 살아있는
  채 태그만 해제된 게시물이 매일 콜을 유발하지 않게 **7일 스로틀**(상수)로 후보에서 제외한다.
  404 확정 행은 `unavailable_at` 자체가 후보 제외 조건이라 재검증 콜이 없다.
- 자가 치유는 기존 경로 그대로: 게시물이 열거에 재등장(재태그·보관 해제)하면 `touchCrawled`가
  `unavailable_at`을 해제한다. 이때 `absence_checked_at`도 함께 NULL로 되돌린다 — 재관측 후 다시
  사라지면 7일을 기다리지 않고 즉시 검증하기 위함이다.

### 4. was

코드 변경 없음 — hidden 유도는 이미 `unavailable_at != null` 단일 조건이라 tagged 행에도 그대로
작동한다. `BrandPostAssembler`의 "tagged-only 행은 항상 null" 주석만 갱신.

### 5. 비용

검증 콜은 "커버된 열거에서 사라진 게시물"에만 발생 — 정상 상태에선 0콜. 태그 해제 게시물은
첫 검증 후 7일에 1콜로 수렴, 삭제 게시물은 확정 후 0콜. 폭주 상한 = 브랜드당 30콜/일.

### 6. 테스트

- monitoring: 커버 스윕에서 미관측 tagged-only 행 검증 → 404면 마킹 / 성공이면 checked만 /
  미커버(③) 스윕은 검증 스킵 / 겹침·direct-only 행 후보 제외 / 상한 컷 로그.
- 리포지토리 시맨틱: `touchCrawled`가 `absence_checked_at`도 해제.
