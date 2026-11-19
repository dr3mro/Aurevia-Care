# Prepared image for OpenCode agent runs. Everything slow is installed here once
# so a run only pulls the image, checks out the repository and starts the agent.
# Add the Java and Node toolchains here when the application modules arrive.
FROM node:22-bookworm-slim
ARG OPENCODE_VERSION=latest
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bash ca-certificates curl git gh jq python3 shellcheck \
 && rm -rf /var/lib/apt/lists/* \
 && npm install -g "opencode-ai@${OPENCODE_VERSION}" \
 && npm cache clean --force \
 && opencode --version
ENV OPENCODE_DISABLE_AUTOUPDATE=1 \
    OPENCODE_DISABLE_FREE_MODELS=0 \
    CI=true
LABEL org.opencontainers.image.description="OpenCode agent runner"
