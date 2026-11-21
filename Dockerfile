# syntax=docker/dockerfile:1.7
# One Dockerfile for all three services: docker build --build-arg MODULE=worker-service .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml ./
COPY config config
COPY common-contracts common-contracts
COPY job-core job-core
COPY orchestrator-api orchestrator-api
COPY scheduler-service scheduler-service
COPY worker-service worker-service
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    test -n "${MODULE}" \
    && mvn -B -q -pl "${MODULE}" -am package -DskipTests -Dcheckstyle.skip \
    && cp "${MODULE}/target/${MODULE}.jar" /app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
