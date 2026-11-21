FROM postgres:17-alpine@sha256:526f86d93c6ea08fd3f1fe5605650cacc781b2c6013169a0ad8f42c7d3d84f32 # postgres:17-alpine
RUN apk add --no-cache nginx
ARG VERSION=dev
ARG REVISION=unknown
ARG PROJECT_NAME=SmartClinic
LABEL org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$REVISION \
      org.opencontainers.image.title=$PROJECT_NAME
COPY infra/bootstrap/ /opt/smartclinic/
USER postgres
EXPOSE 8080
HEALTHCHECK --start-period=20s --interval=5s --timeout=3s --retries=12 CMD wget -qO- http://127.0.0.1:8080/health.txt >/dev/null && pg_isready -q -h 127.0.0.1 -U postgres
ENTRYPOINT ["/bin/bash", "/opt/smartclinic/start.sh"]
