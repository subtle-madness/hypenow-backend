# 성과 대시보드 동시 조립 합류(single-flight) — 26초 꼬리 응급 처치 설계

> 상태: 🟢 활성 (2026-08-31 작성 · 미구현)
>
> 계기: 운영 요청 추적 대시보드에서 `GET /v1/performance-dashboard/growth` 26.5초 관측(request_id `mbx0prz2`, 2026-08-31 00:32 UTC)
> 선행: [2026-08-27 목록 API 최적화](2026-08-27-perf-dashboard-list-api-optimization-design.md)(2단 조립·ETag) ·
> [2026-08-13 ETag 설계](2026-08-13-performance-dashboard-etag-design.md)(버전키 계약) ·
> DECISIONS 2026-08-28 브랜드 인덱스 캐시(`BrandIndexCache` — 브랜드 표면 전용)

## 배경

성과 대시보드 4표면(contents·comparison·influencers·growth)이 요청마다
`PerformanceContentAssembler.index(userId)`로 **유저의 브랜드 풀 전량**을 다시 만든다. 이 조립
비용은 연결 브랜드 수 × 창 안 행 수에 비례하고, FE는 개요 화면에서 **계정마다 growth를 1건씩
병렬로** 쏜다. 그래서 같은 조립이 동시에 N번 돌고 2코어 호스트에서 서로를 밀어낸다.

본 설계는 그 **중복 조립만** 없앤다(응급). 조립 1회의 비용(8초)과 그 구조적 원인은 §7 후속으로
넘긴다 — 사용자 결정(2026-08-31): "구조 개편은 별도로 설계하고, 그동안 26초는 single-flight로 막는다".

## 결정 요약

| # | 항목 | 결정 |
|---|---|---|
| P0 | 동시 중복 조립 | 같은 (userId, 버전키)로 진행 중인 조립에 **합류**시킨다 — 리더 1명만 조립, 나머지는 그 결과를 받는다(§2) |
| P0 | 보관 | **하지 않는다.** 완료 즉시 맵에서 제거 — 캐시가 아니라 인플라이트 합류다(힙 증가 0, §2-2) |
| P0 | 합류 키 | `(userId, DashboardVersion.compute)` — 버전이 갈린 요청끼리 합류해 ETag(신)와 바디(구)가 어긋나는 것을 막는다(§3) |
| P1 | 버전키 계산 | 요청당 1회 — `conditional()`이 이미 계산한 값을 body로 넘긴다(`Supplier<T>` → `Function<String,T>`, §3) |
| P1 | 공유 안전성 | `DashboardIndex`를 완전 불변으로 굳힌다(§4) |
| — | 비채택 | 인덱스 캐시(보관) — §7-2, `findAccount` 배치 — §7-3 |

## §1 진단 (실측)

### 1-1 관측된 요청

```
요청 단계 요약 uri=/v1/performance-dashboard/growth status=200 total_ms=26493 repo_ms=26370 stage_count=22
  stage=BrandReadRepository.findBrandPostIndex        ms=15533 calls=6
  stage=MonitoringReadRepository.findTargetDetails    ms=3976  calls=1
  stage=MonitoringReadRepository.findSnapshots        ms=1985  calls=1
  stage=BrandReadRepository.findAccount               ms=1883  calls=12
  stage=BrandReadRepository.findLatestSnapshotsForBrand ms=1587 calls=6
  stage=MonitoringReadRepository.lastSuccessfulSweepAt ms=689  calls=1
  stage=BrandReadRepository.findAuthors               ms=605   calls=1
  stage=기타(조립·직렬화·응답쓰기)                      ms=123   calls=1
```

앱 CPU(조립·직렬화)는 123ms뿐이고 26.4초가 전부 리포지토리 대기다.

### 1-2 26초의 정체는 자기 경합이다

같은 시각 caddy 로그 — FE가 **계정마다 1건씩** 병렬로 쏜다:

```
00:31:47  19.16s  /growth?from=2026-03-01&to=2026-08-31&granularity=day&accountIds=119
00:31:55  25.29s  ... accountIds=120
00:31:58  26.39s  ... accountIds=91
00:32:00  22.39s  ... accountIds=92
00:32:01  25.27s  ... accountIds=201
00:32:01  27.30s  ... accountIds=195
00:32:06  26.50s  ... accountIds=119   ← mbx0prz2
```

00:31:28~39 구간에 **7건이 동시 in-flight**다. `accountIds`는 인덱스를 **다 만든 뒤** 메모리에서
거는 필터라([V1PerformanceDashboardController](../../../was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java) growth 라우트),
계정 6개면 6개 요청이 각자 6브랜드 전량을 만든다.

경합이 붙기 전 같은 엔드포인트(`ji0nuz8g`, 00:31:00)의 분해가 **조립 1회의 진짜 비용**이다:

| 단계 | ms | calls |
|---|---|---|
| `findBrandPostIndex` | 6,211 | 6 |
| `findLatestSnapshotsForBrand` | 1,373 | 6 |
| `findAuthors` | 629 | 1 |
| 기타(조립·직렬화) | 119 | 1 |
| **합계** | **8,413** | |

즉 **8.4초가 고정비이고 나머지 18초는 7중 자기 경합**이다. 값싼 단계가 같이 부푼 것이 그 증거다
— `findAccount`·`lastSuccessfulSweepAt`·`findTargetDetails`는 경합 없는 요청에서 합쳐 20ms대인데
`mbx0prz2`에서는 각각 1,883ms·689ms·3,976ms다. 쿼리가 무거워진 것이 아니라 줄을 선 것이다.

### 1-3 ETag가 못 막는 이유

`accountIds`가 달라 URL이 6개로 갈리고, 화면 진입 시점엔 그 URL들의 사본이 없어 `If-None-Match`가
실리지 않는다. 서버 쪽 합류는 URL이 달라도 같은 인덱스를 공유하므로 이 지점을 정확히 메운다.

### 1-4 24시간 분포

| 표면 | 느린 요청(>1s) | p50 | max |
|---|---|---|---|
| `/performance-dashboard/growth` | 11 | 22.4s | 27.3s |
| `/performance-dashboard/contents` | 7 | 8.0s | 12.3s |

산발 사고가 아니라 이 표면의 상시 상태다.

## §2 설계 — `DashboardIndexCoalescer`

### 2-1 계약

`v1/perfdashboard/DashboardIndexCoalescer`(신규 컴포넌트). 어셈블러는 순수하게 두고 합류기가 그
앞에 선다 — `BrandIndexCache`가 브랜드 표면에서 취한 배치와 같다.

```java
public DashboardIndex index(String version, long userId)
```

```java
Key key = new Key(userId, version);
var mine = new CompletableFuture<DashboardIndex>();
var existing = inFlight.putIfAbsent(key, mine);   // 맵에 넣는 건 값이 아니라 "진행 중" 표식
if (existing != null) {
    return join(existing);                        // 합류자 — 리더 결과를 그대로 받는다
}
try {
    DashboardIndex value = assembler.index(userId);   // 조립은 맵 밖에서, 리더 1명만
    mine.complete(value);
    return value;
} catch (Throwable t) {
    mine.completeExceptionally(t);
    throw t;
} finally {
    inFlight.remove(key, mine);                    // 완료 즉시 폐기
}
```

### 2-2 왜 보관하지 않는가

보관(캐시)은 웜 요청을 수십 ms로 만들지만 **콜드 8초는 그대로**이고, 유저당 엔트리가 ~13MB
(§7-1 실측 기준)라 힙을 100MB 단위로 먹는다. 무엇보다 §7-1 구조 개편이 오면 조립 자체가
100ms대가 되어 보관할 이유가 사라진다 — 지금 넣으면 곧 걷어내야 한다. 그래서 이 PR은
**중복만 없애고 보관은 하지 않는다**. 맵에 남는 것은 "지금 진행 중인 키"뿐이고 `finally`가 제거를
보장한다.

### 2-3 리더 실패는 합류자에게도 전파한다

`join`은 `CompletionException`을 벗겨 원 예외로 던진다. 합류자가 각자 재시도하면 DB가 힘든 바로
그 순간에 N중 재조립이 터진다 — 실패는 같이 실패하고 클라이언트가 재시도하는 편이 낫다. 실패한
키는 맵에 남지 않으므로 다음 요청은 새로 조립한다.

### 2-4 합류자의 대기 시간

최악은 리더가 막 시작한 직후 합류했을 때로, 조립 1회분(현재 8.4초)이다 — 현행 26초보다 낫다.
톰캣 스레드 점유는 오히려 줄어든다(6건이 각자 DB를 때리는 대신 대기한다).

## §3 버전키를 요청당 1회 계산해 넘긴다

`conditional()`이 이미 계산해 놓고 버리는 버전키가 곧 합류 키다.

```java
private <T> ResponseEntity<T> conditional(long userId, String ifNoneMatch, Function<String, T> body)
```

4개 라우트가 `version -> { var index = coalescer.index(version, userId); ... }` 형태가 된다.
현행처럼 body 안에서 버전키를 다시 계산하면 **같은 요청 안에서 키가 갈릴 수 있고**(자정 경계·
동시 스윕), 그러면 ETag와 바디가 어긋난다. `BrandIndexCache.version()` javadoc이 브랜드 표면에서
못박은 규칙과 같은 이유다.

## §4 공유 안전성 하드닝

`DashboardIndex` 인스턴스 하나를 여러 스레드가 동시에 읽게 되므로 완전 불변이어야 한다. 소비
쪽(`hydratePage`, 컨트롤러 4곳)은 이미 읽기 전용임을 확인했다(2026-08-31 감사). 다만 생성 시
방어 복사가 빠진 두 곳을 막는다:

- `campaignsById` — `Collectors.toMap`의 `HashMap`이 그대로 실린다 → `Map.copyOf`
- `BrandHydration.ownedShortCodes` — 리포지토리 반환 `Set`이 그대로 실린다 → `Set.copyOf`

"감사해봤더니 안전"이 아니라 구조적으로 안전해진다.

## §5 테스트

- **합류**: N스레드가 같은 (userId, version)으로 동시 진입 → `assembler.index` 호출 **정확히 1회**,
  전원 동일 인스턴스(`assertSame`).
- **버전 분리**: 버전이 다르면 각각 조립된다.
- **보관하지 않음**: 순차 호출 2회 → 조립 2회(이 PR이 캐시가 아님을 못박는 테스트).
- **실패**: 리더가 던지면 합류자도 같은 예외를 받고, 맵이 비어 다음 호출은 새로 조립한다.
- **컨트롤러**: 한 요청에서 `DashboardVersion.compute` 1회 호출 / 304 경로에서 합류기·조립 미진입
  (기존 ETag 테스트 연장).
- 회귀: `:was:test` 전체.

## §6 배포 후 검증

Loki `요청 단계 요약`에서 `/v1/performance-dashboard/*`의 `total_ms` 분포를 본다.

- 동시 버스트에서 리더·합류자가 모두 조립 1회분(8초대)으로 수렴하고 **20초대 꼬리가 사라진다**.
- 합류자의 `repo_ms`는 0에 가깝다(자기 스레드에서 리포지토리를 안 탄다) — 합류가 실제로 일어났다는
  직접 증거로 쓴다.

## §7 안 하는 것 · 후속

### 7-1 (후속·본질) 화면 범위로 스코핑 + DB 집계

같은 6브랜드에서 창 크기별 행 수(2026-08-31 운영 실측):

```
180일(현재 조립 범위)  15,177행
 30일                  3,252행
  7일(화면 기본값)        724행   ← 21배
```

FE는 이미 `uploadedFrom=2026-08-25&uploadedTo=2026-08-31`(7일)을 보내는데, 서버는 그 필터를
**인덱스를 다 만든 뒤** 메모리에서 건다. 즉 724행을 보여주려고 15,177행을 끌어와 JDBC로 매핑한다.
`growth`도 마찬가지로 9,718행을 앱으로 가져와 일 버킷 ≤365개로 접는다 — `date_trunc` 집계로 DB에서
접으면 앱이 받는 행이 두 자릿수로 떨어진다. **8초 고정비의 본체가 여기다.**

걸리는 지점: `statusCounts` 모수는 의도적으로 기간·status를 안 건다(현행 계약). 기간을 SQL로 밀면
의미가 바뀌므로 `GROUP BY status` 집계 쿼리로 분리해야 하고, 이는 FE와 합의가 필요한 계약 변경이다.

### 7-2 (비채택) 인덱스 캐시

§2-2 참조. 유저당 1슬롯 + LRU로 웜을 수십 ms로 만들 수 있으나, 콜드를 못 고치고 힙을 100MB 단위로
쓰며 7-1이 오면 대부분 불필요해진다.

### 7-3 (비채택·후속 후보) `findAccount` 배치

활성 링크를 루프 돌며 단건 조회하는 곳이 대시보드 경로에만 4곳이다(`DashboardVersion:226`,
`PerformanceContentAssembler:321`·`:662`, `PerformanceComparisonAssembler:65`). `/comparison`은
요청당 18회(버전 6 + 인덱스 6 + 비교 6)다. `findAccounts(Collection<Long>)` 배치로 2회가 되지만
단건이 몇 ms라 웜 이득은 작고, 7-1에서 이 경로 자체가 바뀔 가능성이 높아 응급 범위에서 뺀다.

### 7-4 (FE) 계정별 N요청

single-flight가 들어가면 FE를 고쳐도 **콜드 8초는 그대로**다(둘 다 중복 조립을 없애는 같은 처방).
남는 이득은 HTTP 6왕복 → 1과 버전키 계산 6회 → 1이라, 성능 레버가 아니라 정리 작업이다. 7-1과
함께 논의한다.
