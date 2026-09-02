#!/usr/bin/env python3
"""GCS 서빙 이미지 720px·q70 일괄 백필 — **로컬 맥 전용**(sips 사용), 서버 아님.

스펙: docs/superpowers/specs/2026-09-02-image-resize-720-design.md §4

사용:
  ./backfill-image-resize.py thumb "public, max-age=31536000, immutable"          # 드라이런
  ./backfill-image-resize.py thumb "public, max-age=31536000, immutable" --apply  # 실제 덮어쓰기
  ./backfill-image-resize.py monitor-brand-post "public, max-age=86400" --apply

정책(잡 ImageResizer와 동일): 최장변 720 초과분만 축소, 결과가 크면 원본 유지 — 자연
멱등이라 중단 후 재실행 안전(처리된 객체는 치수 검사에서 자동 통과). Cache-Control은
프리픽스 계약값을 인자로 받아 배치 업로드에 그대로 붙인다(deploy/README §5-2).
잠자기 방지는 호출 측에서: caffeinate -i ./backfill-image-resize.py ...
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

BUCKET = "gs://hypenow-images"
BATCH = 200


def dims(path: Path) -> int:
    """최장변 픽셀. 측정 실패는 0(→ 통과 처리)."""
    try:
        out = subprocess.run(
            ["sips", "-g", "pixelWidth", "-g", "pixelHeight", str(path)],
            capture_output=True, text=True, timeout=30).stdout
        vals = [int(line.split()[-1]) for line in out.splitlines()
                if "pixelWidth" in line or "pixelHeight" in line]
        return max(vals) if len(vals) == 2 else 0
    except Exception:
        return 0


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit("사용법: backfill-image-resize.py <prefix> <cache-control> [--apply]")
    prefix, cache_control = sys.argv[1], sys.argv[2]
    apply = "--apply" in sys.argv[3:]

    urls = subprocess.run(
        ["gcloud", "storage", "ls", f"{BUCKET}/{prefix}/"],
        capture_output=True, text=True, check=True).stdout.split()
    print(f"대상 {len(urls)}개 (모드: {'APPLY' if apply else '드라이런'})")

    shrunk = passed = kept = failed = 0
    before = after = 0
    for i in range(0, len(urls), BATCH):
        batch = urls[i:i + BATCH]
        with tempfile.TemporaryDirectory(prefix="img-backfill.") as tmp:
            dl, up = Path(tmp) / "dl", Path(tmp) / "up"
            dl.mkdir(); up.mkdir()
            # 배치 다운로드 — 개별 실패는 배치 전체를 죽이지 않게 check 없이
            subprocess.run(["gcloud", "storage", "cp", *batch, str(dl)],
                           capture_output=True)
            for url in batch:
                f = dl / url.rsplit("/", 1)[1]
                if not f.exists():
                    failed += 1
                    continue
                size = f.stat().st_size
                if dims(f) <= 720:
                    passed += 1
                    continue
                out = up / f.name
                r = subprocess.run(
                    ["sips", "-Z", "720", "-s", "format", "jpeg",
                     "-s", "formatOptions", "70", str(f), "--out", str(out)],
                    capture_output=True)
                if r.returncode != 0 or not out.exists():
                    failed += 1
                    continue
                if out.stat().st_size >= size:
                    kept += 1
                    out.unlink()
                    continue
                shrunk += 1
                before += size
                after += out.stat().st_size
            outs = list(up.iterdir())
            if apply and outs:
                subprocess.run(
                    ["gcloud", "storage", "cp", *map(str, outs), f"{BUCKET}/{prefix}/",
                     "--content-type=image/jpeg", f"--cache-control={cache_control}"],
                    capture_output=True, check=True)
        done = min(i + BATCH, len(urls))
        print(f"  {done}/{len(urls)} — 축소 {shrunk} · 통과 {passed} · 유지 {kept} · 실패 {failed}", flush=True)

    gb = 2 ** 30
    print(f"축소 {shrunk}개: {before / gb:.2f}GB → {after / gb:.2f}GB (절감 {(before - after) / gb:.2f}GB)")
    print(f"통과(≤720px) {passed} · 역효과 유지 {kept} · 실패 스킵 {failed}"
          + ("" if apply else " · 드라이런(덮어쓰기 0건)"))


if __name__ == "__main__":
    if not shutil.which("sips"):
        sys.exit("sips 없음 — 이 스크립트는 macOS 전용이다")
    main()
