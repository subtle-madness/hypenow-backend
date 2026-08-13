# 브랜드 등록 백필 — 첫 페이지 즉시 ready 설계

> 상태: 🟢 활성 · 구현 대상
> 선행: [2026-08-12 백필 페이지 스트리밍 적재 + 조기 서빙](2026-08-12-brand-backfill-streaming-serving-design.md)
> (그 설계의 "1차 ready 기준 = 최근 30일 커버"를 이 문서가 개정한다 — 스트리밍 적재·신호 3분리는 그대로다)
> 동반 문서: [브랜드 fast-ready 프론트 요청](../../contracts/brand-fast-ready-frontend-request.md)

## 배경 — 왜 또 당기나

08-12 스트리밍 개정으로 등록 → ready가 8분 24초 → 약 1분 30초(tooq 실측 ~17콜)가 됐다.
그런데 서빙 창 30일은 **게시물 물량에 비례하는 대기**다 — 브랜드가 하루 12건씩 태그되면
30일치가 17페이지고, 물량이 늘수록 첫 화면이 늦어진다. 사용자 관점에서 "많이 태그되는
브랜드일수록 오래 기다린다"는 방향이 거꾸로다.

FE 실측(celfit-front `BrandMonitoringClient`)으로 드러난 두 사실이 판단을 바꿨다:

1. **첫 화면 기본 기간은 30일이 아니라 12개월이다** (`useState<BrandDateRange>("12m")`,
   프리페치도 `"12m"`). 즉 서빙 창 30일은 첫 화면의 완전성을 보장한 적이 없다 — 지금도
   ready 시점엔 12개월 화면에 30일치만 있다. 30일은 "충분히 두툼한 부분"이었을 뿐이다.
2. **ready가 열리는 순간 FE 폴링이 멈춘다** (`isCollectionPollable`은
   `status !== "collecting"`이면 false, 유예는 `collectionCompletedAt`이 찍힌 뒤 3분만).
   그래서 ready 이후 들어오는 데이터는 자동으로 화면에 붙지 않는다.

②는 서빙 창을 얼마로 잡든 존재하는 결함이라 서빙 창을 줄이는 것과 별개로 FE가 고쳐야 한다
(동반 요청 문서). 그 결함을 고친다는 전제에서, 서빙 창을 유지할 이유는 남지 않는다 —
"부분을 먼저 보여주고 계속 채운다"가 설계 의도라면 부분은 빠를수록 낫다.

## 결정

**1차 ready 기준 = 첫 편입 성공 페이지.** 서빙 창(`serving-window-days`) 폐기.

등록 → ready = 프로필 1콜 + 열거 1콜 ≈ **7초**, 게시물 물량과 무관한 상수다.

| 브랜드 | 현행(30일 창) | 변경 후 |
|---|---|---|
| tooq급(11.8건/일) | ~17콜 ≒ 1분 30초 | 1콜 ≒ 7초 |
| 저물량(30일치가 1페이지) | ~1콜 ≒ 7초 | 동일 |

## 설계

### 1. BrandCollectService.doSweepCore — 콜백 트리거 교체

현행: 페이지 전체가 `now - servingWindowDays`보다 오래된 순간 콜백.
변경: **`collected`가 처음으로 비지 않게 된 페이지를 처리한 직후** 콜백.

```java
collected.addAll(processPage(brand, newItems, known, today, now));
if (!servingMarked && !collected.isEmpty()) {
    servingMarked = true;
    onServingCovered.accept(List.copyOf(collected));
}
```

**편입 0건 페이지에서는 쏘지 않는다.** 소급 태그나 수집 창 밖 게시물이 맨 앞 페이지를
채우면 "ready인데 목록 0건" 화면이 뜬다 — 열거는 계속되고 있으므로 그건 거짓 신호다.
끝까지 편입이 0건이면 루프 종료 폴백(`if (!servingMarked)`)이 1회 호출한다(현행 유지) —
그때는 정말로 보여줄 게 없는 브랜드이고, 그 사실을 FE에 알리는 게 맞다.

`servingCutoff` 계산·`servingWindowDays` 필드·`monitoring.brand.serving-window-days` 설정을
함께 제거한다(deploy env에 주입된 적 없어 정리가 안전하다).

불변인 것: **"정확히 1회" 호출 계약**(예외 중단 제외), 열거 종료 판정 4종, `coveredCutoff`,
`touchCrawledDepth`, 반환값(전체 누적 리스트).

### 2. 신호 3개의 의미 — 전부 불변

| 컬럼 | 의미 | 갱신 시점 |
|---|---|---|
| `last_swept_at` | 서빙할 데이터 있음 | **첫 편입 페이지 시(markServing)** + 완주 시(touchSwept) |
| `last_swept_on` | 이번 정책 기준 완주 | 완주 시만 |
| `backfill_completed_at` | 최초 완주 시각 | 완주 시만 |

08-12 설계의 논거가 그대로 성립한다 — 조기 마킹은 `last_swept_at`만 건드리므로, 백필이
중간에 죽어도 `last_swept_on`이 null로 남아 다음 일일 스윕이 전체 창을 백스톱한다.

### 3. 선행 보강

`markServing` 시점의 선행 enrich(게시자·댓글)는 그대로다. 대상이 30일치에서 첫 페이지
~21건으로 줄어 더 빨리 끝나고, 완주 후 잔여 보강이 나머지를 덮는 관계도 그대로다.

### 4. was·FE 계약

**변경 없음.** 응답 필드·상태 유도 규칙(`BrandAccountAssembler`) 모두 그대로다.
collecting → ready 전환이 더 당겨질 뿐이다.

## 배포 순서 — FE보다 먼저 나가면 일시적으로 나빠진다

FE 미반영 상태로 백엔드만 배포되면 ready 시점 첫 화면이 30일치(tooq 기준 ~350건)에서
21건으로 얇아지고, 폴링은 여전히 멈춘 채라 새로고침(또는 staleTime 5분 경과 후 재마운트)
전까지 그 상태가 유지된다. **권장 순서는 FE 반영과 함께 배포**다. 급하면 백엔드 선배포도
가능하지만 그 창의 첫 화면이 얇아지는 건 감수해야 한다.

롤백은 배포 롤백으로 한다 — 두 갈래 로직을 설정으로 남기지 않는다(죽은 손잡이가 다음
세션의 오독 비용이 된다).

## 테스트

`BrandCollectServiceTest`의 서빙 콜백 케이스를 교체한다.

| 케이스 | 기대 |
|---|---|
| 첫 페이지에 편입 게시물 있음 | 그 페이지 직후 콜백 1회(누적분 = 1페이지분), 열거는 컷까지 계속 |
| 첫 페이지 편입 0건(창 밖) → 다음 페이지 편입 있음 | 콜백은 2페이지 직후 1회 |
| 끝까지 편입 0건 | 열거 종료 시점에 1회(빈 리스트) |
| 안전 상한 중단 | 기존과 동일(첫 페이지에서 이미 콜백 — 상한 도달과 무관) |

`BrandRegistrationServiceTest`는 콜백 계약(정확히 1회)이 불변이라 변경하지 않는다.
