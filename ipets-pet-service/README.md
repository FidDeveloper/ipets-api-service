# ipets-pet-service

Java 21 / Spring Boot 4.2.0. Owner profiles, pets, medical records, feeding, journals, memories, sample bookings and demo reward redemptions, with owner-scoped CRUD and incremental sync.

This service now lives inside `FidDeveloper/ipets-api-service`, under its own directory, Dockerfile, Maven build and path-filtered workflow. Develop images are published independently; see the root README and deployment/DEVELOP_SERVER.md.

Run `mvn verify` with Java 21/Maven, or build this directory with Docker. Runtime configuration and private credentials are supplied by Compose; never commit `.env`.
