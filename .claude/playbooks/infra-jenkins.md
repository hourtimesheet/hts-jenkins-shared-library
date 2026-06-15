# Archetype: Infra / Jenkins / Pipeline

CI-as-code and orchestration repos: `hts-jenkins-shared-library` (Groovy shared
library), `hts-company-onboarding-pipeline-script` (Jenkins + mongosh onboarding
orchestration), `hts-support-scripts` (Groovy prod diagnostics), config repos.

## Validate
- Groovy shared library: rely on the repo's GitHub Action (`.github/workflows/ci.yaml`)
  and **Jenkins Pipeline Unit** tests if present (`./gradlew test`). At minimum,
  `groovy -e` / `groovysh` syntax-load changed scripts.
- Pipeline scripts (`Jenkinsfile`, `*.groovy`): lint with the Jenkins
  "Declarative Linter" via the controller if reachable; otherwise review against the
  shared-library API.
- mongosh scripts: dry-run against a scratch DB, never prod. Idempotent by design.

## Rules
- These repos are **load-bearing for every other repo's CI** — a broken shared-
  library change breaks the fleet. Change behind a version/branch and validate on a
  throwaway job before merging to the default branch.
- No prod credentials in scripts — pull from Jenkins credentials / Secrets Manager.
- Document the Jenkins job(s) that consume a changed script in the PR.
