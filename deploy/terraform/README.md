# Terraform — aspirational, not currently provisioned

> **Heads-up.** This Terraform code provisions an EKS cluster + supporting
> AWS infrastructure (`ecr.tf`, `eks.tf`, `iam.tf`, `vpc.tf`). It is an
> **aspirational migration target**. **Nothing here is currently applied.**

## What actually runs production

`kinetixrisk.ai` is a **single-host docker-compose deployment** behind
Caddy, not EKS. See [`deploy/helm/README.md`](../helm/README.md) for the
full picture and the files that *do* drive prod.

## Why the code exists

These files describe the intended cloud shape if/when Kinetix migrates
from single-host docker-compose to Kubernetes-on-AWS. Until the
migration happens, changes here have **zero impact on the live
platform**.

If you `terraform plan` against this code, it will offer to create real
AWS resources — do not `terraform apply` without an explicit "we are
starting the migration now" decision from the team.
