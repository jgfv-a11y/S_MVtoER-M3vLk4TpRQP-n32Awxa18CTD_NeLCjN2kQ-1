#!/usr/bin/env python3
"""
NitroBoost skills-system verifier.
Runs without Android SDK / network. Validates that the autonomous engineering
skills system is complete, discoverable, and wired into the development cycle:

  1. AGENTS.md exists at the repo root and references .agents/skills
  2. .agents/skills/registry.json is valid JSON with the expected schema,
     13 skills, and the 9-phase cycle
  3. every registry skill has .agents/skills/<name>/SKILL.md with valid
     frontmatter (name == dir name, kebab-case, non-empty description)
     and all 8 required sections
  4. no skill directory exists that is missing from the registry (and vice versa)
  5. .agents/RULES.md contains RULE-01 .. RULE-11
  6. .agents/DEVELOPMENT_CYCLE.md contains all 9 phase names
  7. .agents/TEAM.md contains roles R-1 .. R-9
  8. supporting files (cycle/innovation/team/changelog/verification/report)
     exist and are non-empty

Exit code 0 = skills system intact.
"""
import json
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKILLS_ROOT = os.path.join(REPO_ROOT, ".agents", "skills")
REGISTRY = os.path.join(SKILLS_ROOT, "registry.json")
AGENTS_MD = os.path.join(REPO_ROOT, "AGENTS.md")
AGENTS_DIR = os.path.join(REPO_ROOT, ".agents")

REQUIRED_HEADINGS = [
    "## Mission and Scope",
    "## When to Invoke (Triggers)",
    "## Inputs and Outputs",
    "## Execution Procedure",
    "## Acceptance Criteria",
    "## Testing and Verification",
    "## Non-Negotiable Constraints",
    "## Repository References",
]

EXPECTED_CYCLE = ["DISCOVER", "ANALYZE", "INNOVATE", "PLAN", "IMPLEMENT",
                  "TEST", "REVIEW", "RELEASE", "LEARN"]
EXPECTED_SKILL_COUNT = 13
NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")


def parse_frontmatter(text):
    """Return (dict, body) for a SKILL.md-style file, or (None, text)."""
    if not text.startswith("---\n"):
        return None, text
    end = text.find("\n---\n", 4)
    if end == -1:
        return None, text
    fm = {}
    for line in text[4:end].splitlines():
        m = re.match(r"^([a-zA-Z_-]+):\s*(.*)$", line)
        if m:
            fm[m.group(1).strip()] = m.group(2).strip()
    return fm, text[end + 5:]


def check_root(root=REPO_ROOT):
    errors, warnings = [], []

    def err(msg):
        errors.append(msg)

    def warn(msg):
        warnings.append(msg)

    skills_root = os.path.join(root, ".agents", "skills")
    registry_path = os.path.join(skills_root, "registry.json")
    agents_md = os.path.join(root, "AGENTS.md")

    # 1. AGENTS.md entry point
    if not os.path.isfile(agents_md):
        err("AGENTS.md missing at repository root")
    else:
        text = open(agents_md, encoding="utf-8").read()
        if ".agents/skills" not in text:
            err("AGENTS.md does not reference .agents/skills")
        if "DEVELOPMENT_CYCLE" not in text or "RULES.md" not in text:
            warn("AGENTS.md should reference the cycle and rules documents")

    # 2. registry
    registry = None
    if not os.path.isfile(registry_path):
        err("registry.json missing at .agents/skills/registry.json")
    else:
        try:
            registry = json.load(open(registry_path, encoding="utf-8"))
        except Exception as e:
            err(f"registry.json is not valid JSON: {e}")
    if registry is not None:
        skills = registry.get("skills", [])
        if len(skills) != EXPECTED_SKILL_COUNT:
            err(f"registry lists {len(skills)} skills, expected {EXPECTED_SKILL_COUNT}")
        if registry.get("cycle") != EXPECTED_CYCLE:
            err("registry cycle does not match the 9-phase DISCOVER..LEARN cycle")
        names = [s.get("name", "") for s in skills]
        if len(set(names)) != len(names):
            err("duplicate skill names in registry")
        # 3+4. skill dirs vs registry
        on_disk = sorted(d for d in os.listdir(skills_root)
                         if os.path.isdir(os.path.join(skills_root, d))) \
            if os.path.isdir(skills_root) else []
        if sorted(names) != on_disk:
            err(f"registry names {sorted(names)} != skill dirs on disk {on_disk}")
        for s in skills:
            name = s.get("name", "")
            if not NAME_RE.match(name or ""):
                err(f"invalid skill name (kebab-case required): {name!r}")
            skill_md = os.path.join(skills_root, name, "SKILL.md")
            if not os.path.isfile(skill_md):
                err(f"missing {skill_md}")
                continue
            raw = open(skill_md, encoding="utf-8").read()
            fm, body = parse_frontmatter(raw)
            if fm is None:
                err(f"{name}/SKILL.md has no YAML frontmatter (--- ... ---)")
                continue
            if fm.get("name") != name:
                err(f"{name}/SKILL.md frontmatter name={fm.get('name')!r} != dir {name!r}")
            if not fm.get("description"):
                err(f"{name}/SKILL.md frontmatter description is empty")
            for h in REQUIRED_HEADINGS:
                if h not in body:
                    err(f"{name}/SKILL.md missing required section: {h}")
            if not s.get("description"):
                warn(f"registry entry for {name} has no description")

    # 5. rules
    rules_path = os.path.join(root, ".agents", "RULES.md")
    if not os.path.isfile(rules_path):
        err(".agents/RULES.md missing")
    else:
        text = open(rules_path, encoding="utf-8").read()
        for i in range(1, 12):
            if f"RULE-{i:02d}" not in text:
                err(f".agents/RULES.md missing RULE-{i:02d}")

    # 6. cycle phases
    cycle_path = os.path.join(root, ".agents", "DEVELOPMENT_CYCLE.md")
    if not os.path.isfile(cycle_path):
        err(".agents/DEVELOPMENT_CYCLE.md missing")
    else:
        text = open(cycle_path, encoding="utf-8").read()
        for phase in EXPECTED_CYCLE:
            if phase not in text:
                err(f".agents/DEVELOPMENT_CYCLE.md missing phase {phase}")

    # 7. team roles
    team_path = os.path.join(root, ".agents", "TEAM.md")
    if not os.path.isfile(team_path):
        err(".agents/TEAM.md missing")
    else:
        text = open(team_path, encoding="utf-8").read()
        for i in range(1, 10):
            if f"R-{i}" not in text:
                err(f".agents/TEAM.md missing role R-{i}")
        if "sub-agent" not in text.lower() and "sub-agents" not in text.lower():
            warn(".agents/TEAM.md should document the no-sub-agents platform constraint")

    # 8. supporting files
    for rel in ["INNOVATION.md", "CHANGELOG.md", "VERIFICATION.md", "REPORT.md",
                "skills/README.md"]:
        p = os.path.join(root, ".agents", rel)
        if not os.path.isfile(p) or os.path.getsize(p) == 0:
            err(f".agents/{rel} missing or empty")

    return errors, warnings


def main():
    errors, warnings = check_root()
    for w in warnings:
        print(f"warning: {w}")
    if errors:
        for e in errors:
            print(f"error: {e}")
        print(f"SKILLS SYSTEM BROKEN: {len(errors)} error(s), {len(warnings)} warning(s)")
        return 1
    print(f"skills system OK: {EXPECTED_SKILL_COUNT} skills, "
          f"{len(EXPECTED_CYCLE)} phases, 11 rules, entry point AGENTS.md")
    for w in warnings:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
