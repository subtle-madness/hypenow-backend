# 그라파나 대시보드 개발용 로컬 하니스

대시보드 JSON을 운영에 올리기 전에 **운영과 같은 그라파나 이미지·같은 데이터소스 uid**로 로컬에서
그려 보기 위한 스택이다. 다른 건 다 같고 **데이터만 목(mock)** 이다.

- 그라파나 `http://localhost:3300` (admin / admin)
- postgres 호스트 포트 `55432` (dev / dev, DB `analysis`·`monitoring`)
- 컨테이너명 prefix `grafana-dev` (compose `name:`)

대시보드 provider는 운영 파일(`../provisioning/dashboards`)을 그대로 읽기 전용 마운트한다 —
`../provisioning/dashboards/json/*.json`(HypeNow 폴더)·`../provisioning/dashboards/json-brand/*.json`
(브랜드 모니터링 폴더) 어느 쪽을 고쳐도 **60초 안에** 로컬 그라파나에 반영된다
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

## 2. 스키마 + 시드(목 데이터)

DB는 **빈 상태**로 뜬다(테이블조차 없다). 스키마 적용 → 시드 두 단계를 거쳐야 패널이 그려진다.
아래는 전부 **레포 루트에서** 실행한다.

### 2-1. 스키마 적용

```bash
bash deploy/grafana/dev/apply-migrations.sh
```

**기존 볼륨을 재사용하는 하니스는 08-17 이후 마이그레이션이 빠져 있으면 시드가
`column "ad_verdict" does not exist`로 깨진다**(V20260817160000 광고 판정 · V20260818024048 백필
인덱스) — §3의 `down -v` 리셋 후 이 스크립트를 다시 돌리거나, 두 파일만 수동 적용할 것.

레포 Flyway SQL(analytics `analysis` · was `app` · monitoring)을 버전 숫자순으로 적용한다.
운영에서 Flyway 밖에 있는 전제(롤 `was_reader`·`alarm_reader`, `app` 스키마, `search_path=app`)도
스크립트가 대신 만든다. **멱등이 아니다** — 이미 적용된 DB에 다시 돌리면 `already exists`로
깨진다. 다시 적용하려면 §3의 `down -v` 리셋부터 한다.

### 2-2. 시드 적용

`seed.sql`은 2026-08-18 운영 실측 밀도를 복제한 **정상(초록) 시드**다. analysis·monitoring 두 DB를
한 파일에 담고 `-- BEGIN <db>` / `-- END <db>` 주석으로 구간을 나눠 뒀다(psql 세션은 DB 하나만
보므로 구간을 잘라 두 번 실행한다).

```bash
C="docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -q -U dev"
sed -n '/^-- BEGIN analysis/,/^-- END analysis/p'     deploy/grafana/dev/seed.sql | $C -d analysis
sed -n '/^-- BEGIN monitoring/,/^-- END monitoring/p' deploy/grafana/dev/seed.sql | $C -d monitoring
```

`^-- ` 앵커를 빼면 안 된다 — 앵커 없는 패턴은 파일 상단 안내 주석 줄(그 안에 두 마커 문자열이
다 들어 있다)을 구간 시작으로 잡아 analysis 구간까지 monitoring DB로 흘려보낸다.

**`seed.sql`은 재적용 가능하다**(각 구간 첫머리에서 시드 대상 테이블을 `TRUNCATE ... RESTART
IDENTITY CASCADE` 한다). 난수도 `setseed`로 고정이라 재적용 때 같은 분포가 나온다.

밀도(실측 대비):

| | 값 |
|---|---|
| analysis | users 53 · brand_monitorings 130 · campaigns 12 · monitoring_items 76 · registrations 20 · digests 20 · accounts 200 · contents 2,400 |
| monitoring | brand_account 130 · target 73 · sweep_run 30 · alarm_event 97 · brand_hashtag_post 2,543 · brand_tagged_post 28,255 · brand_post_meta 8,000(판정 60%) · Hiker 콜 30일 ≈48,700(브랜드)+3,600(타깃) |

### 2-3. 빨간불 상태 확인

`seed-red.sql`은 초록 시드 위에 덧입혀 홈 신호등의 **DB 판정 타일 5개**(스윕 신선도·오늘 Hiker
콜·미러 신선도·멈춘 등록·알림 발송 실패)를 전부 빨강으로 뒤집는다. 나머지 8개 타일은
Prometheus/Loki/node-exporter 소관이라 여기서 못 만든다.

```bash
sed -n '/^-- BEGIN analysis/,/^-- END analysis/p'     deploy/grafana/dev/seed-red.sql | $C -d analysis
sed -n '/^-- BEGIN monitoring/,/^-- END monitoring/p' deploy/grafana/dev/seed-red.sql | $C -d monitoring
```

브랜드 폴더 2장의 빨간불도 같이 뒤집는다 — **[브랜드] 수집 현황**: 오늘 신규 태그 게시물 0 ·
백필 미완 브랜드 4 · enrich 잔여 600(빨강 임계 500 초과), **[브랜드] 광고 표기**: 오늘 판정 0건
(판정 잡 정지 양상).

복원은 **`seed.sql` 재적용**이면 된다(`down -v`까지 갈 필요 없다).

### 2-4. 직접 조회

```bash
psql "postgresql://dev:dev@localhost:55432/analysis"    # 분석·app 스키마
psql "postgresql://dev:dev@localhost:55432/monitoring"  # 모니터링 지표
```

`dev`는 슈퍼유저이고 두 DB 모두 소유자다. **그래서 운영의 `grafana_reader` 컬럼 화이트리스트
GRANT 누락은 이 하니스로 못 잡는다** — 패널을 추가하면 GRANT 런북(설계 §5)도 따로 챙겨야 한다.

`called_on` 같은 date 컬럼은 **KST 달력일이 정본**이다(운영 규약). 컨테이너 postgres는 UTC로
도니 패널 쿼리도 `current_date`가 아니라 `(now() AT TIME ZONE 'Asia/Seoul')::date`를 써야
UTC 15~24시(=KST 00~09시)에 오진하지 않는다.

## 3. 리셋 / 정리

레포 루트에서(§2와 같은 기준):

```bash
docker compose -f deploy/grafana/dev/compose.dev.yaml down -v   # -v 로 postgres 볼륨까지 삭제
docker compose -f deploy/grafana/dev/compose.dev.yaml up -d && sleep 20
bash deploy/grafana/dev/apply-migrations.sh                     # §2-1
# 이어서 §2-2 시드 재적용
```

데이터만 되돌리면 되는 경우(빨간불 시드 실험 후 등)는 리셋 없이 §2-2 재적용으로 충분하다.

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
- **호스트 디스크·컨테이너별 지표는 로컬에서 무데이터다 — Loki 스텁과 동급으로 취급한다.**
  macOS Docker Desktop은 컨테이너가 리눅스 VM 안에서 도는 구조라, 마운트를 붙여도 호스트(macOS)
  파일시스템과 도커 이미지 레이어 메타데이터에 닿지 못한다. **해당 패널들의 검증은 운영에서 한다.**
  compose는 운영(Task 9)과 같은 마운트를 그대로 유지한다 — 로컬에서 안 나온다고 빼면 운영과 어긋난다.

  | 지표 | 로컬 실측(2026-08-18) |
  |---|---|
  | `container_cpu_usage_seconds_total{name=~".+"}` | **빈 결과.** cAdvisor에 `/var/lib/docker:ro`를 붙여도 동일 — Docker Desktop의 이미지 저장소 레이아웃엔 `image/overlayfs/layerdb/mounts/<id>/mount-id`가 없어 로그가 `failed to identify the read-write layer ID`로 도배된다. 노출되는 건 cgroup 루트(`id="/"`, `/docker`)뿐이라 `name` 라벨이 붙은 시계열 자체가 없다 |
  | `container_memory_working_set_bytes{name=~".+"}` | 빈 결과(같은 원인) |
  | `node_filesystem_avail_bytes{mountpoint="/"}` | **빈 결과.** node-exporter가 보는 마운트는 컨테이너 자기 것(`/etc/hostname`·`/etc/hosts`·`/etc/resolv.conf`)뿐 — 마운트를 추가해도 호스트 디스크는 안 보인다 |
  | `node_cpu_seconds_total` | 나온다(VM 기준 값). CPU·메모리 계열 패널은 로컬에서 형태 확인 가능 |

- **`was` 프로메테우스 잡은 기본이 down이다.** JVM 패널을 만질 때만 로컬 was를 띄워 붙인다. 스크레이프
  대상이 운영과 같은 `9081`인데(운영은 `application-prod.yml`이 관리 포트를 분리) **로컬 기본 프로필은
  액추에이터가 메인 포트 8081에 붙으므로** 관리 포트를 맞춰 줘야 한다. 안 띄워도 무해하다(down 표시).

  ```bash
  ./gradlew :was:bootRun --args='--management.server.port=9081'
  ```
