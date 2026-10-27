# Cloud Run-ready image. Not deployed.
#
# CI / normal build (default target `runtime`, compiles and tests inside Docker):
#   docker build -f engine/Dockerfile -t end-forms-engine:dev .
# Local verification with a jar built on the host (`cd engine && mvn package`):
#   docker build -f engine/Dockerfile --target runtime-prebuilt -t end-forms-engine:prebuilt .
#
# Lender templates are NOT baked in: mount them read-only (locally -v, on Cloud Run a GCS volume)
# and point ENGINE_TEMPLATE_DIRS at the mount.
#
# Behind a TLS-intercepting proxy (corporate CI, sandboxes) pass the proxy's CA bundle as a BuildKit
# secret and, if plain-HTTP apt is blocked, an HTTPS mirror. The CA is trusted only during RUN steps
# and never stored in a layer:
#   docker build --secret id=build_ca,src=/path/ca-bundle.crt --build-arg APT_MIRROR=https://archive.ubuntu.com/ubuntu ...

# BUILD_IMAGE is overridable only so the build stage can be verified where Docker Hub is rate-limited.
ARG BUILD_IMAGE=maven:3.9.11-eclipse-temurin-21
FROM ${BUILD_IMAGE} AS build
WORKDIR /src
ARG MAVEN_EXTRA_OPTS=
# Optional extra CA: imported into a throw-away truststore used only by these Maven runs.
RUN --mount=type=secret,id=build_ca,required=false \
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
# Unit tests run here; legacy-template tests skip because .local/ is not in the build context.
RUN MAVEN_OPTS="$MAVEN_EXTRA_OPTS $( [ -f /tmp/build-truststore ] && echo -Djavax.net.ssl.trustStore=/tmp/build-truststore )" \
    mvn -q -B -f engine/pom.xml package

FROM eclipse-temurin:21-jre AS runtime-base
# Fonts are NOT needed to fill: overlays and the Unicode fallback use Liberation Sans embedded from the
# PDFBox jar, and bank templates carry their own fonts. They are installed so the `render` command
# (and any future flattening/thumbnails) draws standard-14 fonts correctly: fonts-urw-base35
# provides a ZapfDingbats substitute (D050000L) for the banks' check marks.
ARG APT_MIRROR=
RUN --mount=type=secret,id=build_ca,required=false \
    set -eu; \
    if [ -n "$APT_MIRROR" ]; then \
      sed -i -E "s#http://(archive|security)\.ubuntu\.com/ubuntu/?#$APT_MIRROR/#g" \
        /etc/apt/sources.list.d/*.sources /etc/apt/sources.list 2>/dev/null || true; \
    fi; \
    if [ -f /run/secrets/build_ca ]; then \
      echo 'Acquire::https::CAInfo "/run/secrets/build_ca";' > /etc/apt/apt.conf.d/99build-ca; \
    fi; \
    apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-urw-base35 \
    && rm -rf /var/lib/apt/lists/* /etc/apt/apt.conf.d/99build-ca \
    && useradd --system --uid 10001 engine
WORKDIR /app
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
ENTRYPOINT ["java", "-jar", "/app/end-forms-engine.jar"]
CMD ["serve"]

FROM runtime-base AS runtime-prebuilt
COPY engine/target/end-forms-engine.jar /app/end-forms-engine.jar

FROM runtime-base AS runtime
COPY --from=build /src/engine/target/end-forms-engine.jar /app/end-forms-engine.jar
