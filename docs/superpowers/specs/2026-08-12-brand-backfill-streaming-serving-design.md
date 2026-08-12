# 브랜드 백필 페이지 스트리밍 적재 + 조기 서빙 설계

> 상태: 🟢 활성 · 설계 승인됨(2026-08-12) · 구현 계획: (작성 예정)

## 배경 — 왜 바꾸나

tooq.official 등록 실측(2026-08-12, 운영): 등록 → ready(첫 화면) **8분 24초**.

- 등록 백필은 365일치 태그 열거(`/v2/user/tag/medias`)를 **전부 끝낸 뒤에야** 일괄
  적재(processCore)하고 touchSwept(ready)를 찍는다.
- tooq는 태그 게시물이 많아 96콜 × 콜당 p50 4.9초(재시도 0 — Hiker 이 엔드포인트 자체가
  느리다)가 그대로 대기 시간이 됐다. 열거는 커서 체인이라 병렬화 불가.
- 그동안 FE는 collecting 로딩 화면으로 **이미 받아온 데이터까지 가린다**.

목표: 완성된 부분(최신부터)을 먼저 서빙한다. 사용자 결정(2026-08-12): **1차 ready 기준 =
최근 30일 커버**(tooq 실측 ~17콜, 약 1분 30초), 방식 = **페이지 스트리밍 적재**(중복 콜 0),
게시자·댓글 보강은 **30일 커버 시점에 선행 시작**.

## 전제 — 신호의 의미 분리

`brand_account`의 스윕 관련 컬럼 3개는 소비자가 다르다:

| 컬럼 | 소비자 | 의미 | 갱신 시점(이 설계) |
|---|---|---|---|
| `last_swept_at` | was ready 판정(`BrandAccountAssembler`) | 서빙할 데이터 있음 | **30일 커버 시(신설 markServing)** + 완주 시(touchSwept, 현행) |
| `last_swept_on` | 다음 스윕 열거 깊이(`enumerationCutoff` 백필 판정) | 이번 정책 기준 완주 | 완주 시만(현행 유지) |
| `backfill_completed_at` | FE "과거분 수집 중" 배지(`backfillCompletedAt`) | 최초 완주 시각 | 완주 시만(현행 유지) |

30일 시점에 `last_swept_on`을 찍으면 안 된다 — 이후 백그라운드 열거가 실패했을 때 다음
스윕이 14일 컷만 돌아 30~365일 구간이 영구 공백이 된다(안전 상한 초과와 같은 유형의 구멍).
`last_swept_at`만 조기 마킹하면: 백필이 중간에 죽어도 FE는 부분 데이터를 계속 보고(08-10
결정 "데이터 있으면 보여준다"와 정합), `last_swept_on`이 null로 남아 다음 일일 스윕이
전체를 백스톱한다(자가 치유 유지).

## 설계

### 1. BrandRepository — markServing 신설

```sql
UPDATE brand_account SET last_swept_at = now()
WHERE id = ? AND last_swept_at IS NULL
```

첫 백필에서만 유효(이미 서빙 중이면 no-op). touchSwept(3컬럼 일괄)는 현행 그대로 완주
시점에만. 스키마 변경 없음.

### 2. BrandCollectService.sweepCore — 스트리밍화

현행 "전체 열거 → 일괄 processCore"를 **페이지(~21건)마다 즉시 처리**로 바꾼다:

- `knownCodes`는 루프 시작 때 1회 로드, 이번 실행 처리분 코드를 누적해 페이지 간 중복
  (커서 드리프트)은 스킵.
- 페이지마다: 편입 컷(365일) 필터 → 복권 지표 보정(zero-carry 이력 쿼리도 페이지 범위) →
  스냅샷 upsert·신규 링크 insert → `touchCrawled`. 전부 upsert/멱등이라 재실행 안전.
- **서빙 경계 판정**: 설정 `monitoring.brand.serving-window-days: 30`. 페이지 전체가 경계보다
  오래된 순간(소급 태그 혼입 대비 — 기존 `wholePageBeforeCutoff`와 같은 보수 규칙) 서빙
  콜백 1회 호출. 게시물이 30일치보다 적거나 열거가 그전에 끝나면(자연 종료·상한·미전진
  포함) 루프 종료 시점에 호출.
- 열거 종료 판정 4종·`coveredCutoff`·`touchCrawledDepth`는 현행 그대로.
- 반환값은 현행처럼 전체 누적 리스트.

서빙 콜백은 `sweepCore(brand, BiConsumer<...>)` 류 파라미터로 받는다. 콜백 인자로 "그
시점까지 누적된 게시물 목록"을 넘긴다(선행 보강 입력). 일일 스윕 경로는 no-op 콜백.

### 3. BrandRegistrationService — 선행 보강

등록 백필의 서빙 콜백 = `markServing` + **그 시점까지의 게시물로 enrich(게시자 프로필 +
댓글)를 enrich executor에 즉시 제출**. 열거 완주 후에는 나머지(선행분 제외) 게시물만
enrich한다 — 선행분 코드 집합으로 필터. 게시자 프로필은 전역 fresh 캐시(30일)라 겹쳐도
추가 콜이 없고, 댓글 게이트는 워터마크 기반이라 중복 제출에 안전하다.

수집 순서(등록 백필, tooq 기준 시각):

1. 브랜드 프로필 1콜 (~2초)
2. 태그 열거·적재 — 최신→과거 페이지 스트리밍 (30일 경계 ~1분 30초, 완주 ~8분)
3. 30일 커버 시: **ready 전환** + 최근 30일분 게시자·댓글 보강 시작(병렬 6, 별도 executor)
4. 열거 완주 시: touchSwept(last_swept_on·backfill_completed_at) + 나머지 게시자·댓글 보강
5. 해시태그 첫 스윕(현행 유지 — 보강 뒤)

### 4. FE/was 계약 — 변경 없음

- 폴링 구조 그대로: collecting → ready 전환이 당겨질 뿐. ready 후 목록 API 재조회 때마다
  적재분이 늘어난다.
- "과거분 아직 수집 중" 판별은 기존 응답 필드 `backfillCompletedAt == null`로 가능.
- 게시물 목록 렌더에 필요한 값(지표·댓글 수·작성자 username)은 열거 응답에 이미 있으므로
  보강 미완이 첫 화면을 깨지 않는다.

### 5. 실패 시나리오

- 열거 중간 실패: 적재된 페이지는 그대로 서빙. markServing 이후면 FE는 ready + 부분
  데이터. `last_swept_on` null → backfill_error 기록(현행 가드 `last_swept_on IS NULL`
  그대로 동작) + 다음 스윕 전체 재백필.
- markServing 이전 실패: 현행과 동일(collecting → error 문구, 다음 스윕 백스톱).
- 선행 보강 실패: 현행 enrich와 동일 격리(warn 로그만) — 게시자 stale·댓글 워터마크로
  다음 스윕이 재시도.

### 6. 동시성

전역 Hiker 동시 콜 상한(실측 무저항 한계 8 = enrich 워커 6 + 스윕 core 1 + 등록 core 1,
`BrandBackfillConfig`)은 불변 — 선행 보강은 enrich executor(기존 큐)에서 돌며, 등록 core
열거와 겹치는 구간이 "완주 후 보강" 대비 앞당겨질 뿐 동시 콜 수 자체는 같다.

### 7. 테스트

기존 monitoring 관용구(가짜 HikerHttp)로:

1. 30일 경계 통과 페이지에서 markServing 1회 + 서빙 콜백에 그때까지 누적분 전달,
   `last_swept_on`은 미변경.
2. 게시물이 30일치 미만이면 루프 종료 시점 markServing.
3. 중간 페이지 실패 시 앞 페이지 적재 보존 + backfill_error 기록.
4. 페이지 간 중복 코드 스킵(첫 관측 유지 — 현행 putIfAbsent 의미 보존).
5. 완주 시 touchSwept 3컬럼 현행대로 갱신.
6. 등록 경로: 선행 보강 제출분과 완주 후 잔여 보강분이 겹치지 않음(코드 집합 분리).

## 비범위

- 안전 상한(max-posts-per-sweep 2000) 초과·백필 공백 대응 — 별도 트랙(task_9385a385,
  독립 세션 진행 중).
- 게시물 목록 API 페이지네이션(현행 전량 응답 유지).
- 해시태그 스윕 경로 변경 없음.
