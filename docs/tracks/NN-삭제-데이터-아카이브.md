# NN — 삭제 데이터 아카이브

- **상태**: ✅ 운영 배포 완료(2026-08-11 — PR #313 develop 머지 → #401 staging → #402 main)
  - 머지 시 흡수분: PR 대기 9일 사이 develop에 생긴 app 테이블 5종(notices·notice_items·notice_seen·brand_monitorings·brand_direct_posts)을 가드 테스트가 잡아내 카탈로그 배선 완료. notices/notice_items는 어드민 삭제 경로가 실존해 EXCLUDED가 아닌 `NOTICE_DELETED` 사유로 편입. brand_direct_posts는 monitoring_items CASCADE 제거(V20260811090500) 후 두 삭제 경로 모두 명시 아카이브. 마이그레이션은 out-of-order 회피로 `V20260811082031`로 재채번
- **설계 문서**: [specs/2026-08-02-delete-archive-design.md](../superpowers/specs/2026-08-02-delete-archive-design.md)
- **구현 계획**: [plans/2026-08-02-delete-archive.md](../superpowers/plans/2026-08-02-delete-archive.md)

## 목표

was의 삭제 로직은 사실상 전부 hard delete였다. 사용자가 만든 데이터가 삭제되는 순간
복구 경로 없이 사라지고, 그중 상당수는 서비스 개선에 쓸 수 있는 행동 시그널이었다.
이 트랙은 **삭제되는 행을 원본 그대로 축적**한다 — 집계·요약이 아니라 행 단위 보존이다.

전면 soft delete는 기각했다. was 조회가 JdbcClient 생 SQL이라 `deleted_at IS NULL` 누락을
컴파일러가 못 잡고, 하나만 빠지면 삭제한 데이터가 사용자에게 다시 보인다. `users.email`
UNIQUE 충돌과 `ON DELETE CASCADE` 무력화도 따라온다. 대신 **삭제 직전 같은 트랜잭션으로
아카이브 테이블에 이관**하고, 라이브 삭제·조회 경로는 한 줄도 바꾸지 않았다 — 위 결함이
원리적으로 발생하지 않는다.

## 범위

- **스키마**: was에 `archive` 스키마 신설 + 단일 범용 테이블 `archive.archived_rows`
  (`table_name`, `row_pk` jsonb, `user_id`, `payload` jsonb, `archived_at`, `archived_reason`).
  원본 테이블별 미러 대신 `payload jsonb` 하나로 받는 이유는 원본 스키마가 바뀌어도
  아카이브 DDL이 따라갈 필요가 없기 때문(expand-contract 하에서 스키마가 자주 움직인다).
  마이그레이션은 `V20260811082031__delete_archive.sql`(순수 CREATE, 파괴적 변경 없음).
- **이관 경로 4종**(`ArchiveWriter` 컴포넌트 하나가 전담, `INSERT … SELECT`로 DB 안에서 완결):
  - **탈퇴** — `UserRepository.deleteAccount`. 테이블 9개(저장 2종 + CASCADE 자식 5종 +
    간접 CASCADE `monitoring_registration_entries` + `users`). 자식을 먼저 이관 → `users`
    이관 → 삭제(CASCADE가 그대로 수행). `users`만 직접 식별 컬럼 7종(`email`,
    `password_hash`, `name`, `nickname`, `phone_country_code`, `phone_number`,
    `profile_image_url`)을 뺀 가명화 형태로 이관 — `id`는 유지해 자식 행과의 조인을 살렸다
    (익명화가 아니라 가명화).
  - **저장 개별 해제** — `SavedRepository`·`V1SavedRepository` 양쪽(같은 SQL을 별 bean으로
    중복 구현하고 있어 두 곳 다 배선).
  - **캠페인 삭제** — `CampaignRepository.delete`.
  - **모니터링 등록 실패 롤백** — `MonitoringItemRepository.delete`.
  - 4경로 모두 fail-closed: 아카이브 INSERT가 실패하면 트랜잭션이 롤백돼 삭제도 일어나지
    않는다. 이관 건수와 삭제 건수를 `verifyMatched`로 대조해 "0건 이관 + N건 삭제" 같은
    조용한 유실도 막는다(탈퇴의 CASCADE 자식 6종은 삭제 건수를 코드에서 관측할 수 없어
    이 대조에서 제외).
- **가드 테스트 2종**:
  - `ArchiveInventoryTest` — `information_schema`에서 `app` 스키마 테이블 전체를 읽어, 각
    테이블이 `ArchiveTables.CATALOG`(ARCHIVED) 또는 사유가 적힌 `EXCLUDED` 중 하나에
    등재돼 있는지 검사한다. 새 테이블을 만들고 마이그레이션을 올리는 순간 분류 없이는
    CI가 깨진다.
  - `ArchiveCascadeReachabilityTest` — 삭제 뿌리 3개(`app.users`, `app.monitoring_campaigns`,
    `app.monitoring_items`)에서 `ON DELETE CASCADE`를 재귀로 타고 도달 가능한 테이블 전부가
    아카이브 대상인지 검사한다. 직접 FK만 보면 놓치는 간접 연쇄(`monitoring_registration_entries`)를
    이 재귀 검사가 잡았다.

## 범위 밖 (후속)

- 불필요 데이터 정리 배치 잡(아카이브 자체의 보관 주기·정리)
- 아카이브 → 라이브 복원 도구
- crawler·analytics 쪽 삭제 지점 아카이브(이번 트랙은 was 한정)
- 아카이브 데이터 분석 표면(어드민 조회 등)

## 미해결로 남긴 것

1. **탈퇴 롤백 시 외부 부작용 잔존**(설계 §10-1) — `AccountDeletionService`는 외부
   monitoring target 해지(fail-open) → DB 삭제(fail-closed) 순서다. 이 트랙이 DB 단계에
   이관 9건 + 건수 대조를 추가하면서 그 단계의 실패 확률이 올라갔다. 실패 시나리오:
   target 해지는 이미 되돌릴 수 없게 끝났는데 DB 삭제(이관 포함)가 예외로 롤백되면,
   계정은 살아있는데 그 유저의 target은 이미 전부 죽어 있다 — `monitoring_items`는
   롤백으로 되살아나 UI엔 "추적 중"으로 보이지만 실제 수집은 멈춰 있다. 재배치 후보는
   `targetIds` 확보 → `deleteAccount` 커밋 → **커밋 후** 해지(롤백 시 외부 부작용 0,
   fail-open 성질도 보존)지만, `AccountDeletionService`는 계획 범위 밖이고 기존 계약을
   바꾸는 변경이라 실행 여부는 별도 결정으로 남겼다.
2. **`signup_events` 재식별 경로**(설계 §4-4) — `users`를 가명화해도 `app.signup_events`가
   `email` 컬럼을 그대로 보유하고 가입 성공 시 `detail` jsonb에 `userId`를 담아, 아카이브의
   `table_name='app.users'` 행과 `detail->>'userId'` 조인 한 번으로 이메일이 복원된다.
   `signup_events`는 삭제 경로가 없어 이번 아카이브 대상은 아니지만, **법무 확인 시
   "가명화 아카이브 + 미삭제 signup_events" 조합으로 함께 올려야 한다** — 아카이브만
   떼어놓고 보면 실제 노출을 과소평가한다.

그 외 설계 §10에 기록된 리스크(디스크 무한 축적, 개인정보 보유의 법률 판단, V1 테이블
스키마 한정자 부재 등)는 낮은 우선순위로 문서에만 남겨두었다.

## 검증

- `./gradlew :was:test` — 전체 통과.
- `./gradlew test`(전체 모듈: crawler/analytics/was/monitoring) — 전체 통과.
- `./.github/scripts/check-migration-safety.sh develop` — PASS(순수 추가 마이그레이션,
  파괴적 변경 없음).
