-- allow-destructive: 참조 코드는 cc14c717(2026-07-29, 운영 배포 완료)에서 전면 철거 — contract 단계
-- 기능 철거 후 테이블만 남아, 플로우를 완주하지 않은 행의 이메일(개인정보)이 무기한 잔존한다.
-- 행에 TTL이 없고 탈퇴 캐스케이드도 이 테이블을 건드리지 않으므로 DROP으로 잔존 PII까지 일괄 처분.
DROP TABLE app.email_verifications;
