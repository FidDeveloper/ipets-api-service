# ipets-auth-service

Java 21 / Spring Boot 4.2.0. Registration/password test login, RS256 access tokens, hashed rotating refresh tokens, logout and account deletion. Google social login remains pending.

This service now lives inside `FidDeveloper/ipets-api-service`, under its own directory, Dockerfile, Maven build and path-filtered workflow. Develop images are published independently; see the root README and deployment/DEVELOP_SERVER.md.

Run `mvn verify` with Java 21/Maven, or build this directory with Docker. Runtime configuration and private credentials are supplied by Compose; never commit `.env`.
