-- monitoring 모듈 사설 DB + 계정 2종.
-- was_reader에는 접속 권한만 — 객체 GRANT는 monitoring Flyway(V2)가 소유자로서 부여한다.
CREATE ROLE monitoring LOGIN PASSWORD 'monitoring';
CREATE ROLE was_reader LOGIN PASSWORD 'was_reader';
CREATE DATABASE monitoring OWNER monitoring;
