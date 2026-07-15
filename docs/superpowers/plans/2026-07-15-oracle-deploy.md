# was+DB 오라클 배포 구현 계획

> 상태: 🟢 활성
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** was+analysis DB를 오라클 A1 인스턴스에 docker compose로 띄울 수 있는 배포 산출물 일체(`deploy/`, prod/cloud 프로파일, 스크립트, 런북)를 만든다.

**Architecture:** 스펙 [2026-07-15-oracle-deploy-design.md](../specs/2026-07-15-oracle-deploy-design.md). 인스턴스 1대 위 compose 3컨테이너(postgres/was/caddy), 미러는 로컬 analytics가 SSH 터널로 push, 백업은 서버 pg_dump + 맥 pull, 이미지는 GHCR multi-arch로 이식성 확보.

**Tech Stack:** Docker Compose, Caddy 2(HTTPS 자동), eclipse-temurin:21-jre(ARM64/AMD64), GHCR, bash + cron.

**전제:** 이 계획의 산출물은 전부 repo 안 파일 — 실제 오라클 가입·인스턴스 생성·DNS는 계획 실행 후 사용자와 함께 진행(맨 아래 "실 배포 런북" 참고). 로컬 검증에는 `crawler-postgres-1`(포트 5433)이 떠 있어야 한다: `docker start crawler-postgres-1`.

---

### Task 1: was `prod` 프로파일

**Files:**
- Create: `was/src/main/resources/application-prod.yml`

- [ ] **Step 1: 프로파일 파일 작성**

```yaml
# 운영(오라클) 프로파일 — 값은 deploy/compose.yaml이 환경변수로 주입한다.
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
server:
  # caddy 뒤에서 X-Forwarded-* 를 신뢰 — Secure 쿠키·scheme 판정용
  forward-headers-strategy: framework
  servlet:
    session:
      cookie:
        secure: true
        same-site: none   # rewrite·직접 호출 어느 경로든 세션 쿠키 전송 보장 (www↔api는 same-site라 Lax도 되지만 광의로)
was:
  cors:
    allowed-origins: https://www.hypenow.io   # 운영 프론트 오리진만 — dev 오리진은 기본 프로파일에
```

- [ ] **Step 2: prod 프로파일로 기동 검증** (로컬 DB를 운영 DB 삼아)

Run (맥엔 `timeout`이 없으므로 백그라운드 기동 후 직접 정리):
```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/infra-deploy
DB_URL=jdbc:postgresql://localhost:5433/analysis DB_USER=crawler DB_PASSWORD=crawler \
  SPRING_PROFILES_ACTIVE=prod ./gradlew :was:bootRun > /tmp/was-prod.log 2>&1 &
sleep 30 && curl -s localhost:8081/health
grep 'profile is active' /tmp/was-prod.log
pkill -f 'com.celfit.was.WasApplication' || true; kill %1 2>/dev/null || true
```
Expected: `{"status":"ok","service":"was"}` + `The following 1 profile is active: "prod"`

- [ ] **Step 3: 기존 테스트 회귀 확인**

Run: `./gradlew :was:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add was/src/main/resources/application-prod.yml
git commit -m "feat(was): 운영(prod) 프로파일 — env 주입 datasource, 프록시 헤더 신뢰, 크로스 사이트 세션 쿠키"
```

---

### Task 2: analytics Flyway 완화 dev 국한 + `cloud` 타깃 프로파일

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/config/FlywayConfig.java`
- Create: `analytics/src/main/resources/application-cloud.yml`

- [ ] **Step 1: FlywayConfig의 `*:missing` 완화를 프로퍼티로 전환**

`FlywayConfig.java`의 `analysisFlyway` 빈을 다음으로 교체 (클래스 javadoc은 유지):

```java
	@Bean(initMethod = "migrate")
	public Flyway analysisFlyway(@Qualifier("analysisDataSource") DataSource analysisDataSource,
			@Value("${analytics.flyway-ignore-missing:true}") boolean ignoreMissing) {
		var configuration = Flyway.configure()
				.dataSource(analysisDataSource)
				.locations("classpath:db/migration/analysis")
				.baselineOnMigrate(true)
				.baselineVersion("0");
		// 공유 dev DB에 병행 브랜치(다른 워크트리)가 적용한 마이그레이션은 이 브랜치엔 파일이 없어
		// "applied not resolved locally"로 기동이 막힌다 — missing만 검증 완화(적용 스킵 아님).
		// §4-5 번호대 예약 컨벤션과 한 쌍. 공유 dev DB 한정 양보 —
		// 클라우드 타깃(application-cloud.yml)은 false로 엄격 검증한다 (ARCHITECTURE §8 해소).
		if (ignoreMissing) {
			configuration.ignoreMigrationPatterns("*:missing");
		}
		return configuration.load();
	}
```

import 추가: `import org.springframework.beans.factory.annotation.Value;`

- [ ] **Step 2: cloud 프로파일 작성**

`analytics/src/main/resources/application-cloud.yml`:

```yaml
# 클라우드 타깃 — 로컬 raw를 읽어 오라클 analysis DB(SSH 터널 localhost:15432)에 Flyway+미러 push.
# 사용법: deploy/scripts/tunnel.sh <ssh-host> 로 터널을 연 뒤
#   CLOUD_DB_PASSWORD=... ./gradlew :analytics:bootRun --args='--spring.profiles.active=cloud'
analytics:
  flyway-ignore-missing: false   # 클라우드 DB엔 이 repo 마이그레이션만 존재 — 엄격 검증
app:
  datasource:
    analysis:
      jdbc-url: jdbc:postgresql://localhost:15432/analysis
      username: ${CLOUD_DB_USER:celfit}
      password: ${CLOUD_DB_PASSWORD}
```

- [ ] **Step 3: 컴파일·기존 테스트 회귀 확인**

Run: `./gradlew :analytics:test`
Expected: BUILD SUCCESSFUL (기본값 true라 기존 dev 동작 무변경)

- [ ] **Step 4: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/config/FlywayConfig.java analytics/src/main/resources/application-cloud.yml
git commit -m "feat(analytics): 클라우드 타깃(cloud) 프로파일 — 터널 경유 미러 push, Flyway missing 완화는 dev 국한"
```

---

### Task 3: was 컨테이너화 (Dockerfile + plain jar 비활성 + 스모크)

**Files:**
- Create: `was/Dockerfile`
- Modify: `was/build.gradle` (plain jar 비활성 — `build/libs`에 jar 1개만 남겨 COPY 와일드카드 안전화)

- [ ] **Step 1: plain jar 비활성**

`was/build.gradle` 맨 아래(`tasks.named('test')` 블록 뒤)에 추가:

```groovy
// bootJar만 산출 — Dockerfile의 COPY build/libs/*.jar 가 단일 파일을 보장
tasks.named('jar') {
	enabled = false
}
```

- [ ] **Step 2: Dockerfile 작성**

`was/Dockerfile`:

```dockerfile
# 빌드 컨텍스트 = was/ (jar는 호스트에서 ./gradlew :was:bootJar 로 먼저 빌드 — arch 중립)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

- [ ] **Step 3: jar 빌드 후 산출물이 1개인지 확인**

Run: `./gradlew :was:clean :was:bootJar && ls was/build/libs/`
Expected: `was-0.0.1-SNAPSHOT.jar` 하나만

- [ ] **Step 4: 이미지 빌드 + 스모크 (로컬 DB 연결)**

Run:
```bash
docker build -t hypenow-was:smoke was
docker run -d --rm --name was-smoke -p 18081:8081 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5433/analysis \
  -e DB_USER=crawler -e DB_PASSWORD=crawler hypenow-was:smoke
sleep 20 && curl -s localhost:18081/health; docker stop was-smoke
```
Expected: `{"status":"ok","service":"was"}`
(참고: 이 맥의 Docker는 colima — `host.docker.internal`이 안 풀리면 `192.168.5.2`로 대체)

- [ ] **Step 5: Commit**

```bash
git add was/Dockerfile was/build.gradle
git commit -m "feat(was): 컨테이너화 — temurin 21 JRE 이미지, plain jar 비활성"
```

---

### Task 4: `deploy/` 스택 정의 (compose + Caddyfile + .env.example)

**Files:**
- Create: `deploy/compose.yaml`
- Create: `deploy/Caddyfile`
- Create: `deploy/.env.example`

- [ ] **Step 1: compose.yaml 작성**

```yaml
# 운영 스택 — 오라클 A1이든 폴백 VPS든 동일 (사용법: deploy/README.md)
services:
  postgres:
    image: postgres:17-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: analysis
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    ports:
      - "127.0.0.1:5432:5432"   # 루프백 전용 — 외부 접속은 SSH 터널로만
    volumes:
      - pg-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER} -d analysis"]
      interval: 10s
      timeout: 5s
      retries: 5

  was:
    image: ghcr.io/subtle-madness/hypenow-was:latest
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_URL: jdbc:postgresql://postgres:5432/analysis
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD}
      # 상주 메모리 확보 — 오라클 무료 계정 유휴 회수(메모리 10% 미만 조건) 방어 겸 여유
      JAVA_OPTS: "-Xms2g -Xmx2g -XX:+AlwaysPreTouch"
    depends_on:
      postgres:
        condition: service_healthy

  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    environment:
      API_DOMAIN: ${API_DOMAIN}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
      - caddy-config:/config

volumes:
  pg-data:
  caddy-data:
  caddy-config:
```

- [ ] **Step 2: Caddyfile 작성**

```
{$API_DOMAIN} {
	reverse_proxy was:8081
}
```

- [ ] **Step 3: .env.example 작성**

```
# 서버의 deploy/.env 로 복사해 실제 값 기입 (deploy/.env 는 커밋 금지)
DB_USER=celfit
DB_PASSWORD=change-me
API_DOMAIN=api.hypenow.io
```

- [ ] **Step 4: compose 문법 검증**

Run: `cd deploy && cp .env.example .env && docker compose config >/dev/null && echo OK && rm .env && cd ..`
Expected: `OK`

- [ ] **Step 5: Commit**

```bash
git add deploy/compose.yaml deploy/Caddyfile deploy/.env.example
git commit -m "feat(deploy): 운영 compose 스택 — postgres(루프백)+was+caddy(HTTPS)"
```

---

### Task 5: 서버 셋업·운영 스크립트 5종

**Files:**
- Create: `deploy/setup-server.sh` (서버 1회 셋업)
- Create: `deploy/scripts/deploy.sh` (맥: 빌드→push→서버 재기동)
- Create: `deploy/scripts/tunnel.sh` (맥: DB SSH 터널)
- Create: `deploy/scripts/backup.sh` (서버: 일일 덤프, cron)
- Create: `deploy/scripts/pull-backup.sh` (맥: 최신 덤프 가져오기)

- [ ] **Step 1: setup-server.sh**

```bash
#!/usr/bin/env bash
# 새 Ubuntu 서버 1회 셋업 — docker 설치 + 방화벽 개방 + 백업 크론.
# 오라클 Ubuntu 이미지는 기본 iptables가 22 외 전부 REJECT — ufw 대신 iptables에 직접 개방한다.
set -euo pipefail

# 1) docker
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker "$USER"
fi

# 2) 방화벽 — 80/443만 추가 개방 (OCI Security List와 이중 방어. 5432는 열지 않는다)
sudo iptables -C INPUT -p tcp -m multiport --dports 80,443 -j ACCEPT 2>/dev/null \
  || sudo iptables -I INPUT -p tcp -m multiport --dports 80,443 -j ACCEPT
sudo apt-get update -y && sudo apt-get install -y iptables-persistent rclone
sudo netfilter-persistent save

# 3) 백업 크론 (서버 UTC 19:10 = KST 04:10, 스크립트 경로는 ~/deploy 기준)
mkdir -p "$HOME/backups"
( crontab -l 2>/dev/null | grep -v 'scripts/backup.sh' || true ;
  echo "10 19 * * * $HOME/deploy/scripts/backup.sh >> $HOME/backups/backup.log 2>&1" ) | crontab -

echo "셋업 완료 — 재로그인(docker 그룹) 후 deploy/.env 채우고 'docker compose up -d'"
```

- [ ] **Step 2: scripts/deploy.sh**

```bash
#!/usr/bin/env bash
# 맥에서 실행: jar 빌드 → multi-arch 이미지 push → 서버 pull·재기동
# 사용법: deploy/scripts/deploy.sh <ssh-host>   (예: ubuntu@api.hypenow.io)
set -euo pipefail
HOST="${1:?사용법: deploy.sh <ssh-host>}"
IMAGE=ghcr.io/subtle-madness/hypenow-was:latest
cd "$(git rev-parse --show-toplevel)"
./gradlew :was:bootJar
# multi-arch 빌더 준비 — 기본 docker 드라이버는 멀티 플랫폼 push 불가 (1회 생성 후 재사용)
docker buildx inspect hypenow-multiarch >/dev/null 2>&1 \
  || docker buildx create --name hypenow-multiarch --driver docker-container
docker buildx build --builder hypenow-multiarch --platform linux/arm64,linux/amd64 -t "$IMAGE" --push was
ssh "$HOST" 'cd ~/deploy && docker compose pull was && docker compose up -d && docker compose ps'
```

- [ ] **Step 3: scripts/tunnel.sh**

```bash
#!/usr/bin/env bash
# 오라클 analysis DB로 SSH 터널 — analytics cloud 프로파일·psql용 (localhost:15432)
# 사용법: deploy/scripts/tunnel.sh <ssh-host>
set -euo pipefail
HOST="${1:?사용법: tunnel.sh <ssh-host>}"
echo "localhost:15432 → $HOST 의 postgres(루프백). 종료: Ctrl-C"
ssh -N -L 15432:127.0.0.1:5432 "$HOST"
```

- [ ] **Step 4: scripts/backup.sh**

```bash
#!/usr/bin/env bash
# 서버에서 실행(크론): analysis DB 일일 pg_dump — 서버 7일 롤링 + Google Drive 오프사이트(30일 롤링)
set -euo pipefail
BACKUP_DIR="$HOME/backups"
DEPLOY_DIR="$HOME/deploy"
set -a; source "$DEPLOY_DIR/.env"; set +a
STAMP="$(date +%Y%m%d-%H%M%S)"
cd "$DEPLOY_DIR"
docker compose exec -T postgres pg_dump -U "$DB_USER" -d analysis \
  | gzip > "$BACKUP_DIR/analysis-$STAMP.sql.gz"
ls -1t "$BACKUP_DIR"/analysis-*.sql.gz | tail -n +8 | xargs -r rm

# 오프사이트: rclone gdrive 리모트가 설정돼 있으면 업로드 (설정 절차는 README §6-1)
if command -v rclone >/dev/null 2>&1 && rclone listremotes 2>/dev/null | grep -q '^gdrive:'; then
  rclone copy "$BACKUP_DIR/analysis-$STAMP.sql.gz" gdrive:hypenow-backups/
  rclone delete --min-age 30d gdrive:hypenow-backups/
  echo "Drive 업로드 완료: analysis-$STAMP.sql.gz"
else
  echo "경고: rclone gdrive 리모트 미설정 — 오프사이트 백업 건너뜀" >&2
fi
echo "백업 완료: analysis-$STAMP.sql.gz"
```

- [ ] **Step 5: scripts/pull-backup.sh**

```bash
#!/usr/bin/env bash
# 맥에서 실행: 서버의 최신 덤프를 로컬로 — 오라클 계정이 사라져도 사본은 손안에
# 사용법: deploy/scripts/pull-backup.sh <ssh-host>
set -euo pipefail
HOST="${1:?사용법: pull-backup.sh <ssh-host>}"
DEST="$HOME/backups/hypenow"
mkdir -p "$DEST"
LATEST="$(ssh "$HOST" 'ls -1t ~/backups/analysis-*.sql.gz | head -1')"
scp "$HOST:$LATEST" "$DEST/"
echo "가져옴: $DEST/$(basename "$LATEST")"
```

- [ ] **Step 6: 실행권한 + 문법 검증**

Run:
```bash
chmod +x deploy/setup-server.sh deploy/scripts/*.sh
for f in deploy/setup-server.sh deploy/scripts/*.sh; do bash -n "$f" && echo "OK $f"; done
```
Expected: 5줄 모두 `OK`

- [ ] **Step 7: Commit**

```bash
git add deploy/setup-server.sh deploy/scripts
git commit -m "feat(deploy): 서버 셋업·배포·터널·백업 스크립트 5종"
```

---

### Task 6: `deploy/README.md` 운영 런북

**Files:**
- Create: `deploy/README.md`

- [ ] **Step 1: 런북 작성** — 아래 목차·핵심 내용을 담는다 (문장은 다듬어도 되나 절차·명령은 그대로):

````markdown
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
- LLM 배치(analyze/account-analyze)도 같은 방식으로 `--analytics.*-on-startup=true` 플래그로 실행

## 5. 배포 (코드 변경 반영)
```bash
deploy/scripts/deploy.sh ubuntu@<IP>
```

## 6. 백업·복원
- 자동: 서버 크론이 매일 KST 04:10 덤프 — 서버 `~/backups/` 7일 롤링 + **Google Drive
  `hypenow-backups/` 30일 롤링** (맥·서버 어느 쪽이 꺼져 있든 오프사이트 사본 유지)
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

## 9. 이사 절차 (오라클 → 아무 VPS, 목표 30분)
1. 새 Ubuntu 서버: §3 최초 기동 그대로 (rsync → setup → .env → up)
2. 데이터: `pull-backup.sh`의 최신 덤프를 새 서버에 넣고
   `cd ~/deploy && set -a && source .env && set +a` 후
   `gunzip -c dump.sql.gz | docker compose exec -T postgres psql -U $DB_USER -d analysis`
   (또는 로컬 raw에서 미러 재실행 — §4)
3. DNS A레코드를 새 IP로 변경 → caddy가 인증서 자동 재발급
````

- [ ] **Step 2: Commit**

```bash
git add deploy/README.md
git commit -m "docs(deploy): 운영 런북 — 인스턴스 생성~기동~배포~백업~이사 절차"
```

---

### Task 7: ARCHITECTURE.md 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§7 결정 기록 맨 위에 한 줄 추가, §8 "Flyway missing 완화 국한" 행 갱신, 마지막 갱신 날짜)

- [ ] **Step 1: §7 결정 기록 맨 위에 추가**

```markdown
| 2026-07-15 | **was+DB 오라클 배포 체계 + 도메인 hypenow.io** — 배포 범위는 was+analysis DB만(크롤·분석은 로컬 유지, 미러가 SSH 터널로 push). 오라클 A1 무료(도쿄, 2/12) + docker compose 3컨테이너(postgres 루프백/was/caddy HTTPS), 이미지 GHCR multi-arch로 타사 30분 이사 가능 구조. 일일 pg_dump+맥 pull 백업. 도메인 확보: 프론트 `www.hypenow.io`(Vercel) / API `api.hypenow.io` — 프론트 연동은 Vercel rewrite로 같은 오리진화(CSRF 쿠키), prod CORS는 www.hypenow.io만. was `prod`·analytics `cloud` 프로파일 신설 — Flyway `*:missing` 완화는 dev 기본값으로 국한(§8 해소) | [specs/2026-07-15-oracle-deploy-design.md](docs/superpowers/specs/2026-07-15-oracle-deploy-design.md) |
```

- [ ] **Step 2: §8 미결 표의 "Flyway missing 완화 국한" 행을 갱신**

기존 행을 다음으로 교체:

```markdown
| ~~Flyway missing 완화 국한~~ | 해소(07-15) — 완화를 프로퍼티(`analytics.flyway-ignore-missing`, dev 기본 true)로 전환, 클라우드 타깃은 false 엄격 검증 |
```

- [ ] **Step 3: §1의 프론트 표기 갱신**

`프론트: celfit-front.vercel.app (별도 저장소)` → `프론트: www.hypenow.io (Vercel, 별도 저장소 celfit-front)`

- [ ] **Step 4: 문서 머리 "마지막 갱신"을 2026-07-15로 수정 후 Commit**

```bash
git add ARCHITECTURE.md
git commit -m "docs: 오라클 배포 결정 기록 + Flyway 완화 국한 미결 해소"
```

---

## 실 배포 런북 (계획 실행 후 — 사용자와 함께, 수동)

코드 산출물이 머지되면 `deploy/README.md` §0~§4 순서로 진행한다. 사용자 직접 단계:
오라클 가입(도쿄)·인스턴스 생성·DNS A레코드·GHCR PAT. 이후 함께: 최초 기동 → 터널로
Flyway+미러 → `https://api.hypenow.io/health` 확인 → 프론트 rewrite 연동 → 로그인·저장 E2E →
rclone(Google Drive) 설정 → 백업 크론 첫 실행·Drive 도착 확인 → 복원 리허설 1회 →
(안정화 후) PAYG 전환 + Budget 알림. 2단계(크롤·분석 파이프라인 이전)는 스펙 §8 — 별도 계획으로.
