#!/usr/bin/env python3
"""Unit tests for tools/verify_skills.py (run by CI unittest discovery)."""
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_skills as vs


class FrontmatterTest(unittest.TestCase):
    def test_valid(self):
        text = "---\nname: foo-bar\ndescription: Something useful.\n---\n\n# Body\n"
        fm, body = vs.parse_frontmatter(text)
        self.assertEqual(fm["name"], "foo-bar")
        self.assertEqual(fm["description"], "Something useful.")
        self.assertIn("# Body", body)

    def test_no_frontmatter(self):
        fm, _ = vs.parse_frontmatter("# No frontmatter\n")
        self.assertIsNone(fm)

    def test_unterminated(self):
        fm, _ = vs.parse_frontmatter("---\nname: x\n")
        self.assertIsNone(fm)


class CheckRootTest(unittest.TestCase):
    def test_real_repo_passes(self):
        errors, warnings = vs.check_root(vs.REPO_ROOT)
        self.assertEqual(errors, [], f"real repo should pass: {errors}")

    def test_missing_skill_dir_detected(self):
        """Copy the lightweight .agents tree + AGENTS.md to a tmp root, remove
        one skill directory, and expect an error."""
        tmp = tempfile.mkdtemp()
        try:
            shutil.copytree(os.path.join(vs.REPO_ROOT, ".agents"),
                            os.path.join(tmp, ".agents"))
            shutil.copy(os.path.join(vs.REPO_ROOT, "AGENTS.md"),
                        os.path.join(tmp, "AGENTS.md"))
            victim = os.path.join(tmp, ".agents", "skills", "adversarial-review")
            shutil.rmtree(victim)
            errors, _ = vs.check_root(tmp)
            self.assertTrue(any("adversarial-review" in e for e in errors),
                            f"expected error about removed skill, got: {errors}")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_missing_section_detected(self):
        tmp = tempfile.mkdtemp()
        try:
            shutil.copytree(os.path.join(vs.REPO_ROOT, ".agents"),
                            os.path.join(tmp, ".agents"))
            shutil.copy(os.path.join(vs.REPO_ROOT, "AGENTS.md"),
                        os.path.join(tmp, "AGENTS.md"))
            p = os.path.join(tmp, ".agents", "skills", "automated-testing", "SKILL.md")
            text = open(p, encoding="utf-8").read()
            broken = text.replace("## Acceptance Criteria", "## Removed Criteria")
            open(p, "w", encoding="utf-8").write(broken)
            errors, _ = vs.check_root(tmp)
            self.assertTrue(any("Acceptance Criteria" in e for e in errors),
                            f"expected missing-section error, got: {errors}")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_bad_frontmatter_name_detected(self):
        tmp = tempfile.mkdtemp()
        try:
            shutil.copytree(os.path.join(vs.REPO_ROOT, ".agents"),
                            os.path.join(tmp, ".agents"))
            shutil.copy(os.path.join(vs.REPO_ROOT, "AGENTS.md"),
                        os.path.join(tmp, "AGENTS.md"))
            p = os.path.join(tmp, ".agents", "skills", "release-engineering", "SKILL.md")
            text = open(p, encoding="utf-8").read()
            broken = text.replace("name: release-engineering",
                                  "name: wrong-name", 1)
            open(p, "w", encoding="utf-8").write(broken)
            errors, _ = vs.check_root(tmp)
            self.assertTrue(any("wrong-name" in e for e in errors),
                            f"expected frontmatter mismatch error, got: {errors}")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
