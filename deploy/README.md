# 운영 런북 — was+DB 오라클 배포

스펙: [2026-07-15-oracle-deploy-design.md](../docs/superpowers/specs/2026-07-15-oracle-deploy-design.md)

## 0. 준비물 (사용자 직접)
- 오라클 계정 (홈 리전 **도쿄** — 춘천은 무료 A1 생성 불가. 해외 결제 가능 신용카드)
- 보유 도메인 DNS 관리 접근
- GitHub PAT 2개: 로컬용 `write:packages`, 서버용 `read:packages`

## 1. 인스턴스 생성 체크리스트 (오라클 콘솔)
- Shape: **VM.Standard.A1.Flex — 2 OCPU / 12GB** (Always Free 딱지 확인)
- Image: Ubuntu 24.04 (aarch64), Boot volume: 100GB
- VCN Security List ingress: 22/80/443 (0.0.0.0/0), 5432는 열지 않음
- SSH 공개키 등록 → 생성 후 공인 IP 확보
- "Out of capacity" 시: 시간대 바꿔 재시도 (며칠 걸릴 수 있음 — 그 경우 폴백 검토)

## 2. DNS (hypenow.io)
- A레코드 `api.hypenow.io` → 인스턴스 공인 IP (TTL 300 권장)
- 프론트: `www.hypenow.io`를 Vercel 커스텀 도메인으로 연결 (Vercel 안내 따라 CNAME)

## 3. 최초 기동
```bash
# 맥에서
rsync -av deploy/ ubuntu@<IP>:~/deploy/
ssh ubuntu@<IP> '~/deploy/setup-server.sh'    # 이후 재접속(docker 그룹)
ssh ubuntu@<IP>
# 서버에서
cd ~/deploy && cp .env.example .env && vi .env    # DB_PASSWORD 강한 값, API_DOMAIN 실제 도메인
docker login ghcr.io -u <github-id>               # read:packages PAT
# 다시 맥에서 — 첫 이미지 빌드·push + 서버 기동까지 한 번에 (buildx 빌더는 스크립트가 자동 준비)
docker login ghcr.io -u <github-id>               # 맥: write:packages PAT (최초 1회)
deploy/scripts/deploy.sh ubuntu@<IP>
curl -s https://api.hypenow.io/health             # {"status":"ok","service":"was"}
```
※ 첫 push 후 GitHub → Packages → `hypenow-was` 설정에서 visibility·저장소 연결을 확인하고, 서버 PAT 계정에 read 권한이 있는지 확인 (패키지는 기본 private).

## 4. 클라우드 DB 채우기 (맥에서)
```bash
deploy/scripts/tunnel.sh ubuntu@<IP>              # 터미널 1: 터널 유지
CLOUD_DB_USER=celfit CLOUD_DB_PASSWORD=<위 .env 값> \
  ./gradlew :analytics:bootRun --args='--spring.profiles.active=cloud'   # 터미널 2: Flyway+미러
```
- was의 app 스키마는 was 컨테이너가 기동 시 자체 Flyway로 생성
- ※ 맥 one-shot 방식은 analytics 상주 컨테이너(§4-1) 도입 전 절차 — 지금은 서버 상주가 정본

## 4-1. analytics 상주 (서버, 07-19~)
- 컨테이너 `analytics`(8082, 루프백 전용): raw(postgres-raw) 읽기 → analysis(postgres) 쓰기,
  LLM은 Gemini 무료 키 — 서버 `.env`에 `GEMINI_API_KEY` 필요 (백필 Batch는 `GEMINI_API_KEY_PAID` 별도)
- 스케줄(compose env, KST): 미러 04:30 → 콘텐츠 분석 05:00 → 계정 카피 07:00 (백업 04:10 뒤)
- 어드민 UI: `ssh -L 8082:localhost:8082 <host>` 후 http://localhost:8082/ui — 잡 수동 트리거·로그
- 분석 뷰는 이미지에 없다 — 07-20부터 CD(§5)가 매 배포마다 자동 적용. 수동 적용이 필요하면:
  `cat analytics/views/*.sql | ssh <host> 'docker exec -i deploy-postgres-raw-1 psql -U crawler -d crawler -v ON_ERROR_STOP=1 -q'`
- LLM 예산(무료 티어 일 1,500콜)은 raw DB `app_setting`: `analytics.analyze-batch-limit`(기본 10 → 운영 450),
  `analytics.account-analyze-batch-limit`(→150)

## 4-2. crawler 스케줄 (서버, 07-22~)
- 데일리 자동(compose env, KST — 윈도우 반복: 잡이 남은 대상만 집어 실패·재기동을 흡수):
  collect 01:00~03:30/30분 → reels 01:10~03:55/15분 → qualify 02:00~03:30/30분 → beauty 03:00·03:30
  — analytics 미러 04:30 전 완결. 발굴(discover·similar)은 수동.
- 어드민 UI: `ssh -L 8080:localhost:8080 <host>` 후 http://localhost:8080/ui — 대시보드에서
  잡 수동 트리거(실행 스트립)·예상 비용·실행 로그·최근 실행 확인
- 롤백: compose의 `CRAWLER_SCHEDULE_ENABLED: "false"` 후 `docker compose up -d crawler`
  — 서버에서 직접 고친 값은 다음 CD 배포가 레포 compose로 덮어쓴다. 영구 off는 레포 compose 수정 후 main까지 승격(develop→staging→main).

## 5. 배포 (코드 변경 반영)

**정본은 CD (07-20~)**: `main`에 푸시(=staging→main 머지 — 승격 흐름은 develop→staging→main,
07-29 staging 브랜치 전환)하면 `.github/workflows/cd.yml`이
was·analytics·crawler·monitoring 이미지 빌드·push → 서버 compose pull → caddy reload →
analytics·crawler·monitoring 재기동(`--wait`) → **was 롤링(§5-1, 무중단)** → 나머지 정합 `up -d` →
**분석 뷰 raw DB 적용**(멱등, §4-1의 수동 절차를 대체) → `/health`·monitoring healthy 확인 →
**댕글링 이미지 정리**(`docker image prune -f`, 실패해도 배포는 실패 처리 안 함)까지 수행한다.
매 배포마다 4종 이미지가 `:latest`로 덮이며 이전 레이어가 댕글링으로 쌓여 서버 디스크를
잠식하므로(07-30 실측: 회수 가능분 88%), 헬스체크 전부 통과 뒤 마지막에 정리한다 — dangling-only만
(`-a` 금지, 롤백용 `sha-*` 태그 이미지를 지킨다). test 스테이징 배포(`cd-test.yml`)와 긴급 경로
(`deploy.sh`)도 같은 서버를 공유하므로 동일하게 정리한다.
- 필요 시크릿(GitHub → Settings → Secrets and variables → Actions):
  `DEPLOY_SSH_KEY`(서버 ssh 개인키), `DEPLOY_HOST`(서버 호스트/IP — ssh 사용자는 ubuntu)
- 컬럼 이름·타입이 바뀌는 뷰 변경은 `CREATE OR REPLACE` 불가 — 해당 SQL에 `DROP VIEW` 포함 필요

### 5-1. was 무중단 롤링과 expand-contract 규율 (07-29, 트랙 X)

**롤링 대상은 was 하나** — analytics·crawler는 내부 배치/어드민이라 재기동 다운타임이 무해하고,
test 스택도 재기동 유지. was는 세션 JDBC 영속 + 캐시 외부 redis라 복제 2개 공존이 안전하다.

- **동작**(`scripts/rollout.sh`, CD가 서버로 동기화 후 호출): 잔재 검사(정지분 포함 1개 확인 —
  정지 잔재가 있으면 `--scale`이 그걸 재기동해 구버전이 신으로 둔갑한다, 리뷰 C1) → 신 컨테이너를
  `--scale was=2 --no-recreate`로 추가 기동 → 이미지 일치 검증 → healthcheck healthy 대기(최대
  180초) → **스모크**(`/v1/stats` — TCP 리슨만으론 "기동됐는데 쿼리가 깨지는" 07-20 계열을 못
  잡아서, analysis DB 실조회 경로를 신 컨테이너 안에서 확인) → 구 컨테이너 `stop -t 40`(graceful
  drain)·제거. caddy는 서비스명 도커 DNS로 프록시하므로 교대 중 양쪽에 분산된다.
- **무중단의 범위**: 교대 순간의 신규 연결 실패는 Caddy `lb_try_duration 10s` 재시도가 전
  메서드에서 흡수한다(연결 실패는 메서드 무관 재시도 — Caddy 문서 기준). 잔여 리스크는 구
  컨테이너 종료 직전 **유휴 keep-alive 커넥션에 실린 비-GET 요청**(수 ms 창, 재시도 불가 —
  중복 실행 위험이 더 커서 의도적으로 안 덮는다) 뿐. 배포 중 502 한두 건이 보이면 이 케이스다.
- **실패 모드 = 무중단 실패**: 신이 healthy·스모크에 못 가면 신만 제거하고 구가 계속 서빙, CD만
  빨간불. 단 그 시점엔 **신 analytics + 구 was 스큐**가 남는다(analytics·crawler는 롤링 전에 이미
  교체됨) — expand-contract가 지켜졌으면 안전한 조합이며, 조치는 원인 수정 후 재배포(또는 CD 런
  Re-run). 스큐를 오래 방치하지 말 것. **CD가 아예 안 도는(cancelled) 경우도 별도로 있다** — 대기
  중이던 운영 CD가 뒤이은 test 배포 큐잉에 조용히 취소되는 함정, 대응은 §12 "주의(함정 3건)"
  참조(07-30 staging→main 승격에서 재확인).
- **전제 3가지**(rollout.sh 머리 주석과 동일): was는 host 포트 미점유 · compose healthcheck 정의 ·
  Spring `server.shutdown: graceful`(application-prod.yml) + compose `stop_grace_period: 40s`.
- **순서 규약**: rollout 전에 `up -d --wait analytics`로 analysis Flyway 완료를 보장한다
  (07-20 "새 분석 컬럼 참조 500" 가드의 롤링판 — was의 depends_on은 `--no-deps`로 우회되므로
  CD 순서가 그 역할을 대신한다).
- **운영 특성 2가지**: ①롤링 반복마다 컨테이너 이름 번호가 증가한다(`deploy-was-2`, `-3`… —
  무해, 이름으로 스크립트 짜지 말 것) ②교대 중 최대 ~3분간 was 2개가 공존하므로 인메모리
  레이트리밋(로그인·가입)이 그 창에서 실효 2배가 된다(수용).
- 긴급 경로 `deploy.sh`는 **단순 재기동 유지**(다운타임 있음) — 긴급 시 단순함이 우선.

**expand-contract 규율** — 롤링 중 구버전 코드와 신버전 코드가 같은 DB를 몇십 초 공존해서 본다.
따라서 마이그레이션은 "현재 배포된 코드"와 호환되어야 한다:

- **같은 릴리스 금지**: `DROP TABLE`/`DROP COLUMN`, `RENAME`(테이블·컬럼), 타입 변경,
  `SET NOT NULL` — 구버전을 즉사시키는 변경. CI `migration-guard` 잡
  (`.github/scripts/check-migration-safety.sh`)이 PR에서 차단한다.
- **가드 스코프와 한계** — 가드는 보조 장치지 규율의 대체물이 아니다. 대상은 analysis DB
  마이그레이션(was `db/migration/app` + `analytics …/analysis`)만 — crawler(raw)는 재기동
  배포라 공존이 없고 was는 raw 접근 금지라 대상 외. **가드가 못 잡는 파괴적 변경**(리뷰에서
  확인 — 리뷰어가 볼 것): DEFAULT 없는 `ADD COLUMN … NOT NULL`(구버전 INSERT 즉사),
  `DROP VIEW`/`DROP INDEX`/`DROP CONSTRAINT`(단일 마이그레이션 안에서 DROP+재생성은
  트랜잭션이라 안전 — 재생성 없는 단독 DROP만 위험), `ADD CONSTRAINT … UNIQUE`(중복 데이터
  시 즉사), `TRUNCATE`, 그리고 **데이터 형태 변경**(미러가 값 도메인을 바꾸는 종류).
- **v3(07-30) — Flyway 버전 번호 중복 검사.** PR #181이 `V43__landing_stats_nano_band.sql`을
  들고 있는 사이 develop이 `V43__trait_taxonomy_makeup_review.sql`을 선점, 그대로 머지되면
  같은 버전 2개로 Flyway 기동이 거부된다(V18·V43에 이어 3번째 재발). PR 브랜치 자기 트리만
  봐서는 못 잡는다 — 그 브랜치엔 V43이 1개뿐이라 충돌은 base와 합쳐질 때만 드러나므로,
  `migration-guard` 잡의 `check-migration-safety.sh <base-ref>` 경로에 **base ref와 HEAD의
  트리 스냅샷을 직접 대조**하는 검사를 추가했다(`git ls-tree`, diff가 아님 — diff 기반이면
  "이번 PR이 안 건드린 기존 파일과의 충돌"을 놓친다).
  **스코프가 위 파괴적 DDL 검사와 다르다(의도)**: 파괴적 DDL 검사는 was 롤링 공존 근거가
  있는 analysis DB만 보지만, 버전 중복은 근거가 다르다 — 어느 Flyway 인스턴스든 중복
  버전이면 그 인스턴스 자체가 기동을 거부한다(신구 공존 여부와 무관한 실패 모드). Flyway
  인스턴스는 4개이고 각각 독립 버전 공간(별도 히스토리 테이블)이라 디렉토리별로 독립
  검사하며(was의 V1과 analytics의 V1은 정상), crawler·monitoring을 포함해 **4개 전부**를
  대상으로 한다. 버전 비교는 Flyway와 동일하게 숫자 기준(선행 0 정규화 — `V07` == `V7`).
  집합 단위 검사라 파일 단위 `--scan`과는 별도 seam인 `--versions <base-목록> <head-목록>`으로
  git 없이도 테스트 가능(`check-migration-safety.test.sh`).
  - **v3.1(같은 날, 후속 실측) — #181의 진짜 원인 정정, 인라인 검사 통합.** v3 도입 시점엔
    "버전 중복을 잡는 검사가 없어서" #181이 났다고 서술했으나 부정확했다: `ci.yml`의
    `test` 잡에는 이미 07-21(V35·V36 재발) 이후 붙은 인라인 버전 중복 검사가 있었고,
    실측해보니 **그 검사도 로직상 #181을 잡을 수 있었다**(PR CI가 `refs/pull/N/merge`를
    체크아웃하므로 머지 트리엔 V43이 2개 보였을 것). 실제 원인은 검사 부재가 아니라
    **PR CI 재실행 부재**였다 — #181의 CI는 base가 V43-trait을 얻기 전에 실행됐고, 그 뒤
    base가 바뀌었는데도 재실행 없이 머지됐다. **이 통합(v3.1)은 그 레이스를 고치지
    못한다** — 고치는 건 브랜치 보호 룰셋이고, **07-30에 적용 완료**: 룰셋
    `protect-release-branches`(develop·staging·main)에 `required_status_checks`를 추가하고
    `strict_required_status_checks_policy=true`(머지 전 브랜치 최신화 요구)를 켰다.
    요구 체크는 `Gradle 전체 테스트`·`마이그레이션 롤링 호환 가드`·`분석 뷰 SQL 하니스` 3종.
    이제 base가 움직인 PR은 `BEHIND`로 머지가 막히고, 최신화하면 CI가 재실행되면서 이
    버전 검사가 **최신 base 기준으로** 다시 돈다 — 그게 #181류를 실제로 막는 지점이다.
    (부작용 2건: ①머지 시점에 "Update branch"가 필요해짐 ②`ci.yml`은 PR에서
    `cancel-in-progress: true`라 취소된 CI는 재실행해야 머지가 풀린다.) v3.1이
    실제로 주는 이득은: ①검사 로직이 셀프테스트로 보호되는 **단일 구현**이 됨(인라인
    `ls | uniq -d` 중복 삭제) ②인라인 검사가 빠뜨렸던 monitoring 디렉토리 포함
    ③선행 0 정규화(`V07`==`V7`, 인라인엔 없었음) ④base 대조 모드(PR 전용)까지 갖춤.
    `test` 잡은 push 이벤트에서도 돌아 base_ref가 없으므로, 그 경로는 트리 단독 검사
    (`check-migration-safety.sh --versions-tree`, git 비의존 — `test` 잡 checkout이
    `fetch-depth` 없는 얕은 클론이라 git 이력에 의존할 수 없음)로 대체했다.
  - **v3.2(07-30) — 신규 마이그레이션은 UTC 타임스탬프로 채번, 경합을 애초에 없앤다.**
    v3·v3.1은 "충돌을 잡는" 검사였지만 근본 원인(병행 세션이 같은 다음 정수 번호를 집는 것)은
    그대로였다 — V18→V19, V22→V23에 이어 V43(#181)까지 3연속 재발. 그래서 **신규 파일**은
    `V<YYYYMMDDHHMMSS>__<설명>.sql`(UTC)로 채번하기로 정했다(CLAUDE.md 컨벤션 절). **기존
    `V1`~`V49` 파일은 rename하지 않는다** — `schema_history`에 버전·체크섬이 기록돼 있어
    rename하면 운영 DB에서 마이그레이션이 깨진다. 정렬 전제(`V42 < V20260730112500`)는 Flyway
    자체 API로 실측 확인했다: `flyway-core` 12.4.0의 `MigrationVersion.fromVersion("42")
    .compareTo(MigrationVersion.fromVersion("20260730112500"))`가 음수를 반환하고, `V07`과
    `V7`도 여전히 동일 버전으로 취급된다(선행 0 정규화) — Flyway가 버전을 정수(BigInteger)로
    비교하는 한 자릿수는 비교 결과에 영향을 주지 않는다. **가드 스크립트는 무수정**으로
    호환된다: `normalize_version`의 정규식(`^V[0-9]+(\.[0-9]+)*__`)과 `10#$p` 정규화 둘 다
    자릿수 제한이 없어 14자리 타임스탬프를 그대로 파싱한다 — 셀프테스트에 정수·타임스탬프
    공존/충돌 케이스 4건을 추가해 회귀를 막았다(31케이스). 대상은 v3와 동일하게 독립 버전
    공간 4개(crawler, analytics `db/migration/analysis`, was `db/migration/app`, monitoring)
    전부 — 각 디렉토리 안에서 정수 연번과 타임스탬프가 영구 공존한다.
  - **v4(08-13) — 신규 채번 질서 검사(미래 채번·역전 차단).** v3.2의 타임스탬프 채번은 "모두가
    UTC를 쓴다"는 전제 위에서만 안전했다 — 08-12에 monitoring 마이그레이션이 KST(+9h)로
    채번되며 미래 번호를 선점했고, 이후 UTC로 *정상* 채번된 파일들이 숫자상 더 작아 Flyway
    out-of-order 거부 → 운영 monitoring 크래시루프가 같은 날 두 번 재발했다(#442·#444·#453
    배포 실패, 핫픽스 #445·#455). 그래서 base 목록에 없는 **신규 파일**에 두 검사를 추가했다:
    ①번호가 자기 디렉토리 base 최대 이하면 차단(역전 — 심긴 미래 번호에 뒤따르는 정상 채번이
    운영에서 터지는 걸 PR에서 미리 잡는다) ②14자리 타임스탬프가 현재 UTC+1h를 넘으면
    차단(미래 채번 — KST 채번은 +9h라 반드시 걸린다. +1h는 분 올림 관행 허용 오차). 이미 DB에
    박힌 미래 번호 위로 올라가는 의도적 채번(핫픽스 #455 케이스)은 파일에
    `-- allow-future-version: <사유>` 주석으로 통과시킨다(allow-destructive와 같은 관용구).
    base 대조가 가능한 PR·merge_group 경로(`migration-guard` 잡)에서만 돈다 — push 경로
    (`--versions-tree`)는 base 없이 "신규"를 구분할 수 없고, 머지 전 경로가 전수 커버한다.
- **rename은 rename하지 않는다 — 컬럼 이행 레시피**(타입 변경도 동일):
  1. expand 릴리스: `ADD COLUMN` + **백필 UPDATE를 같은 마이그레이션에**(Flyway가 실행 보장) +
     코드를 새 컬럼으로 전환. 백필 통째 누락은 신 컬럼 전 행 NULL = 기능이 비어 보이므로
     staging(test 스택) 검증 관문에서 걸린다.
  2. 롤링 창(수십 초) 동안 구코드가 구 컬럼에만 쓴 행은 새 컬럼이 낡는다 — 이 유실분은
     contract 시점의 **보정 UPDATE**(아래 3)가 쓸어 담는다. 트래픽이 커져 창 유실을 초 단위로도
     못 참게 되면 그때 dual-write 릴리스를 끼운다(현 규모에선 불요).
  3. contract 릴리스(참조 코드가 사라진 뒤 아무 때나): **보정 UPDATE(멱등) + `DROP COLUMN`을
     같은 파일에**. 가드 v2가 이 짝을 기계로 강제한다 — DROP COLUMN이 있는 파일에 그 컬럼을
     참조하는 UPDATE가 없으면 CI 실패. 보정이 원리적으로 불필요한 컬럼(미러가 매일 전체
     재기록하는 분석 테이블 등)은 `-- no-backfill: <사유>` 주석으로 통과시킨다.
- **추가는 자유**: 새 컬럼은 nullable 또는 `DEFAULT` 포함.
- **의도된 contract 마이그레이션**은 파일에 `-- allow-destructive: <사유>` 주석으로 가드를
  통과시킨다 — 사유에 "참조 코드가 언제 제거됐는지"를 적는다. DROP COLUMN이면 위 3의
  보정 짝 검사가 추가로 걸린다(allow-destructive와 독립).
- **실시간 쓰기 컬럼(app 스키마)은 애초에 이행 자체를 피한다** — 이행 비용이 이름값을 넘는다.
  분석 테이블은 미러 소유라 이 고민이 없다.

수동·긴급 경로(맥에서) — **CD 불능·긴급 롤백 전용**. 스크립트가 HEAD≠origin/main이면 거부한다
(07-20 장애 재발 방지 가드 — `:latest`는 마지막 push가 이겨서 CD 배포를 덮는다):
```bash
deploy/scripts/deploy.sh --force ubuntu@<IP>      # 기본 was+analytics — crawler는 인자로 추가
```
- 매 배포마다 `:latest`와 함께 `:sha-<short>` 태그도 push된다 (GitHub → Packages에서 확인)
- **롤백**: `ssh ubuntu@<IP> 'cd ~/deploy && docker pull ghcr.io/subtle-madness/hypenow-was:sha-<short> && docker tag ghcr.io/subtle-madness/hypenow-was:sha-<short> ghcr.io/subtle-madness/hypenow-was:latest && docker compose up -d was'` (다음 정상 배포가 latest를 다시 덮는다)

### 5-2. 이미지 스토리지 OCI→GCS 컷오버 (2026-08-12 스펙)

스펙: [2026-08-12-gcs-image-migration-design.md](../docs/superpowers/specs/2026-08-12-gcs-image-migration-design.md)

순서 불변식: **서빙(rewrite)이 보는 버킷 ⊇ 쓰기 대상 버킷**. front 전환은 반드시
"잡 정지 + 델타 복사 후, 백엔드 `IMAGE_STORE=gcs` 배포 전".

**스테이징에는 GCS 배선이 없다**(`deploy/compose.test.yaml`은 PAR만) — **GCS 경로는 운영에서
처음 돈다**. 그래서 front를 넘기기 전(5단계) 4단계의 복사·검증을 성의껏 할 것.

**시크릿 파일**: `deploy/secrets/gcs-image-archiver.json` (SA 키 JSON, 커밋 금지).
compose가 analytics·monitoring 양쪽에 `/run/secrets/gcs-image-archiver.json:ro`로 **파일**
마운트하고, 컨테이너 기본값 `IMAGE_GCS_KEY=/run/secrets/gcs-image-archiver.json`을 코드가 직접
읽는다(analytics의 `GOOGLE_APPLICATION_CREDENTIALS`=Vertex ADC와 분리 — ADC는 JVM당 하나뿐이라
덮으면 Vertex LLM이 전면 403). 서버 `.env`에 `IMAGE_GCS_KEY`를 쓸 필요는 없다.
- **par 모드에서도 빈 파일이라도 반드시 선생성**한다 — 절차는 아래 **0단계**.
- 반대로 **`IMAGE_STORE=gcs`로 넘기기 전에는 실키가 반드시 먼저 있어야 한다** — 빈 placeholder는
  par 모드에선 무해하지만 gcs 모드에선 키 로드 실패로 **monitoring이 기동 실패(fail-fast)**한다.

0. **(이 브랜치 운영 배포 전 필수)** compose가 `deploy/secrets/gcs-image-archiver.json` 파일 마운트를
   기대하므로, 배포 전에 서버에 placeholder 파일을 만들어 둔다(없으면 docker가 root 소유 **디렉토리**를
   만들어 이후 scp가 Permission denied로 실패):
   ```bash
   ssh ubuntu@155.248.187.106 'install -m 600 /dev/null ~/deploy/secrets/gcs-image-archiver.json'
   ```
   이미 디렉토리가 생겨버렸다면 `sudo rmdir ~/deploy/secrets/gcs-image-archiver.json` 후 위 명령.
   par 모드에선 빈 파일이어도 무해하다.
1. GCP 준비(로컬, 1회):
   ```bash
   gcloud config set project <PROJECT_ID>
   gcloud storage buckets create gs://hypenow-images --location=asia-northeast3 \
     --uniform-bucket-level-access   # 전역 이름 충돌 시 hypenow-images-prod
   # 공개 읽기는 legacyObjectReader로 — objectViewer는 storage.objects.list까지 포함해
   # 148k 오브젝트 키 전체가 익명 열거된다. legacyObjectReader는 개별 GET만(UBLA 호환).
   gcloud storage buckets add-iam-policy-binding gs://hypenow-images \
     --member=allUsers --role=roles/storage.legacyObjectReader
   gcloud iam service-accounts create image-archiver
   gcloud storage buckets add-iam-policy-binding gs://hypenow-images \
     --member=serviceAccount:image-archiver@<PROJECT_ID>.iam.gserviceaccount.com \
     --role=roles/storage.objectAdmin
   gcloud iam service-accounts keys create gcs-image-archiver.json \
     --iam-account=image-archiver@<PROJECT_ID>.iam.gserviceaccount.com
   ```
   **SA 키 생성이 `iam.disableServiceAccountKeyCreation`으로 거부되면**(2026-08-12 실측 —
   조직 없는 개인 프로젝트는 이 정책을 해제할 방법 자체가 없다: `orgpolicy.policyAdmin` 롤이
   무조직 프로젝트엔 부여 불가), SA 키 대신 **gcloud ADC 파일을 그대로 키 파일로 쓴다**:
   ```bash
   gcloud auth application-default login
   gcloud auth application-default set-quota-project <PROJECT_ID>
   # ~/.config/gcloud/application_default_credentials.json 이 곧 업로드할 키 파일
   ```
   코드(`GcsImageStore`)는 `GoogleCredentials.fromStream`이라 SA 키·authorized_user 둘 다
   읽는다. 이 경우 위의 image-archiver SA·objectAdmin 바인딩은 불필요(계정 owner 권한 사용).
   **콘솔에서 유료 계정 업그레이드 + 예산 알람(월 $5)** — 90일 삭제 절벽 제거.
   **이름 충돌로 `hypenow-images-prod` 같은 대체명을 쓰면 `deploy/scripts/post-container-metrics.py`의
   `GCS_BUCKETS` 상수도 같이 고칠 것** — 하드코딩이라 안 고치면 404가 나고, GCS 수집 실패는 조용히
   스킵되므로 `bucket_used_gb`가 영구 결손이 된다(7단계 확인이 곧 이 함정의 검출 지점).
   키를 서버로 (컷오버 이전에 **먼저** — 위 시크릿 파일 규칙):
   `scp gcs-image-archiver.json ubuntu@155.248.187.106:/home/ubuntu/deploy/secrets/`
2. 벌크 복사(서버에서, 서비스 무영향 — rclone remote는 oci=oracleobjectstorage(user
   principal), gcs=google cloud storage(SA 키) 타입으로 `rclone config`에서 1회 생성):
   ```bash
   # -M(--metadata): Cache-Control 등 객체 메타데이터까지 복사(없으면 4단계에서 전량 보정 필요)
   rclone copy oci:hypenow-images gcs:hypenow-images --transfers 16 -M -P
   ```
3. 잡 정지: 진행 중 브랜드 스윕이 없는지 monitoring UI(8083)에서 확인 후
   `docker compose stop analytics monitoring`. (CDN 만료 여유 3~4일 — 수 시간 정지 무손실.)
4. 델타 복사 + 검증:
   ```bash
   rclone copy oci:hypenow-images gcs:hypenow-images -M -P   # -M: 객체 메타데이터(Cache-Control) 동반 복사
   rclone check oci:hypenow-images gcs:hypenow-images --size-only
   ```
   (2·4단계를 `-M`으로 복사했다면 보정 불필요 — 아래는 **검증만**, 비어 있을 때만 보정한다.)
   **Cache-Control 확인은 프리픽스 8종 전부** — `thumb/`·`profile/`(analytics `ImageArchiveJob`),
   `monitor-profile/`·`monitor-post/`·`monitor-author/`·`monitor-brand/`·`monitor-brand-post/`·
   `monitor-hashtag-post/`(monitoring 잡 6종). 원래 값은 2종이라 보정이 필요해지면
   `gs://hypenow-images/**` 한 번에 밀지 말 것(값이 뭉개진다) — **값 그룹별 2회**로:
   ```bash
   # 샘플 확인(대표 2건) — cacheControl 필드가 비어 있으면 아래 보정
   gcloud storage objects describe gs://hypenow-images/thumb/<아무거나>.jpg
   gcloud storage objects describe gs://hypenow-images/monitor-profile/<아무거나>.jpg
   # 보정 ①  thumb — 불변(1년)
   gcloud storage objects update "gs://hypenow-images/thumb/**" \
     --cache-control="public, max-age=31536000, immutable"
   # 보정 ②  86400 그룹 7종 — 1일 (ImageArchiveJob.PROFILE_CACHE_CONTROL과 동일 값)
   gcloud storage objects update \
     "gs://hypenow-images/profile/**" "gs://hypenow-images/monitor-profile/**" \
     "gs://hypenow-images/monitor-post/**" "gs://hypenow-images/monitor-author/**" \
     "gs://hypenow-images/monitor-brand/**" "gs://hypenow-images/monitor-brand-post/**" \
     "gs://hypenow-images/monitor-hashtag-post/**" \
     --cache-control="public, max-age=86400"
   ```
5. **celfit-front rewrite 전환**(사용자): `/img/:path*`의 대상 OCI PAR URL →
   `https://storage.googleapis.com/hypenow-images/:path*`. 배포 후 기존 이미지 로드 확인.
6. 백엔드 전환: 서버 `.env`에 **두 줄만** — `IMAGE_STORE=gcs`·`IMAGE_GCS_BUCKET=hypenow-images`
   (`IMAGE_GCS_KEY`는 compose 기본값으로 충분). 서버 `.venv-oci-metrics`에
   `pip install google-auth requests`(버킷 메트릭이 GCS API를 쓴다), 정규 CD 배포(또는
   `docker compose up -d`)로 재기동 — 잡 재개 겸용.
7. 확인: 다음 아카이브 잡 후 GCS에 신규 오브젝트 적재 + 프론트에서 신규 썸네일 로드 +
   **다음 정시(top of hour)에 GCS `bucket_used_gb` 게시**. GCS는 크기 합산이 전체 목록 페이징이라
   게시가 **정시 1회**다(OCI 병행 게시는 종전대로 5분 결, `provider=oci` 차원).
   GCS 수집 실패는 컨테이너 메트릭을 지키려고
   조용히 스킵되며 cron stderr(`~/metrics-post.log`)에만 남는다 — **첫 정시 게시를 눈으로 확인**할 것.
   **익명 목록 조회가 403인지 확인**(1단계 legacyObjectReader가 제대로 걸렸는지 — objectViewer였다면 200):
   ```bash
   curl -s -o /dev/null -w '%{http_code}' 'https://storage.googleapis.com/storage/v1/b/hypenow-images/o?maxResults=1'
   # → 403 기대
   ```
   알람 임계 상향(쿼리 창도 `[1h]`로 — `[5m]`이면 정시 게시 특성상 대부분 무데이터):
   ```bash
   oci --profile HYPENOW monitoring alarm update \
     --alarm-id ocid1.alarm.oc1.ap-tokyo-1.amaaaaaa2qpilmqaat7adk6wfdeuxvzcqm7n65dnzqvybkybls36retft36q \
     --query-text 'bucket_used_gb[1h].max() > 50'
   ```
8. 롤백(5~6 사이 문제 시): front rewrite를 OCI로 원복 + `IMAGE_STORE=par`로 재배포 —
   해당 시점 두 버킷이 동일하므로 무손실.
9. **컷오버 확정 후 정리**: `deploy/scripts/post-container-metrics.py`의 **OCI 병행 게시 블록
   (`OCI_BUCKETS`/`OS_NAMESPACE` if 문)을 제거**한다 — 롤백 창(8단계)이 닫히기 전까지는 두 스트림을
   같이 봐야 관측 공백이 없으므로, 제거는 반드시 마지막에.

OCI 버킷은 삭제하지 않는다(동결 스냅샷 안전망, 월 수백 원). 컷오버 전까지는 OCI(5분 결,
`provider=oci` 차원)와 GCS(정시 1회) 두 스트림이 병행 게시되고, 9단계 이후 `bucket_used_gb`는
GCS 값 하나가 된다 — 알람은 스트림별 평가라 어느 쪽이든 그대로 동작한다(§9).

## 6. 백업·복원
- 자동: 서버 크론이 매일 KST 04:10 덤프 (맥·서버 어느 쪽이 꺼져 있든 오프사이트 사본 유지)
  - **analysis**: 서버 `~/backups/` 3일 롤링 + B2 `hypenow-backups/analysis/` 7일(기간) 롤링
    — 분석 결과는 raw에서 재파생 가능(LLM 재호출 비용만 부담)이라 짧게 유지(08-04 7일/30일에서 축소)
  - **crawler**(raw — 07-19부터 서버가 수집 주체라 서버 raw가 유일 원본): 서버는 **오프사이트
    업로드 성패에 따라 1개(성공) / `LOCAL_CRAWLER_KEEP`개(실패, 기본 2)** 롤링(`backup.sh`의
    `offsite_ok` 분기 — B2가 막혀도 로컬 사본 + 수동 pull로 버팀) + B2
    `hypenow-backups/crawler/` **최신 `B2_CRAWLER_KEEP`개**(`backup.sh` 상단 상수, 기본 3 —
    08-04 5에서 축소, 복원 창 3일) 롤링.
    덤프 전 **선-회전**으로 구 사본을 KEEP-1개까지 줄여 신규 덤프와의 동시 존재 피크를 없앤다
    (08-03 `hypenow-disk-high` 알람 원인 — 구 3 + 신규 1 공존으로 루트 디스크 85% 순간 초과).
    덤프가 하루 ~1GiB씩 느는 GB급이라 개수가 곧 용량 —
    B2 버킷 캡 초과 시 업로드가 `403 storage_cap_exceeded`로 전량 실패한다(07-27~30 실측: 기존
    "최신 30개" 정책이 요구한 ~240GB가 캡을 초과해 며칠간 오프사이트 백업 공백 발생. 07-29~
    재발 — 캡 상향 전까지 오프사이트 공백은 `pull-backup.sh` 수동 pull이 보완). 용량
    여유가 생기면 `B2_CRAWLER_KEEP`만 올릴 것.
  - **monitoring**(시딩 캠페인 — postgres 인스턴스 내 별도 DB, §13): 서버 3일 롤링 +
    B2 `hypenow-backups/monitoring/` 7일(기간) 롤링. 덤프가 작아 analysis와 같은 기간 롤링.
- 수동 pull(보조): `deploy/scripts/pull-backup.sh ubuntu@<IP>` → `~/backups/hypenow/`
- 복원 리허설(로컬): `zstdcat analysis-*.sql.zst | psql -h localhost -p 5433 -U crawler -d <빈 DB>`
  (08-04 이전 덤프는 `.sql.gz` — `gunzip -c`로. 압축은 08-04 gzip→zstd 전환: 2 vCPU에서
  gzip이 백업 CPU를 알람 문턱 직하까지 밀어 올려서다)

### 6-1. rclone(Backblaze B2) 1회 설정
```bash
# 맥에서 (B2 계정의 Application Key 필요 — B2 콘솔에서 발급)
brew install rclone
rclone config          # n → 이름 b2 → storage: b2 → Account ID·Application Key 입력
rclone lsd b2:         # 동작 확인
ssh ubuntu@<IP> 'mkdir -p ~/.config/rclone'
scp ~/.config/rclone/rclone.conf ubuntu@<IP>:~/.config/rclone/rclone.conf   # 서버로 복사
ssh ubuntu@<IP> 'rclone mkdir b2:hypenow-backups && rclone lsd b2:'  # 서버에서 확인
```
※ rclone.conf에는 B2 Application Key가 들어 있다 — repo에 커밋 금지, 서버 홈에만.
※ (07-26: Google Drive 무료 15GB 초과로 B2 전환. 07-27~30: B2도 종량제가 아니라 캡이 있어
  다시 걸림 — 위 crawler 개수 축소로 대응. `backup.sh` 상단 주석에 상세 경위 기록.)

## 7. 프론트 연동 (www.hypenow.io)
- 권장: **Vercel rewrite로 같은 오리진화** — celfit-front `vercel.json`에
  `{"rewrites":[{"source":"/api/:path*","destination":"https://api.hypenow.io/api/:path*"}]}`
  → 쿠키가 1st-party가 되어 세션·CSRF(XSRF-TOKEN 쿠키 읽기)가 자연 동작, CORS 불필요
- 직접 호출도 www↔api가 same-site(hypenow.io)라 세션 쿠키는 동작하나, **XSRF-TOKEN 쿠키가
  호스트 전용이라 www의 JS가 못 읽어 쓰기 요청이 403** — 로그인·저장을 쓰는 순간 rewrite가 사실상 필수
- prod CORS 허용 오리진은 `https://www.hypenow.io` (application-prod.yml)
- 검증: 프론트에서 로그인 → 저장 → 새로고침 유지 확인

## 8. 오라클 계정 관리
- 자리 잡으면 **PAYG 전환**(유휴 회수 면제) + **Budget 알림 $1**
- 무료 전용 상태에서는 7일 유휴 시 정지 경고 메일 주시 (JAVA_OPTS -Xms2g가 메모리 조건 방어)

## 9. 모니터링·알람 (07-21~)
- 토픽 2개: 일반 `hypenow-alerts`(알람 5개 → 디스코드) / 치명 `hypenow-alerts-critical`
  (인스턴스 다운·API 불통·**알람 릴레이 다운**(08-05) → 디스코드 **+ EMAIL 백업** — 인스턴스가 통째로 죽으면 릴레이도
  죽어 디스코드 경로가 끊기므로, OCI에서 직접 나가는 이메일이 그 순간을 커버.
  알람당 토픽 1개 제약이라 "치명 토픽에 구독 2개" 구조. 이메일 추가는 구독만 더 붙이면 됨.
  `hypenow-ons-relay-down`이 치명 토픽인 이유도 같은 구조 — 모든 디스코드 알람이 ons-relay를
  지나므로 "ons-relay 다운" 알람만은 죽은 자신을 지나 배달될 수 없다. 이메일이 유일 경로)
- 디스코드는 **릴레이 경유**(CUSTOM_HTTPS 구독 → `ons-relay` 컨테이너 → 디스코드 웹훅.
  ONS가 SLACK 엔드포인트를 hooks.slack.com만 허용해 직결 불가 — 서버 `.env`에
  `DISCORD_WEBHOOK_URL`·`ONS_RELAY_TOKEN` 필요, 경로는 caddy `/internal/ons-relay/<토큰>`.
  구독 확인은 릴레이가 자동 컨펌. PAYG 전환 시 OCI Functions로 릴레이 대체 검토):
  **API 외형 감시**(Health Checks `hypenow-api-health` — 외부 관측점 3곳에서 60초마다
  `https://api.hypenow.io/health`, 과반 실패 2분 지속 시), 인스턴스 CPU·메모리 85%, 인스턴스 다운,
  **컨테이너 다운**(compose 서비스 10종 — 08-05 redis·grafana·ons-relay 추가로 운영 전 서비스 커버.
  `container_up[1m].max() < 1`이 차원 필터 없는 스트림별 평가라 SERVICES에 서비스를 추가하면
  알람 정의 무수정으로 자동 커버된다), **디스크 85%**, **버킷 15GB**(무료 티어 20GiB 한도.
  컷오버 시 50GB 상향 — §5-2 7단계)
- 컨테이너·디스크·버킷 용량은 커스텀 메트릭(`hypenow_custom`) — 서버 크론 1분 주기
  (버킷은 스크립트가 **OCI 5분 결 + GCS 정시 1회**로 조회 — OCI/GCS 둘 다 크기를 자동 게시하지
  않아 직접 게시한다. GCS는 전체 목록 페이징이라 정시 1회. 컷오버 후 OCI 병행 게시는 제거 예정 —
  §5-2 9단계):
  `* * * * * /home/ubuntu/.venv-oci-metrics/bin/python /home/ubuntu/deploy/scripts/post-container-metrics.py >> /home/ubuntu/metrics-post.log 2>&1`
- 인증은 인스턴스 프린시펄 — 서버에 API 키를 두지 않는다. IAM 구성:
  dynamic group `hypenow-instances`(인스턴스 매칭) + policy `hypenow-custom-metrics`:
  `Allow dynamic-group hypenow-instances to use metrics in tenancy where target.metrics.namespace='hypenow_custom'`
  `Allow dynamic-group hypenow-instances to read buckets in tenancy where target.bucket.name='hypenow-images'`
  venv: `python3 -m venv ~/.venv-oci-metrics && ~/.venv-oci-metrics/bin/pip install oci google-auth requests`
  (GCS 버킷 크기 조회만은 예외로 SA 키 파일 `~/deploy/secrets/gcs-image-archiver.json`을 읽는다 —
  compose가 쓰는 것과 같은 파일. 2026-08-12 이미지 스토리지 이전 이후)
- 컨테이너 추가·이름 변경 시 스크립트의 `SERVICES` 목록도 갱신할 것(목록 고정 방식 —
  사라진 컨테이너도 0으로 게시해 알람이 잡는다). 버킷 추가 시 `OCI_BUCKETS`/`GCS_BUCKETS` 목록 갱신.
- **컨테이너 조회는 이름이 아니라 compose 라벨로 한다**(project=`deploy` + service=`<svc>`).
  `deploy-<svc>-1` 이름을 쓰면 안 되는 이유: 롤링 재기동(`rollout.sh`)이 `--scale <svc>=2`로
  **다음 빈 인덱스**에 신 컨테이너를 띄우고 구 1번을 지워, 첫 롤링 이후 `-1`은 영영 없다
  (07-30 롤링 도입 직후 was가 상시 다운으로 오탐 → `hypenow-container-down`이 16시간 넘게
  1시간 주기로 재알림. 실제 컨테이너는 `deploy-was-8` healthy였다). 어느 컨테이너인지는
  **알람 본문의 `📍 containerName=<서비스>` 줄로 확인**(08-13~ 릴레이가
  `alarmMetaData[].dimensions`를 표기 — 디스크 `host=`·버킷 `bucketName=`도 동일).
  본문이 잘렸거나 과거 이력을 볼 때는 메트릭 직접 조회:
  ```bash
  oci monitoring metric-data summarize-metrics-data --compartment-id <tenancy> \
    --namespace hypenow_custom --query-text 'container_up[1m].min()' \
    --start-time <ISO8601> --end-time <ISO8601>
  ```

### 9-1. Caddy 액세스 로그 (운영 07-29~ · test 07-31~)

장애 사후 재구성의 1차 재료(어떤 요청이 언제 몇 건 들어왔나). **도메인마다 파일이 다르다** —
Caddy는 사이트 블록 단위로 로거를 붙이므로, `log` 지시어가 없는 블록의 요청은 **다른 블록의
로그에도 남지 않는다**(07-31 실측: test 도메인이 6.8MB 운영 로그에 host 0건 — 로테이션 유실이
아니라 애초에 미기록이었다. 07-30 test HikariCP 풀 고갈 조사 PR #268이 여기서 막혔다).

| 도메인 | 파일(호스트 경로 `~/deploy/logs/caddy/`) | 롤링 | 설정 위치 |
|---|---|---|---|
| `api.hypenow.io` (운영) | `access.log` | 50MiB × 5 (≈250MB) | `deploy/Caddyfile` |
| `dev-api.hypenow.io` (test) | `test-access.log` | 10MiB × 5 (<60MB) | `deploy/caddy.d/test-api.caddy` |

- 형식은 둘 다 JSON + `format filter` — **자격증명 헤더(Cookie·Authorization·X-Xsrf-Token·
  Set-Cookie)는 기록하지 않는다**. 사이트 블록을 새로 추가할 땐 이 `log` 블록을 함께 복사할 것
  (운영·test 공용 스니펫으로 묶지 않는다 — 본 Caddyfile은 main 배포로만, `caddy.d/*`는 test CD로
  따로 서버에 도달하므로 스니펫 참조는 "정의가 아직 없는 서버"에서 reload를 깨뜨린다).
- **로테이션·디스크**: Caddy 내장 `roll`이 담당(logrotate 불필요). 롤된 파일은 gzip이라 실사용은
  캡보다 작다. 두 파일 합쳐 최악 310MB — 96G 디스크(07-31 63% 사용) 기준 무의미한 증분이며
  디스크 85% 알람 대상에도 영향 없다. 실측 증가율은 운영 약 10MB/일(6.9MB/16.4h), test는 CD
  헬스체크·수동 검증이 대부분이라 그보다 훨씬 적다 — 캡 소진까지 각각 수십 일치가 남는다.
- 파일은 컨테이너가 root:600으로 만든다 → 호스트에서 읽을 때 `sudo` 필요:
  ```bash
  # test 도메인의 사건 시각 요청 재구성 (상태·소요시간·경로)
  sudo jq -r 'select(.ts > 1785400000) | [(.ts|todate), .status, (.duration|tostring), .request.method, .request.uri] | @tsv' \
    ~/deploy/logs/caddy/test-access.log
  # 도메인별 기록 여부 확인(빈 결과 = 그 블록에 log 지시어가 없다는 신호)
  sudo jq -r '.request.host' ~/deploy/logs/caddy/*.log | sort | uniq -c
  ```
- 커밋 금지 — `deploy/logs/`는 `.gitignore` 대상이다(클라이언트 IP가 담긴다).

## 10. 이사 절차 (오라클 → 아무 VPS, 목표 30분)
1. 새 Ubuntu 서버: §3 최초 기동 그대로 (rsync → setup → .env → up)
2. 데이터: `pull-backup.sh`의 최신 덤프를 새 서버에 넣고
   `cd ~/deploy && set -a && source .env && set +a` 후
   `zstdcat dump.sql.zst | docker compose exec -T postgres psql -U $DB_USER -d analysis`
   (또는 로컬 raw에서 미러 재실행 — §4)
3. DNS A레코드를 새 IP로 변경 → caddy가 인증서 자동 재발급

## 11. 수동 발굴 등록 API (크롬 익스텐션, 07-22~)
- `POST https://api.hypenow.io/crawler/api/manual-discoveries` — Caddy가 crawler의 이 경로만 공개.
  헤더 `X-Api-Token` 필요, 서버 `.env`에 `MANUAL_DISCOVERY_TOKEN`(강한 랜덤 값) 설정 후 crawler 재기동.
  토큰 미설정이면 API는 503(fail-closed). 등록된 계정은 DISCOVERED로 들어가 기존 qualify→beauty가 처리.
- 익스텐션은 별도 저장소 `hypenow-extension` — 옵션에 엔드포인트 URL·토큰을 넣어 사용.

## 12. test 스테이징 (태스크 K 07-28 개통 · 07-29 staging 브랜치 전환 — 구명 "dev 스테이징")

staging 브랜치 검증용 스택. **staging CI 성공마다** `.github/workflows/cd-test.yml`이 자동 배포한다
(`workflow_run` 트리거 — CI가 실패하면 test 배포도 없다). develop 머지는 CI만 돌고 배포하지
않는다 — **승격 흐름: develop→staging(test 배포)→main(운영 배포)**. 구조·결정 근거:
[specs/2026-07-26-dev-staging-environment-design.md](../docs/superpowers/specs/archive/2026-07-26-dev-staging-environment-design.md) ·
[specs/2026-07-29-staging-branch-test-stack-design.md](../docs/superpowers/specs/2026-07-29-staging-branch-test-stack-design.md)

- 접속: `https://dev-api.hypenow.io` (도메인은 구명 유지 — DNS 무변경. was 로그인 월 —
  test 전용 가입 코드 필요). 서버 `.env`의 `DEV_*` 변수·raw 계정 `analytics_dev`·데이터 볼륨
  `dev-pg-data`도 구명 유지(의도적 예외 — 리네임 비용 대비 이득 없음, 볼륨은 데이터 유실 방지).
- test 어드민(analytics): `ssh -L 8083:localhost:8083 ubuntu@<IP>` 후 http://localhost:8083/ui
- test analysis DB: `ssh -L 5434:localhost:5434 ubuntu@<IP>` (계정은 서버 `.env`의 `DEV_DB_*`)
- 배치는 **운영 인스턴스 동거** — test 5종(was·analytics·postgres·redis·monitoring)의 정의는 별도 파일 **`deploy/compose.test.yaml`**에 있고
  운영 `compose.yaml`에 겹쳐 쓴다(`-f compose.yaml -f compose.test.yaml --profile test`).
  파일을 나눈 이유: **test CD는 이 test 파일만 서버로 보낸다** — 운영 서비스 정의는 main 배포로만
  서버에 도달하므로, staging의 운영 정의 변경이 test 배포로 먼저 발효되거나 `depends_on` 연쇄로
  운영 컨테이너가 재생성되는 사고가 구조적으로 불가능하다(caddy.d 분리와 같은 원리).
  `profiles: ["test"]`도 유지 — `--profile test` 없이는 뜨지도 멈추지도 않는다(이중 가드).
  mem_limit로 상한을 걸어 운영 메모리를 침식하지 않는다.
- **네트워크 격리 (07-29)** — `compose.yaml`이 브리지 `prod`·`test`를 선언하고 test 서비스는 `test`에만
  소속: test 컨테이너에서 운영 postgres·analytics·crawler로 가는 경로가 커널(iptables) 수준에서
  차단된다. 양쪽 소속은 의도된 접점 둘뿐 — `caddy`(도메인 분기)·`postgres-raw`(raw 읽기).
  compose에 서비스를 추가할 땐 **`networks:` 명시 필수**(커스텀 네트워크 체제라 기본 네트워크가
  없다 — 누락 시 통신 고립). 예외는 `test-monitoring-net`(compose.test.yaml 선언) —
  test-was↔test-monitoring 전용 소네트워크로 test 안에서만 닫혀 있어 격리를 깨지 않는다.
- raw는 운영 `postgres-raw` **공유** — test 계정 `analytics_dev`는 crawler 테이블(public) 읽기 전용,
  뷰·캐시는 자기 소유 `analytics_dev` 스키마에 치환 설치(`rewrite-views-dev-schema.sh`).
  운영 `analytics` 스키마엔 USAGE도 없다 — 치환 누락은 권한 오류로 즉사(fail-closed).
- 스키마 선택 방식: analytics의 조회 SQL은 **뷰 이름을 무접두어로** 쓰고, raw DataSource의
  `connection-init-sql`(`SET search_path TO ${analytics.raw-schema:analytics}, public`)이 스키마를
  결정한다. test만 compose env `ANALYTICS_RAW_SCHEMA=analytics_dev`로 오버라이드 — 운영 동작 불변.
- **test 라우팅은 본 `Caddyfile`이 아니라 `deploy/caddy.d/test-api.caddy`**에 있고, 본 Caddyfile은
  `import /etc/caddy/caddy.d/*.caddy` 한 줄만 갖는다. test CD는 이 test 파일만 서버로 보내고
  reload하므로 **운영 라우팅은 main 배포로만 바뀐다**(staging에만 있는 운영 라우팅 변경이 먼저
  발효되는 뒷문 차단).
- **액세스 로그는 `~/deploy/logs/caddy/test-access.log`**(07-31~, 운영과 별도 파일) — §9-1 참조.
  test 스택 장애를 사후 조사할 땐 여기가 첫 삽이다.

### 최초 개통 체크리스트 (1회, 사용자 실행 — **순서가 중요**. 07-28 실행 완료 — 기록 보존)

1. **DNS A 레코드**: `dev-api.hypenow.io` → 서버 공인 IP (운영과 동일 IP, TTL 300)
2. **서버 `~/deploy/.env`에 추가** (값 생성: `openssl rand -base64 24`):
   `DEV_DB_USER=devapp` · `DEV_DB_PASSWORD=<생성>` · `DEV_RAW_DB_PASSWORD=<생성>` ·
   `DEV_CODES_API_KEY=<생성>` (미설정 시 가입 코드 적재 API는 503 fail-closed) ·
   `DEV_MONITORING_DB_PASSWORD=<생성>` (6번에서 만들 test monitoring 계정 비밀번호와 일치시킬 것 —
   변수명은 `DEV_*` 구명 유지)
3. **`~/deploy/caddy.d` 디렉토리를 ubuntu 소유로 먼저 만든다 — main 배포보다 앞서야 한다**:
   ```bash
   ssh ubuntu@<IP> 'mkdir -p ~/deploy/caddy.d && ls -ld ~/deploy/caddy.d'   # 소유자 ubuntu 확인
   ```
   순서가 뒤집히면 4번 운영 배포의 caddy 컨테이너 재생성이 이 디렉토리를 **root 소유로 생성**해,
   이후 test CD의 `scp test-api.caddy`가 Permission denied로 실패한다.
4. **main 머지 1회(운영 배포)** — caddy의 `caddy.d` 볼륨 마운트와 본 Caddyfile의 `import`
   라인이 이 배포로 서버에 반영된다(compose 정의 변경이라 caddy가 자동 재생성). **그 전까지 test
   라우팅은 살아나지 않는다** — 이 배포보다 test CD가 먼저 돌면 마지막 헬스체크 스텝만 실패한다
   (무해 — 4번 완료 후 워크플로 재실행하면 된다).
5. **staging에 머지**(또는 `CD test` 재실행) → `CI` 성공 → `CD test` 전 스텝 성공 확인
   (계정 준비·뷰 치환 적용·잔존 참조 검사·pull/재기동·caddy reload·`/health`)
6. **test monitoring DB·계정 생성** (test-postgres가 뜬 5번 이후 1회 — 운영 §13의 test 판):
   ```bash
   # 서버에서 (-c를 나눠 쓴다 — 한 -c에 여러 문장을 넣으면 암묵 트랜잭션이라 CREATE DATABASE가 거부된다)
   docker exec -it deploy-test-postgres-1 psql -U <DEV_DB_USER> -d analysis \
     -c "CREATE ROLE monitoring LOGIN PASSWORD '<실값>'" \
     -c "CREATE ROLE was_reader LOGIN PASSWORD '<실값>'" \
     -c "CREATE DATABASE monitoring OWNER monitoring"
   # 확인 — 이 DB가 생기기 전까지는 Restarting/Exited가 보인다
   cd ~/deploy && docker compose -f compose.yaml -f compose.test.yaml --profile test ps -a test-monitoring
   ```
   비밀번호는 2번의 `DEV_MONITORING_DB_PASSWORD`와 같은 값(env 변수명은 `DEV_*` 구명 유지).
   **이 DB가 생기기 전까지 `deploy-test-monitoring-1`은 접속 실패로 재기동을 반복**한다
   (무해 — 생성 후 스스로 붙는다). 운영과 다른 postgres 클러스터라 계정 이름이 겹쳐도 무관하다.
7. **test 가입 코드 시드** — 둘 중 하나:
   ```bash
   # (a) API — 토큰은 .env의 DEV_CODES_API_KEY
   curl -fsS -X POST https://dev-api.hypenow.io/admin/signup-codes \
     -H "Authorization: Bearer $DEV_CODES_API_KEY" -H 'Content-Type: application/json' \
     -d '{"codes":["DEV-AAAA","DEV-BBBB"]}'
   # (b) 스크립트로 INSERT SQL 생성 → test analysis DB에 직접 (맥에서)
   deploy/scripts/generate-signup-codes.sh DEV 10 | \
     ssh ubuntu@<IP> 'docker exec -i deploy-test-postgres-1 psql -U <DEV_DB_USER> -d analysis'
   ```
8. **검증**: `https://dev-api.hypenow.io`에서 가입·로그인 → `/v1/contents` 응답 확인.
   이메일 인증 코드는 Resend 미설정(로깅 폴백)이라
   `ssh ubuntu@<IP> 'docker logs deploy-test-was-1 | grep 인증'`에서 확인.

### 일상 사용

- 기능 확인 절차: PR→develop 머지(CI만 — 배포 없음) → **develop→staging 머지** → CI 성공 →
  cd-test 완료 대기 → 어드민(8083)에서 미러 수동 실행 → dev-api로 API 응답 확인 → 이상 없으면
  **staging→main 머지(운영 배포)**. **승격 = 머지 그 자체** — 코드·설정 수정 0, 같은 아티팩트에
  배포 설정만 다르다.
- test 스케줄은 전부 off(`ANALYTICS_SCHEDULE_ENABLED: "false"`) — 미러·분석·LLM 잡은 어드민 수동
  트리거만. LLM은 운영 자격증명 공유라 소량으로(쿼터·비용 공유 인지). 이미지 아카이브는
  `ANALYTICS_IMAGE_PAR_URL: ""`이라 실행 시 fail-fast(운영 버킷 오염 방지).
  test-monitoring도 같은 원칙 — 스윕 크론을 아예 안 넣어 기본값 `"-"`(off)이고, 등록 시 동기 수집만
  돈다. Hiker 키는 운영 공유라 등록 테스트는 소량으로. test-was는 `http://test-monitoring:8083`으로
  호출한다(전용 네트워크 `test-monitoring-net` — 운영 `monitoring`은 test에서 해석 자체가 안 된다).
- 뷰만 다시 적용(맥에서 — cd-test와 같은 절차):
  ```bash
  cat analytics/views/*.sql | deploy/scripts/rewrite-views-dev-schema.sh | \
    ssh ubuntu@<IP> 'docker exec -i deploy-postgres-raw-1 psql -U analytics_dev -d crawler -v ON_ERROR_STOP=1 -q'
  ```
- 스냅샷 캐시 refresh(필요 시):
  `ssh ubuntu@<IP> 'docker exec -i deploy-postgres-raw-1 psql -U analytics_dev -d crawler -c "SELECT analytics_dev.refresh_snapshot_cache();"'`
- test 스택 정지:
  `cd ~/deploy && docker compose -f compose.yaml -f compose.test.yaml --profile test stop test-monitoring test-was test-analytics test-postgres test-redis`
  (운영 무영향 — 프로파일 밖 서비스는 건드리지 않는다). 재기동은 같은 `-f`·프로파일 인자에 `up -d`.
- 컨테이너 이름은 compose 프로젝트명(`~/deploy` 디렉토리) 기준: `deploy-test-was-1` ·
  `deploy-test-analytics-1` · `deploy-test-postgres-1` · `deploy-test-redis-1` ·
  `deploy-test-monitoring-1`. 모니터링 `SERVICES` 목록(§9)에는 test를 넣지
  않는다 — 수동 정지가 정상 상태라 알람이 오탐이 된다.

### 주의 (함정 3건)

- **staging 머지는 실행 중인 test 잡을 끊는다.** cd-test가 test 컨테이너를 새 이미지로 재생성하므로,
  미러·분석 잡이 돌던 중이면 그대로 중단된다(07-28 첫 미러가 이렇게 유실 — 재실행으로 해소).
  긴 잡을 돌릴 땐 staging 머지 타이밍을 피하거나, 끊겼으면 어드민에서 재트리거하면 된다.
- **orphan 경고는 정상 — 운영 파일 단독 경로에서 `--remove-orphans` 금지.** test 기동 후 운영
  경로(`docker compose up -d`, deploy.sh — compose.yaml 단독)는 매번
  `Found orphan containers (deploy-test-was-1 …)` 경고를 낸다 — test 서비스가 `compose.test.yaml`에
  있어 운영 파일 단독 실행엔 "고아"로 보일 뿐이다. 경고문이 권하는 `--remove-orphans`를 붙이면
  **test 컨테이너 전량(현재 5종)이 제거된다.** 절대 붙이지 말 것. (cd-test의 up에 붙은 `--remove-orphans`는
  다른 얘기 — 전체 파일 세트 기준이라 고아 = 정의가 사라진 컨테이너뿐이며, 07-29 전환 때 구
  `deploy-dev-*` 3종을 이걸로 정리했다.)
- **운영 배포가 cancelled로 끝났으면 재실행.** 운영 CD와 test CD는 같은 concurrency 그룹
  (`deploy-server`)으로 직렬화되는데, GitHub는 그룹당 대기 1개만 유지한다 — test 배포 실행 중에
  운영 배포가 대기하다가 새 test 배포가 또 큐잉되면 **대기 중이던 운영 배포가 조용히 취소**될 수
  있다. staging→main 머지 후엔 Actions에서 CD 런이 success로 끝났는지 확인하고, cancelled면
  Re-run으로 재실행한다(07-20 "배포가 조용히 안 나감" 계열 방지). **`cancel-in-progress: false`는
  "취소 안 됨"을 보장하지 않는다** — 이 설정은 "실행 중인 잡을 안 죽인다"는 뜻일 뿐, 대기 슬롯이
  1개뿐이라는 GitHub Actions concurrency 제약 자체는 못 바꾼다. 07-30 staging→main 승격(PR #247)
  때 이 패턴이 실제로 재현돼 `gh run rerun`으로 재실행·success 확인함(DECISIONS.md 07-30 참조).

## 13. monitoring 모듈 개통 (1회 ops — 첫 CD 배포 전에)

시딩 캠페인 모니터링 컨테이너(사설 `monitoring` DB, 호스트 포트 미노출). **아래 1·2번을
먼저 끝내지 않으면 CD가 실패한다** — compose 동기화 스텝이 `.env`에 없는 `${VAR}` 참조를
발견하면 배포를 중단시킨다(§5).

1. 서버 postgres 컨테이너에 DB·계정 생성 (`db/init/02-create-monitoring-db.sql`과 동일, 비밀번호는 실값):
   ```bash
   # -c를 나눠 쓴다 — 한 -c에 여러 문장을 넣으면 암묵 트랜잭션이라 CREATE DATABASE가 거부된다
   docker exec -it deploy-postgres-1 psql -U <DB_USER> -d postgres \
     -c "CREATE ROLE monitoring LOGIN PASSWORD '<실값>'" \
     -c "CREATE ROLE was_reader LOGIN PASSWORD '<실값>'" \
     -c "CREATE DATABASE monitoring OWNER monitoring"
   ```
   (`was_reader`에는 접속 권한만 — 객체 GRANT는 monitoring Flyway가 소유자로서 부여한다)
2. `~/deploy/.env`에 추가: `MONITORING_DB_USER`, `MONITORING_DB_PASSWORD`
   — `.env.example`에 항목이 있다. 1번의 실값과 일치시킬 것.
   **⚠ 머지 전 필수 확인 — `HIKER_API_KEY` 실값이 서버 `~/deploy/.env`에 있어야 한다.**
   compose는 기본값 없는 `${HIKER_API_KEY}`를 참조하므로(fail loud), 값이 없으면 env 게이트가
   monitoring뿐 아니라 **기존 3종(was·crawler·analytics) 배포까지 통째로 차단**한다.
   ```bash
   ssh ubuntu@<IP> 'grep -c "^HIKER_API_KEY=." ~/deploy/.env'   # 1이어야 함
   ```
3. **was V16(모니터링 v3 스키마) 포함 배포라면 머지 전에 확인 필수**:
   ```bash
   docker exec -it deploy-postgres-1 psql -U <DB_USER> -d analysis \
     -c "SELECT count(*) FROM app.monitoring_campaigns"
   ```
   0이어야 한다 — V16이 이 테이블을 `DROP`·v3 캠페인 테이블로 재정의한다(`allow-destructive`,
   전제는 "기능 미개통·운영 0행"). 0이 아니면 머지를 중단하고 먼저 잔여 행을 파악할 것
   (§13-5-1 절차는 이 배포 전에만 유효 — 아래 참조).
   staging→main 머지로 배포 → `docker compose ps`에서 monitoring `(healthy)` 확인
   (CD의 "monitoring 헬스 확인" 스텝이 같은 판정을 자동 수행 — 호스트 포트가 없어 외부 curl은 불가)
4. 서버 스크립트 갱신 — `backup.sh`는 07-30부터 CD의 "compose·Caddyfile·롤링 스크립트 동기화"
   스텝이 `rollout.sh`와 함께 매 배포마다 자동으로 올린다(cd.yml). **`post-container-metrics.py`는
   여전히 CD가 배포하지 않으므로** 컨테이너 다운 알람 대상 `SERVICES`에 monitoring을 추가했다면(§9)
   수동으로 올릴 것:
   ```bash
   rsync -av deploy/scripts/post-container-metrics.py ubuntu@<IP>:~/deploy/scripts/
   ```
   (07-30 이전엔 `backup.sh`도 CD가 안 올려 레포↔서버가 반대 방향으로 갈라졌었다 — 레포엔
   monitoring 덤프 블록이 있는데 서버엔 없어 monitoring이 운영 백업에서 조용히 누락되고,
   반대로 서버가 먼저 전환한 B2 오프사이트는 레포에 반영이 안 되는 상태였다. `backup.sh`
   자동 동기화로 이 드리프트 재발을 막는다.)
5. **알람 개통 (07-30~, 별도 단계 — 기본 비활성이라 서두르지 않아도 된다)**
   1. **사전 확인 — user_id 없는 기존 캠페인 모수 파악**. ⚠ **이 절차는 was V16(모니터링 v3
      스키마) 배포 전에만 가능** — V16이 구 매핑 테이블 `app.monitoring_campaigns`를 v3 캠페인
      테이블로 `DROP`·재정의한다(위 3번 확인 스텝 참조). V16 이후에는 아래 역추적 근거 자체가
      없으므로 이 절차는 생략하고 해당 기존 캠페인은 "대상 외"로 기록한다:
      ```bash
      docker exec -it deploy-postgres-1 psql -U <DB_USER> -d monitoring \
        -c "SELECT count(*) FROM target WHERE user_id IS NULL AND status IN ('WATCHING','TRACKING')"
      ```
      0이 아니면 (V16 배포 전이라면) `app.monitoring_campaigns`(구 was 매핑 테이블)에서 해당
      target_id의 user_id 매핑 유무를 확인한다 — **있으면** 백필 UPDATE 런북(dry-run → 승인 →
      실행, `target.user_id`를 매핑값으로 채움)을 작성해 실행하고, **없으면** "해당 기존 캠페인은
      알람 대상 외"를 명시적 결정으로 기록해 둔다(나중에 "알람이 안 온다"가 버그로 재조사되지
      않게 — 수신자 미상 행은 `AlarmRecorder.record()`가 조용히 스킵한다).
   2. analysis DB에 읽기 전용 롤 생성 + 두 객체만 GRANT (계약 v2 §6):
      ```bash
      docker exec -it deploy-postgres-1 psql -U <DB_USER> -d analysis \
        -c "CREATE ROLE alarm_reader LOGIN PASSWORD '<실값>'" \
        -c "GRANT USAGE ON SCHEMA app TO alarm_reader" \
        -c "GRANT SELECT (id, email) ON app.users TO alarm_reader" \
        -c "GRANT SELECT ON app.monitoring_email_opt_outs TO alarm_reader"
      ```
      (`app.monitoring_email_opt_outs`는 was Flyway V15가 만든다 — **was 배포 후**에 실행할 것)
   3. `~/deploy/.env`에 `ALARM_READER_PASSWORD`, `RESEND_API_KEY` 실값 등록
      (`RESEND_API_KEY`는 was가 이미 쓰던 값과 같은 키를 공유한다)
   4. 발송 크론 켜기 — `deploy/compose.yaml`의 `MONITORING_ALARM_DISPATCH_CRON`을 `"0 */5 * * * *"`로
      바꿔 커밋·배포(서버에서 직접 고친 값은 다음 CD가 레포 compose로 덮는다 — 스윕 크론과 같은 규칙)
   5. 검증: `docker logs deploy-monitoring-1 | grep -i resend` — "Resend 메일 발송 활성"이면 실발송 모드,
      "RESEND_API_KEY 미설정"이면 로깅 폴백(개통 실패)
   6. **수신자 허용목록 안전판** (`monitoring.alarm.allowed-recipients`, env
      `MONITORING_ALARM_ALLOWED_RECIPIENTS` — 콤마 목록, 대소문자·공백 무시). 비어 있으면(운영
      기본) 무제한 — 위 4번처럼 그냥 켜면 이 값을 건드릴 필요 없다. 허용목록이 있으면 목록 밖
      수신자는 발송 없이 `SKIPPED_NO_RECIPIENT`로 종결(재시도 없음) — 새 상태값을 만들지 않고
      기존 종결 상태를 재사용한다(`AlarmDispatchJob`).
   7. **test 스택 임시 개통(검증용, 07-30~)** — test `analysis` DB는 실사용자 이메일을 그대로
      담고 있어, 4번처럼 크론만 켜면 실사람에게 메일이 나간다. 반드시 아래 3키를 함께 넣을 것
      (`deploy/compose.test.yaml`이 이미 배선돼 있다 — env만 채우면 된다):
      - `DEV_ALARM_DISPATCH_CRON="0 */5 * * * *"` (기본 `"-"`=비활성 — 검증 끝나면 즉시 원복)
      - `DEV_ALARM_ALLOWED_RECIPIENTS=<검증용 이메일>` (콤마 목록 — 승인된 주소만)
      - `DEV_ALARM_READER_PASSWORD` (test-postgres의 `analysis` DB에도 `alarm_reader` 롤을
        2번과 동일하게 GRANT해 둘 것 — 운영과 test는 별도 DB라 롤도 따로 만든다)
      ⚠️ **순서 주의 — 허용목록은 fail-OPEN이다.** 비워 둔 채 크론만 켜면 아무것도 막히지 않고
      test DB의 실사용자 전원에게 실메일이 나간다(6번의 "비어 있으면 무제한"이 test에도 그대로
      적용된다). 반드시 `DEV_ALARM_ALLOWED_RECIPIENTS`를 먼저 채운 뒤 크론을 켤 것.
      검증이 끝나면 `DEV_ALARM_DISPATCH_CRON`을 비우거나 삭제해 재배포 — 계속 켜 두지 않는다.
6. **was v3 조회 개통 (was V16 배포 후, was 서비스 environment의 `MONITORING_*` 4키 배선과 짝)**
   — was가 monitoring DB를 직접 SELECT해 목록·상태·후보 등을 조립한다(계약 §1). 기본
   비활성이라 서두르지 않아도 된다.
   1. `~/deploy/.env`에 `MONITORING_ENABLED=true` + `WAS_READER_PASSWORD`(1번에서 만든 `was_reader`
      실값과 일치) 등록 — `.env.example`에 항목이 있다.
   2. was 재배포(compose 정의 변경 없이 값만 바뀌었다면 `docker compose up -d --no-deps was`로 충분,
      아니면 다음 CD로).
   3. **첫 스윕을 1회 수동으로 성공시켜 `sweep_run`을 시드한다** — `sweep_run`은 `DailySweepJob`
      전체 실행(`SweepScheduler`)에서만 생성되고, 등록 시 동기 수집(`CollectService`)은 이 대장에
      쓰지 않는다. 그 전까지는 was의 목록·추이 스냅샷 조회가 전부 빈 배열을 반환한다(최종 리뷰
      I2). 어드민 UI가 없어 수동 트리거 엔드포인트도 없으므로, 서버 `~/deploy/compose.yaml`의
      `MONITORING_SCHEDULE_SWEEP_CRON` 값을 다음 1~2분 내로 잠깐 당겨 `docker compose up -d monitoring`으로
      재기동 → 1회 태우고 확인 → 원래 값(KST 02:00, 아래 "접근 통제·디버깅" 참조)으로 되돌려
      다시 재기동한다(서버에서 직접 고친 값은 다음 CD가 레포 compose로 덮는 것과 같은 관용구 —
      되돌리지 않아도 다음 CD가 덮지만, 그 사이 매분 스윕이 도는 걸 막으려면 직접 되돌릴 것).
   4. was 목록 응답이 채워지는지 확인 후 프론트 연동 시작.

### 접근 통제·디버깅

- 명령 API는 토큰이 없다 — 전용 네트워크 `monitoring-net`에 **was와 monitoring만** 소속시켜
  통제한다. 이 네트워크 밖 컨테이너(test 스택 포함)는 `monitoring` 호스트명 해석부터 실패한다.
- 호스트 포트를 열지 않는다(어드민 UI 없음 + 호스트 8083은 dev-analytics 터널이 점유).
  수동 호출은 같은 네트워크에 임시 컨테이너를 붙여서:
  ```bash
  docker run --rm --network deploy_monitoring-net curlimages/curl -s http://monitoring:8083/…
  ```
  (was·monitoring 이미지엔 curl이 없어 `docker exec deploy-was-1 curl`은 안 된다)
- 일일 스윕은 컨테이너 env `MONITORING_SCHEDULE_SWEEP_CRON`(UTC 17:00 = KST 02:00).
  임시 중단은 값을 `"-"`로 두고 `docker compose up -d monitoring` — 서버에서 직접 고친 값은
  다음 CD 배포가 레포 compose로 덮는다(crawler 스케줄과 같은 규칙, §4-2).
- 알람 발송은 컨테이너 env `MONITORING_ALARM_DISPATCH_CRON`(기본 `"-"`=비활성, 운영 5분 틱).
  임시 중단은 `"-"`로 두고 재기동 — 대장(`alarm_event`)에 PENDING으로 쌓였다가 다시 켜면 그대로 나간다
  (워터마크가 없어 중단 구간 유실이 없다).
- 백업: `backup.sh`가 analysis와 같은 관용구로 매일 덤프 —
  서버 `~/backups/monitoring-*.sql.zst` 3일 + B2 `hypenow-backups/monitoring/` 7일 롤링(§6).

## 14. Grafana 대시보드 (07-31~, 08-18 6탭 개편 → 브랜드 폴더 분리)

운영 상태를 보는 Grafana 스택. 08-18 개편으로 "데이터소스 축 3장"에서 **목적 축 6탭**으로
재편한 뒤, 같은 날 브랜드 탭을 별도 폴더로 떼어내 지금은 **HypeNow 5탭 + 브랜드 모니터링 폴더
3장**(총 8장) 체제다(설계: `docs/superpowers/specs/2026-08-18-grafana-dashboard-redesign-design.md`,
폴더 분리: `docs/superpowers/specs/2026-08-18-grafana-brand-folder-design.md`).
**레포에서 대시보드 JSON을 지우거나 옮기면 서버 파일은 CD가 지우지 않는다**(cd.yml의 프로비저닝
동기화가 `scp -r` 추가 전용) — 잔존 파일은 수동으로 정리해야 한다(§14-2-2 ④).
**Caddy 라우트가 없다** — 호스트 루프백(`127.0.0.1:3000`)에만 열고 analytics 어드민(§8)·crawler
어드민(§10)과 같은 **SSH 터널 방식**으로만 접근한다. 정의는 `deploy/compose.yaml`(grafana 서비스) +
`deploy/grafana/provisioning/`(데이터소스·대시보드 JSON·알림 규칙, 전부 파일 기반 자동 프로비저닝).
대시보드 JSON을 고치기 전에 로컬 하니스(`deploy/grafana/dev/README.md`)로 그려 볼 것.

> **왜 공개 도메인이 아닌가**(07-31 결정): 이 대시보드의 핵심 가치인 "미완료 등록 30분 초과"
> 알림은 Grafana가 디스코드로 **직접** 발송하므로 터널이 닫혀 있어도 동작한다. 즉 도메인을
> 붙여 얻는 건 북마크 편의뿐인데, Grafana는 인증 우회 계열 CVE가 주기적으로 나오는
> 소프트웨어라 공개 표면을 늘리는 비용이 그보다 크다. DNS·인증서·Caddy 블록이 전부 불필요해져
> 개통 절차도 `.env` 3개 + 롤 생성으로 줄었다. **트레이드오프**: 휴대폰에서 대시보드를 볼 수
> 없다(급한 신호는 디스코드 알림으로 오므로 실질 손해는 작다고 판단).

### 14-1. 접속

```bash
ssh -L 3001:localhost:3000 ubuntu@<IP>    # 터미널 1: 터널 유지
```

- 이후 브라우저에서 `http://localhost:3001` — 로그인은 `GF_SECURITY_ADMIN_USER`/`GF_SECURITY_ADMIN_PASSWORD`
  (익명 접속·회원가입 둘 다 꺼둠, `GF_AUTH_ANONYMOUS_ENABLED=false`/`GF_USERS_ALLOW_SIGN_UP=false`).
- 로컬 3000이 아니라 **3001**인 이유: 로컬 3000은 Next.js 개발 서버가 쓴다. compose의
  `GF_SERVER_ROOT_URL`도 `http://localhost:3001`로 맞춰져 있다 — 다른 포트로 터널을 열면
  로그인 리다이렉트가 어긋나므로 둘을 함께 바꿀 것.
- 폴더 **HypeNow 5탭**(08-18 개편 — 구 "서비스 현황"·"에러"·"API 성능" 3장은 삭제·흡수됨):
  - **홈**(`hypenow-home`) — 신호등 13타일. 행 순서가 곧 읽는 순서: 지금 아픈가(API 5xx·ERROR
    급증·Hiker 402·IG 401) → 돌고 있나(스윕·콜·미러·등록·알림) → 여유가 있나(호스트·JVM·커넥션 풀).
    각 타일 클릭 시 해당 상세 탭으로 이동.
  - **경쟁사 모니터링**(`hypenow-competitor`) — `sweep_run` 축(캠페인 스윕)·타깃·알림 발송
    실패 + 등록 처리(멈춘 등록·결과 미확정).
  - **탐색**(`hypenow-discovery`) — 미러 신선도·랭킹 산출 규모·저장 활동.
  - **Hiker**(`hypenow-hiker`) — 비용(일별·브랜드별 콜)과 외부 의존 실패(402·401·상태코드).
    Hiker는 4xx도 과금이라 비용 축과 실패 축이 보완 관계다.
  - **인프라**(`hypenow-infra`) — 호스트·컨테이너·JVM 지표 + 전 서비스 에러 로그(§16의 조회축 흡수).
- **브랜드 모니터링**(별도 폴더, 08-18 분리 — 3장): `[브랜드] 운영 건강`(`hypenow-brand`,
  스윕 신선도·오늘 성공/소요·처리 간격 — 브랜드 스윕은 런 단위 기록이 없어
  (`brand_account.last_swept_at/on`뿐) 소요·간격은 당일 유도 근사) ·
  `[브랜드] 수집 현황`(`hypenow-brand-collection`, 태그 게시물·해시태그 감지·enrich·백필) ·
  `[브랜드] 광고 표기`(`hypenow-brand-ad`, 판정 분포·경로·추이·미표기 목록)
- 건강 stat은 fail-loud(`noValue`·null 매핑 빨강 + 임계), 사용량 stat은 중립(색 없음) —
  수정 시 이 규약 유지. 상태 스냅샷 패널은 전역 시간 필터를 의도적으로 안 탄다.

### 14-2. `grafana_reader` 롤 생성 (1회, 사용자 수동 — Flyway 아님)

**조사 결론**: 이 레포에서 읽기 전용 롤(`was_reader`·`alarm_reader`·`monitoring` 소유 롤 자체)은
전부 **수동 런북**으로 생성한다(§13-1·§13-5-2, `db/init/02-create-monitoring-db.sql`). Flyway가
하는 일은 그렇게 만들어진 롤에 **객체 GRANT를 부여**하는 것뿐 — `CREATE ROLE`은 어느 앱의
Flyway 마이그레이션에도 없다. 이유: `CREATE ROLE`은 `CREATEROLE` 권한(사실상 슈퍼유저 — 여기서는
postgres 컨테이너의 `POSTGRES_USER`인 `DB_USER`)이 있어야 하는데, 이를 앱 Flyway 이력에 넣으면
"서버 1회 셋업"과 "앱 코드 버전"이라는 서로 다른 수명주기가 섞인다. `grafana_reader`도 같은
이유로 같은 방식(수동 런북)을 따른다 — 임의로 새 방식을 만들지 않았다.

```bash
# 서버에서 (-c를 나눠 쓴다 — 한 -c에 여러 문장을 넣으면 암묵 트랜잭션이라 거부된다)
docker exec -it deploy-postgres-1 psql -U <DB_USER> -d analysis \
  -c "CREATE ROLE grafana_reader LOGIN PASSWORD '<실값>'" \
  -c "GRANT USAGE ON SCHEMA app TO grafana_reader" \
  -c "GRANT SELECT (id, user_id, requested_at, completed_at) ON app.monitoring_registrations TO grafana_reader" \
  -c "GRANT SELECT (registration_id, seq, input, kind, result, reason_code) ON app.monitoring_registration_entries TO grafana_reader" \
  -c "GRANT SELECT (created_at) ON app.users TO grafana_reader" \
  -c "GRANT SELECT (outcome, created_at) ON app.signup_events TO grafana_reader" \
  -c "GRANT SELECT (primary_id) ON app.spring_session TO grafana_reader"
```

- **시스템 경계 준수**: `app` 스키마 외 접근 없음(raw DB·analysis 결과 스키마 GRANT 전혀 없음).
  컬럼도 대시보드 6패널이 실제 쓰는 것만 — `app.users.email`·`password_hash`,
  `app.signup_events.email`·`ip`·`detail`(PII 가능성) 등은 제외(alarm_reader가 `id, email`만
  받은 것과 같은 최소권한 원칙, 다만 이메일 자체도 대시보드엔 불필요해 더 좁혔다).
  `spring_session`은 `count(*)`만 필요해 `primary_id` 한 컬럼만 부여(컬럼 단위 GRANT에서
  `count(*)`가 동작하려면 최소 한 컬럼의 SELECT 권한이 있어야 한다).
- 비밀번호는 `~/deploy/.env`의 `GRAFANA_READER_PASSWORD`와 일치시킬 것.

#### 14-2-1. 추가 GRANT (08-02 대시보드 개편 — 🗄 **08-18 개편으로 대체됨, 실행하지 말 것**)

> 이 절의 대상 패널(가입 코드·도입 문의·모니터링 항목·다이제스트·저장 활동)은 08-18 6탭
> 개편에서 삭제됐다. 살아남은 필요분(`saved_influencers`·`saved_contents`)은 아래 **14-2-2**에
> 포함돼 있으므로 이 절은 실행하지 않는다(이미 실행했어도 무해 — 방치된 GRANT일 뿐).
> 가입 코드 잔여 타일이 나중에 부활하면 그때 `signup_codes` 부분만 되살린다.

08-02 대시보드 개편으로 패널 5개가 새 테이블을 조회한다. 아래 GRANT는 **작성만 해두고 실행하지
않았다** — 다음 서버 접속 시 관리자 계정으로 실행할 것. 컬럼 단위 최소권한 원칙 유지, PII 컬럼
(`app.inquiries.name`·`email`·`organization`·`message`)은 절대 포함하지 않는다.

```bash
docker exec -it deploy-postgres-1 psql -U <DB_USER> -d analysis \
  -c "GRANT SELECT (is_sent, used_at) ON app.signup_codes TO grafana_reader" \
  -c "GRANT SELECT (mode, canceled_at) ON app.monitoring_items TO grafana_reader" \
  -c "GRANT SELECT (created_at) ON app.saved_influencers TO grafana_reader" \
  -c "GRANT SELECT (created_at) ON app.saved_contents TO grafana_reader" \
  -c "GRANT SELECT (created_at, user_type) ON app.inquiries TO grafana_reader" \
  -c "GRANT SELECT (created_at, read_at) ON app.monitoring_digests TO grafana_reader"
```

- `app.signup_codes` — 가입 코드 소진 현황 Stat. `code`는 조회하지 않는다(값 자체는 필요 없고
  발급·발송·사용 여부만 집계).
- `app.monitoring_items` — 모니터링 추적 항목 현황 Table. mode(url/account)별 활성·취소 집계.
- `app.saved_influencers`·`app.saved_contents` — 저장 활동 추이. `created_at`만 필요(집계만, 어떤
  유저가 무엇을 저장했는지는 조회하지 않는다).
- `app.inquiries` — 도입 문의(일별). `user_type`·`created_at`만 — PII 4컬럼(`name`·`email`·
  `organization`·`message`)은 GRANT하지 않는다.
- `app.monitoring_digests` — 다이제스트 발송·읽음(일별). `items`(jsonb, 다이제스트 본문)는
  조회하지 않는다.

#### 14-2-2. 6탭 개편 + 브랜드 폴더 GRANT 런북 (08-18, ⚠️ **main 배포 전 서버 실행 필수 — 아직 미적용**)

> **실질 기한은 staging 승격이 아니라 main 배포 직전이다.** 그라파나 프로비저닝은 운영 CD로만
> 서버에 닿는다 — `compose.test.yaml`에 grafana 서비스가 없고 cd-test.yml은 `provisioning/`을
> scp하지 않는다. 즉 **staging에서는 이 PR의 대시보드 변화를 확인할 수 없다**(대시보드 육안
> 검증의 정본은 로컬 하니스 `deploy/grafana/dev/`). GRANT 자체는 운영 DB 대상이라 미리 해도 무해.

08-18 6탭 개편으로 패널이 **monitoring DB**(신설 데이터소스 `hypenow-monitoring-pg`)와
**analysis DB의 public 스키마**(분석 미러 `landing_stats`·`accounts`·`contents`)를 새로 조회한다.
아래를 실행하기 전까지 운영에서 **브랜드 폴더 3장·경쟁사·Hiker 탭 전체와 홈·탐색의 DB 타일이
권한 오류로 빈다**. 컬럼 목록은 최종 대시보드 JSON의 rawSql에서 기계 추출로 검산했다(2026-08-18) — 패널이
안 쓰는 컬럼은 부여하지 않는다(§14-2 최소권한 원칙). GRANT는 멱등이라 재실행 무해.
08-18 브랜드 폴더 분리로 브랜드 3장이 `brand_tagged_post`·`brand_hashtag_post`·`brand_post_meta`와
`brand_account.collection_months`를 추가 조회한다(스펙: `2026-08-18-grafana-brand-folder-design.md`) —
아래 ② 블록에 이미 반영돼 있다.

```bash
# ① 롤 전역 설정 — 대시보드 쿼리가 운영 쿼리를 밀어내지 않게 문장 타임아웃(클러스터 전역, 두 DB 공통)
docker exec -it deploy-postgres-1 psql -U <DB_USER> -d analysis \
  -c "ALTER ROLE grafana_reader SET statement_timeout = '5s'"
```

```bash
# ② monitoring DB — 접속권 + 대시보드가 읽는 9테이블(컬럼 단위).
#    객체 소유자는 monitoring 롤이지만 슈퍼유저(<DB_USER>)가 GRANT 가능. raw 스키마는 GRANT 없음(fail-closed).
#    brand_account.id는 브랜드 폴더 3장이 아니라 Hiker 탭 "브랜드별 상위 콜 7일"이 쓴다
#    (LEFT JOIN brand_account a ON a.id = c.brand_id) — json-brand/만 추출하면 미사용으로 보이니 주의.
docker exec -it deploy-postgres-1 psql -U <DB_USER> -d monitoring \
  -c "GRANT CONNECT ON DATABASE monitoring TO grafana_reader" \
  -c "GRANT USAGE ON SCHEMA public TO grafana_reader" \
  -c "GRANT SELECT (started_at, completed_at, ok) ON sweep_run TO grafana_reader" \
  -c "GRANT SELECT (type, status, tracked_since, fetch_failing) ON target TO grafana_reader" \
  -c "GRANT SELECT (event_type, occurred_at, email_status, email_attempts) ON alarm_event TO grafana_reader" \
  -c "GRANT SELECT (id, username, registered_at, closed_at, last_swept_at, last_swept_on, collection_months, backfill_completed_at, backfill_error) ON brand_account TO grafana_reader" \
  -c "GRANT SELECT (brand_id, called_on, calls) ON brand_call_count TO grafana_reader" \
  -c "GRANT SELECT (called_on, calls) ON target_call_count TO grafana_reader" \
  -c "GRANT SELECT (first_seen_at, enriched_at) ON brand_tagged_post TO grafana_reader" \
  -c "GRANT SELECT (verdict, first_seen_at) ON brand_hashtag_post TO grafana_reader" \
  -c "GRANT SELECT (short_code, username, ad_verdict, ad_verdict_source, ad_violations, ad_judged_at, judged_caption_hash) ON brand_post_meta TO grafana_reader"
```

```bash
# ③ analysis DB — 분석 미러(public) + app 스키마 신규 조회분.
#    accounts·contents·monitoring_campaigns는 count(*)뿐이라 PK 한 컬럼만(§14-2 spring_session과 같은 관용구).
docker exec -it deploy-postgres-1 psql -U <DB_USER> -d analysis \
  -c "GRANT USAGE ON SCHEMA public TO grafana_reader" \
  -c "GRANT SELECT (updated_at) ON public.landing_stats TO grafana_reader" \
  -c "GRANT SELECT (handle) ON public.accounts TO grafana_reader" \
  -c "GRANT SELECT (short_code) ON public.contents TO grafana_reader" \
  -c "GRANT SELECT (username, created_at, deleted_at) ON app.brand_monitorings TO grafana_reader" \
  -c "GRANT SELECT (id) ON app.monitoring_campaigns TO grafana_reader" \
  -c "GRANT SELECT (created_at, handle, status) ON app.saved_influencers TO grafana_reader" \
  -c "GRANT SELECT (created_at) ON app.saved_contents TO grafana_reader"
```

```bash
# ④ 서버 잔존 파일 제거(1회) — CD scp는 추가 전용이라 레포에서 지우거나 옮긴 파일이 서버에 남는다.
#    대상 4개 = 이번 이관분 1개(hypenow-brand) + 08-18 6탭 개편이 삭제한 구 3장(좀비).
ssh ubuntu@<IP> 'rm -f ~/deploy/grafana/provisioning/dashboards/json/{hypenow-brand,hypenow-service-overview,hypenow-errors,hypenow-api-performance}.json'
```

- **이관분**(`hypenow-brand`): 안 지우면 `json/`(HypeNow 폴더)과 `json-brand/`(브랜드 모니터링
  폴더)가 같은 `uid`를 이중 프로비저닝해 폴더가 오락가락한다.
- **좀비 3장**(`hypenow-service-overview`·`hypenow-errors`·`hypenow-api-performance`): 08-18 6탭
  개편(PR #498, main 배포 완료)이 레포에서 삭제했지만 서버엔 그대로 남아 있다. 안 지우면 총
  **11장**이 뜨고(기대값은 8장), 특히 "서비스 현황"은 §14-2-1이 실행 금지로 강등되면서 그 패널들의
  GRANT가 없어 **권한 오류 패널을 노출**한다.
- **타이밍**: ④는 **main 배포 전**에 실행한다(배포 후면 그 사이 같은 uid를 두 폴더가 이중
  프로비저닝한다). rm 직후부터 배포 전까지 운영에서 `[브랜드]` 대시보드가 잠시 안 보이는 것은
  정상이다 — 프로비저너가 60초 안에 삭제를 반영하고, 배포가 `json-brand/`로 되살린다.

**일반 규칙**: 레포에서 대시보드 JSON을 지우거나 옮기면 서버 파일은 CD가 지우지 않는다
(cd.yml의 `scp -r deploy/grafana/provisioning/.`는 덮어쓰기·추가만 한다) — **잔존 파일은 위처럼
수동 정리**한다. 이번처럼 삭제 시점에 정리하지 않으면 좀비가 릴리스마다 쌓인다.

- 경쟁사 탭의 등록 패널(`monitoring_registrations`·`monitoring_registration_entries`)은 §14-2
  기본 GRANT(운영 기적용)를 그대로 재사용한다 — 추가 없음.
- **반영 절차**: 이 GRANT를 **main 배포 전에** 실행해 두면 별도 재기동이 필요 없다 — cd.yml이
  매 배포마다 `docker compose restart grafana`·`restart prometheus loki alloy`를 실행하므로
  신설 데이터소스(monitoring.yaml)·신규 스크레이프 잡(node-exporter·cAdvisor)은 배포가 알아서
  반영한다. 배포 **후에** GRANT를 실행한 경우엔 대시보드 새로고침이면 충분하고, 그래도 안 붙으면
  `cd ~/deploy && docker compose restart grafana prometheus`.
- **마이그레이션 전제**: ②·③의 컬럼 GRANT는 그 테이블·컬럼의 마이그레이션이 운영 DB에 반영된
  뒤에만 성공한다(특히 monitoring `V20260817160000` 광고 판정 — `brand_post_meta.ad_verdict` 등).
  미반영이면 **그 줄만** 실패하므로(psql은 `-c` 단위 실행) 배포 후 해당 줄만 재실행하면 된다.
- 개통 확인: 터널 접속(§14-1) 후 홈 13타일에 "데이터 없음"/권한 오류가 없는지, 경쟁사·Hiker
  탭과 **브랜드 모니터링 폴더 3장**의 패널이 그려지는지 확인(폴더 2개, 대시보드 총 8장).
  Loki 타일(ERROR 급증·402·401)은 매칭 0건이 숫자 0으로 떠야 정상(`or vector(0)` — 빈 벡터면
  빨강 "데이터 없음"이 뜨게 fail-loud로 짜여 있다).

### 14-3. `.env` 신규 항목 (`.env.example`에도 반영됨)

| 변수 | 설명 |
|---|---|
| `GF_SECURITY_ADMIN_USER` | Grafana 관리자 계정 (예: `admin`) |
| `GF_SECURITY_ADMIN_PASSWORD` | Grafana 관리자 비밀번호 — 강한 값 |
| `GRAFANA_READER_PASSWORD` | 위 14-2에서 만든 `grafana_reader` 비밀번호와 동일 값 |

`DISCORD_WEBHOOK_URL`은 **재사용**(§9, ons-relay와 같은 웹훅) — 새 변수 불필요. 이미 설정돼
있어야 아래 14-4 알림이 동작한다.

**DNS·인증서는 필요 없다** — SSH 터널 전용이라 도메인을 쓰지 않는다(§14 머리말의 결정 근거 참조).

### 14-4. 알림 — "미완료 등록 30분 초과"

Grafana unified alerting을 파일 프로비저닝으로 구성했다
(`deploy/grafana/provisioning/alerting/{contact-points,policies,rules}.yaml`) — contact point는
디스코드 웹훅 재사용, 규칙은 대시보드 패널1과 같은 SQL(30분 초과 미완료 건수 > 0)을 5분마다 평가.

**판단 근거**: 알림 프로비저닝 스키마(특히 `data[]`의 reduce/threshold 표현식 체인, `folder`
필드 처리)는 공식 문서에 완전한 예시가 없어 서버 미접속 제약(이 작업은 파일만 작성) 하에서
실기동 검증을 못 했다. 다만 스키마 자체는 Grafana 9 이후 안정적으로 문서화된 표준 패턴이라
"억지로 만든" 수준은 아니라고 판단해 프로비저닝을 시도했고, 실패 시 폴백으로 아래 수동 확인
절차와 대시보드 임계치 강조(패널1·2 빨강, 패널6 경고색)를 이미 준비해 뒀다.

**최초 기동 후 반드시 확인**: Grafana UI → Alerting → Alert rules에서 "미완료 등록 30분 초과"
규칙이 `HypeNow` 폴더 아래 정상 로드됐는지 확인. 로드에 실패하면(프로비저닝 오류는 보통
컨테이너 로그에 남는다 — `docker logs deploy-grafana-1 | grep -i provision`) UI에서 수동으로
같은 조건(위 rules.yaml의 SQL·reduce last·threshold gt 0, 평가주기 5분)으로 규칙을 만들고,
Contact point는 프로비저닝된 `discord-ops`를 그대로 지정하면 된다(이건 대개 성공한다 — 실패
가능성이 큰 쪽은 규칙 스키마다).

### 14-5. 최초 기동 절차

1. 위 14-2 롤 생성 + **14-2-2 GRANT 런북** (서버, 최초 1회) — **배포보다 먼저**. 없으면
   컨테이너는 뜨지만 패널이 권한 오류로 빈다.
2. `~/deploy/.env`에 14-3 표의 3개 변수 등록 (`GRAFANA_READER_PASSWORD`는 14-2 값과 일치)
3. develop→staging→main 승격으로 배포 (또는 긴급 경로 §5) — CD가 `docker compose pull && up -d`로
   grafana 컨테이너를 기동. **Caddy는 무관하다**(라우트 없음 — 재기동도 인증서 발급도 없다)
4. `ssh -L 3001:localhost:3000 ubuntu@<IP>` 후 `http://localhost:3001` 접속 → 관리자 로그인 →
   홈 대시보드 13타일 확인(14-2-2 개통 확인 절 참조)
5. 14-4의 알림 규칙 로드 여부 확인, 필요 시 수동 보완

## 15. 쿼리·API 성능 측정 스택 (08-10~)

Prometheus(지표)·Loki(로그)·기존 Grafana(시각화) + postgres `pg_stat_statements`(SQL 통계).
정의: `deploy/compose.yaml`(prometheus·loki·alloy 서비스) + `deploy/prometheus/`·`deploy/loki/`·
`deploy/alloy/` 설정 파일 + Grafana 프로비저닝(데이터소스 `observability.yaml`). 셋 다 호스트
포트 미노출 — 조회는 Grafana(§14 SSH 터널)로만. 전용 대시보드 "HypeNow API 성능"은 08-18
개편에서 폐기됐다(p95/p99·상위 SQL 패널은 사용자 결정으로 폐기) — 지표는 홈·인프라 탭이 쓰고,
SQL 통계는 필요할 때 Grafana Explore에서 직접 조회한다.

**범위**: `pg_stat_statements`는 analysis 클러스터(`deploy-postgres-1`)에만 붙는다 — raw DB
(`deploy-postgres-raw-1`, crawler 적재 경로)의 SQL은 잡히지 않는다(필요해지면 그쪽 postgres에도
같은 preload·확장을 따로 넣어야 한다).

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

# 로그: loki에 컨테이너 라벨이 잡히는지 (alloy 이미지엔 wget이 없을 수 있어 같은 prod 네트워크의
# prometheus 컨테이너에서 조회한다 — alloy 자체 상태는 `docker logs deploy-alloy-1`로 본다)
docker exec deploy-prometheus-1 wget -qO- 'http://loki:3100/loki/api/v1/label/container/values'
```

세 번째 명령은 `deploy-was-1`·`deploy-postgres-1` 같은 **컨테이너 이름 목록이 나오면 정상**이다
(alloy 기동 직후에는 첫 로그가 밀려 올라올 때까지 몇 분 비어 있을 수 있다).
`wget`이 없다는 오류가 나면(이미지 구성에 따라 다름) 폴백: Grafana Explore에서 같은 쿼리를 실행하고
(Prometheus 데이터소스에 `up{job="was"}`, Loki 데이터소스에 `{container="deploy-was-1"}`),
컨테이너 자체 상태는 `docker logs deploy-prometheus-1`·`docker logs deploy-loki-1`로 본다.

Grafana(§14-1 터널) → 홈 탭의 API 5xx·인프라 탭의 JVM·커넥션 풀 패널이 그려지는지 확인.
로그는 Explore → Loki 데이터소스 → `{service="was"}`.

> ⚠️ **배포 직후 수 분 구간의 지표는 신뢰하지 말 것.** was 롤링 배포(§5-1) 창에는 신·구 컨테이너가
> 수 분간 공존하는데, prometheus 타깃은 `was:9081` 단일 DNS 이름이라 스크레이프마다 두 JVM 중
> 아무 쪽이나 잡힌다 — 카운터가 신규 JVM의 0으로 되돌아가며 rate가 튀거나 꺼지고, p95는 웜업 중인
> 새 JVM과 드레이닝 중인 구 JVM의 값이 뒤섞인다. 성능 비교·회귀 판정은 롤링이 끝난 뒤 구간으로.

> 설정 파일(`prometheus.yml`·`loki-config.yaml`·`config.alloy`)은 CD가 매 배포마다 scp로
> 동기화하고 `docker compose restart prometheus loki alloy`로 반영한다 — 설정만 바꾼 변경도
> 배포 한 번이면 서버에 붙는다(수동 복사 불필요).

### 15-3. 운영 다이얼

- 부하가 예상(합산 RAM 330~400MB·CPU 1~2%)을 넘으면: `deploy/prometheus/prometheus.yml`의
  `scrape_interval` 60s 상향이 1차 다이얼. **이때 `deploy/grafana/provisioning/datasources/
  observability.yaml`의 `timeInterval`도 같은 값으로 바꾼다** — 둘이 어긋나면 `$__rate_interval`이
  옛 간격 기준으로 계산돼 rate 패널이 조용히 듬성해진다(반영은 CD의 grafana 재기동).
- 통계 리셋(개선 전후 비교 시작점):
  `docker exec deploy-postgres-1 psql -U <DB_USER> -d analysis -c "SELECT pg_stat_statements_reset()"`.
- 슬로우 쿼리 로그 임계는 500ms(`log_min_duration_statement=500`, compose의 postgres command) —
  해당 로그는 postgres 컨테이너 stdout → Loki(`{container="deploy-postgres-1"}`)로 들어온다.
- 디스크 상한: Prometheus 30일·1GB(`--storage.tsdb.retention.time/.size` — 먼저 닿는 쪽이
  적용)·Loki 보관 30일(compactor) — 둘 다 자동 삭제라 수동 정리 불필요.
- Loki는 Prometheus와 달리 **용량 상한이 없고 기간(`retention_period`)만 있다** — 로그량이 늘면
  디스크가 그만큼 자란다. `docker system df -v | grep loki-data`로 볼륨 실측 크기를 주기적으로
  확인하고(디스크 알람 85% 전에), 증가율이 과하면 `deploy/loki/loki-config.yaml`의
  `retention_period`를 하향(예: 30일 → 14일)하는 것이 다이얼이다.

## 16. 에러 추적 (08-12~, 08-18 개편으로 인프라·Hiker 탭에 흡수)

로그(Loki)만으로 "어젯밤 뭐가 터졌나"를 훑고 그 자리에서 원인까지 내려가는 조회축이다.
전용 대시보드 "HypeNow 에러"는 08-18 개편에서 해체됐다 — **전 서비스 에러**(총건수·추이·로거별·
예외별·로그 탐색 + svc/level/search 변수)는 **인프라 탭** 로그 절로, **외부 의존 실패**(Hiker
402·IG 401·상태코드별·크롤 실패 로거)는 **Hiker 탭**으로 이동(WARN 총건수·직전 24h 비교 패널은
사용자 결정으로 폐기). 별도 개통 절차 없음 — 배포 1회면 붙는다(`config.alloy`·대시보드 JSON 모두
CD가 scp + `docker compose restart alloy`/grafana로 반영, §15 말미 참조).

### 16-1. 읽는 순서

1. **홈 탭 첫 행** — ERROR 급증·Hiker 402·IG 401 타일이 입구. 타일 클릭으로 상세 탭 이동.
2. **인프라 탭 로그 절(무엇이 터졌나)** — 로거별·예외 클래스별 Top 15에서 낯선 항목을 찾고,
   찾은 로거·예외명을 "검색어" 변수에 넣어 원문을 본다(정규식, 대소문자 무시).
3. **Hiker 탭(외부 의존 실패)** — **Hiker 402(잔액 소진)·IG 401(IP 차단) 칸이 빨간색이면 사람이
   개입해야 한다.** 402는 충전, 401은 요청량 조절 없이는 저절로 낫지 않는다. **WARN을 반드시
   같이 본다**: crawler·monitoring은 외부 의존 실패(Hiker 4xx·IG 차단·스윕 실패)를 대부분
   `log.warn`으로 남겨서, ERROR만 보면 크롤링 사고가 화면에 안 잡힌다(크롤 실패 로거 Top 15가
   WARN 포함인 이유).

### 16-2. 로그 파이프라인이 하는 일 (`deploy/alloy/config.alloy`)

- **JVM 4종만 multiline 병합**: 스택트레이스를 1건으로 묶는다. 이게 없으면 에러 1건이 트레이스
  줄 수만큼 부풀어 세어진다. caddy·postgres 등은 Spring 타임스탬프로 시작하지 않아 같은
  파이프라인에 태우면 전부 한 덩어리로 뭉치므로, 타깃을 두 갈래로 나눠 놓았다 — **이 분기를
  없애지 말 것.**
- **`service` 라벨**: compose 서비스명 그대로(08-12 로그 누락 수정에서 도입). 롤링 배포마다
  번호가 바뀌는 컨테이너 실명(`deploy-was-38`)과 달리 배포로 안 바뀌는 안정 조회축이다 —
  `container`로 그룹핑하면 배포할 때마다 시계열이 끊긴다. JVM/비-JVM 분기 판정도 이 라벨을 쓴다.
- **`level` 라벨**: 로그 레벨(JVM 4종만). 로거명·예외 클래스는 값이 수백 개라 **라벨로 올리지
  않는다**(쿼리 시점 `| regexp` 추출). 이 원칙을 어기면 Loki 스트림이 폭발한다.

### 16-3. 한계

- 로그 보관 30일, 운영 스택만 수집 — test 스테이징 로그는 없다.
- **요청 단위 상관관계 불가**: traceId/MDC가 없어 에러가 어떤 엔드포인트·유저 요청이었는지
  못 짚는다. 필요해지면 logback 구조화 로깅이 다음 단계다.
- **2026-08-12 파이프라인 변경 이전 로그는 이 대시보드에 안 잡힌다** — `level` 라벨이 없기
  때문. 30일이 지나면 자연 해소된다.
- 로그 기반 에러 집계와 §15의 Prometheus 5xx율은 **숫자가 구조적으로 다르다**(4xx는 로그를
  안 남기고, WARN은 5xx가 아니다). 나란히 비교하지 말 것.
- 자동 새로고침 5분 고정(2코어 보호). 장애 추적 중에는 Grafana UI에서 일시적으로 올린다.

### 16-4. 로컬 검증 리그

`config.alloy`를 고칠 때는 `deploy/alloy/test/`의 리그로 먼저 확인한다 — 운영과 같은 compose
서비스명·네트워크 이름을 흉내내 relabel과 multiline 병합이 실제로 걸리는지 본다. 사용법은
[deploy/alloy/test/README.md](alloy/test/README.md). **서버에서 실행 금지**(운영과 같은 이름 공간).
