FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn --batch-mode verify
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data/files && chown 10001:10001 /data/files
COPY --from=build /build/target/ipets-document-service-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
