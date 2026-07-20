# 인스타 비로그인 댓글 크롤 — 정찰 기록 (Task 7)

대상 포스트: `https://www.instagram.com/p/DYtaeT4TPYu/`
캡처일: 2026-07-09 (로그아웃 상태)

## 픽스처
- `post-page.html` — 서버가 반환하는 포스트 페이지 HTML(비로그인, curl). JS 셸이지만 **lsd 토큰 포함**.
- `comments-response.json` — 실제 `POST /api/graphql` 댓글 응답(로그아웃). DevTools에서 캡처.

## HandshakeExtractor 앵커 (post-page.html 기준)
- **lsd 토큰**: HTML에 `"LSD",[],{"token":"<TOKEN>"}` 형태로 존재. 정규식 `"LSD",\[\],\{"token":"([^"]+)"` 로 추출 가능(확인됨).
- **media_id**: shortCode를 base64 디코딩해 로컬 계산. `DYtaeT4TPYu` → `3903892884139341358` (응답의 `data.xig_polaris_media.id == "POLARIS_3903892884139341358"` 로 교차검증됨).
- **doc_id / friendly_name**: ⚠️ **서버 HTML에 없음.** 댓글 쿼리 doc_id는 별도 JS 번들(크로스오리진)에서 런타임 로드되며, 정적 HTML에서 추출 불가. → **설정값(`DirectCommentProperties`)으로 관리**한다. Task 12 스모크에서 실제 doc_id/variables를 확정한다(브라우저 워커에서 요청이 나가 자동 캡처 불가, DevTools "Copy as cURL"로 확보).

## CommentMapper 앵커 (comments-response.json 기준 — 실측)
- 댓글 배열 경로: `data.xig_polaris_media.comments_connection.edges[]` → 각 `.node`
- 각 node 필드:
  - username: `node.user.username`
  - text: `node.text`
  - created_at: `node.created_at` — **unix epoch seconds** (예: `1779661498`)
  - (부가) likes: `node.comment_like_count`, id: `node.pk`, verified: `node.user.is_verified`
- 페이지네이션: `data.xig_polaris_media.comments_connection.page_info`
  - `end_cursor` (이 캡처에선 `null`), `has_next_page` (이 캡처에선 `false` — edges 15개 단일 페이지)
- 응답 media id: `data.xig_polaris_media.id` = `POLARIS_<mediaId>`

## 스키마 호환 매핑 규칙 (CommentMapper 출력)
raw_comment 생성 컬럼(`writer`=`ownerUsername`, `text`, `written_at`=`timestamp`) + AggregateJob `groupComments`의 `postUrl` 그룹핑과 호환되도록:
- `postUrl` = `ShortCodes.postUrl(shortCode)` (매퍼 호출 시 주입)
- `ownerUsername` = `node.user.username`
- `text` = `node.text`
- `timestamp` = `node.created_at`(epoch seconds)를 **ISO-8601 UTC 문자열**로 변환 (액터 경로가 ISO 문자열을 저장하므로 형식 일치 — 예: `2026-05-...T..Z`)

## 미확정(스모크에서 확정)
- 요청 `doc_id`, `fb_api_req_friendly_name`, `variables` 정확한 키/형식 — DevTools cURL 또는 Task 12 실측.
- variables에 media_id를 어떤 키로 넣는지(예: `media_id`), 정렬/페이지 크기 파라미터 유무.
