# EEbC Verifier for WebCorC.
#
# The build context is the repository root, because the Verifier is built against the EEbC library there:
#   docker build -f verifier/Dockerfile -t eebc-verifier .
#   docker run --rm -p 8080:8080 eebc-verifier
#
# Configuration via environment variables (see README): VERIFIER_PORT, VERIFIER_HOST, VERIFIER_THREADS,
# VERIFIER_STACK_MB, VERIFIER_JOB_TTL_MINUTES.

FROM maven:3.9-eclipse-temurin-24 AS build
WORKDIR /src
# the EEbC library
COPY pom.xml ./
COPY src ./src
RUN mvn -q -B install -DskipTests
# the Verifier (fat jar)
COPY verifier ./verifier
RUN mvn -q -B -f verifier/pom.xml package -DskipTests

FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build /src/verifier/target/eebc-verifier.jar /app/eebc-verifier.jar
ENV VERIFIER_PORT=8080 \
    VERIFIER_HOST=0.0.0.0
EXPOSE 8080
USER 10001
ENTRYPOINT ["java", "-jar", "/app/eebc-verifier.jar"]
