# Develop server

Recommended ongoing-free candidate: Oracle Cloud Always Free Ampere A1 (current free-tenancy allowance: 2 OCPU / 12 GB RAM, subject to capacity and account limits). Linux ARM OCR already works on the local Apple Silicon Docker host; validate on Oracle before assuming the same latency. Start one OCR worker, low concurrency and a few invited testers. A 16 GB x86 Google Cloud trial VM is the fallback ($300 / up to 90 days for eligible new users; credits may exhaust earlier).

1. Choose an eligible account/home region near your testers and verify the console cost estimate and free quota. No paid load balancer/managed DB is needed for this initial test.
2. Install Docker Engine and Compose on the host. Clone this repository on `develop`.
3. From `deployment`, run `python3 setup_local.py`. Keep `.env` private; never regenerate existing secrets casually.
4. Once GitHub Actions has published all five backend images, authenticate to GHCR if the packages are private, then run `docker compose pull` and `docker compose up -d --no-build`. Do not run image builds on the small server.
5. Run `python3 smoke.py`, `python3 verify_database.py` and the API tests with separately supplied synthetic fixtures.
6. Configure a real HTTPS hostname/proxy and firewall before phone access; the default edge binds only 127.0.0.1. Do not expose MySQL/RabbitMQ or simply publish the demo API over HTTP. Set the mobile API URL to that HTTPS endpoint and create a new preview build.
7. Keep off-host encrypted backups and monitor memory/disk and any trial expiry. Use image SHA tags/digests for reproducible rollbacks after the first test.

This publishes development artifacts, not an automatic remote deployment: host/address, SSH/deploy identity, DNS/TLS and GHCR access still need configuring. The public demo account is enabled only for an isolated test environment; set IPETS_DEMO_USER_ENABLED=false before any wider release. Google login, malware quarantine and automatic push delivery remain release gaps.

References: https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm and https://docs.cloud.google.com/free/docs/free-cloud-features
