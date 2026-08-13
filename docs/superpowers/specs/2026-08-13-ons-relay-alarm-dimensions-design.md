# ONS 릴레이 알람 차원 표기 설계

> 상태: 🟢 활성 · 2026-08-13

## 문제

`hypenow-container-down` 알람이 디스코드에 올 때 본문이 "도커 컨테이너가 3분 이상 다운
상태입니다 (containerName 차원 확인)"뿐이라 **어느 컨테이너가 다운됐는지 알 수 없다**.
확인하려면 매번 `oci monitoring ... summarize-metrics-data`를 직접 쳐야 한다(README §9).

## 조사 결과 (2026-08-13, 서버 릴레이 로그 실측)

- ONS가 릴레이로 보내는 알람 JSON에 **차원이 이미 실려 온다**:
  `alarmMetaData[0].dimensions == [{"containerName": "monitoring"}]` (08-12 실제 알람 캡처).
  `metricValues`·`alarmSummary`·`alarmUrl`도 동봉.
- 알람은 이미 "Split messages per metric stream" 모드(`isStreamNotification: true`) —
  스트림(컨테이너)별로 갈라져 온다. **OCI 콘솔 쪽은 손댈 게 없다.**
- 유일한 문제는 `deploy/ons-discord-relay.py`가 `title`/`body`만 쓰고 dimensions를 버리는 것.
- test 스택(`test-was` 등)은 메트릭 게시 대상(`SERVICES`)에 없어 알람 대상이 아니다 —
  수동 정지가 정상 상태라 의도적으로 제외(README §11). 따라서 `containerName=monitoring`은
  항상 운영 컨테이너를 뜻하며, 운영/test 구분 표기는 불필요.

## 설계

**변경 대상: `deploy/ons-discord-relay.py` 단일 파일.** OCI 알람 정의·메트릭 게시
스크립트(`post-container-metrics.py`)는 무변경.

`handle_message`에서 `body.alarmMetaData[].dimensions[]`(dict 목록)를 순회해 `키=값` 쌍을
수집(중복 제거·순서 유지)하고, 있으면 본문 마지막에 한 줄 덧붙인다:

```
🚨 hypenow-container-down
도커 컨테이너가 3분 이상 다운 상태입니다 (containerName 차원 확인).
📍 containerName=monitoring
```

- **모든 차원 일반 처리** — 특정 키 하드코딩 없음. 디스크(`host=hypenow-api`)·버킷
  (`bucketName=hypenow-images`) 알람도 자동으로 대상이 표기된다.
- 파싱은 `.get()` 방어적으로: dimensions가 없거나 형태가 달라도 기존과 동일한 메시지가
  나간다. 기존 원칙 유지 — 1900자 절단, 처리 실패는 로그로만(릴레이는 죽지 않는다).

## 검증

1. **로컬**: 서버 로그에서 캡처한 실제 페이로드로 포맷 로직을 돌려 출력 확인
   (릴레이는 표준 라이브러리 단일 파일 — 포맷 부분을 순수 함수로 분리해 검증).
2. **엔드투엔드(배포 후)**: 같은 페이로드를 서버에서 릴레이에 curl 재주입해 디스코드에
   차원 포함 메시지가 실제로 오는지 확인(테스트 메시지 1건이 채널에 감).

## 반영

서버 `~/deploy/ons-discord-relay.py` 교체 + `ons-relay` 컨테이너 재시작
(볼륨 마운트 단일 파일 — 이미지 빌드·CD 무관. 서버 상태 변경이므로 실행 전 사용자 확인).
README §9의 "알람 본문에 차원이 안 실리니 메트릭으로 확인" 문구를 갱신한다.

## 범위 밖 (명시적 제외)

- test 스택 감시 추가 — 오탐 구조(수동 정지가 정상)라 별도 결정 필요. 이번 작업에서 안 다룬다.
- 알람 정의·임계값 변경, 릴레이의 다른 동작(구독 확인 등) 변경.
