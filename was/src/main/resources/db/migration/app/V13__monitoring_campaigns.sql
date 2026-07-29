-- 마케팅 모니터링 캠페인 매핑 — was가 보관하는 (user, target, 멱등키) + 등록 2단계의 pending 상태.
-- target_id는 monitoring DB target.id의 논리 참조 (크로스 DB — FK 금지, saved_influencers.handle 관용구).
-- target_id NULL = 등록 1단계(pending): monitoring 호출 전 선저장, 호출 성공 시 UPDATE로 확정.
CREATE TABLE app.monitoring_campaigns (
    id               bigserial PRIMARY KEY,
    -- ON DELETE CASCADE: saved_* 의 "자식부터 순서 삭제" 관례와 다른 의도적 선택 — 탈퇴(deleteAccount)가
    -- 캠페인 보유 유저에서도 실패하지 않게 한다. 대가로 탈퇴 시 monitoring target은 cancel 없이 고아가
    -- 되지만 expires_at 자연 만료로 수렴(유계). 탈퇴 시 cancel 루프는 후속(스펙 §8).
    user_id          bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    registration_key uuid   NOT NULL UNIQUE,   -- was가 생성하는 멱등 키 (계약 §2-1)
    target_id        bigint,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX monitoring_campaigns_user_idx ON app.monitoring_campaigns (user_id);

-- confirmed 행의 (user_id, target_id) 중복 방어 — findByUserAndTarget의 단일 행 가정을 DB가 보증
-- (pending 행은 target_id NULL이라 제외)
CREATE UNIQUE INDEX monitoring_campaigns_user_target_uidx
    ON app.monitoring_campaigns (user_id, target_id) WHERE target_id IS NOT NULL;
