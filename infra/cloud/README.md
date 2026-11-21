# Cloud infrastructure as code

Terraform for the separate **staging** and **production** AWS accounts. It
provisions an isolated network, a private encrypted and backed-up PostgreSQL
database, a private encrypted object bucket, an ECS Fargate service for the API
and the workers, and least-privilege IAM roles. The delivery design and recovery
gates this implements are in [../DEPLOYMENT.md](../DEPLOYMENT.md).

This module satisfies the infrastructure portion of **OPS-06** (one account and
one state per environment, policy checks before apply) and **OPS-02** (TLS,
encryption at rest for data, queues, logs and backups, restricted network and
cloud identity, least privilege). Application-level audit and clinical controls
remain in the API and database.

## Layout

| File | Responsibility |
|---|---|
| `main.tf` | Provider pinned to one account, required Terraform/provider versions and partial S3 backend |
| `variables.tf` | Every environment-specific value as a typed, validated variable; no secret or account value is committed |
| `network.tf` | Dedicated VPC, public and private subnets across two zones, S3 gateway and per-service interface endpoints, no internet egress from tasks and no single-AZ NAT, security groups |
| `database.tf` | KMS key, private subnet group and encrypted RDS with automated backups and RDS-managed credentials |
| `storage.tf` | Private bucket, disabled ACLs and public access, KMS encryption, versioning, export expiry and a TLS-only bucket policy |
| `iam.tf` | Scoped execution, API and worker roles; no wildcard action, principal or resource except the AWS-required ECR authorization scope |
| `compute.tf` | ECR, encrypted SQS with a dead-letter queue, ECS cluster and services, TLS load balancer and backlog-based scaling |
| `environments/` | One synthetic, committed `*.tfvars.example` per account; copy to a local, git-ignored `*.tfvars` before a real apply |
| `terraform_policy_check.py` | Offline standard-library gate that parses the Terraform and fails on a public bucket, unencrypted storage, missing backups or a wildcard role |
| `tests/` | Exhaustive synthetic tests plus a build-failing 100% line-coverage gate |
| `check.sh` | Canonical module gate; see [check.sh](check.sh) |

## Prerequisites (owned outside this module)

- Terraform 1.10 or newer (native S3 state locking with `use_lockfile`) and AWS provider 5.x.
- One dedicated AWS account per environment. The committed
  [staging](environments/staging.tfvars.example) and
  [production](environments/production.tfvars.example) files contain **synthetic**
  account ids, regions, CIDRs, bucket names and image digests. Copy one to a
  local `*.tfvars` (the repository ignores that suffix), replace the values
  with approved ones before any real apply, and never commit credentials.
- An encrypted S3 state bucket with native locking and a KMS key, one per
  account. State is configured with a partial `backend "s3" {}` block; supply
  the bucket, key, region and KMS key with `-backend-config` at plan time.
- A pre-provisioned ACM certificate ARN and the API/worker image digests. The
  delivery pipeline passes the exact promoted digest as a variable; image
  promotion, signing and the OIDC deploy role belong to the delivery pipeline,
  not to this module.
- An audited, time-limited production access approval. Applying production is
  a separate, human-authorized action after green CI, a reviewed plan and a
  rehearsed rollback.

## Plan and apply

Run from `infra/cloud`. The backend and variable files differ per account, so
staging can never read or overwrite production state. The provider's
`allowed_account_ids` also fails closed if the wrong credentials are used.

Staging plan:

```bash
cd infra/cloud
terraform init \
  -backend-config="bucket=<staging-state-bucket>" \
  -backend-config="key=smartclinic/staging.tfstate" \
  -backend-config="region=<staging-region>" \
  -backend-config="kms_key_id=<staging-state-key-arn>" \
  -backend-config="use_lockfile=true"
terraform plan -out=staging.tfplan \
  -var-file=environments/staging.tfvars.example
```

Production plan (only with approved, time-limited access):

```bash
cd infra/cloud
terraform init \
  -backend-config="bucket=<production-state-bucket>" \
  -backend-config="key=smartclinic/production.tfstate" \
  -backend-config="region=<production-region>" \
  -backend-config="kms_key_id=<production-state-key-arn>" \
  -backend-config="use_lockfile=true"
terraform plan -out=production.tfplan \
  -var-file=environments/production.tfvars.example
```

Review the saved plan for the expected account, resource names, private-only
data services and immutable image digests. Apply only that reviewed plan, in
the approved change window, with the required checks green:

```bash
terraform apply -lock-timeout=5m staging.tfplan
# Production: after approval and a reviewed production plan
terraform apply -lock-timeout=5m production.tfplan
```

Promote the same image digest to staging and production through the delivery
pipeline; do not rebuild per environment. Migrations run as a separate
deployment step before the new task definition is served, using the
expand/contract gate in `scripts/check-migrations.py`. Rollback is the previous
task-definition digest; the database change must be contract-safe first.

## Offline verification

No cloud credentials or Terraform binary are needed for the policy gate:

```bash
bash infra/cloud/check.sh
```

It runs the synthetic unit suite, proves 100% line coverage of the checker,
asserts the committed Terraform is conforming, and asserts that the synthetic
violation fixture fails with the policy-breach exit code. The Python test
parses the `.tf` files and fails on a public bucket, an unencrypted volume, a
backup-free database or a wildcard IAM role. Findings from this gate are
evidence of a static check, not a real deployment or a human approval.

## Scope limits

This module does not create the OIDC deploy role, sign images, provision TLS
certificates, register DNS, configure the WAF or perform the restore drill.
Those are separate, authorized work described in `infra/DEPLOYMENT.md` and the
operations runbooks. All identifiers in this directory are synthetic; no real
patient data or credential is present.

The offline checker is a guardrail, not a complete policy proof. It reads only
`*.tf` files directly under this directory: a `*.tf.json` file fails closed
rather than being silently skipped, but policies built from
`data "aws_iam_policy_document"` blocks, `file()` references or values supplied
in tfvars are not evaluated by it and need ordinary code review. The backend is
partially configured by design: CI must supply account-scoped `-backend-config`
values, and the saved plan must name the expected account and state key.
