# Aurevia Care, one image: staff web on 3000, API on 8080, PostgreSQL inside.
#
#   docker build -f Dockerfile.app -t aurevia-app .
#   aurevia run            (or: docker run -p 3000:3000 -p 8080:8080 aurevia-app)
#
# The database lives in memory (a tmpfs at /pgdata) and starts empty from the Flyway
# migrations. With AUREVIA_PERSIST=1 and a volume on /dumps it is dumped on a clean
# stop and restored on the next start.

# node:22-alpine (digest pinned; Dependabot bumps it)
FROM node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS web
WORKDIR /src
COPY project.json ./project.json
COPY apps/staff-web/package.json apps/staff-web/package-lock.json ./apps/staff-web/
RUN cd apps/staff-web && npm ci --no-audit --no-fund
COPY apps/staff-web ./apps/staff-web
RUN cd apps/staff-web && npm run build

# maven:3.9-eclipse-temurin-21 (digest pinned; Dependabot bumps it)
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS api
WORKDIR /src
COPY apps/api/pom.xml ./apps/api/pom.xml
RUN mvn -B -q -f apps/api/pom.xml dependency:go-offline -Pno-security || true
COPY apps/api ./apps/api
# spring-boot:repackage runs from the parent's plugin management, so pom.xml needs no change.
RUN mvn -B -q -f apps/api/pom.xml -Pno-security -DskipTests package spring-boot:repackage \
 && cp apps/api/target/*.jar /api.jar

# postgres:17-alpine (digest pinned; Dependabot bumps it)
FROM postgres:17-alpine@sha256:f9cf3e1f0066f0eb0c48707d864f0ac5941e9ff454a072077d97a4d278d07d73
RUN apk add --no-cache nginx openjdk21-jre-headless
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION \
      org.opencontainers.image.title="Aurevia Care" \
      org.opencontainers.image.source="https://github.com/dr3mro/Aurevia-Care"
COPY --from=api /api.jar /opt/aurevia/api.jar
COPY --from=web /src/apps/staff-web/dist /opt/aurevia/web
COPY db/migrations /opt/aurevia/migrations
COPY infra/app/start.sh infra/app/nginx.conf /opt/aurevia/
RUN mkdir -p /pgdata /dumps && chown postgres:postgres /pgdata /dumps && chmod 700 /pgdata
USER postgres
ENV AUREVIA_PERSIST=0 \
    AUREVIA_PROFILE=synthetic-auth \
    JAVA_OPTS="-Xmx512m"
VOLUME ["/dumps"]
EXPOSE 3000 8080
HEALTHCHECK --start-period=60s --interval=5s --timeout=3s --retries=12 \
  CMD wget -qO- http://127.0.0.1:3000/ >/dev/null && pg_isready -q -h 127.0.0.1 -U postgres
ENTRYPOINT ["/bin/bash", "/opt/aurevia/start.sh"]
