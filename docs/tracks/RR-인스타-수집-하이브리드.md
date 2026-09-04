# RR — 인스타 수집 하이브리드(Hiker + 자체크롤) Phase 1

- **소속 트랙군**: monitoring 수집 — 설계 정본: [specs/2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md](../superpowers/specs/2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md) · 마일스톤 C 개요: [plans/2026-08-31-instagram-selfcrawl-hybrid-phase1-milestoneC.md](../superpowers/plans/2026-08-31-instagram-selfcrawl-hybrid-phase1-milestoneC.md) · 코드 계획(완료): [plans/archive/…milestoneC-code.md](../superpowers/plans/archive/2026-08-31-instagram-selfcrawl-hybrid-phase1-milestoneC-code.md)
- **의존**: DataImpulse 프록시(레지덴셜·모바일), Hiker(폴백), 메모리 `instagram-selfcrawl-hybrid-phase1-plan.md`
- **상태**: 🔨 (1단계 개통 완료 · 관측 공백 수정 push · 2단계 토글은 사용자 승인 대기)

## 내용

`instagram-source` 모듈의 `FailoverInstagramSource`(self 1순위 → 실패 시 Hiker 폴백)로 monitoring 수집을
하이브리드화한다. 배선은 `HikerConfig` 빈 4개: `instagramSource`(@Primary, 배치용 self 1순위 + `SelfRetry(3, 8s)`
+ `SurfaceCircuitBreaker(5)`), `syncInstagramSource`(동기 경로, Hiker 1순위 + self 구조 1회 2초),
`userTriggeredInstagramSource`(`ToggledInstagramSource`로 위 둘을 `ig-source.self-user-triggered`로 전환),
`metricsRetryInstagramSource`(생 Hiker). 런타임 토글은 monitoring `app_setting`(`ig-source.*`, TTL 5초).

**1단계 개통(2026-09-03)**: `ig-source.self-enabled=true`, `ig-source.self-paths=fetchPost,fetchComments`. 야간
스윕(UTC 17:00)·기동 백필·보강이 게시물 단건·댓글을 self로 먼저 시도한다. 09-04 24시간 실측(Prometheus
`instagram_source_route_total`): fetchPost self ok 13,413 / hiker fallback:OTHER 682(서킷 단락)·TRANSPORT 14,
fetchComments self ok 2,154·partial 4 / fallback LOGIN_WALL 23·OTHER 19·TRANSPORT 8. 폴백률 fetchPost ≈5%,
fetchComments ≈2%.

**판정 규약(2026-09-04 확정)**: self 건전성은 self 시리즈 유무가 아니라 **hiker `fallback:*` 비율**로 본다.
self를 쓰는 워크로드(기동 백필·야간 스윕)가 없으면 self 시리즈도 없다. 09-04 "self 사망" 의심은 이 규약이
없어 생긴 오진(원인은 기동 백필 워크로드 소진, self 정상).

**관측 공백 수정(2026-09-04, 브랜치 `feat/selfcrawl-phase1-wrapup`)**: ① `FailoverInstagramSource` self→Hiker
폴백에 rate-limited WARN(path·surface·에러클래스·메시지, 키별 30초 1건 + 억제 건수) ② `SurfaceCircuitBreaker`
트립·half-open·복구 로그 + monitoring 게이지 `instagram.source.self.circuit.open{surface,source=batch|sync}`
③ Grafana `[흐름] 비용·외부 의존`에 "③ 자체크롤(self) 건전성" 행(라우팅 결과 rate·폴백 비율·서킷 상태).
`SelfCrawlException`에 surface가 붙는다(서킷 표면 = embed/comment/wpi/og/feed, path 이름이 아님).

**self 확장 보류(2026-09-04 실측)**: 인스타가 09-02부터 로그아웃 프로필 표면을 막았다(`web_profile_info` 401
`require_login`, 다른 세션 확정). `fetchRecentPosts`가 쓰는 `api/v1/feed/user/{id}/?count=12`도 **운영 프록시
경유 실측 3/3 전부 401 `{"message":"Please wait a few minutes before you try again.","require_login":true,
"igweb_rollout":true}`**(레지덴셜·모바일, 실계정 2개). 같은 프록시로 embed는 200이라 프록시 문제가 아니라
인스타 정책. 따라서 `fetchProfile`·`fetchRecentPosts`의 self 확장은 차단 해제 실호출 확인 전까지 보류.
재확인 명령은 메모리 `instagram-selfcrawl-hybrid-phase1-plan.md` 참조.

**2단계(사용자 트리거 self 1순위) 개통 준비**: 토글 `ig-source.self-user-triggered=true`면 등록 백필 4흐름
(`CollectService.retryReelsMetricsUserTriggered`, `BrandCollectService.sweepCoreUserTriggered`·
`enrichUserTriggeredDeferred`·`enrichUserTriggered`)이 @Primary 빈(self 1순위·서킷·재시도 예산 공유)을 탄다.
영향 범위는 self-paths에 든 `fetchPost`(릴스 지표 재시도)·`fetchComments`(보강 댓글)뿐이고 프로필·태그·클립은
게이트 off라 그대로 Hiker. 4흐름 모두 HTTP 응답 이후 백그라운드(CompletableFuture·백필 실행기)에서 돌아
**동기 응답 지연은 0**이며, 지연은 완주 스탬프(`backfill_completed_at`) 밖 후행 단계(댓글)에만 얹힌다.
worst case 호출당 self 3회 재시도(8초 예산) + Hiker 1콜. 서킷은 야간 스윕과 공유되므로 스윕 중 트립되면
사용자 흐름도 즉시 Hiker로 단락된다(의도된 동작). 켜기 전 확인: (a) 24h 폴백률 fetchPost·fetchComments 각
10% 미만 (b) 서킷 게이지 전 표면 0 (c) fetchComments partial 비율 1% 미만(doc_id 유효) (d) 프록시 잔액.
롤백은 SQL 한 줄, 5초 반영:
`UPDATE app_setting SET value='false' WHERE key='ig-source.self-user-triggered';`
(monitoring DB). 토글 실행은 사용자 승인 후.

**범위 밖**: crawler 모듈의 프로필 수집(별도 사고 트랙, 메모리 `ig-logged-out-profile-block-incident.md`),
Phase 2(crawler를 instagram-source로 이관).
