# 그라파나 대시보드 개편 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행 완료(틀 범위 — Task 10 GRANT 런북·세부 다듬기는 후속 PR로 분리, 사용자 결정 08-18) · 스펙: [2026-08-18-grafana-dashboard-redesign-design.md](../specs/archive/2026-08-18-grafana-dashboard-redesign-design.md)

**Goal:** 그라파나를 홈 신호등 13타일 + 기능축 7장으로 재편한다 — 로컬 목데이터 하니스에서 UI를 보며 반복하고, 확정본만 정규 CD로 배포.

**Architecture:** 3단계. ①로컬 하니스(스키마=레포 Flyway 그대로, 시드=운영 실측 밀도 복제) ②대시보드 JSON을 레포 경로에서 직접 작성 — 하니스가 같은 디렉토리를 마운트해 60초 내 자동 반영, 브라우저로 확인·가감 ③확정 JSON + compose 변경 + GRANT 런북을 develop→staging→main CD로. 운영 서버는 ③ 전까지 불변.

**Tech Stack:** Grafana 13.1.1(운영과 동일 버전), PostgreSQL 17-alpine, Prometheus, node-exporter, cAdvisor, docker compose(로컬은 Docker Desktop — 이 머신은 colima 미설치, `DOCKER_HOST` 미설정이 정답).

## Global Constraints

- 대시보드 수정은 레포 JSON로만(`allowUiUpdates: false` 유지) — 로컬 하니스도 같은 규율.
- 데이터소스 uid는 운영과 동일하게: `hypenow-analysis-pg` / `hypenow-prometheus` / `hypenow-loki` / 신설 `hypenow-monitoring-pg`. JSON 무수정 이식의 전제.
- 신호등 타일 원칙: 타일=예/아니오 하나, 초록이면 읽을 게 없음, 클릭=담당 대시보드(스펙 §3).
- 기능 대시보드 순서 고정: 위=건강, 아래=사용량. 사용량은 stat+최근 목록이 기본, 시계열은 밀도 충분한 것만(스펙 §2).
- 운영 배포는 정규 CD만. `deploy/scripts/deploy.sh` 수동 실행 금지.
- 임계값 셋(ERROR 급증·IG 401 급증·GC pause)은 느슨한 초기값 → 배치 후 1주 실측 보정(스펙 §6). 계획의 초기값이 정본.
- 커밋 메시지는 한국어, `feat(deploy):`/`docs:` prefix.
- grep 시 `--exclude-dir=docs`(CLAUDE.md 함정 항목).

## 파일 구조

```
deploy/grafana/dev/                      # 로컬 하니스 (신규 — deploy/alloy/test/ 관례)
  compose.dev.yaml                       # postgres+grafana+prometheus+node-exporter+cadvisor
  datasources/dev.yaml                   # 운영과 같은 uid 4개, 접속만 로컬
  prometheus-dev.yml
  apply-migrations.sh                    # 레포 Flyway SQL을 버전순 psql 적용
  seed.sql                               # 실측 밀도 목데이터
  seed-red.sql                           # 빨간불 상태 재현(타일 검증용)
  README.md                              # 기동·시드·정리 절차
deploy/grafana/provisioning/dashboards/json/
  hypenow-home.json                      # 신규 — 홈 신호등
  hypenow-monitoring.json                # 신규 — 모니터링(브랜드+경쟁사+캠페인)
  hypenow-discovery.json                 # 신규 — 탐색
  hypenow-acquisition.json               # 신규 — 계정 유입
  hypenow-infra.json                     # 신규 — 인프라
  hypenow-service-overview.json          # 삭제(기능 3장으로 해체)
  hypenow-errors.json / hypenow-api-performance.json   # 유지(홈 링크 착지점)
deploy/grafana/provisioning/datasources/monitoring.yaml  # 신규 — 운영 monitoring DB
deploy/compose.yaml                      # node-exporter·cadvisor 추가
deploy/prometheus/prometheus.yml         # job 2개 추가
deploy/scripts/post-container-metrics.py # SERVICES 2개 추가
deploy/README.md                         # §14 런북 — monitoring GRANT·statement_timeout
```

---

## Phase A — 로컬 하니스

### Task 1: 하니스 compose + 데이터소스

**Files:**
- Create: `deploy/grafana/dev/compose.dev.yaml`
- Create: `deploy/grafana/dev/datasources/dev.yaml`
- Create: `deploy/grafana/dev/prometheus-dev.yml`
- Create: `deploy/grafana/dev/README.md`

**Interfaces:**
- Produces: `docker compose -f deploy/grafana/dev/compose.dev.yaml up -d`로 뜨는 로컬 스택. grafana `http://localhost:3300`(admin/admin), postgres 호스트포트 `55432`(dev/dev), 컨테이너명 prefix `grafana-dev`. Task 2·3·8이 이 포트·계정에 의존.

- [ ] **Step 1: compose.dev.yaml 작성**

```yaml
# 그라파나 대시보드 개발용 로컬 하니스 — 운영과 같은 이미지·uid, 데이터만 목.
# 기동: docker compose -f compose.dev.yaml up -d  /  정리: down -v
name: grafana-dev
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: analysis
      POSTGRES_USER: dev
      POSTGRES_PASSWORD: dev
    ports: ["127.0.0.1:55432:5432"]
    volumes:
      - ../../../db/init:/docker-entrypoint-initdb.d:ro   # analysis·monitoring DB 생성(운영 init 재사용)
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dev -d analysis"]
      interval: 5s
      timeout: 3s
      retries: 10

  grafana:
    image: grafana/grafana:13.1.1        # 운영과 동일 버전 고정
    ports: ["127.0.0.1:3300:3000"]
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: admin
      GF_ANALYTICS_REPORTING_ENABLED: "false"
      GF_ANALYTICS_CHECK_FOR_UPDATES: "false"
    volumes:
      # 대시보드 provider는 운영 파일 그대로 → 레포 JSON 수정이 60초 내 로컬 그라파나에 반영
      - ../provisioning/dashboards:/etc/grafana/provisioning/dashboards:ro
      # 데이터소스만 dev 것으로 대체(같은 uid, 접속만 로컬) — 운영 datasources·alerting은 마운트하지 않음
      - ./datasources:/etc/grafana/provisioning/datasources:ro
    depends_on:
      postgres:
        condition: service_healthy

  prometheus:
    image: prom/prometheus:v2.53.0
    volumes:
      - ./prometheus-dev.yml:/etc/prometheus/prometheus.yml:ro

  node-exporter:
    image: prom/node-exporter:v1.8.2

  cadvisor:
    image: gcr.io/cadvisor/cadvisor:v0.49.1
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - /sys:/sys:ro
```

- [ ] **Step 2: datasources/dev.yaml 작성** — uid는 운영과 동일, 접속만 로컬. loki는 스텁(로컬에 로키 없음 — Loki 타일 3개는 로컬에서 에러 표시가 정상, 운영 검증은 Task 12)

```yaml
apiVersion: 1
datasources:
  - name: HypeNow analysis (dev)
    uid: hypenow-analysis-pg
    type: postgres
    access: proxy
    url: postgres:5432
    database: analysis
    user: dev
    isDefault: true
    jsonData: { database: analysis, sslmode: disable, postgresVersion: 1700 }
    secureJsonData: { password: dev }
  - name: HypeNow monitoring (dev)
    uid: hypenow-monitoring-pg
    type: postgres
    access: proxy
    url: postgres:5432
    database: monitoring
    user: dev
    jsonData: { database: monitoring, sslmode: disable, postgresVersion: 1700 }
    secureJsonData: { password: dev }
  - name: Prometheus (dev)
    uid: hypenow-prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    jsonData: { timeInterval: 30s }
  - name: Loki (스텁 — 로컬 미기동)
    uid: hypenow-loki
    type: loki
    access: proxy
    url: http://loki:3100
```

- [ ] **Step 3: prometheus-dev.yml 작성**

```yaml
global: { scrape_interval: 30s }
scrape_configs:
  - job_name: node
    static_configs: [{ targets: ["node-exporter:9100"] }]
  - job_name: cadvisor
    static_configs: [{ targets: ["cadvisor:8080"] }]
  # was JVM 패널 개발 시 로컬 bootRun( :was:bootRun )을 붙일 때만 유효 — 없어도 무해(down 표시)
  - job_name: was
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ["host.docker.internal:9081"] }]
```

- [ ] **Step 4: 기동 검증**

Run: `cd deploy/grafana/dev && docker compose -f compose.dev.yaml up -d && sleep 20 && curl -s http://localhost:3300/api/health && curl -s http://localhost:9090/api/v1/targets 2>/dev/null | head -c200 || docker compose -f compose.dev.yaml ps`
Expected: grafana health `"database": "ok"`, 컨테이너 5개 Up. (prometheus는 호스트 포트 미노출이므로 ps로 Up만 확인)

- [ ] **Step 5: README.md 작성** (기동/시드/정리 3절 — Step 1~4 명령 + `down -v` 정리 + "운영과 다른 점: 데이터 전부 목, loki 스텁") **후 커밋**

```bash
git add deploy/grafana/dev && git commit -m "feat(deploy): 그라파나 대시보드 개발용 로컬 하니스 — 운영 동일 uid·이미지, 목 DB"
```

### Task 2: 스키마 적용 스크립트

**Files:**
- Create: `deploy/grafana/dev/apply-migrations.sh`

**Interfaces:**
- Consumes: Task 1의 postgres(localhost:55432, dev/dev)
- Produces: analysis DB(public=분석 결과 + app 스키마) / monitoring DB 스키마 완성. Task 3 시드의 전제.

- [ ] **Step 1: apply-migrations.sh 작성** — 레포 Flyway SQL을 **버전 숫자순**으로 psql 적용. 주의: 사전순은 틀린다(`V2__` > `V20260730…` 사전순) — 버전 추출 후 `sort -n`. Flyway 이력 테이블은 만들지 않는다(하니스는 1회성, `down -v`로 리셋).

```bash
#!/usr/bin/env bash
# 레포 Flyway SQL을 버전순으로 로컬 하니스 DB에 적용한다. 사용: ./apply-migrations.sh
set -euo pipefail
cd "$(dirname "$0")/../../.."   # 레포 루트
PSQL="docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -q -U dev"

apply_dir() { # $1=디렉토리 $2=DB $3=적용 전 SQL(옵션)
  [ -n "${3:-}" ] && echo "$3" | $PSQL -d "$2"
  ls "$1" | awk -F'__' '{v=$1; sub(/^V/,"",v); print v, $0}' | sort -n | cut -d' ' -f2- | \
  while read -r f; do echo "  $2 <- $f"; $PSQL -d "$2" < "$1/$f"; done
}

# 마이그레이션이 GRANT하는 롤을 선생성(운영은 런북 소관 — CREATE ROLE은 Flyway 밖)
ROLES="DO \$\$ BEGIN
  CREATE ROLE was_reader; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$;
DO \$\$ BEGIN CREATE ROLE alarm_reader; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$;"

apply_dir analytics/src/main/resources/db/migration/analysis analysis "$ROLES"
apply_dir was/src/main/resources/db/migration/app analysis
apply_dir monitoring/src/main/resources/db/migration monitoring "$ROLES"
echo "완료"
```

- [ ] **Step 2: 실행 → 실패 시 교정.** app 스키마 일부 파일이 `SET search_path` 등 전제를 가질 수 있다 — 실패한 파일을 열어 전제(스키마 생성·롤)를 스크립트 상단에 보강하고 재실행(`down -v && up -d` 후). 반복해서 3개 DB 스키마가 끝까지 적용되게 한다.

Run: `bash deploy/grafana/dev/apply-migrations.sh`
Expected: 마지막 줄 `완료`, 에러 없음

- [ ] **Step 3: 스키마 검증**

Run: `docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -U dev -d monitoring -c "\dt" | grep -c "" && docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -U dev -d analysis -c "\dt app.*" | grep -c ""`
Expected: monitoring 테이블 20개 내외(target·sweep_run·brand_call_count 포함), app 테이블 20개 내외

- [ ] **Step 4: 커밋**

```bash
git add deploy/grafana/dev/apply-migrations.sh && git commit -m "feat(deploy): 하니스 스키마 적용 스크립트 — 레포 Flyway SQL 버전순 적용"
```

### Task 3: 실측 밀도 시드

**Files:**
- Create: `deploy/grafana/dev/seed.sql`
- Create: `deploy/grafana/dev/seed-red.sql`

**Interfaces:**
- Consumes: Task 2가 만든 스키마
- Produces: 패널이 그릴 데이터. **밀도가 스펙의 존재 이유** — 2026-08-18 운영 실측(스펙 §9)을 규모·분포까지 복제한다.

- [ ] **Step 1: seed.sql 작성** — 아래 골격으로 시작하고, NOT NULL·CHECK 위반이 나면 해당 마이그레이션 DDL을 열어 컬럼을 보강한다. 핵심 원칙: **행 수는 실측과 같게**(가입 53·브랜드 130·타깃 73·콜 30일 ≈49,756), 시각 분포는 `generate_series`로 퍼뜨린다.

```sql
-- ===== analysis DB (psql -d analysis) =====
-- 유저 53명, 최근 90일 분산(하루 0~2명꼴 — "빈 판 시계열" 재현이 목적)
INSERT INTO app.users (email, password_hash, created_at)
SELECT 'user'||g||'@mock.test', 'x', now() - (random()*90 || ' days')::interval
FROM generate_series(1,53) g;

INSERT INTO app.saved_influencers (user_id, handle) SELECT (g%53)+1, 'handle'||g FROM generate_series(1,9) g;
INSERT INTO app.saved_contents (user_id, short_code) SELECT (g%53)+1, 'SC'||g FROM generate_series(1,4) g;
INSERT INTO app.inquiries (id, created_at) SELECT gen_random_uuid(), now() - interval '20 days' - (g||' days')::interval FROM generate_series(1,6) g;  -- 마지막 문의 07-29꼴(오래됨)

INSERT INTO app.monitoring_campaigns (user_id, name, created_at)
SELECT (g%10)+1, '캠페인'||g, now() - (random()*60||' days')::interval FROM generate_series(1,12) g;
INSERT INTO app.monitoring_registrations (user_id, requested_at, completed_at)
SELECT (g%10)+1, now() - (g||' days')::interval, now() - (g||' days')::interval + interval '3 minutes'
FROM generate_series(1,20) g;
INSERT INTO app.brand_monitorings (user_id, brand_id, username, created_at)
SELECT (g%53)+1, g, 'brand'||g, now() - (random()*45||' days')::interval FROM generate_series(1,130) g;
-- 미러 신선도 대용 지표: 2시간 전 갱신(초록 상태)
INSERT INTO landing_stats DEFAULT VALUES;  -- 실패 시 V32__landing_stats.sql의 컬럼으로 보강
UPDATE landing_stats SET updated_at = now() - interval '2 hours';
-- 탐색 미러 규모: accounts·contents에 소량(카운트 패널용 — 각 200·2400건 수준)
--   V1__serving_tables.sql의 NOT NULL 컬럼 확인 후 generate_series INSERT 작성

-- ===== monitoring DB (psql -d monitoring) =====
-- 타깃 73(TRACKING 44 / WATCHING 26 / 종결 3), 스윕 30일 성공
INSERT INTO target (type, username, status, registration_key, registered_at, user_id, tracked_short_code, tracked_since)
SELECT CASE WHEN g%3=0 THEN 'POST' ELSE 'ACCOUNT' END, 'acct'||g,
       CASE WHEN g<=44 THEN 'TRACKING' WHEN g<=70 THEN 'WATCHING' ELSE 'CLOSED' END,
       gen_random_uuid(), now() - (random()*60||' days')::interval, (g%10)+1,
       CASE WHEN g<=44 THEN 'TSC'||g END, CASE WHEN g<=44 THEN now() - (random()*30||' days')::interval END
FROM generate_series(1,73) g;
INSERT INTO sweep_run (started_at, completed_at, ok)
SELECT d, d + interval '40 minutes', true
FROM generate_series(now() - interval '29 days', now() - interval '10 hours', interval '1 day') d;  -- 마지막 스윕 10시간 전(초록)
-- Hiker 콜: 브랜드 30일 ≈49,756 (브랜드 130 × 일 12~13콜), 타깃 ≈3,536
INSERT INTO brand_call_count (brand_id, called_on, calls)
SELECT b, current_date - d, 10 + (random()*5)::int
FROM generate_series(1,130) b, generate_series(0,29) d;
INSERT INTO target_call_count (user_id, called_on, calls)
SELECT u, current_date - d, 3 + (random()*3)::int
FROM generate_series(1,10) u, generate_series(0,29) d ON CONFLICT DO NOTHING;
-- 알림 대장 97건, 전부 발송 성공(초록)
INSERT INTO alarm_event (target_id, user_id, event_type, occurred_at, email_status, email_attempts, email_sent_at)
SELECT (g%44)+1, (g%10)+1, 'TRACKING_STARTED', now() - (random()*30||' days')::interval, 'SENT', 1, now()
FROM generate_series(1,97) g;
-- 해시태그·태그드 게시물(모니터링 대시보드 테이블 패널 밀도용 — 실측 2,543·28,255)
INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username, taken_at, content_type, likes, comments, verdict, first_seen_at)
SELECT (g%130)+1, 'HP'||g, '태그'||(g%150), 'author'||(g%400), now()-(random()*90||' days')::interval, 'reel', (random()*5000)::int, (random()*200)::int, 'RELEVANT', now()-(random()*30||' days')::interval
FROM generate_series(1,2543) g;
INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at, first_seen_at)
SELECT (g%130)+1, 'TP'||g, 'author'||(g%2000), now()-(random()*180||' days')::interval, now()-(random()*40||' days')::interval
FROM generate_series(1,28255) g;
```

- [ ] **Step 2: 적용·교정 반복** — CHECK/NOT NULL 위반이 나오는 테이블은 해당 DDL 파일을 열어 유효값으로 교정(예: `target.status`·`alarm_event.event_type`·`email_status`의 CHECK 어휘는 `V1__core_tables.sql`·`V3__user_id_and_alarm_event.sql`이 정본 — email_status는 `PENDING/SENT/FAILED` 계열).

Run: `docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -q -U dev -d analysis < deploy/grafana/dev/seed.sql` (파일 내 안내대로 monitoring 구간은 `-d monitoring`으로 분리 실행 — 파일을 `seed.sql`(analysis)·구간 주석으로 관리)
Expected: 에러 없음

- [ ] **Step 3: 밀도 검증**

Run: `docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -U dev -d monitoring -t -c "SELECT sum(calls) FROM brand_call_count"` 외 users·brand_monitorings·target 카운트
Expected: 콜 합계 39,000~58,500(130×30×10~15), users 53, brand_monitorings 130, target 73

- [ ] **Step 4: seed-red.sql 작성** — 홈 타일의 빨간불을 전부 켜는 상태 변형(검증용, 적용→확인→`down -v`+재시드로 복원)

```sql
-- analysis DB
UPDATE landing_stats SET updated_at = now() - interval '30 hours';                    -- 타일7 빨강
INSERT INTO app.monitoring_registrations (user_id, requested_at) VALUES (1, now() - interval '2 hours');  -- 타일8 빨강(멈춘 등록)
-- monitoring DB
DELETE FROM sweep_run WHERE started_at > now() - interval '3 days';                   -- 타일5 빨강(신선도 초과)
DELETE FROM brand_call_count WHERE called_on = current_date;
DELETE FROM target_call_count WHERE called_on = current_date;                         -- 타일6 빨강(오늘 0콜)
UPDATE alarm_event SET email_status='FAILED', email_sent_at=NULL
WHERE id IN (SELECT id FROM alarm_event ORDER BY occurred_at DESC LIMIT 3);           -- 타일9 빨강
```

- [ ] **Step 5: 커밋**

```bash
git add deploy/grafana/dev/seed.sql deploy/grafana/dev/seed-red.sql && git commit -m "feat(deploy): 하니스 목 시드 — 08-18 운영 실측 밀도 복제 + 빨간불 재현 시드"
```

---

## Phase B — 대시보드 JSON v1

**공통 관용구** (모든 대시보드 태스크에 적용): 기존 [hypenow-errors.json](../../deploy/grafana/provisioning/dashboards/json/hypenow-errors.json)의 스켈레톤(schemaVersion·refresh `5m`·row 구조·stat fieldConfig)을 복사해 시작한다. 데이터소스 참조는 `{"type":"postgres","uid":"hypenow-monitoring-pg"}` 식 uid 고정. 타일 클릭 이동은 패널 `links`: `[{"title":"모니터링 대시보드로","url":"/d/hypenow-monitoring"}]`. 시간창: 홈 `now-24h`, 기능 `now-7d`, 인프라 `now-6h`.

### Task 4: 홈 「지금 정상인가」 — hypenow-home.json

**Files:**
- Create: `deploy/grafana/provisioning/dashboards/json/hypenow-home.json` (uid `hypenow-home`, title `HypeNow 홈 — 지금 정상인가`)

**Interfaces:**
- Consumes: 데이터소스 uid 4종, Task 3 시드
- Produces: 타일 13개(스펙 §3 표와 1:1). 이후 태스크의 대시보드 uid(`hypenow-monitoring`·`hypenow-discovery`·`hypenow-acquisition`·`hypenow-infra`)로 링크.

- [ ] **Step 1: 3개 row + stat 13개 작성.** 전 타일 `type: stat`, `graphMode: none`, `textMode: value_and_name`. 쿼리·임계값:

| # | 타일 | ds | 쿼리 | 임계(빨강) |
|---|---|---|---|---|
| 1 | API 5xx | prometheus | `sum(rate(http_server_requests_seconds_count{status=~"5..",uri!~"UNKNOWN\|root\|/actuator.*"}[1h])) / sum(rate(http_server_requests_seconds_count{uri!~"UNKNOWN\|root\|/actuator.*"}[1h]))` (unit percentunit) | ≥0.01 |
| 2 | ERROR 급증 | loki | `sum(count_over_time({service=~".+", level="ERROR"}[1h]))` | ≥30(느슨 초기값) |
| 3 | Hiker 402 | loki | `sum(count_over_time({service=~"crawler\|monitoring"} \|~ ` + "`(?:HTTP|Hiker) 402`" + ` [24h]))` | ≥1 |
| 4 | IG 401 | loki | 같은 식의 401, `[24h]` | ≥50(느슨 초기값) |
| 5 | 스윕 신선도 | monitoring-pg | `SELECT EXTRACT(EPOCH FROM (now() - max(started_at)))/3600 AS value FROM sweep_run WHERE ok` (unit h, decimals 0) | >26 |
| 6 | 오늘 Hiker 콜 | monitoring-pg | `SELECT coalesce((SELECT sum(calls) FROM brand_call_count WHERE called_on=current_date),0) + coalesce((SELECT sum(calls) FROM target_call_count WHERE called_on=current_date),0) AS value` | ≥3300(7일 평균 ~1,650의 2배) + **valueMappings로 `0` → 빨강·텍스트 "0 — 스윕 불발?"** |
| 7 | 미러 신선도 | analysis-pg | `SELECT EXTRACT(EPOCH FROM (now() - max(updated_at)))/3600 AS value FROM landing_stats` (unit h) | >26 |
| 8 | 멈춘 등록 | analysis-pg | `SELECT count(*) AS value FROM app.monitoring_registrations WHERE completed_at IS NULL AND requested_at < now() - interval '30 minutes'` (기존 알림과 같은 30분 기준) | ≥1 |
| 9 | 알림 발송 실패 | monitoring-pg | `SELECT count(*) AS value FROM alarm_event WHERE email_status='FAILED' AND occurred_at > now() - interval '24 hours'` | ≥1 |
| 10 | 호스트 CPU | prometheus | `100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[15m])))` (unit percent) | ≥85 |
| 11 | 호스트 메모리 | prometheus | `node_memory_MemAvailable_bytes` (unit bytes) | **역방향**: <1GiB 빨강 — thresholds steps `[{red,null},{green,1073741824}]` |
| 12 | was JVM | prometheus | `100 * sum(jvm_memory_used_bytes{area="heap",id=~".*Old.*"}) / sum(jvm_memory_max_bytes{area="heap",id=~".*Old.*"})` (unit percent) | ≥85 |
| 13 | DB 커넥션 풀 | prometheus | `max(hikaricp_connections_pending)` | ≥1 |

- [ ] **Step 2: 로컬 확인** — 60초 내 자동 로드. `http://localhost:3300/d/hypenow-home` 접속(브라우저 도구). Expected: 타일 5~11 초록으로 렌더(값 표시), 2·3·4는 loki 스텁 에러(정상), 12·13은 was 미기동 시 No data(정상 — 패널 골격만 확인)
- [ ] **Step 3: seed-red.sql 적용 → 타일 5·6·7·8·9 빨강 전환 확인 → `down -v` 후 Task 1 Step4 + Task 2 + Task 3 재실행으로 복원**
- [ ] **Step 4: 커밋** `git add deploy/grafana/provisioning/dashboards/json/hypenow-home.json && git commit -m "feat(deploy): 홈 대시보드 — 신호등 13타일"`

### Task 5: 모니터링 — hypenow-monitoring.json

**Files:**
- Create: `deploy/grafana/provisioning/dashboards/json/hypenow-monitoring.json` (uid `hypenow-monitoring`)

- [ ] **Step 1: 위 절「건강」** — row + 패널 5:
  - [stat] 마지막 스윕: 타일5와 동일 쿼리 재사용
  - [timeseries] 스윕 이력 30일: `SELECT started_at AS time, EXTRACT(EPOCH FROM (completed_at-started_at))/60 AS "소요(분)" FROM sweep_run WHERE started_at > now()-interval '30 days' ORDER BY 1`
  - [stat] fetch 실패 타깃: `SELECT count(*) AS value FROM target WHERE fetch_failing AND status='TRACKING'` (빨강 ≥1)
  - [timeseries] 감지 전환 추이(정보성 — 신호등 아님, 패널 설명에 "0이 정상일 수 있음" 명기): `SELECT date_trunc('day', tracked_since) AS time, count(*) AS "WATCHING→TRACKING" FROM target WHERE type='ACCOUNT' AND tracked_since IS NOT NULL AND tracked_since > now()-interval '30 days' GROUP BY 1 ORDER BY 1`
  - [table] 발송 실패 알림: `SELECT occurred_at, event_type, email_attempts FROM alarm_event WHERE email_status='FAILED' ORDER BY occurred_at DESC LIMIT 20`
- [ ] **Step 2: 가운데 절「비용」** — 밀도 충분(콜 30일 5만) → 시계열 제값:
  - [timeseries] 일별 Hiker 콜: `SELECT called_on AS time, sum(calls) AS "브랜드" FROM brand_call_count WHERE called_on > current_date-30 GROUP BY 1 ORDER BY 1` + 두 번째 쿼리로 `target_call_count` 동일 형식 "캠페인"
  - [table] 브랜드별 상위 콜 7일: `SELECT brand_id, sum(calls) AS calls FROM brand_call_count WHERE called_on > current_date-7 GROUP BY 1 ORDER BY 2 DESC LIMIT 15`
- [ ] **Step 3: 아래 절「사용량」** — stat+목록(시계열 금지 — 밀도 미달):
  - [stat] 브랜드 모니터링: `SELECT count(*) FILTER (WHERE deleted_at IS NULL) AS "활성", count(*) FILTER (WHERE created_at > now()-interval '7 days') AS "이번주 신규" FROM app.brand_monitorings` (ds analysis-pg)
  - [stat] 추적 타깃: `SELECT count(*) FILTER (WHERE status='TRACKING') AS "추적중", count(*) FILTER (WHERE status='WATCHING') AS "감시중" FROM target`
  - [stat] 캠페인: `SELECT count(*) AS value FROM app.monitoring_campaigns`
  - [table] 최근 등록: `SELECT created_at, username, CASE WHEN deleted_at IS NULL THEN '활성' ELSE '해지' END AS 상태 FROM app.brand_monitorings ORDER BY created_at DESC LIMIT 15`
- [ ] **Step 4: 로컬 확인 후 커밋** — Expected: 전 패널 데이터 렌더. `git commit -m "feat(deploy): 모니터링 대시보드 — 건강·비용·사용량"`

### Task 6: 탐색 + 계정 유입

**Files:**
- Create: `deploy/grafana/provisioning/dashboards/json/hypenow-discovery.json` (uid `hypenow-discovery`)
- Create: `deploy/grafana/provisioning/dashboards/json/hypenow-acquisition.json` (uid `hypenow-acquisition`)

- [ ] **Step 1: 탐색 — 건강 절**: [stat] 미러 신선도(타일7 쿼리 재사용), [stat] 랭킹 산출 규모 `SELECT (SELECT count(*) FROM accounts) AS "계정", (SELECT count(*) FROM contents) AS "콘텐츠"` (ds analysis-pg)
- [ ] **Step 2: 탐색 — 사용량 절**: [stat] `SELECT (SELECT count(*) FROM app.saved_influencers) AS "저장 인플루언서", (SELECT count(*) FROM app.saved_contents) AS "저장 콘텐츠"`, [table] 최근 저장 `SELECT created_at, handle, status FROM app.saved_influencers ORDER BY created_at DESC LIMIT 10` (created_at 부재 시 DDL 확인해 교정)
- [ ] **Step 3: 계정 유입 — 건강 절**: 기존 서비스현황의 「처리 중 멈춘 등록」·「└ 상세」 패널을 hypenow-service-overview.json에서 복사 이전(쿼리 그대로 — 이미 검증된 쿼리)
- [ ] **Step 4: 계정 유입 — 사용량 절**: [stat] 가입 `SELECT count(*) AS "누적", count(*) FILTER (WHERE created_at > now()-interval '7 days') AS "이번주" FROM app.users`, [stat] 가입 코드 소진(기존 패널 복사 이전), [stat] 문의 `SELECT count(*) AS "누적", count(*) FILTER (WHERE created_at > now()-interval '7 days') AS "이번주" FROM app.inquiries`, [table] 최근 가입 `SELECT created_at, left(email, 3)||'***' AS email FROM app.users ORDER BY created_at DESC LIMIT 10`. **일별 timeseries는 만들지 않는다**(스펙 §2 — 기존 가입 추이·문의 추이 패널은 이전하지 않고 폐기).
- [ ] **Step 5: 로컬 확인 후 커밋** `git commit -m "feat(deploy): 탐색·계정 유입 대시보드 — stat+목록 형식"`

### Task 7: 인프라 — hypenow-infra.json

**Files:**
- Create: `deploy/grafana/provisioning/dashboards/json/hypenow-infra.json` (uid `hypenow-infra`)

- [ ] **Step 1: 위 절「호스트」** (ds prometheus, 로컬 node-exporter 실데이터로 개발):
  - [timeseries] CPU: `100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m])))`
  - [timeseries] 메모리 가용: `node_memory_MemAvailable_bytes` (1GiB 빨강 임계선 표시)
  - [timeseries] 디스크 사용률: `100 * (1 - node_filesystem_avail_bytes{mountpoint="/",fstype!~"tmpfs.*"} / node_filesystem_size_bytes{mountpoint="/",fstype!~"tmpfs.*"})`
  - [timeseries] 네트워크: `rate(node_network_receive_bytes_total{device!~"lo|veth.*"}[5m])` + transmit 동형
- [ ] **Step 2: 아래 절「컨테이너·JVM」**:
  - [timeseries] 컨테이너 메모리(**mem_limit 대비 %**): `100 * container_memory_working_set_bytes{name=~".+"} / container_spec_memory_limit_bytes{name=~".+"} != +Inf` (legend `{{name}}`)
  - [timeseries] 컨테이너 CPU: `rate(container_cpu_usage_seconds_total{name=~".+"}[5m])` (legend `{{name}}`)
  - [timeseries] was 힙: `jvm_memory_used_bytes{area="heap"}` by id / [timeseries] GC pause: `rate(jvm_gc_pause_seconds_sum[5m])` / [timeseries] HikariCP: `hikaricp_connections_active`, `hikaricp_connections_pending`
- [ ] **Step 3: 로컬 확인** — 호스트·컨테이너 절은 로컬 실데이터 렌더 확인(Docker Desktop VM 기준이라 일부 cgroup 지표 공백 가능 — 패널 골격·쿼리 문법 검증이 목적). JVM 절은 `:was:bootRun` 기동 시에만 데이터(선택).
- [ ] **Step 4: 커밋** `git commit -m "feat(deploy): 인프라 대시보드 — 호스트·컨테이너·JVM"`

### Task 8: 사용자 UI 리뷰 반복 ⟵ 사람 개입 지점

- [ ] **Step 1**: 브라우저로 `http://localhost:3300` 5장 순회를 사용자와 함께 확인 — 패널 가감·배치·문구를 즉석 반영(JSON 수정 → 60초 자동 반영). seed-red로 빨간 상태도 시연.
- [ ] **Step 2**: 가감이 끝나면 확정 커밋 `git commit -am "feat(deploy): 대시보드 v1 확정 — UI 리뷰 반영"`

---

## Phase C — 코드화·배포 준비

### Task 9: 운영 인프라 변경 (compose·prometheus·datasource)

**Files:**
- Modify: `deploy/compose.yaml` (grafana 서비스 뒤에 2개 추가)
- Modify: `deploy/prometheus/prometheus.yml`
- Modify: `deploy/scripts/post-container-metrics.py:26` (SERVICES)
- Create: `deploy/grafana/provisioning/datasources/monitoring.yaml`

- [ ] **Step 1: compose.yaml에 node-exporter·cadvisor 추가** — 관측 스택 규율(oom_score_adj 500, 압박 시 먼저 죽는 쪽) 그대로:

```yaml
  # 호스트·컨테이너 지표(대시보드 개편 스펙 2026-08-18) — 죽음 감시는 OCI 알람(푸시),
  # 여기는 상태·추이(풀) 담당. 관측 스택이므로 압박 시 먼저 죽도록 oom_score_adj 500.
  node-exporter:
    image: prom/node-exporter:v1.8.2
    restart: unless-stopped
    logging: *logging
    networks: [prod]
    mem_limit: 64m
    oom_score_adj: 500
    pid: host
    command: ["--path.rootfs=/rootfs"]
    volumes: ["/:/rootfs:ro"]

  cadvisor:
    image: gcr.io/cadvisor/cadvisor:v0.49.1
    restart: unless-stopped
    logging: *logging
    networks: [prod]
    mem_limit: 256m
    oom_score_adj: 500
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - /sys:/sys:ro
      - /var/lib/docker:/var/lib/docker:ro
```

- [ ] **Step 2: prometheus.yml에 job 2개 추가** (Task 1 Step 3의 node·cadvisor job과 동일 — targets만 `node-exporter:9100`·`cadvisor:8080`)
- [ ] **Step 3: post-container-metrics.py SERVICES에 `"node-exporter", "cadvisor"` 추가** (사라지면 0 게시 → OCI 알람 규율)
- [ ] **Step 4: datasources/monitoring.yaml 작성** — postgres.yaml 관례(파일 프로비저닝·editable false·주의 주석) 복제:

```yaml
# monitoring DB 읽기 전용(대시보드 개편 스펙 2026-08-18) — 롤은 grafana_reader 재사용
# (Postgres 롤은 클러스터 전역 — README §14-2 런북이 monitoring DB GRANT를 추가).
apiVersion: 1
datasources:
  - name: HypeNow monitoring (읽기 전용)
    uid: hypenow-monitoring-pg
    type: postgres
    access: proxy
    url: postgres:5432
    database: monitoring
    user: grafana_reader
    editable: false
    jsonData:
      database: monitoring   # 최상위만으로는 패널이 안 돈다 — postgres.yaml의 08-02 실측 주석 참조
      sslmode: disable
      postgresVersion: 1700
      maxOpenConns: 5
      maxIdleConns: 2
      connMaxLifetime: 14400
    secureJsonData:
      password: $__env{GRAFANA_READER_PASSWORD}
```

- [ ] **Step 5: 커밋** `git commit -m "feat(deploy): 인프라 수집 추가 — node-exporter·cAdvisor + monitoring DB 데이터소스"`

### Task 10: GRANT 추출 → 런북 갱신 + 구 대시보드 제거

**Files:**
- Modify: `deploy/README.md` (§14 런북)
- Delete: `deploy/grafana/provisioning/dashboards/json/hypenow-service-overview.json`

- [ ] **Step 1: 확정 JSON에서 참조 테이블·컬럼 기계 추출**

Run: `python3 -c "import json,glob,re; [print(sorted(set(re.findall(r'(?:FROM\|JOIN)\s+([a-z_.]+)', t.get('rawSql',''))))) for f in glob.glob('deploy/grafana/provisioning/dashboards/json/hypenow-*.json') for p in json.load(open(f)).get('panels',[]) for t in p.get('targets',[])]" | sort -u`
Expected: 참조 테이블 전수 목록 — 이걸로 Step 2 GRANT 목록을 검산(빠진 테이블 없어야 함)

- [ ] **Step 2: README §14에 「14-3. 대시보드 개편 GRANT (1회, 수동)」 추가** — v1 패널 기준 정확 목록(Step 1 결과로 검산·보정). 폭주 쿼리 방어 `statement_timeout` 포함:

```bash
ssh ubuntu@<IP> 'docker exec -i deploy-postgres-1 psql -U $DB_USER -d postgres \
  -c "ALTER ROLE grafana_reader SET statement_timeout = '\''5s'\''" \
  -c "GRANT CONNECT ON DATABASE monitoring TO grafana_reader"'
ssh ubuntu@<IP> 'docker exec -i deploy-postgres-1 psql -U $DB_USER -d monitoring \
  -c "GRANT USAGE ON SCHEMA public TO grafana_reader" \
  -c "GRANT SELECT (started_at, completed_at, ok) ON sweep_run TO grafana_reader" \
  -c "GRANT SELECT (brand_id, called_on, calls) ON brand_call_count TO grafana_reader" \
  -c "GRANT SELECT (user_id, called_on, calls) ON target_call_count TO grafana_reader" \
  -c "GRANT SELECT (event_type, occurred_at, email_status, email_attempts) ON alarm_event TO grafana_reader" \
  -c "GRANT SELECT (type, status, username, tracked_since, fetch_failing) ON target TO grafana_reader"'
ssh ubuntu@<IP> 'docker exec -i deploy-postgres-1 psql -U $DB_USER -d analysis \
  -c "GRANT SELECT (updated_at) ON landing_stats TO grafana_reader" \
  -c "GRANT SELECT (handle) ON accounts TO grafana_reader" \
  -c "GRANT SELECT (short_code) ON contents TO grafana_reader" \
  -c "GRANT SELECT (created_at, username, deleted_at) ON app.brand_monitorings TO grafana_reader" \
  -c "GRANT SELECT (created_at) ON app.monitoring_campaigns TO grafana_reader" \
  -c "GRANT SELECT (created_at) ON app.inquiries TO grafana_reader" \
  -c "GRANT SELECT (user_id, handle, status, created_at) ON app.saved_influencers TO grafana_reader" \
  -c "GRANT SELECT (user_id, short_code) ON app.saved_contents TO grafana_reader" \
  -c "GRANT SELECT (email) ON app.users TO grafana_reader"'
```

- [ ] **Step 3: hypenow-service-overview.json 삭제** — 해체 대상 패널(멈춘 등록·가입 코드)은 Task 6에서 이미 이전됨. 유일하게 이전 안 된 「세션 수」·「다이제스트 발송·읽음」·「등록 결과 분포」는 의도적 폐기(밀도 미달 시계열 + 세션 수는 신호 아님 — 스펙 §2·§4).
- [ ] **Step 4: 커밋** `git commit -m "feat(deploy): GRANT 런북 갱신 + 서비스 현황 대시보드 해체(기능 3장으로 이전)"`

### Task 11: 기록·PR

- [ ] **Step 1: DECISIONS.md 맨 위에 결정 1행 추가** — "그라파나 대시보드 개편 — 홈 신호등 13타일 + 기능축 7장(탐색·모니터링·계정유입 + 에러·API성능·인프라), 데이터소스 축→목적 축, 사용량은 stat+목록(밀도 미달 시계열 폐기), monitoring DB 데이터소스 신설, node-exporter·cAdvisor 추가, SaaS 전환 기각" + 스펙 링크
- [ ] **Step 2: 스펙 상태 헤더를 `✅ 구현/실행/반영됨`으로, 이 plan을 `docs/superpowers/plans/archive/`로 이동** (CLAUDE.md 세션 위생 — PR과 같은 커밋에)
- [ ] **Step 3: 전체 스모크** — 하니스 `down -v` → up → apply-migrations → seed → 5장 렌더 재확인(회귀 검증)
- [ ] **Step 4: PR 생성** (develop 대상)

```bash
git push -u origin feature/dashboard-redesign-869bee
gh pr create --base develop --title "feat(deploy): 그라파나 대시보드 개편 — 홈 신호등 + 기능축 7장" --body "$(cat <<'EOF'
## 요약
- 대시보드 축을 데이터소스→목적으로 재편: 홈(신호등 13타일) + 탐색/모니터링/계정유입 + 에러/API성능/인프라
- monitoring DB 데이터소스 신설(스윕·감지·Hiker 콜 = 비용 축 개통), node-exporter·cAdvisor 추가
- 사용량 패널은 stat+최근 목록로 전환(실측 밀도 기준 — 빈 판 시계열 폐기), 서비스 현황 해체
- 로컬 목 하니스(deploy/grafana/dev) 동봉 — 스키마=레포 Flyway, 시드=08-18 실측 밀도

## 배포 순서(머지 후)
1. staging 승격 → cd-test로 test 환경 확인
2. **README §14-3 GRANT 런북 실행(수동 1회)** — 이거 전엔 새 패널이 전부 No data
3. main 승격 → 운영. Loki 타일 3개·JVM 타일은 운영에서 최종 확인(스펙 §6 임계 보정은 1주 후)

스펙: docs/superpowers/specs/2026-08-18-grafana-dashboard-redesign-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review 결과

- 스펙 커버리지: §3 홈 13타일→Task 4, §4 기능·횡단→Task 5·6·7, §5 인프라 변경·런북→Task 9·10, §6 임계 유보→Global Constraints·PR 본문, §2 형식 규칙→Task 6 Step 4 명기. 커버 안 되는 스펙 항목 없음.
- 타입 일관성: 대시보드 uid 5종(`hypenow-home/-monitoring/-discovery/-acquisition/-infra`)이 Task 4 링크·Task 10 추출·PR 본문에서 동일. 데이터소스 uid 4종 동일.
- 알려진 불확실성(계획에 명시): seed의 일부 컬럼은 DDL 확인 후 보강(Task 2 Step 2·Task 3 Step 2의 교정 루프가 흡수), Loki·JVM 패널은 로컬 검증 불가(Task 12 아님 — PR 본문 배포 순서 3에 운영 확인으로 명시).
