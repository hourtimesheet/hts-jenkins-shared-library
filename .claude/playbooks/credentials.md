# HTS Credentials & Secrets

Three secret surfaces in HTS — know which applies:

| Surface | Store | How |
|---|---|---|
| Lambda runtime config (Mongo URL, payroll tokens) | **AWS SSM Parameter Store** | `/payroll-onboarding/lambda/${stage}/<Key>` — use **SecureString** for secrets |
| Spring service properties | **Jasypt** encryption + **AWS KMS** | `ENC(...)` values in `application*.properties`; KMS-wrapped keys |
| Any credential you create/receive (test accounts, API tokens, QBO OAuth, Stripe/Gusto keys) | **AWS Secrets Manager**, acct **517311508324** | `aws --profile lmntl secretsmanager create-secret --name hts/<env>/<name> ...` |

Rules (inherited from the workbench credential policy):
- **Secret store FIRST**, then optionally a mode-600 local cache whose first lines
  name the canonical secret + retrieval command. The store wins on disagreement.
- NEVER put secret VALUES in agent memory, transcripts, issue/PR bodies, comments,
  or logs. Reference the secret NAME only.
- Region is **us-west-2**. Use `--profile lmntl` for the HTS/M7/HTS estate account.
- To verify a secret, compare digests on the host that holds it — values never
  transit chat output.
- If a repo's docs don't name a store, that's a doc bug: use the table above and
  fix the doc in the same change.
