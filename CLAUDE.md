# CLAUDE.md — hts-jenkins-shared-library

> Part of the **Hour Timesheet (HTS)** platform (`github.com/hourtimesheet`).
> Entry point for Claude Code in this repo. **Fleet-wide agents and protocols are
> inherited from the workbench** (`~/.claude/`); this file + `.claude/playbooks/`
> are the repo-specific customization layer. **Active** (Tier 1) — high-traffic, fully tailored bundle.

## What this repo is
Jenkins shared library — CI building blocks for the whole fleet.

## Stack
- Groovy, Jenkins shared library, GitHub Actions ci.yaml. Load-bearing for every repo's CI.
- **Data store:** MongoDB (dbs `hourtimesheet` / `payroll`) — company-scoped queries; DCAA records are append-only.
- **Build:** `./gradlew test  # Jenkins Pipeline Unit if present`
- **Deploy:** Consumed by every repo's Jenkins pipeline — validate on a throwaway job first
- **CI:** Jenkins (`hts-jenkins-shared-library`) + GitHub Actions
- **Secrets:** SSM Parameter Store (lambda) / Jasypt+KMS (Spring) / Secrets Manager `--profile lmntl` (acct 517311508324). See [`.claude/playbooks/credentials.md`](.claude/playbooks/credentials.md).
- **Default branch:** `main` — but **always resolve at runtime** (`git symbolic-ref --short refs/remotes/origin/HEAD`); the fleet mixes `main`/`master`.

## Validation gate (run before every PR)
```bash
groovy syntax-load changed scripts; CI ci.yaml
```
Best-effort: build/dep-resolution failures that are environmental (Artifactory/AWS
creds, Android SDK) are not code defects — flag them in the PR rather than blocking.

## Read first for your task type
| Task | Read |
|---|---|
| Any change / PR | [`.claude/playbooks/hts-sdlc.md`](.claude/playbooks/hts-sdlc.md) |
| Secrets | [`.claude/playbooks/credentials.md`](.claude/playbooks/credentials.md) |
| Infra Jenkins | [`.claude/playbooks/infra-jenkins.md`](.claude/playbooks/infra-jenkins.md) |
| Coordination / titles / worktrees | workbench playbooks in `~/.claude/playbooks/` |

## Agents
- **Inherited (fleet defaults):** The full 15-agent fleet set — `project-manager`, `implementation-engineer`, `code-review-architect`, `qa-test-engineer`, `security-risk-auditor`, `devops-engineer`, `data-engineer`, `technical-architect`, `site-reliability-engineer`, `pr-scope-reviewer`, `docs-verifier`, `product-owner`, `business-analyst`, `ux-ui-designer`, `api-integration-specialist` — is inherited from the workbench (`~/.claude/agents/`). The repo-tuned agents below override their fleet namesakes with HTS-specific knowledge.
- **Repo-tuned (in `.claude/agents/`):** `devops-engineer`, `code-review-architect`, `security-risk-auditor`
- **To add or override an agent:** drop a `.claude/agents/<name>.md` — project agents
  override the inherited fleet defaults of the same name. Tune freely per repo.

## SDLC (non-negotiable)
Branch → Code → Test → Validate (gate above) → Commit → PR → Audit → Deploy.
Never commit to the default branch. Never commit secrets. Production deploys go
through Jenkins — agents never dispatch prod unilaterally.
Full detail: [`.claude/playbooks/hts-sdlc.md`](.claude/playbooks/hts-sdlc.md).

## Coordination & identity
- gh identity: **DavidAllison**. Org: **hourtimesheet**. Tracker: **GitHub Issues**.
- Fleet coordination commands (installed by the workbench): `/claim`, `/release`,
  `/heartbeat`, `/coord-sweep`. Claim an issue before starting work that yields a PR.
