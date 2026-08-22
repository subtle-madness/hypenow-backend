# monitoring 모듈 JVM 지표 수집 + 인프라 대시보드 확장 설계

> 상태: 🟢 활성 · 2026-08-22

## 배경·목적

브랜드·경쟁사 스윕을 실제로 돌리는 monitoring 모듈은 `mem_limit 768m`/힙 512m으로 돌고
08-12 OOM 전력(힙 상향 + `ExitOnOutOfMemoryError`)이 있는데, 프로메테우스가 was만 긁고
있어 JVM 지표가 전혀 안 모인다. OOM 선행 신호(old gen after-GC 우상향, GC pause 상승,
working set의 limit 근접)를 보려면 수집부터 뚫어야 한다.

**범위**: 계측(액추에이터) + 수집(프로메테우스 잡) + 화면(기존 인프라 대시보드 확장).
**범위 밖**(명시적 이연): 알람 룰(지표 며칠 실측 후 임계 결정 — 사용자 결정), crawler·analytics
계측(같은 패턴 반복이라 필요 시 확장), 브랜드 등록 소요·자산 규모 패널(별건).

## 결정

- **모듈별 대시보드 신설 대신 기존 인프라 대시보드 확장** (사용자 승인, 08-22). JVM 3~4개
  규모에선 job별 시리즈를 한 패널에 겹치는 쪽이 비교·유지비 모두 우위. 업무 화면은 이미
  브랜드/경쟁사 대시보드가 모듈별로 존재한다.
- **관리 포트는 prod에서 9083으로 분리하되 compose env로 주입** — monitoring은 was와 달리
  application-prod.yml 없이 env 주입이 프로필 관용구라서 `MANAGEMENT_SERVER_PORT: 9083`을
  compose environment에 둔다(was는 application-prod.yml에 9081). Caddy가 프록시하지 않으므로
  도커 네트워크 내부 전용. 로컬·테스트 기본은 메인 포트(8083)에 그대로 노출(was와 동일 의미).

## 구현

### 1. 계측 — monitoring 모듈

- `monitoring/build.gradle`: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
  (was build.gradle 25-27행 패턴).
- `monitoring/src/main/resources/application.yml`: `management.endpoints.web.exposure.include:
  health,prometheus` (was 73-79행 패턴 — http.server.requests 히스토그램은 API 성능 측정용이라
  monitoring엔 불요, 제외).
- `deploy/compose.yaml` monitoring environment: `MANAGEMENT_SERVER_PORT: "9083"`.

### 2. 수집 — 프로메테우스

- `deploy/prometheus/prometheus.yml`: `job_name: monitoring`, `metrics_path: /actuator/prometheus`,
  target `monitoring:9083`. prometheus·monitoring 둘 다 prod 네트워크라 도달 가능(확인됨).
- `deploy/grafana/dev/prometheus-dev.yml`: 같은 잡, target `host.docker.internal:9083` —
  was 잡과 동일하게 기본 down 무해, JVM 패널 검증 때만 로컬 bootRun을 붙인다.

### 3. 화면 — hypenow-infra.json "컨테이너·JVM" row 확장

신설 2:
- **JVM 힙 사용률 (모듈별)**: `100 * sum by(job)(jvm_memory_used_bytes{area="heap"}) /
  sum by(job)(jvm_memory_max_bytes{area="heap"} > 0)` — 모듈 간 비교의 기본 화면.
  (`> 0` 필터: G1의 Eden/Survivor max=-1 제외 — Old Gen max=Xmx만 남는다.)
- **old gen after-GC (모듈별)**: `jvm_gc_live_data_size_bytes`, legend `{{job}}` — 풀GC 후
  잔존 힙. 계단식 우상향 = 누수. 이번 작업의 핵심 신설 패널(현재 없는 유일한 OOM 선행 지표).

기존 확장 3 (job 필터가 없어 monitoring 수집 시작 즉시 시리즈가 유입되므로 legend에 job 구분 필수):
- `was 힙 (영역별)` → 제목 **힙 (모듈·영역별)**, legend `{{job}} {{id}}`.
- `GC pause`: legend `{{job}} {{action}} / {{cause}}`.
- `HikariCP 커넥션`: legend `{{job}} active/pending {{pool}}`.

오염 방지 1:
- 홈 `was JVM` 타일(hypenow-home.json): job 필터 없는 `sum()`이라 monitoring 유입 시 두 모듈
  old gen이 합산돼 타일이 오염된다 — 분자·분모에 `job="was"` 추가(타일 이름 그대로).

레이아웃: 컨테이너 메모리·CPU(y=18) 유지, y=26에 힙 사용률(모듈별)·old gen after-GC·GC pause
각 w8, y=34에 힙 (모듈·영역별) w12·HikariCP w12. 이하 로그 row는 y만 +8 이동.

## 검증

- `./gradlew :monitoring:test` (모듈 단위 — CLAUDE.md 규약).
- 로컬 하니스: grafana-dev 스택 기동 + `./gradlew :monitoring:bootRun --args='
  --management.server.port=9083 --spring.datasource.url=jdbc:postgresql://localhost:55432/monitoring
  --spring.datasource.username=dev --spring.datasource.password=dev --spring.flyway.enabled=false'`
  (하니스 DB엔 flyway_schema_history가 없으므로 Flyway를 끈다 — apply-migrations.sh 주석 참조).
  안전 확인됨: 스윕·알람 크론 기본 전부 `"-"` 비활성 + API 키 빈값이라 외부 호출 없음.
- 프로메테우스 타깃 up 확인 → 인프라 대시보드 패널 5종 + 홈 타일이 실데이터로 렌더되는지 육안.

## 운영 반영

CD가 매 배포 prometheus.yml을 scp하고 prometheus·grafana를 restart하므로 별도 수동 절차 없음.
GRANT 무관(프로메테우스 데이터소스 — postgres 아님). 롤링 중 신구 이미지 공존 시 구 이미지는
9083 미리슨 → 스크레이프 down일 뿐 무해.
