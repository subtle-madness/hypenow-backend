#!/usr/bin/env python3
# 구독 버스트 드라이버 (07-19) — ClaudeBurstRunner export 산출(JSONL {key,system,user})을
# claude -p(구독 컴퓨트, Haiku) 병렬 호출로 처리해 {key,text} 결과 JSONL을 만든다.
# 유료 API 키 불필요 — 로컬 Claude Code 로그인 세션을 그대로 쓴다.
#
# 사용법: python3 claude_burst_driver.py <input.jsonl> <output.jsonl> [--workers 6] [--model claude-haiku-4-5]
# 재실행 안전: output에 이미 있는 key는 건너뛴다(append 모드) — 중단 후 이어서 실행 가능.
import argparse
import json
import subprocess
import sys
import threading
import time
from queue import Queue

parser = argparse.ArgumentParser()
parser.add_argument("input")
parser.add_argument("output")
parser.add_argument("--workers", type=int, default=6)
parser.add_argument("--model", default="claude-haiku-4-5")
parser.add_argument("--timeout", type=int, default=180)
args = parser.parse_args()

REQUIRED_HINTS = ("{", "}")  # 최소 JSON 형태 검증은 json.loads로

def strip_fences(text):
    t = text.strip()
    if t.startswith("```"):
        nl = t.find("\n")
        t = "" if nl < 0 else t[nl + 1:]
        if t.endswith("```"):
            t = t[:-3]
    return t.strip()

def call_claude(system, user):
    """claude -p 1콜 — 성공 시 결과 텍스트, 실패 시 예외."""
    proc = subprocess.run(
        ["claude", "-p", "--model", args.model, "--output-format", "json",
         "--append-system-prompt", system],
        input=user, capture_output=True, text=True, timeout=args.timeout)
    if proc.returncode != 0:
        raise RuntimeError(f"claude exit {proc.returncode}: {proc.stderr[:200]}")
    envelope = json.loads(proc.stdout)
    if envelope.get("is_error"):
        raise RuntimeError(f"claude error result: {str(envelope.get('result'))[:200]}")
    return envelope["result"]

done_keys = set()
try:
    with open(args.output) as f:
        for line in f:
            if line.strip():
                done_keys.add(json.loads(line)["key"])
except FileNotFoundError:
    pass

items = []
with open(args.input) as f:
    for line in f:
        if line.strip():
            item = json.loads(line)
            if item["key"] not in done_keys:
                items.append(item)

total = len(items)
print(f"대상 {total}건 (이미 완료 {len(done_keys)}건 건너뜀), workers={args.workers}, model={args.model}")

q = Queue()
for it in items:
    q.put(it)

lock = threading.Lock()
out = open(args.output, "a")
stats = {"ok": 0, "fail": 0, "start": time.time()}

def worker():
    while True:
        try:
            item = q.get_nowait()
        except Exception:
            return
        key = item["key"]
        text = None
        for attempt in (1, 2, 3):
            try:
                result = call_claude(item["system"], item["user"])
                stripped = strip_fences(result)
                parsed = json.loads(stripped)  # 형태 검증 — collect의 strict 파싱 전 1차 필터
                if not isinstance(parsed, dict):
                    raise ValueError("객체가 아님")
                text = stripped
                break
            except Exception as e:
                msg = str(e)
                # 사용량 한도류는 길게 쉬고 재시도 — 버스트가 구독 한도에 닿는 경우
                pause = 90 if ("limit" in msg.lower() or "overload" in msg.lower()) else 5 * attempt
                if attempt < 3:
                    time.sleep(pause)
                else:
                    with lock:
                        stats["fail"] += 1
                        print(f"  실패 {key}: {msg[:160]}", file=sys.stderr)
        if text is not None:
            with lock:
                out.write(json.dumps({"key": key, "text": text}, ensure_ascii=False) + "\n")
                out.flush()
                stats["ok"] += 1
                n = stats["ok"] + stats["fail"]
                if n % 25 == 0 or n == total:
                    rate = stats["ok"] / max(time.time() - stats["start"], 1) * 60
                    eta = (total - n) / max(rate, 0.1)
                    print(f"  진행 {n}/{total} (성공 {stats['ok']}, 실패 {stats['fail']}, "
                          f"{rate:.0f}건/분, 잔여 ~{eta:.0f}분)")

threads = [threading.Thread(target=worker, daemon=True) for _ in range(args.workers)]
for t in threads:
    t.start()
for t in threads:
    t.join()
out.close()
print(f"완료 — 성공 {stats['ok']}, 실패 {stats['fail']} (실패분은 재실행하면 이어서 처리)")
sys.exit(0 if stats["fail"] == 0 else 1)
