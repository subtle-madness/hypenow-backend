# 쿼리·API 성능 측정 스택 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 · 2026-08-10 작성 · 스펙: [2026-08-10-query-performance-observability-design.md](../specs/2026-08-10-query-performance-observability-design.md)

**Goal:** 운영 서버에 API 레이턴시(p95/p99)·느린 SQL·배포에도 살아남는 로그를 측정할 수 있는
PLG(Prometheus + Loki + 기존 Grafana) + pg_stat_statements 스택을 구축한다.

**Architecture:** was에 Actuator/Micrometer를 붙여 관리 포트(9081, 내부망 전용)로 지표를 노출하고,
신규 컨테이너 3개(prometheus·loki·alloy)가 수집·저장한다. 시각화는 기존 Grafana에 데이터소스·대시보드
프로비저닝만 추가. DB층은 postgres 컨테이너에 pg_stat_statements·슬로우 쿼리 로그를 켠다.

**Tech Stack:** Spring Boot 4.1 Actuator + micrometer-registry-prometheus /
prom/prometheus v3 / grafana/loki 3.5 / grafana/alloy 1.8 / postgres 17 pg_stat_statements

## Global Constraints

- 주석·커밋 메시지는 한국어, 커밋 prefix는 `feat(모듈):`/`docs:` 식 (CLAUDE.md 컨벤션)
- 신규 컨테이너는 기존 compose 관례 준수: `logging: *logging` 앵커 + `mem_limit` +
  `oom_score_adj`(관측 도구는 **+500** — 메모리 압박 시 서비스보다 먼저 희생) + 호스트 포트 미노출
- 모든 신규 저장소에 크기·보관 상한 필수 (디스크 85% 알람 예민 — 스펙 제약)
- 테스트는 모듈 단위: `./gradlew :was:test` (전체 `./gradlew test`는 PR 직전에만)
- 로컬 도커는 Docker Desktop — `DOCKER_HOST` 설정 불필요 (08-09 확인, colima 아님)
- Spring Boot 4 주의: `@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지
- 이 작업에 DB 스키마 변경(Flyway) 없음 — expand-contract 무관
- 브랜치는 현 worktree(`feature/server-query-performance-tools-26b7e9`)에서 작업, develop 대상 PR

## File Structure

```
was/build.gradle                                        # 수정: actuator·prometheus 레지스트리 의존성
was/src/main/resources/application.yml                  # 수정: management 노출·히스토그램 설정
was/src/main/resources/application-prod.yml             # 수정: 관리 포트 9081 분리(운영만)
was/src/main/java/com/celfit/was/config/SecurityConfig.java  # 수정: /actuator/** permitAll 체인
was/src/test/java/com/celfit/was/ActuatorPrometheusTest.java # 생성: 노출·시큐리티·히스토그램 검증
deploy/compose.yaml                                     # 수정: postgres 플래그, 신규 서비스 3개, 볼륨 2개
deploy/prometheus/prometheus.yml                        # 생성: was 스크레이프 설정
deploy/loki/loki-config.yaml                            # 생성: 파일시스템 저장·30일 보관
deploy/alloy/config.alloy                               # 생성: 도커 로그 수집 → Loki push
deploy/grafana/provisioning/datasources/observability.yaml   # 생성: prometheus·loki 데이터소스
deploy/grafana/provisioning/dashboards/json/hypenow-api-performance.json  # 생성: API 성능 대시보드
deploy/README.md                                        # 수정: §15 개통 런북(확장 생성·pg_monitor·순서)
DECISIONS.md                                            # 수정: 결정 기록 맨 위 추가
```

---

### Task 1: was Actuator + Micrometer 계측

**Files:**
- Modify: `was/build.gradle`
- Modify: `was/src/main/resources/application.yml`
- Modify: `was/src/main/resources/application-prod.yml`
- Modify: `was/src/main/java/com/celfit/was/config/SecurityConfig.java`
- Test: `was/src/test/java/com/celfit/was/ActuatorPrometheusTest.java`

**Interfaces:**
- Consumes: 기존 `IntegrationTest` 베이스 클래스(Testcontainers Postgres 싱글턴)
- Produces: 운영에서 `http://was:9081/actuator/prometheus` (도커 prod 네트워크 내부 전용) —
  Task 3의 Prometheus가 이 주소를 스크레이프한다. 지표 이름은 Micrometer 표준
  `http_server_requests_seconds_bucket/_count/_sum` (uri·status 태그 포함)

- [ ] **Step 1: 실패하는 테스트 작성**

`was/src/test/java/com/celfit/was/ActuatorPrometheusTest.java` 생성:

```java
package com.celfit.was;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 액추에이터 프로메테우스 노출 검증 — ① 인증 없이 200(permitAll 체인),
 * ② http.server.requests 히스토그램 버킷 노출(p95/p99 산출의 전제).
 *
 * <p>테스트 프로파일은 관리 포트를 분리하지 않으므로(관리 포트 분리는 application-prod.yml만)
 * 메인 포트의 /actuator/prometheus를 MockMvc로 직접 친다 — 시큐리티 체인은 포트와 무관하게
 * 같은 FilterChainProxy가 적용되므로 permitAll 검증으로 충분하다.
 */
@AutoConfigureMockMvc
class ActuatorPrometheusTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void 프로메테우스_엔드포인트가_인증_없이_히스토그램_버킷을_노출한다() throws Exception {
		// http.server.requests 지표를 최소 1건 적재 — 버킷 라인은 첫 요청 관측 후에만 나타난다
		mockMvc.perform(get("/health")).andExpect(status().isOk());

		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("http_server_requests_seconds_bucket")));
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.ActuatorPrometheusTest"`
Expected: FAIL — `/actuator/prometheus`가 404 (액추에이터 미도입 상태)

- [ ] **Step 3: 의존성 추가**

`was/build.gradle`의 `dependencies` 블록, `spring-boot-starter-webmvc` 줄 아래에 추가:

```groovy
	// API 성능 측정(스펙 2026-08-10) — /actuator/prometheus로 uri별 레이턴시 히스토그램 노출
	implementation 'org.springframework.boot:spring-boot-starter-actuator'
	runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```

주의: Boot 4에서 아티팩트명이 다르면(모듈 분리) 컴파일 단계에서 즉시 드러난다 —
그 경우 `./gradlew :was:dependencies --configuration runtimeClasspath | grep -i actuator`로
실제 모듈명을 확인해 교체한다.

- [ ] **Step 4: application.yml에 노출·히스토그램 설정**

`was/src/main/resources/application.yml` 맨 아래(`was:` 블록 위 아무 최상위 자리)에 추가:

```yaml
# API 성능 측정(스펙 2026-08-10) — 노출은 health·prometheus만. 운영은 application-prod.yml이
# 관리 포트를 9081로 분리해 내부망 전용(Caddy 라우팅 없음), 로컬·테스트는 메인 포트에 그대로 노출.
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true   # uri별 p95/p99를 Prometheus 쪽에서 histogram_quantile로 산출
```

- [ ] **Step 5: application-prod.yml에 관리 포트 분리**

`was/src/main/resources/application-prod.yml`의 `server:` 블록과 나란히(최상위) 추가:

```yaml
# 액추에이터는 관리 포트로 분리 — prod 도커 네트워크 내부(prometheus 스크레이프) 전용.
# Caddy가 8081만 프록시하므로 9081은 외부에서 도달 불가.
management:
  server:
    port: 9081
```

- [ ] **Step 6: SecurityConfig에 permitAll 체인 추가**

`was/src/main/java/com/celfit/was/config/SecurityConfig.java`에 필터 체인 빈 추가 —
기존 `@Order(0)` `signupCodeIngestFilterChain` 메서드 **위**에 배치:

```java
	/**
	 * 액추에이터 체인(성능 측정 스펙 2026-08-10) — /actuator/**는 인증 없이 연다.
	 * 운영은 관리 포트(9081)가 도커 내부망 전용이라 외부 노출이 없고(Caddy는 8081만 프록시),
	 * 메인 포트(8081)의 /actuator/*는 관리 포트 분리 시 매핑 자체가 없어 404 — permitAll이어도
	 * 내용이 새지 않는다. 세션·CSRF는 지표 스크레이프에 불필요해 전부 끈다.
	 */
	@Bean
	@Order(-1)
	public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
		http
				.securityMatcher("/actuator/**")
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}
```

(필요 임포트 `AbstractHttpConfigurer`·`SessionCreationPolicy`·`Order`는 이 파일에 이미 있다.)

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.ActuatorPrometheusTest"`
Expected: PASS

- [ ] **Step 8: was 전체 테스트로 회귀 확인**

Run: `./gradlew :was:test`
Expected: 전체 PASS — 기존 시큐리티 테스트가 새 체인에 영향받지 않는지 확인.
실패 시 실패 테스트가 `/actuator` 경로를 전제하는지 먼저 확인할 것.

- [ ] **Step 9: 커밋**

```bash
git add was/build.gradle was/src/main/resources/application.yml \
  was/src/main/resources/application-prod.yml \
  was/src/main/java/com/celfit/was/config/SecurityConfig.java \
  was/src/test/java/com/celfit/was/ActuatorPrometheusTest.java
git commit -m "feat(was): Actuator/Micrometer 계측 — 관리 포트 분리·prometheus 노출"
```

---

### Task 2: postgres에 pg_stat_statements + 슬로우 쿼리 로그

**Files:**
- Modify: `deploy/compose.yaml` (postgres 서비스)

**Interfaces:**
- Consumes: 없음
- Produces: analysis DB의 `pg_stat_statements` 뷰(확장 생성은 Task 6 런북에서 수동),
  500ms 초과 쿼리의 stderr 로그(→ Task 4 Alloy가 Loki로 적재)

- [ ] **Step 1: postgres 서비스에 command 플래그 추가**

`deploy/compose.yaml`의 `postgres:` 서비스(analysis 쪽 — `postgres-raw` 아님)에서
`environment:` 위에 추가:

```yaml
    # 쿼리 성능 측정(스펙 2026-08-10) — pg_stat_statements는 preload 필수라 command로 주입.
    # 확장 생성(CREATE EXTENSION)은 README §15 런북(수동 1회). 이 항목을 바꾸면 postgres가
    # 재생성돼 짧은 순단이 발생한다 — was/analytics는 HikariCP가 자동 재접속.
    command:
      - postgres
      - "-c"
      - "shared_preload_libraries=pg_stat_statements"
      - "-c"
      - "pg_stat_statements.track=all"
      - "-c"
      - "log_min_duration_statement=500"
```

- [ ] **Step 2: compose 문법 검증**

Run: `docker compose -f deploy/compose.yaml config -q`
Expected: 종료코드 0 (환경변수 미설정 WARN은 무시 — 서버 `.env` 전제 파일이라 정상)

- [ ] **Step 3: 로컬에서 플래그 동작 확인**

Run:
```bash
docker run --rm -d --name pgss-smoke postgres:17-alpine \
  -c shared_preload_libraries=pg_stat_statements -c log_min_duration_statement=500 \
  2>/dev/null || echo "이미 실행 중이면 무시"
sleep 3
docker exec pgss-smoke psql -U postgres -c "SHOW shared_preload_libraries" 2>/dev/null \
  || docker exec -e POSTGRES_PASSWORD=x pgss-smoke psql -U postgres -c "SHOW shared_preload_libraries"
docker rm -f pgss-smoke
```
Expected: `pg_stat_statements` 출력. (POSTGRES_PASSWORD 미지정으로 기동 실패하면
`-e POSTGRES_PASSWORD=x`를 docker run에 넣고 재시도)

- [ ] **Step 4: 커밋**

```bash
git add deploy/compose.yaml
git commit -m "feat(deploy): postgres에 pg_stat_statements preload·슬로우 쿼리 로그(500ms) 활성화"
```

---

### Task 3: Prometheus 컨테이너

**Files:**
- Create: `deploy/prometheus/prometheus.yml`
- Modify: `deploy/compose.yaml` (서비스 1개, 볼륨 1개 추가)

**Interfaces:**
- Consumes: Task 1의 `was:9081/actuator/prometheus`
- Produces: `http://prometheus:9090` (prod 네트워크 내부) — Task 5의 Grafana 데이터소스가 조회

- [ ] **Step 1: 스크레이프 설정 파일 생성**

`deploy/prometheus/prometheus.yml`:

```yaml
# was 지표 수집(스펙 2026-08-10) — 30초 간격. 대상 추가(crawler/analytics)는 job을 늘리면 된다.
global:
  scrape_interval: 30s
  scrape_timeout: 10s

scrape_configs:
  - job_name: was
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["was:9081"]
```

- [ ] **Step 2: compose에 서비스·볼륨 추가**

`deploy/compose.yaml`의 `grafana:` 서비스 정의 **앞**에 추가:

```yaml
  # API 지표 저장(스펙 2026-08-10) — was:9081 스크레이프, 보관 30일 + 디스크 1GB 상한(이중 봉인).
  # 호스트 포트 미노출 — 조회는 Grafana 데이터소스로만. 관측 도구는 oom_score_adj +500(먼저 희생).
  prometheus:
    image: prom/prometheus:v3.4.0
    restart: unless-stopped
    logging: *logging
    networks: [prod]
    mem_limit: 256m
    oom_score_adj: 500
    command:
      - "--config.file=/etc/prometheus/prometheus.yml"
      - "--storage.tsdb.path=/prometheus"
      - "--storage.tsdb.retention.time=30d"
      - "--storage.tsdb.retention.size=1GB"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
```

`volumes:` 최상위 블록(`grafana-data:` 아래)에 추가:

```yaml
  prometheus-data:
```

- [ ] **Step 3: compose 문법 검증 + 로컬 스모크**

Run:
```bash
docker compose -f deploy/compose.yaml config -q && echo OK
docker run --rm -v "$PWD/deploy/prometheus/prometheus.yml:/p.yml:ro" \
  --entrypoint promtool prom/prometheus:v3.4.0 check config /p.yml
```
Expected: `OK` + `SUCCESS: /p.yml is valid prometheus config`

- [ ] **Step 4: 커밋**

```bash
git add deploy/prometheus/prometheus.yml deploy/compose.yaml
git commit -m "feat(deploy): Prometheus 컨테이너 — was 지표 30초 스크레이프, 30일/1GB 상한"
```

---

### Task 4: Loki + Alloy 컨테이너 (로그 저장)

**Files:**
- Create: `deploy/loki/loki-config.yaml`
- Create: `deploy/alloy/config.alloy`
- Modify: `deploy/compose.yaml` (서비스 2개, 볼륨 1개 추가)

**Interfaces:**
- Consumes: 전 컨테이너의 도커 로그(호스트 `/var/run/docker.sock` 읽기 전용)
- Produces: `http://loki:3100` (prod 네트워크 내부, `container` 라벨로 조회) — Task 5 데이터소스가 사용

- [ ] **Step 1: Loki 설정 파일 생성**

`deploy/loki/loki-config.yaml`:

```yaml
# 로그 저장(스펙 2026-08-10) — 단일 바이너리·파일시스템 모드. 보관 30일(compactor가 만료 삭제).
# 배포로 컨테이너가 재생성돼도 로그가 loki-data 볼륨에 남는다(기존 json-file 유실 문제 해결).
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  instance_addr: 127.0.0.1
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

compactor:
  working_directory: /loki/compactor
  retention_enabled: true
  delete_request_store: filesystem

limits_config:
  retention_period: 720h   # 30일
```

- [ ] **Step 2: Alloy 설정 파일 생성**

`deploy/alloy/config.alloy`:

```alloy
// 도커 로그 수집(스펙 2026-08-10) — 호스트의 전 컨테이너 stdout/stderr를 Loki로 push.
// 컨테이너 이름(선행 "/" 제거)을 container 라벨로 부여 — Grafana에서 {container="deploy-was-1"}식 조회.

discovery.docker "containers" {
  host             = "unix:///var/run/docker.sock"
  refresh_interval = "30s"
}

discovery.relabel "containers" {
  targets = discovery.docker.containers.targets

  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container"
  }
}

loki.source.docker "containers" {
  host             = "unix:///var/run/docker.sock"
  targets          = discovery.relabel.containers.output
  forward_to       = [loki.write.local.receiver]
  refresh_interval = "30s"
}

loki.write "local" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"
  }
}
```

- [ ] **Step 3: compose에 서비스 2개·볼륨 추가**

`deploy/compose.yaml`의 `prometheus:` 서비스 아래에 추가:

```yaml
  # 로그 저장(스펙 2026-08-10) — 파일시스템 모드·보관 30일. 배포 시 컨테이너 로그 유실 문제의 해결책.
  loki:
    image: grafana/loki:3.5.0
    restart: unless-stopped
    logging: *logging
    networks: [prod]
    mem_limit: 384m
    oom_score_adj: 500
    command: ["-config.file=/etc/loki/loki-config.yaml"]
    volumes:
      - ./loki/loki-config.yaml:/etc/loki/loki-config.yaml:ro
      - loki-data:/loki

  # 로그 수집기 — 도커 소켓(ro)으로 전 컨테이너 로그를 긁어 Loki로 push. Loki 불능 시 재시도
  # 버퍼링만 하고 서비스 컨테이너의 stdout 로깅(json-file)에는 영향 없음.
  alloy:
    image: grafana/alloy:v1.8.2
    restart: unless-stopped
    logging: *logging
    networks: [prod]
    mem_limit: 192m
    oom_score_adj: 500
    command: ["run", "/etc/alloy/config.alloy", "--storage.path=/var/lib/alloy/data"]
    volumes:
      - ./alloy/config.alloy:/etc/alloy/config.alloy:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
```

`volumes:` 최상위 블록에 추가:

```yaml
  loki-data:
```

- [ ] **Step 4: 설정 파일 문법 검증**

Run:
```bash
docker compose -f deploy/compose.yaml config -q && echo COMPOSE-OK
docker run --rm -v "$PWD/deploy/loki/loki-config.yaml:/c.yaml:ro" \
  grafana/loki:3.5.0 -config.file=/c.yaml -verify-config
docker run --rm -v "$PWD/deploy/alloy/config.alloy:/c.alloy:ro" \
  grafana/alloy:v1.8.2 fmt /c.alloy
```
Expected: `COMPOSE-OK` + Loki `config is valid` + Alloy는 파싱 성공 시 정규화된 설정을 그대로 출력
(문법 오류면 비정상 종료)

- [ ] **Step 5: 커밋**

```bash
git add deploy/loki/loki-config.yaml deploy/alloy/config.alloy deploy/compose.yaml
git commit -m "feat(deploy): Loki+Alloy 로그 파이프라인 — 배포 시 로그 유실 해결, 보관 30일"
```

---

### Task 5: Grafana 데이터소스 + API 성능 대시보드

**Files:**
- Create: `deploy/grafana/provisioning/datasources/observability.yaml`
- Create: `deploy/grafana/provisioning/dashboards/json/hypenow-api-performance.json`

**Interfaces:**
- Consumes: Task 3 `http://prometheus:9090`, Task 4 `http://loki:3100`,
  기존 Postgres 데이터소스 uid `hypenow-analysis-pg`(pg_stat_statements 패널용 —
  `pg_monitor` 권한은 Task 6 런북에서 부여)
- Produces: Grafana 폴더 "HypeNow"에 대시보드 "HypeNow API 성능"(uid `hypenow-api-performance`)

- [ ] **Step 1: 데이터소스 프로비저닝 파일 생성**

`deploy/grafana/provisioning/datasources/observability.yaml`:

```yaml
# 성능 측정 데이터소스(스펙 2026-08-10) — 내부 도커 네트워크(prod)로만 통신, 기존
# postgres.yaml과 같은 파일 기반 프로비저닝(운영 UI 수정 금지 규율 동일).
apiVersion: 1

datasources:
  - name: Prometheus (was 지표)
    uid: hypenow-prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    editable: false
    jsonData:
      timeInterval: 30s   # 스크레이프 간격과 일치 — rate() 최소 창 계산 근거

  - name: Loki (컨테이너 로그)
    uid: hypenow-loki
    type: loki
    access: proxy
    url: http://loki:3100
    editable: false
```

- [ ] **Step 2: 대시보드 JSON 생성**

`deploy/grafana/provisioning/dashboards/json/hypenow-api-performance.json`
(자동 새로고침 5분 — 기존 규율 "1분 미만 금지" 준수):

```json
{
  "title": "HypeNow API 성능",
  "uid": "hypenow-api-performance",
  "schemaVersion": 39,
  "editable": false,
  "refresh": "5m",
  "time": { "from": "now-6h", "to": "now" },
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "엔드포인트별 p95 응답시간",
      "gridPos": { "h": 9, "w": 12, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "hypenow-prometheus" },
      "fieldConfig": { "defaults": { "unit": "s" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "expr": "histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri!~\"UNKNOWN|root|/actuator.*\"}[5m])))",
          "legendFormat": "{{uri}}"
        }
      ]
    },
    {
      "id": 2,
      "type": "timeseries",
      "title": "엔드포인트별 p99 응답시간",
      "gridPos": { "h": 9, "w": 12, "x": 12, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "hypenow-prometheus" },
      "fieldConfig": { "defaults": { "unit": "s" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "expr": "histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri!~\"UNKNOWN|root|/actuator.*\"}[5m])))",
          "legendFormat": "{{uri}}"
        }
      ]
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "처리량 (req/s, 엔드포인트별)",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 9 },
      "datasource": { "type": "prometheus", "uid": "hypenow-prometheus" },
      "fieldConfig": { "defaults": { "unit": "reqps" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "expr": "sum by (uri) (rate(http_server_requests_seconds_count{uri!~\"UNKNOWN|root|/actuator.*\"}[5m]))",
          "legendFormat": "{{uri}}"
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "5xx 비율",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 9 },
      "datasource": { "type": "prometheus", "uid": "hypenow-prometheus" },
      "fieldConfig": { "defaults": { "unit": "percentunit", "max": 1, "min": 0 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "expr": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))",
          "legendFormat": "5xx 비율"
        }
      ]
    },
    {
      "id": 5,
      "type": "table",
      "title": "누적 실행시간 상위 SQL (pg_stat_statements, 시간필터 없음 — 누적치)",
      "gridPos": { "h": 10, "w": 24, "x": 0, "y": 17 },
      "datasource": { "type": "postgres", "uid": "hypenow-analysis-pg" },
      "targets": [
        {
          "refId": "A",
          "format": "table",
          "rawQuery": true,
          "rawSql": "SELECT left(regexp_replace(query, '\\s+', ' ', 'g'), 160) AS query, calls, round(total_exec_time::numeric, 0) AS total_ms, round(mean_exec_time::numeric, 1) AS mean_ms, rows FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat_statements%' ORDER BY total_exec_time DESC LIMIT 20"
        }
      ]
    }
  ]
}
```

- [ ] **Step 3: JSON 문법 검증**

Run: `python3 -m json.tool deploy/grafana/provisioning/dashboards/json/hypenow-api-performance.json > /dev/null && echo JSON-OK`
Expected: `JSON-OK`

- [ ] **Step 4: 커밋**

```bash
git add deploy/grafana/provisioning/datasources/observability.yaml \
  deploy/grafana/provisioning/dashboards/json/hypenow-api-performance.json
git commit -m "feat(deploy): Grafana 성능 데이터소스·API 성능 대시보드 프로비저닝"
```

---

### Task 6: 개통 런북(README §15) + DECISIONS.md 기록

**Files:**
- Modify: `deploy/README.md` (§14 뒤에 §15 추가 — 기존 §15 이후 절이 있으면 번호를 §16으로 밀지 말고
  "§15-성능"처럼 새 절 이름으로 붙인다. 실제 다음 자유 번호를 파일에서 확인할 것)
- Modify: `DECISIONS.md` (맨 위에 항목 추가)

**Interfaces:**
- Consumes: Task 1~5의 전체 구성
- Produces: 서버 개통 절차 문서(수동 단계 포함) — 배포 후 사람이 따라 하는 정본

- [ ] **Step 1: README에 개통 런북 추가**

`deploy/README.md`의 §14 절 끝(§15 시작 전)에 추가. 기존 절 번호와 충돌하면 절 제목만 조정:

````markdown
## 15. 쿼리·API 성능 측정 스택 (08-10~)

Prometheus(지표)·Loki(로그)·기존 Grafana(시각화) + postgres `pg_stat_statements`(SQL 통계).
정의: `deploy/compose.yaml`(prometheus·loki·alloy 서비스) + `deploy/prometheus/`·`deploy/loki/`·
`deploy/alloy/` 설정 파일 + Grafana 프로비저닝(데이터소스 `observability.yaml`, 대시보드
"HypeNow API 성능"). 셋 다 호스트 포트 미노출 — 조회는 Grafana(§14 SSH 터널)로만.

### 15-1. 최초 개통 (배포 1회 + 수동 2단계)

compose 변경이 배포되면 postgres가 재생성된다(짧은 순단 — was/analytics는 HikariCP 자동 재접속,
저트래픽 시간대 권장). 이후 서버에서:

```bash
# ① pg_stat_statements 확장 생성 (analysis DB, 1회 — preload는 compose가 이미 함)
docker exec deploy-postgres-1 psql -U <DB_USER> -d analysis \
  -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"

# ② grafana_reader에 통계 조회 권한 (pg_stat_statements 뷰는 pg_monitor 필요 — §14-2 롤 전제)
docker exec deploy-postgres-1 psql -U <DB_USER> -d analysis \
  -c "GRANT pg_monitor TO grafana_reader"
```

### 15-2. 개통 확인

```bash
# 지표: prometheus가 was를 긁고 있는지 (up 1이면 정상)
docker exec deploy-prometheus-1 wget -qO- 'http://localhost:9090/api/v1/query?query=up{job="was"}'

# SQL 통계: 상위 느린 쿼리가 쌓이는지
docker exec deploy-postgres-1 psql -U <DB_USER> -d analysis \
  -c "SELECT calls, round(total_exec_time::numeric) AS total_ms, left(query,60) FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 5"

# 로그: loki에 컨테이너 라벨이 잡히는지
docker exec deploy-alloy-1 wget -qO- 'http://loki:3100/loki/api/v1/label/container/values'
```

Grafana(§14-1 터널) → 폴더 HypeNow → "HypeNow API 성능" 대시보드에서 p95·처리량·상위 SQL 확인.
로그는 Explore → Loki 데이터소스 → `{container="deploy-was-1"}`.

### 15-3. 운영 다이얼

- 부하가 예상(합산 RAM 330~400MB·CPU 1~2%)을 넘으면: `deploy/prometheus/prometheus.yml`의
  `scrape_interval` 60s 상향이 1차 다이얼.
- 통계 리셋(개선 전후 비교 시작점): `SELECT pg_stat_statements_reset()`.
- 디스크 상한: Prometheus 1GB(`--storage.tsdb.retention.size`)·Loki 보관 30일(compactor) —
  둘 다 자동 삭제라 수동 정리 불필요.
````

- [ ] **Step 2: DECISIONS.md 맨 위에 결정 기록 추가**

`DECISIONS.md` 맨 위(기존 첫 항목 앞)에, 파일의 기존 항목 형식을 그대로 따라 추가.
핵심 내용:

```markdown
## 2026-08-10 성능 측정은 self-hosted PLG + pg_stat_statements (외부 APM 배제)

API·쿼리 성능 개선에 앞서 측정 스택을 도입 — Prometheus(was 지표)·Loki+Alloy(로그)·기존
Grafana(시각화)·pg_stat_statements(SQL 누적 통계). New Relic류 APM은 에이전트가 이 서버(2코어)에서
가장 무거운 선택지(힙 +100~300MB·CPU 1~3%)라 배제. 관측 컨테이너는 전부 mem_limit +
oom_score_adj +500(메모리 압박 시 서비스보다 먼저 희생) + 저장소 상한(Prometheus 1GB·Loki 30일).
배포 시 컨테이너 로그 유실 문제도 Loki 도입으로 함께 해결.
스펙: docs/superpowers/specs/2026-08-10-query-performance-observability-design.md
```

(DECISIONS.md의 실제 헤더 형식이 다르면 — 예: 날짜 표기·불릿 스타일 — 기존 첫 항목의 형식을 복사해 맞춘다.)

- [ ] **Step 3: 커밋**

```bash
git add deploy/README.md DECISIONS.md
git commit -m "docs: 성능 측정 스택 개통 런북(§15)·결정 기록"
```

---

### Task 7: 최종 검증 + PR

**Files:** 없음 (검증·PR만)

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew test`
Expected: 전체 PASS (PR 직전 전체 테스트 — CLAUDE.md 규율)

- [ ] **Step 2: compose 최종 검증**

Run: `docker compose -f deploy/compose.yaml config -q && echo OK`
Expected: `OK`

- [ ] **Step 3: PR 생성 (develop 대상)**

```bash
git push -u origin feature/server-query-performance-tools-26b7e9
gh pr create --base develop \
  --title "feat: 쿼리·API 성능 측정 스택 (PLG + pg_stat_statements)" \
  --body "$(cat <<'EOF'
## 요약
- was에 Actuator/Micrometer 계측 — 관리 포트(9081, 내부망 전용)로 uri별 레이턴시 히스토그램 노출
- Prometheus(30일/1GB 상한)·Loki+Alloy(로그 30일 보관 — 배포 시 로그 유실 해결) 컨테이너 추가
- postgres에 pg_stat_statements preload + 슬로우 쿼리 로그(500ms)
- 기존 Grafana에 데이터소스 2개 + "HypeNow API 성능" 대시보드 프로비저닝
- 개통 런북 deploy/README §15 (배포 후 수동 2단계: CREATE EXTENSION + GRANT pg_monitor)

## 배포 주의
- compose의 postgres command 변경으로 **배포 시 postgres가 재생성**된다(짧은 순단) —
  저트래픽 시간대 머지 권장, 배포 후 README §15-1 수동 단계 필요

스펙: docs/superpowers/specs/2026-08-10-query-performance-observability-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL 출력

---

## Self-Review 체크 결과

- **스펙 커버리지**: DB층(Task 2·6), 앱층(Task 1), 수집·저장(Task 3·4), 시각화(Task 5),
  런북·기록(Task 6) — 스펙 전 절 커버. 알림 규칙·test 스택·crawler/analytics 계측은 스펙에서 범위 제외.
- **플레이스홀더**: 없음 — 모든 설정 파일·코드·검증 커맨드 실물 포함.
- **타입 일관성**: 데이터소스 uid(`hypenow-prometheus`·`hypenow-loki`·`hypenow-analysis-pg`),
  포트(9081·9090·3100), 볼륨명(`prometheus-data`·`loki-data`)이 Task 간 일치함을 확인.
