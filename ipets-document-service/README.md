# ipets-document-service

Java 21 / Spring Boot 4.2.0. Private volume-backed files, checksummed scan uploads, RabbitMQ OCR jobs, status and draft APIs. Production malware quarantine and object storage remain pending.

This service now lives inside `FidDeveloper/ipets-api-service`, under its own directory, Dockerfile, Maven build and path-filtered workflow. Develop images are published independently; see the root README and deployment/DEVELOP_SERVER.md.

Run `mvn verify` with Java 21/Maven, or build this directory with Docker. Runtime configuration and private credentials are supplied by Compose; never commit `.env`.
