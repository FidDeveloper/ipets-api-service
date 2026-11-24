# Develop server

The selected provider is now **Google Cloud**, project `project-d786fe4f-a307-4760-93f`. Oracle registration failed; home/Mac hosting was rejected by the owner.

See the [GCP deployment runbook](gcp/README.md) for provisioning, CI/CD, HTTPS, Android testing, cost controls and start/stop instructions.

The selected setup is one 2 vCPU / 16 GB VM in Singapore, started only when testing, plus a Cloud Run proxy that supplies a Google HTTPS address and scales to zero. Local Docker Compose remains available separately. Deployed on 24 November 2026: https://ipets-edge-skogx2vwva-as.a.run.app. All 12 functional/OCR tests passed on the VM and through HTTPS. The VM automatically stops four hours after boot.
