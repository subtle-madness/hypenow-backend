-- 하니스용 DB 초기화. 운영 db/init(01·02)은 그대로 못 쓴다:
--   * 01은 `CREATE DATABASE analysis OWNER crawler` — 하니스엔 crawler 롤이 없고(초기화 실패),
--     analysis는 POSTGRES_DB로 이미 만들어져 중복이다.
--   * 02는 monitoring·was_reader 롤까지 만드는데, 하니스는 데이터소스가 전부 dev 한 계정이라 불필요.
-- 하니스에 필요한 건 "monitoring DB가 dev 소유로 존재한다" 하나뿐이다(analysis는 POSTGRES_DB).
CREATE DATABASE monitoring OWNER dev;

-- 09-04 수집 회귀 감시 트랙: crawler DB도 같은 이유로 추가(운영 db/init 01은 `OWNER crawler` 롤을
-- 전제해 하니스에선 초기화가 깨진다 — dev 소유로 대체).
CREATE DATABASE crawler OWNER dev;
