# syntax=docker/dockerfile:1
FROM python:3.12-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/opt/models \
    SENTENCE_TRANSFORMERS_HOME=/opt/models

WORKDIR /srv

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# CPU-only torch. The default wheel pulls the CUDA stack, which is several
# gigabytes of GPU runtime this service will never use.
COPY AI/requirements.txt ./requirements.txt
RUN pip install --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt

# The embedding model is baked into the image so the first request after a cold
# start is not the one that waits for a model download (Section 31.1).
RUN python -c "from sentence_transformers import SentenceTransformer; \
SentenceTransformer('all-MiniLM-L6-v2', device='cpu')"

COPY AI/app ./app

RUN useradd --system --create-home --shell /usr/sbin/nologin rlna \
    && chown -R rlna:rlna /srv /opt/models
USER rlna

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8000/health >/dev/null || exit 1

CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
