# Prepared base image for OpenCode agent runs. Built once by agent-image.yml and
# reused by every worker; nothing here is rebuilt per task. The warm Maven
# repository is written in a single RUN layer so slot images stay config-only.
FROM eclipse-temurin:21-jdk AS jdk
FROM maven:3-eclipse-temurin-21 AS maven
FROM node:22-bookworm-slim
ARG OPENCODE_VERSION=2.3.2
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY --from=maven /usr/share/maven /usr/share/maven
ENV JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/usr/share/maven \
    PATH=/opt/java/openjdk/bin:/usr/share/maven/bin:$PATH \
    MAVEN_OPTS=-Dmaven.repo.local=/opt/m2
# Only what the runner uses: git/gh for the trusted tail, jq for the auth file,
# python3 for checks, bash for shell features. shellcheck and curl are not used
# inside the worker (the quick gate guards shellcheck with command -v).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bash ca-certificates git gh jq python3 \
 && rm -rf /var/lib/apt/lists/* \
 && npm install -g "@opencode/cli@${OPENCODE_VERSION}" \
 && npm cache clean --force \
 && opencode --version && java -version && mvn -v
# Warm the Maven repository so workers do not download dependencies. The
# workflow copies apps/api/pom.xml next to this file. One RUN layer holds the
# cache and removes the scratch pom; a failure only costs speed, never the image.
COPY pom.xml /tmp/warm/pom.xml
RUN mvn -B -q -f /tmp/warm/pom.xml dependency:go-offline >/dev/null 2>&1 || true; rm -rf /tmp/warm
ENV OPENCODE_DISABLE_AUTOUPDATE=1 \
    CI=true
LABEL org.opencontainers.image.description="OpenCode agent runner"
