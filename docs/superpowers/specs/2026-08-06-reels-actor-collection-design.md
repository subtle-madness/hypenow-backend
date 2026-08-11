# 릴스 액터 수집 (임시 토글) 설계

> 상태: 🟢 활성

## 배경·목적

- Hiker 크레딧이 얼마 남지 않아 최대한 아끼고, **잘못 결제된 Apify 크레딧을 소진**하는 동안
  릴스 수집을 Apify 액터(`apify~instagram-reel-scraper`, `Actors.DETAIL_REELS` — 선언만 있고
  미사용)로 임시 전환한다.
- **임시 사용이 전제**: 언제든 Hiker로 무해하게 복귀 가능해야 한다(양방향 토글, 재시작 불필요,
  Hiker 경로 무변경).
- **액터로 수집한 릴스는 analytics에서 반드시 읽혀야 한다** — 수집만 되고 지표(하이프 스코어·
  랭킹)가 끊기는 것은 불가.

## 결정 사항 (브레인스토밍 합의)

| 항목 | 결정 |
|---|---|
| 전환 방식 | 런타임 토글 `reels.source` (HIKER 기본 / ACTOR) — 접근 A |
| 수확 깊이 | 계정당 6개 (`reels.actor-results-limit`, 조절 가능) — Hiker 1페이지 12개보다 얕게, 최신 게시물만 대상이므로 충분 |
| analytics | `v_base_reel_item` 확장 필수 (지표 단절 불가) |
| 구조 | ReelsJob 내부 분기 — 계정당 액터 런 1회 (배치 런·별도 잡 기각: 귀속 모호성·코드 중복이 임시 용도에 과함) |

## 1. 토글·설정

- `ReelsSource` enum `{HIKER, ACTOR}` + `ReelsSourceSetting` — `app_setting` 키 `reels.source`,
  **값이 없거나 파싱 불가면 HIKER**. `ProfileSourceSetting`과 동일 패턴(코드 기본값이라 Flyway
  시드 불필요, 저장 즉시 반영, 빈칸 저장 = HIKER 복귀).
- `reels.actor-results-limit` — 기본 6, yml `crawler.reels.actor-results-limit`(`ReelsProperties`).
  `SettingsService` KEYS에 추가해 어드민 런타임 설정 표에서 조절.

## 2. crawler 수집 경로 (ReelsJob 분기)

`ReelsJob.visit()`에서 `reels.source`를 읽어 분기한다. **HIKER 경로는 무변경** — 기존 테스트
무변경 통과가 복귀 안전의 증명이다.

ACTOR 경로:

- 계정당 액터 런 1회: `CrawlExecutor.execute(REELS, trigger, null, username,
  Actors.DETAIL_REELS, input)` — 액터 오버로드로 run 기록·과금 집계·아이템 아카이브가 기존
  인프라를 그대로 탄다.
- 입력 `ActorInputs.reels(username, limit)` = `{"username": ["<계정명>"], "resultsLimit": <n>}`.
- 결과 아이템 리스트를 `{"items": [...]}` 래퍼로 감싸 `raw_media_page(source=APIFY_ACTOR)` 1행
  저장(아이템 원형 무변형 보존). content·캡션 upsert, `last_reels_at` 북키핑은 기존 코드 공유.
- **pk(ig_user_id) 없는 계정도 ACTOR 경로에선 수집한다**(액터는 username 기반) — pk 스킵은
  HIKER 경로에만 남긴다.
- **0건 응답 = 수확 완료 마킹**(Hiker 404 `NO_CLIPS_MARK`와 동일 의미론). "릴스 없음"과 "액터
  누락"을 구분하지 못하는 것은 임시 용도로 수용 — 다음 재방문 주기에 자연 재시도된다.
- 방문 1회 = 트랜잭션 1개, 계정 단위 실패 격리(기존 구조 유지).

## 3. 추출 계층 (MediaItemExtractor)

액터 아이템 필드명이 Hiker와 다르다 — 분기 없으면 content upsert 전량 누락.

- `items()`: `APIFY_ACTOR` → `payload.get("items")`.
- shortCode: `shortCode`(camelCase) — `firstString` 후보에 추가.
- takenAt: `timestamp`(ISO 문자열) — 기존 `takenAtOf`가 이미 ISO를 처리.
- type: `productType == "clips"` → REELS. 릴스 전용 액터이므로 필드 부재 시 REELS 기본.
- pinned: 액터에 정보 없음 → false.
- **캡션은 평문 문자열**(`"caption": "..."`) — 현행 `captionOf`는 `containsKey("caption")` 후
  Map이 아니면 `""`를 반환해 평문 캡션을 "확인된 무캡션"으로 오판한다. String 분기를 추가하되
  3-상태 계약(null=미확인 / ""=확인된 무캡션 / 원문)을 유지한다.
- 뷰티 재판정용 `captions()`에도 `APIFY_ACTOR` 분기 추가(`items[].caption` 평문).

## 4. analytics 뷰 확장 (v_base_reel_item)

`UNION ALL`로 `raw_media_page WHERE source='APIFY_ACTOR'`의 `payload->'items'` 평탄화 분기 추가.
상위 뷰(스냅샷·하이프 스코어)는 `v_base_reel_item`만 보므로 base 확장만으로 지표가 이어진다.

| 컬럼 | 액터 필드 | 비고 |
|---|---|---|
| short_code | `shortCode` | |
| likes | `NULLIF((likesCount)::bigint, -1)` | 비공개 -1 → NULL |
| comments_count | `commentsCount` | |
| views | `COALESCE(videoPlayCount, videoViewCount)` | |
| caption | `caption` | 평문 |
| thumbnail_url | `displayUrl` | |
| video_duration | `videoDuration` | |
| paid_partnership | `COALESCE(isSponsored, false)` | |

`item_ordinal`은 `WITH ORDINALITY`(기존 분기와 동일 — 합성 스냅샷 id 재료).

## 5. 액터 payload 실측 검증 (구현 필수 선행 단계)

위 필드명은 Apify 문서 기반 **가정**이다. 로컬 DB에 릴스 액터 실측 payload가 없음을 확인했다
(레거시 `raw_post_detail` 0건, `raw_run_item`에 reel-scraper 아이템 없음 — 08-06 실측).

- **구현 초기에 실제 액터 스모크 런 1회**(계정 1개, resultsLimit 소액)로 payload를 확보하고
  §3·§4 필드명을 실측으로 확정한다. 불일치 발견 시 스펙이 아니라 구현(추출기·뷰·픽스처)을
  실측에 맞춘다.
- 확보한 실측 payload는 crawler 테스트 픽스처·SQL 하니스 시드의 원본으로 쓴다(가공 금지).

## 6. 어드민 UI·부대 작업

- `settings.html`에 "릴스 수집 방식" 라디오 섹션(HikerAPI 기본 / 액터) +
  `ReelsSourceUiController` — 프로필 수집 방식 UI와 동일 패턴.
- `JobCostEstimator`: reels 추정에 ACTOR일 때 "Apify 액터 과금 별도" 주석(프로필 패턴 재사용).
- DECISIONS.md 맨 위 결정 1행.

## 7. 테스트

- crawler: `ReelsJob` 통합 테스트 ACTOR 케이스(액터 스텁 → raw 저장·content upsert·북키핑·
  0건 완료 마킹·pk 없는 계정 수집), `MediaItemExtractor` 액터 형태 단위 테스트(캡션 3-상태 포함).
  기존 HIKER 테스트 무변경 통과 확인.
- analytics: SQL 하니스에 `APIFY_ACTOR` 시드 + 어서션 케이스 추가.
- 검증: `./gradlew :crawler:test` → `analytics/test/run.sh`.

## 범위 밖 (YAGNI)

- 배치 액터 런 최적화(회당 1런) — 회당 10계정 규모에서 불필요.
- 피드(`DETAIL_FEED`) 액터 전환, 자동 폴백(Hiker 실패 시 액터), 액터 크레딧 잔량 감시.
- Hiker 복귀 시 정리 작업 — 토글만 되돌리면 끝나는 구조가 이 설계의 목적.
