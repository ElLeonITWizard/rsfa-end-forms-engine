# Private administration service image (ENGINE_ROLE=admin, D-041). Not deployed by this repository.
#
# Build ONLY from the staged admin context (tools/stage_admin.py), never from the repository root:
#   Cloud Build:  deploy/admin/cloudbuild.yaml (target `admin`: compiles and runs every unit test inside the build)
#   Local:        docker build -f deploy/admin/Dockerfile --target admin-prebuilt <context>   (jar built on the host)
#
# The image holds the jar, the repository manifests (mappings/, curation/; no client data) and the two status files
# the template library reads. It holds NO PDF: blank templates are uploaded at runtime into the private admin bucket.
# Same pinned base images as the engine (engine/Dockerfile, digests recorded 2026-10-27).
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

FROM ${RUNTIME_IMAGE} AS admin-base
# fonts-urw-base35: ZapfDingbats substitute so page previews draw the banks' check marks.
ARG APT_MIRROR=
RUN --mount=type=secret,id=build_ca \
    set -eu; \
    if [ -n "$APT_MIRROR" ]; then \
      sed -i -E "s#http://(archive|security)\.ubuntu\.com/ubuntu/?#$APT_MIRROR/#g" \
        /etc/apt/sources.list.d/*.sources /etc/apt/sources.list 2>/dev/null || true; \
    fi; \
    if [ -f /run/secrets/build_ca ]; then \
      install -m 0644 /run/secrets/build_ca /tmp/build-ca.crt; \
      echo 'Acquire::https::CAInfo "/tmp/build-ca.crt";' > /etc/apt/apt.conf.d/99build-ca; \
    fi; \
    apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-urw-base35 \
    && rm -rf /var/lib/apt/lists/* /etc/apt/apt.conf.d/99build-ca /tmp/build-ca.crt \
    && useradd --system --uid 10001 admin
WORKDIR /app
COPY mappings /app/mappings
COPY curation /app/curation
COPY deploy/poc-allowlist.json deploy/deployment-state.json /app/deploy/
ENV ENGINE_ROLE=admin \
    ENGINE_BIND=0.0.0.0 \
    PORT=8080 \
    ENGINE_MAPPINGS_DIR=/app/mappings \
    ADMIN_REPO_ROOT=/app \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true"
USER admin
EXPOSE 8080
# Required at deploy time (deploy_admin.sh): ADMIN_USERS, IAP_AUDIENCE, ADMIN_BUCKET. The service refuses to start
# without them, on a service not named "*admin*", or with any engine/relay secret present.
ENTRYPOINT ["java", "-jar", "/app/end-forms-engine.jar"]
CMD ["serve"]

FROM admin-base AS admin
COPY --from=build /src/engine/target/end-forms-engine.jar /app/end-forms-engine.jar

FROM admin-base AS admin-prebuilt
COPY engine/target/end-forms-engine.jar /app/end-forms-engine.jar
