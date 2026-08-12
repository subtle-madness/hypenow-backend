#!/bin/sh
# monitoring 로그 실측 포맷 재현 — 외부 의존 실패 3종(Hiker 404 / Hiker 402 / IG HTTP 401).
while true; do
  TS=$(date -u +%Y-%m-%dT%H:%M:%S)
  echo "${TS}.073Z  WARN 1 --- [monitoring] [enrich-worker-5] c.celfit.monitoring.hiker.HikerClient    : 댓글 2페이지 실패 — 받은 14건은 보존(미완주): media 3910608935035842863 com.celfit.monitoring.hiker.SubjectNotFoundException: Hiker 404: {\"detail\":\"Entries not found\"}"
  echo "${TS}.181Z  WARN 1 --- [monitoring] [enrich-worker-3] c.celfit.monitoring.hiker.HikerClient    : 브랜드 프로필 조회 실패 — Hiker 402: 잔액 소진"
  echo "${TS}.264Z  WARN 1 --- [monitoring] [   sweep-worker] c.c.m.service.DailySweepJob              : 프로필 블록(HTTP 401) — 스킵"
  sleep 10
done
