"""Exercise real Git hooks in disposable repositories; never use business data."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from privacy_guard import ROOT, scanner


class PrivacyGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.binary = Path(scanner())

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="privacy-guard-test-")
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name) / "repo"
        self.repo.mkdir()
        self.env = os.environ.copy()
        self.env["PATH"] = str(self.binary.parent) + os.pathsep + self.env.get("PATH", "")
        # Tests must not inherit an alternate index or external repository.
        for name in ("GIT_INDEX_FILE", "GIT_DIR", "GIT_WORK_TREE"):
            self.env.pop(name, None)
        self.git("init", "-q")
        self.git("config", "user.name", "Privacy Guard Test")
        self.git("config", "user.email", "test@example.invalid")
        (self.repo / "notes.txt").write_text("safe initial content\n")
        self.git("add", "notes.txt")
        self.git("commit", "-qm", "Initial fixture")
        self.base = self.git("rev-parse", "HEAD").stdout.strip()
        (self.repo / "scripts").mkdir()
        shutil.copy2(ROOT / "scripts/privacy_guard.py", self.repo / "scripts/privacy_guard.py")
        shutil.copy2(ROOT / ".gitleaks.toml", self.repo / ".gitleaks.toml")
        shutil.copytree(ROOT / ".githooks", self.repo / ".githooks")
        for path in (self.repo / ".githooks").iterdir():
            path.chmod(0o755)

    def git(self, *args, check=True):
        result = subprocess.run(["git", *args], cwd=self.repo, env=self.env,
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
        if check and result.returncode:
            self.fail("Git fixture operation failed (output withheld).")
        return result

    def enable_hooks(self):
        self.git("config", "core.hooksPath", ".githooks")

    def guard(self, mode, input=None):
        return subprocess.run([sys.executable, "scripts/privacy_guard.py", mode],
                              cwd=self.repo, env=self.env, input=input, capture_output=True,
                              text=True, encoding="utf-8", errors="replace")

    def test_real_pre_commit_blocks_secret_even_when_worktree_is_cleaned(self):
        secret = "synthetic-sensitive-password"
        (self.repo / "notes.txt").write_text("=".join(("DB_PASSWORD", secret)) + "\n")
        self.git("add", "notes.txt")
        (self.repo / "notes.txt").write_text("DB_PASSWORD=${DB_PASSWORD}\n")
        self.enable_hooks()
        result = self.git("commit", "-m", "Fixture change", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("project-literal-credential", result.stderr)
        self.assertNotIn(secret, result.stdout + result.stderr)
        self.assertEqual(self.base, self.git("rev-parse", "HEAD").stdout.strip())

    def test_real_pre_commit_uses_safe_index_instead_of_dirty_worktree(self):
        (self.repo / "notes.txt").write_text("DB_PASSWORD=${DB_PASSWORD}\n")
        self.git("add", "notes.txt")
        (self.repo / "notes.txt").write_text("DB_PASSWORD=synthetic-sensitive-password\n")
        self.enable_hooks()
        self.assertEqual(0, self.git("commit", "-m", "Safe fixture", check=False).returncode)

    def test_private_file_path_is_blocked_even_without_secret_content(self):
        (self.repo / ".env").write_text("EMPTY=\n")
        self.git("add", ".env")
        result = self.guard("staged")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("private-file-path", result.stderr)

    def test_explicit_template_and_empty_environment_fallback_are_allowed(self):
        (self.repo / ".env.example").write_text("PG_PASSWORD=CHANGE_ME_DATABASE_PASSWORD\nJWT_SECRET=${JWT_SECRET:}\n")
        self.git("add", ".env.example")
        self.assertEqual(0, self.guard("staged").returncode)

    def test_nonempty_environment_fallback_is_blocked(self):
        fallback = "${%s:%s}" % ("PG_PASSWORD", "synthetic-sensitive-password")
        (self.repo / "notes.txt").write_text(": ".join(("password", fallback)) + "\n")
        self.git("add", "notes.txt")
        result = self.guard("staged")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("credential-environment-fallback", result.stderr)

    def test_compose_required_variable_message_is_not_a_credential(self):
        required = "${%s:?%s}" % ("PG_PASSWORD", "PG_PASSWORD must be configured")
        (self.repo / "compose.yml").write_text(": ".join(("password", required)) + "\n")
        self.git("add", "compose.yml")
        self.assertEqual(0, self.guard("staged").returncode)

    def test_quoted_source_password_is_blocked(self):
        declaration = " ".join(("private String", "password", "=", "'synthetic-sensitive-password';"))
        (self.repo / "Example.java").write_text(declaration + "\n")
        self.git("add", "Example.java")
        result = self.guard("staged")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("source-literal-credential", result.stderr)

    def test_inline_allow_comment_does_not_skip_secret(self):
        (self.repo / "notes.txt").write_text("DB_PASSWORD=synthetic-sensitive-password #gitleaks:allow\n")
        self.git("add", "notes.txt")
        self.assertNotEqual(0, self.guard("staged").returncode)

    def test_chinese_password_note_is_blocked(self):
        (self.repo / "notes.txt").write_text("密码：" + "synthetic-sensitive-password" + "\n", encoding="utf-8")
        self.git("add", "notes.txt")
        result = self.guard("staged")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("chinese-credential-label", result.stderr)

    def test_public_ip_is_blocked_but_documentation_ip_is_allowed(self):
        (self.repo / "notes.txt").write_text("server=" + ".".join(("8", "8", "8", "8")) + "\n")
        self.git("add", "notes.txt")
        self.assertNotEqual(0, self.guard("staged").returncode)
        (self.repo / "notes.txt").write_text("server=" + ".".join(("203", "0", "113", "1")) + "\n")
        self.git("add", "notes.txt")
        self.assertEqual(0, self.guard("staged").returncode)

    def test_real_pre_push_blocks_secret_deleted_in_later_commit(self):
        (self.repo / "notes.txt").write_text("DB_PASSWORD=synthetic-sensitive-password\n")
        self.git("add", "notes.txt")
        self.git("commit", "-qm", "Synthetic historical finding")
        (self.repo / "notes.txt").write_text("safe again\n")
        self.git("add", "notes.txt")
        self.git("commit", "-qm", "Remove synthetic finding")
        remote = Path(self.temporary.name) / "remote.git"
        self.git("init", "--bare", "-q", str(remote))
        self.enable_hooks()
        result = self.git("push", str(remote), "HEAD:refs/heads/fixture", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("project-literal-credential", result.stderr)
        self.assertNotIn("synthetic-sensitive-password", result.stderr + result.stdout)
        self.assertEqual("", self.git("ls-remote", str(remote)).stdout.strip())
        # Existing references must inspect the advertised range too.
        tip = self.git("rev-parse", "HEAD").stdout.strip()
        record = f"refs/heads/fixture {tip} refs/heads/fixture {self.base}\n"
        self.assertNotEqual(0, self.guard("push", record).returncode)

    def test_scanner_error_blocks_instead_of_passing(self):
        (self.repo / ".gitleaks.toml").write_text("invalid = [\n")
        self.assertEqual(2, self.guard("staged").returncode)

    def test_unknown_remote_commit_blocks_push(self):
        tip = self.git("rev-parse", "HEAD").stdout.strip()
        record = f"refs/heads/fixture {tip} refs/heads/fixture {'f' * 40}\n"
        self.assertEqual(2, self.guard("push", record).returncode)


if __name__ == "__main__":
    unittest.main()
