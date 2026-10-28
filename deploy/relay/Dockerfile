# Relay image (ENGINE_ROLE=relay, D-027/D-031, docs/15). Not deployed by this repository.
# Build ONLY from the staged relay context (tools/stage_relay.py), never from the repository root:
#   Cloud Build: deploy/relay/cloudbuild.yaml (target `relay`: compiles and runs every unit test inside the build)
#   Local:       docker build -f deploy/relay/Dockerfile --target relay-prebuilt <context>
# The relay holds no templates, no mappings at runtime and no secret in the image: the HMAC secret comes from Secret
# Manager at deploy time. Same pinned base images as the engine (engine/Dockerfile, digests recorded 2026-10-27).
ARG BUILD_IMAGE=mirror.gcr.io/library/maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
ARG RUNTIME_IMAGE=mirror.gcr.io/library/eclipse-temurin:21-jre@sha256:6ef3295d2cef695fd288f928a15a92081a7ce61da9e785b6fd09b2ea0c1aa8e5

FROM ${BUILD_IMAGE} AS build
WORKDIR /src
ARG MAVEN_EXTRA_OPTS=
RUN --mount=type=secret,id=build_ca \
    if [ -f /run/secrets/build_ca ]; then \
      mkdir -p /tmp/ca && cd /tmp/ca \
      && awk '/BEGIN CERT/{n++} {print > ("c" n ".pem")}' /run/secrets/build_ca \
      && cp "$JAVA_HOME/lib/security/cacerts" /tmp/build-truststore \
      && for f in c*.pem; do keytool -importcert -noprompt -storepass changeit -keystore /tmp/build-truststore -alias "$f" -file "$f" >/dev/null 2>&1 || true; done; \
    fi
COPY engine/pom.xml engine/pom.xml
RUN MAVEN_OPTS="$MAVEN_EXTRA_OPTS $( [ -f /tmp/build-truststore ] && echo -Djavax.net.ssl.trustStore=/tmp/build-truststore )" \
    mvn -q -B -f engine/pom.xml dependency:go-offline
COPY engine/src engine/src
RUN MAVEN_OPTS="$MAVEN_EXTRA_OPTS $( [ -f /tmp/build-truststore ] && echo -Djavax.net.ssl.trustStore=/tmp/build-truststore )" \
    mvn -q -B -f engine/pom.xml package

FROM ${RUNTIME_IMAGE} AS relay-base
RUN useradd --system --uid 10001 relay
WORKDIR /app
ENV ENGINE_ROLE=relay \
    ENGINE_BIND=0.0.0.0 \
    PORT=8080 \
    RELAY_REPLAY_STORE=gcs \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
USER relay
EXPOSE 8080
# Required at deploy time (deploy_relay.sh): RELAY_TARGET_URL, RELAY_REPLAY_BUCKET, RELAY_HMAC_SECRETS (Secret Manager).
# The service refuses to start without them, with an unsafe secret, or with a plain-http remote target.
ENTRYPOINT ["java", "-jar", "/app/end-forms-engine.jar"]
CMD ["serve"]

FROM relay-base AS relay
COPY --from=build /src/engine/target/end-forms-engine.jar /app/end-forms-engine.jar

FROM relay-base AS relay-prebuilt
COPY engine/target/end-forms-engine.jar /app/end-forms-engine.jar
