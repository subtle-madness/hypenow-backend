# caddy 액세스 로그 Loki 수집 설계

> 상태: ✅ 구현됨

## 배경

2026-08-24 blakshave 브랜드 등록 시 사용자가 목격한 "일시적 500"을 조사한 결과, 백엔드(엣지
caddy·was·monitoring 전 계층)에는 5xx가 0건이었다 — 원인은 백엔드 바깥(FE 계층 추정). 조사
과정에서 구조적 사각지대가 확인됐다: **상태코드가 전수 기록되는 유일한 곳은 caddy 액세스
로그인데, 이 로그는 파일(`deploy/logs/caddy/access.log`)로만 남고 Loki로 수집되지 않는다.**
따라서 엣지에서 만들어지는 5xx(예: 롤링 배포 순단의 502)는 어떤 Grafana 패널에도 잡히지 않는다
("API 5xx" 패널은 앱 계층 Prometheus 메트릭, ERROR 패널들은 Loki `level="ERROR"` 로그 기반).

## 목표

운영 caddy 액세스 로그를 Loki로 수집해 엣지 5xx가 대시보드에 보이게 한다.

## 결정 사항 (2026-08-24 유저 확인)

- **수집 범위는 운영만** — `access.log`만. `test-access.log`(dev-api)는 기존 "운영만 계측"
  원칙대로 제외.
- **대시보드 노출까지만** — 디스코드 알림(alert rule)은 발생 빈도를 지켜본 뒤 후속으로.

## 접근 선택

- **A. alloy가 바인드 파일 테일링(채택)** — caddy가 이미 호스트 바인드로 쓰는 파일을 alloy에
  ro 마운트해 `loki.source.file`로 수집. caddy 설정 무변경.
- B. caddy 로그를 stdout에도 복제 — 런타임 로그와 액세스 로그가 한 스트림에 섞이고 디스크
  이중 저장. 기각.
- C. promtail 사이드카 — 수집기가 둘이 됨. 기각.

## 구성 변경 (전부 `deploy/`)

### 1. `alloy/config.alloy` — 파일 파이프라인 추가

- `local.file_match`: 경로 `/var/log/caddy/access.log` **정확 매칭** — 로테이션 산출물
  `access-*.log.gz`·`test-access.log`가 패턴 밖이어야 한다.
- `loki.source.file` → `loki.process`:
  - 고정 라벨 `service="caddy-access"` (기존 caddy stdout 스트림 `service="caddy"`와 분리).
  - `stage.json`으로 `status`·`ts` 추출. status·uri·method는 **라벨로 올리지 않는다**
    (카디널리티) — 조회는 쿼리 시 `| json | status >= 500`.
  - **status 5xx일 때만 `level="ERROR"` 라벨** 부여 → 기존 홈 "ERROR 급증"·인프라 "서비스별
    ERROR" 패널이 추가 작업 없이 엣지 5xx를 잡는다. 4xx 이하는 level 라벨 없음.
  - 타임스탬프는 수집 시각 사용(`stage.timestamp` 없음 — 구현 시 확정): `tail_from_end=true`라
    테일 지연이 ms 단위고, JSON `ts`(epoch float)는 JMESPath 숫자→문자열 변환의 지수 표기
    위험이 있어 파서를 걸 이유가 없다. `tail_from_end`는 기존 파일(~50MiB) 일괄 재수집으로
    "지금" 타임스탬프의 과거 로그가 쏟아지는 것도 함께 막는다.

### 2. `compose.yaml` — alloy 볼륨 2개 추가

- `./logs/caddy:/var/log/caddy:ro` — 입력 파일.
- `alloy-data:/var/lib/alloy/data`(named volume) — **포지션 영속**. 없으면 alloy 재기동
  (배포)마다 access.log 전체(~50MiB)를 재수집해 중복 적재된다. 기존 docker 소스의 재시작
  중복도 같이 해소된다.

### 3. `grafana/provisioning/dashboards/json/hypenow-home.json` — "엣지 5xx" stat 패널 추가

- 쿼리: `sum(count_over_time({service="caddy-access", level="ERROR"}[1h])) or vector(0)`
- 기존 "API 5xx"(앱 계층) 옆에 배치 — "앱은 0인데 엣지만 5xx" = 배포 순단·업스트림 다운을
  바로 분별.

## 에러 처리·경계

- 파일 부재(신규 서버·로그 미생성): `local.file_match`가 조용히 대기 — 무해.
- 로테이션: Caddy roll이 rename 후 새 `access.log` 생성 — file_match 재탐색이 새 파일을 잡고,
  `.gz`는 패턴 밖이라 재수집 없음.
- Loki 불능 시: alloy가 버퍼링·재시도(기존 docker 파이프라인과 동일 특성).

## 검증 (`deploy/alloy/test/` 리그 확장)

- 액세스 로그 픽스처 emitter 추가: 200·500 혼합 JSON 라인을 파일로 기록.
- 리그 compose에 동일 경로 마운트 반영(운영 config.alloy 무수정 원칙 유지).
- 확인 3종:
  1. `{service="caddy-access"}`에 엔트리 존재.
  2. 500 엔트리만 `level=ERROR` 라벨.
  3. 기존 caddy(stdout) 스트림 회귀 없음(엔트리 1줄·level 없음 그대로).

## 스코프 밖

test 도메인(dev-api) 수집, 디스코드 알림 rule, 기존 대시보드 패널 수정(추가만).
