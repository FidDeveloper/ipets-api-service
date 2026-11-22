# GCP development deployment

Status: prepared; live deployment and billing verification require the project owner's Google sign-in. Do not treat local tests as proof that the GCP deployment is running.

Project: `project-d786fe4f-a307-4760-93f`. Singapore (`asia-southeast1-b`). One on-demand `e2-highmem-2` VM (2 vCPU, 16 GB RAM), 60 GB balanced boot disk. A small Cloud Run nginx proxy supplies the Google `https://…run.app` address and TLS, with minimum instances 0 and maximum 1. No purchased domain, GKE, managed database, load balancer, NAT gateway or VPC connector.

The VM has an ephemeral external IP for outbound package/image downloads. Inbound access permits only IAP SSH and the dedicated Cloud Run subnet to the API on the VM's private address. MySQL, RabbitMQ and internal service routes are not public. VM credentials/secrets are generated on the server, not copied from the Mac. The public demo password is disabled; create a test user through registration. Google social login remains separate unfinished application work.

## First deployment

Install/authenticate the official Google Cloud CLI, select this project and link the trial billing account. Never send login codes, card details or private keys in chat. From `deployment`, run:

```bash
bash gcp/launch.sh
```

If using a CLI installed outside PATH, set `GCLOUD` to its absolute executable path. `launch.sh` provisions networking/IAM/VM/registry, bootstraps Docker, downloads published images, runs security and functional/OCR tests, deploys the HTTPS proxy, then repeats the functional tests through HTTPS. An initial CLI login must have project provisioning and billing-budget permissions. Review console estimates; this is a trial-credit deployment, not an Always Free stack.

`provision.py` creates a monthly alert budget of **25 in the billing account's currency**, excluding credits, with 50/90/100% notifications to the billing account's default recipients. This is deliberately an early warning; it does not stop spending. Existing matching budgets are retained.

## CI/CD

Set the nonsecret variables printed by provisioning in **both** API and OCR repositories: `GCP_PROJECT_ID`, `GCP_ZONE`, `GCP_INSTANCE`, `GCP_DEPLOY_ACCOUNT`, `GCP_WIF_PROVIDER`. Set `GCP_DEPLOY_ENABLED=true` only after initial cloud tests pass. Workload Identity Federation trusts the exact numeric GitHub owner/repository IDs and only `refs/heads/develop`; no Google service-account key is stored in GitHub.

Each Java service and OCR pipeline builds/tests/publishes independently, then deploys that service's immutable SHA image through IAP. A host file lock serializes deployments across repositories. Every deployment runs health, anonymous/forged JWT rejection, auth/refresh, CRUD, ownership isolation, deletion and actual PaddleOCR on a synthetic printed card. Failed pull/start/check restores previous image selections; database schema changes and configuration changes need manual review because image rollback cannot undo migrations or restore old configuration.

The deployment-configuration workflow refreshes all successful `develop` image tags and records their pulled digests. Secrets, database volumes and uploaded files are retained. Do not delete the VM's disk or run `docker compose down -v` to redeploy.

Set `EXPO_PUBLIC_API_URL` in `ipets-mobile` repository variables to the resulting HTTPS URL. Run its **Android development APK** workflow on `develop`; download/install its APK artifact. It embeds JavaScript and uses the generated development keystore, suitable for testing only. iOS/store signing is deferred.

## Start only when testing

The backend is intentionally unavailable when the VM is stopped. CI publishes images but defers deployment while it is stopped; CI does not start a billable VM automatically.

```bash
gcloud compute instances start ipets-develop --project=project-d786fe4f-a307-4760-93f --zone=asia-southeast1-b
```

After starting, run **Deployment configuration → Run workflow → develop** in the API repository to deploy the latest successfully published images and test them. Existing containers also restart automatically with their last images on boot. When finished:

```bash
gcloud compute instances stop ipets-develop --project=project-d786fe4f-a307-4760-93f --zone=asia-southeast1-b
```

An additional systemd timer powers off the VM four hours after each boot to prevent forgotten test sessions from running indefinitely. To extend one active session, use IAP SSH and `sudo systemctl restart ipets-autostop.timer`; then stop the VM yourself. This timer may interrupt an active request/deployment at the cutoff.

## Costs and limits

Estimate with **running hours**, not calendar time: `VM hourly rate × test hours + persistent disk + IPv4 while running + registry storage + any Cloud Run/traffic usage above allowances`. Confirm Singapore SKU prices and your billing currency before running. The 60 GB disk remains billable while stopped; ephemeral IPv4 is released on stop. The HTTPS proxy scales to zero when idle but is a metered service, not a guarantee of zero cost. Cloud trial credit also has an expiry; unspent credit does not extend it.

This is one development host with no high availability and no off-host backup yet. Use synthetic data until backups and production security/recovery work are complete. Handwritten OCR quality is not asserted by this cloud fixture; review extracted medical records before saving.

References: [Cloud Run HTTPS URLs](https://docs.cloud.google.com/run/docs/triggering/https-request), [Direct VPC egress](https://docs.cloud.google.com/run/docs/configuring/vpc-direct-vpc), [GitHub federation](https://docs.cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines), [VM pricing](https://cloud.google.com/products/compute/pricing/general-purpose), [IPv4 pricing](https://cloud.google.com/vpc/pricing), [budget alerts are not caps](https://docs.cloud.google.com/billing/docs/how-to/budgets).
