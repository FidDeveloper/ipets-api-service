# iPets Java APIs

Four independently built/deployed Java 21 / Spring Boot services in the shared repository chosen by the owner. `develop` is the development release branch.

| Directory | Responsibility | Develop image |
|---|---|---|
| ipets-auth-service | Accounts, JWT issuance, refresh sessions | ghcr.io/fiddeveloper/ipets-api-service/auth:develop |
| ipets-pet-service | Pets, medical records, owner/lifestyle data | ghcr.io/fiddeveloper/ipets-api-service/pet:develop |
| ipets-document-service | Private documents, scans and OCR jobs | ghcr.io/fiddeveloper/ipets-api-service/document:develop |
| ipets-notification-service | Stored care reminders and sync | ghcr.io/fiddeveloper/ipets-api-service/notification:develop |

Each service has its own path-filtered CI workflow and image; changing one service does not rebuild the others. Pushes to develop/main verify and publish commit-tagged and branch-tagged images for AMD64 and ARM64. This repository grouping does not merge the running services or databases.

See [deployment/DEVELOP_SERVER.md](deployment/DEVELOP_SERVER.md) for the development-server checklist. No host credentials or real medical records are committed. The previous sibling service folders in the local workspace are preserved as migration references; this shared repository is the publishing source now.
