# postgres:17-alpine (digest pinned; Dependabot bumps it)
FROM postgres:19-alpine@sha256:8b0ff4cdf221f0c53d7fcda681d3b461be725c492cd881c5ec9c0ad25023f188
RUN apk add --no-cache nginx
ARG VERSION=dev
ARG REVISION=unknown
ARG PROJECT_NAME=Orevia
LABEL org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION \
      org.opencontainers.image.title=$PROJECT_NAME
COPY infra/bootstrap/ /opt/orevia/
USER postgres
EXPOSE 8080
HEALTHCHECK --start-period=20s --interval=5s --timeout=3s --retries=12 CMD wget -qO- http://127.0.0.1:8080/health.txt >/dev/null && pg_isready -q -h 127.0.0.1 -U postgres
ENTRYPOINT ["/bin/bash", "/opt/orevia/start.sh"]
