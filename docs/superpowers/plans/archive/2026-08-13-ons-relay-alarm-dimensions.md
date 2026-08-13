# ONS 릴레이 알람 차원 표기 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행됨 (Task 1~4 전체 — 서버 반영·재주입 검증 08-13 완료) · 2026-08-13 · [스펙](../../specs/archive/2026-08-13-ons-relay-alarm-dimensions-design.md)

**Goal:** `hypenow-container-down` 등 OCI 알람이 디스코드에 올 때 어느 대상(컨테이너·호스트·버킷)인지 차원(`containerName=monitoring` 등)을 본문에 표기한다.

**Architecture:** ONS 페이로드에 이미 실려 오는 `alarmMetaData[].dimensions`를 릴레이(`deploy/ons-discord-relay.py`)가 파싱해 본문 마지막 줄에 덧붙인다. 포맷 로직을 순수 함수로 분리해 서버 기동 없이 테스트한다. OCI 콘솔·알람 정의·메트릭 게시 스크립트는 무변경.

**Tech Stack:** Python 3.12 표준 라이브러리만 (별도 이미지 빌드 없음 — compose가 `python:3.12-alpine`에 파일 볼륨 마운트).

## Global Constraints

- 주석·커밋 메시지는 한국어. 커밋 prefix는 `feat(deploy):`/`docs:` 식.
- 릴레이는 죽지 않는 게 우선 — 어떤 파싱 실패도 메시지 발송 자체를 막으면 안 된다(기존 원칙).
- 디스코드 본문 1900자 절단 유지(`post_discord` 무변경).
- 의존성 추가 금지 — 표준 라이브러리만.
- 서버 상태 변경(파일 교체·컨테이너 재시작)은 **실행 전 사용자 확인** 필수.

---

### Task 1: 릴레이 포맷 로직 분리 + 차원 표기

**Files:**
- Modify: `deploy/ons-discord-relay.py`
- Test: `deploy/ons-discord-relay.test.py` (신규 — `backup.test.sh` 등 deploy 동반 테스트 파일 관례)

**Interfaces:**
- Produces: `format_alarm(body: dict, raw: str) -> str` — 디스코드로 보낼 content 문자열. `handle_message`가 호출. 테스트가 import해서 검증.

- [ ] **Step 1: `__main__` 가드 추가 (import 부작용 제거 리팩토링)**

`deploy/ons-discord-relay.py` 마지막 줄을 다음으로 교체:

```python
if __name__ == "__main__":  # 테스트가 import할 수 있게 서버 기동은 스크립트 실행 시에만
	HTTPServer(("0.0.0.0", 9099), Handler).serve_forever()
```

(기존: `HTTPServer(("0.0.0.0", 9099), Handler).serve_forever()` 단독 줄. 이 파일은 탭 들여쓰기 — 유지할 것.)

- [ ] **Step 2: 가드 동작 확인 — import는 즉시 반환, 스크립트 실행은 여전히 리슨**

```bash
cd deploy && DISCORD_WEBHOOK_URL=http://unused.invalid ONS_RELAY_TOKEN=unused python3 -c "
import importlib.util
spec = importlib.util.spec_from_file_location('relay', 'ons-discord-relay.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print('import OK (서버 미기동)')"
```

Expected: `import OK (서버 미기동)` 출력 후 즉시 종료 (멈춰 있으면 가드 실패).

```bash
cd deploy && DISCORD_WEBHOOK_URL=http://unused.invalid ONS_RELAY_TOKEN=unused python3 ons-discord-relay.py & sleep 1 && python3 -c "
import socket; socket.create_connection(('127.0.0.1', 9099), timeout=3); print('리슨 OK')" && kill %1
```

Expected: `리슨 OK` (스크립트 직접 실행 시 서버 기동 유지).

- [ ] **Step 3: 실패하는 테스트 작성**

`deploy/ons-discord-relay.test.py` 신규 (탭 들여쓰기):

```python
#!/usr/bin/env python3
# ons-discord-relay.py 포맷 로직 단위 테스트 — 서버 기동·네트워크 없이 순수 함수만 검증.
# 실행: python3 deploy/ons-discord-relay.test.py  (실패 시 AssertionError로 종료 코드 1)
import importlib.util
import os

os.environ.setdefault("DISCORD_WEBHOOK_URL", "http://unused.invalid")
os.environ.setdefault("ONS_RELAY_TOKEN", "unused")
_spec = importlib.util.spec_from_file_location(
	"relay", os.path.join(os.path.dirname(os.path.abspath(__file__)), "ons-discord-relay.py"))
relay = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(relay)

# 2026-08-12 운영 실물 페이로드(축약) — containerName 차원이 본문에 표기되어야 한다
firing = {
	"title": "hypenow-container-down",
	"body": "도커 컨테이너가 3분 이상 다운 상태입니다 (containerName 차원 확인).",
	"type": "OK_TO_FIRING",
	"severity": "CRITICAL",
	"alarmMetaData": [{
		"status": "FIRING",
		"dimensions": [{"containerName": "monitoring"}],
	}],
}
out = relay.format_alarm(firing, "")
assert out == ("🚨 **hypenow-container-down**\n"
	"도커 컨테이너가 3분 이상 다운 상태입니다 (containerName 차원 확인).\n"
	"📍 containerName=monitoring"), out

# 해소(FIRING_TO_OK)도 접미사와 차원이 함께 표기된다
resolved = dict(firing, type="FIRING_TO_OK")
out = relay.format_alarm(resolved, "")
assert "(해소됨 ✅)" in out and "📍 containerName=monitoring" in out, out

# 차원 없는 알람(구형·비알람 메시지)은 기존 포맷 그대로 — 📍 줄 없음
plain = {"title": "hypenow-disk-high", "body": "본문", "severity": "WARNING", "type": "OK_TO_FIRING"}
out = relay.format_alarm(plain, "")
assert out == "⚠️ **hypenow-disk-high**\n본문", out

# 여러 스트림·중복 차원은 순서 유지로 합치고, 형태가 이상한 항목은 조용히 건너뛴다
messy = {
	"title": "t", "body": "b", "severity": "CRITICAL",
	"alarmMetaData": [
		{"dimensions": [{"containerName": "was"}, {"containerName": "redis"}]},
		"이상한 항목",
		{"dimensions": [{"containerName": "was"}, "이상한 차원", {"host": "hypenow-api"}]},
	],
}
out = relay.format_alarm(messy, "")
assert out.endswith("📍 containerName=was, containerName=redis, host=hypenow-api"), out

# title/body 없는 원문 폴백도 기존 동작 유지
out = relay.format_alarm({}, "원문 그대로")
assert out == "🔔 **OCI 알림**\n원문 그대로", out

print("전체 통과")
```

- [ ] **Step 4: 테스트가 실패하는 것 확인**

Run: `python3 deploy/ons-discord-relay.test.py`
Expected: `AttributeError: module 'relay' has no attribute 'format_alarm'`

- [ ] **Step 5: `format_alarm` 구현 — `handle_message`의 포맷 부분을 함수로 추출하고 차원 표기 추가**

`deploy/ons-discord-relay.py`의 `handle_message` 마지막 6줄(`title = ...`부터 `post_discord(...)`까지)을 다음으로 교체:

```python
		post_discord(format_alarm(body, raw))
```

그리고 `Handler` 클래스 **위**(모듈 레벨, `post_discord` 아래)에 추가:

```python
def alarm_dimensions(body: dict) -> list[str]:
	"""alarmMetaData[].dimensions[]의 키=값 쌍을 순서 유지·중복 제거로 수집.
	형태가 예상과 달라도 조용히 건너뛴다 — 차원 표기는 부가 정보라 본문 발송을 막지 않는다."""
	pairs = []
	meta = body.get("alarmMetaData")
	for entry in meta if isinstance(meta, list) else []:
		dims = entry.get("dimensions") if isinstance(entry, dict) else None
		for dim in dims if isinstance(dims, list) else []:
			if not isinstance(dim, dict):
				continue
			for key, value in dim.items():
				pair = f"{key}={value}"
				if pair not in pairs:
					pairs.append(pair)
	return pairs


def format_alarm(body: dict, raw: str) -> str:
	title = body.get("title") or "OCI 알림"
	text = body.get("body") or raw[:1500]
	emoji = SEVERITY_EMOJI.get(str(body.get("severity", "")).upper(), "🔔")
	state = body.get("type", "")  # 예: OK_TO_FIRING / FIRING_TO_OK
	suffix = " (해소됨 ✅)" if "TO_OK" in state else ""
	dims = alarm_dimensions(body)
	dim_line = f"\n📍 {', '.join(dims)}" if dims else ""
	return f"{emoji} **{title}**{suffix}\n{text}{dim_line}"
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `python3 deploy/ons-discord-relay.test.py`
Expected: `전체 통과`

- [ ] **Step 7: 커밋**

```bash
git add deploy/ons-discord-relay.py deploy/ons-discord-relay.test.py
git commit -m "feat(deploy): ONS 릴레이 알람 본문에 차원(containerName 등) 표기

alarmMetaData[].dimensions가 페이로드에 이미 실려 오는데 릴레이가 버리고
있어 어느 컨테이너가 다운됐는지 매번 메트릭을 직접 조회해야 했다.
포맷 로직을 format_alarm 순수 함수로 분리해 서버 기동 없이 테스트한다.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: README §9 문구 갱신

**Files:**
- Modify: `deploy/README.md:411-417` (§9 컨테이너 조회 항목 끝부분)

**Interfaces:**
- Consumes: Task 1의 `📍 키=값` 표기 형식(문서에 예시로 기재).

- [ ] **Step 1: "알람 본문에 차원이 안 실리니" 문구를 현행화**

`deploy/README.md`의 다음 부분(§9, 07-30 오탐 사고 설명 끝):

```
  1시간 주기로 재알림. 실제 컨테이너는 `deploy-was-8` healthy였다). 알람 본문에 차원이 안 실리니
  **어느 컨테이너인지는 메트릭으로 확인**할 것:
```

을 다음으로 교체:

```
  1시간 주기로 재알림. 실제 컨테이너는 `deploy-was-8` healthy였다). 어느 컨테이너인지는
  **알람 본문의 `📍 containerName=<서비스>` 줄로 확인**(08-13~ 릴레이가
  `alarmMetaData[].dimensions`를 표기 — 디스크 `host=`·버킷 `bucketName=`도 동일).
  본문이 잘렸거나 과거 이력을 볼 때는 메트릭 직접 조회:
```

(뒤따르는 `oci monitoring ...` 코드 블록은 폴백 용도로 그대로 둔다.)

- [ ] **Step 2: 커밋**

```bash
git add deploy/README.md
git commit -m "docs: README §9 알람 차원 표기 현행화 (릴레이가 본문에 📍로 표기)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: PR 생성 (+ 계획 문서 아카이브)

**Files:**
- Move: `docs/superpowers/plans/2026-08-13-ons-relay-alarm-dimensions.md` → `docs/superpowers/plans/archive/`

**Interfaces:**
- Consumes: Task 1·2의 커밋들. 브랜치 `feature/container-down-detection-alert-e17e88` → base `develop`.

- [ ] **Step 1: 계획 문서를 archive로 이동하고 상태 헤더를 ✅로 갱신**

```bash
git mv docs/superpowers/plans/2026-08-13-ons-relay-alarm-dimensions.md docs/superpowers/plans/archive/
```

이동한 파일의 상태 헤더 `> 상태: 🟢 활성`을 `> 상태: ✅ 실행됨`으로 수정.

- [ ] **Step 2: 커밋 + 푸시 + PR**

```bash
git add -A docs/superpowers/plans
git commit -m "docs: 알람 차원 표기 계획 아카이브

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push -u origin feature/container-down-detection-alert-e17e88
gh pr create --base develop --title "feat(deploy): ONS 릴레이 알람 본문에 컨테이너 등 차원 표기" --body "..."
```

PR 본문에는 스펙 링크·before/after 메시지 예시·서버 반영 절차(Task 4)가 별도임을 명시.

---

### Task 4: 서버 반영 + 엔드투엔드 검증 (⚠️ 사용자 확인 후)

릴레이는 CD 대상이 아니라 서버 `~/deploy/ons-discord-relay.py` 볼륨 마운트 단일 파일 — 수동 반영. **파일 교체·재시작 전 반드시 사용자 확인을 받는다.** PR 머지와 독립적으로 가능하지만, 머지 후 반영을 기본으로 한다.

**Interfaces:**
- Consumes: Task 1의 완성된 `deploy/ons-discord-relay.py`.

- [ ] **Step 1: (사용자 확인 후) 파일 교체 + 재시작**

```bash
scp deploy/ons-discord-relay.py ubuntu@155.248.187.106:~/deploy/ons-discord-relay.py
ssh ubuntu@155.248.187.106 "cd ~/deploy && docker compose restart ons-relay"
```

(단일 파일 바인드 마운트는 inode 교체 문제가 있지만 scp는 원본 파일을 truncate-후-덮어쓰기해
inode가 유지되고, 재시작 시 경로 기준으로 다시 마운트되므로 어느 쪽이든 안전.)

- [ ] **Step 2: 재시작 후 컨테이너 healthy 확인**

```bash
ssh ubuntu@155.248.187.106 "docker ps --filter label=com.docker.compose.service=ons-relay --format '{{.Status}}'"
```

Expected: `Up ... (healthy)` (healthcheck 통과까지 수십 초 대기 가능).

- [ ] **Step 3: 실물 페이로드 재주입으로 엔드투엔드 검증**

08-12 캡처 페이로드를 컨테이너 안에서 릴레이(9099)로 POST — 디스코드에 테스트 메시지 1건이 실제로 감:

```bash
ssh ubuntu@155.248.187.106 'CTR=$(docker ps -q --filter label=com.docker.compose.service=ons-relay) && docker exec "$CTR" python -c "
import json, os, urllib.request
payload = {
    \"title\": \"hypenow-container-down\",
    \"body\": \"도커 컨테이너가 3분 이상 다운 상태입니다 (containerName 차원 확인). [릴레이 차원 표기 검증용 재주입]\",
    \"type\": \"OK_TO_FIRING\", \"severity\": \"CRITICAL\",
    \"alarmMetaData\": [{\"status\": \"FIRING\", \"dimensions\": [{\"containerName\": \"monitoring\"}]}],
}
req = urllib.request.Request(\"http://127.0.0.1:9099/\" + os.environ[\"ONS_RELAY_TOKEN\"],
    data=json.dumps(payload).encode(), headers={\"Content-Type\": \"application/json\"})
print(urllib.request.urlopen(req, timeout=10).status)
"'
```

Expected: `200` 출력 + 디스코드 채널에 `🚨 hypenow-container-down` / 본문 끝 `📍 containerName=monitoring` 메시지 도착(사용자 육안 확인 요청).

- [ ] **Step 4: 릴레이 로그에 처리 실패 없음 확인**

```bash
ssh ubuntu@155.248.187.106 'docker logs --since 5m $(docker ps -q --filter label=com.docker.compose.service=ons-relay) 2>&1 | tail -5'
```

Expected: `수신: {...}` 로그만 있고 `처리 실패:` 줄 없음.
