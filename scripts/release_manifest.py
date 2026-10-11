#!/usr/bin/env python3
"""PhotoCull 릴리스 자산 준비 (CI: .github/workflows/android.yml).

  python3 scripts/release_manifest.py code 0.2.0
      → versionCode 출력(200). app/build.gradle.kts와 같은 규칙: major*10000 + minor*100 + patch.

  python3 scripts/release_manifest.py prepare --apk app/build/outputs/apk/release/app-release.apk \\
      --version 0.2.0 --repo Leepy0/PhotoCull --dist dist --notes-out notes.md
      → dist/PhotoCull.apk, dist/PhotoCull.apk.sha256, dist/PhotoCull.json(매니페스트), notes.md(릴리스 본문)

변경 내용 = 이전 v* 태그 이후 커밋 제목(병합 커밋 제외, Co-Authored-By/Claude-Session 줄 제외).
앱은 PhotoCull.json의 versionCode·url·sha256·size·notes를 읽는다(update/UpdateManifest.kt).
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

APK_NAME = "PhotoCull.apk"
MANIFEST_NAME = "PhotoCull.json"
MAX_NOTE_LINES = 40
TRAILER = re.compile(r"^(co-authored-by|claude-session|signed-off-by)\s*:", re.IGNORECASE)


def version_code(version: str) -> int:
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version.strip())
    if not m:
        raise ValueError(f"versionName must be X.Y.Z: {version!r}")
    major, minor, patch = (int(g) for g in m.groups())
    if minor >= 100 or patch >= 100:
        raise ValueError(f"minor/patch must be < 100: {version}")
    code = major * 10000 + minor * 100 + patch
    if code <= 0:
        raise ValueError(f"version must be > 0.0.0: {version}")
    return code


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def previous_tag(current_tag: str) -> str | None:
    """HEAD에서 닿는 가장 최근 v* 태그(지금 만들 태그는 제외)."""
    try:
        tags = git("tag", "--merged", "HEAD", "--list", "v*", "--sort=-v:refname").split()
    except subprocess.CalledProcessError:
        return None
    for t in tags:
        if t != current_tag and re.fullmatch(r"v\d+\.\d+\.\d+", t):
            return t
    return None


def change_lines(since_tag: str | None) -> list[str]:
    rev = f"{since_tag}..HEAD" if since_tag else "HEAD"
    try:
        out = git("log", "--no-merges", "--format=%s", rev)
    except subprocess.CalledProcessError:
        return []
    lines = []
    for raw in out.splitlines():
        s = raw.strip()
        if not s or TRAILER.match(s) or s.lower().startswith("merge "):
            continue
        if s not in lines:
            lines.append(s)
    return lines


def build_notes(lines: list[str]) -> str:
    if not lines:
        return "- 자잘한 개선"
    shown = lines[:MAX_NOTE_LINES]
    text = "\n".join(f"- {s}" for s in shown)
    if len(lines) > len(shown):
        text += f"\n- 외 {len(lines) - len(shown)}건"
    return text


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def prepare(args: argparse.Namespace) -> None:
    version = args.version.strip()
    code = version_code(version)
    tag = f"v{version}"
    if not os.path.isfile(args.apk) or os.path.getsize(args.apk) == 0:
        raise FileNotFoundError(f"APK not found or empty: {args.apk}")

    os.makedirs(args.dist, exist_ok=True)
    apk = os.path.join(args.dist, APK_NAME)
    shutil.copyfile(args.apk, apk)
    digest = sha256_of(apk)
    size = os.path.getsize(apk)
    with open(apk + ".sha256", "w", encoding="utf-8") as f:
        f.write(f"{digest}  {APK_NAME}\n")

    since = args.since_tag if args.since_tag else previous_tag(tag)
    notes = build_notes(change_lines(since))
    base = f"https://github.com/{args.repo}"
    published = args.published or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    manifest = {
        "version": version,
        "versionCode": code,
        "file": APK_NAME,
        "url": f"{base}/releases/download/{tag}/{APK_NAME}",
        "sha256": digest,
        "size": size,
        "published": published,
        "page": f"{base}/releases/tag/{tag}",
        "notes": notes,
    }
    with open(os.path.join(args.dist, MANIFEST_NAME), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")

    with open(args.notes_out, "w", encoding="utf-8") as f:
        f.write(notes + "\n\n")
        if since:
            f.write(f"이전 버전: {since}\n\n")
        f.write(f"SHA-256 (`{APK_NAME}`): `{digest}`\n")

    print(f"{tag} (versionCode {code}) · {size} bytes · sha256 {digest}")
    print(f"notes since {since or '(first release)'}:\n{notes}")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("code", help="versionName → versionCode")
    c.add_argument("version")
    r = sub.add_parser("prepare", help="릴리스 자산·매니페스트·노트 생성")
    r.add_argument("--apk", required=True)
    r.add_argument("--version", required=True)
    r.add_argument("--repo", required=True, help="owner/name")
    r.add_argument("--dist", default="dist")
    r.add_argument("--notes-out", default="notes.md")
    r.add_argument("--since-tag", default=None, help="변경 내용 기준 태그(기본: 가장 최근 v* 태그)")
    r.add_argument("--published", default=None, help="UTC ISO 시각(기본: 지금)")
    a = p.parse_args()
    try:
        if a.cmd == "code":
            print(version_code(a.version))
        else:
            prepare(a)
    except (ValueError, FileNotFoundError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
