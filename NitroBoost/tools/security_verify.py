#!/usr/bin/env python3
"""Narrow, dependency-free security regression checks for NitroBoost.

This is a repository guardrail, not a vulnerability scanner or certification.
Run from any directory with: python3 tools/security_verify.py
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
BUILD = ROOT / "app/build.gradle.kts"
WORKFLOW = REPO / ".github/workflows/build-apk.yml"
SRC = ROOT / "app/src/main/java"
ANDROID = "{http://schemas.android.com/apk/res/android}"

# Every declared permission must have an explicit product/security rationale.
# In particular, QUERY_ALL_PACKAGES must be justified in the Play declaration.
REVIEWED_PERMISSIONS = {
    "android.permission.POST_NOTIFICATIONS": "foreground session/overlay notification",
    "android.permission.SYSTEM_ALERT_WINDOW": "user-enabled in-game HUD",
    "android.permission.FOREGROUND_SERVICE": "user-started boost/overlay session",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE": "declared special-use game session/overlay",
    "android.permission.RECEIVE_BOOT_COMPLETED": "restore only previously opted-in startup behavior",
    "android.permission.INTERNET": "local TCP connectivity probe; no upload/cloud",
    "android.permission.ACCESS_NOTIFICATION_POLICY": "optional DND task with explicit capability check",
    "android.permission.WRITE_SETTINGS": "optional user-granted system settings tasks",
    "android.permission.PACKAGE_USAGE_STATS": "optional game/session foreground detection",
    "android.permission.QUERY_ALL_PACKAGES": "game library discovery; Play policy declaration required",
}

BOOT_ACTION = "android.intent.action.BOOT_COMPLETED"
LAUNCH_ACTION = "android.intent.action.MAIN"
LAUNCH_CATEGORY = "android.intent.category.LAUNCHER"
TILE_ACTION = "android.service.quicksettings.action.QS_TILE"
TILE_PERMISSION = "android.permission.BIND_QUICK_SETTINGS_TILE"
SHIZUKU_PERMISSION = "android.permission.INTERACT_ACROSS_USERS_FULL"


def attr(element: ET.Element, name: str) -> str | None:
    return element.get(ANDROID + name)


def component_actions(component: ET.Element) -> set[str]:
    return {
        action
        for intent_filter in component.findall("intent-filter")
        for node in intent_filter.findall("action")
        if (action := attr(node, "name")) is not None
    }


def component_categories(component: ET.Element) -> set[str]:
    return {
        category
        for intent_filter in component.findall("intent-filter")
        for node in intent_filter.findall("category")
        if (category := attr(node, "name")) is not None
    }


def manifest_findings(root: ET.Element, source_root: Path) -> list[str]:
    errors: list[str] = []
    app = root.find("application")
    if app is None:
        return ["manifest has no application element"]

    if attr(app, "allowBackup") != "false":
        errors.append("application must explicitly set android:allowBackup=\"false\"")
    if attr(app, "debuggable") == "true":
        errors.append("application must not be debuggable in the source manifest")
    if attr(app, "usesCleartextTraffic") != "false":
        errors.append("application must explicitly disable cleartext traffic")

    for permission in root:
        if not permission.tag.startswith("uses-permission"):
            continue
        name = attr(permission, "name")
        if name and name not in REVIEWED_PERMISSIONS:
            errors.append(f"unreviewed permission declared: {name}")

    for component in list(app):
        if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        name = attr(component, "name") or "(unnamed component)"
        short_name = name.rsplit(".", 1)[-1]
        exported = attr(component, "exported")
        if exported is None:
            errors.append(f"{name} must explicitly set android:exported to true or false")
            continue
        if exported != "true":
            continue

        actions = component_actions(component)
        categories = component_categories(component)
        permission = attr(component, "permission")
        if short_name == "MainActivity":
            if component.tag != "activity" or actions != {LAUNCH_ACTION} or categories != {LAUNCH_CATEGORY}:
                errors.append("exported MainActivity must expose only the MAIN/LAUNCHER entry point")
        elif short_name == "QuickTileService":
            if component.tag != "service" or permission != TILE_PERMISSION or actions != {TILE_ACTION}:
                errors.append("exported QuickTileService requires the QS_TILE action and BIND_QUICK_SETTINGS_TILE")
        elif short_name == "BootReceiver":
            if component.tag != "receiver" or actions != {BOOT_ACTION}:
                errors.append("exported BootReceiver must listen only for protected BOOT_COMPLETED")
            boot_source = source_root / "com/nitroboost/app/service/BootReceiver.kt"
            try:
                source = boot_source.read_text(encoding="utf-8")
            except OSError:
                source = ""
            if not re.search(r"intent\.action\s*!=\s*Intent\.ACTION_BOOT_COMPLETED", source):
                errors.append("BootReceiver must guard its action before doing work")
        elif short_name == "ShizukuProvider":
            if component.tag != "provider" or permission != SHIZUKU_PERMISSION:
                errors.append("exported ShizukuProvider requires INTERACT_ACROSS_USERS_FULL")
        else:
            errors.append(f"unexpected exported component: {name}")
    return errors


def sdk_findings(build_text: str) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    compile_match = re.search(r"\bcompileSdk\s*=\s*(\d+)", build_text)
    target_match = re.search(r"\btargetSdk\s*=\s*(\d+)", build_text)
    if not compile_match or not target_match:
        return ["could not read compileSdk/targetSdk from app/build.gradle.kts"], warnings
    compile_sdk = int(compile_match.group(1))
    target_sdk = int(target_match.group(1))
    if target_sdk > compile_sdk:
        errors.append(f"targetSdk {target_sdk} exceeds compileSdk {compile_sdk}")
    if target_sdk < 36:
        warnings.append(
            f"targetSdk is {target_sdk}; review Google Play API 36 policy before Play distribution"
        )
    return errors, warnings


def dependency_findings(build_text: str) -> list[str]:
    errors: list[str] = []
    dep_pattern = re.compile(
        r"\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|kapt|annotationProcessor)"
        r"\s*\(\s*[\"']([^\"']+)[\"']\s*\)"
    )
    for coordinate in dep_pattern.findall(build_text):
        parts = coordinate.split(":")
        if len(parts) < 3:
            continue
        version = parts[-1]
        if (
            "+" in version
            or "SNAPSHOT" in version.upper()
            or version.lower() in {"latest.release", "latest.integration"}
            or re.match(r"^[\[(].*,", version)
        ):
            errors.append(f"dependency version must be pinned, found {coordinate}")
    return errors


def _job_blocks(workflow: str) -> dict[str, str]:
    match = re.search(r"(?ms)^jobs:\s*\n(.*)\Z", workflow)
    if not match:
        return {}
    body = match.group(1)
    headers = list(re.finditer(r"(?m)^  ([A-Za-z0-9_-]+):\s*$", body))
    jobs: dict[str, str] = {}
    for index, header in enumerate(headers):
        end = headers[index + 1].start() if index + 1 < len(headers) else len(body)
        jobs[header.group(1)] = body[header.end():end]
    return jobs


def _permissions_block(block: str) -> str:
    match = re.search(r"(?ms)^    permissions:\s*\n((?:^      [^\n]*\n?)+)", block)
    return match.group(1) if match else ""



def action_pin_findings(workflow: str) -> list[str]:
    errors: list[str] = []
    for line_number, line in enumerate(workflow.splitlines(), start=1):
        match = re.search(r"\buses:\s*(.+)$", line)
        if not match:
            continue
        reference = match.group(1).split("#", 1)[0].strip()
        if reference.startswith("./"):
            continue
        if not re.search(r"@[0-9a-f]{40}$", reference):
            errors.append(f"GitHub Action ref on line {line_number} is not pinned to a full commit SHA")
        if not re.search(r"#\s*v?\d+\.\d+", line):
            errors.append(f"GitHub Action ref on line {line_number} needs a readable version comment")
    return errors


def workflow_findings(workflow: str) -> list[str]:
    errors: list[str] = []
    errors.extend(action_pin_findings(workflow))
    top = re.search(r"(?ms)^permissions:\s*\n((?:^  [^\n]*\n?)+)", workflow)
    if not top:
        errors.append("workflow must declare least-privilege top-level permissions")
    else:
        permission_lines = [line.strip() for line in top.group(1).splitlines() if line.strip()]
        if permission_lines != ["contents: read"]:
            errors.append("workflow default permissions must be limited to contents: read")

    jobs = _job_blocks(workflow)
    required_jobs = {"verify-build", "prepare-release", "sign-release", "publish-release"}
    for name in sorted(required_jobs - jobs.keys()):
        errors.append(f"workflow is missing {name} job")
    build = jobs.get("verify-build", "")
    prepare = jobs.get("prepare-release", "")
    signing = jobs.get("sign-release", "")
    publish = jobs.get("publish-release", "")

    build_permissions = _permissions_block(build)
    if not re.search(r"(?m)^      contents:\s*read\s*$", build_permissions):
        errors.append("verify-build must explicitly use contents: read")
    if "python3 tools/security_verify.py" not in build:
        errors.append("verify-build must execute the security regression gate")
    if "python3 -m unittest discover -s tools -p 'test_*.py' -v" not in build:
        errors.append("verify-build must execute the Python regression tests")

    prepare_permissions = _permissions_block(prepare)
    if not re.search(r"(?m)^      contents:\s*read\s*$", prepare_permissions):
        errors.append("prepare-release must explicitly use contents: read")
    if "needs: verify-build" not in prepare:
        errors.append("prepare-release must depend on verify-build")
    if not re.search(r"(?m)^    if:\s*github\.ref\s*==\s*['\"]refs/heads/main['\"]\s*$", prepare):
        errors.append("prepare-release must be restricted to refs/heads/main")

    signing_permissions = _permissions_block(signing)
    if not re.search(r"(?m)^      contents:\s*read\s*$", signing_permissions):
        errors.append("sign-release must use contents: read")
    if re.search(r"(?m)^      [^:]+:\s*write\s*$", signing_permissions):
        errors.append("sign-release must not have write permissions")
    if "needs: prepare-release" not in signing:
        errors.append("sign-release must consume the unsigned release from prepare-release")
    if not re.search(r"(?m)^    if:\s*github\.ref\s*==\s*['\"]refs/heads/main['\"]\s*$", signing):
        errors.append("sign-release must be restricted to refs/heads/main")
    if not re.search(r"apksigner.{0,2}\s+sign", signing):
        errors.append("sign-release must sign the prepared APK with apksigner")
    if re.search(r"(?m)^\s*(?:\./)?gradlew?\b", signing):
        errors.append("sign-release must not execute Gradle or project build scripts")

    publish_permissions = _permissions_block(publish)
    if not re.search(r"(?m)^      contents:\s*write\s*$", publish_permissions):
        errors.append("publish-release must explicitly request contents: write")
    if not re.search(r"(?m)^    if:\s*github\.ref\s*==\s*['\"]refs/heads/main['\"]\s*$", publish):
        errors.append("publish-release must be restricted to refs/heads/main")
    if not re.search(r"(?m)^    needs:.*verify-build.*sign-release", publish):
        errors.append("publish-release must wait for verified build and signing result")
    if "fail_on_unmatched_files: true" not in publish:
        errors.append("publish-release must fail if an expected APK asset is missing")
    if re.search(r"(?m)^\s*(?:\./)?gradlew?\b", publish):
        errors.append("publish-release must not execute Gradle or project build scripts")

    allowed_secrets = {
        "sign-release": {
            "NITRO_KEYSTORE_BASE64", "NITRO_KEYSTORE_PASSWORD", "NITRO_KEY_ALIAS", "NITRO_KEY_PASSWORD"
        },
        "publish-release": {"GITHUB_TOKEN"},
    }
    for name, block in jobs.items():
        permissions = _permissions_block(block)
        if name != "publish-release" and re.search(r"(?m)^      [^:]+:\s*write\s*$", permissions):
            errors.append(f"only publish-release may have write permissions (found in {name})")
        secret_refs = set(re.findall(r"\$\{\{\s*secrets\.([A-Z0-9_]+)\s*\}\}", block))
        if secret_refs - allowed_secrets.get(name, set()):
            errors.append(f"unexpected repository secret use in {name}: {', '.join(sorted(secret_refs - allowed_secrets.get(name, set())))}")
        if "actions/checkout@" in block and "persist-credentials: false" not in block:
            errors.append(f"{name} checkout must not persist the GitHub token")
    return errors


def secret_findings(source_root: Path) -> list[str]:
    errors: list[str] = []
    patterns = {
        "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
        "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
        "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    }
    ignored_dirs = {".git", "node_modules", "build", "dist", "__pycache__", ".gradle"}
    for path in source_root.rglob("*"):
        if any(part in ignored_dirs for part in path.parts):
            continue
        if not path.is_file() or path.suffix not in {
            ".kt", ".java", ".xml", ".kts", ".properties", ".yml", ".yaml"
        }:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for label, pattern in patterns.items():
            if pattern.search(text):
                errors.append(f"possible {label} embedded in {path.relative_to(source_root)}")
    return errors


def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []
    checks = 0

    try:
        manifest_root = ET.parse(MANIFEST).getroot()
        errors.extend(manifest_findings(manifest_root, SRC))
        if any(
            attr(permission, "name") == "android.permission.QUERY_ALL_PACKAGES"
            for permission in manifest_root
            if permission.tag.startswith("uses-permission")
        ):
            warnings.append("QUERY_ALL_PACKAGES requires a valid Google Play policy declaration if Play distribution is used")
        checks += 4
    except (OSError, ET.ParseError) as exc:
        errors.append(f"cannot parse Android manifest: {exc}")

    try:
        build_text = BUILD.read_text(encoding="utf-8")
        sdk_errors, sdk_warnings = sdk_findings(build_text)
        errors.extend(sdk_errors)
        warnings.extend(sdk_warnings)
        errors.extend(dependency_findings(build_text))
        checks += 2
    except OSError as exc:
        errors.append(f"cannot read app build configuration: {exc}")

    try:
        errors.extend(workflow_findings(WORKFLOW.read_text(encoding="utf-8")))
        checks += 5
    except OSError as exc:
        errors.append(f"cannot read GitHub Actions workflow: {exc}")

    errors.extend(secret_findings(REPO))
    checks += 1

    for warning in warnings:
        print(f"WARN  {warning}")
    for error in errors:
        print(f"ERROR {error}")
    if errors:
        print(f"\nFAIL — {len(errors)} security regression finding(s); {checks} check groups evaluated")
        return 1
    print(f"OK — {checks} security regression check groups; 0 findings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
