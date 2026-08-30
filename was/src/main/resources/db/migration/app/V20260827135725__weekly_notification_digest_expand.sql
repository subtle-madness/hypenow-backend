-- 주간 알림 다이제스트 개편(2026-08-27 설계 §6·§7·§8) — expand 단계.
-- 셋 다 additive다: ① 옵트아웃 CHECK 허용값 확대(CHECK·테이블·컬럼만 — 진짜 additive라
-- 구버전 코드가 새 값을 몰라도 위반이 없다), ② 신규 테이블 1개, ③ monitoring_digests에
-- DEFAULT 있는 ADD COLUMN 2개. DROP·RENAME·타입 변경·SET NOT NULL 없음.
--
-- 주의 — 기존 4종 옵트아웃 → WEEKLY_DIGEST 보수적 이관 INSERT는 여기 없다. 그 INSERT를
-- 이 마이그레이션에 넣으면 롤링 창에서 구버전 was의 `EmailOptOutRepository.findOptOuts`가
-- (toFront가 미지 값에 예외를 던지는 구현이라) WEEKLY_DIGEST 행을 읽는 순간 알림 설정
-- 조회 API가 500이 난다 — CHECK 확대와 달리 이 INSERT는 "새 데이터를 즉시 만들어 구코드가
-- 마주치게" 하므로 additive 논증이 적용되지 않는다. 이관은 배포(롤링 완료) 후 수동 SQL
-- 1회로 옮겼다(README §13-5 참조).
ALTER TABLE app.monitoring_email_opt_outs DROP CONSTRAINT monitoring_email_opt_outs_event_type_check;
ALTER TABLE app.monitoring_email_opt_outs ADD CONSTRAINT monitoring_email_opt_outs_event_type_check
    CHECK (event_type IN ('COLLECTION_STARTED', 'COLLECTION_ENDED', 'METRICS_HIDDEN',
                          'CONTENT_UNAVAILABLE', 'WEEKLY_DIGEST'));

-- ② 미표기 판정 알림 이력(설계 §8 "미표기 재판정 중복") — 게시물당 1회 알림 가드.
-- notified_week를 함께 들고 있어야 같은 주 재실행(따라잡기·재기동)이 자기가 방금 남긴
-- 이력에 걸려 그 주 알림을 스스로 지우는 자기무효화가 생기지 않는다.
CREATE TABLE app.ad_disclosure_notices (
    user_id       bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    short_code    text        NOT NULL,
    notified_week date        NOT NULL,   -- 알린 주의 시작일(월요일)
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, short_code)
);

-- ③ 주간 리포트 메일 발송 상태(설계 §6) — 다이제스트 행이 곧 발송 대장이다. 워터마크 없이
-- "안 보냈고 시도 상한 미달인 행"만 발송 대상이 되고, 실패는 시도만 올려 다음 따라잡기 틱이
-- 그 행만 다시 집는다(at-least-once). DEFAULT가 있는 ADD COLUMN이라 기존 행도 즉시 유효하다.
ALTER TABLE app.monitoring_digests
    ADD COLUMN email_sent_at  timestamptz,
    ADD COLUMN email_attempts smallint NOT NULL DEFAULT 0;
