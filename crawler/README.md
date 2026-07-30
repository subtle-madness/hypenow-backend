# celfit crawler

검색 키워드로 인스타그램 **인플루언서**를 발굴하고, 팔로워 범위로 판정한 뒤, 판정을
통과한 인플루언서의 게시물(릴스/피드) 상세·댓글·프로필을 **Apify/HikerAPI 응답 원형(raw)**
그대로 적재하는 수집 시스템. 카테고리 계층이 아니라 **인플루언서**가 중심 도메인이다.

- 설계: [docs/superpowers/specs/2026-07-07-crawler-design.md](docs/superpowers/specs/2026-07-07-crawler-design.md),
  [docs/superpowers/specs/2026-07-14-influencer-pipeline-design.md](docs/superpowers/specs/2026-07-14-influencer-pipeline-design.md)
- 파이프라인: **discover**(검색 키워드로 인플루언서 발굴) → **qualify**(프로필 조회 + 팔로워
  범위 판정 → QUALIFIED/EXCLUDED) → **collect**(QUALIFIED 인플루언서의 게시물 열거 + 상세·댓글 수집)

## 실행

필요: Java 21, Docker Desktop(Postgres 자동 기동), Apify 계정 토큰(HikerAPI 키는 선택 — 소스 설정에 따라).

```powershell
$env:APIFY_TOKEN = 'apify_api_...'
./gradlew bootRun
```

- UI: http://localhost:8080/ui (대시보드 — 잡 실행 스트립·예상 비용·실행 로그 포함 · 데일리 수집 · 인플루언서 · 검색 키워드 · 설정)
- DB: localhost:5433 / crawler / crawler — raw 테이블(`raw_media_page`/
  `raw_comment`/`raw_profile`)은 `payload`(jsonb, Apify/HikerAPI 응답 원형)와 함께
  추출된 실컬럼(`short_code`/`caption`/`writer`/`text`/`followers` 등)을 갖고 있어
  `select writer, text from raw_comment` 식으로 일반 테이블처럼 바로 조회 가능
  (generated column이 아니라 실컬럼 — 추출 실패 시 NULL 허용, 원형은 `payload`에 그대로 남음).
  게시물 캡션 원문은 `content_caption`(content_id PK, 최신 1건)에 별도 보존 —
  raw jsonb에서 추출한 정본, 조회는 `select caption from content_caption where content_id = ?`
- Apify 인증은 Authorization Bearer 헤더로 나감 (URL·로그에 토큰 노출 없음)

## 테스트

```bash
./gradlew test          # 통합 테스트는 Testcontainers — Docker Desktop 필요
```

## 운영 절차

1. UI → 검색 키워드(`/ui/keywords`): 발굴에 쓸 해시태그 검색어 등록(예: 데일리룩). 텍스트
   수정은 없음 — 바꾸려면 삭제 후 재추가. "제외" 처리한 키워드는 다음 discover에서 빠진다.
2. 대시보드(`/ui`)의 실행 스트립에서 **① discover** 실행 → 활성 키워드를 순회하며 인플루언서를
   upsert(신규는 DISCOVERED) → 대시보드에서 DISCOVERED 수 확인
3. **② qualify** 실행 → 프로필 조회 후 전역 팔로워 범위로 QUALIFIED/EXCLUDED 판정
   (`EXCLUDED 재판정 포함` 체크박스로 이전에 탈락한 인플루언서도 다시 판정 가능)
4. **③ collect** 실행 → QUALIFIED 인플루언서의 게시물을 열거해 `content`에 적재하고, 발견된
   게시물의 상세·댓글을 수집(PENDING→COLLECTED). 첫 수집(백필)은 최근 N개월(기본 6개월),
   이후 추적 수집은 최근 N일(기본 30일) 범위 — 대시보드 "백필 대기" 카드로 진행 확인
5. 운영 서버는 deploy/compose.yaml의 `CRAWLER_SCHEDULE_*` env로 qualify·beauty·collect·reels가
   새벽 윈도우 반복 크론으로 자동 실행된다(07-22~, deploy/README §4-2). 발굴(discover·similar)만
   수동 트리거.

수집 튜닝 값(발굴 상한·판정 팔로워 범위·배치 크기·백필/추적 기간·댓글 상한·재시도 상한)은
재시작 없이 UI 설정 화면(`/ui/settings`) 또는 REST(`GET/PUT /admin/settings`)로 바꿀 수 있다 —
값을 비우면 `application.yml` 기본값으로 복귀. `schedule.enabled`·cron은 대상 아님(재시작 필요).
같은 화면에서 발굴/댓글/프로필 수집 소스(액터 vs HikerAPI vs 자체 크롤)도 전환 가능.

REST로도 가능:
- `POST /admin/jobs/discover` (활성 검색 키워드 전체 순차 실행)
- `POST /admin/jobs/qualify?requalify=true|false`, `POST /admin/jobs/collect`
- `GET /admin/runs`, `GET /admin/status` (인플루언서 판정 카운트 + 백필 대기 + 게시물 수집 카운트)
- 검색 키워드 CRUD는 `GET/POST /admin/keywords`, `PUT /admin/keywords/{id}`(enabled만),
  `DELETE /admin/keywords/{id}`

## 스모크 테스트 (실 과금 주의 — CI 금지)

첫 실 실행 전 **액터/HikerAPI id·입출력 필드 검증**이 목적. 최소 비용으로:

1. `/ui/settings`에서 `discover.results-limit`, `collect.batch-limit`,
   `collect.comments-per-post`를 임시로 작게(예: 5, 1, 5) 낮춘다
2. 검색 키워드 1개 등록 → discover → `crawl_run`에 SUCCEEDED + item_count 확인,
   `influencer`에 신규 행(DISCOVERED)과 `influencer_discovery`에 발굴 출처가 쌓이는지 확인
3. qualify → `influencer.followers`·`status`(QUALIFIED/EXCLUDED)가 채워지는지,
   `raw_profile.followers` 필드명(`followersCount`)이 유효한지 확인
4. collect(`collect.batch-limit=1`로 소형 계정 1명) → `raw_media_page`(게시물 열거 원형),
   `content`(PENDING→COLLECTED), `raw_comment`(댓글 페이지 원형) 적재 확인
5. 값 원복

## 주의

- **run-sync 금지** — 비동기 시작→폴링→dataset 수신만 사용 (장시간 실행 시 과금+유실 방지).
  폴링 중 일시 오류 시 액터는 abort되지 않음 — crawl_run FAILED와 Apify 콘솔로 추적.
- 한글 키워드는 자동으로 `keywordSearch: true` 우회 (인스타 비로그인 해시태그 차단)
- 공유 수·IG 파트너십 라벨·광고 표기 판별(AdSignals)은 로그인 세션이 필요하거나 공식 액터가
  주지 않는 데이터라 **수집하지 않기로 결정** (2026-07-08) — 로그인 세션 수집은 계정 정지
  리스크. 랭킹 스코어는 조회수 기반으로 충분
- 액터 id·필드명은 `apify/Actors.java`·`ActorInputs.java`·V1 마이그레이션에 모여 있음 —
  Apify 쪽 변경 시 이 세 곳만 수정
- 게시물 단위 실패(댓글 수집 등)는 다음 방문에서 해당 게시물만 재시도된다(PENDING 유지) —
  attempts 3회 초과 시 FAILED
- 과금한 모든 액터 응답은 `raw_run_item`에 원형 그대로 전량 보관된다(파이프라인이 버린
  아이템도 포함) — 호출자 트랜잭션에 합류한다. discover/qualify는 잡 전체가 트랜잭션 1개라
  잡이 롤백되면 그 실행분 아카이브도 같이 롤백되지만, collect는 인플루언서 방문 1회 = 트랜잭션
  1개라 한 인플루언서의 오류가 이미 커밋된 다른 인플루언서의 raw까지 되돌리지 않는다
- 추출 실패로 raw 실컬럼이 NULL인 행의 재추출 배치는 별도로 만들지 않는다 — 원형이
  `payload`에 남아 있어 필요해지는 시점(첫 추출 실패 관측)에 추가해도 데이터 유실이 없다
