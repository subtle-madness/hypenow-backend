# 인스타 댓글 doc_id 자동 갱신자(헤드리스 사이드카) 설계

> 상태: 🟢 활성 · 설계 확정, 구현 착수. 운영 배치·크론 등록은 사용자 승인 후.
> 작성: 2026-09-01

## 0. 한 줄 요약

monitoring 자체크롤이 댓글 딥 페이징(2p+, 45건 계약)에 쓰는 GraphQL **doc_id**는 IG가 2~4주
주기로 회전(만료)시킨다. 이 값을 **헤드리스 브라우저(Playwright)로 로그아웃 상태에서 실제
페이징 요청을 가로채 캡처 → 그 doc_id로 페이징 1콜을 실제로 쳐서 200+edges 검증 → 검증
통과 시에만 monitoring DB `app_setting`에 upsert**하는 one-shot 사이드카를, 운영 서버 호스트
cr론이 주 2회 트리거한다. monitoring 애플리케이션 코드는 수정하지 않는다(app_setting 키
이름만 공유).

## 1. 배경과 제약

### 1-1. 왜 필요한가 (원 트랙 이력 압축)

- monitoring 자체크롤(신 모듈 `instagram-source`, 브랜치 `feat/monitoring-comment-selfcrawl`,
  **미머지**)은 댓글을 3페이지=45건 수집한다. 1페이지는 게시물 SSR HTML 인라인 파싱(doc_id
  불필요), **2페이지+는 GraphQL POST 페이징인데 여기에 doc_id가 필수**다.
- doc_id는 어떤 JS 청크에도 정적 인라인되지 않고 IG 런타임 매니페스트 해석으로만 생긴다
  (09-01 실측: 옵션 C=정적 청크 마이닝 실증 탈락, 페이지 레벨 fetch 훅도 IG가 모듈 로드
  시점 바인딩으로 원천 우회). → **런타임 추출 = 사실상 헤드리스 브라우저가 유일**.
- 조달 방식은 **A(헤드리스 사이드카)로 확정**(재론 없음). B(monitoring 내장 Playwright-Java)는
  격리·메모리(analytics OOM 이력)·롤링 열위로 기각.

### 1-2. 소비 계약 (자체크롤 브랜치, 미머지 — 키 이름만 공유)

monitoring DB `app_setting` 테이블 `(key text PRIMARY KEY, value text NOT NULL)`:

| key | 현재 시드값 | 소비처 |
|---|---|---|
| `ig-source.comment-doc-id` | `27659279553772821` | `IgSourceSettings.commentDocId()` (5s TTL 캐시) |
| `ig-source.comment-friendly-name` | `PolarisLoggedOutDesktopWWWPostCommentsPaginationQuery` | `IgSourceSettings.commentFriendlyName()` |

- 시드는 자체크롤 브랜치 Flyway `V20260901060335__ig_source_comment_doc_id.sql`
  (`ON CONFLICT DO NOTHING`). app_setting에 값이 없으면 env 폴백
  (`InstagramProxyProperties.commentDocId/commentFriendlyName`, `monitoring.proxy.*`).
- 갱신자는 이 두 키를 **upsert**한다 — monitoring의 `AppSettingRepository.upsert`와 동일 관용구
  (`INSERT … ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value`). **키 존재를 가정하지
  않는다**(브랜치 미머지여도 테이블만 있으면 동작). friendly_name은 로그아웃 쿼리라 사실상
  불변이지만, 캡처값을 그대로 반영해 스킴 회전(2026-06 X-CSRFToken 신 스킴 같은)도 자동 추종.

### 1-3. 시스템 경계·컨벤션 준수

- **app_setting 규약(CLAUDE.md)**: "기준값은 Flyway 시드, 런타임 토글만 수동 UPDATE". doc_id
  회전 대응은 정확히 "런타임 값 교체" 범주 — 현재 수동 SQL UPDATE로 하던 것을 자동화한다.
  기준값(초기 시드)은 여전히 자체크롤 Flyway가 소유하고, 갱신자는 **런타임 UPDATE만** 한다.
- **monitoring DB 소유권**: app_setting은 monitoring DB 소속. 갱신자는 monitoring 시스템의
  운영 도구로서 같은 스택(prod 네트워크)에서 `postgres:5432/monitoring`에 직접 upsert한다.
  monitoring 애플리케이션 코드·Flyway는 **수정하지 않는다**(미머지 브랜치 독립성 유지 + was
  경계와 무관 — was는 monitoring DB 무권한).
- **crawler 무수정**(팀 소유). 갱신자는 monitoring 자체크롤만 대상으로 한다.
- **자격증명 비노출**: 프록시 URL·DB 비밀번호는 로그·커밋·에러 메시지에 절대 출력하지 않는다.

## 2. 형태 — one-shot 컨테이너 + 호스트 크론

### 2-1. 왜 one-shot인가 (상주 아님)

주 2회 몇 분 도는 작업에 Playwright 브라우저를 상주시키면 무의미하게 메모리를 점유한다.
오라클 VM(2CPU/12GB)에 이미 15개 서비스가 상주하고 analytics/monitoring OOM 이력이 있다.
→ analytics one-shot 미러 패턴과 동형으로 **호스트 crontab이 `docker compose run --rm`으로
트리거**하고, 실행 중에만 브라우저 메모리를 점유한 뒤 종료한다. B(monitoring 내장) 기각
사유(격리·메모리)와 정합한다.

### 2-2. 구성

```
deploy/docid-refresher/
├── refresh-docid.js     # 단일 Node 스크립트: 캡처 → 검증 → upsert
├── package.json         # playwright, pg
├── Dockerfile           # mcr.microsoft.com/playwright 베이스 + npm ci + 스크립트
└── README.md            # 운영·크론 등록·롤백·수동 실행
```

- **베이스 이미지**: `mcr.microsoft.com/playwright:v1.55.0-noble`(Chromium 브라우저 내장) —
  자체 브라우저 설치 불필요. Node 스크립트 하나 + `pg` 드라이버만 얹는다.
- **compose 서비스**(`deploy/compose.yaml`): `docid-refresher`를 **`profiles: [tools]`**로 추가해
  `up -d`엔 절대 자동 기동되지 않게 하고, `docker compose --profile tools run --rm docid-refresher`
  로만 실행. 네트워크 `[prod]`(postgres 접근), env는 `DATAIMPULSE_RESIDENTIAL_PROXY_URL`·
  `MONITORING_DB_USER`·`MONITORING_DB_PASSWORD`를 기존 .env에서 주입. `restart` 없음(one-shot).
- **이미지 빌드**: CD 파이프라인(ghcr 프리빌트)에 넣지 않는다 — 사이드카는 배포 산출물이
  아니라 운영 도구다. compose `build:`로 서버에서 직접 빌드하고(최초/스크립트 변경 시만,
  캐시 히트면 무비용), 크론 래퍼가 매 실행 전 `compose build`를 한 번 태워 드리프트를 없앤다.

### 2-3. 갱신 주기

- **주 2회**(월·목) KST 05:30(UTC 20:30) — crawler collect(01:00~03:30)·monitoring 스윕(02:00)·
  analytics 미러(04:30~) 윈도우가 모두 끝난 뒤라 자원 경합 없음. 만료 2~4주 대비 갱신 간격
  3~4일이면 만료 창을 최소 4~9회 앞질러 덮는다.
- **실패 시 재시도**: 크론이 주 2회라 한 번 실패해도 3~4일 내 자동 재시도. 추가로 스크립트
  내부에서 캡처를 **여러 타깃 게시물 URL로 순차 폴백**(한 게시물이 삭제·비활성이어도 다음
  게시물로) + 게시물당 소폭 재시도. 값이 이미 최신이면(회전 없음) no-op.

## 3. 스크립트 동작 (refresh-docid.js)

단일 Node 프로세스. 캡처와 검증을 **같은 Playwright 브라우저 컨텍스트**에서 수행해 프록시·
익명 쿠키·csrftoken·lsd를 그대로 승계한다(검증을 위한 별도 세션 재현이 불필요).

### 3-1. 캡처 (실측 debug2.js 기반)

1. Playwright Chromium **headless, 로그아웃**, 프록시 = DataImpulse 레지덴셜 + **geo:kr**
   (username suffix `__cr.kr`). 데이터센터 IP 직결은 "Unauthorized logged out query"로 거부되니
   반드시 레지덴셜 경유. UA=데스크톱 Chrome, locale ko-KR, tz Asia/Seoul.
2. `context.on('request')`로 `POST /api/graphql` 요청을 관찰. 공개 게시물 페이지
   (타깃 URL 배열 중 하나) 열기 → **가입 유도 모달 닫기**(모달이 스크롤을 가로채므로 닫아야
   페이징이 위임돼 발화) → 댓글 영역 스크롤(mouse.wheel 반복).
3. 발화한 요청 중 **`fb_api_req_friendly_name == PolarisLoggedOutDesktopWWWPostCommentsPaginationQuery`**
   인 것에서 `doc_id`를 캡처. (⚠️ **로그아웃 전용 쿼리** — 로그인 상태로 캡처하면 다른 쿼리
   `PolarisPostCommentsPaginationQuery`의 틀린 doc_id를 얻는다. 반드시 로그아웃.)
4. 하나도 못 잡으면 다음 타깃 게시물로. 전부 실패 시 캡처 실패(2절 재시도로 이월).

### 3-2. 검증 (필수 게이트 — 실측 verify_comment_paging.py 기반)

캡처한 doc_id를 **실제로 한 번 페이징 요청에 태워** 유효성을 확인한 뒤에만 반영한다.
틀린 값 반영이 최악(해당 게시물 영구 재수집 불가 — 원 트랙 "doc_id 가짜 완주" 결함과 동류)이다.

- 같은 브라우저 컨텍스트의 `context.request.post('https://www.instagram.com/api/graphql', …)`로
  폼 바디 `lsd` / `fb_api_req_friendly_name` / `doc_id`(캡처값) / `variables`(page1 SSR에서 뽑은
  `media_id` + `end_cursor`, `first:10`)를 전송. 헤더는 캡처 시 관찰된 스킴 그대로:
  `x-ig-app-id`, `x-fb-lsd`, **`X-CSRFToken`**(익명 csrftoken 쿠키), `X-FB-Friendly-Name`.
- **합격 조건**: HTTP 200 **AND** `data.xig_polaris_media.comments_connection.edges` 길이 > 0
  **AND** top-level `errors` 없음. 하나라도 어긋나면 검증 실패 → 반영 안 함.

### 3-3. 반영 (검증 통과 시에만)

- `pg`로 `postgres:5432/monitoring` 접속(user/pw는 env), 트랜잭션에서:
  - `ig-source.comment-doc-id` = 캡처·검증된 doc_id
  - `ig-source.comment-friendly-name` = 캡처된 friendly_name
  - `ig-source.comment-doc-id-refreshed-at` = 현재 UTC ISO8601 (관측용 — staleness 감시)
  를 각각 `INSERT … ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value`로 upsert.
- **값이 기존과 동일하면**(회전 없음) doc-id/friendly-name은 실질 no-op이고 refreshed-at만
  갱신 — "갱신자는 살아있고, doc_id는 아직 유효"라는 신호. IgSourceSettings TTL(5s)이라 반영
  지연 없음.

### 3-4. 실패 시 동작·관측

- **캡처 실패 / 검증 실패**: app_setting **미변경**(기존 유효값 유지) + 에러 로그 + **non-zero
  exit**. 자체크롤은 기존 doc_id로 계속 동작하고(만료 전이면 정상, 만료면 이미 complete=false로
  1페이지 15건 보존 — 데이터 유실 없음), 3~4일 뒤 크론이 재시도.
- **관측**: ① stdout/stderr → 호스트 크론 로그 파일(`~/docid-refresher.log`, backup.log 관례
  동일) + docker 로그(loki 수집 대상). ② `refreshed-at` 키로 "마지막 성공 갱신 후 경과"를
  monitoring/Grafana가 감시 가능(후속). ③ non-zero exit로 크론 실패 가시화. 자격증명은 어떤
  로그에도 출력하지 않는다(doc_id·edges 수·소요시간만).

## 4. 타깃 게시물

캡처는 **공개·댓글 많은 활성 게시물**이어야 페이징이 발화한다. 특정 게시물 하나에 묶으면
그 게시물이 삭제·비활성화될 때 갱신자가 죽는다 → **안정적 대형 공개 계정의 게시물 여러 개를
배열로 두고 순차 폴백**. 실측 성공: nasa `DcOX3hWFiey`. 후보는 환경변수/스크립트 상수로 두고
쉽게 교체 가능하게. doc_id는 게시물 무관 전역값이라 어느 게시물에서 잡아도 동일.

## 5. 로컬 검증 계획 (구현 후 1회 실증)

운영 서버 .env의 프록시 자격증명을 파일로 받아(값 비노출), 로컬에서:
1. 실 레지덴셜 프록시로 캡처 → doc_id 획득
2. 같은 컨텍스트로 검증 페이징 1콜 → 200+edges 확인
3. 로컬 monitoring DB(`crawler-postgres-1`의 `monitoring` DB) app_setting에 upsert → 반영 확인
   (app_setting 테이블이 로컬에 없으면 자체크롤 브랜치 Flyway 미적용 상태 — 테스트용으로 임시
   생성해 upsert 경로만 실증하고 정리).

## 6. 미결·후속 (개통 단계)

- **운영 배치**: 이미지 빌드(서버 `compose build`), 크론 등록(setup-server.sh에 라인 추가),
  최초 수동 실행 1회로 개통 검증 — **모두 사용자 승인 후**.
- **개통 순서 의존**: 이 갱신자의 실질 효용은 자체크롤 댓글 경로가 운영 개통된 뒤부터
  (그전엔 doc_id를 아무도 소비 안 함). 자체크롤 머지·개통과 독립적으로 **먼저 배치해 둬도
  무해**(app_setting 값만 최신 유지, 소비자 없으면 no-op).
- **staleness 알람**: `refreshed-at` 키 기반 "N일 이상 미갱신" 감시를 monitoring/Grafana에
  추가(후속).
- **CD 통합 여부**: 현재는 setup-server.sh 크론(호스트) 방식. 필요 시 CD가 스크립트 동기화를
  맡는 방식으로 승격 검토.
```
