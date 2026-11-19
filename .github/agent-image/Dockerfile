# Prepared base image for OpenCode agent runs. Everything slow is installed here
# once (OpenCode, Git, gh, JDK 21, Maven and the project's Maven dependencies) so
# a worker only pulls the image, checks out the repository and starts the agent.
FROM eclipse-temurin:21-jdk AS jdk
FROM maven:3-eclipse-temurin-21 AS maven
FROM node:22-bookworm-slim
ARG OPENCODE_VERSION=latest
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY --from=maven /usr/share/maven /usr/share/maven
ENV JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/usr/share/maven \
    PATH=/opt/java/openjdk/bin:/usr/share/maven/bin:$PATH \
    MAVEN_OPTS=-Dmaven.repo.local=/opt/m2
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bash ca-certificates curl git gh jq python3 shellcheck \
 && rm -rf /var/lib/apt/lists/* \
 && npm install -g "@opencode/cli@${OPENCODE_VERSION}" \
 && npm cache clean --force \
 && opencode --version && java -version && mvn -v
# Warm the Maven repository so workers do not download dependencies. The
# workflow copies apps/api/pom.xml next to this file. A failure here only
# costs speed, never the image.
COPY pom.xml /tmp/warm/pom.xml
RUN mvn -B -q -f /tmp/warm/pom.xml dependency:go-offline || true; rm -rf /tmp/warm
ENV OPENCODE_DISABLE_AUTOUPDATE=1 \
    CI=true
LABEL org.opencontainers.image.description="OpenCode agent runner"
