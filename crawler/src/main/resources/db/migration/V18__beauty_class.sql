-- 뷰티 판정 v2 — 4분류 원본(beauty_class). boolean(beauty/beauty_company)은 파생값으로 유지된다.
-- BEAUTY_SERVICE(시술·서비스: 병원·에스테틱·헤어·네일 업체와 그 영역 개인)는 beauty=false로 파생.
alter table influencer add column beauty_class text
    constraint influencer_beauty_class_check
    check (beauty_class in ('INFLUENCER', 'COMPANY', 'BEAUTY_SERVICE', 'NOT_BEAUTY'));
-- 기존 판정분 백필 없음 — 전환 직후 전체 초기화(MANUAL 포함) 후 새 기준으로 재판정한다
-- (deploy/scripts/reset-beauty-judgments.sql).
