# RLNA — Research Literature & Novelty Assistant

An AI-assisted research workspace. Upload a set of papers, search them in plain
English, ask questions that are answered from the retrieved passages, compare
methodologies, find research gaps, and check an idea against what the corpus
already contains.

The defining constraint is honesty about evidence. Every AI output is built from
retrieved chunks, cites the papers it came from, reports its own confidence, and
refuses when the evidence is too thin to answer.

```
React (Vite)  ->  Spring Boot  ->  Neon PostgreSQL + pgvector
                       |            Backblaze B2 (S3-compatible)
                       +--------->  FastAPI  ->  Gemini / Groq / OpenRouter
```

---

## What it does

| Feature | Behaviour |
|---|---|
| **Paper indexing** | PDF to text, section detection, section-aware chunking, local embeddings, stored in pgvector. Runs on the server; closing the tab does not affect it. |
| **Semantic search** | Query embedding against the user's own chunks, filtered by owner inside the same SQL statement as the ranking. |
| **Section-weighted retrieval** | Every chunk carries its section, so gap analysis boosts Limitations and Future Work while comparison boosts Methodology and Results. |
| **Grounded Q&A** | Answers cite the chunk behind each claim. A claim citing a chunk that was not retrieved is dropped before rendering. |
| **Paper comparison** | Two or three papers across nine fixed dimensions. A dimension a paper does not cover is `Not reported`, never inferred. |
| **Research gaps** | Derived from limitations and future work the authors themselves wrote. A gap with no citable evidence is discarded, not shown. |
| **Novelty assessment** | Overlap, differences and potentially-novel aspects against the retrieved corpus. No numeric score, and the limitations banner is always visible. |
| **Literature review draft** | Themes, methods, agreements, contradictions. Only papers that contributed evidence are cited. |
| **Two-tier model routing** | Cheap tier for extraction, strong tier for reasoning, automatic failover across three providers with per-provider cooldown. |
| **Result caching** | SHA-256 of task, model, normalized query and evidence ids. An identical request makes no model call at all. |
| **Duplicate detection** | Exact content hash blocks a re-upload; trigram title match warns at upload; embedding similarity flags a match after indexing. |
| **Export** | Markdown, PDF and BibTeX, with confidence and limitations carried into the exported document. |

---

## Repository layout

```
Backend/          Spring Boot 3.4 (Java 21) - the public REST API
AI/               FastAPI (Python 3.12)     - PDF, embeddings, retrieval, models
Frontend/         React 19 + Vite + Tailwind 4
Database/         Flyway migrations (the single canonical copy of the schema)
Infrastructure/   Docker Compose, nginx config
Docs/             Architecture, API reference, implementation notes
venv/             Python virtual environment (git-ignored)
```

Migrations live only in `Database/migrations`. The backend build copies them onto
its classpath, so there is one copy of the schema and no drift.

---

## Running it locally

### Prerequisites

Java 21, Maven 3.9, Node 20+, Python 3.11+, Docker.

### 1. Configure

```bash
cp .env.example .env
```

Fill in `DATABASE_URL`, the `NEON_AUTH_*` values, and at least one LLM provider
key. See [Manual setup](#manual-setup) below for where each comes from.

### 2. Storage

Backblaze B2 in production, reached through its S3-compatible API. For local
development MinIO speaks the same API, so the storage code path is identical and
no cloud credentials are needed. Set `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD`
in `.env` first — any values will do, they never leave your machine — then:

```bash
docker compose --env-file .env -f Infrastructure/docker/docker-compose.yml up -d minio minio-init
```

### 3. Start the services

```bash
python -m venv venv && ./venv/Scripts/python.exe -m pip install -r AI/requirements.txt
```

```bash
cd AI && ../venv/Scripts/python.exe -m uvicorn app.main:app --port 8000
```

```bash
cd Backend && mvn spring-boot:run
```

```bash
cd Frontend && npm install && npm run dev
```

The frontend runs on <http://localhost:5173>, the API on `:8080`, the AI service
on `:8000`. Flyway applies the schema on the backend's first start.

### Everything in Docker

```bash
docker compose --env-file .env -f Infrastructure/docker/docker-compose.yml up --build
```

This also starts Postgres with pgvector, so the stack runs without Neon. It needs
`POSTGRES_PASSWORD` alongside the two MinIO values; Compose stops with a message
naming any that are missing rather than starting a database with a blank one.

> **Note for Windows users:** if the repository path contains spaces, npm's
> `.bin` shims can fail to resolve. The `Frontend/package.json` scripts invoke
> the tool entry points through `node` directly to avoid this.

---

## Tests

```bash
cd Backend && mvn test
```

```bash
cd AI && ../venv/Scripts/python.exe -m pytest
```

```bash
cd Frontend && npm run lint && npm run build
```

The Java suite covers upload validation, the cache fingerprint, the HTTP error
contract, and per-user isolation. The Python suite covers chunking and section
detection, the provider fallback chain against mocked providers, prompt-injection
fencing, and the honesty rules. No test makes a real provider call.

---

## Manual setup

These need credentials only you can create.

| What | Where | Needed for |
|---|---|---|
| **Neon database** | <https://console.neon.tech> | `DATABASE_URL`. Enable the `vector` extension (the first migration does this). |
| **Neon Auth** | Neon console, Auth tab | `NEON_AUTH_BASE_URL`, `NEON_AUTH_JWKS_URL`, `NEON_AUTH_ISSUER`. The issuer is the server **origin**, without the `/<db>/auth` path. |
| **Sign-in** | Neon Auth, OAuth providers | Email and password work with no setup. Google runs on Neon's shared development credentials; GitHub, and Google in production, need your own OAuth app's client id and secret. |
| **Backblaze B2** | <https://secure.backblaze.com> | Create a **private** bucket and an application key scoped to it, then set `B2_KEY_ID`, `B2_APPLICATION_KEY`, `B2_BUCKET_NAME`, `B2_ENDPOINT` and `B2_REGION`. The region is the middle segment of the endpoint host, for example `us-west-004` in `s3.us-west-004.backblazeb2.com`. |
| **LLM provider** | Google AI Studio, Groq, OpenRouter | At least one key. Without one, indexing and search still work; analysis reports that AI is unavailable. |

Nothing else requires manual work. Everything above is read from environment
variables, and both backends refuse to start if a required one is missing.

---

## Architecture notes

Longer reasoning lives in [Docs/Architecture](Docs/Architecture/architecture.md).
The short version:

**Why two backends.** Spring Boot does what Java is good at: REST, validation,
transactions, authorization. The PDF and embedding ecosystem is Python-native.
The boundary is strict: FastAPI holds no business logic, is never called by a
browser, and performs no authorization of its own.

**Why B2 through the S3 API rather than its native API.** The S3 surface means
one SDK, presigned URLs, and a provider that can be swapped by changing five
environment variables. Two settings are not optional against B2 and are set
explicitly in `StorageConfig`: request checksums are turned down to
when-required, because the AWS SDK's defaults send headers B2 rejects outright;
and the region must match the endpoint host, because B2 signs over it.

**Why pgvector, not a vector database.** The corpus is thousands of chunks. Keeping
vectors in Postgres means owner, project, year and section filters apply in the
same query as the similarity ordering, instead of a cross-system join in
application code.

**Why a thread pool, not a queue.** Job state is persisted, so progress survives a
restart and unfinished jobs are resumed on boot. A broker would add a service to
run for no benefit at one instance per service. The migration path is to replace
the executor with a queue consumer and leave the job table alone.

**Why no numeric novelty score.** A percentage would imply a precision the
retrieved evidence cannot support. The output is structured evidence with a
confidence band that is capped by corpus size and retrieval strength.
