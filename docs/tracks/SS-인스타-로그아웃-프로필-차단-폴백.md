# SS — 인스타 로그아웃 프로필 차단 사고 · self→Hiker 폴백 누락 수정

- **소속 트랙군**: 인스타 수집(crawler 프로필 + monitoring `instagram-source`) — 하이브리드 설계 정본: [specs/2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md](../superpowers/specs/2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md). 하이브리드 Phase 1 트랙(RR)은 브랜치 `feat/selfcrawl-phase1-wrapup`에 있다 — 머지되면 이 트랙으로의 포인터 1줄을 그쪽에 추가할 것.
- **의존**: Hiker(폴백), DataImpulse 프록시, 메모리 `ig-logged-out-profile-block-incident.md`(사고 실측·임시 복구 기록 정본)
- **상태**: 🔨 (2026-09-05 코드 수정 완료·push, PR 승인 대기 → 배포 후 운영 토글 되돌리기 남음)

## 사고 (2026-09-02~, 09-04 확정)

인스타가 로그아웃 `web_profile_info`를 전면 401(`require_login`, `igweb_rollout`)로 차단 — 레지덴셜·모바일·서버 직결 전부 동일(IP 무관 정책). 게시물 embed·게시물 페이지는 200 정상. crawler COLLECT 잡(`profile.source=SELF_HIKER_FALLBACK`)이 하루 2,900명→31·76명으로 무너졌고 **실패는 어디에도 기록되지 않았다**(crawl_run FAILED 마지막 07-29). 09-04 12:47 UTC 임시 복구: 운영 app_setting `profile.source=HIKER_MOBILE`, `collect.batch-limit=15000`, 수동 실행.

사용자 원칙: **"self가 반복 실패하면 무조건 Hiker가 돌아야 한다. 안 넘기는 곳이 있으면 절대 안 된다."**

## 감사 위반 → 수정 (브랜치 `fix/self-hiker-fallback-gaps`, 2026-09-05)

| # | 위반 | 수정 |
|---|---|---|
| V1 | crawler `SelfProfileFetcher` 401/403/429 3회 소진 계정이 어느 out 리스트에도 안 담김 → 컴포지트 폴백은 400 ∪ 연속 빈응답만 | `collect(..., blockedOut)` 오버로드 추가, 컴포지트가 `blockedOut`을 COLLECT·QUALIFY 공통 즉시 `fallbackTargets`에 합류(3회 재시도가 게이트) |
| V6 | `catch (ApifyException)`이 재시도 루프 밖 → 407·타임아웃·커넥트 실패는 재시도·폴백 없음 | catch를 루프 안으로, 블록과 같은 3회 재시도 후 `blockedOut` |
| V3 | 방문당 1명 `collect()`라 `RATE_LIMIT_STREAK_LIMIT=5` 영원히 미도달(죽은 코드) | 컴포지트(싱글턴)에 프로세스 수준 헬스 게이트: 호출 5회 연속 전량 블록 → 강등(self 미호출, 전량 Hiker, `degraded_skip`), 10분 쿨다운 후 half-open 프로브 1회(CAS), 성공 시 복귀. 트립 WARN·복귀 INFO 각 1회. 죽은 코드 제거 |
| V2 | crawl_run 저장이 방문 트랜잭션에 합류 → `refreshProfile` 예외 시 롤백으로 행 소멸 | `CrawlRunFailureRecorder`(REQUIRES_NEW)가 `visitOne` catch(롤백 완료 지점)에서 FAILED + `error_message`(username 포함), actor `visit`, 방문당 1행. 404 소프트딜리트는 기록 대상 아님. QualifyJob은 fetch가 트랜잭션 밖이라 해당 없음 |
| V5 | QualifyJob 동일 컴포지트 | 블록 폴백은 잡 무관 공통으로 자동 해결. 빈응답 트랙 규칙(08-18) 불변 |
| V4 | crawler 도메인 메트릭 0개 | Micrometer 카운터 `crawler.profile.fetch{job,source,outcome}` — outcome: ok·blocked·bad_request·empty·not_found·degraded_skip / fallback_ok·fallback_not_found·fallback_empty·fallback_failed. 계정 1건 단위. **알람 룰·Grafana는 `feat/grafana-collection-alerts` 세션 담당** |
| V8 | instagram-source 댓글 2p+ 실패(doc_id 만료 등)가 `complete=false`로 partial 관측만 | `FailoverInstagramSource.route()`: partial이면 같은 콜에서 Hiker 승격(`fallback:PARTIAL`), Hiker도 실패 시 self 1p 결과 보존(`partial`) |
| V7 | `WpiProfileFetcher` 200+user 부재 → NOT_FOUND 확정(Hiker 재확인 없음) | OTHER로 던져 Hiker 재확인, Hiker 404일 때만 `SubjectNotFoundException`. HTTP 404의 NOT_FOUND는 유지 |
| V9 | `FeedUserPostsFetcher` `items:[]`를 정상 0건으로 | OTHER로 던져 Hiker 재확인(Hiker도 0건이면 진짜 0건) |
| V10 | `SurfaceCircuitBreaker` half-open 프로브 무제한·연속 카운터라 부분장애 미트립 / `SelfCrawlBackend` OTHER·5xx 서킷 미계상 / `SelfHttpClient` 15s > `SelfRetry` 예산 8s | **사실 확인만, 코드 미수정** — 해당 파일들이 `feat/selfcrawl-phase1-wrapup`과 겹친다. 후속 PR 후보 |

사실 아님으로 판정(손대지 않음): "LOGIN_WALL-on-200 dead"(살아 있음), "서킷 트립 후 복구 없음"(60s 쿨다운 half-open 존재).

## 검증

- `./gradlew :crawler:test` 536 tests 0 fail(Testcontainers 통합 포함) · `./gradlew :instagram-source:test` 212 tests 0 fail · monitoring 컴파일 확인(diff 없음).
- 단위 테스트로 고정한 시나리오: 401 3회→Hiker(COLLECT·QUALIFY) / ApifyException 3회 재시도→Hiker / 연속 5회 블록→degraded_skip→10분 후 프로브→복귀 / 400·빈응답 기존 동작 불변 / 카운터 태그별 증가 / 방문 실패 롤백 후 crawl_run FAILED 행 존속 + 다른 변경은 롤백(통합) / 댓글 partial→Hiker, Hiker 실패 시 partial 보존 / wpi user 부재→Hiker 재확인 / feed `items:[]`→폴백.

## 잔여

1. PR(사용자 승인 후) → develop → staging → main.
2. **배포 후 운영 app_setting 되돌리기**(crawler DB): `profile.source` HIKER_MOBILE→`SELF_HIKER_FALLBACK`, `collect.batch-limit` 15000→1000. 되돌린 뒤 `crawler.profile.fetch{outcome="blocked"|"degraded_skip"}`와 crawl_run FAILED 행으로 폴백이 실제로 타는지 확인(wpi 401이 지속되면 5회 후 강등→전량 Hiker가 정상 동작).
3. 알람·Grafana(별도 세션 `feat/grafana-collection-alerts`), V10 서킷 보강(wrapup 머지 후), Phase 2 crawler→instagram-source 이관.
4. `feat/selfcrawl-phase1-wrapup` 머지 시 `FailoverInstagramSource.route()`·`SelfCrawlBackend` 충돌 가능(양쪽 모두 소폭 추가) — 어느 쪽이 뒤에 머지되든 리베이스 필요.
