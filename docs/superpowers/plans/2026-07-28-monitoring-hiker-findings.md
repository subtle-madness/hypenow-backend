# monitoring — HikerAPI 엔드포인트 실측 findings

> 상태: 🟢 활성 · 실측일 2026-07-29 · 대상 계정 `rarebeauty`(공개, pk `3109786630`, 팔로워 864만, media_count 5047)
> 근거 픽스처: `monitoring/src/test/resources/hiker/*.json` (응답 원문, 공개 데이터)

Task 1(실측·픽스처)의 결과 문서. **Task 4 파서의 필드 후보는 이 문서가 정본**이며,
계획서(`plans/archive/2026-07-28-monitoring-module.md` — 실행 완료 후 아카이브됨) Task 4 코드
블록도 이 결론에 맞춰 이미 수정했다.

---

## 1. 확정 엔드포인트

브리프의 초안은 `v1` 계열이었으나 실측 결과 **v1 계열은 6지표 중 2종(좋아요·댓글)밖에 못 준다**.
아래 v2 계열로 전량 교체한다(모두 HTTP 200 실측).

| 용도 | 확정 경로 | 응답 셰이프 | 비고 |
|---|---|---|---|
| ① 프로필 | `GET /v2/user/by/username?username=<username>` | `{ "user": {...}, "status": "ok" }` | 브리프안 그대로 유지 |
| ② 게시물 열거(릴스+피드) | `GET /v2/user/medias?user_id=<pk>` | `{ "response": { "items": [...], "num_results", "more_available", "next_max_id" }, "next_page_id" }` | **브리프의 `/v1/user/medias/chunk`를 대체** |
| ②' 릴스 재생수 보강 | `GET /v2/user/clips?user_id=<pk>` | `{ "response": { "items": [ { "media": {...} } ], "paging_info" }, "next_page_id" }` | ②가 릴스 `play_count`를 안 주므로 **추가 1콜** |
| ③ 게시물 단건 | `GET /v2/media/by/code?code=<shortCode>` | `{ "num_results", "more_available", "items": [ {...} ] }` | **브리프의 `/v1/media/by/code`를 대체** |

헤더는 기존 crawler 관용구와 동일하게 `x-access-key: <API_KEY>`.

### 폐기한 v1 후보 (실측 근거)

| 경로 | 결과 |
|---|---|
| `/v1/user/medias/chunk?user_id=` | 200, `[[items…], "next_page_id"]`. 12건. **`play_count`·`view_count`가 전부 `0`(릴스 포함), `save_count`·`reshare_count`·`media_repost_count` 키 자체가 없음** → 지표 2종만. 폐기 |
| `/v1/user/medias?user_id=&amount=12` | 위와 동일한 정규화 셰이프·동일 한계. 폐기 |
| `/v1/media/by/code?code=` | 200. `play_count`는 정상(900,733)이나 `save_count`·`reshare_count`·`media_repost_count` **키 없음** → 저장·공유·리포스트 취득 불가. 폐기 |

정리: hikerapi의 v1 계열은 "정규화된 축약 스키마"라 IG 원본의 인게이지먼트 필드가 깎여 나온다.
v2 계열은 **IG 모바일 원본 필드를 거의 그대로** 통과시킨다.

---

## 2. 지표별 소스 필드

### ① 프로필 (`/v2/user/by/username` → `user`)

| 항목 | 필드 | 실측값 |
|---|---|---|
| 팔로워 | `user.follower_count` | 8,643,561 |
| 팔로잉 | `user.following_count` | 412 |
| 게시물 수 | `user.media_count` | 5,047 |
| 내부 pk | `user.pk` (**숫자 타입**), `user.pk_id`/`user.id`(문자열) | 3109786630 |
| 비공개 여부 | `user.is_private` | false |

주의: `user.pk`는 JSON number다(게시물의 `pk`도 number). 문자열로 쓰려면 `asString()` 코어션 필요.

### ② 게시물 6지표

`E` = 열거 `/v2/user/medias`, `C` = 클립 열거 `/v2/user/clips`(`items[].media`), `S` = 단건 `/v2/media/by/code`(`items[0]`).
✅=값 있음, ⛔=키 자체 없음, —=해당 없음.

| 지표 | 소스 필드 | E(릴스) | E(피드/캐러셀) | C(릴스) | S(릴스) | S(피드/캐러셀) |
|---|---|---|---|---|---|---|
| 좋아요 | `like_count` | ✅ | ✅ | ✅ | ✅ | ✅ |
| 댓글 | `comment_count` | ✅ | ✅ | ✅ | ✅ | ✅ |
| 조회 | `play_count` (동일값 `ig_play_count`) | ⛔ | ⛔ | ✅ | ✅ | ⛔ |
| 저장 | `save_count` | ✅ | ⛔ | ✅ | ✅ | ⛔ |
| 공유 | `reshare_count` | ✅ | ⛔ | ✅ | ✅ | ⛔ |
| 리포스트 | `media_repost_count` | ✅ | ✅ | ✅ | ✅ | ✅ |

실측 샘플(릴스 `DbV7LgZsKG8`, 단건 `S`): like 48,833 / comment 595 / play 900,812 / save 1,066 / reshare 809 / repost 657.
피드 `DbOMP1_CY18`(`S`): like 92,262 / comment 664 / repost 917, **나머지 3종은 키 부재**.

#### 결론 3가지

1. **저장·공유는 릴스 전용이다.** 피드·캐러셀 응답에는 `save_count`·`reshare_count` 키가 아예 없다
   (null이 아니라 **키 부재**). 단건 콜로 보강해도 안 나온다 → **피드·캐러셀은 저장·공유 영구 null**.
2. **리포스트는 전 타입 제공된다.** 필드명이 `reshare_count`/`repost_count`가 아니라 **`media_repost_count`**다.
   `reshare_count`(공유)와 `media_repost_count`(리포스트)는 **서로 다른 값**이다
   (릴스 `DbQei8FCh7i`: reshare 7,680 vs repost 3,924). 6지표에서 공유≠리포스트로 분리 유지 타당.
3. **조회수는 릴스만, 그리고 열거 ②에서는 안 나온다.** `/v2/user/medias`는 프로필 그리드 API라
   릴스여도 `play_count` 키가 없다. `/v2/user/clips` 또는 단건 `/v2/media/by/code`에서만 나온다.
   → **스윕에서 계정당 `②`+`②'` 2콜을 쏘고 `code` 기준으로 릴스 재생수를 머지**한다(권장·채택).
   CLAUDE.md의 "피드 게시물은 조회수(views)가 항상 NULL"과 정확히 일치.
   `view_count`는 v2에서 항상 `null`(릴스 포함)이므로 후보에서 제외한다.
4. **(08-02 정정) `play_count`는 세션 의존이라 조회수 정본이 아니다 — `ig_play_count`를 우선한다.**
   07-29 실측 당시엔 `play_count == ig_play_count`(동일값)였지만, 운영 원형 적재
   (`raw.fetch_payload` 49건 단건 + 클립 열거) 재실측 결과 Hiker가 콜마다 다른 IG 세션을 태우며,
   FB 교차게시 데이터가 보이는 세션에서만 `fb_play_count` 키가 실리고 그때
   `play_count = ig_play_count + fb_play_count`(합산)로 커진다. 그 결과 `play_count` 우선 파싱은
   같은 릴스 조회수가 **221 → 305 → 222로 역행**하는 "랜덤 조회수"를 만든다(단건 `DX0U76Xy1D2`,
   클립 열거 `DXx7gtszvSV` 32,264→31,944 등 다수). `ig_play_count`는 전 콜에서 존재했고 단조
   증가 — **조회수는 `ig_play_count` 우선, `play_count`는 폴백**(HikerClient 2곳: 단건 `toPost`·
   클립 머지 `fetchClipPlays`). 세션 종류는 Hiker 쪽 풀이라 우리가 고정할 수 없다.
   **(08-03 확장) 화면(모바일 앱)에 보이는 조회수는 합산값이다** — 저장되는 `post_snapshot.views`는
   화면 기준을 따르기로 결정(08-03): `views = IG 몫 + fb_plays`. FB 몫이 안 실린 콜(IG 전용 세션)은
   **직전 관측 `fb_plays`를 캐리포워드**한다(FB 몫은 실측상 며칠 단위 정적 — `DXg4DAUE0NU` 720 고정
   등 — 이라 오차 미미). 합산 세션 비율은 콜 단위 실측 단건 18.5%·클립 33.3%뿐이라 "실릴 때까지
   재시도"는 기대 비용 3~5배 — 대신 **fb 관측 이력이 전무한 신규 릴스에 한해 1회만 재조회**
   (CollectService, 열거는 clips 콜만·단건은 단건 콜). 판별 마커: 합산 세션 응답에만 `fb_*` 키 3종
   (`fb_play_count`·`fb_like_count`·`fb_comment_count`)이 실리며, 교차게시 없는 릴스도 합산 세션은
   `fb_play_count: 0`을 준다(키 부재 = 세션이 FB를 못 봄, 0 = 관측된 0 — 이 구분이 캐리포워드·재시도
   판정 기준).
   **(08-03 보강 2건)** ① **FB 몫은 `play - ig` 유도를 fb 키보다 우선한다** — fb 키 없이 합산
   `play_count`만 주는 세션(`DUrj0iGEn6G`: play 570,331 vs ig 512,077, fb 키 부재)과 fb 키가 0인데
   play > ig인 모순 세션(`DPQoGI1APa_`: diff 47,443, fb 키 0)이 실존. fb 키가 정상일 때는
   play - ig == fb(실측 검산: 305-222=83 등)라 결과 동일. ② **첫 관측 역전파 + 원형 기반 백필** —
   fb를 처음 관측하는 날 이전 행들이 IG 전용이면 시계열에 +fb 유령 점프가 생겨 성과 추이가
   "▲증가"로 오표시된다(실사례: `DX0U76Xy1D2` 221→305 ▲84). 쓰기 시 첫 관측을 이전 미관측 행에
   소급(UPDATE)하고, fb_plays 도입 이전 적재분은 raw.fetch_payload에서 (code, KST 날짜)별 ig/fb를
   재추출해 일회성 마이그레이션으로 재계산했다(`V20260803064353` — 운영 드라이런 검증: 221→304
   정렬, 유도 fb 릴스 diff 0).

---

## 3. 열거 응답 형태

| 항목 | `/v2/user/medias` | `/v2/user/clips` |
|---|---|---|
| 1페이지 게시물 수 | **12** (`response.num_results` = 12) | **12** (릴스만) |
| 아이템 경로 | `response.items[]` (미디어 객체 직접) | `response.items[].media` (**한 겹 더 감쌈**) |
| 다음 페이지 커서 | `next_page_id`(최상위) = `response.next_max_id` (예: `3946974539133803409_3109786630`) | `next_page_id`(최상위) = `response.paging_info.max_id` (불투명 base64 커서) |
| 더 있음 플래그 | `response.more_available` | `response.paging_info.more_available` |

**정렬 함정**: `/v2/user/medias` 1페이지의 **첫 항목이 고정(pinned) 게시물**이라
`taken_at`이 2023-04-12로 튄다(나머지 11건은 최신순). "최근 N개" 로직은 배열 순서가 아니라
`taken_at`으로 재정렬해야 한다. `/v2/user/clips`에는 이 현상이 없었다.

---

## 4. 판별·캡션 필드

| 항목 | 필드 | 값 |
|---|---|---|
| 미디어 타입 | `media_type` | `1`=이미지, `2`=비디오, `8`=캐러셀 |
| 상품 타입 | `product_type` | `feed` / `clips` / `carousel_container` |
| 캡션 | **`caption.text`** (v2). v1은 `caption_text` | `caption` 자체가 null일 수 있음 |
| 숏코드 | `code` | 예: `DbV7LgZsKG8` |
| 게시 시각 | `taken_at`(epoch seconds, **number**) | v1은 ISO 문자열 `taken_at`이라 서로 다름 — v2 기준으로 통일 |
| 캐러셀 장수 | `carousel_media_count` | 캐러셀만 |

**content_type 판별은 `media_type == 2`가 아니라 `product_type == "clips"`로 한다.**
`media_type == 2`는 릴스가 아닌 일반 비디오 피드 게시물(`product_type == "feed"`)도 포함하기 때문.
→ `"clips".equals(product_type) ? "REELS" : "FEED"`.

부수 함정: 협업(coauthor) 게시물은 `id`의 `_` 뒤 소유자 세그먼트가 대상 계정 pk가 아니다
(실측: `rarebeauty` 그리드의 릴스 `id`가 `3951324523536622012_23818608`). **소유자 식별에 `id` 접미사를 쓰지 말 것**,
`code`/`pk`만 사용.

---

## 5. Task 4 파서(`firstLong`) 후보 — 브리프 초안과 달라진 점

계획서 Task 4의 `toPost()`를 아래로 교체했다(이미 반영 완료).

| PostInfo 필드 | 브리프 초안 후보 | **실측 확정 후보** | 변경 사유 |
|---|---|---|---|
| `contentType` | `media_type == 2 ? REELS : FEED` | `"clips".equals(product_type)` | 일반 비디오 피드 오분류 방지 |
| `caption` | `caption_text` → `caption.text` | 동일(유지) | v2는 `caption.text`, 폴백 유지로 무해 |
| `views` | `play_count`, `view_count` | **`ig_play_count`, `play_count`** (08-02 순서 정정 — §2 결론 4) | `view_count`는 v2에서 항상 null. `play_count`는 세션 따라 FB 합산 여부가 바뀌어 역행함 |
| `saves` | `save_count`, `saved_count` | **`save_count`** | `saved_count`는 존재하지 않음 |
| `shares` | `share_count` | **`reshare_count`** | `share_count`는 존재하지 않음(`share_count_disabled` 불리언만 있음) |
| `reposts` | `reshare_count`, `repost_count` | **`media_repost_count`** | `reshare_count`는 공유 지표로 이동, `repost_count`는 없음 |

엔드포인트도 함께 교체: 열거 `/v1/user/medias/chunk` → `/v2/user/medias`(+`/v2/user/clips` 머지),
단건 `/v1/media/by/code` → `/v2/media/by/code`. 응답 언랩 경로가 `response.items[]` /
`response.items[].media` / `items[0]`로 늘었으므로 파서의 `items()` 헬퍼도 `response` 언랩을 추가했다.

---

## 6. 픽스처

| 파일 | 출처 | 크기 | 비고 |
|---|---|---|---|
| `hiker/profile.json` | `/v2/user/by/username?username=rarebeauty` | 21KB | 원문 그대로 |
| `hiker/medias.json` | `/v2/user/medias?user_id=3109786630` | 413KB | 원문 그대로(12건: 릴스 3·캐러셀 6·피드 3) |
| `hiker/clips.json` | `/v2/user/clips?user_id=3109786630` | 231KB | **items를 앞 5건으로 잘라 저장**(원본 517KB/12건). `num_results`류 메타는 원본값 유지 |
| `hiker/media-by-code.json` | `/v2/media/by/code?code=DbV7LgZsKG8` (릴스) | 47KB | 6지표 전량 존재 케이스 |
| `hiker/media-by-code-feed.json` | `/v2/media/by/code?code=DbOMP1_CY18` (피드) | 11KB | 저장·공유·조회 **키 부재** 케이스(널 규칙 테스트용) |

API 키는 픽스처·문서 어디에도 없다(커밋 전 grep 0건 확인).

---

## 7. 운영 시 유의

- **콜 비용**: 계정당 스윕 1회에 프로필 1 + 열거 1 + 클립 1 = **3콜**. 릴스 조회수를 포기하면 2콜.
  추적 게시물이 열거 범위 밖으로 밀려나면 게시물당 단건 1콜 추가.
- **지표는 계속 움직인다**: 같은 릴스를 몇 초 간격으로 다른 엔드포인트로 찍었더니
  like 48,811→48,833→48,857로 변했다. 스냅샷 간 delta 계산 시 "같은 시각 기준"을 기대하지 말 것.
- **`/v2/user/medias`는 계획서에 없던 엔드포인트**다. crawler는 `/v1/user/medias/chunk`·`/gql/user/medias`를
  쓰고 있으므로 monitoring이 첫 사용처다. IG 업스트림 장애 시 폴백으로 `/v1/user/medias/chunk`
  (좋아요·댓글만) 강등 경로를 열어둘 수 있다.

---

## 8. Task 4(파서 구현) 추기 — 2026-07-29

### 8-1. 필드 매핑 조정: **없음**

Task 4를 픽스처 5종으로 TDD 구현한 결과 §2·§4·§5의 매핑이 **전부 그대로 통과**했다.
파서를 픽스처에 맞춰 고칠 부분은 나오지 않았다. 재확인된 사항:

- `medias.json` 12건 = 릴스 3(`DbV7LgZsKG8`·`DbTV2SAum6h`·`DbQei8FCh7i`) + 캐러셀 6 + 피드 3.
  릴스만 `save_count`·`reshare_count`가 있고 `play_count`는 **12건 전부 부재** → clips 머지 없이는 조회수 전량 null.
- 클립 머지 커버리지: `clips.json`(앞 5건)에 릴스 3건이 모두 포함돼 열거 릴스의 조회수가 전부 채워진다.
- `medias.json` 첫 항목이 핀 고정 `Cq87mzyPyvs`(taken_at 1681336758 = 2023-04-12)라 **정렬 없이는 테스트가 실패한다** —
  §3 정렬 함정이 회귀 테스트로 고정됐다.
- `caption_text` 키는 v2 응답에 없다(`has("caption_text")` = false) → 폴백 분기가 항상 `caption.text`를 탄다.

### 8-2. 브리프 코드 대비 추가한 것(공개 시그니처는 불변)

| 항목 | 브리프 | 구현 | 이유 |
|---|---|---|---|
| `pages` 인자 | 무시(1페이지 고정, YAGNI) | `next_page_id` → `&page_id=` 커서 루프 | 인자를 조용히 무시하면 스윕이 `enumerate-pages` 설정을 반영하지 못한다. crawler `HikerV2ClipsFetcher`/`HikerDiscoverFetcher`의 검증된 관용구(`&page_id=` + URL 인코딩)를 그대로 사용 |
| 중복 | 없음 | `LinkedHashMap` 숏코드 dedupe | 페이지 경계에서 같은 게시물이 겹칠 수 있음 |
| 클립 페이징 | 1페이지 | medias와 동일 페이지 수 | 2페이지째 릴스가 조회수 없이 남는 비대칭 방지 |
| 쿼리 파라미터 | 문자열 연결 | `URLEncoder.encode` | `username`은 API 입력이고, clips 커서는 `==`로 끝나는 base64라 인코딩이 **필수**(`%3D%3D`) |
| `fetchPost` 빈 응답 | `getFirst()` (NoSuchElement) | `SubjectNotFoundException` | 게시물 삭제 시 호출자가 종결 처리할 수 있게 |
| `HikerFetchException` | 메시지 생성자만 | `(String, Throwable)` 추가 | `JdkHikerHttp`가 `IOException`을 감싸 던짐 |

콜 비용(§7)은 그대로 계정당 프로필 1 + 열거 `pages` + 클립 `pages`. 기본 `enumerate-pages: 1` 기준 3콜.

## 9. Task 6(등록 API) 추기 — 2026-07-29

- **원형(`raw.fetch_payload`) 적재를 전송 계층으로 옮겼다** — `RecordingHikerHttp`(HikerHttp 데코레이터)가
  성공 응답을 콜 단위로 저장한다. 파싱 결과(`PostInfo.rawJson`)를 호출자가 저장하던 방식은
  **콜 3개 중 1개만 남았다**: `rawJson`은 그 게시물이 아니라 body 전체라 열거 12건이 같은 값을 공유하고,
  clips 응답과 2페이지 이후는 아예 기록되지 않았다. 이제 §7의 콜 수(계정당 3콜)와 적재 행 수가 1:1이다.
  kind·subject는 경로로 판정한다: `by/username`→`PROFILE`/username, `medias`·`clips`→`POSTS`/user_id,
  `media/by/code`→`POST`/code.
- **`fetchPost`는 `user.username` 부재를 셰이프 이상으로 본다**(`HikerFetchException` → 502).
  단건 응답에는 usernameHint가 없어 여기가 소유 계정의 유일한 출처이고,
  `post_snapshot.username`·`target.username`이 둘 다 NOT NULL이라 없으면 적재 자체가 불가능하다.

### §9-1. 원형 적재의 트랜잭션 한계 (Task 6 리뷰 보류 — Task 8에서 재검토)

- 데코레이터의 raw 저장이 `CollectService`의 `@Transactional` 안에서 돌아 **수집 실패 시
  원형도 함께 롤백**된다. 예: `collectPost`의 셰이프 이상(502) 경로 — 왜 이상했는지 확인할
  유일한 증거인 응답 body가 사라진다.
- Task 8 권고: `REQUIRES_NEW`보다 먼저 **fetch를 트랜잭션 밖으로 빼고 쓰기만 짧은
  트랜잭션으로** 묶는 구조를 검토할 것 — 원형 자동 커밋 + Hiker 레이턴시 동안 커넥션
  점유(스윕 N대상×3콜의 풀 고갈 위험)가 한 번에 풀린다.
- 부기: raw.fetch_payload의 subject는 PROFILE=username, POSTS=user_id(숫자 pk), POST=short_code —
  계정 단위 감사 쿼리는 PROFILE 행의 payload에서 pk를 얻어 2단계로.

## 10. P2 실측 추기 (2026-07-30) — 댓글·share 해소

대상: `rarebeauty` 릴스 `DbV7LgZsKG8`(픽스처 §6과 동일 게시물). 총 4콜.

### 10-1. 댓글 — `GET /v2/media/comments?id=<media_pk>` (확정)

- **media_pk는 저장 없이 shortcode에서 산술 유도**된다: shortcode = pk의 base64url 인코딩
  (알파벳 `A-Za-z0-9-_`). 실측 검증: `DbV7LgZsKG8` → `3951324523536622012` 일치.
- 응답 셰이프: `{ response: { comments: [...], has_more_comments, next_min_id, ... }, next_page_id }`
  — **1콜 15건**, 커서는 medias/clips와 같은 최상위 `next_page_id`.
- 정렬은 IG 기본(랭킹, `is_ranked: true`) — 최신순 아님. 정렬 파라미터 없음.
- 댓글 필드: `pk`(댓글 ID, **문자열**), `text`, `comment_like_count`,
  `created_at_utc`(epoch seconds), `user.username`·`user.full_name`·`user.profile_pic_url`.
- **답글은 `preview_child_comments[]`로 동봉**되며 자식에 **`is_created_by_media_owner`
  불리언이 있다** — 작성자 본인 답글 판정에 별도 `/v2/media/comments/replies` 콜 불필요.
  ⚠️ 협업(coauthor) 게시물에서는 media owner ≠ 추적 계정일 수 있다(실측: rarebeauty
  그리드의 이 릴스에서 owner는 sephora). 판정은 `is_created_by_media_owner ∨
  (자식 user.username = 게시물 username)`로.
- 픽스처: `hiker/comments.json`(자식 있는 3건 + 없는 3건으로 축약, 메타 원본 유지).

### 10-2. share 해소 — `GET /v2/media/info/by/url?url=<원문 URL>` (채택)

- 응답: `{ media_or_ad: {...}, status }` — `code`·`user.username`·`product_type` 존재.
  스펙 명세: 200=정상 / 404=삭제·비접근 / 400=URL 불량 → 계약 에러 어휘로 1:1 매핑.
- `/v1/share/by/url`은 스토리·하이라이트(`/s/` 링크) 전용이라 게시물 share 토큰에는
  부적합. `/v1/share/reel/by/url`(릴스 전용)은 폴백 후보로 남김.
- ⚠️ **실제 `instagram.com/share/…` 토큰 실측은 잔여**(샘플 확보 불가 — 일반
  `/reel/` URL로만 셰이프 검증). 픽스처: `hiker/media-info-by-url.json`(미디어 버전
  배열 등 무거운 키 제거, 파서 필드 생존).
