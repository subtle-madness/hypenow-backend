# 인스타 댓글 doc_id 자동 갱신자

## 무엇을·왜

monitoring 자체크롤(신 모듈 `instagram-source`, 브랜치 `feat/monitoring-comment-selfcrawl`,
미머지)이 댓글 딥 페이징(2페이지 이상, 45건 계약)에 쓰는 GraphQL `doc_id`는 IG가 2~4주
주기로 회전(만료)시킨다. 이 스크립트는 헤드리스 Playwright로 **로그아웃 상태에서 실제
페이징 요청을 가로채 doc_id를 캡처 → 그 값으로 페이징 1콜을 실제로 쳐서 200+edges 검증 →
검증 통과 시에만** monitoring DB `app_setting`에 upsert한다. 캡처·검증 실패 시 기존 값을
그대로 유지한다(자체크롤은 만료 전까지 정상 동작, 만료 후에도 1페이지 15건은 보존됨 —
데이터 유실 없음).

상세 설계: `docs/superpowers/specs/2026-09-01-ig-comment-docid-refresher-design.md`

monitoring 애플리케이션 코드·Flyway는 건드리지 않는다 — app_setting 키 이름만 공유한다.

## 로컬 실행법

```bash
cd deploy/docid-refresher
npm install
DATAIMPULSE_RESIDENTIAL_PROXY_URL="http://user:pass@host:port" \
MONITORING_DB_HOST=localhost \
MONITORING_DB_PORT=5433 \
MONITORING_DB_USER=... \
MONITORING_DB_PASSWORD=... \
DRY_RUN=true \
node refresh-docid.js
```

`DRY_RUN=true`면 캡처·검증까지만 하고 DB upsert는 생략한다(로그로 "would upsert ..."만
찍힘). 실제 반영을 확인하려면 `DRY_RUN` 미설정 또는 `false`로 실행.

### 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `DATAIMPULSE_RESIDENTIAL_PROXY_URL` | (필수) | `http://user:pass@host:port` — 데이터센터 IP는 로그아웃 쿼리에서 거부되므로 반드시 레지덴셜 |
| `MONITORING_DB_HOST` | `postgres` | monitoring DB 호스트 |
| `MONITORING_DB_PORT` | `5432` | monitoring DB 포트 |
| `MONITORING_DB_NAME` | `monitoring` | monitoring DB 이름 |
| `MONITORING_DB_USER` | (필수, DRY_RUN 아니면) | |
| `MONITORING_DB_PASSWORD` | (필수, DRY_RUN 아니면) | |
| `DOCID_TARGET_URLS` | 스크립트 내장 기본값(nasa 게시물 1건) | 콤마 구분 공개 게시물 URL 목록, 순차 폴백 |
| `DRY_RUN` | `false` | `true`면 upsert 생략, 캡처·검증 결과만 로그 |

## 운영 실행법

이미지는 `profiles: [tools]`로 두어 `docker compose up -d`엔 자동 기동되지 않는다. 수동/크론
실행만:

```bash
docker compose --profile tools run --rm docid-refresher
```

## 크론 등록 예시

주 2회(월·목) KST 05:30 = UTC 20:30 — crawler collect·monitoring 스윕·analytics 미러
윈도우가 모두 끝난 뒤라 자원 경합 없음(설계 스펙 §2-3). `deploy/setup-server.sh`의
`backup.sh` crontab 관용구와 동일 패턴:

```bash
0 20 * * 1,4 cd $HOME/deploy && docker compose build docid-refresher && docker compose --profile tools run --rm docid-refresher >> $HOME/docid-refresher.log 2>&1
```

## 실패 시 동작

- **캡처 실패**(전 타깃 URL에서 페이징 요청 미발화) 또는 **검증 실패**(HTTP 200이 아니거나
  edges가 0이거나 top-level errors 존재): app_setting **미변경**, non-zero exit.
- 크론이 주 2회이므로 한 번 실패해도 3~4일 내 자동 재시도된다.
- 값이 기존과 동일해도(회전 없음) upsert는 수행한다 — `ig-source.comment-doc-id-refreshed-at`
  갱신으로 "갱신자가 살아있고 doc_id가 아직 유효하다"는 관측 신호를 남긴다.

## 자격증명 취급 주의

프록시 URL과 DB 비밀번호는 **어떤 로그·에러 메시지에도 출력하지 않는다**(스크립트가 그렇게
작성돼 있음 — 프록시 파싱 실패 시에도 "proxy url 파싱 실패"만 출력). `.env`에만 두고 커밋하지
말 것.

## 운영 배치 (09-02 반영 완료)

`deploy/compose.yaml`에 아래 서비스 조각, `deploy/setup-server.sh`에 크론 등록 블록이 실제로
반영돼 있다. **CD는 이 디렉토리를 서버로 나르지 않는다** — `.github/workflows/cd.yml`의
`deploy` 잡은 compose.yaml·Caddyfile·grafana/prometheus/loki/alloy 설정·`scripts/{rollout,backup,
post-container-metrics}` 등 지정 파일만 scp한다(빌드 대상 4종은 GHA가 이미지를 빌드·push해
서버는 pull만). `docid-refresher`는 `build: ./docid-refresher`로 **서버가 직접 빌드**하므로,
소스 갱신(스크립트 수정 등)은 CD가 대신해주지 않는다 — `deploy/docid-refresher/`를 수동
scp(또는 rsync)로 서버에 동기화해야 반영된다. main 승격 후에도 이 갭은 남는다(향후 CD에 편입할
수 있으나 현재는 미편입).

```yaml
  # 인스타 댓글 doc_id 자동 갱신자 — one-shot, 호스트 크론이 트리거(README 참조).
  # profiles: [tools]로 up -d엔 절대 포함되지 않는다.
  docid-refresher:
    build: ./docid-refresher
    profiles: [tools]
    logging: *logging
    mem_limit: 1024m   # Chromium 헤드리스 실행 여유
    networks: [prod]   # postgres 접속. IG는 레지덴셜 프록시(IPv4) 경유라 v6egress 불필요
    environment:
      DATAIMPULSE_RESIDENTIAL_PROXY_URL: ${DATAIMPULSE_RESIDENTIAL_PROXY_URL:-}
      MONITORING_DB_HOST: postgres
      MONITORING_DB_PORT: "5432"
      MONITORING_DB_NAME: monitoring
      MONITORING_DB_USER: ${MONITORING_DB_USER}
      MONITORING_DB_PASSWORD: ${MONITORING_DB_PASSWORD}
    # restart 없음(one-shot) — depends_on도 두지 않는다(run --rm은 up -d 스택과 별개 실행이라
    # postgres가 이미 떠 있다는 전제, 크론 스크립트에서 postgres 헬시 확인은 불필요).
```

## crontab 라인 (setup-server.sh에 반영됨)

`deploy/setup-server.sh`의 `backup.sh` 등록 블록과 같은 패턴(재실행 시 멱등 — 기존 줄
제거 후 재등록):

```bash
( crontab -l 2>/dev/null | grep -v 'docid-refresher' || true ;
  echo "0 20 * * 1,4 cd $HOME/deploy && docker compose build docid-refresher && docker compose --profile tools run --rm docid-refresher >> $HOME/docid-refresher.log 2>&1" ) | crontab -
```
