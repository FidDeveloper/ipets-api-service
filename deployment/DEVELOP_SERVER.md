# Develop server

The selected provider is now **Google Cloud**, project `project-d786fe4f-a307-4760-93f`. Oracle registration failed; home/Mac hosting was rejected by the owner.

See the [GCP deployment runbook](gcp/README.md) for provisioning, CI/CD, HTTPS, Android testing, cost controls and start/stop instructions.

The selected setup is one 2 vCPU / 16 GB VM in Singapore, started only when testing, plus a Cloud Run proxy that supplies a Google HTTPS address and scales to zero. Local Docker Compose remains available separately. Cloud deployment must be verified after the owner's Google sign-in; prepared configuration alone does not mean the server is running.
