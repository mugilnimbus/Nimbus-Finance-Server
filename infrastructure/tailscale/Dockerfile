ARG TAILSCALE_BASE_IMAGE=tailscale/tailscale:stable@sha256:8c42c4574ab066384fcb72f69e086a2ff1dd3652eb6f56856cee34bcf0d2f680
FROM ${TAILSCALE_BASE_IMAGE}

# Refresh the Alpine userspace without altering the pinned stable binary.
RUN apk upgrade --no-cache
