# MON-BT — 브랜드 태그 모니터링

- **소속 트랙군**: 모니터링 트랙 — 2026-08-06 설계 확정: [specs/2026-08-06-brand-tag-monitoring-schedule-design.md](../superpowers/specs/2026-08-06-brand-tag-monitoring-schedule-design.md)
- **의존**: MON
- **상태**: 🔵 (08-06 수집 파이프라인 구현 완료 — PR 리뷰 대기 · 구현 계획 [plans/archive/2026-08-06-brand-tag-monitoring-impl.md](../superpowers/plans/archive/2026-08-06-brand-tag-monitoring-impl.md) · was 조회 API·FE 계약은 범위 밖 — 후속 트랙)

## 내용

브랜드 회원가입 계정에 태그된 게시물 자동 모니터링(가입 시 자동 시작~탈퇴까지, 스케일 가정 2,000계정). 수집은 `/v2/user/tag/medias` 열거 단일 경로(단건 게시물 콜 전면 배제 — 태그 열거는 릴스 조회수 인라인, findings §11) — 감지 매일 1페이지 1콜·트래킹 3일 1회 105개 깊이(감지 겸함), 윈도우 90일 & 105개(백필=트래킹 깊이 정합). 댓글은 열거 `comment_count` > 저장값일 때만 최대 3콜 45개(기지 페이지 중단), 게시자 프로필은 `/v2/user/by/id` 브랜드 간 전역 캐시(미보유·30일 stale 등장 시만 콜). 복권 3종(저장·공유·리포스트)은 DB 적재·FE 미노출 — 재시도 콜 없이 부재=0·0 캐리 규칙 재사용.

구현(08-06): 신규 3테이블(`brand_account`·`brand_tagged_post`·`author_profile`) + 스냅샷·메타·댓글은 기존 공용 테이블(post_snapshot·post_meta·post_comment·profile_meta) 재사용(SnapshotWriter 깔때기 — fb 캐리포워드·역전파 공짜 승계). 진입점 `POST /api/brands`(동기 프로필 1콜 + 비동기 백필, 멱등 replay)·`DELETE /api/brands/{username}`. 스윕은 전용 크론(`monitoring.brand.schedule.sweep-cron`, 기본 비활성 — 운영 KST 03:00 권장) + 브랜드 단위 격리, 트래킹 실패 시 last_tracked_on 미갱신으로 다음날 백스톱.

## 미결·후속

- was 조회 API·FE 계약(스펙 §8 말미 — 이번 범위에서 명시 제외).
- `/v2/user/by/id` 응답 셰이프는 라이브 미실측(by/username과 동형 가정) — 운영 첫 콜에서 다르면 HikerFetchException으로 표면화, 게시자 단위 격리라 수집 본체는 계속 돈다.
- 운영 크론 env 주입(`cd-test.yml`/compose) — 개통 시점에 캠페인 스윕(KST 02:00)과 시차 확인.
