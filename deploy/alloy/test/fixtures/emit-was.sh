#!/bin/sh
# was 로그 실측 포맷 재현. 한 사이클 = INFO 1건 + (ERROR 헤더 + 스택트레이스 5줄) 1건.
while true; do
  TS=$(date -u +%Y-%m-%dT%H:%M:%S)
  echo "${TS}.374Z  INFO 1 --- [was] [nio-8081-exec-2] c.c.w.v.b.V1BrandAccountService          : 브랜드 계정 삭제 — 브랜드 연결만 해제 brandId=2"
  echo "${TS}.512Z ERROR 1 --- [was] [nio-8081-exec-7] c.c.w.v.c.V1ExceptionAdvice              : v1 처리 실패"
  echo "java.lang.IllegalStateException: 리그 테스트용 예외"
  printf '\tat com.celfit.was.v1.brand.V1BrandAccountService.delete(V1BrandAccountService.java:42)\n'
  printf '\tat com.celfit.was.v1.brand.V1BrandAccountController.delete(V1BrandAccountController.java:31)\n'
  echo "Caused by: java.sql.SQLException: 커넥션 없음"
  printf '\t... 3 more\n'
  sleep 10
done
