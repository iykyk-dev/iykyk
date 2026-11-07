#!/usr/bin/env python3
"""Writes <site>/release.json for the download page from the newest GitHub release,
and <site>/version.json (a mirror for the in-app updater) when that release is
not a beta.

Usage: GH_TOKEN=... site-release.py <site dir> <owner/repo>
"""
import json
import os
import re
import sys
import urllib.request
from pathlib import Path


def get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "iykyk-site"})
    token = os.environ.get("GH_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def main() -> None:
    site, repo = Path(sys.argv[1]), sys.argv[2]
    releases = json.loads(get(f"https://api.github.com/repos/{repo}/releases?per_page=30"))
    candidates = [
        r for r in releases
        if not r.get("draft") and r.get("tag_name", "").startswith("v")
        and any(a["name"] == "version.json" for a in r.get("assets", []))
    ]
    if not candidates:
        print("no release with version.json yet")
        return
    stable = [r for r in candidates if not r.get("prerelease")]
    release = (stable or candidates)[0]
    asset = next(a for a in release["assets"] if a["name"] == "version.json")
    manifest = json.loads(get(asset["browser_download_url"]))

    signature = manifest.get("signature", "")
    if not signature:
        m = re.search(r"((?:[0-9A-F]{2}:){31}[0-9A-F]{2})", release.get("body") or "")
        signature = m.group(1) if m else ""
    files = {f"IYKYK-{abi}.apk" if abi != "universal" else "IYKYK.apk": info for abi, info in manifest.get("android", {}).items()}
    if "windows" in manifest:
        files["IYKYK-Setup.exe"] = manifest["windows"]
    checksums = "\n".join(f"{info['sha256']}  {name}" for name, info in sorted(files.items()))

    data = {
        "version": manifest["version"],
        "beta": bool(release.get("prerelease")),
        "date": release.get("published_at"),
        "notes": manifest.get("notes", ""),
        "android": manifest.get("android", {}),
        "windows": manifest.get("windows", {}),
        "signature": signature,
        "checksums": checksums,
        "page": release.get("html_url"),
    }
    (site / "release.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    if not release.get("prerelease"):
        (site / "version.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(data, ensure_ascii=False, indent=2)[:1500])


if __name__ == "__main__":
    main()
