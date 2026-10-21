"""Install pinned Gitleaks in local Git metadata and enable this repo's hooks."""

import hashlib
import io
import os
from pathlib import Path
import platform
import stat
import subprocess
import tarfile
import urllib.request
import zipfile

from privacy_guard import ROOT, VERSION, GuardError, git, scanner


def download(name):
    url = f"https://github.com/gitleaks/gitleaks/releases/download/v{VERSION}/{name}"
    request = urllib.request.Request(url, headers={"User-Agent": "legal-assistant-privacy-guard"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def install():
    configured = subprocess.run(["git", "config", "--get", "core.hooksPath"],
                                cwd=ROOT, capture_output=True, text=True)
    if configured.returncode not in (0, 1):
        raise GuardError("Cannot inspect hooks configuration.")
    if configured.stdout.strip() not in ("", ".githooks"):
        raise GuardError("Another hooksPath is configured; integrate privacy checks before replacing it.")
    for hook in ("pre-commit", "pre-push"):
        existing = Path(os.fsdecode(git("rev-parse", "--git-path", "hooks/" + hook)).strip())
        if not existing.is_absolute():
            existing = ROOT / existing
        if existing.is_file() and not configured.stdout.strip():
            raise GuardError("Existing Git hooks need integration; installation stopped.")

    operating_system = {"Windows": "windows", "Linux": "linux", "Darwin": "darwin"}.get(platform.system())
    architecture = {"AMD64": "x64", "x86_64": "x64", "arm64": "arm64", "aarch64": "arm64"}.get(platform.machine())
    if not operating_system or not architecture:
        raise GuardError("Unsupported platform; install the pinned Gitleaks version manually.")
    suffix = ".exe" if operating_system == "windows" else ""
    tools = Path(os.fsdecode(git("rev-parse", "--git-path", "privacy-tools")).strip())
    if not tools.is_absolute():
        tools = ROOT / tools
    try:
        scanner()
    except GuardError:
        extension = "zip" if operating_system == "windows" else "tar.gz"
        filename = f"gitleaks_{VERSION}_{operating_system}_{architecture}.{extension}"
        archive = download(filename)
        checksums = download(f"gitleaks_{VERSION}_checksums.txt").decode("utf-8")
        expected = next((line.split()[0] for line in checksums.splitlines()
                         if line.split()[-1].lstrip("*") == filename), None)
        if not expected or hashlib.sha256(archive).hexdigest() != expected:
            raise GuardError("Gitleaks checksum verification failed.")
        executable = "gitleaks" + suffix
        if extension == "zip":
            with zipfile.ZipFile(io.BytesIO(archive)) as package:
                content = package.read(executable)
        else:
            with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as package:
                content = package.extractfile(executable).read()
        tools.mkdir(parents=True, exist_ok=True)
        binary = tools / executable
        binary.write_bytes(content)
        binary.chmod(binary.stat().st_mode | stat.S_IXUSR)
        scanner()
    for name in ("pre-commit", "pre-push"):
        hook = ROOT / ".githooks" / name
        hook.chmod(hook.stat().st_mode | stat.S_IXUSR)
    git("config", "--local", "core.hooksPath", ".githooks")
    print(f"Privacy hooks enabled for this clone with Gitleaks {VERSION}.")


if __name__ == "__main__":
    try:
        install()
    except Exception:
        # Network and filesystem errors may embed private paths. Keep output generic.
        raise SystemExit("Privacy guard installation failed; existing hooks were not replaced. Check platform, network and hooks configuration.")
