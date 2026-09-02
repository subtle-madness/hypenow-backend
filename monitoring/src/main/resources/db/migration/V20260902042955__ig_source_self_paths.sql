-- 경로별(표면별) 자체크롤 토글 — 운영 점진 개통 시 "프로필만 빼고 켜기" 같은 부분 개통 제어 수단.
-- 토큰은 FailoverInstagramSource.route()가 넘기는 path 문자열(=metric path 태그)을 그대로 재사용한다
-- (별칭 매핑 없음): fetchProfile · fetchRecentPosts · fetchPost · fetchComments.
-- 시드는 전체 4경로 — 마스터 토글 ig-source.self-enabled가 여전히 false(off)라 행동 변화는 없다.
-- 운영 전환 시 UPDATE로 빼고 싶은 경로만 제외한다(예: 'fetchRecentPosts,fetchPost,fetchComments' →
-- fetchProfile만 Hiker 유지).
INSERT INTO app_setting (key, value) VALUES
    ('ig-source.self-paths', 'fetchProfile,fetchRecentPosts,fetchPost,fetchComments')
ON CONFLICT (key) DO NOTHING;
