-- 자체 댓글 GraphQL 페이지네이션(doc_id·friendly_name) 기준값 — 기존 env 주입(IG_COMMENT_DOC_ID 등)을
-- app_setting으로 이전(런타임 토글, 재배포 없는 회전 대응). doc_id는 IG가 2~4주 주기로 회전한다(실측) —
-- 회전 시 이 값을 UPDATE로 갱신한다(CLAUDE.md 런타임 토글 규칙, 기준값은 마이그레이션으로 시드).
INSERT INTO app_setting (key, value) VALUES
    ('ig-source.comment-doc-id', '27659279553772821'),
    ('ig-source.comment-friendly-name', 'PolarisLoggedOutDesktopWWWPostCommentsPaginationQuery')
ON CONFLICT (key) DO NOTHING;
