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
  Re-run). 스큐를 오래 방치하지 말 것.
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

## 6. 백업·복원
- 자동: 서버 크론이 매일 KST 04:10 덤프 (맥·서버 어느 쪽이 꺼져 있든 오프사이트 사본 유지)
  - **analysis**: 서버 `~/backups/` 7일 롤링 + Google Drive `hypenow-backups/` 30일 롤링
  - **crawler**(raw — 07-19부터 서버가 수집 주체라 서버 raw가 유일 원본): 서버 3일 롤링 +
    Drive `hypenow-backups/crawler/` **최신 3개** 롤링 — 덤프가 GB급(07-20 실측 ~1.5GB,
    DB 기준 하루 ~0.6GB씩 증가)이라 Drive 무료 15GB에 맞춰 개수 제한. 용량 증설 시 개수 상향.
  - **monitoring**(시딩 캠페인 — postgres 인스턴스 내 별도 DB, §13): 서버 7일 롤링 +
    Drive `hypenow-backups/monitoring/` 30일 롤링. 덤프가 작아 analysis와 같은 기간 롤링.
- 수동 pull(보조): `deploy/scripts/pull-backup.sh ubuntu@<IP>` → `~/backups/hypenow/`
- 복원 리허설(로컬): `gunzip -c analysis-*.sql.gz | psql -h localhost -p 5433 -U crawler -d <빈 DB>`

### 6-1. rclone(Google Drive) 1회 설정
```bash
# 맥에서 (브라우저 OAuth 필요)
brew install rclone
rclone config          # n → 이름 gdrive → storage: drive → 기본값들 → 브라우저 승인
rclone lsd gdrive:     # 동작 확인
ssh ubuntu@<IP> 'mkdir -p ~/.config/rclone'
scp ~/.config/rclone/rclone.conf ubuntu@<IP>:~/.config/rclone/rclone.conf   # 서버로 복사
ssh ubuntu@<IP> 'rclone mkdir gdrive:hypenow-backups && rclone lsd gdrive:'  # 서버에서 확인
```
※ rclone.conf에는 구글 OAuth 토큰이 들어 있다 — repo에 커밋 금지, 서버 홈에만.

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
  (인스턴스 다운·API 불통 → 디스코드 **+ EMAIL 백업** — 인스턴스가 통째로 죽으면 릴레이도
  죽어 디스코드 경로가 끊기므로, OCI에서 직접 나가는 이메일이 그 순간을 커버.
  알람당 토픽 1개 제약이라 "치명 토픽에 구독 2개" 구조. 이메일 추가는 구독만 더 붙이면 됨)
- 디스코드는 **릴레이 경유**(CUSTOM_HTTPS 구독 → `ons-relay` 컨테이너 → 디스코드 웹훅.
  ONS가 SLACK 엔드포인트를 hooks.slack.com만 허용해 직결 불가 — 서버 `.env`에
  `DISCORD_WEBHOOK_URL`·`ONS_RELAY_TOKEN` 필요, 경로는 caddy `/internal/ons-relay/<토큰>`.
  구독 확인은 릴레이가 자동 컨펌. PAYG 전환 시 OCI Functions로 릴레이 대체 검토):
  **API 외형 감시**(Health Checks `hypenow-api-health` — 외부 관측점 3곳에서 60초마다
  `https://api.hypenow.io/health`, 과반 실패 2분 지속 시), 인스턴스 CPU·메모리 85%, 인스턴스 다운,
  **컨테이너 다운**(deploy-*-1 7종 — monitoring 포함, §13), **디스크 85%**, **버킷 15GB**(무료 티어 20GiB 한도)
- 컨테이너·디스크·버킷 용량은 커스텀 메트릭(`hypenow_custom`) — 서버 크론 1분 주기
  (버킷은 스크립트가 5분 결에만 조회 — OCI가 StoredBytes를 자동 게시하지 않아 직접 게시):
  `* * * * * /home/ubuntu/.venv-oci-metrics/bin/python /home/ubuntu/deploy/scripts/post-container-metrics.py >> /home/ubuntu/metrics-post.log 2>&1`
- 인증은 인스턴스 프린시펄 — 서버에 API 키를 두지 않는다. IAM 구성:
  dynamic group `hypenow-instances`(인스턴스 매칭) + policy `hypenow-custom-metrics`:
  `Allow dynamic-group hypenow-instances to use metrics in tenancy where target.metrics.namespace='hypenow_custom'`
  `Allow dynamic-group hypenow-instances to read buckets in tenancy where target.bucket.name='hypenow-images'`
  venv: `python3 -m venv ~/.venv-oci-metrics && ~/.venv-oci-metrics/bin/pip install oci`
- 컨테이너 추가·이름 변경 시 스크립트의 `SERVICES` 목록도 갱신할 것(목록 고정 방식 —
  사라진 컨테이너도 0으로 게시해 알람이 잡는다). 버킷 추가 시 `BUCKETS` 목록 갱신.

## 10. 이사 절차 (오라클 → 아무 VPS, 목표 30분)
1. 새 Ubuntu 서버: §3 최초 기동 그대로 (rsync → setup → .env → up)
2. 데이터: `pull-backup.sh`의 최신 덤프를 새 서버에 넣고
   `cd ~/deploy && set -a && source .env && set +a` 후
   `gunzip -c dump.sql.gz | docker compose exec -T postgres psql -U $DB_USER -d analysis`
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
[specs/2026-07-26-dev-staging-environment-design.md](../docs/superpowers/specs/2026-07-26-dev-staging-environment-design.md) ·
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
  Re-run으로 재실행한다(07-20 "배포가 조용히 안 나감" 계열 방지).

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
4. 서버 스크립트 갱신 — 레포에는 반영돼 있지만 **CD는 스크립트를 배포하지 않는다**(compose·이미지만).
   두 파일 모두 rsync로 직접 올릴 것:
   - `post-container-metrics.py` — 컨테이너 다운 알람 대상 `SERVICES`에 monitoring 추가(§9)
   - `backup.sh` — monitoring DB 덤프 추가(§6). 안 올리면 백업 크론이 옛 스크립트를 계속 돌려
     monitoring만 백업에서 조용히 빠진다.
   ```bash
   rsync -av deploy/scripts/post-container-metrics.py deploy/scripts/backup.sh ubuntu@<IP>:~/deploy/scripts/
   ```
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
  서버 `~/backups/monitoring-*.sql.gz` 7일 + Drive `hypenow-backups/monitoring/` 30일 롤링(§6).
