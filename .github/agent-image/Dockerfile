# Prepared base image for OpenCode agent runs. Built once by agent-image.yml and
# reused by every worker; nothing here is rebuilt per task. The warm Maven
# repository is written in a single RUN layer so slot images stay config-only.
# eclipse-temurin:21-jdk (digest pinned; Dependabot bumps it)
FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS jdk
# maven:3-eclipse-temurin-21 (digest pinned; Dependabot bumps it)
FROM maven:3-eclipse-temurin-21@sha256:3e18d95a0a49d18aa5b075e5ee3c30a98078c382850467dcd4caa49bb7dc4038 AS maven
# node:22-bookworm-slim (digest pinned; Dependabot bumps it)
FROM node:22-bookworm-slim@sha256:e9f565d32859f0cbfb52125eafe8be39390f24dac302dc3cdf566e8ba3ab21ee
ARG OPENCODE_VERSION=2.3.2
# npm integrity (sha512, base64) of that exact version; change both together.
ARG OPENCODE_INTEGRITY=sha512-U/DD6X8kgnEFM3VYT7+QN/UJotW++qOxTJtPEUiNzKwu9tNHtAC1jOtup1gpk773j0AFNmMBbLNou4YkdvlRKA==
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY --from=maven /usr/share/maven /usr/share/maven
ENV JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/usr/share/maven \
    PATH=/opt/java/openjdk/bin:/usr/share/maven/bin:$PATH \
    MAVEN_OPTS=-Dmaven.repo.local=/opt/m2
# Only what the runner uses: git/gh for the trusted tail, jq for the auth file,
# python3 for checks, bash for shell features, and PostgreSQL 17 (same major as
# CI) so workers can run the integration suites. curl and gnupg only fetch the
# PostgreSQL apt key. shellcheck is not used inside the worker (the quick gate
# guards it with command -v).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bash ca-certificates curl git gh gnupg jq python3 python3-yaml python3-coverage shellcheck doxygen \
 && install -d /usr/share/keyrings \
 && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
      | gpg --dearmor -o /usr/share/keyrings/pgdg.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] https://apt.postgresql.org/pub/repos/apt bookworm-pgdg main" \
      > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends postgresql-17 \
 && (pg_dropcluster --stop 17 main || true) \
 && rm -rf /var/lib/apt/lists/* \
 && cd /tmp && tarball="$(npm pack "@opencode/cli@${OPENCODE_VERSION}" --silent)" \
 && node -e 'const c=require("crypto"),f=require("fs");const h="sha512-"+c.createHash("sha512").update(f.readFileSync(process.argv[1])).digest("base64");if(h!==process.argv[2]){console.error("OpenCode CLI integrity mismatch: "+h);process.exit(1)}' "$tarball" "${OPENCODE_INTEGRITY}" \
 && npm install -g "/tmp/$tarball" && rm -f "/tmp/$tarball" && cd / \
 && npm cache clean --force \
 && opencode --version && java -version && mvn -v \
 && python3 -I -c 'import yaml'
# Warm the Maven repository so workers do not download dependencies. The
# workflow copies apps/api/pom.xml next to this file. One RUN layer holds the
# cache and removes the scratch pom; a failure only costs speed, never the image.
COPY pom.xml /tmp/warm/pom.xml
RUN mvn -B -q -f /tmp/warm/pom.xml dependency:go-offline >/dev/null 2>&1 || true; rm -rf /tmp/warm
COPY start-postgres.sh /usr/local/bin/start-postgres
RUN chmod 0755 /usr/local/bin/start-postgres
ENV PATH=/usr/lib/postgresql/17/bin:$PATH \
    AUREVIA_PG_ADMIN=postgres \
    OPENCODE_DISABLE_AUTOUPDATE=1 \
    CI=true
LABEL org.opencontainers.image.description="OpenCode agent runner"
