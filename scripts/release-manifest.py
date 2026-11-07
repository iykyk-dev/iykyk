#!/usr/bin/env python3
"""Writes dist/version.json (read by the in-app updater) and dist/notes.md.

Usage: release-manifest.py <tag> <owner/repo> <dist dir> <RELEASE_NOTES.md>

The notes for a version are the section "## <version>" of RELEASE_NOTES.md;
a beta tag (v1.1.0-beta2) uses the section of its release (1.1.0).
"""
import hashlib
import json
import re
import sys
from pathlib import Path


def version_code(version: str) -> int:
    # Same formula as android/app/build.gradle.kts.
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-beta(\d+))?", version)
    if not m:
        sys.exit(f"bad version {version!r}: expected 1.2.3 or 1.2.3-beta4")
    major, minor, patch, beta = m.groups()
    build = min(max(int(beta), 1), 98) if beta else 99
    return int(major) * 1_000_000 + int(minor) * 10_000 + int(patch) * 100 + build


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def notes_for(version: str, notes_file: Path) -> str:
    base = version.split("-")[0]
    if not notes_file.exists():
        return ""
    text = notes_file.read_text(encoding="utf-8")
    m = re.search(rf"^## {re.escape(base)}\s*$(.*?)(?=^## |\Z)", text, re.M | re.S)
    return m.group(1).strip() if m else ""


def main() -> None:
    tag, repo, dist, notes_file = sys.argv[1], sys.argv[2], Path(sys.argv[3]), Path(sys.argv[4])
    version = tag.removeprefix("v")
    base = f"https://github.com/{repo}/releases/download/{tag}"
    notes = notes_for(version, notes_file)

    android = {}
    for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
        apk = dist / f"IYKYK-{abi}.apk"
        android[abi] = {"url": f"{base}/{apk.name}", "sha256": sha256(apk), "size": apk.stat().st_size}
    universal = dist / "IYKYK.apk"
    android["universal"] = {"url": f"{base}/IYKYK.apk", "sha256": sha256(universal), "size": universal.stat().st_size}

    setup = dist / "IYKYK-Setup.exe"
    manifest = {
        "version": version,
        "versionCode": version_code(version),
        "notes": notes,
        "android": android,
        "windows": {"url": f"{base}/{setup.name}", "sha256": sha256(setup), "size": setup.stat().st_size},
    }
    (dist / "version.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    signature = (dist / "signature.txt").read_text().strip() if (dist / "signature.txt").exists() else ""
    sums = "\n".join(f"{sha256(p)}  {p.name}" for p in sorted(dist.glob("IYKYK*")))
    body = notes or "Исправления и улучшения."
    body += "\n\n### Какой файл скачать\n"
    body += "- **Android**: `IYKYK.apk` подходит любому телефону; `IYKYK-arm64-v8a.apk` меньше и подходит почти всем современным.\n"
    body += "- **Windows 10/11**: `IYKYK-Setup.exe`.\n"
    body += "\n### Проверка подлинности\n"
    if signature:
        # Same format as Settings → "Подпись приложения": AB:CD:…
        hexdigest = signature.rsplit(":", 1)[-1].strip().upper()
        pretty = ":".join(hexdigest[i:i + 2] for i in range(0, len(hexdigest), 2))
        body += f"Подпись Android-приложения (должна совпадать с «Настройки → Подпись приложения»):\n```\n{pretty}\n```\n"
    body += f"SHA-256 файлов:\n```\n{sums}\n```\n"
    (dist / "notes.md").write_text(body, encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
