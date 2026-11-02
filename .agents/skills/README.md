# .agents/skills — NitroBoost Engineering Skills Catalog

Each skill is a directory containing a `SKILL.md` file with standard YAML
frontmatter (`name`, `description`) followed by fixed sections:

1. `## Mission and Scope`
2. `## When to Invoke (Triggers)`
3. `## Inputs and Outputs`
4. `## Execution Procedure`
5. `## Acceptance Criteria`
6. `## Testing and Verification`
7. `## Non-Negotiable Constraints`
8. `## Repository References`

Machine-readable catalog: `registry.json` (validated by `tools/verify_skills.py`).
Invocation map per development phase: `.agents/DEVELOPMENT_CYCLE.md`.
Mandatory entry point: `AGENTS.md` at the repository root.

| # | Skill | Role | Invoked at |
|---|---|---|---|
| 1 | `engineering-director` | Director / orchestrator | every cycle start, planning, release decision |
| 2 | `repository-intelligence` | Analyst | DISCOVER, ANALYZE |
| 3 | `android-architecture` | Architect / Android engineer | PLAN, IMPLEMENT |
| 4 | `autonomous-feature-inventor` | Innovator | INNOVATE |
| 5 | `performance-engineering` | Performance engineer | TEST (perf claims), IMPLEMENT (perf changes) |
| 6 | `adaptive-engineering` | Adaptive/A-B engineer | IMPLEMENT/TEST of adaptive engine changes |
| 7 | `system-integration-engineering` | Systems integration engineer | IMPLEMENT of shell/Shizuku/root/sysfs changes |
| 8 | `thermal-safety-engineering` | Thermal safety officer | always; overrides any performance work |
| 9 | `reliability-and-recovery` | Reliability engineer | IMPLEMENT/TEST of journal, rollback, crash paths |
| 10 | `automated-testing` | Test engineer | TEST (hard gate) |
| 11 | `security-engineering` | Security engineer | IMPLEMENT, REVIEW |
| 12 | `adversarial-review` | Independent reviewer | REVIEW (hard gate) |
| 13 | `release-engineering` | Release engineer | RELEASE |

Non-negotiable rules: `.agents/RULES.md` (RULE-01 … RULE-11).
Team model: `.agents/TEAM.md`. Innovation protocol: `.agents/INNOVATION.md`.
Development cycle: `.agents/DEVELOPMENT_CYCLE.md`.
