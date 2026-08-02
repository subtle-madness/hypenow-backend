-- 모니터링 등록 entry 영구 pending 정산(트랙 LL) — result·reason_code 어휘에 'canceled' 추가.
-- 설계: docs/superpowers/specs/2026-07-31-monitoring-registration-pending-settlement-design.md
--
-- 롤링 안전성: 값 추가는 제약을 넓히는 방향이라 구 코드가 위배할 일이 없다. 구 코드는 result를
-- String으로 통과시키기만 하므로(RegistrationResponse.Entry.from) 'canceled'를 읽어도 깨지지 않는다.
-- expand 단계로 안전(deploy/README.md §5-1). DROP CONSTRAINT는 migration-guard의 DESTRUCTIVE
-- 정규식에 없어(스크립트 주석에 알려진 한계로 명시) 차단되지 않지만, 근거를 여기 남긴다 — CHECK
-- 제약 자체는 데이터를 없애거나 바꾸지 않고, 재생성한 제약이 구 값을 전부 포함해 기존 행에 영향 없다.
--
-- V16에서 인라인 CHECK로 정의돼 Postgres가 자동 이름을 붙였다
-- (monitoring_registration_entries_result_check / _reason_code_check) — 여기서 명시적 이름으로 교체한다.
ALTER TABLE app.monitoring_registration_entries
    DROP CONSTRAINT monitoring_registration_entries_result_check;
ALTER TABLE app.monitoring_registration_entries
    ADD CONSTRAINT monitoring_registration_entries_result_check
        CHECK (result IN ('success', 'failed', 'duplicate', 'pending', 'canceled'));

ALTER TABLE app.monitoring_registration_entries
    DROP CONSTRAINT monitoring_registration_entries_reason_code_check;
ALTER TABLE app.monitoring_registration_entries
    ADD CONSTRAINT monitoring_registration_entries_reason_code_check
        CHECK (reason_code IN ('invalid_format', 'not_found', 'private_account',
                                'share_link_unresolved', 'duplicate', 'internal_error', 'canceled'));

-- 소급 보정 — 취소된 item(canceled_at IS NOT NULL)에 매달린 pending entry를 정산한다. 실행기·
-- cancel API 양쪽 모두 지금까지 이 갱신을 하지 않아(설계 §2) 실측 운영에서 11시간 넘게 pending으로
-- 갇힌 행이 있었다(registration id=2). ON CONFLICT 걱정 없는 단순 UPDATE라 재실행해도 멱등
-- (이미 canceled인 행은 WHERE result = 'pending' 조건에 안 걸린다).
UPDATE app.monitoring_registration_entries e
SET result = 'canceled', reason_code = 'canceled', reason = '등록을 취소했어요.'
FROM app.monitoring_items i
WHERE e.item_id = i.id
  AND e.result = 'pending'
  AND i.canceled_at IS NOT NULL;

-- 소급 completed_at — 위 보정으로 pending entry가 0건이 된 registration만 채운다(이미 완료된 행은
-- completed_at IS NULL 조건이 걸러내 재실행해도 값이 안 바뀐다).
UPDATE app.monitoring_registrations r
SET completed_at = now()
WHERE r.completed_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM app.monitoring_registration_entries e
      WHERE e.registration_id = r.id AND e.result = 'pending'
  )
  AND EXISTS (
      SELECT 1 FROM app.monitoring_registration_entries e
      WHERE e.registration_id = r.id
  );
