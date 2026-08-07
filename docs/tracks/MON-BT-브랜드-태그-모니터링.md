# MON-BT — 브랜드 태그 모니터링

- **소속 트랙군**: 모니터링 트랙 — 2026-08-06 설계 확정: [specs/2026-08-06-brand-tag-monitoring-schedule-design.md](../superpowers/specs/2026-08-06-brand-tag-monitoring-schedule-design.md) (**주기·스키마는 같은 날 설계 재논의로 개정 — DECISIONS 08-06 개정 행이 정본**)
- **의존**: MON
- **상태**: ✅ (08-07 운영 개통 — 수집 파이프라인(PR #351) + was API 전체(PR #354, [spec 2026-08-07](../superpowers/specs/2026-08-07-brand-monitoring-was-api-design.md)) 급행 승격 배포, 스윕 크론 KST 03:00 가동)

## 내용

브랜드 회원가입 계정에 태그된 게시물 자동 모니터링(가입 시 자동 시작~탈퇴까지, 스케일 가정 2,000계정). 수집은 `/v2/user/tag/medias` 열거 단일 경로(단건 게시물 콜 전면 배제 — 태그 열거는 릴스 조회수 인라인, findings §11). **매일 전량 수집**(08-06 개정 — 감지/트래킹 구분 폐지): 브랜드마다 프로필 1콜(최신값 + 추이 일 1행) + 105개 깊이 열거(~5콜), 윈도우 90일 & 105개 안 전 게시물이 매일 1행 스냅샷. 댓글은 열거 `comment_count` > 저장값일 때만 최대 3콜 45개(기지 페이지 중단), 게시자 프로필은 `/v2/user/by/id` 브랜드 간 전역 캐시 최신 1행(미보유·30일 stale 등장 시만 콜). 복권 3종(저장·공유·리포스트)은 DB 적재·FE 미노출 — 재시도 콜 없이 부재=0·0 캐리 규칙 재사용. 비용 2,000계정 월 ~$550~600(매일 전량 개정 반영 — 사용자 수용).

구현(08-06): **전면 브랜드 전용 7테이블**(`brand_account`·`brand_tagged_post`(링크+댓글 게이트)·`brand_post_snapshot`·`brand_post_meta`·`brand_post_comment`·`brand_profile_snapshot`·`author_profile`) — 캠페인 테이블 불간섭(볼륨 격리 + 겹침 게시물 덮어쓰기 차단, DECISIONS 08-06 개정 행). fb 캐리포워드·역전파·0 캐리는 `BrandSnapshotRepository`에 동형 이식, 쓰기 경계는 `BrandSnapshotWriter`(알람 미경유). 진입점 `POST /api/brands`(동기 프로필 1콜 + 비동기 백필 `brandBackfillExecutor`, 멱등 replay)·`DELETE /api/brands/{username}`. 백필 상태는 `last_swept_on`(null=수집 준비 중)으로 판별. 스윕은 전용 크론(`monitoring.brand.schedule.sweep-cron`, 기본 비활성 — 운영 KST 03:00 권장) + 브랜드 단위 격리, 실패 시 다음날 백스톱. 윈도우 이탈 데이터는 영구 보존.

## 미결·후속

- ~~was 조회 API·FE 계약~~ → **구현 완료**(08-07, PR #354 — DECISIONS 08-07 행·[spec 2026-08-07](../superpowers/specs/2026-08-07-brand-monitoring-was-api-design.md)). FE 명세 대비 의도적 편차 5개는 FE 공유 필요(스펙 §2).
- ~~`/v2/user/by/id` 응답 셰이프 라이브 미실측~~ → **실측 반영**(08-07): 파라미터명이 `user_id`가 아니라 `id`(422 실측 핫픽스 4ab01545). 응답 셰이프는 by/username 동형 확인.
- ~~운영 크론 env 주입~~ → **가동 중**(08-07): KST 03:00(UTC 18:00), 캠페인 스윕(KST 02:00)과 시차 확보. 서버 override 선주입분을 레포 `deploy/compose.yaml`로 정합(드리프트 해소).
- Task 11(캠페인 v2)은 급행 머지로 리뷰 생략 → 08-07 사후 리뷰 실시(결과는 이 파일·DECISIONS 갱신분 참조).
