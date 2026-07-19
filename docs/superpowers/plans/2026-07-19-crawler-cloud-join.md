# 크롤러 클라우드 합류 구현 계획 (최소 범위)

> 상태: 🟢 활성
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** crawler를 오라클 인스턴스의 6번째 컨테이너로 합류시킨다 — 지금 필요한 것만: 백업 개통, 블록 볼륨(용량·격리), crawler 배포, raw DB 이사, 이미지 버킷.

**Architecture:** 스펙 [2026-07-19-crawler-cloud-join-design.md](../specs/2026-07-19-crawler-cloud-join-design.md). 레포 변경(Task 1~5)과 서버·클라우드 적용(Task 6)을 분리 — 적용 단계는 **사용자 승인 게이트** 뒤에서만 실행.

**Tech Stack:** docker compose, buildx multi-arch(arm64+amd64), GHCR, OCI CLI(`--profile HYPENOW`), pg_dump/psql, rclone

## Global Constraints

- **클라우드 리소스·서버 상태 변경은 사용자 승인 후에만 실행** (블록 볼륨, 버킷, 서버 compose 적용, DB 이사)
- **`postgres`(analysis) 컨테이너는 무변경·무재시작** — compose 설정을 일절 바꾸지 않는다 (사용자 방침 07-19)
- **최적화 제외(사용자 방침 07-19)**: 컨테이너 `mem_limit`, `shared_buffers` 튜닝 등은 이 계획에서 하지 않는다 — 후속(§보류 항목)
- 커밋 메시지는 한국어, prefix `feat(deploy):`/`docs:` (CLAUDE.md)
- 배포는 락스텝: was·crawler·analytics 3이미지를 같은 SHA로 (스펙 §4)
- crawler 8080은 `127.0.0.1` 바인드만 — 공개 노출 금지

---

### Task 1: crawler Dockerfile + compose 서비스 추가

**Files:**
- Create: `crawler/Dockerfile`
- Modify: `deploy/compose.yaml` (crawler 서비스 추가)
- Modify: `deploy/.env.example` (crawler용 키 추가)

**Interfaces:**
- Produces: compose 서비스명 `crawler` (이미지 `ghcr.io/subtle-madness/hypenow-crawler`) — Task 2·6이 사용

- [ ] **Step 1: crawler/Dockerfile 작성** (was/analytics 패턴 그대로, 포트만 8080)

```dockerfile
# 빌드 컨텍스트 = crawler/ (jar는 호스트에서 ./gradlew :crawler:bootJar 로 먼저 빌드 — arch 중립)
FROM eclipse-temurin:21-jre
# non-root 실행 — was와 동일 규율. 크롤러는 디스크 쓰기 없음(적재는 DB로만).
RUN useradd --system --no-create-home --shell /usr/sbin/nologin crawler
USER crawler
WORKDIR /app
COPY build/libs/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

- [ ] **Step 2: deploy/compose.yaml에 crawler 서비스 추가** — `analytics` 서비스 블록 바로 아래에 삽입.
  (JAVA_OPTS는 analytics와 같은 관용구의 힙 크기 지정일 뿐 — 별도 튜닝 아님)

```yaml
  # 수집 상주 서버(8080 어드민 /ui) — raw(postgres-raw)에 쓰기. 수동 운영: 어드민 UI로 잡 트리거.
  # 외부 미노출(루프백) — 접근은 SSH 터널(ssh -L 8080:localhost:8080). 스케줄은 기본 off(수동 운영).
  crawler:
    image: ghcr.io/subtle-madness/hypenow-crawler:latest
    restart: unless-stopped
    logging: *logging
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-raw:5432/crawler
      SPRING_DATASOURCE_USERNAME: ${RAW_DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${RAW_DB_PASSWORD}
      APIFY_TOKEN: ${APIFY_TOKEN}
      HIKER_API_KEY: ${HIKER_API_KEY:-}
      DATALIKERS_API_KEY: ${DATALIKERS_API_KEY:-}
      GEMINI_API_KEY: ${GEMINI_API_KEY}
      APIFY_PROXY_URL: ${APIFY_PROXY_URL:-}
      DATAIMPULSE_RESIDENTIAL_PROXY_URL: ${DATAIMPULSE_RESIDENTIAL_PROXY_URL:-}
      DATAIMPULSE_MOBILE_PROXY_URL: ${DATAIMPULSE_MOBILE_PROXY_URL:-}
      JAVA_OPTS: "-Xmx1g"
    ports:
      - "127.0.0.1:8080:8080"   # 루프백 전용 — 어드민은 SSH 터널로만
    depends_on:
      postgres-raw:
        condition: service_healthy
```

- [ ] **Step 3: deploy/.env.example에 crawler 키 추가** — 파일 끝에 삽입

```bash
# crawler (수집) — Apify 필수, 나머지는 쓰는 소스만
APIFY_TOKEN=
HIKER_API_KEY=
DATALIKERS_API_KEY=
APIFY_PROXY_URL=
DATAIMPULSE_RESIDENTIAL_PROXY_URL=
DATAIMPULSE_MOBILE_PROXY_URL=
```
(`GEMINI_API_KEY`·`RAW_DB_USER`·`RAW_DB_PASSWORD`는 기존 항목 재사용 — 없으면 함께 추가)

- [ ] **Step 4: 검증 — compose 문법·변수 확인**

Run: `cd deploy && docker compose --env-file .env.example config --quiet && echo OK`
Expected: `OK` (빈값 경고는 무해)

- [ ] **Step 5: 검증 — crawler 이미지 로컬 빌드 스모크** (push 없이 단일 arch)

Run: `./gradlew :crawler:bootJar && docker build -t crawler-smoke crawler/ && docker run --rm crawler-smoke sh -c 'ls /app/app.jar'`
Expected: `/app/app.jar`

- [ ] **Step 6: Commit**

```bash
git add crawler/Dockerfile deploy/compose.yaml deploy/.env.example
git commit -m "feat(deploy): crawler 상주 컨테이너 — Dockerfile·compose 서비스(루프백 8080, postgres-raw 연결)"
```

---

### Task 2: deploy.sh에 crawler 포함 + 런북 갱신

**Files:**
- Modify: `deploy/scripts/deploy.sh:7,10`
- Modify: `deploy/README.md`

**Interfaces:**
- Consumes: Task 1의 `crawler` 서비스명·`hypenow-crawler` 이미지명
- Produces: `deploy.sh <host>`가 3이미지 락스텝 배포

- [ ] **Step 1: deploy.sh 기본 서비스에 crawler 추가**

10행:
```bash
if [ ${#SERVICES[@]} -eq 0 ]; then SERVICES=(was analytics crawler); fi
```
7행 사용법 문자열 갱신: `[was|analytics|crawler …]`

- [ ] **Step 2: deploy/README.md에 crawler 절차 추가** — §3 최초 기동 아래에 삽입

```markdown
## 3-1. crawler 수동 운영
- 어드민 UI: `ssh -L 8080:localhost:8080 ubuntu@<IP>` 후 브라우저 `http://localhost:8080/ui`
- 잡 실행은 UI 버튼(수동 운영 — schedule.enabled 기본 off). 로그: `docker compose logs -f crawler`
- 필요 키: 서버 `~/deploy/.env`의 APIFY_TOKEN(필수)·HIKER_API_KEY·GEMINI_API_KEY 등 (.env.example 참조)
```

- [ ] **Step 3: 검증**

Run: `bash -n deploy/scripts/deploy.sh && grep -n "was analytics crawler" deploy/scripts/deploy.sh`
Expected: 문법 오류 없음 + 10행 매치

- [ ] **Step 4: Commit**

```bash
git add deploy/scripts/deploy.sh deploy/README.md
git commit -m "feat(deploy): 배포 기본 서비스에 crawler 추가 — 3이미지 락스텝 + 수동 운영 런북"
```

---

### Task 3: 블록 볼륨 이전 — compose 볼륨 교체 + 서버 절차 스크립트

**Files:**
- Modify: `deploy/compose.yaml` (postgres-raw 볼륨을 bind mount로 — 이 외 postgres-raw 설정 무변경)
- Create: `deploy/scripts/attach-raw-volume.sh`

**Interfaces:**
- Consumes: Task 1까지의 compose
- Produces: postgres-raw 데이터 경로 `/mnt/raw/pgdata` — Task 6이 서버에서 실행

- [ ] **Step 1: compose의 postgres-raw 볼륨을 bind로 교체**

```yaml
  postgres-raw:
    volumes:
      - /mnt/raw/pgdata:/var/lib/postgresql/data   # 블록 볼륨 — raw 성장이 부트볼륨(OS·서빙)을 침범 못 하게
```
`volumes:` 최상위 목록에서 `pg-raw-data:` 항목 제거.

- [ ] **Step 2: attach-raw-volume.sh 작성** (서버에서 1회 실행)

```bash
#!/usr/bin/env bash
# 서버에서 실행: 부착된 블록 볼륨을 /mnt/raw로 마운트하고 postgres-raw 데이터를 이전한다.
# 전제: 오라클 콘솔/CLI에서 100GB 블록 볼륨을 paravirtualized로 인스턴스에 부착해둔 상태.
set -euo pipefail
DEV="${1:?사용법: attach-raw-volume.sh <디바이스 경로 예:/dev/oracleoci/oraclevdb>}"
sudo mkfs.ext4 -L rawdata "$DEV"          # 신규 볼륨 전제 — 기존 데이터 있으면 여기서 중단할 것
sudo mkdir -p /mnt/raw
echo "LABEL=rawdata /mnt/raw ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab
sudo mount /mnt/raw
cd ~/deploy
docker compose stop postgres-raw
# named volume(deploy_pg-raw-data) → 블록 볼륨으로 복사 (권한·소유자 보존)
sudo rsync -a "$(docker volume inspect deploy_pg-raw-data -f '{{.Mountpoint}}')/" /mnt/raw/pgdata/
docker compose up -d postgres-raw
docker compose exec postgres-raw pg_isready -U "$(grep ^RAW_DB_USER .env | cut -d= -f2)" -d crawler
echo "이전 완료 — 검증 후 구 볼륨 삭제: docker volume rm deploy_pg-raw-data"
```

- [ ] **Step 3: 검증**

Run: `bash -n deploy/scripts/attach-raw-volume.sh && chmod +x deploy/scripts/attach-raw-volume.sh`
Expected: 문법 오류 없음

- [ ] **Step 4: Commit**

```bash
git add deploy/compose.yaml deploy/scripts/attach-raw-volume.sh
git commit -m "feat(deploy): postgres-raw를 블록 볼륨(/mnt/raw)으로 — 마운트·데이터 이전 스크립트"
```

---

### Task 4: raw DB 이사 스크립트 (로컬 → 서버, run_item 중복분 제외)

**Files:**
- Create: `deploy/scripts/migrate-raw-db.sh`

**Interfaces:**
- Consumes: 로컬 `hypenow-crawler-postgres-1`(5433), 서버 postgres-raw(터널 15433)
- Produces: 서버 crawler DB에 로컬 데이터 복원 — Task 6에서 실행

- [ ] **Step 1: migrate-raw-db.sh 작성**

```bash
#!/usr/bin/env bash
# 맥에서 실행: 로컬 raw DB를 서버 postgres-raw로 이사한다.
# COLLECT·REELS·RESNAPSHOT의 raw_run_item(이중 저장분 ~2.1GB)은 제외 — ARCHITECTURE §7 07-19 결정.
# 전제: 터미널 1에서 터널 유지 — ssh -L 15433:localhost:5433 ubuntu@<IP>
set -euo pipefail
LOCAL_CONTAINER=hypenow-crawler-postgres-1
REMOTE_USER="${RAW_DB_USER:?서버 .env의 RAW_DB_USER를 export 할 것}"
REMOTE_PW="${RAW_DB_PASSWORD:?서버 .env의 RAW_DB_PASSWORD를 export 할 것}"
REMOTE="postgresql://$REMOTE_USER:$REMOTE_PW@localhost:15433/crawler"
WORK="$(mktemp -d)"

# 0) 안전 가드 — 서버 DB가 비어있지 않으면 중단 (기존 데이터 덮어쓰기 방지)
if psql "$REMOTE" -t -c "SELECT count(*) FROM content" 2>/dev/null | grep -qv '^ *0$'; then
  echo "중단: 서버 crawler DB에 데이터가 있음 — 상태 확인 후 수동 결정 필요" >&2; exit 1
fi

# 1) 전체 덤프 — raw_run_item은 데이터 제외(스키마는 포함)
docker exec $LOCAL_CONTAINER pg_dump -U crawler -d crawler \
  --exclude-table-data=raw_run_item -Fc -f /tmp/raw-migration.dump
docker cp $LOCAL_CONTAINER:/tmp/raw-migration.dump "$WORK/"

# 2) run_item 보존분(타입 테이블 없는 잡)만 별도 내보내기
docker exec $LOCAL_CONTAINER psql -U crawler -d crawler -c "\
  \copy (SELECT i.* FROM raw_run_item i JOIN crawl_run r ON r.id=i.crawl_run_id \
         WHERE r.job NOT IN ('COLLECT','REELS','RESNAPSHOT')) TO '/tmp/run-items-keep.tsv'"
docker cp $LOCAL_CONTAINER:/tmp/run-items-keep.tsv "$WORK/"

# 3) 서버 복원 (빈 crawler DB 전제 — crawler 컨테이너는 정지 상태일 것)
pg_restore -d "$REMOTE" --no-owner --role="$REMOTE_USER" "$WORK/raw-migration.dump"
psql "$REMOTE" -c "\copy raw_run_item FROM '$WORK/run-items-keep.tsv'"
psql "$REMOTE" -c "SELECT setval('raw_run_item_id_seq', (SELECT coalesce(max(id),1) FROM raw_run_item));"

# 4) 행 수 대조
for t in influencer content raw_profile raw_media_page crawl_run app_setting; do
  L=$(docker exec $LOCAL_CONTAINER psql -U crawler -d crawler -t -c "SELECT count(*) FROM $t")
  R=$(psql "$REMOTE" -t -c "SELECT count(*) FROM $t")
  echo "$t: local=$L remote=$R"
done
echo "확인: 위 카운트가 전부 일치해야 함 (raw_run_item은 제외분만큼 적은 것이 정상)"
```

- [ ] **Step 2: 검증**

Run: `bash -n deploy/scripts/migrate-raw-db.sh && chmod +x deploy/scripts/migrate-raw-db.sh`
Expected: 문법 오류 없음

- [ ] **Step 3: Commit**

```bash
git add deploy/scripts/migrate-raw-db.sh
git commit -m "feat(deploy): raw DB 이사 스크립트 — run_item 중복분 제외 덤프·복원·행수 대조"
```

---

### Task 5: Object Storage 버킷 준비 스크립트 + ARCHITECTURE 갱신

**Files:**
- Create: `deploy/scripts/setup-media-bucket.sh`
- Modify: `ARCHITECTURE.md` §2·§7

- [ ] **Step 1: setup-media-bucket.sh 작성**

```bash
#!/usr/bin/env bash
# 맥에서 실행 (oci --profile HYPENOW): 이미지 버킷 + 인스턴스 업로드 권한(인스턴스 프린시펄).
# ⚠️ 클라우드 리소스 생성 — 사용자 승인 후 실행할 것.
set -euo pipefail
PROFILE=HYPENOW
TENANCY=$(oci iam availability-domain list --profile $PROFILE --query 'data[0]."compartment-id"' --raw-output)

# 1) 버킷 — 객체 읽기 공개(FE 서빙: Vercel rewrite가 GET), 목록은 비공개
oci os bucket create --profile $PROFILE --compartment-id "$TENANCY" \
  --name hypenow-media --public-access-type ObjectRead

# 2) 동적 그룹 — 이 테넌시의 인스턴스(현재 hypenow-api 1대)
oci iam dynamic-group create --profile $PROFILE \
  --name hypenow-instances --description "hypenow 인스턴스 (Object Storage 업로드용)" \
  --matching-rule "ANY {instance.compartment.id = '$TENANCY'}"

# 3) 정책 — 인스턴스가 media 버킷 객체를 관리(업로드·교체)
oci iam policy create --profile $PROFILE --compartment-id "$TENANCY" \
  --name hypenow-media-upload --description "인스턴스의 hypenow-media 업로드" \
  --statements '["Allow dynamic-group hypenow-instances to manage objects in tenancy where target.bucket.name='"'"'hypenow-media'"'"'"]'

NS=$(oci os ns get --profile $PROFILE --query data --raw-output)
echo "완료 — 객체 URL 형식: https://objectstorage.ap-tokyo-1.oraclecloud.com/n/$NS/b/hypenow-media/o/<객체명>"
```

- [ ] **Step 2: 검증**

Run: `bash -n deploy/scripts/setup-media-bucket.sh && chmod +x deploy/scripts/setup-media-bucket.sh`
Expected: 문법 오류 없음

- [ ] **Step 3: ARCHITECTURE.md 갱신** — §2의 배포 서술을 "전 모듈 오라클 인스턴스 1대(컨테이너 6개), 로컬 맥은 개발·복원 리허설 전용"으로, §7 맨 위에 결정 행 추가:

```markdown
| 2026-07-19 | **크롤러 클라우드 합류** — 인스턴스 1대 유지(무료 A1 전량·분리 안 함), crawler 상주 컨테이너(6번째, 루프백 8080 수동 운영), raw DB는 블록 볼륨 100GB로 격리(무료 200GB 전량), 배포는 was·crawler·analytics 3이미지 락스텝, 이미지 서빙은 OCI Object Storage+Vercel rewrite(버킷만 선행). analysis DB 컨테이너 무변경, 메모리 제한·튜닝은 보류, raw 장기 아카이빙은 후속 스펙 | [specs/2026-07-19-crawler-cloud-join-design.md](docs/superpowers/specs/2026-07-19-crawler-cloud-join-design.md) |
```

- [ ] **Step 4: Commit**

```bash
git add deploy/scripts/setup-media-bucket.sh ARCHITECTURE.md
git commit -m "feat(deploy): 이미지 버킷 준비 스크립트 + ARCHITECTURE 크롤러 합류 반영"
```

---

### Task 6: 서버·클라우드 적용 (전 단계 사용자 승인 게이트 — 순서 고정)

**Files:** 없음 (운영 실행 — 위 태스크 산출물 사용)

각 단계는 **개별 승인 후** 실행, 실패 시 다음 단계로 넘어가지 않는다.
`postgres`(analysis) 컨테이너는 어느 단계에서도 재생성되지 않는다 — compose 무변경.

- [ ] **Step 0: 오프사이트 백업 개통** (가장 급함 — app 스키마가 현재 단일 장애점)
  - 사용자: 맥에서 `brew install rclone && rclone config`(gdrive OAuth — 브라우저 승인)
  - 실행: `scp ~/.config/rclone/rclone.conf ubuntu@<IP>:~/.config/rclone/` → 서버 `rclone mkdir gdrive:hypenow-backups`
  - 검증: 서버 `~/deploy/scripts/backup.sh` 수동 1회 → `Drive 업로드 완료` 출력 + `rclone lsl gdrive:hypenow-backups`에 오늘 덤프
- [ ] **Step 1: 블록 볼륨 생성·부착** (승인 후 CLI/콘솔 — 100GB·AD-1·paravirtualized) → 서버에서 `deploy/scripts/attach-raw-volume.sh /dev/oracleoci/oraclevdb`. 검증: `docker inspect deploy-postgres-raw-1 | grep /mnt/raw` + `df -h /mnt/raw` + analytics의 raw 읽기 정상
- [ ] **Step 2: crawler 첫 배포** — 서버 `.env`에 APIFY_TOKEN 등 반입 → 맥에서 `rsync -av deploy/ ubuntu@<IP>:~/deploy/` → `deploy/scripts/deploy.sh ubuntu@<IP>` (3이미지). 검증: `docker compose ps` crawler Up + postgres(analysis) Created 시각 불변 + 터널로 `localhost:8080/ui` 접속
- [ ] **Step 3: raw DB 이사** — 로컬 크롤 중단 확인 → 서버 `docker compose stop crawler` → 터널(15433) → `deploy/scripts/migrate-raw-db.sh` → 행 수 대조 전부 일치 → `docker compose start crawler`. 로컬 DB는 검증 완료까지 보존
- [ ] **Step 4: 첫 서버 크롤 실행** — 어드민 UI에서 소량 잡(REELS batch-limit 기본 10) 수동 실행. 검증: postgres-raw 신규 행, `raw_run_item` 증가 0(A안 머지 후 이미지 기준), COLLECT의 KST 달력일 재방문이 로컬 이력에 이어짐(중복 방문 0), Hiker 과금 카운터 연속, 새벽 미러 후 analysis 반영, api.hypenow.io 정상
- [ ] **Step 5: Object Storage 버킷** — 승인 후 `deploy/scripts/setup-media-bucket.sh`. 검증: `oci os bucket get --bucket-name hypenow-media` + 더미 객체 업로드 후 공개 GET curl 200

---

## 보류 항목 (이 계획에서 명시적으로 하지 않는 것)

- **컨테이너 메모리 제한(`mem_limit`)·`shared_buffers` 튜닝** — 사용자 방침(07-19 "지금 최적화는 하지 말자"). 규모 확장·자동화 시점에 별도 승인으로
- analysis DB 컨테이너의 어떤 변경도 (무변경·무재시작 방침)
- 크롤 자동화(크론)·CD Actions 승격 — 스펙 §4 로드맵의 후속
- 이미지 파이프라인 코드·FE rewrite — 별도 태스크 (스펙 §7)

## 완료 기준

- [ ] 서버 어드민 UI로 크롤 → raw 적재 → 미러 → api.hypenow.io 서빙 체인 확인 (base 뷰 재작성 전에는 신규분 미노출이 정상)
- [ ] postgres-raw 데이터가 `/mnt/raw` 위 (디스크 격리)
- [ ] postgres(analysis) 컨테이너 무변경 확인 (Created 시각)
- [ ] Drive에 analysis 덤프 도착 (오프사이트 개통)
- [ ] 로컬 맥은 dev 전용으로 전환 (크롤 프로세스 없음)
