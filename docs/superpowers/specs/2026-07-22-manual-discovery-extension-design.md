# 수동 발굴 등록 API + 크롬 익스텐션 설계

> 상태: 🟢 활성 · 2026-07-22

## 배경·목적

인플루언서 유입은 현재 크롤링 두 경로(`DiscoverJob` 해시태그 발굴, `SimilarJob` 유사계정)뿐이다.
운영자가 인스타그램을 직접 둘러보다 발견한 계정을 파이프라인에 넣을 수동 경로가 없다.
크롬 익스텐션으로 "지금 보고 있는 프로필"을 한 번의 클릭으로 crawler DB의 발굴 단계에 등록하고,
이후 판정(QualifyJob)→뷰티판정(BeautyJob)은 기존 파이프라인이 그대로 이어받게 한다.

## 결정 사항 (사용자 확정)

1. **네트워크 경로**: Caddy가 수동 등록 API 경로만 리버스 프록시로 공개, `X-Api-Token` 사전 공유
   토큰으로 인증. crawler 컨테이너는 계속 루프백 전용, 어드민 `/ui`·`/admin`은 비공개 유지.
2. **익스텐션 UX**: 툴바 팝업 방식 — 현재 탭 URL에서 username을 추출, 버튼 한 번으로 등록.
   인스타그램 DOM에는 손대지 않는다.
3. **판정 정책**: 수동 등록도 기존 파이프라인 그대로 — `DISCOVERED`로만 넣고 팔로워 범위 필터·
   뷰티판정은 기존 잡이 동일하게 처리. 출처는 `influencer_discovery`에 keyword `수동:크롬`.
4. **익스텐션 위치**: 이 레포 밖 — `hypenow-backend` 옆 별도 디렉토리
   `../hypenow-extension`(독립 git 저장소). 이 레포에는 서버 측 변경만 들어간다.

## 1. crawler — 수동 등록 API (신규)

- `POST /api/manual-discoveries` — body `{ "username": "..." }`.
- 서버 정규화: 소문자화, 앞의 `@`·공백 제거. 빈 값/형식 불량은 400.
- 동작:
  - 신규 → `influencer`를 `DISCOVERED`로 생성 + `influencer_discovery`에 keyword `수동:크롬`
    기록 (기존 `DiscoverJob` upsert 관용구 재사용, `discovered_post_short_code`는 null).
  - 기존 → 아무것도 바꾸지 않고 현재 상태만 응답 (반복 클릭으로 discovery 이력 스팸 방지).
- 응답: `{ "username", "created": true|false, "status", "beautyClass" }` —
  팝업이 "신규 등록됨" / "이미 있음 · QUALIFIED · 뷰티" 를 표시할 수 있게.
- 인증: `X-Api-Token` 헤더를 설정 토큰(`crawler.manual-discovery.token`, 환경변수 주입)과 비교.
  불일치·부재 시 401. 토큰 미설정 시 API 전체 비활성(503 또는 404) — fail-closed.
- 이 API에만 인증 적용. 기존 어드민 표면은 변경 없음.

## 2. 배포 — Caddy 라우팅

- `deploy/Caddyfile`: `/crawler/api/manual-discoveries` → `crawler:8080/api/manual-discoveries`
  한 경로만 리버스 프록시 추가. 그 외 crawler 경로는 계속 외부 미노출.
- 토큰은 서버 `.env` → compose 환경변수로 crawler에 주입.
- CORS 설정 불필요 — MV3 익스텐션은 `host_permissions`로 교차 출처 제약 없이 fetch.

## 3. 크롬 익스텐션 (별도 저장소 `hypenow-extension`, MV3, 순수 JS·빌드 도구 없음)

- **팝업**: 아이콘 클릭 → 현재 탭 URL에서 username 추출(`instagram.com/{username}/` 형태만.
  `/p/`·`/reel/`·`/reels/`·`/explore/`·`/stories/` 등 예약 경로는 "프로필 페이지가 아님" 안내)
  → username 표시 + [발굴 등록] 버튼 → POST → 결과 표시(신규/이미 있음+상태/오류).
- **옵션 페이지**: 서버 URL·API 토큰 입력, `chrome.storage.sync` 저장. 코드에 토큰 하드코딩 금지.
- UI 텍스트는 한국어.

## 4. 테스트·검증

- crawler: 신규 컨트롤러 `@WebMvcTest`(TDD) — 토큰 부재/불일치 401, 토큰 미설정 fail-closed,
  신규 등록, 중복 등록, username 정규화, 형식 불량 400.
- 익스텐션: URL 파서를 순수 함수로 분리해 `node --test` 단위 테스트, 팝업 동작은 수동 확인.
- 문서: ARCHITECTURE.md §5(작업 트랙)·§7(결정 기록) 갱신.

## 범위 밖 (YAGNI)

- 대량 등록·CSV 업로드, 등록 취소 UI, 익스텐션에서의 상태 조회 전용 화면, 인스타 DOM 주입.
