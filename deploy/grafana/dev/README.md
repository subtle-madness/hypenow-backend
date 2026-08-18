# 그라파나 대시보드 개발용 로컬 하니스

대시보드 JSON을 운영에 올리기 전에 **운영과 같은 그라파나 이미지·같은 데이터소스 uid**로 로컬에서
그려 보기 위한 스택이다. 다른 건 다 같고 **데이터만 목(mock)** 이다.

- 그라파나 `http://localhost:3300` (admin / admin)
- postgres 호스트 포트 `55432` (dev / dev, DB `analysis`·`monitoring`)
- 컨테이너명 prefix `grafana-dev` (compose `name:`)

대시보드 provider는 운영 파일(`../provisioning/dashboards`)을 그대로 읽기 전용 마운트한다 —
`../provisioning/dashboards/json/*.json`을 고치면 **60초 안에** 로컬 그라파나에 반영된다
(`updateIntervalSeconds: 60`). 즉 이 하니스에서 보는 대시보드는 레포에 커밋될 그 파일이다.

## 1. 기동

```bash
cd deploy/grafana/dev
docker compose -f compose.dev.yaml up -d
```

검증(그라파나가 뜨는 데 20초쯤 걸린다):

```bash
curl -s http://localhost:3300/api/health          # {"database": "ok", "version": "13.1.1", ...}
docker compose -f compose.dev.yaml ps             # 컨테이너 5개 Up
```

프로메테우스는 호스트 포트를 열지 않는다(그라파나가 도커 네트워크로만 본다) — 타깃 상태를 보려면
컨테이너 안에서 조회한다.

```bash
docker exec grafana-dev-prometheus-1 wget -qO- http://localhost:9090/api/v1/targets
```

## 2. 시드(목 데이터)

DB는 빈 상태로 뜬다. 패널을 그리려면 목 데이터를 넣어야 한다.

```bash
psql "postgresql://dev:dev@localhost:55432/analysis"    # 분석 뷰용
psql "postgresql://dev:dev@localhost:55432/monitoring"  # 모니터링 지표용
```

`dev`는 슈퍼유저이고 두 DB 모두 소유자라 스키마·테이블을 자유롭게 만들 수 있다.
(시드 스크립트 자체는 후속 태스크에서 이 디렉토리에 추가된다.)

## 3. 정리

```bash
docker compose -f compose.dev.yaml down -v   # -v 로 postgres 볼륨까지 삭제 → 다음 기동은 깨끗한 DB
```

## 운영과 다른 점

- **데이터가 전부 목이다.** 운영 DB·운영 프로메테우스를 절대 붙이지 않는다(계정도 dev/dev).
- **Loki는 스텁이다.** 로컬엔 로키를 띄우지 않아 `hypenow-loki` 데이터소스는 항상 연결 실패다 —
  로그 패널이 로컬에서 에러로 보이는 건 **정상**이고, 그 패널들의 검증은 운영에서 한다.
- **알림(alerting)은 마운트하지 않는다.** 운영 `provisioning/alerting`은 컨택트포인트가 실제
  디스코드라서 로컬에서 울리면 안 된다. 데이터소스도 운영 파일 대신 `./datasources/dev.yaml`로
  대체한다 — **uid는 운영과 동일**(`hypenow-analysis-pg`·`hypenow-monitoring-pg`·`hypenow-prometheus`
  ·`hypenow-loki`)해서 대시보드 JSON을 손대지 않고 그대로 쓴다.
- **postgres 초기화 스크립트가 다르다.** 운영 `db/init`은 `crawler`·`monitoring`·`was_reader` 롤을
  전제해서 하니스에선 초기화가 깨진다(`role "crawler" does not exist` → 컨테이너 exit 3).
  하니스는 `./initdb/01-create-monitoring-db.sql`로 `monitoring` DB만 만들고 `analysis`는
  `POSTGRES_DB`가 만든다. 둘 다 소유자는 `dev`.
- **`was` 프로메테우스 잡은 기본이 down이다.** JVM 패널을 만질 때만 로컬 was를 띄워 붙인다. 스크레이프
  대상이 운영과 같은 `9081`인데(운영은 `application-prod.yml`이 관리 포트를 분리) **로컬 기본 프로필은
  액추에이터가 메인 포트 8081에 붙으므로** 관리 포트를 맞춰 줘야 한다. 안 띄워도 무해하다(down 표시).

  ```bash
  ./gradlew :was:bootRun --args='--management.server.port=9081'
  ```
