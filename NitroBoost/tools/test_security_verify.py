"""Unit tests for the narrow security regression gate (not app pentest tests)."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from security_verify import (
    ROOT,
    SRC,
    action_pin_findings,
    dependency_findings,
    manifest_findings,
    workflow_findings,
)

ANDROID = "http://schemas.android.com/apk/res/android"


def manifest_with(component: str, *, backup: str = "false", cleartext: str = "false") -> ET.Element:
    text = f'''<manifest xmlns:android="{ANDROID}">
      <application android:allowBackup="{backup}" android:usesCleartextTraffic="{cleartext}">
        {component}
      </application>
    </manifest>'''
    return ET.fromstring(text)


LAUNCHER = '''<activity android:name=".ui.MainActivity" android:exported="true">
  <intent-filter><action android:name="android.intent.action.MAIN" />
  <category android:name="android.intent.category.LAUNCHER" /></intent-filter>
</activity>'''


class ManifestSecurityTests(unittest.TestCase):
    def test_repository_manifest_passes_export_and_backup_policy(self):
        root = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        self.assertEqual([], manifest_findings(root, SRC))

    def test_backup_must_be_disabled(self):
        root = manifest_with(LAUNCHER, backup="true")
        self.assertTrue(any("allowBackup" in error for error in manifest_findings(root, SRC)))

    def test_cleartext_must_be_disabled(self):
        root = manifest_with(LAUNCHER, cleartext="true")
        self.assertTrue(any("cleartext" in error for error in manifest_findings(root, SRC)))

    def test_unknown_exported_component_is_rejected(self):
        component = '<service android:name=".service.Unreviewed" android:exported="true" />'
        root = manifest_with(LAUNCHER + component)
        self.assertTrue(any("unexpected exported component" in error
                            for error in manifest_findings(root, SRC)))

    def test_unreviewed_permission_is_rejected(self):
        root = manifest_with(LAUNCHER)
        root.insert(0, ET.Element("uses-permission", {f"{{{ANDROID}}}name": "android.permission.CAMERA"}))
        self.assertTrue(any("unreviewed permission" in error
                            for error in manifest_findings(root, SRC)))

    def test_shizuku_provider_requires_signature_permission(self):
        provider = '<provider android:name="rikka.shizuku.ShizukuProvider" android:exported="true" />'
        root = manifest_with(LAUNCHER + provider)
        self.assertTrue(any("ShizukuProvider" in error
                            for error in manifest_findings(root, SRC)))

    def test_launcher_must_not_add_unreviewed_intent_filters(self):
        component = LAUNCHER.replace(
            "</activity>",
            '<intent-filter><action android:name="com.example.UNREVIEWED" /></intent-filter></activity>',
        )
        root = manifest_with(component)
        self.assertTrue(any("MAIN/LAUNCHER" in error
                            for error in manifest_findings(root, SRC)))


def safe_workflow() -> str:
    return '''permissions:
  contents: read
jobs:
  verify-build:
    permissions:
      contents: read
    steps:
      - run: |
          python3 tools/security_verify.py
          python3 -m unittest discover -s tools -p 'test_*.py' -v
  prepare-release:
    if: github.ref == 'refs/heads/main'
    needs: verify-build
    permissions:
      contents: read
    steps: []
  sign-release:
    if: github.ref == 'refs/heads/main'
    needs: prepare-release
    permissions:
      contents: read
      actions: read
    steps:
      - run: apksigner sign
        env:
          KEYSTORE_BASE64: ${{ secrets.NITRO_KEYSTORE_BASE64 }}
  publish-release:
    if: github.ref == 'refs/heads/main'
    needs: [verify-build, sign-release]
    permissions:
      contents: write
      actions: read
    steps:
      - uses: softprops/action-gh-release@efb35369e0ad2afab669f228072c1b0d510eae64 # v3.0.3
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        with:
          fail_on_unmatched_files: true
'''


class WorkflowSecurityTests(unittest.TestCase):
    def test_mutable_action_tag_is_rejected(self):
        self.assertTrue(action_pin_findings("uses: actions/checkout@v7 # v7.0.1"))

    def test_full_sha_with_version_comment_is_accepted(self):
        self.assertEqual(
            [], action_pin_findings(
                "uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1"
            )
        )

    def test_repository_workflow_has_least_privilege_jobs(self):
        text = (ROOT.parent / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
        self.assertEqual([], workflow_findings(text))

    def test_release_write_scope_in_build_job_is_rejected(self):
        text = safe_workflow().replace(
            "  verify-build:\n    permissions:\n      contents: read",
            "  verify-build:\n    permissions:\n      contents: write",
        )
        self.assertTrue(any("verify-build" in error or "default" in error
                            for error in workflow_findings(text)))

    def test_release_job_must_be_main_only(self):
        text = safe_workflow().replace(
            "if: github.ref == 'refs/heads/main'", "if: always()"
        )
        self.assertTrue(any("refs/heads/main" in error for error in workflow_findings(text)))

    def test_publish_job_must_not_receive_signing_secrets(self):
        text = safe_workflow().replace(
            "GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}",
            "GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}\n          KEY_PASSWORD: ${{ secrets.NITRO_KEY_PASSWORD }}",
        )
        self.assertTrue(any("unexpected repository secret use in publish-release" in error
                            for error in workflow_findings(text)))


class DependencySecurityTests(unittest.TestCase):
    def test_fixed_dependency_versions_are_accepted(self):
        self.assertEqual([], dependency_findings('implementation("x:y:1.2.3")'))

    def test_dynamic_dependency_versions_are_rejected(self):
        self.assertTrue(dependency_findings('implementation("x:y:1.+")'))


if __name__ == "__main__":
    unittest.main()
