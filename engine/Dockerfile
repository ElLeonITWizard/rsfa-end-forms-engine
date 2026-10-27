# Cloud Run-ready image. NOT built or deployed yet (no Docker daemon in the POC environment).
# Build from the repo root:  docker build -f engine/Dockerfile -t end-forms-engine:dev .
# Lender templates are NOT baked in: mount them read-only (locally -v, on Cloud Run a GCS volume)
# and point ENGINE_TEMPLATE_DIRS at the mount.

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY engine/pom.xml engine/pom.xml
RUN mvn -q -B -f engine/pom.xml dependency:go-offline
COPY engine/src engine/src
# Unit tests run here; legacy-template tests skip because .local/ is not in the build context.
RUN mvn -q -B -f engine/pom.xml package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 engine
WORKDIR /app
COPY --from=build /src/engine/target/end-forms-engine.jar /app/end-forms-engine.jar
COPY mappings /app/mappings
ENV ENGINE_BIND=0.0.0.0 \
    PORT=8080 \
    ENGINE_MAPPINGS_DIR=/app/mappings \
    ENGINE_TEMPLATE_DIRS=/templates \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true"
USER engine
EXPOSE 8080
# ENGINE_AUTH must be set at deploy time: PLATFORM_IAM on Cloud Run (with --no-allow-unauthenticated)
# or API_KEY with ENGINE_API_KEY injected from Secret Manager. NONE refuses to bind 0.0.0.0.
ENTRYPOINT ["java", "-jar", "/app/end-forms-engine.jar", "serve"]
