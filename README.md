# celfit crawler

카테고리 키워드로 인스타그램 콘텐츠(릴스/피드)를 발굴하고, 업로드 3일 후 게시물
상세·댓글·작성자 프로필을 **Apify 응답 원형(raw)** 그대로 적재하는 수집 시스템.

- 설계: [docs/superpowers/specs/2026-07-07-crawler-design.md](docs/superpowers/specs/2026-07-07-crawler-design.md)
- 파이프라인: **discover**(발굴) → **qualify**(프로필+규칙 판정) → **aggregate**(+3일 상세·댓글)

## 실행

필요: Java 21, Docker Desktop(Postgres 자동 기동), Apify 계정 토큰.

```powershell
$env:APIFY_TOKEN = 'apify_api_...'
./gradlew bootRun
```

- UI: http://localhost:8080/ui (대시보드 · 잡 실행 · 카테고리/규칙 · 데이터 열람)
- DB: localhost:5433 / crawler / crawler (raw는 psql·DBeaver로 직접 조회 —
  generated column 덕에 `select writer, text from raw_comment` 식으로 일반 테이블처럼 보임)
- Apify 인증은 Authorization Bearer 헤더로 나감 (URL·로그에 토큰 노출 없음)

## 테스트

```bash
./gradlew test          # 통합 테스트는 Testcontainers — Docker Desktop 필요
```

## 운영 절차 (초기 = 전부 수동)

1. UI → 카테고리·규칙(`/ui/categories`): 카테고리 생성(예: 메이크업), 키워드 추가, 규칙(팔로워 범위 등) 설정
2. UI → 잡 실행(`/ui/jobs`): **discover** 실행 (카테고리 선택 또는 "전체 카테고리") → 대시보드에서 PENDING 확인
3. **qualify** 실행 → QUALIFIED/EXCLUDED 분포 확인
4. 3일 후(백필이면 즉시 — 과거 게시물은 이미 3일 경과) **aggregate** 실행
5. 검증 끝나면 `application.yml`의 `crawler.schedule.enabled: true`로 자동화

REST로도 가능:
- `POST /admin/jobs/discover?category=<id>` (category 생략 시 전체 활성 카테고리 순차 실행)
- `POST /admin/jobs/qualify`, `POST /admin/jobs/aggregate`
- `GET /admin/runs`, `GET /admin/status`, 카테고리·키워드·규칙 CRUD는 `/admin/categories...`

## 스모크 테스트 (실 Apify 과금 주의 — CI 금지)

첫 실 실행 전 **액터 id·입출력 필드 검증**이 목적. 최소 비용으로:

1. `application.yml`에서 `crawler.discover.results-limit: 5`, `crawler.aggregate.comments-per-post: 5`로 임시 축소
2. 키워드 1개짜리 카테고리로 discover → `crawl_run`에 SUCCEEDED + item_count 확인,
   Apify 콘솔에서 같은 run id 확인
3. `raw_discovery_post.payload`에 `shortCode`/`timestamp`/`ownerUsername`/`productType` 존재 확인
   (없으면 `DiscoveryItemParser`·generated column 필드명을 실제 응답에 맞게 수정)
4. qualify → `raw_profile.followers` 채워지는지 확인 (`followersCount` 필드명 검증)
5. aggregate(과거 게시물이라 즉시 도래) → `raw_post_detail`·`raw_comment` 적재 확인,
   댓글 아이템의 `postUrl`로 shortcode 매칭이 되는지 확인
6. limit 원복

## 주의

- **run-sync 금지** — 비동기 시작→폴링→dataset 수신만 사용 (장시간 실행 시 과금+유실 방지).
  폴링 중 일시 오류 시 액터는 abort되지 않음 — crawl_run FAILED와 Apify 콘솔로 추적.
- 한글 키워드는 자동으로 `keywordSearch: true` 우회 (인스타 비로그인 해시태그 차단)
- 액터 id·필드명은 `apify/Actors.java`·`ActorInputs.java`·V1 마이그레이션에 모여 있음 —
  Apify 쪽 변경 시 이 세 곳만 수정
- detail 응답이 완전히 비면(레이트리밋 소프트 실패) GONE이 아니라 재시도로 처리됨 —
  attempts 3회 초과 시 FAILED
