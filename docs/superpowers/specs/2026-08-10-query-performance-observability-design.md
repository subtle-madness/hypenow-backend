# 쿼리·API 성능 측정 스택 설계 (PLG + pg_stat_statements)

> 상태: 🟢 활성 · 2026-08-10 작성 · 승인됨

## 배경·목적

운영 서버의 API가 체감상 느린데, 현재는 **어떤 API가, 어떤 SQL 때문에** 느린지 볼 수단이 전혀 없다
(Actuator/Micrometer 미도입, pg_stat_statements 미설정, 슬로우 쿼리 로그 미설정).
성능 개선 작업에 앞서 측정 도구를 먼저 갖춘다. 아울러 "배포 시 컨테이너 로그 유실" 문제
(컨테이너 재생성 시 json-file 로그 소멸)를 로그 저장소 도입으로 함께 해결한다.

**목표**: ① 엔드포인트별 레이턴시(p95/p99) 시계열 → 개선 전후 비교 가능,
② 누적 기준 느린 SQL 식별, ③ 배포와 무관하게 보존되는 로그.

**제약**: 서버는 2코어 / 물리 12GB — 부하 최소가 최우선 기준.
디스크 알람(85%)이 예민한 상태라 모든 신규 저장소에 크기 상한 필수.
이 기준으로 New Relic류 APM 에이전트(힙 +100~300MB, CPU 1~3%)는 배제했다.

## 아키텍처 개요

```
was (Actuator/Micrometer) ──/actuator/prometheus(관리 포트, 내부망)──▶ Prometheus (TSDB, 30일/1GB 상한)
전 컨테이너 stdout ──Alloy(도커 소켓 ro)──▶ Loki (파일시스템, 30일 보관)
postgres (pg_stat_statements) ◀──기존 Postgres 데이터소스로 직접 조회──┐
Prometheus·Loki ◀──신규 데이터소스──── 기존 Grafana ──────────────────┘
```

## 구성 요소

### 1. DB층 — pg_stat_statements + 슬로우 쿼리 로그

- `postgres`(analysis·app) 컨테이너의 compose `command:`에
  `-c shared_preload_libraries=pg_stat_statements` + `-c log_min_duration_statement=500ms`급 설정 추가.
  **postgres 재시작 1회 필요** — 짧은 순단, was/analytics는 HikariCP 자동 재접속. 저트래픽 시간대에 적용.
- `CREATE EXTENSION pg_stat_statements`(analysis DB)와 `grafana_reader`에 `pg_monitor` 롤 부여는
  기존 관례대로 **수동 런북**(deploy/README §14에 절차 추가) — 확장 생성은 인스턴스 설정 성격이라 앱 Flyway 밖.
- `postgres-raw`(crawler)는 범위 제외 — 느린 것은 was API 경로. 필요 시 같은 방법 복제.

### 2. 앱층 — was에 Actuator + Micrometer

- 의존성: `spring-boot-starter-actuator` + `micrometer-registry-prometheus` (Spring Boot 4.1 기준).
- `http.server.requests`에 히스토그램 버킷을 켜 uri 태그별 p95/p99 산출.
- `/actuator/prometheus`는 **별도 관리 포트**(`management.server.port`)로 분리 —
  도커 내부망(prod)에서만 접근, Caddy 라우팅 없음(외부 노출 0), Spring Security 체인 밖.
- crawler/analytics 계측은 범위 제외 (Prometheus 스크레이프 대상 추가만으로 확장 가능).

### 3. 수집·저장 — 신규 컨테이너 3개 (deploy/compose.yaml)

| 컨테이너 | 역할 | 상한 |
|---|---|---|
| prometheus | was 지표 30초 간격 스크레이프, TSDB | mem 256m · 보관 30일 · **디스크 1GB 상한**(`--storage.tsdb.retention.size`) |
| loki | 로그 저장(파일시스템 모드), 컴팩터로 만료 삭제 | mem 384m · 보관 30일 |
| alloy | 도커 소켓(ro)으로 전 컨테이너 로그 수집 → Loki push | mem 192m |

- 셋 다 호스트 포트 미노출, `prod` 네트워크 내부 통신만.
- 기존 관례 준수: `logging: *logging` 앵커, `mem_limit`, `oom_score_adj`는 **높은 값(먼저 희생)** —
  메모리 압박 시 관측 도구가 서비스보다 먼저 죽는다. 전용 볼륨(`prometheus-data`, `loki-data`).
- 예상 실사용 부하: 합산 RAM 330~400MB, CPU 평시 1~2%.

### 4. 시각화 — 기존 Grafana 프로비저닝 확장

- 데이터소스 2개 추가: prometheus, loki (`provisioning/datasources/` 기존 패턴).
- 대시보드 1개 추가: **API 성능** — 엔드포인트별 p95/p99·처리량·에러율(Prometheus) +
  pg_stat_statements 상위 느린 쿼리 패널(기존 Postgres 데이터소스, `pg_monitor` 권한 필요).
- 알림 규칙은 만들지 않는다 — 측정이 목적. 기준선 확보 후 별도 작업.

## 범위 제외 (YAGNI)

- test 스택(compose.test.yaml) 계측 — 같은 서버 자원 소모라 운영만.
- New Relic 등 외부 APM, 분산 트레이싱(Tempo), 알림 규칙, crawler/analytics 계측 — 전부 다음 단계.
- journald 전환안은 기각 — Grafana 연동·지표와의 시간축 대조 불가, 추후 Loki 중복 도입 비용.

## 에러 처리·안전장치

- 관측 컨테이너 전부 `mem_limit`로 봉인 — 폭주해도 자기 상한에서 죽고 서비스에 전파 안 됨.
- Loki 다운 시 Alloy는 재시도 버퍼링, 서비스 컨테이너의 stdout 로깅은 무영향(json-file 경로 유지).
- Prometheus 다운 시 지표 수집만 공백 — was 동작 무영향(pull 모델).
- 부하가 예상을 넘으면 스크레이프 간격 60초 상향이 1차 다이얼.

## 검증

- 로컬: compose로 스택 기동 → Grafana에서 지표·로그 조회 확인.
- 운영 배포 후: ① 대시보드에 실트래픽 p95 표시, ② pg_stat_statements 상위 쿼리 조회,
  ③ 배포 1회 거친 뒤 이전 로그가 Loki에 남아있는지(유실 해결) 확인.
