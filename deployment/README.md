# Development deployment

This directory is tracked in `FidDeveloper/ipets-api-service` on `develop`. See [DEVELOP_SERVER.md](DEVELOP_SERVER.md) for server setup and deployment limitations.

Four Java services, Python PaddleOCR worker, MySQL, RabbitMQ and Nginx run as separate containers. For a fresh server, generate secrets with `python3 setup_local.py`, authenticate to GHCR if private, then `docker compose pull` and `docker compose up -d --no-build`. Existing data requires the original matching `.env`; do not regenerate database credentials/signing keys over existing volumes. The current local checkout retains the previous local secrets privately; they are excluded from Git.

Files currently live in a private Docker volume; production malware quarantine/storage/backups and HTTPS setup remain pending. Internal service/database ports are not published. The default edge binds loopback. Do not delete volumes to apply schema updates: Flyway owns migrations.

Run `python3 smoke.py` and `python3 verify_database.py`. `e2e_api.py` uses synthetic accounts; optional OCR image tests require separately supplied reference files, which are not published here.
