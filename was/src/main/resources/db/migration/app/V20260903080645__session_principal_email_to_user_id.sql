-- 세션 principal 이메일→userId 전환(트랙 A 09-03)의 데이터 이전 — 코드 전환 후 요청이 닿은 세션은
-- spring-session-jdbc가 PRINCIPAL_NAME을 자가 재기록하지만, 한 번도 안 쓰인 구 세션은 이메일로 남아
-- deleteOthers/deleteAll(userId)가 못 지운다(비밀번호 재설정의 탈취 세션 차단 목적 약화). 전량 이전한다.
-- 직렬화 blob은 형상 불변이라 그대로 유효. 롤링 창 동안 구 컨테이너는 이 세션들을 목록/개별 로그아웃에서 못 볼 뿐.
UPDATE app.spring_session s SET principal_name = u.id::text
FROM app.users u WHERE s.principal_name = u.email;
