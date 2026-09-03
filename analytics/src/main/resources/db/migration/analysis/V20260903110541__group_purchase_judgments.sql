-- 공동구매(공구) 판정 결과 저장 테이블(스펙 2026-09-03-group-purchase-judgment-design.md §4) —
-- 규칙(RULE) 확정분·LLM(애매분) 판정 양쪽을 이 테이블 하나에 기록한다. RULE 확정분도 저장하는
-- 이유는 was가 이 테이블 단일 조회로 6.21 groupPurchaseCount/hasGroupPurchase·6.4 groupPurchase
-- 뱃지를 끝내기 위함이다(미러 없이 같은 analysis DB를 직접 읽는다).
-- judged_caption_hash는 Java MessageDigest(MD5)로 계산 — monitoring AdDisclosureJudgeService와
-- 같은 원칙: 기록·비교 양쪽에 같은 알고리즘을 써서 언어 간 해시 불일치 리스크를 없앤다.
CREATE TABLE group_purchase_judgments (
    short_code          text PRIMARY KEY,
    verdict             boolean,
    tier                text NOT NULL,
    reason              text,
    judged_caption_hash text NOT NULL,
    judged_at           timestamptz NOT NULL,
    model               text
);

COMMENT ON TABLE group_purchase_judgments IS
  '공동구매(공구) 판정 결과 — 규칙 확정(RULE)·애매분 LLM(LLM) 판정을 함께 저장. was는 미러 없이 '
  '이 테이블을 직접 읽는다(같은 analysis DB). 스펙: docs/superpowers/specs/'
  '2026-09-03-group-purchase-judgment-design.md';
COMMENT ON COLUMN group_purchase_judgments.verdict IS
  '공동구매 여부. NULL = 미판정(LLM 실패·대기 — 다음 실행이 자동 재시도)';
COMMENT ON COLUMN group_purchase_judgments.tier IS
  '판정 근거 — RULE(규칙으로 확정) | LLM(애매분을 LLM이 판정)';
COMMENT ON COLUMN group_purchase_judgments.reason IS '판정 근거 한 줄(규칙 설명 또는 LLM 응답 reason)';
COMMENT ON COLUMN group_purchase_judgments.judged_caption_hash IS
  '판정 시점 캡션의 md5(Java MessageDigest, 캡션 NULL은 md5("")) — 값이 달라지면 재판정 대상';
COMMENT ON COLUMN group_purchase_judgments.model IS 'LLM 판정에 쓰인 모델명 — RULE 확정분은 NULL';

-- 리셋 런북(스펙 §5) — 사전·프롬프트를 고쳐도 기존 판정 행은 자동으로 재판정되지 않는다.
--
-- LLM 판정만 다시 태우기(프롬프트 수정 후 — RULE 확정분은 프롬프트와 무관하므로 건드릴 필요 없음):
--   DELETE FROM group_purchase_judgments WHERE tier = 'LLM';
--
-- 전체 재판정 강제(도구 어휘 사전을 고쳐 RULE 확정분도 다시 태워야 할 때 — 해시를 무효화해
-- 다음 GROUP_PURCHASE_JUDGE 실행이 전량을 후보로 재선정하게 한다):
--   UPDATE group_purchase_judgments SET judged_caption_hash = '';
