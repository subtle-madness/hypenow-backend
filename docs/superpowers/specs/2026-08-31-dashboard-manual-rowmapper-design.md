# 성과 대시보드 8초 고정비 — 원인 실측과 수동 RowMapper 응급 설계

> 상태: ✅ 구현됨 (2026-08-31 작성·구현)
>
> 선행: [2026-08-31 동시 조립 합류 설계](2026-08-31-dashboard-index-coalescing-design.md) §7-1이 남긴
> "조립 1회 8초"의 원인 규명 후속. 사용자 결정(2026-08-31): "구조 개편 전에 원인부터 실측으로
> 판명한다" → 판명 결과 계약·SQL 무변경으로 절반 이상을 걷어낼 수 있어 응급을 먼저 간다.

## §1 진단 — 8.4초의 실측 분해

스테이징 유저 5(브랜드 7연결, monitoring 계정 행 있는 유효 5개, 창 안 16,225행)의 리더 요청
단계 로그(2026-08-31 04:46 UTC, 리더 3건 7.6~8.7초 일관)와, 같은 쿼리를 스테이징 DB에서 직접
실행(EXPLAIN ANALYZE + `\timing`)한 결과를 겹쳐 층을 갈랐다.

```
총 7,662ms (growth 리더 hgioby2o)
├─ JVM (행 매핑·바인드)                        ~5.4s  (71%)
│   ├─ findBrandPostIndex   앱 5,727 − DB 1,780 ≈ 3.9s   ← 16,225행 × 17컬럼 (~240µs/행)
│   ├─ findLatestSnapshots  앱 1,174 − DB   180 ≈ 1.0s   ← ~15,000행 × 7컬럼  (~65µs/행)
│   └─ findAuthors          앱   599 − DB    40 ≈ 0.56s  ← IN 6,633개 바인드 전개 + 6,624행 매핑
├─ DB 실행                                     ~2.0s  (26%)
│   ├─ 협찬 정규식 평가                          ~1.35s  ← b120 850ms + b119 486ms
│   └─ 조인·스캔·DISTINCT ON                     ~0.65s
└─ 전송 + 조립·직렬화                           ~0.2s   (psql 전송 실측 80ms/12MB, "기타" 98ms)
```

### 1-1 JVM 5.4초의 정체 — `SimplePropertyRowMapper`의 값당 리플렉션 매핑

`JdbcClient.query(Class)`가 쓰는 매퍼는 `SimplePropertyRowMapper`다(spring-jdbc 7.0.8
`DefaultJdbcClient` 바이트코드로 확정). `mapRow`가 **행마다** 하는 일:

1. 생성자 파라미터 17개 각각에 대해 `rs.findColumn("shortCode")`(camelCase) — 컬럼은
   snake_case라 **항상 실패** → SQLException을 던지고 잡은 뒤 언더스코어 변환으로 재시도.
   컬럼 인덱스를 행 간에 캐시하지 않아, 요청 하나에 예외가 약 43만 번 생성·폐기된다.
2. 값마다 `JdbcUtils.getResultSetValue` + `ConversionService.convert`(무변환 String→String도 경유).
3. 리플렉션 생성자 호출 + 행마다 `Object[17]`·`HashSet` 할당 + 2차 메타데이터 순회.

같은 쿼리·같은 record의 로컬 대조 실측(M-series, 스테이징 b120 10,429행):

| 경로 | 시간 | 행당 |
|---|---|---|
| raw JDBC 수동 매핑(17컬럼 전부 디코드) | ~22ms | ~2µs |
| Spring `JdbcClient.query(Row.class)` | ~490ms | ~47µs — **20배** |
| 〃 camelCase 별칭(예외 경로 제거 대조군) | ~360ms | 예외 경로 몫 ≈ 오버헤드의 1/4 |

행당 비용이 컬럼 수에 비례하고(17컬럼 240µs/행 vs 7컬럼 65µs/행 → 컬럼당 ~10µs대), 로컬
47µs/행 × 2코어 Ampere A1의 단일코어 열세(~4-5배) = 스테이징 잔차와 정확히 맞는다.
**드라이버 페치·디코드는 사실상 공짜(페치 루프 실측 4ms)이고, 비용 전부가 Spring의 이름 기반
리플렉션 매핑 계층에 있다.**

### 1-2 DB 2.0초의 정체 — 협찬 정규식이 실행 비용의 80%

`findBrandPostIndex` EXPLAIN을 정규식 컬럼 유무로 대조: b120 1,050ms → **200ms**, b119 582ms →
**96ms**. 08-27 P0가 캡션 전송 제거를 위해 SQL로 내린 437자 ARE 정규식이 창 안 전 행의 캡션에
평가된다(행당 ~80µs). 전송 비용을 DB CPU로 바꾼 트레이드오프였는데 모수가 커서 그 CPU 비용
자체가 유의미해졌다. 이 항은 본 응급의 범위 밖이다(§6).

### 1-3 배제한 것 (증거로)

- **커넥션 풀 대기·경합 아님** — 같은 요청의 `findAccount` 14콜이 8ms(경합이면 값싼 콜부터
  부푼다 — 26초 사고의 판정법 역적용).
- **전송 아님** — psql 실측 ~80ms/12MB.
- **브랜드당 DB 고정비 아님** — 작은 브랜드(329행)는 플래너가 네스티드 루프를 골라 89ms.
- **DB 기본 실행 아님** — 정규식 제외 시 16k행 쿼리가 ~0.4s.

## §2 결정 요약

| # | 항목 | 결정 |
|---|---|---|
| P0 | 대상 | **실측된 3개만**: `findBrandPostIndex` · `findLatestSnapshotsForBrand` · `findAuthors` (전부 `BrandReadRepository`) — 증거 있는 곳만 건드는 응급 |
| P0 | 방식 | `.query(XxxRow.class)` → **컬럼명 기반 수동 람다 매퍼**. SQL·record·메서드 시그니처·소비자 무변경 |
| P1 | findAuthors 바인드 전개 | **이번엔 안 고친다**(사용자 결정) — IN 6,633개 파라미터의 파스·플랜 ~0.4s는 남는다. `= ANY(배열)` 전환은 후속 후보(§6) |
| — | 비채택 | 정규식(§6-1), 다른 쿼리 확산, 공용 매퍼 유틸(3곳에 유틸은 과잉) |

수혜 표면: 매퍼가 리포지토리 메서드 안이라 성과 대시보드 4표면뿐 아니라 **브랜드 목록·브랜드
인플루언서 표면(`BrandIndexCache` 경유 동일 쿼리)도 같이 빨라진다** — 소비자는 손대지 않는다.

## §3 설계

변경 파일 1개: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java`.

```java
.query((rs, i) -> new BrandPostIndexRow(
        rs.getString("short_code"),
        rs.getObject("taken_at", OffsetDateTime.class),
        // ...
        rs.getObject("author_followers", Long.class)))
```

- **컬럼명 기반**(포지셔널 아님) — 컬럼당 해시 조회 하나 더 들지만 여전히 raw 수준(~3µs/행)이고,
  SELECT 절 순서 변경에 안전하며 기존 레포 람다 관용구(`V1SavedRepository` 등)와 같다.
- **널러블 박싱 타입**(`Long`·`Boolean`)은 `rs.getObject(col, Long.class)` — 널 안전.
- **원시 `boolean`**(`captionMarker`·`likesHidden`)은 `rs.getBoolean` — `caption_marker`는 SQL 식
  구조상 널 불가(`FALSE AND NULL = FALSE`), `likes_hidden`은 NOT NULL 컬럼. 종전
  매퍼는 이 자리에 널이 오면 예외였고 `getBoolean`은 false로 접는다 — 도달 불가지만 더 관대한
  쪽으로의 변화라 회귀 위험이 없다.
- **`OffsetDateTime`**은 `rs.getObject(col, OffsetDateTime.class)` — Spring 경로가 내부적으로
  쓰는 것과 같은 드라이버 API라 값 동일.
- 각 메서드 javadoc에 근거 한 줄: 대량 행 쿼리라 수동 매핑(`SimplePropertyRowMapper` 행당
  ~47µs 실측, 본 설계 §1-1).

## §4 테스트

- 기존 Testcontainers 통합 테스트가 세 쿼리를 실데이터 셰이프로 태운다 — 그대로 회귀 검증.
- 매퍼 교체의 유일한 실질 위험은 **널 처리의 조용한 변화**다: 널 포함 행(메타 없는 LEFT JOIN
  미스 → `is_paid_partnership`·`content_type` 등 널, `author_followers` 널, 작성자 조인 미스)의
  매핑을 못박는 리포지토리 테스트가 없으면 추가한다.
- 회귀: `:was:test` 전체(PR 전).

## §5 배포 후 검증

develop 머지 → 스테이징 배포 → 유저 5(브랜드 7연결)로 대시보드 요청 후
`docker logs deploy-test-was-1`의 `요청 단계` 로그 전후 비교(스테이징은 Loki에 없다 — alloy가
`test-*`를 drop).

| 단계 | 전(실측) | 후(기대) |
|---|---|---|
| `findBrandPostIndex` (5콜) | 5,727ms | ~1.9s (DB 실행+전송만) |
| `findLatestSnapshotsForBrand` (5콜) | 1,174ms | ~0.25s |
| `findAuthors` (1콜) | 599ms | ~0.45s (바인드 전개 잔존) |
| **요청 전체** | **8.4s** | **~3.5s** |

조립이 여전히 1초를 넘어 `SlowRequestStageLogFilter` 로그가 계속 남는다 — 전후 비교 가능.
운영 반영 후엔 Loki `요청 단계 요약`의 `/v1/performance-dashboard/*` total_ms 분포로 확인.

## §6 안 하는 것 · 후속

### 6-1 (후속·본체) 화면 범위 스코핑 + DB 집계

남는 ~3.5s의 본체는 정규식 ~1.35s + DB 실행 ~0.65s + findAuthors 바인드 ~0.4s — 전부 행수에
선형이라, 모수를 화면 필요치(7일 724행, 현행의 1/21)로 줄이는 스코핑이 오면 함께 준다.
statusCounts 모수 계약·ETag·FE N요청 논의 포함(선행 설계 §7-1). 본 응급과 독립.

### 6-2 (후속 후보) findAuthors `= ANY(배열)` 바인드

파라미터 6,633개 → 1개. 이번에 안 넣은 이유: 응급의 변경 표면 최소화(사용자 결정). 스코핑이
오면 id 목록 자체가 줄어 우선순위가 더 내려간다.

### 6-3 (비채택) 정규식 SQL 평가 개선

마커 컬럼 사전 계산(저장)은 "키워드 개선이 과거분에 즉시 소급"하는 현행 설계와 충돌한다.
스코핑이 오면 평가 행수가 1/21이 되어 별도 처치가 불필요할 가능성이 높다.
