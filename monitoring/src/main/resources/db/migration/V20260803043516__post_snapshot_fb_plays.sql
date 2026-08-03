-- 릴스 조회수 세션 일관성(findings §2 결론 4) — FB 교차게시 몫을 별도 보존한다.
-- views는 화면 합산값(IG 몫 + fb_plays)으로 저장되고, fb_plays는 캐리포워드의 재료다:
-- fb 키가 안 실리는 세션(IG 전용)에 걸린 날은 직전 관측 fb_plays를 이어받아 views를 조립한다.
-- null = FB 몫 미관측(합산 세션에 아직 못 걸림), 0 = 관측된 0(교차게시 없음/재생 0).
ALTER TABLE post_snapshot ADD COLUMN fb_plays bigint;
