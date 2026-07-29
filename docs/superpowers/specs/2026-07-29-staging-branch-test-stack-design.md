# staging 승격 브랜치 + test 스택 리네임 + 도커 네트워크 분리 설계

> 상태: 🟢 활성 · ✅ 구현·전환 완료 (07-29 운영 CD·staging 개통·격리 검증까지 실행됨)

## 배경

태스크 K(07-28 개통)로 dev 스테이징 스택이 생겼지만, 트리거가 **develop CI 성공마다**라서
develop 머지 = dev 서버 배포가 자동 결합돼 있었다. 문제 셋:

1. **develop이 통합과 검증 배포를 겸함** — 아직 dev 서버로 내보내고 싶지 않은 머지도 무조건 배포되고,
   실행 중인 dev 잡을 끊는다(07-28 첫 미러 유실).
2. **명칭 혼선** — "dev 스테이징"의 dev가 브랜치(develop)·스택(dev-*)·도메인(dev-api) 세 가지 의미로 겹침.
3. **네트워크 평면** — 전 컨테이너가 compose 기본 브리지 하나에 동거, dev→운영 접근을 막는 것이
   자격증명뿐(네트워크 위상으로는 도달 가능).

## 결정

### 1. 브랜치 흐름 — develop → staging → main (A안)

```
feat/* ──PR──▶ develop ──머지──▶ staging ──머지──▶ main
                (CI만)     (CI + test 서버 배포)   (운영 배포)
```

- develop 머지는 CI만 돈다(배포 없음). **test 서버 배포는 staging 머지가 트리거**
  (CI@staging 성공 후 `workflow_run` — 기존 게이트 방식 유지).
- **운영 승격은 staging→main 머지** — dev 서버에서 검증된 커밋만 운영에 나간다.
- 기각: B안(develop→main 유지 + staging 병렬 검증 브랜치) — 검증한 커밋과 운영에 나가는
  커밋이 어긋날 수 있어 승격 보증이 없다.

### 2. 명칭 — dev 계열 전부 test로 통일 (B안)

| 항목 | 구 | 신 |
|---|---|---|
| 서비스/컨테이너 | dev-postgres·dev-analytics·dev-was(·dev-redis — 07-29 develop 유입) | **test-postgres·test-analytics·test-was·test-redis** (`deploy-test-*-1`) |
| compose 파일 | compose.dev.yaml | **compose.test.yaml** |
| 프로파일 | `--profile dev` | **`--profile test`** |
| 워크플로 | cd-dev.yml (CD dev) | **cd-test.yml (CD test)** |
| caddy 파일 | caddy.d/dev-api.caddy | **caddy.d/test-api.caddy** |
| 이미지 태그 | `:develop` + `develop-sha-*` | **`:staging` + `staging-sha-*`** |

**유지(변경 비용 대비 이득 없음 — 의도적 예외)**: 도메인 `dev-api.hypenow.io`(DNS 무변경),
서버 `.env`의 `DEV_*` 변수, raw DB 계정·스키마 `analytics_dev`(및 `prepare-dev-raw-role.sh`·
`rewrite-views-dev-schema.sh` 스크립트명), 데이터 볼륨 `dev-pg-data`(리네임 시 dev DB 데이터
유실 — 이관 없이 이름만 유지).

- 기각: 서비스명만 변경(A안) — dev/test 혼재가 남아 이후 monitoring 배선·문서에서 혼동.
- 기각: 도메인 `test-api.hypenow.io` 리네임 — DNS 추가 필요, 사용자가 유지 결정.

### 3. 도커 네트워크 분리 — prod / test

compose.yaml(운영 정의 — main CD 공급)에 브리지 네트워크 2개를 선언, 전 서비스 소속 명시:

- **prod**: postgres, analytics, crawler, was, ons-relay
- **test**: test-postgres, test-redis, test-analytics, test-was
- **양쪽(dual-homed)**: caddy(운영·test 도메인 라우팅), postgres-raw(test-analytics의 raw 읽기 —
  기존 `analytics_dev` 읽기 전용 계정 경로 유지)

효과: test 컨테이너에서 운영 postgres·analytics·crawler로 가는 경로가 **커널(iptables
DOCKER-ISOLATION) 수준에서 차단**된다. 남는 공유 접점은 의도된 둘뿐 — caddy 도메인 분기,
raw 읽기(권한 fail-closed). 커스텀 네트워크 선언 시 기본 네트워크가 사라지므로 **모든 서비스에
`networks:` 명시 필수**(누락 = 통신 고립).

### 4. monitoring 모듈과의 정합 (07-28 스펙 결정 11·12)

monitoring 스펙의 `monitoring-net`(was↔monitoring 전용)·`dev-monitoring`은 이 설계의 prod/test
대분리 **위에 얹히는 소네트워크**라 충돌 없음. 이 설계가 먼저 develop에 들어가므로 monitoring
배선(deploy 태스크 미착수 상태) 시 다음 매핑을 따른다:

| monitoring 스펙 표기 | 이 설계 이후 |
|---|---|
| `dev-monitoring` 컨테이너 | `test-monitoring` |
| `dev-monitoring-net` | `test-monitoring-net` (test-was와 둘만) |
| `:develop` 태그 | `:staging` |
| dev-postgres의 monitoring DB | test-postgres의 monitoring DB |

## 구현 세부

### 워크플로

- **ci.yml**: push `[develop, staging]`, PR `[develop, staging, main]` — staging push CI가
  CD test의 게이트. Gradle 캐시 쓰기는 기본 브랜치(develop)만이므로 staging CI는 읽기 재사용.
- **cd-test.yml**(구 cd-dev.yml): `workflow_run` CI@`staging` → 이미지 `:staging`·`staging-sha-*`
  빌드 → 서버 동기화 시 **구 명칭 파일 삭제**(`compose.dev.yaml`, `caddy.d/dev-api.caddy` —
  구 caddy 파일이 남으면 같은 도메인 이중 선언으로 reload 실패) → config 검증 → 계정 준비·뷰
  치환·잔존 검사(기존 그대로) → `up -d ... --remove-orphans` → reload → 헬스체크.
- **`--remove-orphans` 사용 기준**: README §12의 금지는 **운영 파일 단독 실행 경로**
  (compose.yaml만 — test 서비스가 고아로 보임) 이야기. cd-test는 전체 파일 세트
  (`-f compose.yaml -f compose.test.yaml`)로 실행하므로 고아 = 정말 정의가 사라진 컨테이너
  (전환 시점의 구 dev-*)뿐이라 안전하고, 첫 배포에서 구 컨테이너를 자동 정리한다.
- **cd.yml(운영)**: 트리거·내용 무변경(main push). 주석·문서의 승격 경로만 staging→main으로.

### 전환 런북 (1회, 순서 중요)

1. PR → **develop 머지** — 이 시점부터 develop 머지는 배포를 트리거하지 않는다.
2. 곧바로 **develop→main 머지(운영 배포)** — 네트워크가 서버에 생성되고 운영 컨테이너 전체가
   1회 재생성된다. **새벽 크롤·분석 시간대(01:00~07:00 KST) 회피.** 이 배포 전까지 서버의
   compose.yaml엔 네트워크 선언이 없으므로, 먼저 도는 cd-test는 config 검증에서 명시 실패한다
   (fail-closed — main 배포 후 재실행).
   - 이 사이 구 dev 컨테이너들은 구 기본 네트워크에 남고 caddy는 prod/test로 옮겨가므로
     **dev-api 라우팅이 일시 502** — 3단계까지의 과도기 상태(운영 무영향).
3. **staging 브랜치 생성·push**(develop에서) → CI → cd-test가 test 스택 배포 + 구 dev-* 정리.
4. 검증: `api`·`dev-api` 양쪽 `/health` 200, 격리 확인
   (`docker exec deploy-test-was-1 bash -c '</dev/tcp/postgres/5432'` → 실패해야 정상),
   터널 8083 어드민 정상, 구 `deploy-dev-*` 컨테이너 부재 확인.

### 문서

CLAUDE.md(브랜치 규칙·배포 경로), deploy/README.md(§5 배포·§12 test 스테이징 전면 갱신),
ARCHITECTURE.md(§5 트랙 W 추가 — S·T·U·V는 develop 병렬 트랙이 선점·§7 결정 기록),
구 스펙(2026-07-26)은 상태 헤더로 본 문서 연결.

## 검증

- 로컬: `docker compose -f compose.yaml -f compose.test.yaml --profile test config` 렌더 성공
  (전 서비스 네트워크 소속·서비스명·태그 확인), 워크플로 YAML 문법 검사.
- 서버: 전환 런북 4단계.
