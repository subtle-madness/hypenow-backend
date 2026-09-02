# caddy 액세스 로그 Loki 수집 구현 계획

> 상태: 🟢 활성
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 caddy 액세스 로그(파일)를 alloy로 테일링해 Loki에 넣고, 엣지 5xx를 홈 대시보드에 노출한다.

**Architecture:** caddy가 이미 호스트 바인드(`deploy/logs/caddy/access.log`)로 쓰는 파일을 alloy 컨테이너에 ro 마운트, `local.file_match`+`loki.source.file`+`loki.process`로 수집. 라벨은 `service="caddy-access"` 고정 + status 5xx만 `level="ERROR"`(기존 ERROR 패널 자동 편입). 검증은 `deploy/alloy/test/` 로컬 리그로.

**Tech Stack:** Grafana Alloy v1.8.2(River 설정), Loki 3.5, docker compose, Grafana 대시보드 JSON.

**스펙:** [docs/superpowers/specs/2026-08-24-caddy-access-log-loki-design.md](../../specs/2026-08-24-caddy-access-log-loki-design.md)

## Global Constraints

- 수집 범위는 운영 `access.log`만 — `test-access.log`·로테이션 산출물(`access-*.log.gz` 등)은 절대 수집 금지.
- status·uri·method를 Loki 라벨로 올리지 않는다(카디널리티) — 조회는 쿼리 시 `| json`.
- 리그는 로컬 맥 전용(서버 실행 금지). 로컬 도커는 Docker Desktop — `DOCKER_HOST` 미설정이 정답.
- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(deploy):`/`docs:`.
- 스펙 대비 확정 변경 1건: `stage.timestamp`(JSON ts 사용)는 **뺀다** — `tail_from_end=true`라 테일 지연이 ms 단위고, JMESPath 숫자→문자열 변환(지수 표기 위험)에 파서를 걸 이유가 없다. Task 2에서 스펙에 반영.

---

### Task 1: 리그 픽스처 — 액세스 로그 파일 발생기 (실패 검증 포함)

**Files:**
- Create: `deploy/alloy/test/fixtures/emit-caddy-access.sh`
- Modify: `deploy/alloy/test/compose.test.yaml`

**Interfaces:**
- Produces: 리그 공유 볼륨 `caddy-logs`가 alloy 컨테이너의 `/var/log/caddy`(ro)로 마운트됨. `access.log`(200·500 각 10초 주기 append), 미수집 확인용 `access-2026-08-24T00-00-00.000-size.log`(status 599)·`test-access.log`(status 598) 각 1회 기록.

- [ ] **Step 1: 발생기 스크립트 작성**

`deploy/alloy/test/fixtures/emit-caddy-access.sh`:

```sh
#!/bin/sh
# caddy 액세스 로그 파일 발생기 — 운영 /var/log/caddy/access.log의 실측 JSON 포맷 재현(2026-08-24 스펙).
# 다른 emit-*와 달리 컨테이너 stdout이 아니라 **공유 볼륨의 파일**에 append한다 —
# loki.source.file 파이프라인의 입력이라 service 라벨(compose 서비스명) 규칙과 무관하다.
# 로테이션 산출물(599)·test-access.log(598)는 미수집 검증용 — Loki에 나타나면 패턴이 샌 것이다.
mkdir -p /var/log/caddy
TS=$(date +%s)
printf '{"level":"info","ts":%s.111,"logger":"http.log.access.log0","msg":"handled request","request":{"method":"GET","host":"api.hypenow.io","uri":"/rotated"},"duration":0.01,"size":10,"status":599}\n' "$TS" > "/var/log/caddy/access-2026-08-24T00-00-00.000-size.log"
printf '{"level":"info","ts":%s.222,"logger":"http.log.access.log0","msg":"handled request","request":{"method":"GET","host":"dev-api.hypenow.io","uri":"/health"},"duration":0.01,"size":10,"status":598}\n' "$TS" > "/var/log/caddy/test-access.log"
while true; do
  TS=$(date +%s)
  printf '{"level":"info","ts":%s.123,"logger":"http.log.access.log0","msg":"handled request","request":{"remote_ip":"203.0.113.7","proto":"HTTP/2.0","method":"GET","host":"api.hypenow.io","uri":"/v1/me"},"duration":0.012,"size":123,"status":200}\n' "$TS" >> /var/log/caddy/access.log
  printf '{"level":"error","ts":%s.456,"logger":"http.log.access.log0","msg":"handled request","request":{"remote_ip":"203.0.113.7","proto":"HTTP/2.0","method":"POST","host":"api.hypenow.io","uri":"/v1/brand-monitoring/accounts"},"duration":4.7,"size":0,"status":500}\n' "$TS" >> /var/log/caddy/access.log
  sleep 10
done
```

- [ ] **Step 2: 리그 compose에 발생기 서비스·볼륨 추가**

`deploy/alloy/test/compose.test.yaml` — `caddy:` 서비스 블록 아래에 추가:

```yaml
  # 액세스 로그 **파일** 발생기(2026-08-24 스펙) — caddy가 파일로만 남기는 액세스 로그를 흉내낸다.
  # 컨테이너 로그가 아니라 공유 볼륨의 파일이 테스트 입력이라, 서비스명은 운영과 맞출 필요 없다.
  caddy-access-writer:
    image: alpine:3.20
    container_name: alloytest-caddy-access-writer
    networks: [prod]
    entrypoint: ["sh", "/fixtures/emit-caddy-access.sh"]
    volumes:
      - ./fixtures:/fixtures:ro
      - caddy-logs:/var/log/caddy
```

`alloy:` 서비스의 volumes에 한 줄 추가(운영 compose.yaml의 `./logs/caddy:/var/log/caddy:ro`와 동일 마운트 지점):

```yaml
      - caddy-logs:/var/log/caddy:ro
```

파일 맨 아래에 볼륨 선언 추가:

```yaml
volumes:
  caddy-logs:
```

- [ ] **Step 3: 리그 기동, 파이프라인 부재 확인(실패하는 테스트)**

```bash
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml up -d
sleep 45
curl -sG http://localhost:3100/loki/api/v1/query_range --data-urlencode 'query={service="caddy-access"}' --data-urlencode 'limit=10'
```

기대: `"result":[]` (빈 결과 — config.alloy에 파일 파이프라인이 아직 없다). 발생기 자체는 도는지 확인:

```bash
docker exec alloytest-caddy-access-writer sh -c 'wc -l /var/log/caddy/access.log; ls /var/log/caddy'
```

기대: access.log 줄수 ≥ 2, 파일 3개(access.log, access-2026-…-size.log, test-access.log).

- [ ] **Step 4: 커밋**

```bash
git add deploy/alloy/test/fixtures/emit-caddy-access.sh deploy/alloy/test/compose.test.yaml
git commit -m "test(deploy): alloy 리그에 caddy 액세스 로그 파일 발생기 추가"
```

---

### Task 2: config.alloy 파일 파이프라인 + 운영 compose 마운트

**Files:**
- Modify: `deploy/alloy/config.alloy` (파일 끝, `loki.write "local"` 블록 앞에 추가)
- Modify: `deploy/compose.yaml` (alloy 서비스 volumes ~410-412행, 최하단 volumes 선언 ~503행)
- Modify: `docs/superpowers/specs/2026-08-24-caddy-access-log-loki-design.md` (stage.timestamp 제외 반영)

**Interfaces:**
- Consumes: Task 1의 리그(공유 볼륨 `/var/log/caddy`).
- Produces: Loki 스트림 `{service="caddy-access"}` — 5xx 엔트리만 `level="ERROR"` 라벨. 이 라벨 계약을 Task 3 대시보드 쿼리가 사용한다.

- [ ] **Step 1: config.alloy에 파이프라인 추가**

`deploy/alloy/config.alloy`의 `loki.source.docker "others"` 블록과 `loki.write "local"` 블록 사이에 추가:

```alloy
// 엣지 액세스 로그(2026-08-24 스펙) — caddy 액세스 로그는 파일로만 남아(Caddyfile log output file)
// 도커 파이프라인에 안 잡힌다. 파일을 직접 테일링해 엣지 5xx(예: 롤링 배포 순단 502)가
// 대시보드에 보이게 한다. 경로는 정확 매칭 — 로테이션 산출물(access-*.log.gz)·test 도메인
// (test-access.log)은 패턴 밖이어야 한다("운영만 계측" 원칙).
local.file_match "caddy_access" {
  path_targets = [{
    __path__ = "/var/log/caddy/access.log",
    service  = "caddy-access",
  }]
}

loki.source.file "caddy_access" {
  targets    = local.file_match.caddy_access.targets
  forward_to = [loki.process.caddy_access.receiver]

  // 첫 발견 시 파일 끝부터 — 기존 파일(~50MiB) 일괄 재수집으로 "지금" 타임스탬프의 과거
  // 로그가 쏟아지는 것을 막는다. 이후 재기동은 포지션 파일(compose의 alloy-data 볼륨)로 이어읽는다.
  tail_from_end = true
}

loki.process "caddy_access" {
  // status·uri·method는 라벨로 올리지 않는다(카디널리티) — 조회는 쿼리 시 `| json | status >= 500`.
  // 단 5xx 여부만 level=ERROR로 승격 — 홈 "ERROR 급증"·인프라 "서비스별 ERROR" 패널이
  // {level="ERROR"}를 집계하므로 추가 작업 없이 엣지 5xx가 편입된다. 4xx 이하는 level 라벨
  // 없음(빈 문자열 라벨은 Prometheus 의미론상 미존재와 동일 — Step 3 리그에서 실측 확인).
  stage.json {
    expressions = { status = "status" }
  }

  stage.template {
    source   = "edge_level"
    template = "{{ if ge (int .status) 500 }}ERROR{{ end }}"
  }

  stage.labels {
    values = { level = "edge_level" }
  }

  forward_to = [loki.write.local.receiver]
}
```

- [ ] **Step 2: 리그 재기동으로 검증 3종 + 미수집 2종**

```bash
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml restart alloy
sleep 45
# ① 수집 자체
curl -sG http://localhost:3100/loki/api/v1/query_range --data-urlencode 'query={service="caddy-access"}' --data-urlencode 'limit=50'
# ② 5xx만 level=ERROR
curl -sG http://localhost:3100/loki/api/v1/query_range --data-urlencode 'query={service="caddy-access", level="ERROR"}' --data-urlencode 'limit=50'
# ③ 비-5xx에 level 라벨 없음
curl -sG http://localhost:3100/loki/api/v1/query_range --data-urlencode 'query={service="caddy-access", level=""}' --data-urlencode 'limit=50'
# ④⑤ 미수집: 로테이션(599)·test(598)
curl -sG http://localhost:3100/loki/api/v1/query_range --data-urlencode 'query={service="caddy-access"} |= "599"' --data-urlencode 'limit=10'
curl -sG http://localhost:3100/loki/api/v1/query_range --data-urlencode 'query={service="caddy-access"} |= "598"' --data-urlencode 'limit=10'
# ⑥ 기존 caddy(stdout) 회귀 없음 — 엔트리 1줄·level 라벨 없음 그대로
curl -sG http://localhost:3100/loki/api/v1/query_range --data-urlencode 'query={service="caddy"}' --data-urlencode 'limit=10'
```

기대: ① status 200·500 엔트리 존재 / ② 전부 `"status":500` 라인만 / ③ `"status":200` 라인만(스트림 라벨에 level 키 자체가 없어야 함 — 응답의 `stream` 오브젝트 확인) / ④⑤ 빈 결과 / ⑥ 기존과 동일.

**②·③이 어긋나면(빈 문자열 라벨이 실제로 붙는 경우)** template+labels 두 stage를 아래 stage.match로 교체하고 재검증:

```alloy
  stage.match {
    selector = "{service=\"caddy-access\"} |~ `\"status\":5\\d\\d[,}]`"

    stage.static_labels {
      values = { level = "ERROR" }
    }
  }
```

- [ ] **Step 3: 운영 compose.yaml에 볼륨 2개 추가**

`deploy/compose.yaml` alloy 서비스의 volumes(현행 2줄)에 추가:

```yaml
      # 엣지 액세스 로그 테일링(2026-08-24 스펙) — caddy가 같은 호스트 경로에 쓴다(caddy 서비스의
      # ./logs/caddy 바인드와 짝). 포지션은 named volume으로 영속 — 없으면 매 배포(restart alloy)마다
      # tail_from_end가 새로 걸려 재기동 순간 로그를 놓친다.
      - ./logs/caddy:/var/log/caddy:ro
      - alloy-data:/var/lib/alloy/data
```

최하단 `volumes:` 선언에 추가:

```yaml
  alloy-data:
```

- [ ] **Step 4: 스펙에 확정 변경 반영**

`docs/superpowers/specs/2026-08-24-caddy-access-log-loki-design.md`의 `stage.timestamp` 항목을 다음으로 교체:

```markdown
  - 타임스탬프는 수집 시각 사용(`stage.timestamp` 없음 — 구현 시 확정): `tail_from_end=true`라
    테일 지연이 ms 단위고, JSON `ts`(epoch float)는 JMESPath 숫자→문자열 변환의 지수 표기
    위험이 있어 파서를 걸 이유가 없다. `tail_from_end`는 기존 파일(~50MiB) 일괄 재수집으로
    "지금" 타임스탬프의 과거 로그가 쏟아지는 것도 함께 막는다.
```

- [ ] **Step 5: 커밋**

```bash
git add deploy/alloy/config.alloy deploy/compose.yaml docs/superpowers/specs/2026-08-24-caddy-access-log-loki-design.md
git commit -m "feat(deploy): caddy 액세스 로그를 alloy 파일 테일링으로 Loki 수집

service=caddy-access 스트림 신설, 5xx만 level=ERROR로 승격해 기존 ERROR
패널에 자동 편입. 포지션 영속용 alloy-data 볼륨 추가."
```

---

### Task 3: 홈 대시보드 "엣지 5xx" 패널

**Files:**
- Modify: `deploy/grafana/provisioning/dashboards/json/hypenow-home.json` ("지금 아픈가" 행, panels 배열)

**Interfaces:**
- Consumes: Task 2의 라벨 계약 `{service="caddy-access", level="ERROR"}`.

- [ ] **Step 1: 첫 행 레이아웃 조정 + 패널 추가**

기존 첫 행 4패널(w=6)을 5패널로: `API 5xx` gridPos를 `{h:5,w:5,x:0,y:1}`, `ERROR 급증` `{h:5,w:5,x:9,y:1}`, `Hiker 402` `{h:5,w:5,x:14,y:1}`, `IG 401` `{h:5,w:5,x:19,y:1}`로 수정하고, panels 배열에서 `API 5xx`(id 1) 바로 뒤에 아래 패널을 삽입:

```json
{
 "id": 14,
 "type": "stat",
 "title": "엣지 5xx",
 "description": "최근 1시간 caddy 엣지 5xx 건수(액세스 로그 → Loki, 2026-08-24 스펙). 왼쪽 'API 5xx'(앱 계층)가 0인데 여기만 뜨면 배포 순단 502·업스트림 다운 — 요청이 was까지 못 간 실패다. 평시 0건이 정상이라 임계 1.",
 "gridPos": { "h": 5, "w": 4, "x": 5, "y": 1 },
 "datasource": { "type": "loki", "uid": "hypenow-loki" },
 "links": [ { "title": "인프라 대시보드로", "url": "/d/hypenow-infra" } ],
 "fieldConfig": {
  "defaults": {
   "noValue": "데이터 없음",
   "links": [ { "title": "인프라 대시보드로", "url": "/d/hypenow-infra" } ],
   "mappings": [
    { "type": "special", "options": { "match": "null+nan", "result": { "text": "데이터 없음", "color": "red", "index": 0 } } }
   ],
   "unit": "short",
   "decimals": 0,
   "color": { "mode": "thresholds" },
   "thresholds": { "mode": "absolute", "steps": [ { "color": "green", "value": null }, { "color": "red", "value": 1 } ] }
  },
  "overrides": []
 },
 "options": {
  "graphMode": "none",
  "colorMode": "background",
  "textMode": "value_and_name",
  "reduceOptions": { "calcs": [ "lastNotNull" ], "fields": "", "values": false }
 },
 "targets": [
  {
   "refId": "A",
   "datasource": { "type": "loki", "uid": "hypenow-loki" },
   "queryType": "instant",
   "legendFormat": "1h 엣지 5xx",
   "expr": "sum(count_over_time({service=\"caddy-access\", level=\"ERROR\"}[1h])) or vector(0)"
  }
 ]
}
```

- [ ] **Step 2: JSON 유효성 + 리그 Grafana에서 렌더 확인**

```bash
python3 -c "import json; json.load(open('deploy/grafana/provisioning/dashboards/json/hypenow-home.json')); print('ok')"
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml restart grafana
```

http://localhost:3000 → HypeNow 홈 대시보드: 첫 행에 "엣지 5xx" 패널이 뜨고, 리그 발생기의 500 때문에 **빨간 배경 + 0이 아닌 수**가 보여야 한다("데이터 없음"이면 데이터소스/쿼리 오류).

- [ ] **Step 3: 커밋**

```bash
git add deploy/grafana/provisioning/dashboards/json/hypenow-home.json
git commit -m "feat(deploy): 홈 대시보드에 엣지 5xx 패널 추가"
```

---

### Task 4: 문서 갱신 + 리그 정리 + PR

**Files:**
- Modify: `deploy/alloy/test/README.md` (검증 3종 표 → 파일 소스 행 추가)
- Modify: `deploy/README.md` (관측 섹션 — Loki 조회축에 caddy-access 추가)
- Modify: `DECISIONS.md` (맨 위에 결정 추가)
- Modify: `docs/superpowers/specs/2026-08-24-caddy-access-log-loki-design.md` (상태 헤더 → ✅)
- Move: `docs/superpowers/plans/2026-08-24-caddy-access-log-loki.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: 리그 README 검증 표에 행 추가**

`deploy/alloy/test/README.md` "검증 3종" 표(제목을 "검증 4종"으로) 아래 행 추가:

```markdown
| 파일 소스(액세스 로그) | `{service="caddy-access"}` | 200·500 엔트리 존재, 500만 `level=ERROR`. 로테이션 파일(599)·test-access.log(598)는 0건 |
```

- [ ] **Step 2: deploy/README.md 관측 섹션 갱신**

"로그는 Explore → Loki 데이터소스 → `{service="was"}`" 문장(~1029행) 뒤에 추가:

```markdown
엣지 액세스 로그는 `{service="caddy-access"} | json | status >= 500` — caddy 액세스 로그
파일을 alloy가 테일링한다(2026-08-24 스펙, 5xx만 `level="ERROR"` 라벨).
```

- [ ] **Step 3: DECISIONS.md 맨 위에 결정 기록**

```markdown
## 2026-08-24 caddy 액세스 로그를 Loki로 수집(엣지 5xx 가시화)

blakshave 500 조사에서 확인: 상태코드 전수 기록처는 caddy 액세스 로그뿐인데 파일로만 남아
엣지 5xx(롤링 배포 순단 502 등)가 어떤 대시보드에도 안 잡혔다. alloy `loki.source.file`로
`access.log`만 테일링(`service="caddy-access"`, 5xx만 `level=ERROR` 승격 — 기존 ERROR 패널
자동 편입), 홈 대시보드에 "엣지 5xx" 패널 추가. 운영만 수집(test-access.log 제외), 알림
rule은 빈도 실측 후 후속. [설계](../../specs/2026-08-24-caddy-access-log-loki-design.md)
```

(기존 항목 형식과 다르면 파일 상단의 실제 형식에 맞춘다.)

- [ ] **Step 4: 스펙 상태 ✅, plan 아카이브, 리그 종료**

```bash
sed -i '' 's/> 상태: 🟢 활성/> 상태: ✅ 구현됨/' docs/superpowers/specs/2026-08-24-caddy-access-log-loki-design.md
mkdir -p docs/superpowers/plans/archive
git mv docs/superpowers/plans/2026-08-24-caddy-access-log-loki.md docs/superpowers/plans/archive/
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml down -v
```

- [ ] **Step 5: 커밋 + PR**

```bash
git add -A
git commit -m "docs(deploy): 액세스 로그 수집 문서 갱신·결정 기록·plan 아카이브"
git push -u origin feature/blakshave-500-error-investigation-555133
gh pr create --base develop --title "feat(deploy): caddy 액세스 로그 Loki 수집 — 엣지 5xx 대시보드 가시화" --body "..."
```

PR 본문에는 배경(blakshave 500 조사 → 관측 사각지대), 변경 요약, 리그 검증 결과(쿼리 출력 요지), 배포 시 유의(alloy-data 볼륨 신규 — CD의 `restart alloy`로 자동 반영, compose 변경이라 `up -d` 필요 여부 확인)를 담는다.
