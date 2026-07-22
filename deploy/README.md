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

## 5. 배포 (코드 변경 반영)

**정본은 CD (07-20~)**: `main`에 푸시(=develop→main 머지)하면 `.github/workflows/cd.yml`이
was·analytics·crawler 이미지 빌드·push → 서버 compose pull·재기동 → **분석 뷰 raw DB 적용**(멱등,
§4-1의 수동 절차를 대체) → `/health` 확인까지 수행한다.
- 필요 시크릿(GitHub → Settings → Secrets and variables → Actions):
  `DEPLOY_SSH_KEY`(서버 ssh 개인키), `DEPLOY_HOST`(서버 호스트/IP — ssh 사용자는 ubuntu)
- 컬럼 이름·타입이 바뀌는 뷰 변경은 `CREATE OR REPLACE` 불가 — 해당 SQL에 `DROP VIEW` 포함 필요

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
  **컨테이너 다운**(deploy-*-1 6종), **디스크 85%**, **버킷 15GB**(무료 티어 20GiB 한도)
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
