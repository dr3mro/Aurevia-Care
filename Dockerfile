# postgres:17-alpine (digest pinned; Dependabot bumps it)
FROM postgres:17-alpine@sha256:f9cf3e1f0066f0eb0c48707d864f0ac5941e9ff454a072077d97a4d278d07d73
RUN apk add --no-cache nginx
ARG VERSION=dev
ARG REVISION=unknown
ARG PROJECT_NAME=Aurevia
LABEL org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION \
      org.opencontainers.image.title=$PROJECT_NAME
COPY infra/bootstrap/ /opt/aurevia/
USER postgres
EXPOSE 8080
HEALTHCHECK --start-period=20s --interval=5s --timeout=3s --retries=12 CMD wget -qO- http://127.0.0.1:8080/health.txt >/dev/null && pg_isready -q -h 127.0.0.1 -U postgres
ENTRYPOINT ["/bin/bash", "/opt/aurevia/start.sh"]
