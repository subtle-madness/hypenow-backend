-- 캠페인 일일 스윕의 target 전수 스캔 제거(2026-09-03 monitoring 풀스캔 점검).
--
-- target은 삭제가 없어 EXPIRED·CANCELED·FAILED 행이 영구 누적되는데, 스윕이 매번 도는 두 조회가
-- 활성 행만 필요하면서도 테이블 전체를 걷는다:
--   1) TargetRepository.findActive — SELECT * FROM target WHERE status IN ('WATCHING','TRACKING')
--      ORDER BY id. DailySweepJob이 재시도 라운드마다 다시 읽어 스윕 1회에 4~5번 실행된다.
--   2) TargetRepository.expireOverdue — UPDATE target ... WHERE status IN ('WATCHING','TRACKING')
--      AND expires_at < now(). 스윕 첫 문장.
-- 기존 부분 인덱스 3개(idx_target_active·idx_target_tracked_short_code·idx_target_user_tracked)는
-- 모두 username·tracked_short_code·user_id가 선두라 status 단독 조건이나 expires_at 범위·id 정렬을
-- 태우지 못한다.
--
-- 둘 다 활성 행(WATCHING/TRACKING)만 담는 부분 인덱스라 크기는 활성 캠페인 수에 비례하고, 누적된
-- 종료 행이 아무리 많아져도 스캔 비용은 늘지 않는다.
--   - (id) 부분 인덱스: findActive의 ORDER BY id를 인덱스 순서로 그대로 내보내 정렬도 없앤다.
--   - (expires_at) 부분 인덱스: expireOverdue의 범위 조건을 태운다.
--
-- CONCURRENTLY 미적용 — 선례(V20260827171444)와 같은 판단. target의 쓰기는 등록 요청과 일일 스윕
-- 뿐이고 마이그레이션은 배포 기동 시점에 돌아 겹치지 않는다. 테이블이 크게 자라면 트랜잭션 밖 별도
-- 마이그레이션으로 CONCURRENTLY 재고할 것.
CREATE INDEX idx_target_active_id ON target (id)
    WHERE status IN ('WATCHING','TRACKING');
CREATE INDEX idx_target_active_expires_at ON target (expires_at)
    WHERE status IN ('WATCHING','TRACKING');
