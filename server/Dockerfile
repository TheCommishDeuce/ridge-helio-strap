# Build context: the REPO ROOT (see deploy/compose.yaml).
FROM python:3.13-slim
COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /usr/local/bin/uv
WORKDIR /app
COPY server/pyproject.toml server/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY server/src ./src
RUN uv sync --frozen --no-dev
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
EXPOSE 8766
# Migrate, then serve. One container, so there is no older instance to migrate under.
CMD ["sh", "-c", "python -m strap_server.migrate && exec uvicorn strap_server.api:app --host 0.0.0.0 --port 8766"]
