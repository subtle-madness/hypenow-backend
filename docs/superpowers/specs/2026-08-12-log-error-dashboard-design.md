# 로그 기반 에러 추적 대시보드 설계 (Loki + Grafana)

> 상태: 🟢 활성 · 2026-08-12 작성 · 승인됨

## 배경·목적

08-10 스펙([쿼리·API 성능 측정 스택](2026-08-10-query-performance-observability-design.md))으로 PLG
스택(Alloy → Loki → Grafana)이 올라가 운영 컨테이너 로그가 30일 보존된다. 그런데 **로그를 쓰는
대시보드는 아직 0개다** — 기존 두 대시보드(`hypenow-service-overview`, `hypenow-api-performance`)는
각각 Postgres·Prometheus만 본다. 쌓인 로그를 실제로 에러 파악에 쓰는 화면을 만든다.

**용도(합의됨)**: ① 매일 아침 "어젯밤 뭐가 터졌나"를 5분 안에 훑기, ② 장애 감지 시 같은 화면에서
시간축을 좁혀 원인까지 내려가기. 두 동작이 "위에서 아래로 좁혀간다"는 하나의 흐름이라 화면을
나누지 않고 단일 대시보드 Row 4단으로 구성한다.

**범위**: 전 서비스(was·crawler·analytics·monitoring) + 외부 의존 실패(IG·Hiker HTTP 오류).
**범위 밖**: 로그 기반 알람 규칙(먼저 보드를 띄워 실제 에러 분포를 본 뒤 임계값을 정한다 —
기준 없이 거는 알람은 노이즈로 무시된다), 앱 로깅 구조화(logback JSON·MDC traceId), 배포 회귀
감시 전용 보드, 인프라 컨테이너(caddy·postgres·redis) 오류.

## 설계를 규정한 현행 로그 실태

착수 전 확인한 사실 네 가지. 대시보드가 담을 수 있는 내용의 상한을 이것들이 정한다.

1. **스택트레이스가 줄 단위로 쪼개져 적재된다.** `deploy/alloy/config.alloy`에 multiline stage가
   없어 `at com.celfit...` 한 줄 한 줄이 개별 로그 엔트리다. 이 상태로 "에러 건수"를 세면
   스택트레이스 길이에 비례해 부풀고, 예외 클래스별 집계는 아예 불가능하다.
2. **traceId/MDC가 없다.** 코드 전체에 `MDC` 사용처 0건 — 에러 로그와 그 요청(엔드포인트·유저)을
   잇는 열쇠가 없다. 요청 단위 상관관계는 이번 범위에서 포기한다.
3. **로그가 평문 + 한국어 자유 문장이다.** logback 설정 파일 자체가 없어 Spring Boot 기본 콘솔
   포맷을 쓴다. 메시지 텍스트로 그룹핑하면 카디널리티가 터지므로, **그룹핑 키는 축약 로거명**
   (`c.c.m.service.DailySweepJob`)과 예외 클래스명으로 잡는다.
4. **crawler·monitoring은 실패를 대부분 `log.warn`으로 남긴다.** 스윕 실패·댓글 수집 실패·썸네일
   아카이브 실패·프로필 블록(HTTP 4xx)이 전부 WARN이고, monitoring 전체에 `log.error`는 2건뿐이다.
   → **ERROR만 보는 대시보드는 실제 크롤링 사고를 놓친다.** ERROR와 WARN을 두 축으로 병치한다
   (ERROR = "우리 코드가 깨졌다", WARN = "외부가 우릴 막았다" — 성격이 달라 섞지 않는다).

## 1. 로그 파이프라인 변경 (`deploy/alloy/config.alloy`)

### 1-1. 타깃을 JVM/기타 두 갈래로 나눈다

multiline stage는 "firstline 정규식에 안 맞는 줄은 직전 엔트리에 이어붙인다"로 동작한다. 지금처럼
`deploy_prod` 전체를 한 파이프라인에 태우면 caddy(JSON)·postgres·redis처럼 Spring 타임스탬프로
시작하지 않는 로그가 **전부 하나의 거대 엔트리로 뭉친다**. 그래서 JVM 4개만 multiline을 태우고
나머지는 현행대로 직행시킨다.

```
discovery.relabel "jvm"    → deploy_prod keep + container =~ "deploy-(was|crawler|analytics|monitoring)-.*" keep
discovery.relabel "others" → deploy_prod keep + 위 regex drop

loki.source.docker "jvm"    → loki.process.jvm.receiver → loki.write.local
loki.source.docker "others" → loki.write.local (현행 동일)
```

기존 `discovery.relabel "containers"`의 두 rule(prod 네트워크 한정, 컨테이너명 → `container` 라벨)은
양쪽에 그대로 유지한다. prod 한정 rule은 test 스택 유입 차단과 멀티 네트워크 컨테이너 중복 타깃
해소를 동시에 하는 장치라 제거하면 안 된다(원 주석 참조).

### 1-2. `loki.process "jvm"` 스테이지

```
stage.multiline { firstline = "^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}", max_lines = 300, max_wait_time = "3s" }
stage.regex     { expression = "^\S+\s+(?P<level>TRACE|DEBUG|INFO|WARN|ERROR)\s" }
stage.labels    { values = { level = "" } }
```

- `firstline`은 Spring Boot 기본 콘솔 패턴의 ISO-8601 타임스탬프에 맞춘다. 스택트레이스 연속 줄
  (`\tat `, `Caused by:`, `... N more`)은 어느 것도 이 패턴으로 시작하지 않으므로 직전 엔트리에
  병합된다. Go RE2에 부정 전방탐색이 없어 "연속 줄을 직접 매칭"하는 방식은 쓰지 않는다.
- `max_lines = 300`은 비정상적으로 긴 트레이스가 하나의 엔트리를 무한정 키우는 것을 막는 상한이다.
- `level` 파싱 실패 시 라벨이 비게 되므로, 대시보드 쿼리는 `level=~"ERROR|WARN"` 형태로 명시 매칭한다.

### 1-3. 라벨은 `level` 하나만 추가한다

- **라벨화 O — `level`**: 값이 5개뿐이라 스트림 증가가 미미하고(컨테이너 4 × 레벨 5), 이 대시보드
  쿼리 대부분이 "ERROR/WARN만"으로 시작하므로 Loki가 청크 단위로 건너뛴다.
- **라벨화 X — 로거명·예외 클래스**: 값이 수백 개라 라벨로 올리면 스트림이 폭발한다. 쿼리 시점에
  `| regexp`로 뽑는다.

### 1-4. 감수하는 대가

- **이 변경 배포 이전 로그에는 `level` 라벨이 없어 대시보드에 잡히지 않는다.** 최근 24시간을 보는
  용도라 배포 다음날이면 해소되지만, 그 전까지 화면이 비어 보일 수 있다. 대시보드 상단 텍스트
  패널에 이 사실을 적는다.
- 스택트레이스 마지막 줄은 최대 3초(`max_wait_time`) 늦게 도착한다.
- Alloy 파이프라인이 2배로 늘지만 타깃 수는 그대로다(컨테이너당 1타깃 유지).

## 2. 대시보드 (`deploy/grafana/provisioning/dashboards/json/hypenow-errors.json`)

기존 두 대시보드와 같은 파일 기반 프로비저닝(운영 UI 수정 금지 규율 동일). 데이터소스는
`hypenow-loki`(uid) 고정.

- uid: `hypenow-errors` / 제목: `HypeNow 에러`
- 기본 시간범위 `now-6h`, 새로고침 `1m` (패널 12개대라 넓은 범위 기본값은 로딩을 무겁게 한다)

### 2-1. 변수

| 변수 | 종류 | 값 |
|---|---|---|
| `$svc` | custom, multi, include All | `was`, `crawler`, `analytics`, `monitoring` (기본 All) |
| `$level` | custom, multi | `ERROR`, `WARN` (기본 둘 다) |
| `$search` | textbox | 기본 빈값 — 드릴다운 로그 패널의 대소문자 무시 필터 |

컨테이너 셀렉터는 전 패널 공통으로 `{container=~"deploy-($svc)-.*"}`를 쓴다.

### 2-2. Row 1 — 지금 아픈가 (아침엔 여기까지만)

| 패널 | 종류 | 내용 |
|---|---|---|
| ERROR 총건수 | stat | 선택 구간 합계 + 스파크라인 |
| WARN 총건수 | stat | 동일. ERROR와 나란히 둬야 크롤링 사고가 보인다 |
| 서비스별 ERROR | bar gauge | `sum by (container)` |
| ERROR 추이 | timeseries | 서비스별 라인 |
| WARN 추이 | timeseries | 서비스별 라인 (ERROR와 별도 패널 — 자릿수가 달라 한 축에 겹치면 ERROR가 안 보인다) |

### 2-3. Row 2 — 무엇이 터졌나

| 패널 | 종류 | 내용 |
|---|---|---|
| 로거별 Top 15 | table | `\| regexp "(?P<logger>[\w.]+)\s+:\s"` 후 `topk(15, sum by (logger) (count_over_time(...[$__range])))`. 어느 클래스가 시끄러운지가 원인 추적의 첫 갈래다 |
| 예외 클래스별 Top 15 | table | `\| regexp "(?P<exc>[\w.]*(Exception\|Error))"`. multiline 병합 덕에 비로소 가능해지는 패널 |
| 직전 24시간 로거별 Top 15 | table | 동일 쿼리, 시간범위만 `now-24h`로 고정. 위 표와 눈으로 비교해 "새로 생긴 놈"을 잡는다 |

### 2-4. Row 3 — 외부 의존 실패 (crawler·monitoring 한정)

| 패널 | 종류 | 내용 |
|---|---|---|
| HTTP 상태코드별 실패 | timeseries | `\|~ "HTTP [0-9]{3}"` 후 `\| regexp "HTTP (?P<code>[0-9]{3})"` → 코드별 집계 |
| Hiker 402 (잔액 소진) | stat, 임계 빨강 | 1건이라도 뜨면 사람이 충전해야 한다 (08-10 브랜드 스윕 고착 전례) |
| IG 401 (IP 차단) | stat, 임계 빨강 | 요청 폭주로 인한 차단 신호 (07-24 qualify 전면 실패 전례) |
| 크롤 실패 로거 Top | table | crawler·monitoring 한정, WARN 포함 |

### 2-5. Row 4 — 드릴다운

- **Logs 패널**: `{container=~"deploy-($svc)-.*", level=~"$level"} |~ "(?i)$search"` — 최신순 정렬,
  줄바꿈 on, dedup none. `$search`가 빈 문자열이면 필터가 무효가 되도록 쿼리를 구성한다.
- **텍스트 패널**: 다른 두 대시보드로의 링크 + 아래 "한계" 항목 요약.

### 2-6. 어노테이션 — 배포·재기동 마커

Spring Boot 기동 완료 로그(`Started ... in ...s`)를 Loki 어노테이션 쿼리로 걸어 전 패널에 세로줄로
겹친다. 별도 데이터 소스 없이 "언제부터 이 에러가 나기 시작했나"를 배포 시점과 겹쳐 읽을 수 있다.

### 2-7. 의도적으로 뺀 것

- **급증률 자동 계산**: `최근 1h / (직전 24h ÷ 24)` 형태의 이항연산은 신규 등장한 로거에서 분모가
  없어 Loki가 결과를 통째로 버린다 — 정작 가장 보고 싶은 "새 에러"가 사라진다. 표 두 개 병치가
  덜 똑똑하지만 정직하다.
- **Prometheus 5xx율 재게시**: `hypenow-api-performance`에 이미 있고, 로그 집계와 숫자가 구조적으로
  안 맞아(4xx는 로그를 안 남기고, WARN은 5xx가 아니다) 나란히 놓으면 혼란만 커진다. 링크로 연결한다.

## 3. 한계 (대시보드 텍스트 패널에 명시)

- 로그 보관 30일, **운영(`deploy_prod`)만** 수집 — test 스테이징 로그는 여기에 없다.
- 요청 단위 상관관계 불가(traceId 없음) — 에러 로그에서 어떤 엔드포인트·유저였는지 못 짚는다.
- 파이프라인 변경 배포 이전 로그는 `level` 라벨이 없어 잡히지 않는다.
- 로거명·예외 클래스 추출은 정규식 기반이라, 기본 콘솔 패턴을 벗어나는 로그(서드파티 라이브러리
  직접 출력 등)는 누락될 수 있다.

## 4. 검증

1. **로컬**: `deploy/compose.yaml` 스택 기동 → was에 의도적 예외를 발생시켜 Grafana Explore에서
   ① 스택트레이스가 **1건**으로 묶였는지, ② `level="ERROR"` 라벨이 붙었는지 확인.
2. **비-JVM 회귀 확인**: 같은 환경에서 caddy·postgres 로그가 여전히 줄 단위로 정상 적재되는지
   확인한다(1-1 분기가 제대로 걸렸는지 검증하는 핵심 항목 — 실패 시 로그가 거대 엔트리로 뭉친다).
3. **대시보드**: 프로비저닝으로 `HypeNow 에러`가 자동 등록되는지, 전 패널이 No data가 아닌지 확인.
   기존 두 대시보드가 datasource uid/type 문제로 전 패널 No data를 겪은 전례가 세 번 있으므로
   (커밋 `9868fc1b`·`832dd706`·`62f2696b`) 이 항목을 생략하지 않는다.
4. **운영**: develop→staging→main 승격 후, 배포를 1회 거친 뒤 Row 1이 채워지는지와 2-6 배포
   어노테이션이 실제 배포 시점에 그어지는지 확인.

## 5. 변경 파일

| 파일 | 변경 |
|---|---|
| `deploy/alloy/config.alloy` | 타깃 2분기 + `loki.process "jvm"`(multiline·level 라벨) 추가 |
| `deploy/grafana/provisioning/dashboards/json/hypenow-errors.json` | 신규 |
| `deploy/README.md` | 관측 스택 절에 에러 대시보드 항목 추가 |
| `DECISIONS.md` | 결정 1행 추가 |
