> 상태: 🟡 개요 · 상세 계획 미작성 (2026-08-31)
> 범위: Phase 1 마일스톤 B(자체크롤 백엔드 신설). **선행: 마일스톤 A 완료 필수**(seam·모듈이 있어야 착수).
> 설계 정본: `docs/superpowers/specs/2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md`. 선행 실측: 메모리 `hiker-self-scraping-breakeven.md`.

# 인스타그램 수집 하이브리드 — Phase 1 마일스톤 B(자체크롤 백엔드) 개요

> **주의:** 이 문서는 **개요**다. 착수 시점에 이 골격을 마일스톤 A와 같은 상세도(TDD 스텝·정확 파일경로·전체 코드)로 풀어 `-milestoneB.md`를 재작성한 뒤 실행한다. 아래는 범위·구성·참조 자산·검증 축의 확정 골격이다.

**Goal:** `instagram-source` 모듈에 `SelfCrawlBackend`(저수준 HTTP + 문서표면 fetcher + DirectComment + 에러 taxonomy + 표면별 서킷)를 신설하고, `FailoverInstagramSource`에 "자체 1순위 + Hiker 폴백" 정책을 채운다. **토글 기본 off(=Hiker)로 배선** — 코드가 들어가도 런타임 동작은 마일스톤 A와 동일하고, 마일스톤 C가 점진 개통한다.

**핵심 원칙:** crawler 자산(팀 소유·운영 중, **수정 금지**)을 **참조·이식**하되, 스펙의 검증 완료 개선안(embed 문서표면·geo:kr·모바일 K=1·에러 taxonomy)을 처음부터 내장한 상위호환으로 새로 쓴다. crawler를 import하지 않는다.

## 신설 파일 골격 (`com.celfit.instagram.source`, 전부 순수 JDK)

| 파일 | 책임 | 이식·참조 원본(crawler, 읽기전용) |
|---|---|---|
| `SelfHttpTransport` (HikerHttp와 별개 인터페이스 or 확장) | 저수준 GET/POST, 프록시 로테이션, geo:kr 핀, fastfail, gunzip, 401 복원 | `JdkInstagramWebClient`(HTTP/2 고정·`x-ig-app-id=936619743392459`·UA·요청당 새 클라이언트+`shutdownNow()`·CONNECT 터널 auth) |
| `ProxyRotation` | 레지덴셜 K≈3 / 모바일 K=1, geo:kr(`__cr.kr` 유저네임), sticky 금지 | 신규(메모리 실측 근거 §로테이션 입도·401·transport-ab) |
| `ProxyConfig` (순수 record) | 레지덴셜·모바일 URL·타임아웃 보관 + `urlFor(tier)` | `ProxyProperties`(단, **Spring `@ConfigurationProperties` 제거** — 모듈은 순수 JDK, 값 주입은 monitoring이 함) |
| `ProxyTier` enum | RESIDENTIAL / MOBILE (+ geo:kr 플래그) | `ProxySource`(DIRECT/APIFY/DATAIMPULSE_*) 참조하되 모듈 스코프로 축소 |
| `EmbedPostFetcher` (문서표면) | `/p/{code}/embed/captioned/` — 정확 좋아요·댓글·조회수, **doc_id 불필요**, nav 헤더(`Accept text/html`·Sec-Fetch navigate·`x-ig-app-id` **빼기**) | 신규(메모리 실측 §embed exact — 20/20 100%) |
| `OgProfileFetcher` / `WpiProfileFetcher` (문서·API 표면) | 프로필 통계. og=문서표면(80~90%), wpi=web_profile_info(73%, 모바일 K=1) | `SelfProfileFetcher`(재시도 3·서킷 5연속·`isBlockStatus` 429/401/403·`web_profile_info` URL) |
| `DirectCommentBackend` | 비로그인 GraphQL 댓글 — 세션 lsd 부트스트랩 1회 + 커서 페이지네이션 | `DirectCommentFetcher`(`https://www.instagram.com/api/graphql`·`x-fb-lsd`·doc_id는 config) |
| `SelfCrawlBackend implements InstagramSource` | 위 fetcher들을 InstagramSource 계약으로 정규화(자체 가능 5경로만), 하드게이트 3종은 `UnsupportedOperationException`(정책이 Hiker로 라우팅) | — |
| `ErrorClass` (taxonomy) | 구조적400 / 회복가능401 / 전송TLS·Connect / 로그인리다이렉트HTML / 404notFound → 대응 라우팅(스펙 §8-1) | `SelfProfileFetcher`의 400·401·404 분기 + `JdkInstagramWebClient.isInterceptedServerUnauthorized` |
| `SurfaceCircuitBreaker` | 표면별(self-embed/self-wpi) 5연속 블록 트립 + self 전역 킬 | `SelfProfileFetcher.RATE_LIMIT_STREAK_LIMIT=5` 참조 |

## `FailoverInstagramSource` 확장 (마일스톤 A의 위임 seam을 정책으로)

- 경로별 폴백 사다리(스펙 §8-2): 게시물 단건 embed(레지 geo:kr, fastfail)→[서킷]→Hiker / 프로필 og(기본)→Hiker(핫패스 2단) / 댓글 Direct(레지 K≈3)→Hiker / 동기 등록=타이트 사다리(자체→곧장 Hiker) / share=self redirect→Hiker.
- 하드게이트 3종(`fetchTaggedPage`·`fetchAuthorProfile`·`fetchHashtagRecentPage`)은 **Hiker 단독** 그대로(자체 백엔드가 `UnsupportedOperationException`을 던지면 정책이 즉시 Hiker).
- 에러 taxonomy → 라우팅: 구조적400=재시도 없이 즉시 Hiker, 회복가능401=티어 내 로테이트+재시도, 전송실패=geo:kr+새 터널 1회, HTML 리다이렉트=다음 표면→Hiker, 404=종료(폴백 안 함).
- **프리미엄 지표(저장·공유·리포스트)는 self 모드에서 채우지 않는다** — 경로 자체 없음(스펙 §2 비목표). `fetchClipCounts`도 self 백엔드 미구현(embed가 조회수 흡수 → 마일스톤 C에서 재시도 경로 축소).

## monitoring 배선 추가 (마일스톤 B — 코드만, 개통은 C)

- monitoring 컨테이너에 프록시 env 추가: `DATAIMPULSE_RESIDENTIAL_PROXY_URL`·`DATAIMPULSE_MOBILE_PROXY_URL`(crawler와 동일 조달: `ssh hypenow 'docker exec deploy-crawler-1 printenv DATAIMPULSE_RESIDENTIAL_PROXY_URL'`). `deploy/compose.yaml`·`.env.example`에 monitoring 서비스 블록 항목 추가.
- monitoring에 순수 record `ProxyConfig` 값을 주입하는 `@ConfigurationProperties("monitoring.proxy")`(레지·모바일 URL) + `SelfCrawlBackend`·`Failover` 조립을 `HikerConfig`(또는 신 `InstagramSourceConfig`)에 추가. **기본 토글 off**라 `FailoverInstagramSource`가 self 경로를 타지 않음(=마일스톤 A와 동일 동작).

## 테스트 골격

- **저수준 전송**: JDK `com.sun.net.httpserver.HttpServer`로 프록시·헤더·gunzip·401복원·fastfail 검증(`JdkHikerHttpTest` 패턴 재사용).
- **문서표면 파서**: fixture(embed HTML·og HTML) → 정확 지표 추출 단위 테스트(fake transport 람다).
- **에러 taxonomy**: 상태코드·본문 셰이프별 `ErrorClass` 분류 표 테스트.
- **서킷**: 5연속 블록 트립·리셋 단위 테스트.
- **프록시 실측 스모크**(옵션, 게이트 off 기본): `DATAIMPULSE_*` env 있을 때만 도는 태그드 테스트 — 없으면 skip(POC 하니스 `poc/selfscrape-harness` 관례).

## 열린 리스크(스펙 §10 — 마일스톤 C 검증 대상)

- geo:kr 엔드포인트층 401 영향 미측정(전송층만 100%) → C의 실엔드포인트 A/B.
- Hiker 자체 지연 미측정 → C 벤치마크.
- IG embed 표면 변경 리스크(장수 엔드포인트나 0 아님) → Hiker 폴백 상시 + 킬 스위치(C).
- 모바일 꼬리 지연(26s 행) → fastfail 필수, wpi 폴백에만.

## 완료 기준(개요)

- [ ] `SelfCrawlBackend`가 자체 가능 5경로(단건 embed·프로필 og/wpi·최근열거·댓글·share)를 InstagramSource로 정규화.
- [ ] `FailoverInstagramSource`가 경로별 폴백 사다리 + 에러 taxonomy 라우팅 + 표면별 서킷 구현.
- [ ] **토글 기본 off** — `:monitoring:test` 결과가 마일스톤 A와 동일(행동 변화 0).
- [ ] 하드게이트 3종은 Hiker 단독 유지.
- [ ] 프리미엄 지표 경로 부재 확인.

**PR·배포·개통은 이 계획 범위 밖.** 실개통·검증은 마일스톤 C.
