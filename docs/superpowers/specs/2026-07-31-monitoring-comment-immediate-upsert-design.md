> 상태: 🟢 활성

# 게시물 등록 시 댓글 즉시 수집 + 저장 멱등 전환 (monitoring)

## 배경

POST 모드로 게시물을 등록하면 댓글 *개수*(`comment_count`)는 즉시 채워지지만 댓글 **본문**은
채워지지 않는다. 본문은 `DailySweepJob.sweepComments`에서만 수집되므로 등록 직후 최대 24시간
동안 화면에 댓글이 비어 있다.

같은 코드 경로에 "매일 무조건 전량 재수집 + 전량 교체 저장"이라는 별도 문제가 있어, 즉시 수집만
넣으면 중복이 커진다는 우려로 두 건을 한 트랙에서 다룬다.

## 착수 전 검증 결과 (전제 2개가 뒤집혔다)

**1. 증분 fetch는 성립하지 않는다.**
HikerAPI 공식 OpenAPI 스펙상 `GET /v2/media/comments`의 파라미터는 `id`, `page_id`,
`can_support_threading`, `safe_int`뿐이다. `since`/`sort`/`order`/`count`는 없다
(`/v1/media/comments/chunk`에만 `min_id`/`max_id`가 있으나 이는 커서이지 시각 필터가 아니고,
monitoring은 v2만 채택했으며 v1은 미실측이다).

더 결정적으로, 응답 **정렬이 시간순이 아니라 IG 랭킹 혼합**이다. 근거 이중화:
- 팀 실측 기록 `docs/superpowers/plans/2026-07-28-monitoring-hiker-findings.md` §10-1 —
  "정렬은 IG 기본(랭킹, `is_ranked: true`) — 최신순 아님. 정렬 파라미터 없음."
- 픽스처 `monitoring/src/test/resources/hiker/comments.json`의 `created_at_utc`가 배열 순서로
  오름차순도 내림차순도 아니다.

따라서 워터마크(최종 수집 id 저장)도, 조기중단(첫 페이지에서 아는 댓글을 만나면 stop)도 신규
댓글을 누락시킨다. **신규 판별은 정렬이 아니라 pk 집합 차집합으로만 가능하다.**

**2. 등록 응답 10초는 사용자 대기 시간이 아니다.**
was `V1MonitoringRegistrationService.register`는 `@Transactional`로 DB만 쓰고 즉시
`TrackingItemResponse.pendingPost`를 응답한다. monitoring 호출은 `afterCommit` 콜백에서 비동기로
나간다. `MonitoringConfig`의 10초는 was→monitoring HTTP **read timeout**이다. 게다가
`JdkHikerHttp`의 재시도 정책(request-timeout 15s / max-retries 2 / backoff 2s 선형)상 재시도가
한 번만 발동해도 콜 수와 무관하게 이미 10초를 초과한다 — 이 예산은 이번 변경 이전부터 정상
응답에만 의존해 왔다.

**3. ACCOUNT 모드에는 공백이 없다.**
`DailySweepJob.runSweep`이 모든 스윕 라운드를 끝낸 **뒤** `sweepComments(targets.findActive())`를
호출한다. 스윕 중 첫 감지로 `markTracking`된 게시물은 그 시점에 이미 TRACKING이라 같은 스윕
런에서 댓글까지 수집된다. **24시간 공백은 POST 모드 등록에서만 발생한다.**

**4. 현재 저장은 매일 데이터를 잃고 있다.**
`CommentRepository.replaceForPost`가 `DELETE FROM post_comment WHERE short_code=?`를 무조건
선행한 뒤 그날 fetch한 최대 15건으로 채운다. 랭킹 정렬이므로 **어제 top-15에 있다가 오늘 밀린
댓글은 물리적으로 삭제된다.** was는 `commented_at DESC` 상위 8건(`TrackingItemAssembler.COMMENT_LIMIT`)을
서빙하므로, 이는 화면에서 댓글이 사라졌다 나타나는 플리커로 나타난다.

**5. 소비자는 was 하나뿐이다.**
`post_comment` / `CommentInfo` / `PostCommentRow` 전수 조사 결과 참조처는 monitoring과 was뿐이고
analytics에는 없다. 감정분석·키워드 매칭 등 전량을 요구하는 소비자는 없다. 알람이 보는
"comments"는 `post_snapshot.comments` 카운트 지표이지 본문 테이블이 아니다.

## 결정

**API 콜 절감은 포기한다.** 위 1번 때문에 워터마크·조기중단 어느 쪽도 불가능하다.

유일하게 성립하는 절감 레버는 **댓글 개수 델타 게이트**였다 — `sweepComments`가 스윕 라운드
뒤에 돌아 오늘자 `post_snapshot.comments`가 이미 있으므로, 어제 대비 무변동이면 콜을 건너뛸 수
있다(정렬 가정 불필요, 추가 콜 0, 새 테이블 불필요). **이번 트랙에서는 채택하지 않는다:**
monitoring이 07-30에 개통돼 비교할 스냅샷 이력이 없어 절감 폭을 실측할 수 없고, 모니터링 대상은
"지금 활발한 게시물"이라 개수가 매일 움직일 가능성이 높아 절감이 0에 수렴할 수 있다. 반면
"개수는 같은데 내용이 바뀐 날"이라는 오답 경로를 새로 만든다. 운영 데이터가 쌓인 뒤 무변동
비율을 실측해 별도로 판단한다.

**삭제 감지도 채택하지 않는다.** 랭킹 정렬 + `comment-pages=1`(15건) 조건에서 "이번 응답에
없음"은 삭제를 뜻하지 않는다. `last_seen_at` 기반 정리는 멀쩡한 댓글을 삭제된 것으로 오판한다.

## 설계

### 1. 등록 시 즉시 수집 (POST 모드만)

`RegistrationService.registerPost`에서 `collect.collectPost(shortCode)` 직후, 이미 확보된
`post.username()`을 넘겨 `collect.collectComments(shortCode, post.username())`를 호출한다.

- **best-effort**: try/catch로 감싸 실패는 로그만 남기고 등록은 성공시킨다. 당일 스윕이 이미
  백스톱이므로 실패 시 손실은 "현행 동작으로 되돌아감"뿐이다.
- **게시물 저장 뒤에 둔다**: 댓글이 느리거나 깨져도 지표는 이미 커밋된 상태가 되게 한다.
- **동기로 간다**: POST 등록이 Hiker 1콜 → 2콜이 된다. monitoring에 비동기 실행기를 새로 들이는
  복잡도가 이득을 넘고, 위 검증 2번대로 10초 예산의 성질을 바꾸지 않는다. 운영에서 실제 지연이
  문제로 드러나면 비동기 분리를 후속으로 다룬다.

ACCOUNT 모드 경로는 손대지 않는다(검증 3번).

### 2. 저장 멱등 전환

`CommentRepository.replaceForPost` → `upsertForPost`로 이름과 의미를 함께 바꾼다.

- `DELETE` 제거.
- `INSERT ... ON CONFLICT (short_code, id) DO UPDATE SET body, like_count, owner_reply_text,
  commented_at`. `body`를 갱신 대상에 넣는 이유는 IG 댓글 편집이 가능하기 때문이고,
  `commented_at`은 사실상 불변이지만 파싱 보정 시 수렴하도록 포함한다.
- `@Transactional` 유지.

**계약**: `post_comment`는 "지금까지 관측된 top-15 댓글의 **누적 합집합**"이며 행을 삭제하지
않는다. IG에서 삭제된 댓글은 DB에 남는다 — 삭제 판정이 불가능하므로 어설픈 추정보다 데이터
보존을 택한다. 이 문장을 코드 주석과 계약 문서에 명시한다.

**부수 효과(의도됨)**: 랭킹에서 밀린 댓글이 보존되므로 was의 `commented_at DESC 8` 서빙 품질이
좋아진다(플리커 해소, 후보 풀 확대). 반대로 `like_count`는 재관측되지 않으면 갱신되지 않는다.

### 3. 마이그레이션 없음

기존 PK `(short_code, id)`를 그대로 쓰고 컬럼을 추가하지 않으므로 Flyway 파일이 필요 없다.
V번호 채번 경합도 expand-contract 검토도 발생하지 않는다. GRANT도 무관하다
(`V2__read_surface.sql`의 `ALTER DEFAULT PRIVILEGES`가 이미 처리).

## 테스트

**반드시 다시 쓰는 것** — 바뀌는 동작을 단언하고 있어 그대로 두면 실패한다:
- `StoreTest.댓글은_게시물당_전량_교체_갱신된다` → "이전 id가 보존되고 새 id가 추가된다(합집합)"
  + "재관측 시 `like_count`·`body`가 갱신된다"로 교체.
- `DailySweepJobTest.댓글은_스윕마다_전량_교체_갱신된다` → 같은 취지로 교체.

**추가**:
- POST 등록 시 댓글 콜이 1회 나가고 저장까지 되는지.
- 댓글 콜 실패가 등록을 깨지 않는지(best-effort 격리).
- `RegistrationApiTest.SwitchableHiker`에 `/v2/media/comments` 스텁 추가 — 현재는 스텁이 없어
  `media-by-code.json`으로 fallthrough하며 조용히 빈 리스트를 반환한다. 스텁 없이는 새 동작이
  실질적으로 검증되지 않는다.

**영향 없음**: `HikerClientTest` 전체, dedupe·실패격리 테스트
(`같은_게시물을_추적하는_캠페인이_여러_개여도_댓글은_한_번만_수집한다` 등),
`댓글_교체는_다른_게시물에_영향을_주지_않는다`(합집합에서도 성립).

**주의**: `CollectService` 생성자 시그니처를 바꾸면 `DailySweepJobTest`의
`new CollectService(...)` 호출부 4곳이 전부 깨진다 — 이번 설계는 시그니처를 바꾸지 않는다.

## 변경하지 않는 것

`HikerClient.fetchComments`, `comment-pages` 기본값(1), 스윕 크론·short_code dedupe 로직,
ACCOUNT 등록 경로, was 쪽 코드 전부.
