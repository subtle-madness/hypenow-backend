# 로그 기반 에러 추적 대시보드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 컨테이너 로그(Loki)를 읽어 "어젯밤 뭐가 터졌나"를 5분에 훑고 그 자리에서 원인까지 내려가는 Grafana 대시보드 `HypeNow 에러`를 만든다.

**Architecture:** Alloy 수집 파이프라인을 JVM/비-JVM 두 갈래로 나눠 JVM 쪽에만 multiline 병합(스택트레이스 1건화)과 `level`·`service` 라벨을 붙인다. 그 위에 단일 대시보드를 Row 4단(요약 → 유형 → 외부의존 → 드릴다운)으로 얹는다. 앱 코드는 건드리지 않는다 — 전부 `deploy/` 아래 설정 파일 변경이다.

**Tech Stack:** Grafana Alloy v1.8.2 (`loki.process`/`discovery.relabel`), Loki 3.5.0 (LogQL), Grafana 13.1.1 (파일 기반 프로비저닝, JSON 대시보드)

**설계 스펙:** [docs/superpowers/specs/2026-08-12-log-error-dashboard-design.md](../specs/2026-08-12-log-error-dashboard-design.md) — 본문 + 말미 "부록: 착수 전 실측 보정"까지 읽을 것. 부록이 본문 §2를 세 군데 덮어쓴다.

## Global Constraints

- **앱 코드(`was/`·`crawler/`·`analytics/`·`monitoring/`)를 수정하지 않는다.** 이번 범위는 `deploy/` 설정과 문서뿐이다. Gradle 빌드·테스트를 돌릴 일이 없다.
- **Loki 라벨로 새로 만드는 것은 `level`과 `service` 둘뿐이다.** 로거명·예외 클래스·HTTP 코드는 반드시 쿼리 시점 `| regexp` 추출로 남긴다(라벨화하면 스트림이 폭발한다).
- **`service` 라벨 값은 정확히 `was`·`crawler`·`analytics`·`monitoring` 4개다.** 대시보드 전 쿼리는 `container`가 아니라 `service`로 그룹핑한다 — 운영 was 컨테이너명이 `deploy-was-38`처럼 배포마다 바뀌기 때문이다.
- **대시보드 `refresh`는 `"5m"`.** `deploy/compose.yaml` grafana 서비스 주석의 "1분 미만 금지 · 5분 고정"(2코어 서버 보호) 규율을 따른다.
- **대시보드 JSON 고정값**: `"uid": "hypenow-errors"`, `"title": "HypeNow 에러"`, `"schemaVersion": 39`, `"editable": false`, `"timezone": "Asia/Seoul"`, `"time": {"from": "now-6h", "to": "now"}`. 데이터소스는 전 패널 `{"type": "loki", "uid": "hypenow-loki"}`.
- **기존 `discovery.relabel "containers"`의 두 rule을 삭제하지 않는다** — `deploy_prod` 네트워크 keep(test 스택 유입 차단 + 멀티 네트워크 컨테이너 중복 타깃 해소)과 컨테이너명 → `container` 라벨. 둘 다 새 구조로 옮겨 살린다.
- 주석·커밋 메시지는 한국어. 커밋 prefix는 `feat(deploy):` / `docs:`.
- 브랜치는 현재 worktree의 `feature/plg-error-tracking-dashboard-2b6726`. develop 대상 PR로 합친다.

## File Structure

| 파일 | 책임 |
|---|---|
| `deploy/alloy/config.alloy` (수정) | 도커 로그 수집 파이프라인. JVM/비-JVM 분기, multiline 병합, `level`·`service` 라벨 부여 |
| `deploy/alloy/test/compose.test.yaml` (신규) | 로컬 검증 리그 — 운영과 같은 컨테이너 이름·네트워크 이름을 흉내낸 로그 발생기 + loki + alloy + grafana |
| `deploy/alloy/test/fixtures/emit-was.sh` (신규) | was 실측 포맷 로그 발생기(정상 INFO + ERROR + 스택트레이스 + `Caused by`) |
| `deploy/alloy/test/fixtures/emit-monitoring.sh` (신규) | monitoring 실측 포맷 로그 발생기(Hiker 404/402, IG 401 WARN) |
| `deploy/alloy/test/fixtures/emit-caddy.sh` (신규) | 비-JVM(JSON) 로그 발생기 — 분기 회귀 확인용 |
| `deploy/alloy/test/README.md` (신규) | 리그 사용법·검증 명령·주의(서버 실행 금지) |
| `deploy/grafana/provisioning/dashboards/json/hypenow-errors.json` (신규) | 대시보드 전체. Row 4단·패널 14개·변수 3개·배포 어노테이션 |
| `deploy/README.md` (수정) | §16 신설 — 에러 대시보드 읽는 법과 한계 |
| `DECISIONS.md` (수정) | 결정 1행 추가(맨 위) |

---

### Task 1: Alloy 파이프라인 — multiline 병합 + `level`·`service` 라벨

**Files:**
- Create: `deploy/alloy/test/compose.test.yaml`
- Create: `deploy/alloy/test/fixtures/emit-was.sh`
- Create: `deploy/alloy/test/fixtures/emit-monitoring.sh`
- Create: `deploy/alloy/test/fixtures/emit-caddy.sh`
- Create: `deploy/alloy/test/README.md`
- Modify: `deploy/alloy/config.alloy` (전면 재작성 — 43줄)

**Interfaces:**
- Consumes: 없음(첫 태스크)
- Produces: Loki 스트림 라벨 `service`(값: `was`|`crawler`|`analytics`|`monitoring`), `level`(값: `TRACE`|`DEBUG`|`INFO`|`WARN`|`ERROR`), `container`(기존 유지). Task 2·3의 모든 LogQL이 이 세 라벨에 의존한다.
- Produces: 로컬 검증 리그 — `docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml up -d` 로 뜨고, Loki는 `http://localhost:3100`, Grafana는 `http://localhost:3000`(익명 Admin). Task 2·3이 대시보드 확인에 그대로 재사용한다.

- [ ] **Step 1: 로그 발생기 3종 작성**

`deploy/alloy/test/fixtures/emit-was.sh` — 운영 was 로그 실측 포맷(2026-08-12 수집)을 재현한다. 정상 INFO 1줄, ERROR 1줄 + 스택트레이스 4줄이 한 사이클이다. 병합이 되면 6줄이 2건, 안 되면 6건이 된다.

```sh
#!/bin/sh
# was 로그 실측 포맷 재현. 한 사이클 = INFO 1건 + (ERROR 헤더 + 스택트레이스 5줄) 1건.
while true; do
  TS=$(date -u +%Y-%m-%dT%H:%M:%S)
  echo "${TS}.374Z  INFO 1 --- [was] [nio-8081-exec-2] c.c.w.v.b.V1BrandAccountService          : 브랜드 계정 삭제 — 브랜드 연결만 해제 brandId=2"
  echo "${TS}.512Z ERROR 1 --- [was] [nio-8081-exec-7] c.c.w.v.c.V1ExceptionAdvice              : v1 처리 실패"
  echo "java.lang.IllegalStateException: 리그 테스트용 예외"
  printf '\tat com.celfit.was.v1.brand.V1BrandAccountService.delete(V1BrandAccountService.java:42)\n'
  printf '\tat com.celfit.was.v1.brand.V1BrandAccountController.delete(V1BrandAccountController.java:31)\n'
  echo "Caused by: java.sql.SQLException: 커넥션 없음"
  printf '\t... 3 more\n'
  sleep 10
done
```

`deploy/alloy/test/fixtures/emit-monitoring.sh` — Row 3(외부 의존 실패) 패널을 검증할 데이터를 만든다. Hiker 404·402와 IG 401을 실측 문구 그대로 흉내낸다.

```sh
#!/bin/sh
# monitoring 로그 실측 포맷 재현 — 외부 의존 실패 3종(Hiker 404 / Hiker 402 / IG HTTP 401).
while true; do
  TS=$(date -u +%Y-%m-%dT%H:%M:%S)
  echo "${TS}.073Z  WARN 1 --- [monitoring] [enrich-worker-5] c.celfit.monitoring.hiker.HikerClient    : 댓글 2페이지 실패 — 받은 14건은 보존(미완주): media 3910608935035842863 com.celfit.monitoring.hiker.SubjectNotFoundException: Hiker 404: {\"detail\":\"Entries not found\"}"
  echo "${TS}.181Z  WARN 1 --- [monitoring] [enrich-worker-3] c.celfit.monitoring.hiker.HikerClient    : 브랜드 프로필 조회 실패 — Hiker 402: 잔액 소진"
  echo "${TS}.264Z  WARN 1 --- [monitoring] [   sweep-worker] c.c.m.service.DailySweepJob              : 프로필 블록(HTTP 401) — 스킵"
  sleep 10
done
```

`deploy/alloy/test/fixtures/emit-caddy.sh` — 비-JVM 분기 회귀 확인용. Spring 타임스탬프로 시작하지 않는 JSON 로그다. 이 줄들이 서로 뭉치면 분기가 잘못 걸린 것이다.

```sh
#!/bin/sh
# 비-JVM(caddy JSON) 로그 — multiline 파이프라인을 타면 안 되는 쪽.
while true; do
  echo '{"level":"info","logger":"http.log.access","msg":"handled request","status":200}'
  echo '{"level":"error","logger":"http.log.access","msg":"handled request","status":502}'
  sleep 10
done
```

- [ ] **Step 2: 검증 리그 compose 작성**

`deploy/alloy/test/compose.test.yaml`. 컨테이너 이름을 `deploy-was-1`·`deploy-monitoring-1`·`deploy-caddy-1`로 **고정**하고 네트워크 이름을 `deploy_prod`로 고정해, 운영 `config.alloy`의 relabel 규칙이 수정 없이 그대로 걸리게 한다. loki·alloy·grafana는 `alloytest-` 접두로 이름을 달리해 프로젝트가 섞여도 운영 컨테이너를 건드릴 수 없게 한다.

```yaml
# config.alloy 검증 리그 — 운영의 컨테이너 이름·네트워크 이름을 흉내내 relabel 규칙을 그대로 태운다.
#
# ⚠️ 로컬 맥 전용. 서버(운영 도커)에서 실행 금지 — deploy-was-1 등 운영과 같은 이름 공간을 쓴다.
#
#   docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml up -d
#   docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml down -v
networks:
  prod:
    name: deploy_prod

services:
  # 이름이 곧 테스트 입력이다 — config.alloy의 service 라벨 규칙 /deploy-<서비스>-<번호>에 걸려야 한다.
  fake-was:
    image: alpine:3.20
    container_name: deploy-was-1
    networks: [prod]
    entrypoint: ["sh", "/fixtures/emit-was.sh"]
    volumes:
      - ./fixtures:/fixtures:ro

  fake-monitoring:
    image: alpine:3.20
    container_name: deploy-monitoring-1
    networks: [prod]
    entrypoint: ["sh", "/fixtures/emit-monitoring.sh"]
    volumes:
      - ./fixtures:/fixtures:ro

  # 비-JVM 분기 회귀 확인용 — 이 컨테이너 로그는 multiline 파이프라인을 타면 안 된다.
  fake-caddy:
    image: alpine:3.20
    container_name: deploy-caddy-1
    networks: [prod]
    entrypoint: ["sh", "/fixtures/emit-caddy.sh"]
    volumes:
      - ./fixtures:/fixtures:ro

  # 서비스 키는 loki로 둔다 — config.alloy의 loki.write url(http://loki:3100)이 이 이름으로 붙는다.
  loki:
    image: grafana/loki:3.5.0
    container_name: alloytest-loki
    networks: [prod]
    ports:
      - "127.0.0.1:3100:3100"
    command: ["-config.file=/etc/loki/loki-config.yaml"]
    volumes:
      - ../../loki/loki-config.yaml:/etc/loki/loki-config.yaml:ro

  alloy:
    image: grafana/alloy:v1.8.2
    container_name: alloytest-alloy
    networks: [prod]
    command: ["run", "/etc/alloy/config.alloy", "--storage.path=/var/lib/alloy/data"]
    volumes:
      - ../config.alloy:/etc/alloy/config.alloy:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro

  # 대시보드 JSON 확인용(Task 2·3). 운영과 달리 익명 Admin — 로컬 루프백 전용이라 인증 불필요.
  # 프로비저닝은 Loki 데이터소스와 대시보드만 마운트한다(postgres 데이터소스·알림 규칙은
  # DB가 없어 기동 로그에 에러를 남기므로 의도적으로 제외).
  grafana:
    image: grafana/grafana:13.1.1
    container_name: alloytest-grafana
    networks: [prod]
    ports:
      - "127.0.0.1:3000:3000"
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Admin
      GF_ANALYTICS_REPORTING_ENABLED: "false"
      GF_ANALYTICS_CHECK_FOR_UPDATES: "false"
    volumes:
      - ../../grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards:ro
      - ../../grafana/provisioning/datasources/observability.yaml:/etc/grafana/provisioning/datasources/observability.yaml:ro
```

- [ ] **Step 3: 리그를 현행 config로 띄워 "깨진 상태"를 눈으로 확인 (실패 확인)**

```bash
chmod +x deploy/alloy/test/fixtures/*.sh
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml up -d
sleep 45
```

현행 `config.alloy`에는 `service`·`level` 라벨이 없고 multiline도 없다. 세 가지를 확인한다.

```bash
# ① service 라벨이 아예 없어야 한다 (아직 안 만들었으므로)
curl -s 'http://localhost:3100/loki/api/v1/labels'

# ② was ERROR가 스택트레이스 줄 수만큼 쪼개져 있어야 한다
curl -sG 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={container="deploy-was-1"}' --data-urlencode 'limit=200' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]["result"]; print("총 엔트리:", sum(len(s["values"]) for s in d))'
```

Expected: ① 라벨 목록에 `service`·`level`이 **없다**(`container`·`job` 등만).
② 한 사이클이 7줄이므로 총 엔트리가 사이클 수 × 7. **이것이 고쳐야 할 문제다** — ERROR 1건이 6건으로 세어진다.

- [ ] **Step 4: `config.alloy` 재작성**

`deploy/alloy/config.alloy` 전체를 아래로 교체한다.

```river
// 도커 로그 수집(스펙 2026-08-10 · 에러 대시보드 스펙 2026-08-12) — prod 네트워크(deploy_prod)
// 컨테이너의 stdout/stderr만 Loki로 push.
//
// 파이프라인이 두 갈래인 이유: JVM 4종에만 multiline 병합을 걸어야 하기 때문이다. multiline은
// "firstline 정규식에 안 맞는 줄은 직전 엔트리에 이어붙인다"로 동작하는데, caddy(JSON)·postgres·
// redis 로그는 Spring 타임스탬프로 시작하지 않아 한 파이프라인에 태우면 전부 하나의 거대
// 엔트리로 뭉친다.

discovery.docker "containers" {
  host             = "unix:///var/run/docker.sock"
  refresh_interval = "30s"
}

// 공통 전처리 — prod 한정 + container 라벨 + service 라벨.
discovery.relabel "prod" {
  targets = discovery.docker.containers.targets

  // prod 네트워크 소속만 수집 — 두 가지를 동시에 처리한다.
  //  ① test 스택(test-was 등) 로그 유입 차단: 스펙상 계측 범위는 운영뿐인데, 도커 소켓을 통째로
  //     열거하면 같은 호스트의 test 컨테이너까지 딸려와 Loki 보관량과 라벨을 오염시킨다.
  //  ② 중복 타깃 해소: discovery.docker는 컨테이너×네트워크로 타깃을 만들어 멀티 네트워크
  //     컨테이너(caddy = prod+test, was = prod+monitoring-net 등)가 2개로 잡힌다 — 같은 로그를
  //     두 번 push하는 경로다. prod 하나로 한정해 컨테이너당 1타깃을 보장한다.
  rule {
    source_labels = ["__meta_docker_network_name"]
    regex         = "deploy_prod"
    action        = "keep"
  }

  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container"
  }

  // 배포마다 바뀌는 복제본 번호를 흡수하는 안정 라벨(2026-08-12). was는 롤링 배포가 번호를
  // 올려 운영 컨테이너명이 deploy-was-38 → 39로 계속 바뀐다 — container로 그룹핑하면 배포할
  // 때마다 시계열이 끊기고 죽은 스트림이 30일간 누적된다. 값이 4개뿐이라 카디널리티는 무해.
  // container 라벨은 롤링 중 신·구 복제본 구분용으로 그대로 남긴다.
  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/deploy-(was|crawler|analytics|monitoring)-[0-9]+"
    target_label  = "service"
    replacement   = "$1"
  }
}

// JVM 4종 — multiline 병합 + level 라벨을 받는 쪽.
discovery.relabel "jvm" {
  targets = discovery.relabel.prod.output

  rule {
    source_labels = ["service"]
    regex         = "was|crawler|analytics|monitoring"
    action        = "keep"
  }
}

// 그 외(caddy·postgres·redis·prometheus·loki·alloy·grafana·ons-relay) — 현행 그대로 직행.
// service 라벨이 비어 있어 위 regex에 걸리지 않는다.
discovery.relabel "others" {
  targets = discovery.relabel.prod.output

  rule {
    source_labels = ["service"]
    regex         = "was|crawler|analytics|monitoring"
    action        = "drop"
  }
}

loki.source.docker "jvm" {
  host             = "unix:///var/run/docker.sock"
  targets          = discovery.relabel.jvm.output
  forward_to       = [loki.process.jvm.receiver]
  refresh_interval = "30s"
}

loki.process "jvm" {
  // Spring Boot 기본 콘솔 패턴의 ISO-8601 타임스탬프로 시작하는 줄이 엔트리의 첫 줄이다.
  // 스택트레이스 연속 줄("\tat ", "Caused by:", "\t... N more")은 어느 것도 이 패턴으로
  // 시작하지 않아 직전 엔트리에 병합된다. Go RE2에 부정 전방탐색이 없어 "연속 줄을 직접
  // 매칭"하는 방식은 쓸 수 없다. max_lines는 비정상적으로 긴 트레이스가 한 엔트리를 무한정
  // 키우는 것을 막는 상한, max_wait_time만큼 마지막 줄의 도착이 늦어진다.
  stage.multiline {
    firstline     = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
    max_lines     = 300
    max_wait_time = "3s"
  }

  // 레벨은 타임스탬프 다음 토큰이다: "2026-08-12T05:11:01.374Z  INFO 1 --- [was] ...".
  // 파싱 실패 시 라벨이 비므로 대시보드 쿼리는 level=~"ERROR|WARN" 식으로 명시 매칭한다.
  stage.regex {
    expression = "^\\S+\\s+(?P<level>TRACE|DEBUG|INFO|WARN|ERROR)\\s"
  }

  stage.labels {
    values = {
      level = "",
    }
  }

  forward_to = [loki.write.local.receiver]
}

loki.source.docker "others" {
  host             = "unix:///var/run/docker.sock"
  targets          = discovery.relabel.others.output
  forward_to       = [loki.write.local.receiver]
  refresh_interval = "30s"
}

loki.write "local" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"
  }
}
```

- [ ] **Step 5: 문법 검증**

```bash
docker run --rm -v "$PWD/deploy/alloy/config.alloy:/c.alloy:ro" grafana/alloy:v1.8.2 fmt /c.alloy > /dev/null && echo "문법 OK"
```

Expected: `문법 OK`. 실패하면 stderr에 줄 번호가 찍힌다 — 고치고 다시 돌린다.

- [ ] **Step 6: 리그를 새 config로 재기동하고 통과 확인**

```bash
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml down -v
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml up -d
sleep 45
```

검증 3종:

```bash
# ① service 라벨이 생겼고 값이 was·monitoring 두 개뿐인가 (caddy는 없어야 한다)
curl -s 'http://localhost:3100/loki/api/v1/label/service/values'

# ② was ERROR가 1건으로 묶였고 그 1건이 스택트레이스 전체를 담는가
curl -sG 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service="was", level="ERROR"}' --data-urlencode 'limit=5' \
  | python3 -c '
import json,sys
r = json.load(sys.stdin)["data"]["result"]
line = r[0]["values"][0][1]
print("엔트리 줄 수:", len(line.splitlines()))
print("IllegalStateException 포함:", "IllegalStateException" in line)
print("... 3 more 포함:", "... 3 more" in line)
'

# ③ 비-JVM은 여전히 줄 단위인가 (뭉쳤으면 분기가 잘못 걸린 것)
curl -sG 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={container="deploy-caddy-1"}' --data-urlencode 'limit=50' \
  | python3 -c '
import json,sys
r = json.load(sys.stdin)["data"]["result"]
vals = [v[1] for s in r for v in s["values"]]
print("엔트리 수:", len(vals))
print("한 엔트리에 2줄 이상 뭉친 것:", sum(1 for v in vals if len(v.splitlines()) > 1))
'
```

Expected:
- ① `["monitoring","was"]`
- ② `엔트리 줄 수: 6` / `IllegalStateException 포함: True` / `... 3 more 포함: True`
- ③ `엔트리 수:` 는 사이클 수 × 2, `한 엔트리에 2줄 이상 뭉친 것: 0`

③이 0이 아니면 `discovery.relabel "others"`의 drop 규칙이 안 걸린 것이다 — `docker logs alloytest-alloy`로 타깃 수를 확인한다.

- [ ] **Step 7: 리그 README 작성**

`deploy/alloy/test/README.md`(바깥 울타리는 4중 백틱 — 내용에 코드 블록이 들어 있어서다):

````markdown
# Alloy 로그 파이프라인 검증 리그

`deploy/alloy/config.alloy`를 **수정 없이** 로컬에서 태워보는 임시 스택이다. 컨테이너 이름
(`deploy-was-1`·`deploy-monitoring-1`·`deploy-caddy-1`)과 네트워크 이름(`deploy_prod`)을 운영과
똑같이 맞춰, relabel 규칙과 multiline 병합이 실제로 걸리는지 확인한다.

> ⚠️ **로컬 맥 전용. 서버에서 실행 금지** — 운영과 같은 이름 공간을 쓴다.

## 기동·정리

```bash
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml up -d    # 기동
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml down -v  # 정리(볼륨까지)
```

기동 후 40초쯤 지나야 첫 로그가 Loki에 들어온다(alloy `refresh_interval` 30s + 발생기 주기 10s).

- Loki API: http://localhost:3100
- Grafana: http://localhost:3000 (익명 Admin, 프로비저닝된 대시보드가 그대로 뜬다)

## 검증 3종

| 확인 | 쿼리 | 기대 |
|---|---|---|
| `service` 라벨 | `/loki/api/v1/label/service/values` | `["monitoring","was"]` — 비-JVM(caddy)엔 없다 |
| 스택트레이스 병합 | `{service="was", level="ERROR"}` | 엔트리 1건이 6줄(`IllegalStateException`~`... 3 more`)을 통째로 담는다 |
| 비-JVM 회귀 | `{container="deploy-caddy-1"}` | 전 엔트리가 1줄. 2줄 이상이면 분기가 잘못 걸린 것 |

## 로그 발생기

`fixtures/emit-*.sh`는 2026-08-12 운영 서버에서 실측한 로그 포맷을 그대로 재현한다. 포맷이
바뀌면(로깅 설정 변경 등) 여기 픽스처부터 갱신해야 검증이 의미를 유지한다.
````

- [ ] **Step 8: 커밋**

```bash
git add deploy/alloy/config.alloy deploy/alloy/test
git commit -m "feat(deploy): Alloy 로그 파이프라인에 multiline 병합·level·service 라벨 추가

스택트레이스가 줄마다 개별 엔트리로 적재돼 에러 1건이 수십 건으로
세어지던 문제를 JVM 4종 전용 multiline 병합으로 해결한다. 비-JVM
로그가 거대 엔트리로 뭉치지 않도록 파이프라인을 두 갈래로 나눈다.

롤링 배포마다 바뀌는 was 컨테이너명(deploy-was-38)을 흡수하는 안정
service 라벨을 도입해 배포 시점에 시계열이 끊기지 않게 한다.

로컬 검증 리그(deploy/alloy/test)를 함께 넣어 운영 이름 공간을 흉내낸
상태로 relabel·병합·비-JVM 회귀를 실측 확인할 수 있게 했다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: 대시보드 Row 1·2 — 요약과 유형

**Files:**
- Create: `deploy/grafana/provisioning/dashboards/json/hypenow-errors.json`

**Interfaces:**
- Consumes: Task 1의 `service`·`level`·`container` 라벨, 검증 리그(`-p alloytest`).
- Produces: 변수 `$svc`(multi, allValue `was|crawler|analytics|monitoring`)·`$level`(multi)·`$search`(textbox). Task 3의 패널들이 같은 변수를 쓴다. 패널 `id`는 1~10을 쓴다 — Task 3은 11부터 이어 붙인다.

- [ ] **Step 1: 대시보드 JSON 작성 (Row 1·2까지)**

`deploy/grafana/provisioning/dashboards/json/hypenow-errors.json`:

```json
{
  "title": "HypeNow 에러",
  "uid": "hypenow-errors",
  "schemaVersion": 39,
  "editable": false,
  "refresh": "5m",
  "timezone": "Asia/Seoul",
  "time": { "from": "now-6h", "to": "now" },
  "templating": {
    "list": [
      {
        "name": "svc",
        "label": "서비스",
        "type": "custom",
        "query": "was,crawler,analytics,monitoring",
        "multi": true,
        "includeAll": true,
        "allValue": "was|crawler|analytics|monitoring",
        "current": { "text": ["All"], "value": ["$__all"] },
        "options": []
      },
      {
        "name": "level",
        "label": "레벨",
        "type": "custom",
        "query": "ERROR,WARN",
        "multi": true,
        "includeAll": false,
        "current": { "text": ["ERROR", "WARN"], "value": ["ERROR", "WARN"] },
        "options": []
      },
      {
        "name": "search",
        "label": "검색어(정규식)",
        "type": "textbox",
        "query": "",
        "current": { "text": "", "value": "" },
        "options": []
      }
    ]
  },
  "panels": [
    {
      "id": 1,
      "type": "row",
      "title": "1. 지금 아픈가",
      "collapsed": false,
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 0 },
      "panels": []
    },
    {
      "id": 2,
      "type": "stat",
      "title": "ERROR 총건수",
      "description": "선택한 시간 범위의 ERROR 로그 엔트리 수. 스택트레이스는 1건으로 묶여 세어진다.",
      "gridPos": { "h": 5, "w": 4, "x": 0, "y": 1 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "color": { "mode": "thresholds" },
          "thresholds": {
            "mode": "absolute",
            "steps": [{ "color": "green", "value": null }, { "color": "red", "value": 1 }]
          }
        },
        "overrides": []
      },
      "options": {
        "graphMode": "area",
        "colorMode": "value",
        "reduceOptions": { "calcs": ["sum"], "fields": "", "values": false }
      },
      "targets": [
        {
          "refId": "A",
          "queryType": "range",
          "expr": "sum(count_over_time({service=~\"${svc:regex}\", level=\"ERROR\"}[$__auto]))"
        }
      ]
    },
    {
      "id": 3,
      "type": "stat",
      "title": "WARN 총건수",
      "description": "crawler·monitoring은 외부 의존 실패를 대부분 WARN으로 남긴다 — ERROR만 보면 크롤링 사고를 놓친다.",
      "gridPos": { "h": 5, "w": 4, "x": 4, "y": 1 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "color": { "mode": "fixed", "fixedColor": "orange" }
        },
        "overrides": []
      },
      "options": {
        "graphMode": "area",
        "colorMode": "value",
        "reduceOptions": { "calcs": ["sum"], "fields": "", "values": false }
      },
      "targets": [
        {
          "refId": "A",
          "queryType": "range",
          "expr": "sum(count_over_time({service=~\"${svc:regex}\", level=\"WARN\"}[$__auto]))"
        }
      ]
    },
    {
      "id": 4,
      "type": "bargauge",
      "title": "서비스별 ERROR",
      "gridPos": { "h": 5, "w": 16, "x": 8, "y": 1 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": {
        "defaults": { "unit": "short", "color": { "mode": "palette-classic" } },
        "overrides": []
      },
      "options": {
        "displayMode": "gradient",
        "orientation": "horizontal",
        "reduceOptions": { "calcs": ["lastNotNull"], "fields": "", "values": false }
      },
      "targets": [
        {
          "refId": "A",
          "queryType": "instant",
          "legendFormat": "{{service}}",
          "expr": "sum by (service) (count_over_time({service=~\"${svc:regex}\", level=\"ERROR\"}[$__range]))"
        }
      ]
    },
    {
      "id": 5,
      "type": "timeseries",
      "title": "ERROR 추이",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 6 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": {
        "defaults": { "unit": "short", "custom": { "fillOpacity": 10 } },
        "overrides": []
      },
      "targets": [
        {
          "refId": "A",
          "queryType": "range",
          "legendFormat": "{{service}}",
          "expr": "sum by (service) (count_over_time({service=~\"${svc:regex}\", level=\"ERROR\"}[$__auto]))"
        }
      ]
    },
    {
      "id": 6,
      "type": "timeseries",
      "title": "WARN 추이",
      "description": "ERROR와 자릿수가 달라 한 축에 겹치면 ERROR가 안 보인다 — 의도적으로 패널을 나눴다.",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 6 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": {
        "defaults": { "unit": "short", "custom": { "fillOpacity": 10 } },
        "overrides": []
      },
      "targets": [
        {
          "refId": "A",
          "queryType": "range",
          "legendFormat": "{{service}}",
          "expr": "sum by (service) (count_over_time({service=~\"${svc:regex}\", level=\"WARN\"}[$__auto]))"
        }
      ]
    },
    {
      "id": 7,
      "type": "row",
      "title": "2. 무엇이 터졌나",
      "collapsed": false,
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 14 },
      "panels": []
    },
    {
      "id": 8,
      "type": "table",
      "title": "로거별 ERROR Top 15",
      "description": "어느 클래스가 시끄러운지가 원인 추적의 첫 갈래다. 로거명은 스레드 대괄호 뒤를 앵커로 추출한다.",
      "gridPos": { "h": 9, "w": 8, "x": 0, "y": 15 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": { "defaults": { "unit": "short" }, "overrides": [] },
      "options": { "sortBy": [{ "displayName": "Value", "desc": true }] },
      "targets": [
        {
          "refId": "A",
          "queryType": "instant",
          "expr": "topk(15, sum by (logger) (count_over_time({service=~\"${svc:regex}\", level=\"ERROR\"} | regexp `\\] (?P<logger>[\\w.]+)\\s+: ` [$__range])))"
        }
      ]
    },
    {
      "id": 9,
      "type": "table",
      "title": "예외 클래스별 Top 15",
      "description": "multiline 병합 덕에 스택트레이스 본문에서 예외 클래스를 뽑을 수 있다. 레벨은 상단 $level 변수를 따른다.",
      "gridPos": { "h": 9, "w": 8, "x": 8, "y": 15 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": { "defaults": { "unit": "short" }, "overrides": [] },
      "options": { "sortBy": [{ "displayName": "Value", "desc": true }] },
      "targets": [
        {
          "refId": "A",
          "queryType": "instant",
          "expr": "topk(15, sum by (exc) (count_over_time({service=~\"${svc:regex}\", level=~\"${level:regex}\"} |~ `(?:Exception|Error)` | regexp `(?P<exc>[\\w.]+(?:Exception|Error))` [$__range])))"
        }
      ]
    },
    {
      "id": 10,
      "type": "table",
      "title": "직전 24시간 로거별 ERROR Top 15",
      "description": "왼쪽 표와 눈으로 비교해 '새로 생긴 놈'을 잡는다. 이 패널만 시간 범위가 24시간으로 고정돼 있다.",
      "gridPos": { "h": 9, "w": 8, "x": 16, "y": 15 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "timeFrom": "24h",
      "hideTimeOverride": false,
      "fieldConfig": { "defaults": { "unit": "short" }, "overrides": [] },
      "options": { "sortBy": [{ "displayName": "Value", "desc": true }] },
      "targets": [
        {
          "refId": "A",
          "queryType": "instant",
          "expr": "topk(15, sum by (logger) (count_over_time({service=~\"${svc:regex}\", level=\"ERROR\"} | regexp `\\] (?P<logger>[\\w.]+)\\s+: ` [$__range])))"
        }
      ]
    }
  ]
}
```

- [ ] **Step 2: JSON 문법 확인**

```bash
python3 -m json.tool deploy/grafana/provisioning/dashboards/json/hypenow-errors.json > /dev/null && echo "JSON OK"
```

Expected: `JSON OK`

- [ ] **Step 3: 리그에서 패널 렌더 확인**

Task 1의 리그가 떠 있어야 한다(안 떠 있으면 `docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml up -d` 후 45초 대기). 프로비저닝은 60초마다 갱신되므로 파일을 저장하고 1분 기다리거나 grafana를 재기동한다.

```bash
docker restart alloytest-grafana && sleep 20
curl -s 'http://localhost:3000/api/dashboards/uid/hypenow-errors' | python3 -c '
import json,sys
d = json.load(sys.stdin)
print("등록됨:", d["dashboard"]["title"])
print("패널 수:", len(d["dashboard"]["panels"]))
'
```

Expected: `등록됨: HypeNow 에러` / `패널 수: 10`

이어서 브라우저로 http://localhost:3000/d/hypenow-errors 를 열어 확인한다:
- Row 1의 ERROR 총건수가 0이 아니다(리그 was 발생기가 사이클마다 1건씩 넣는다)
- 서비스별 ERROR 막대에 `was`가 보인다
- 로거별 Top 15 표에 `c.c.w.v.c.V1ExceptionAdvice`가 보인다
- 예외 클래스별 Top 15 표에 `java.lang.IllegalStateException`(또는 `IllegalStateException`)이 보인다

**No data가 나오면 datasource uid를 먼저 의심한다** — 기존 두 대시보드가 같은 사유로 전 패널 No data를 세 번 겪었다(커밋 `9868fc1b`·`832dd706`·`62f2696b`). `"uid": "hypenow-loki"`, `"type": "loki"`가 맞는지 확인한다.

- [ ] **Step 4: 커밋**

```bash
git add deploy/grafana/provisioning/dashboards/json/hypenow-errors.json
git commit -m "feat(deploy): 에러 대시보드 Row 1·2 — 요약과 에러 유형

ERROR/WARN 총건수·서비스별 분포·추이(Row 1)와 로거별·예외 클래스별
Top 15, 직전 24시간 비교표(Row 2)를 넣는다. WARN을 ERROR와 나란히
두는 것은 crawler·monitoring이 외부 의존 실패를 WARN으로 남기기
때문이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: 대시보드 Row 3·4 — 외부 의존 실패와 드릴다운

**Files:**
- Modify: `deploy/grafana/provisioning/dashboards/json/hypenow-errors.json` (`panels` 배열에 이어 붙이고, 최상위에 `annotations` 추가)

**Interfaces:**
- Consumes: Task 2의 변수 `$svc`·`$level`·`$search`, 패널 id 1~10.
- Produces: 완성된 대시보드(패널 18개). Task 4의 문서가 이 패널 구성을 설명한다.

- [ ] **Step 1: Row 3·4 패널을 `panels` 배열 끝에 추가**

id 10 패널 객체 뒤에, 배열 닫기(`]`) 전에 아래를 이어 붙인다.

```json
    ,
    {
      "id": 11,
      "type": "row",
      "title": "3. 외부 의존 실패 (crawler·monitoring)",
      "collapsed": false,
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 24 },
      "panels": []
    },
    {
      "id": 12,
      "type": "timeseries",
      "title": "HTTP 상태코드별 실패",
      "description": "IG·Hiker 응답 코드를 로그 본문에서 추출한다(`HTTP 401`·`Hiker 404` 형태). 상단 $svc 변수와 무관하게 crawler·monitoring 고정이다.",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 25 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": {
        "defaults": { "unit": "short", "custom": { "fillOpacity": 10 } },
        "overrides": []
      },
      "targets": [
        {
          "refId": "A",
          "queryType": "range",
          "legendFormat": "{{code}}",
          "expr": "sum by (code) (count_over_time({service=~\"crawler|monitoring\", level=~\"WARN|ERROR\"} |~ `(?:HTTP|Hiker) [0-9]{3}` | regexp `(?:HTTP|Hiker) (?P<code>[0-9]{3})` [$__auto]))"
        }
      ]
    },
    {
      "id": 13,
      "type": "stat",
      "title": "Hiker 402 (잔액 소진)",
      "description": "1건이라도 뜨면 사람이 충전해야 한다. 08-10에 이 상태로 브랜드 스윕 4건이 collecting에 고착했다.",
      "gridPos": { "h": 4, "w": 6, "x": 12, "y": 25 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "color": { "mode": "thresholds" },
          "thresholds": {
            "mode": "absolute",
            "steps": [{ "color": "green", "value": null }, { "color": "red", "value": 1 }]
          }
        },
        "overrides": []
      },
      "options": {
        "graphMode": "none",
        "colorMode": "background",
        "reduceOptions": { "calcs": ["sum"], "fields": "", "values": false }
      },
      "targets": [
        {
          "refId": "A",
          "queryType": "range",
          "expr": "sum(count_over_time({service=~\"crawler|monitoring\"} |~ `(?:HTTP|Hiker) 402` [$__auto]))"
        }
      ]
    },
    {
      "id": 14,
      "type": "stat",
      "title": "IG 401 (IP 차단)",
      "description": "요청 폭주로 인한 차단 신호. 07-24에 similar 잡 대량 유입 → 401 전면 실패 전례가 있다.",
      "gridPos": { "h": 4, "w": 6, "x": 18, "y": 25 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "color": { "mode": "thresholds" },
          "thresholds": {
            "mode": "absolute",
            "steps": [{ "color": "green", "value": null }, { "color": "red", "value": 1 }]
          }
        },
        "overrides": []
      },
      "options": {
        "graphMode": "none",
        "colorMode": "background",
        "reduceOptions": { "calcs": ["sum"], "fields": "", "values": false }
      },
      "targets": [
        {
          "refId": "A",
          "queryType": "range",
          "expr": "sum(count_over_time({service=~\"crawler|monitoring\"} |~ `(?:HTTP|Hiker) 401` [$__auto]))"
        }
      ]
    },
    {
      "id": 15,
      "type": "table",
      "title": "크롤 실패 로거 Top 15 (WARN 포함)",
      "gridPos": { "h": 4, "w": 12, "x": 12, "y": 29 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "fieldConfig": { "defaults": { "unit": "short" }, "overrides": [] },
      "options": { "sortBy": [{ "displayName": "Value", "desc": true }] },
      "targets": [
        {
          "refId": "A",
          "queryType": "instant",
          "expr": "topk(15, sum by (logger) (count_over_time({service=~\"crawler|monitoring\", level=~\"WARN|ERROR\"} | regexp `\\] (?P<logger>[\\w.]+)\\s+: ` [$__range])))"
        }
      ]
    },
    {
      "id": 16,
      "type": "row",
      "title": "4. 드릴다운",
      "collapsed": false,
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 33 },
      "panels": []
    },
    {
      "id": 17,
      "type": "logs",
      "title": "로그 탐색",
      "description": "상단 $svc·$level·$search 변수를 그대로 따른다. 검색어는 정규식이며 대소문자를 무시한다.",
      "gridPos": { "h": 14, "w": 18, "x": 0, "y": 34 },
      "datasource": { "type": "loki", "uid": "hypenow-loki" },
      "options": {
        "showTime": true,
        "showLabels": false,
        "wrapLogMessage": true,
        "prettifyLogMessage": false,
        "enableLogDetails": true,
        "dedupStrategy": "none",
        "sortOrder": "Descending"
      },
      "targets": [
        {
          "refId": "A",
          "queryType": "range",
          "expr": "{service=~\"${svc:regex}\", level=~\"${level:regex}\"} |~ `(?i)$search`"
        }
      ]
    },
    {
      "id": 18,
      "type": "text",
      "title": "읽는 법·한계",
      "gridPos": { "h": 14, "w": 6, "x": 18, "y": 34 },
      "options": {
        "mode": "markdown",
        "content": "### 읽는 순서\n\n1. **Row 1** — ERROR/WARN 총건수와 추이. 평소와 다르면 아래로.\n2. **Row 2** — 어느 로거·예외가 시끄러운지. 오른쪽 24시간 표와 비교해 '새로 생긴 놈'을 찾는다.\n3. **Row 3** — 크롤링이 외부에 막힌 건지. 402·401 칸이 빨갛면 사람이 개입해야 한다.\n4. **Row 4** — 위에서 찾은 로거·예외명을 검색어에 넣어 원문을 본다.\n\n세로 점선은 **배포·재기동 시점**이다.\n\n### 한계\n\n- 로그 보관 **30일**, **운영만** 수집(test 스테이징 로그는 없다).\n- **요청 단위 상관관계 불가** — traceId가 없어 에러가 어떤 엔드포인트·유저였는지 못 짚는다.\n- 2026-08-12 파이프라인 변경 **이전 로그는 잡히지 않는다**(`level`·`service` 라벨이 없다).\n- 로거·예외·HTTP 코드는 정규식 추출이라 기본 콘솔 패턴을 벗어난 로그는 누락될 수 있다.\n- **5xx 비율은 여기가 아니라** [HypeNow API 성능](/d/hypenow-api-performance) 대시보드에서 본다 — 로그 집계와 지표는 숫자가 구조적으로 다르다."
      }
    }
```

- [ ] **Step 2: `annotations` 블록을 최상위에 추가**

`"templating"` 키 바로 앞에 넣는다.

```json
  "annotations": {
    "list": [
      {
        "builtIn": 1,
        "datasource": { "type": "grafana", "uid": "-- Grafana --" },
        "enable": true,
        "hide": true,
        "iconColor": "rgba(0, 211, 255, 1)",
        "name": "Annotations & Alerts",
        "type": "dashboard"
      },
      {
        "name": "배포·재기동",
        "datasource": { "type": "loki", "uid": "hypenow-loki" },
        "enable": true,
        "hide": false,
        "iconColor": "purple",
        "target": {
          "refId": "Anno",
          "queryType": "range",
          "expr": "{service=~\"${svc:regex}\"} |= `Started ` |= ` seconds`"
        }
      }
    ]
  },
```

Spring Boot 기동 완료 줄(`Started CrawlerApplication in 25.539 seconds ...`)을 잡아 전 패널에 세로줄로 겹친다 — "언제부터 이 에러가"를 배포 시점과 겹쳐 읽기 위한 것이다.

- [ ] **Step 3: JSON 문법·패널 수 확인**

```bash
python3 -c '
import json
d = json.load(open("deploy/grafana/provisioning/dashboards/json/hypenow-errors.json"))
ids = [p["id"] for p in d["panels"]]
print("패널 수:", len(ids), "| id 중복:", len(ids) != len(set(ids)))
print("어노테이션:", [a["name"] for a in d["annotations"]["list"]])
'
```

Expected: `패널 수: 18 | id 중복: False` / `어노테이션: ['Annotations & Alerts', '배포·재기동']`

- [ ] **Step 4: 리그에서 Row 3·4 렌더 확인**

```bash
docker restart alloytest-grafana && sleep 20
```

http://localhost:3000/d/hypenow-errors 에서 확인:
- **HTTP 상태코드별 실패**에 `404`·`402`·`401` 세 계열이 보인다(리그 monitoring 발생기가 셋 다 넣는다)
- **Hiker 402** 칸과 **IG 401** 칸이 빨간 배경에 0이 아닌 값이다
- **로그 탐색** 패널에 로그가 흐르고, 검색어에 `IllegalStateException`을 넣으면 was ERROR 1건만 남는다
- 검색어를 비우면 다시 전체가 보인다(빈 값이면 `(?i)`가 되어 모두 매칭)

배포 어노테이션은 리그에 `Started ... seconds` 로그가 없어 안 그어진다 — 정상이다. 운영 배포 후 확인한다(Task 4 §운영 확인).

- [ ] **Step 5: 커밋**

```bash
git add deploy/grafana/provisioning/dashboards/json/hypenow-errors.json
git commit -m "feat(deploy): 에러 대시보드 Row 3·4 — 외부 의존 실패와 드릴다운

IG·Hiker HTTP 상태코드별 실패 추이와, 즉시 개입이 필요한 402(잔액
소진)·401(IP 차단) 전용 칸을 넣는다. 드릴다운 Row는 상단 변수를 그대로
따르는 로그 탐색 패널과 읽는 법·한계 안내로 구성한다.

Spring Boot 기동 완료 로그를 어노테이션으로 걸어 배포 시점이 전 패널에
세로줄로 겹치게 했다 — 별도 데이터 소스 없이 얻는 배포 마커다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: 문서화와 PR

**Files:**
- Modify: `deploy/README.md` (§15 뒤에 §16 신설 — 현재 파일 끝)
- Modify: `DECISIONS.md` (표 맨 위 행 추가)

**Interfaces:**
- Consumes: Task 1~3의 최종 산출물(`config.alloy`, `hypenow-errors.json`, 검증 리그).
- Produces: 없음(마지막 태스크).

- [ ] **Step 1: `deploy/README.md`에 §16 추가**

파일 맨 끝에 이어 붙인다.

```markdown

## 16. 에러 추적 대시보드 (08-12~)

로그(Loki)만으로 "어젯밤 뭐가 터졌나"를 훑고 그 자리에서 원인까지 내려가는 대시보드다.
Grafana(§14-1 터널) → 폴더 HypeNow → **"HypeNow 에러"**. 별도 개통 절차 없음 — 배포 1회면 붙는다
(`config.alloy`·대시보드 JSON 모두 CD가 scp + `docker compose restart alloy`/grafana로 반영, §15 말미 참조).

### 16-1. 읽는 순서

1. **Row 1(지금 아픈가)** — ERROR/WARN 총건수와 서비스별 추이. **WARN을 반드시 같이 본다**:
   crawler·monitoring은 외부 의존 실패(Hiker 4xx·IG 차단·스윕 실패)를 대부분 `log.warn`으로
   남겨서, ERROR만 보면 크롤링 사고가 화면에 안 잡힌다.
2. **Row 2(무엇이 터졌나)** — 로거별·예외 클래스별 Top 15. 오른쪽 "직전 24시간" 표와 눈으로
   비교해 새로 등장한 항목을 찾는다.
3. **Row 3(외부 의존 실패)** — **Hiker 402(잔액 소진)·IG 401(IP 차단) 칸이 빨간색이면 사람이
   개입해야 한다.** 402는 충전, 401은 요청량 조절 없이는 저절로 낫지 않는다.
4. **Row 4(드릴다운)** — 위에서 찾은 로거·예외명을 상단 "검색어"에 넣어 원문을 본다(정규식,
   대소문자 무시).

전 패널에 겹치는 세로 점선은 **배포·재기동 시점**이다(Spring Boot `Started ... seconds` 로그).

### 16-2. 로그 파이프라인이 하는 일 (`deploy/alloy/config.alloy`)

- **JVM 4종만 multiline 병합**: 스택트레이스를 1건으로 묶는다. 이게 없으면 에러 1건이 트레이스
  줄 수만큼 부풀어 세어진다. caddy·postgres 등은 Spring 타임스탬프로 시작하지 않아 같은
  파이프라인에 태우면 전부 한 덩어리로 뭉치므로, 타깃을 두 갈래로 나눠 놓았다 — **이 분기를
  없애지 말 것.**
- **`service` 라벨**: `deploy-was-38` 같은 컨테이너명에서 복제본 번호를 떼어낸 안정 라벨
  (`was`·`crawler`·`analytics`·`monitoring`). was는 롤링 배포마다 번호가 올라서, `container`로
  그룹핑하면 배포할 때마다 시계열이 끊긴다.
- **`level` 라벨**: 로그 레벨. 로거명·예외 클래스는 값이 수백 개라 **라벨로 올리지 않는다**
  (쿼리 시점 `| regexp` 추출). 이 원칙을 어기면 Loki 스트림이 폭발한다.

### 16-3. 한계

- 로그 보관 30일, 운영(`deploy_prod`)만 수집 — test 스테이징 로그는 없다.
- **요청 단위 상관관계 불가**: traceId/MDC가 없어 에러가 어떤 엔드포인트·유저 요청이었는지
  못 짚는다. 필요해지면 logback 구조화 로깅이 다음 단계다.
- **2026-08-12 파이프라인 변경 이전 로그는 이 대시보드에 안 잡힌다** — `level`·`service` 라벨이
  없기 때문. 30일이 지나면 자연 해소된다.
- 로그 기반 에러 집계와 §15의 Prometheus 5xx율은 **숫자가 구조적으로 다르다**(4xx는 로그를
  안 남기고, WARN은 5xx가 아니다). 나란히 비교하지 말 것.
- 자동 새로고침 5분 고정(2코어 보호). 장애 추적 중에는 Grafana UI에서 일시적으로 올린다.

### 16-4. 로컬 검증 리그

`config.alloy`를 고칠 때는 `deploy/alloy/test/`의 리그로 먼저 확인한다 — 운영과 같은 컨테이너
이름·네트워크 이름을 흉내내 relabel과 multiline 병합이 실제로 걸리는지 본다. 사용법은
[deploy/alloy/test/README.md](alloy/test/README.md). **서버에서 실행 금지**(운영과 같은 이름 공간).
```

- [ ] **Step 2: `DECISIONS.md` 표 맨 위에 행 추가**

`| 날짜 | 결정 | 근거/상세 |`와 `|---|---|---|` 다음 줄에 삽입한다(기존 `2026-08-11` 행 위).

```
| 2026-08-12 | **로그 기반 에러 추적 대시보드 — Alloy multiline 병합 + `service`/`level` 라벨 + 단일 대시보드 Row 4단** — PLG 스택은 08-10에 올라갔지만 로그를 쓰는 대시보드가 0개였다. 용도를 "아침 훑기 + 장애 시 원인 추적" 둘로 한정하고, 두 동작이 같은 시간축에서 위→아래로 좁혀가는 하나의 흐름이라 화면을 나누지 않고 단일 대시보드(`hypenow-errors`) Row 4단(요약 → 유형 → 외부의존 → 드릴다운)으로 구성했다. 파이프라인은 **타깃을 JVM 4종/그 외 두 갈래로 분기** — multiline stage가 "firstline에 안 맞는 줄은 직전 엔트리에 이어붙인다"로 동작해서, 한 파이프라인에 태우면 caddy(JSON)·postgres 로그가 전부 하나의 거대 엔트리로 뭉친다. 라벨은 `level`(값 5개)과 `service`(값 4개)만 추가하고 로거명·예외 클래스는 쿼리 시점 `regexp` 추출로 남겼다(라벨화 시 스트림 폭발). `service`는 롤링 배포마다 번호가 오르는 was 컨테이너명(`deploy-was-38`)에서 번호를 떼어낸 안정 라벨 — `container`로 그룹핑하면 배포마다 시계열이 끊긴다. **설계를 바꾼 실측 두 건**: ① crawler·monitoring은 외부 의존 실패를 대부분 `log.warn`으로 남겨(monitoring 전체 `log.error` 2건) ERROR 전용 보드로는 크롤링 사고를 놓친다 → ERROR·WARN 두 축 병치, ② 로그가 평문 한국어 자유 문장이라 메시지 텍스트 그룹핑은 카디널리티가 터진다 → 축약 로거명·예외 클래스를 그룹핑 키로. 범위 밖(후속): 로그 기반 알람 규칙(실제 분포를 본 뒤 임계값 결정), logback 구조화 로깅·MDC traceId. 검증은 `deploy/alloy/test/` 리그(운영 컨테이너명·네트워크명을 흉내낸 로컬 스택)로 병합·라벨·비-JVM 회귀를 실측 | 스펙 [docs/superpowers/specs/2026-08-12-log-error-dashboard-design.md](docs/superpowers/specs/2026-08-12-log-error-dashboard-design.md) · [deploy/README.md §16](deploy/README.md) · `deploy/alloy/config.alloy`·`deploy/alloy/test/`·`deploy/grafana/provisioning/dashboards/json/hypenow-errors.json` |
```

- [ ] **Step 3: 리그 정리 후 최종 확인**

```bash
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml down -v
git status --short
```

Expected: 변경 파일이 `deploy/alloy/config.alloy`, `deploy/alloy/test/*`(5개), `deploy/grafana/provisioning/dashboards/json/hypenow-errors.json`, `deploy/README.md`, `DECISIONS.md`뿐. **앱 모듈(`was/`·`crawler/`·`analytics/`·`monitoring/`) 변경이 하나도 없어야 한다.**

- [ ] **Step 4: 커밋**

```bash
git add deploy/README.md DECISIONS.md
git commit -m "docs: 에러 대시보드 운영 런북(§16)·결정 기록 추가

읽는 순서와 한계, 파이프라인 두 갈래 분기를 없애면 안 되는 이유를
런북에 남긴다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

- [ ] **Step 5: PR 생성**

```bash
git push -u origin feature/plg-error-tracking-dashboard-2b6726
gh pr create --base develop \
  --title "feat(deploy): 로그 기반 에러 추적 대시보드" \
  --body "$(cat <<'EOF'
## 배경

08-10에 PLG 스택(Alloy → Loki → Grafana)이 올라갔지만 로그를 쓰는 대시보드가 0개였다.
쌓인 로그를 실제 에러 파악에 쓰는 화면을 만든다.

## 무엇이 바뀌나

- **`deploy/alloy/config.alloy`** — 타깃을 JVM 4종/그 외 두 갈래로 분기. JVM 쪽에만 multiline
  병합(스택트레이스 1건화)과 `level` 라벨을 붙이고, 공통으로 `service` 안정 라벨을 추가한다.
- **`hypenow-errors.json`** (신규) — 대시보드 "HypeNow 에러". Row 4단: 요약 → 유형 →
  외부의존 실패 → 드릴다운. 패널 18개, 배포 어노테이션 포함.
- **`deploy/alloy/test/`** (신규) — 로컬 검증 리그. 운영 컨테이너명·네트워크명을 흉내내
  `config.alloy`를 수정 없이 태워본다.
- 런북 §16과 결정 기록 1행.

## 설계를 바꾼 실측 두 건

1. **crawler·monitoring은 실패를 대부분 `log.warn`으로 남긴다**(monitoring 전체 `log.error` 2건).
   ERROR 전용 보드였다면 크롤링 사고를 통째로 놓쳤을 것이다 → ERROR·WARN 두 축 병치.
2. **운영 was 컨테이너명이 배포마다 바뀐다**(현재 `deploy-was-38`). `container`로 그룹핑하면
   배포할 때마다 시계열이 끊기고 죽은 스트림이 30일간 쌓인다 → `service` 안정 라벨 도입.

## 검증

로컬 리그에서 실측 확인:
- `service` 라벨 값이 정확히 JVM 서비스만(`["monitoring","was"]`), 비-JVM엔 없음
- was ERROR 1건이 스택트레이스 6줄을 통째로 담음(병합 전엔 6건으로 쪼개짐)
- 비-JVM(caddy JSON) 로그는 여전히 줄 단위 — 뭉치지 않음
- 대시보드 18개 패널이 프로비저닝으로 등록되고 No data 없이 렌더

앱 코드 변경 없음 — `deploy/` 설정과 문서뿐이다.

## 범위 밖 (후속)

로그 기반 알람 규칙(실제 에러 분포를 본 뒤 임계값 결정), logback 구조화 로깅·MDC traceId.

스펙: `docs/superpowers/specs/2026-08-12-log-error-dashboard-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6: 운영 확인 (머지·승격 후, 사용자와 함께)**

develop → staging → main 승격으로 배포된 뒤:

1. Grafana 터널(`ssh -L 3001:localhost:3000 ubuntu@<host>`) → 폴더 HypeNow → "HypeNow 에러"
2. Row 1이 채워지는지 — 배포 직후엔 비어 있을 수 있다(이전 로그에 `level` 라벨이 없다). **최소
   몇 시간 뒤 다시 본다.**
3. 배포 어노테이션 세로줄이 실제 배포 시각에 그어지는지
4. 비-JVM 회귀 — Explore에서 `{container="deploy-caddy-1"}`이 줄 단위로 보이는지
5. Loki 볼륨 증가율 확인(`docker system df -v | grep loki-data`) — multiline 병합은 엔트리 수를
   줄이지 실제 바이트를 늘리지 않지만, 라벨 2개 추가로 스트림이 늘어난다

---

## 실행 순서 요약

Task 1(파이프라인) → Task 2(Row 1·2) → Task 3(Row 3·4) → Task 4(문서·PR). Task 2·3은 Task 1의
라벨과 검증 리그에 의존하므로 순서를 바꿀 수 없다.
