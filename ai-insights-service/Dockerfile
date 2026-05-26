# Multi-stage Dockerfile for the Python AI Insights service (FastAPI + Claude Agent SDK)

# --- Builder stage ---
FROM python:3.12-slim AS builder

WORKDIR /app

# Install uv for fast dependency management
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

# Copy dependency files first for better layer caching
COPY pyproject.toml uv.lock ./

# Install dependencies (no dev deps)
RUN uv sync --frozen --no-dev

# Copy source code
COPY src/ src/

# --- Runtime stage ---
FROM python:3.12-slim

# Node.js + the Claude Code CLI are required at runtime: the Python
# claude-agent-sdk spawns `claude` as a subprocess and authenticates via the
# host's ~/.claude session (mounted into the container). Without the CLI on
# PATH the live insight client raises InsightClientUnavailable and the
# factory falls back to canned mode — which is exactly the demo-mode UX
# we're trying to avoid here.
RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs npm ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && npm install -g @anthropic-ai/claude-code \
 && npm cache clean --force

# Give the kinetix user a real HOME so the SDK can find ~/.claude. The
# compose / Helm mount lands at $HOME/.claude (see docker-compose.services.yml
# and charts/ai-insights-service/templates/deployment.yaml).
RUN groupadd -r kinetix \
 && useradd -r -g kinetix -m -d /home/kinetix kinetix

WORKDIR /app

# Copy virtual environment and source from builder
COPY --from=builder /app/.venv .venv
COPY --from=builder /app/src src

RUN chown -R kinetix:kinetix /app

USER kinetix

ARG GIT_COMMIT_SHA=unknown
ENV GIT_COMMIT_SHA=${GIT_COMMIT_SHA}
ENV HOME=/home/kinetix
ENV PATH="/app/.venv/bin:$PATH"
# uv sync does not install the project in editable mode, so the package
# source lives under /app/src and must be on PYTHONPATH for imports to resolve.
ENV PYTHONPATH=/app/src

EXPOSE 8095

CMD ["uvicorn", "kinetix_insights.app:app", "--host", "0.0.0.0", "--port", "8095"]
