# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with the Maven wrapper -------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies change far less often than code: resolve them in their own cached layer.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI before an image is built (see .github/workflows); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q package -DskipTests -Djacoco.skip=true -Dspotless.check.skip=true \
 && java -Djarmode=tools -jar target/esm-backend-*.jar extract --layers --launcher --destination target/extracted

# ---- Runtime stage: JRE only, non-root, layered for cache-friendly pushes --------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 esm && useradd --system --uid 10001 --gid esm --no-create-home esm
WORKDIR /app

# Least-changing layers first: a code change only replaces the small "application" layer.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER 10001:10001
EXPOSE 8080
# Size the heap from the container's memory limit, and restart cleanly instead of limping on OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
