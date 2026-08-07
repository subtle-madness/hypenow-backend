# MON-BT — 브랜드 태그 모니터링

- **소속 트랙군**: 모니터링 트랙 — 2026-08-06 설계 확정: [specs/2026-08-06-brand-tag-monitoring-schedule-design.md](../superpowers/specs/2026-08-06-brand-tag-monitoring-schedule-design.md) (**주기·스키마는 같은 날 설계 재논의로 개정 — DECISIONS 08-06 개정 행이 정본**)
- **의존**: MON
- **상태**: 🔵 (08-06 수집 파이프라인 구현 완료 — PR #351 리뷰 대기 · was 조회 API·FE 계약은 범위 밖 — 후속 트랙)

## 내용

브랜드 회원가입 계정에 태그된 게시물 자동 모니터링(가입 시 자동 시작~탈퇴까지, 스케일 가정 2,000계정). 수집은 `/v2/user/tag/medias` 열거 단일 경로(단건 게시물 콜 전면 배제 — 태그 열거는 릴스 조회수 인라인, findings §11). **매일 전량 수집**(08-06 개정 — 감지/트래킹 구분 폐지): 브랜드마다 프로필 1콜(최신값 + 추이 일 1행) + 105개 깊이 열거(~5콜), 윈도우 90일 & 105개 안 전 게시물이 매일 1행 스냅샷. 댓글은 열거 `comment_count` > 저장값일 때만 최대 3콜 45개(기지 페이지 중단), 게시자 프로필은 `/v2/user/by/id` 브랜드 간 전역 캐시 최신 1행(미보유·30일 stale 등장 시만 콜). 복권 3종(저장·공유·리포스트)은 DB 적재·FE 미노출 — 재시도 콜 없이 부재=0·0 캐리 규칙 재사용. 비용 2,000계정 월 ~$550~600(매일 전량 개정 반영 — 사용자 수용).

구현(08-06): **전면 브랜드 전용 7테이블**(`brand_account`·`brand_tagged_post`(링크+댓글 게이트)·`brand_post_snapshot`·`brand_post_meta`·`brand_post_comment`·`brand_profile_snapshot`·`author_profile`) — 캠페인 테이블 불간섭(볼륨 격리 + 겹침 게시물 덮어쓰기 차단, DECISIONS 08-06 개정 행). fb 캐리포워드·역전파·0 캐리는 `BrandSnapshotRepository`에 동형 이식, 쓰기 경계는 `BrandSnapshotWriter`(알람 미경유). 진입점 `POST /api/brands`(동기 프로필 1콜 + 비동기 백필 `brandBackfillExecutor`, 멱등 replay)·`DELETE /api/brands/{username}`. 백필 상태는 `last_swept_on`(null=수집 준비 중)으로 판별. 스윕은 전용 크론(`monitoring.brand.schedule.sweep-cron`, 기본 비활성 — 운영 KST 03:00 권장) + 브랜드 단위 격리, 실패 시 다음날 백스톱. 윈도우 이탈 데이터는 영구 보존.

백필 단계식 ready(08-07 — DECISIONS 08-07 행): 등록 백필을 `sweepCore`(열거+적재, ~30초) / `enrich`(게시자+댓글, 수 분)로 분리 — core 직후 touchSwept(ready), 보강은 `brandEnrichExecutor`(신설) 별도 큐. 운영 실측(cclime_official 등록→ready 8.5분: 앞 계정 대기 5분 + 보강 콜 ~85%)이 근거. 보강 실패는 backfill_error 미기록(로그만) — 게시자 stale·댓글 워터마크로 다음 스윕 백스톱. 매일 스윕은 `sweep`(합본) 그대로.

## 미결·후속

- was 조회 API·FE 계약(스펙 §8 말미 — 이번 범위에서 명시 제외). `last_swept_on`이 "수집 준비 중" 판별 기준.
- `/v2/user/by/id` 응답 셰이프는 라이브 미실측(by/username과 동형 가정) — 운영 첫 콜에서 다르면 HikerFetchException으로 표면화, 게시자 단위 격리라 수집 본체는 계속 돈다.
- 운영 크론 env 주입(`cd-test.yml`/compose) — 개통 시점에 캠페인 스윕(KST 02:00)과 시차 확인.
