# CLAUDE.md

## 세션 시작

- **[ARCHITECTURE.md](ARCHITECTURE.md)를 먼저 읽는다** — 구조·경계·원칙만 담은 경량 문서(살아있는 문서, 항상 최신).
  결정 기록([DECISIONS.md](DECISIONS.md))·작업 트랙 상세([`docs/tracks/`](docs/tracks/))는 필요할 때만 연다.
- 구조나 태스크 상태가 바뀌는 작업을 했으면 **새 결정은 [DECISIONS.md](DECISIONS.md) 맨 위에**,
  **트랙 상태는 해당 `docs/tracks/<트랙문자>-<슬러그>.md`**(트랙 1개 = 파일 1개, §5 참조)에 갱신한다.
  ARCHITECTURE.md의 §5·§7은 둘 다 그 파일들로의 포인터 스텁이라 갱신 대상이 아니다.
- 문서 체계: `ARCHITECTURE.md`(항상 최신) / `docs/superpowers/specs/`(설계 기록 — 영구 보존·내용 불변,
  **완결 트랙의 스펙은 `specs/archive/`로 이동**) / `docs/superpowers/plans/`(구현 계획 — 실행 완료 시
  `plans/archive/`로 이동) / `docs/tracks/`(트랙 1개 = 파일 1개 — **완결(✅)·제거(🗑) 트랙은 그 마무리
  PR에서 `docs/tracks/archive/`로 이동**, 재개되면 다시 `docs/tracks/`로 꺼낸다. specs·plans와 동일
  원칙 — 완료 문서가 활성 위치에 남으면 이후 세션들이 계속 읽어 컨텍스트 비용이 쌓인다).
  dated 문서는 첫머리 상태 헤더(`> 상태: 🟢 활성 · ✅ 구현/실행/반영됨 · 🗄 대체됨 · ⏸ 보류`)를 **단일
  상태로** 유지한다(예: "🟢 활성 · ✅ 구현됨" 같은 혼합 표기 금지 — 아카이브 여부 분류를 막는다).

## 시스템 경계 (위반 금지)

- **crawler** → raw DB(`crawler`) 쓰기. 크롤링 담당 영역 — 분석 작업에서 raw 스키마를 바꾸지 않는다.
- **analytics** → raw 읽기, 분석 결과(analysis DB) 쓰기. LLM 분석도 이 층 소속.
- **was** → 분석 결과는 **읽기만**, 쓰기는 **서비스 데이터(`app` 스키마)에만**. raw DB 접근 금지.
  분석 결과와 서비스 데이터를 SQL 조인하지 않는다(조합은 was 코드에서).
- 모듈 간 Java 공유는 계약 모듈 `contract-analysis`(분석 결과 record·enum, 순수 JDK)만 —
  crawler는 계약 모듈과 무관. 미러는 타입 기반(뷰 SQL/Flyway DDL/공유 record — ARCHITECTURE §4-3).

## 빌드·검증

- **테스트는 모듈 단위가 기본**: `./gradlew :was:test`, 단일은 `./gradlew :was:test --tests
  "com.celfit.was.SomeTest"`. **전체 `./gradlew test`는 PR 직전에만** — `org.gradle.parallel=true`라
  모듈 4개가 각자 Testcontainers Postgres를 띄워서, 로컬에선 컨테이너 4개가 VM 자원을 두고 경합한다.
  (Java 21, Spring Boot 4.1, Gradle 멀티모듈: crawler/analytics/was/monitoring)
- 통합 테스트는 Testcontainers(PostgreSQL) — 로컬 도커는 **colima가 정본**(Docker Desktop 아님).
  **colima는 8 CPU / 12 GiB 이상으로 기동**한다: 기본값 4 CPU / 4 GiB에서는 병렬 테스트가 VM을 굶겨
  느려지고, 컨테이너 `now()`가 주기적으로 역행하는 플레이키를 유발한다(07-30 실측 —
  [test-wall-clock-backward-steps] 계열 원인). 재기동 후 실데이터 컨테이너는 `docker start`로 올릴 것.
  ```
  colima stop && colima start --cpu 8 --memory 12
  ```
  테스트 시간의 본체는 테스트 로직이 아니라 **컨테이너 부팅 + Flyway 재생**이다(07-30 실측: 68개
  클래스 64.6초 중 51초가 컨테이너를 띄우는 2개 클래스). 느려졌다고 느끼면 먼저 colima 자원을 볼 것.
- 정적분석: Error Prone이 `net.ltgt.errorprone` 플러그인으로 컴파일에 붙는다 — ERROR 등급만 빌드 실패,
  WARNING은 노이즈 감안해 전부 끔(루트 `build.gradle` subprojects 블록).
- 분석 뷰 검증: SQL 하니스(더미 시드 + BEGIN/ROLLBACK 격리) — `analytics/test/run.sh`(전체) /
  `analytics/test/run.sh test/NN_*.test.sql`(단일). 실데이터 postgres 컨테이너 필요(기본
  `crawler-postgres-1`, `PG_CONTAINER`로 오버라이드).
- CI(`ci.yml`): develop push·develop/main 대상 PR마다 `./gradlew test` 전체 + `sql-harness` 잡(프레시
  Postgres에 crawler 마이그레이션 전체 적용 후 위 SQL 하니스 실행)이 자동으로 돈다.
- 실행: `./gradlew :was:bootRun`(8081) / `:crawler:bootRun`(8080, 어드민 `/ui`) / `:analytics:bootRun`(8082, 어드민 `/ui` — 잡 트리거·로그. one-shot 미러는 `--analytics.mirror-on-startup=true --spring.main.web-application-type=none`)
- 실행(monitoring): `./gradlew :monitoring:bootRun`(8083) — 로컬은 기존 DB 볼륨에 `db/init/02-create-monitoring-db.sql`을 수동 적용해야 뜬다(init 스크립트는 새 볼륨에만 자동 실행).
- DB: docker `crawler-postgres-1` (포트 5433, crawler/crawler, DB `crawler`·`analysis`) —
  컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다를 수 있음(예: `hypenow-crawler-postgres-1`).
  스크립트는 `PG_CONTAINER` 환경변수로 오버라이드.

## 컨벤션

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix는 `feat(모듈):`/`docs:` 식.
- **브랜치·PR**: 기본 브랜치는 `develop`. 작업은 `feat/*`(또는 `docs/*`, `chore/*`) 브랜치에서 하고
  **develop 대상 PR**로 합친다. develop·staging·main 직접 push 금지(승격 머지 제외). `main`은 릴리스용.
- **승격 흐름은 develop→staging→main** (07-29~): develop 머지는 CI만(배포 없음),
  **develop→staging 머지 = test 스테이징 배포**(dev-api.hypenow.io — cd-test.yml),
  **staging→main 머지 = 운영 배포**. 배포는 이 CD로만 한다. `deploy/scripts/deploy.sh` 수동 실행 금지 —
  긴급 롤백·CD 불능 시에만, 반드시 사용자 확인 후 `--force`로. (07-20: develop 체크아웃 상태의
  수동 배포가 CD의 main 배포를 덮어 was/analytics 버전 불일치 → 랭킹 API 전면 500.
  `:latest`는 마지막 push가 이긴다.)
- **핫픽스만 예외로 main 직행**(`hotfix/*`, 08-10~ — [설계](docs/superpowers/specs/2026-08-10-hotfix-direct-to-main-design.md)):
  운영 장애 한정. main에서 분기해 **base=main으로 PR**을 열면 CI 3종이 그대로 돌고(main엔 머지 큐가
  없어 대기 없음) 머지 즉시 운영 배포된다. 룰셋·CI 설정은 이미 이걸 허용하므로 손댈 게 없다.
  **staging을 건너뛰므로 운영에서 처음 도는 코드다** — 범위를 최소로 하고 **스키마 마이그레이션은
  동반하지 않는다**(expand-contract가 전제하는 릴리스 간격이 사라진다 — 필요하면 정규 경로로).
  머지 후 `backmerge.yml`이 main→develop·main→staging 역머지 PR을 자동으로 열고 auto-merge를 건다
  (시크릿 `BACKMERGE_TOKEN` 필요). **승격·역머지 PR은 반드시 merge commit으로** — squash·rebase는
  원 커밋 SHA를 새로 만들어 역머지 판정(`git log --no-merges <target>..main`)을 깨고, 그러면 평범한
  승격마다 역머지 PR이 열려 규약이 죽는다.
- crawler는 DDD/헥사고날(`<context>/{domain, application/{service,port}, adapter/{in,out}}`), was·analytics는 평탄 패키지.
- DTO는 record(+정적 `from()`). was 조회는 JdbcClient. Jackson 3(`tools.jackson.*`).
- 분석 뷰는 `analytics/views/NN_*.sql` 번호순 적용, 테스트는 같은 번호 `analytics/test/NN_*.test.sql`.
- 런타임 설정은 `app_setting(key,value)` — 뷰가 직접 읽는 키도 있다(예: `analytics.recent-window`).
  **기준값은 crawler Flyway 마이그레이션으로 시드**(`ON CONFLICT DO NOTHING`, V16 참조 —
  07-20 수동 등록분 유실 사고 후 확립). 기준값 추가·변경은 후속 마이그레이션으로,
  수동 UPDATE는 런타임 토글(프로바이더 전환·임시 상향)만.
- **신규 Flyway 마이그레이션은 UTC 타임스탬프로 채번한다**(`V<YYYYMMDDHHMMSS>__<설명>.sql`,
  07-30~ — 예: `V20260730153000__account_summary_note.sql`). 정수 연번(`V1`~`V49` 등)은 병행
  세션이 같은 다음 번호를 집는 경합이 반복됐다(V18→V19, V22→V23, PR #181). Flyway는 버전을
  숫자로 비교하므로(선행 0 무시) 14자리 타임스탬프는 항상 기존 정수보다 커서 순서가 자동
  보장된다(`MigrationVersion.compareTo` 실측 확인, 가드 v3.2 참고). **기존 `V1`~`V49` 파일은
  절대 rename 금지** — `schema_history`에 버전·체크섬이 기록돼 있어 rename하면 운영 DB
  마이그레이션이 깨진다. 대상은 독립 버전 공간 4개(각자 다음 자유 번호를 자유 채번) 전부 —
  crawler, analytics `db/migration/analysis`, was `db/migration/app`, monitoring. 번호 경합 검사
  (`check-migration-safety.sh`)는 자릿수 제한 없는 정규식이라 무수정으로 호환.
  **반드시 UTC로**(`date -u +%Y%m%d%H%M%S`) — KST 채번은 미래 번호 선점으로 뒤따르는 정상
  채번을 전부 Flyway out-of-order 거부에 빠뜨린다(08-12 운영 크래시루프 2회). 가드 v4가
  PR에서 미래 채번(UTC+1h 초과)·역전(base 최대 이하)을 차단하며, 의도된 미래 번호는
  `-- allow-future-version: <사유>` 주석으로 통과(deploy/README.md §5-1).
- 배열 저장은 `text[]` 대신 `jsonb` (기존 `RawComment.payload` 매핑 관용구 재사용).
- **스키마 변경은 expand-contract** (07-29 was 롤링 배포 도입~): 롤링 중 신구 코드가 같은 DB를
  공존해서 본다 — `DROP`·`RENAME`·타입 변경·`SET NOT NULL`은 참조 코드가 끊긴 **다음 릴리스**에서만.
  CI `migration-guard`가 차단하며, 의도된 contract 단계는 `-- allow-destructive: <사유>` 주석으로
  통과. **DROP COLUMN 파일은 그 컬럼을 참조하는 보정 UPDATE 동봉 필수**(가드 v2 짝 검사 —
  롤링 창 유실분 최종 백필. 불필요하면 `-- no-backfill: <사유>`)([deploy/README.md §5-1](deploy/README.md)).
- **세션 위생**(여러 세션 동시 작업 전제): 작업이 끝나면 머지 준비가 안 됐더라도 **PR을 즉시 연다**(draft
  가능) — 열린 PR이 "이 영역은 점유 중"이라는 세션 간 유일한 신호다. 작업 종료 시 자기 worktree와
  머지된 브랜치를 정리한다 — 방치된 worktree가 쌓이면 어느 작업이 살아있는지 식별 불가능해진다
  (07-30 기준 25개 방치 확인). 작업이 끝나 PR을 열 때는 그 작업의 plan 문서도 같은 PR에서
  `plans/archive/`로 함께 옮긴다 — 완료 문서가 남아 있으면 이후 세션들이 계속 읽어 컨텍스트
  비용이 쌓인다.

## 함정

- `docker compose up`은 디렉토리명 기반 프로젝트로 **빈 컨테이너를 새로 만든다** — 실데이터는
  기존 컨테이너(`crawler-postgres-1`, 머신에 따라 이름 상이)에 있음. `docker start`로 기동할 것.
- `.env`는 JVM에 자동 로드되지 않는다 — `APIFY_TOKEN` 등은 셸 `export` 필요.
- **셸에 `DOCKER_HOST`가 없으면 Testcontainers 테스트가 대량 실패한다** — colima 소켓은
  `unix://$HOME/.colima/default/docker.sock`이고, 미설정 시 `DockerClientProviderStrategy` 초기화가
  깨져 통합 테스트가 무더기로 죽는다(07-30 실측: `:was:test` 714개 중 331개 실패). **테스트 코드
  결함이나 플레이키로 오진하기 쉬운 실패 양상**이니, 대량 실패를 보면 먼저 이걸 확인할 것.
  ```
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  ```
- Spring Boot 4 주의: `@WebMvcTest`는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지,
  Testcontainers 2.x는 `org.testcontainers.postgresql.PostgreSQLContainer`.
- **피드 게시물은 조회수(views)가 항상 NULL** — 조회수 집계·비율 계산에는 항상 NULL 규칙이 따라붙는다.
- **"분석 잔여 몇 건 / 왜 분석 안 됐나" 카운트는 `analytics/check/pending.sh` 정본으로.** 미분석
  콘텐츠의 timely 여부는 분석 완료 전엔 어디에도 영속화되지 않는다(`v_analysis_candidates.timely`가
  유일한 판정 지점) — `content_analyses` 단독 카운트나 즉석 쿼리는 "제때창(3일) 놓쳐서 분석 안
  함(영구 제외·상세 트랙)"과 "분석 대상인데 대기"를 뭉갠다(07-21·07-28 오답 전력).
- **뷰 이름·컬럼명·API 경로 등을 grep할 때는 `docs/`를 제외한다** — `docs/`가 3.9MB 한글 마크다운이라
  문서 히트가 코드보다 많아 실제 정의·사용처 탐색이 묻힌다. 예:
  `grep -rn 'v_account_summaries' --exclude-dir=docs .`
