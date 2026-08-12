#!/bin/sh
# 비-JVM(caddy JSON) 로그 — multiline 파이프라인을 타면 안 되는 쪽.
while true; do
  echo '{"level":"info","logger":"http.log.access","msg":"handled request","status":200}'
  echo '{"level":"error","logger":"http.log.access","msg":"handled request","status":502}'
  sleep 10
done
