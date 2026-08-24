#!/bin/sh
# caddy 액세스 로그 파일 발생기 — 운영 /var/log/caddy/access.log의 실측 JSON 포맷 재현(2026-08-24 스펙).
# 다른 emit-*와 달리 컨테이너 stdout이 아니라 **공유 볼륨의 파일**에 append한다 —
# loki.source.file 파이프라인의 입력이라 service 라벨(compose 서비스명) 규칙과 무관하다.
# 로테이션 산출물(599)·test-access.log(598)는 미수집 검증용 — Loki에 나타나면 패턴이 샌 것이다.
mkdir -p /var/log/caddy
TS=$(date +%s)
printf '{"level":"info","ts":%s.111,"logger":"http.log.access.log0","msg":"handled request","request":{"method":"GET","host":"api.hypenow.io","uri":"/rotated"},"duration":0.01,"size":10,"status":599}\n' "$TS" > "/var/log/caddy/access-2026-08-24T00-00-00.000-size.log"
printf '{"level":"info","ts":%s.222,"logger":"http.log.access.log0","msg":"handled request","request":{"method":"GET","host":"dev-api.hypenow.io","uri":"/health"},"duration":0.01,"size":10,"status":598}\n' "$TS" > "/var/log/caddy/test-access.log"
while true; do
  TS=$(date +%s)
  printf '{"level":"info","ts":%s.123,"logger":"http.log.access.log0","msg":"handled request","request":{"remote_ip":"203.0.113.7","proto":"HTTP/2.0","method":"GET","host":"api.hypenow.io","uri":"/v1/me"},"duration":0.012,"size":123,"status":200}\n' "$TS" >> /var/log/caddy/access.log
  printf '{"level":"error","ts":%s.456,"logger":"http.log.access.log0","msg":"handled request","request":{"remote_ip":"203.0.113.7","proto":"HTTP/2.0","method":"POST","host":"api.hypenow.io","uri":"/v1/brand-monitoring/accounts"},"duration":4.7,"size":0,"status":500}\n' "$TS" >> /var/log/caddy/access.log
  sleep 10
done
