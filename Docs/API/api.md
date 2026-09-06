# API reference

Two APIs. The **public API** is what the browser talks to. The **internal API**
is what the application backend uses to reach the AI service, and is never
publicly routable.

A live, always-accurate reference is generated from the controllers themselves:

- OpenAPI document: `http://localhost:8080/v3/api-docs`
- Browsable UI: `http://localhost:8080/swagger-ui.html`

A ready-to-import request collection is at
[rlna.postman_collection.json](rlna.postman_collection.json).

---

## Conventions

- JSON in, JSON out. Plural resource nouns.
- Every `/api/**` endpoint requires `Authorization: Bearer <token>`, where the
  token comes from Neon Auth's `/token` endpoint.
- List endpoints take `page` and `size` and return a uniform envelope.
- Errors always use one shape:

```json
{
  "error": {
    "code": "PAPER_NOT_FOUND",
    "message": "This paper does not exist or you do not have access to it.",
    "traceId": "a1b2c3d4",
    "fields": { "name": "Give the project a name." }
  }
}
```

`fields` appears only on validation failures. `traceId` correlates with the
server log; the detail behind it is never returned.

### Status codes

| Code | Meaning |
|---|---|
| `200` | Success |
| `201` | Resource created |
| `202` | Accepted; work continues on the server |
| `204` | Success with no body |
| `400` | Malformed or invalid request |
| `401` | Missing, expired or invalid token |
| `404` | Not found **or not yours**. The API never distinguishes the two. |
| `409` | Conflict, such as a duplicate paper |
| `413` | File larger than the upload limit |
| `422` | Understood but unprocessable, including `INSUFFICIENT_EVIDENCE` |
| `429` | Rate limited |
| `500` | Unexpected server fault |
| `503` | A dependency is unavailable |

### Error codes worth handling

| Code | Status | Meaning |
|---|---|---|
| `UNSUPPORTED_FILE_TYPE` | 400 | The bytes are not a PDF, whatever the name said |
| `DUPLICATE_PAPER` | 409 | Identical content already in the library |
| `FILE_TOO_LARGE` | 413 | Above `MAX_UPLOAD_SIZE_MB` |
| `INSUFFICIENT_EVIDENCE` | 422 | Retrieval found too little; no model was called |
| `PAPER_NOT_INDEXED` | 422 | Indexing has not finished |
| `RATE_LIMITED` | 429 | Per-user budget exceeded |
| `AI_UNAVAILABLE` | 503 | No provider succeeded, or none is configured |
| `STORAGE_UNAVAILABLE` | 503 | Backblaze B2 is not reachable or not configured |

---

## Public API

### Auth

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/auth/session` | Current user; provisions the record on first call |
| `POST` | `/api/auth/logout` | Forwards sign-out to Neon Auth; always `204` |

### Projects

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/projects` | With paper counts and status breakdown |
| `POST` | `/api/projects` | `201` |
| `GET` | `/api/projects/{id}` | |
| `PATCH` | `/api/projects/{id}` | |
| `DELETE` | `/api/projects/{id}` | Papers survive, unassigned |
| `GET` | `/api/projects/{id}/analytics` | |

### Papers

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/papers` | `projectId, status, year, readingStatus, tag, q, sort, page, size` |
| `POST` | `/api/papers` | Multipart `files`. `202`, one outcome per file |
| `GET` | `/api/papers/{id}` | Detail with sections and concepts |
| `PATCH` | `/api/papers/{id}` | Title, tags, reading status, notes, project |
| `DELETE` | `/api/papers/{id}` | Removes the index and the stored object |
| `POST` | `/api/papers/{id}/file` | Attach a PDF to a metadata-only record |
| `GET` | `/api/papers/{id}/file` | Short-lived presigned B2 URL, issued after an ownership check |
| `GET` | `/api/papers/{id}/status` | Live indexing progress |
| `GET` | `/api/papers/{id}/duplicate` | `204` when there is no warning |
| `POST` | `/api/papers/{id}/reprocess` | Retry a failed index |
| `GET` | `/api/papers/tags` | Distinct tags |

A batch upload returns one entry per file, so a rejected file never discards the
accepted ones:

```json
[
  { "filename": "attention.pdf", "accepted": true,
    "paper": { "id": "...", "processingStatus": "queued" }, "duplicateOf": null },
  { "filename": "notes.txt", "accepted": false,
    "errorCode": "UNSUPPORTED_FILE_TYPE", "errorMessage": "Only PDF files are supported." }
]
```

### Search

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/search/semantic` | `{ query, projectId?, paperId?, topK?, task? }` |
| `GET` | `/api/search/keyword` | `q, projectId?, limit?` |

`task` selects the section-weighting profile: `research_gap`, `comparison`,
`novelty`, `summary`, `literature_review`, or omitted for unweighted.

### Analysis

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/papers/{id}/summary` | `{ refresh? }` |
| `POST` | `/api/papers/{id}/concepts` | `{ refresh? }` |
| `POST` | `/api/papers/{id}/question` | `{ question, topK?, refresh? }` |
| `POST` | `/api/projects/{id}/question` | `{ question, topK?, refresh? }` |
| `POST` | `/api/analysis/compare` | `{ paperIds: [2 or 3], refresh? }` |
| `POST` | `/api/analysis/research-gap` | `{ projectId, refresh? }` |
| `POST` | `/api/analysis/novelty` | `{ projectId, idea, includeAcademicSearch?, refresh? }` |
| `POST` | `/api/analysis/literature-review` | `{ projectId, refresh? }` |
| `GET` | `/api/analysis/history` | `type?, projectId?, paperId?, page, size` |
| `GET` | `/api/analysis/{id}` | |
| `DELETE` | `/api/analysis/{id}` | |

Every analysis response carries `fromCache`, and `evidenceRefs` naming the chunks
used and the corpus size they were drawn from. `providerUsed` and `modelId` are
stored but **never returned**: the user is not told which provider answered.

### Academic search, analytics, export

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/academic/search` | `q, limit?`. Returns `200` with `degraded: true` when the upstream is down |
| `POST` | `/api/academic/import` | `201`, creates a `metadata_only` paper |
| `GET` | `/api/analytics` | `projectId?` |
| `POST` | `/api/export` | `{ analysisId, format }`, format `markdown` `pdf` `bibtex`. Streams a file |
| `GET` | `/health` | Unauthenticated; per-dependency status |

---

## Internal API

Not publicly routable. Requires `Authorization: Bearer <ALLOWED_CALLER_TOKEN>`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/internal/process-pdf` | Extract, section, chunk, embed, store |
| `POST` | `/internal/embed` | Embed raw texts |
| `POST` | `/internal/retrieve` | Vector search with section weighting |
| `POST` | `/internal/summarize` | Structured paper summary |
| `POST` | `/internal/extract-concepts` | Keywords, methods, datasets, metrics |
| `POST` | `/internal/question` | Grounded answer with claims |
| `POST` | `/internal/compare` | Aligned comparison |
| `POST` | `/internal/research-gap` | Evidence-backed gaps |
| `POST` | `/internal/novelty` | Novelty assessment |
| `POST` | `/internal/literature-review` | Review draft |
| `POST` | `/internal/render-pdf` | Markdown to PDF, base64 |
| `GET` | `/health` | Unauthenticated |

Interactive docs are disabled on this service: publishing a schema browser would
widen the surface of something internal.

`/internal/retrieve` returns the `model_id` that would answer the given task, so
the caller can fingerprint a request against the model without a second call.
