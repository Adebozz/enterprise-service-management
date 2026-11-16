# syntax=docker/dockerfile:1
# One image for single-container hosts (Render's free tier, see docs/deployment.md): the React build
# is served by Spring Boot, so the web app and the API share an origin, exactly as behind nginx in
# Docker Compose. The refresh cookie stays first-party and SameSite=Strict.
# Docker Compose keeps using backend/Dockerfile and frontend/Dockerfile. Tests run in CI, not here.

# ---- 1. Web app build ---------------------------------------------------------------------------
FROM node:22-alpine AS web
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. Backend build, with the web app as static resources -------------------------------------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY backend/src src
COPY --from=web /app/frontend/dist/ src/main/resources/static/
RUN ./mvnw -B -q package -DskipTests -Djacoco.skip=true -Dspotless.check.skip=true \
 && java -Djarmode=tools -jar target/esm-backend-*.jar extract --destination target/app

# ---- 3. Runtime: JRE only, non-root -------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 esm && useradd --system --uid 10001 --gid esm --no-create-home esm
WORKDIR /app
COPY --from=backend /app/backend/target/app/lib/ ./lib/
COPY --from=backend /app/backend/target/app/esm-backend-*.jar ./app.jar
# Class Data Sharing: a training run starts the application context once (no database: Flyway off,
# Hibernate told not to read JDBC metadata) and records the classes it loaded. Loading them from the
# archive afterwards shortens every start, which on a free instance is every wake-up from sleep.
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect \
        -Dspring.datasource.url=jdbc:postgresql://localhost:5432/training \
        -Desm.security.jwt.secret=dHJhaW5pbmctcnVuLW9ubHktbm90LWEtcmVhbC1zZWNyZXQtMTIzNA== \
        -Desm.sla.breach-check.enabled=false \
        -jar app.jar
USER 10001:10001
EXPOSE 8080
# Sized for small instances (Render free: 512 MB, a fraction of a CPU): the serial collector and
# C1-only compilation start faster and use less memory than the defaults on one core.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "app.jar"]
