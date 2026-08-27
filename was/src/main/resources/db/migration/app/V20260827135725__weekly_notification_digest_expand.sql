-- 주간 알림 다이제스트 개편(2026-08-27 설계 §6·§7·§8) — expand 단계.
-- 셋 다 additive다: ① 옵트아웃 CHECK 허용값 확대 + 보수적 이관 INSERT, ② 신규 테이블 1개,
-- ③ monitoring_digests에 DEFAULT 있는 ADD COLUMN 2개. DROP·RENAME·타입 변경·SET NOT NULL 없음.

-- ① 주간 이메일 수신 토글(설계 §5) — 별도 테이블을 만들지 않고 기존 옵트아웃 테이블의
-- event_type 어휘를 하나 넓힌다. 이미 아카이브 카탈로그·탈퇴 이관 경로에 배선된 테이블이라
-- 새 테이블을 만들 때 필요한 배선(ArchiveTables·ACCOUNT_DELETION_ORDER)이 통째로 불필요하다.
-- CHECK 확대는 허용 범위를 넓히기만 하므로 롤링 중 구버전 코드가 이 값을 몰라도 위반이 없다
-- (선례: V20260827060558 brand_post_meta_ad_verdict_check).
ALTER TABLE app.monitoring_email_opt_outs DROP CONSTRAINT monitoring_email_opt_outs_event_type_check;
ALTER TABLE app.monitoring_email_opt_outs ADD CONSTRAINT monitoring_email_opt_outs_event_type_check
    CHECK (event_type IN ('COLLECTION_STARTED', 'COLLECTION_ENDED', 'METRICS_HIDDEN',
                          'CONTENT_UNAVAILABLE', 'WEEKLY_DIGEST'));

-- 보수적 이관(설계 §7) — 기존 4종 중 하나라도 꺼 둔 유저는 주간 이메일도 off로 시작한다.
-- 구 4종 행은 그대로 남긴다(contract 단계에서 정리) — 롤링 창의 구버전 was가 아직 읽는다.
INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
SELECT DISTINCT user_id, 'WEEKLY_DIGEST' FROM app.monitoring_email_opt_outs
ON CONFLICT DO NOTHING;

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
