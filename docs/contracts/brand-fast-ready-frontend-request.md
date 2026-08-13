# 브랜드 등록 첫 화면 fast-ready — 프론트 요청 (celfit-front 전달)

> 상태: 🟢 활성 · 백엔드 구현 완료(배포 대기)
> 수신: celfit-front 담당자. **API 계약 변경은 없다** — 응답 필드도, 상태 어휘도 그대로다.
> 바뀌는 건 `collectionStatus`가 `ready`로 넘어가는 **시점**뿐이고, 그 결과 FE 쪽에 이미 있던
> 결함 하나가 눈에 띄게 드러난다. 이 문서는 그 결함의 수정 요청이다.
> 근거 설계: 백엔드 [2026-08-13 첫 페이지 즉시 ready](../superpowers/specs/2026-08-13-brand-first-page-fast-ready-design.md)

## 1. 무엇이 바뀌나

브랜드 등록 백필의 ready 전환 시점을 앞당겼다.

| | 종전 | 변경 후 |
|---|---|---|
| ready 조건 | 최근 **30일치** 수집 완료 | **첫 페이지(최신 ~21건)** 적재 완료 |
| tooq급(11.8건/일) 등록 → ready | 약 1분 30초 | **약 7초** |
| 저물량 브랜드 | 약 7초 | 동일 |

종전 규칙은 태그 게시물이 많은 브랜드일수록 첫 화면이 늦어지는 구조였다(30일치가 17페이지).
이제 물량과 무관하게 상수다.

수집 자체는 그대로다 — ready 이후에도 백그라운드에서 **수집 창 전체(기본 12개월)** 열거가
계속되고, 고물량 브랜드는 완주까지 약 8분 걸린다. 즉 **ready 시점 데이터는 최신 21건뿐**이고
나머지는 그 뒤 수 분에 걸쳐 채워진다.

## 2. 그래서 고쳐야 하는 것 — ready 이후 폴링이 멈춘다

`src/lib/monitoring/brand-policy.ts`의 `isCollectionPollable`이 이렇게 돼 있다.

```ts
if (isCollectionSettling(account, now)) return true;
if (account.collectionStatus !== "collecting") return false;   // ← ready면 즉시 중단
```

`isCollectionSettling`은 `collectionCompletedAt`(백엔드 `backfillCompletedAt` = **최초 완주**
시각)이 찍힌 뒤 3분만 true다. 그런데 fast-ready 시점엔 `collectionCompletedAt`이 아직 **null**이다
(완주는 몇 분 뒤). 그래서 지금 코드로는:

- 계정 목록 폴링(`BrandAccountsProvider` `refetchInterval`) → ready 되는 순간 멈춤
- 게시물 목록 폴링(`BrandMonitoringClient` `postsQuery.refetchInterval`) → settling일 때만 도는데
  settling이 false라 아예 안 돎

결과: **12개월 기본 화면에 21건만 뜬 채로 고정**되고, 사용자가 새로고침하거나 staleTime 5분이
지나 재마운트될 때까지 그대로다. 뒤이어 들어온 수백 건이 화면에 안 붙는다.

이건 fast-ready가 만든 결함이 아니라 **원래 있던 결함**이다(종전에도 ready 시점 30일치 → 12개월
화면이었고 폴링은 똑같이 멈췄다). fast-ready가 그 간극을 21건까지 넓힐 뿐이다.

### 요청 2-1. 폴링 조건 확장

`isCollectionPollable`을 "**`collectionCompletedAt`이 null이면 status가 ready여도 계속 폴링**"으로
넓혀 주세요. 나머지 안전장치는 그대로 두는 게 좋습니다.

```ts
export function isCollectionPollable(account, now): boolean {
  if (isCollectionSettling(account, now)) return true;
  if (account.collectionStatus === "error") return false;
  // ready여도 최초 완주 전이면 백그라운드 수집이 계속된다(백엔드 fast-ready, 08-13).
  if (account.collectionCompletedAt) return false;
  const started = account.collectionStartedAt;
  if (started === null) return false;
  const startedAt = typeof started === "number" ? started : Date.parse(started);
  if (Number.isNaN(startedAt)) return false;
  return now - startedAt < BRAND_COLLECTION_POLL_MAX_MS;   // 30분 상한 유지
}
```

- 30분 상한(`BRAND_COLLECTION_POLL_MAX_MS`)은 그대로 두세요 — 백엔드가 완주를 못 찍는 사고가
  나도 무한 폴링으로 가지 않는 유일한 방어입니다. 정상 완주는 길어야 ~8분이라 상한 안입니다.
- 종료 판정은 `collectionCompletedAt != null`이 정본입니다. 백엔드는 열거 완주 시에만 이 값을
  찍고, 그 뒤 3분 settle 동안 지표·댓글 보강분이 마저 들어옵니다(기존 규칙 그대로).
- `collectionStartedAt`은 등록(및 기간 확장) 시각이라 앵커로 그대로 쓸 수 있습니다.

### 요청 2-2. 게시물 목록도 같이 다시 읽기

`BrandMonitoringClient`의 `postsQuery.refetchInterval`이 지금은 settling 구간에서만 돕니다.
**`collectionCompletedAt`이 null인 동안에도** 같은 간격(`BRAND_COLLECTION_POLL_MS` 10초)으로
다시 읽어 주세요. 계정 상태만 갱신되고 목록이 그대로면 화면상 달라지는 게 없습니다.

주의: `enabled`가 `collectionStatus === "ready"` 조건을 그대로 쓰면 됩니다 — fast-ready
이후로는 계속 ready이므로 조건이 끊기지 않습니다.

## 3. 요청 3. "계속 수집 중" 표시

지금은 `collectionCompletedAt`을 폴링 판정에만 쓰고 화면에 아무 표시가 없습니다. fast-ready
이후에는 **ready이면서 아직 부분 데이터**인 구간이 수 분간 존재하므로, 사용자에게 그 사실이
보여야 합니다. 안 그러면 "12개월 화면인데 21건뿐"이 그냥 틀린 화면으로 읽힙니다.

- 조건: `collectionStatus === "ready" && collectionCompletedAt == null`
- 위치 제안: 게시물 패널 헤더(개수 옆) 또는 프로필 카드 아래 인라인 배너
- 문구 제안: "최근 게시물부터 먼저 보여드리고 있어요. 과거 게시물은 계속 불러오는 중입니다
  (보통 몇 분)." — 개수가 늘어난다는 사실이 전달되면 표현은 자유입니다
- 함께 짚어주면 좋은 것: **정렬·필터 결과도 아직 부분 기준**입니다. 특히 인기순 정렬과 칩
  개수는 지금 받은 범위 안에서만 계산되므로 수집이 끝나면 순위가 바뀔 수 있습니다

## 4. 요청 4. collecting 로딩 화면 문구

`BrandCollectionLoadingState`가 안내하는 예상 소요(`BRAND_COLLECTION_ETA_MIN/MAX` = 3~5분)는
이제 실제와 크게 어긋납니다. **collecting 구간 자체가 약 7초**라 이 화면은 거의 스쳐 지나갑니다.

- ETA 상수를 "10초 내외"로 낮추거나, 로딩 화면에서 시간 안내를 빼고 요청 3의 배너가 이후를
  설명하게 하는 편이 깔끔합니다
- `stalled` 판정(폴링 상한 초과)은 그대로 두세요 — 등록이 진짜로 실패한 경우의 유일한 출구입니다

## 5. 타임라인 (tooq급 고물량 브랜드 기준)

| 시각 | 백엔드 | `collectionStatus` | `collectionCompletedAt` | 화면 |
|---|---|---|---|---|
| 0초 | 등록 요청 | collecting | null | 로딩 |
| ~2초 | 프로필 1콜 완료 | collecting | null | 로딩 |
| **~7초** | 첫 페이지 적재 → ready | **ready** | null | 게시물 21건 + "수집 중" 배너 |
| ~7초~8분 | 페이지마다 ~21건씩 적재 | ready | null | 10초마다 개수 증가 |
| ~8분 | 열거 완주 | ready | **찍힘** | 배너 제거, settle 3분 폴링 후 종료 |

저물량 브랜드는 첫 페이지가 곧 전량이라 ~7초에 ready + 곧바로 완주입니다.

## 6. 확인 안 해도 되는 것

- 응답 스키마·필드명·상태 어휘: **변경 없음**
- `nextScheduledAt`·`collectionMonths`·기간 확장(`collection_started_at` 앵커) 동작: 변경 없음
- 기간 확장 중 상태(`collecting` + 게시물 있음 = 확장 배너) 규칙: 변경 없음.
  이번 요청 3의 배너는 **최초 등록** 구간(`collectionCompletedAt == null`)이라 확장 배너와
  조건이 겹치지 않습니다

## 7. 배포 순서 (중요)

백엔드가 먼저 나가고 FE가 늦으면, ready 시점 첫 화면이 종전 30일치(~350건)에서 21건으로
얇아진 채 폴링은 여전히 멈춘 상태가 됩니다 — **일시적으로 지금보다 나쁩니다.**
가능하면 FE 반영과 함께 배포하고, 순서가 어긋날 것 같으면 알려주세요. 백엔드 배포를
FE 일정에 맞춰 잡겠습니다.
