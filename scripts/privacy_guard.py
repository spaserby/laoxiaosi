"""Fail-closed Git privacy checks. Never print scanner output or secret values."""

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile


VERSION = "8.30.1"
ROOT = Path(__file__).resolve().parents[1]


class GuardError(Exception):
    pass


def git(*args):
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True)
    if result.returncode:
        raise GuardError("Git inspection failed; privacy check cannot complete.")
    return result.stdout


def blocked_path(name):
    path = PurePosixPath(name.lower())
    if any(part in {".ssh", ".qoder", ".workbuddy", "blog", "interview"} for part in path.parts):
        return True
    if path.name.startswith(".env") and path.name not in {".env.example", ".env.template"}:
        return True
    if re.match(r"^id_(rsa|dsa|ecdsa|ed25519)(?:\.|$)", path.name):
        return True
    return path.suffix in {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore",
                           ".dump", ".har", ".hprof", ".sqlite", ".sqlite3", ".db", ".log",
                           ".zip", ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz"}


def check_paths(names):
    findings = sorted({name for name in names if blocked_path(name)})
    for name in findings:
        print(f"BLOCKED {name!r}: private-file-path", file=sys.stderr)
    return not findings


def scanner():
    suffix = ".exe" if os.name == "nt" else ""
    # Resolve relative Git paths against the repository, also supporting worktrees.
    tools_path = Path(os.fsdecode(git("rev-parse", "--git-path", "privacy-tools")).strip())
    if not tools_path.is_absolute():
        tools_path = ROOT / tools_path
    binary = tools_path / ("gitleaks" + suffix)
    candidate = str(binary) if binary.is_file() else shutil.which("gitleaks")
    if not candidate:
        raise GuardError("Gitleaks missing. Run python scripts/install_privacy_guard.py.")
    version = subprocess.run([candidate, "version"], capture_output=True, text=True)
    if version.returncode or version.stdout.strip().lstrip("v") != VERSION:
        raise GuardError(f"Gitleaks {VERSION} required. Reinstall the privacy guard.")
    return candidate


def scan(binary, mode, target, extra=(), manifest=None):
    with tempfile.TemporaryDirectory(prefix="privacy-report-") as temporary:
        report = Path(temporary) / "findings.json"
        ignore = Path(temporary) / ".gitleaksignore"
        ignore.touch()
        command = [binary, mode, str(target), "--config", str(ROOT / ".gitleaks.toml"),
                   "--redact=100", "--no-banner", "--ignore-gitleaks-allow",
                   "--gitleaks-ignore-path", str(ignore), "--report-format=json",
                   "--report-path", str(report), "--max-decode-depth=3", *extra]
        env = os.environ.copy()
        env.pop("GITLEAKS_CONFIG", None)
        env.pop("GITLEAKS_CONFIG_TOML", None)
        result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True)
        # Do not relay stdout/stderr: even error messages can contain private data.
        if result.returncode not in (0, 1) or not report.is_file():
            raise GuardError("Secret scanner failed; commit/push is blocked.")
        try:
            findings = json.loads(report.read_text(encoding="utf-8")) or []
        except (ValueError, OSError) as error:
            raise GuardError("Invalid scanner report; commit/push is blocked.") from error
        for item in findings:
            filename = item.get("File", "(unknown)")
            if mode == "dir":
                try:
                    filename = Path(filename).relative_to(target).as_posix()
                except ValueError:
                    pass
                filename = (manifest or {}).get(Path(filename).name, filename)
            print(f"BLOCKED {filename!r}:{item.get('StartLine', '?')}: "
                  f"{item.get('RuleID', 'secret')}", file=sys.stderr)
        if result.returncode == 1 and not findings:
            raise GuardError("Scanner returned failure without findings; commit/push is blocked.")
        return result.returncode == 0 and not findings


def staged(binary):
    names = [os.fsdecode(name) for name in git("diff", "--cached", "--name-only",
             "--diff-filter=ACMRT", "-z").split(b"\0") if name]
    if not check_paths(names):
        return False
    with tempfile.TemporaryDirectory(prefix="privacy-index-") as temporary:
        destination = Path(temporary)
        manifest = {}
        for index, name in enumerate(names):
            # Flat names prevent symlink/path traversal, including malicious Git paths.
            # Keep a manifest to translate reports back to repository paths.
            snapshot_name = f"{index:06d}-{PurePosixPath(name).name}"
            manifest[snapshot_name] = name
            (destination / snapshot_name).write_bytes(git("show", f":{name}"))
        return scan(binary, "dir", destination, manifest=manifest)


def history(binary, revision):
    names = [os.fsdecode(name).strip("\n") for name in git("log", "-m", "--format=", "--name-only",
             "--diff-filter=ACMRT", "-z", revision, "--").split(b"\0") if name.strip(b"\n")]
    paths_ok = check_paths(names)
    secrets_ok = scan(binary, "git", ROOT, [f"--log-opts=--full-history -m {revision}"])
    return paths_ok and secrets_ok


def push(binary, stream):
    passed = True
    for line in stream:
        fields = line.split()
        if len(fields) != 4:
            raise GuardError("Invalid pre-push input; push is blocked.")
        _, local_oid, _, remote_oid = fields
        if not all(re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", oid) for oid in (local_oid, remote_oid)):
            raise GuardError("Invalid push object IDs; push is blocked.")
        if set(local_oid) == {"0"}:  # Deleting a remote reference publishes no content.
            continue
        git("rev-parse", "--verify", local_oid + "^{commit}")
        if set(remote_oid) == {"0"}:
            revision = local_oid
        else:
            git("rev-parse", "--verify", remote_oid + "^{commit}")
            revision = remote_oid + ".." + local_oid
        passed = history(binary, revision) and passed
    return passed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("staged", "push", "history"))
    args = parser.parse_args()
    try:
        binary = scanner()
        if args.mode == "staged":
            passed = staged(binary)
        elif args.mode == "push":
            passed = push(binary, sys.stdin)
        else:
            passed = history(binary, "HEAD")
        if passed:
            print("Privacy check passed. Review personal data and binary attachments manually.")
        return 0 if passed else 1
    except (GuardError, OSError, UnicodeError) as error:
        # Use fixed messages for OS errors: their arguments can contain sensitive data.
        message = str(error) if isinstance(error, GuardError) else "Privacy check could not complete."
        print("BLOCKED: " + message, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
