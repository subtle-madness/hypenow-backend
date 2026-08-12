> 상태: 🗄 대체됨 (부분) — 구조 원리(운영 동거·raw 공유·analytics_dev 격리·파일 공급 분리)는 유효하나,
> 트리거·명칭은 [2026-07-29-staging-branch-test-stack-design.md](../2026-07-29-staging-branch-test-stack-design.md)로
> 개편(develop CI→staging CI, dev-*→test-*, `:develop`→`:staging`, prod/test 네트워크 분리)

# dev 스테이징 환경 설계 — 운영 인스턴스 동거 + raw 공유(`analytics_dev` 스키마 격리)

## 배경

develop→main 머지가 곧 운영 배포(CD)인 현 체계에서, 새 기능을 **운영 반영 전에 실제 환경·실데이터로
확인할 자리**가 없다. 요구사항: 크롤러는 제외하고 analytics + was를 dev로 두며, was API 응답까지
확인 가능해야 하고, **무료 한도 내**여야 한다.

### 제약 (2026-07-26 실측)

- **OCI Always Free A1 한도가 2026-06-15부로 4 OCPU/24GB → 2 OCPU/12GB로 축소**
  (월 1,500 OCPU-h + 9,000 GB-h, free tier·PAYG 공통). 운영 인스턴스(2/12)가 한도 전량을
  이미 사용 중 — **신규 A1 인스턴스 불가**. E2.1.Micro(1GB RAM) 2개는 무료로 남아 있으나
  JVM 스테이징에 부적합(스왑 스래싱·OOM으로 검증 신뢰도 훼손).
- 운영 인스턴스 여유: 메모리 실사용 ~4.4/12GB, load 0.46 — dev 스택 ~3GB 동거 여력 충분.
- **raw DB는 10GB, 하루 ~1GB씩 성장** (raw_media_page 5.7GB·raw_profile 2.9GB — 매일 계정
  1.3만의 프로필·릴스 페이지 jsonb 스냅샷 누적). raw를 dev로 복사하는 안은 이 성장률과 함께
  디스크 소모가 2배가 되어 지속 불가능.

## 결정 요약

| 축 | 결정 | 기각 대안 |
|---|---|---|
| 배치 | 운영 인스턴스에 dev 컨테이너 동거 (`profiles: ["dev"]`) | 신규 A1(한도 소진) · E2.1.Micro(1GB 부적합) · 유료 인스턴스(요구사항 위반) |
| raw 접근 | **운영 postgres-raw 공유** — dev 계정은 crawler 테이블 읽기 전용 + `analytics_dev` 스키마만 소유 | raw 사본 복원(성장률에 무너짐) · 읽기 전용 공유+운영 뷰 사용(뷰 변경 검증 불가) |
| analysis DB | dev 전용 `dev-postgres` (Flyway·app 스키마를 dev가 자유 사용) | 운영 공유(마이그레이션 격리 불가) |
| 배포 트리거 | develop 푸시마다 자동 (`cd-dev.yml`) | 수동 트리거 |
| 접근 | `dev-api.hypenow.io` 서브도메인(Caddy) + 기존 로그인 월 | SSH 터널만(프론트 연동 불편) |
| dev 스케줄 | 전부 off — 어드민 수동 트리거만 | 운영 동일 스케줄(LLM 비용 2배·야간 raw 부하 경합) |

핵심 원리: **데이터(10GB)는 한 벌, 분석 로직(뷰)만 두 벌.** 뷰는 조회 공식이라 용량이 0에
가깝고, develop 브랜치의 뷰를 같은 raw DB 안 `analytics_dev` 스키마에 나란히 설치하면
데이터 복사 없이 로직만 격리된다. dev가 추가로 먹는 디스크는 dev용 스냅샷 캐시(~0.6GB)뿐.

## 구성

### 컨테이너 (deploy/compose.yaml에 `profiles: ["dev"]`로 추가)

| 컨테이너 | 이미지 | 역할 | 포트 | 리소스 상한 |
|---|---|---|---|---|
| `dev-postgres` | postgres:17-alpine | dev analysis DB (분석 결과 public + app 스키마) | 루프백 5434 | mem 512m |
| `dev-analytics` | `hypenow-analytics:develop` | 미러·분석 잡 (수동 트리거), 어드민 `/ui` | 루프백 8083 | mem 1.5g / `-Xmx768m` |
| `dev-was` | `hypenow-was:develop` | dev API 서빙 | 미노출 (caddy 라우팅) | mem 1g / `-Xmx512m` |

같은 compose 파일·네트워크를 쓰므로 caddy가 dev-was로 라우팅하고 dev-analytics가
postgres-raw에 접속하는 데 추가 배선이 없다. `mem_limit`·`cpus` 상한으로 dev 폭주가
운영을 침범하지 못하게 한다(기존 85% 알람이 2차 방어).

### raw DB 계정·스키마

- 신규 role `analytics_dev`(비밀번호는 서버 `.env`): `GRANT SELECT` on public(crawler 테이블
  + app_setting), `analytics_dev` 스키마 소유(CREATE 포함). **`analytics` 스키마와 public에
  쓰기 권한 없음** — 치환 누락 등 어떤 실수도 운영 오염이 아니라 권한 오류(fail-closed)로 끝난다.
- `analytics_dev` 스키마: develop 브랜치의 뷰 전체 + `content_snapshot_cache` 테이블 +
  `refresh_snapshot_cache()` 등 함수의 dev 사본.

## 코드 변경 (유일한 Java 작업)

analytics에 프로퍼티 `analytics.raw-schema`(기본값 `analytics`) 신설. SQL 문자열에
`analytics.` 스키마를 하드코딩한 약 30곳(ContentAnalysisJob·미러·커버리지·어드민 등
10여 파일)을 이 값으로 치환한다. **운영은 기본값이라 동작 불변**, dev 컨테이너만 env로
`analytics_dev`를 주입. 기본값 경로(=운영 SQL이 기존과 동일한 스키마를 참조)를 잠그는
회귀 테스트를 포함한다.

was·crawler는 무변경(was는 raw DB 접근 금지 경계 그대로, crawler는 dev 없음).

## 뷰 적용 파이프라인 (dev CD의 한 단계)

1. `CREATE SCHEMA IF NOT EXISTS analytics_dev` + `analytics_dev` role 권한 (멱등)
2. `analytics/views/NN_*.sql` 번호순으로 `analytics.` → `analytics_dev.` 치환 적용.
   **따옴표 문자열 내부는 치환 제외** — 뷰 SQL 안의 app_setting 키(`'analytics.recent-window'`
   등)가 깨지면 안 된다.
3. 적용 후 자동 검증: `analytics_dev` 내 뷰 정의(pg_views)와 함수 본문을 스캔해
   `analytics.` 참조가 남아 있으면 **배포 실패** 처리(치환 누락 감지).

치환 스크립트는 하니스 컨벤션대로 픽스처 테스트(따옴표 키 보존·오브젝트 수 일치)를 둔다.
운영 경로(치환 없는 원문 적용)는 기존 CI sql-harness가 매 PR 프레시 DB 적용으로 이미 검증한다.

## dev CD — `cd-dev.yml`

- 트리거: **develop 푸시** (CI 통과 후 실행).
- 이미지: `:develop` 태그, **arm64 단일 빌드**(서버가 arm64뿐 — 운영 CD의 멀티아치와 달리 빠름).
- 서버 단계(SSH): 뷰 적용 파이프라인(위) → `docker compose --profile dev pull` →
  `up -d dev-postgres dev-analytics dev-was` → `dev-was /health` 확인.
- 운영 CD(main 트리거)와 워크플로 분리 — 상호 간섭 없음. dev-postgres의 Flyway는
  dev-analytics·dev-was 기동 시 develop 기준으로 자동 적용.

## 접근 — `dev-api.hypenow.io`

- DNS A 레코드를 운영과 같은 IP로 추가, Caddy에 사이트 블록 추가 → `dev-was:8081`.
- 보호는 별도 인증 계층 없이 **was의 기존 로그인 월** 사용 — dev app 스키마는 별도라
  dev 전용 가입 코드를 시드해 팀만 가입. 레이트리밋·CSRF 등 기존 방어 그대로.
- 프론트 dev(Vercel 프리뷰)는 운영과 같은 rewrite 패턴으로 `dev-api`를 바라보면
  same-origin이 되어 CORS 추가 설정 불필요.
- dev-analytics 어드민(8083)·dev-postgres(5434)는 기존 컨벤션대로 루프백 + SSH 터널.

## dev 데이터 수명·운영 규칙

- **raw**: 운영 실데이터 실시간 공유(읽기 전용) — 갱신 작업 없음.
- **분석 결과**: dev-analytics 어드민에서 미러를 수동 실행 → `analytics_dev` 뷰 기준으로
  dev analysis DB가 채워짐. 스냅샷 캐시도 dev에서 수동 refresh.
- **스케줄 전부 off** (`ANALYTICS_SCHEDULE_ENABLED=false`) — LLM(Vertex) 비용·쿼터 이중
  지출과 운영 raw 야간 부하 경합 방지. LLM 자격증명은 운영 것 공유(수동 소량 실행 전제).

## 알려진 한계 (수용)

1. **새 app_setting 키**: 키는 crawler(main) Flyway로 시드되므로 develop 시점의 dev에선
   미존재 — 뷰의 COALESCE 기본값 컨벤션이 이를 흡수(기존 규칙 그대로).
2. **crawler·raw 스키마 변경은 dev 검증 대상 아님** — 크롤러는 dev 없음(요구사항).
3. dev는 뷰를 치환된 이름으로 검증하므로 "원문 그대로 적용" 순간 자체는 dev가 아닌
   CI sql-harness가 커버.
4. dev 분석 잡 실행 중에는 운영 raw DB에 읽기 부하가 걸림 — 같은 인스턴스 동거라 총량
   동일, 수동 트리거 원칙으로 시점 통제.

## 범위 밖

- **운영 디스크 런웨이 대응**(여유 42GB, 하루 ~1.5GB 소모로 4~5주 내 고갈 전망 —
  무료 블록 볼륨 +100GB·백업 보존 조정·raw 보존 정책): 별도 세션에서 진행하기로 확정.
- 운영 인스턴스 분리(서버 나누기): 부하 실측상 불필요 + A1 한도 축소로 무료 불가 —
  현행 단일 인스턴스 유지 결정.
