# postgres:17-alpine (digest pinned; Dependabot bumps it)
FROM postgres:18-alpine@sha256:0115267ab5a509022df9319c77be365cef335adb71556c75f60f5360ee247e71
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
