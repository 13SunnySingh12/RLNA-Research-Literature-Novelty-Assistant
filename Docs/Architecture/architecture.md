# Architecture

What was built, and why it is shaped this way. This describes the implementation
as it actually exists; where the build diverged from `features.md`, the deviation
and its reason are recorded in
[../Implementation/decisions.md](../Implementation/decisions.md).

---

## 1. Services

```
                    ┌──────────────────────────────┐
                    │  React 19 + Vite (Frontend/) │
                    │  Landing · Dashboard · Library│
                    │  Search · Ask · Compare       │
                    │  Gaps · Novelty · History     │
                    └───────────────┬──────────────┘
                       Bearer JWT   │  HTTPS
                                    ▼
                    ┌──────────────────────────────┐
    Neon Auth ──────│  Spring Boot 3.4 (Backend/)  │
    (JWKS, offline) │                              │
                    │  Authentication verification │
                    │  Authorization + ownership   │
                    │  Projects, papers, metadata  │
                    │  Upload validation, B2 writes│
                    │  Job orchestration           │
                    │  Analysis history + cache    │
                    │  Academic API client         │
                    └───┬────────────┬─────────┬───┘
                        │            │         │
              JDBC      │   internal │  S3 API │
                        │   token    │         │
            ┌───────────▼──┐  ┌──────▼──────┐  ▼
            │ Neon Postgres│  │  FastAPI    │ Backblaze B2
            │ + pgvector   │◄─┤  (AI/)      ├─► (reads only)
            │              │  │             │
            │ users        │  │ extraction  │
            │ projects     │  │ sections    │
            │ papers       │  │ chunking    │
            │ sections     │  │ embeddings  │
            │ chunks       │  │ retrieval   │
            │ concepts     │  │ prompts     │
            │ analyses     │  │ router      │
            │ jobs         │  └──────┬──────┘
            └──────────────┘         │
                          ┌──────────┼──────────┐
                          ▼          ▼          ▼
                       Gemini      Groq    OpenRouter
                      (primary)  (primary) (fallback)
```

### Responsibility boundary

| Spring Boot owns | FastAPI owns |
|---|---|
| Verifying the access token | PDF text extraction and cleaning |
| Every ownership check | Section detection |
| Projects, papers, metadata | Section-aware chunking |
| Upload validation and object writes | Embedding generation |
| Job creation and terminal state | Vector retrieval and section weighting |
| Analysis history and the cache | Prompt construction |
| Academic API integration | Provider routing and fallback |
| Export assembly | Structured output validation, PDF rendering |

The rule that makes this a boundary rather than a suggestion: **FastAPI performs
no authorization.** It receives ids that Spring Boot has already checked, and it
is protected only by a shared token, which is exactly why it must never be
publicly routable.

FastAPI does write to the database, but only within its own domain: sections,
chunks, concepts, and job progress. It never writes a `papers` or
`analysis_results` row.

---

## 2. Authentication

Neon Auth (Better Auth) owns identity. The flow:

1. The browser signs in with Google or GitHub against Neon Auth directly.
2. Neon Auth sets a cross-site session cookie.
3. The frontend exchanges that session for a short-lived JWT at `/token`, and
   caches it until a minute before expiry.
4. Every API request carries that JWT as a bearer token.
5. Spring Boot verifies it offline against the cached JWKS.
6. The subject claim is mapped to a `users` row, created on first sight.

**The user id used for data access comes only from the verified token.** It is
never read from a request body, path, or query parameter. Controllers take an
injected `User` rather than a user id, so a caller has no way to nominate whose
data they are asking for.

### Why the JWT decoder is hand-rolled

Neon Auth signs with EdDSA over Ed25519. Spring Security's `NimbusJwtDecoder`
cannot consume those keys, and not for a reason configuration can fix: its key
selector converts every matched JWK to a `java.security.Key`, nimbus refuses to
export an OKP key that way, the conversion failure is swallowed, and the token is
rejected as "no matching key(s) found" even though the key matched.

`NeonAuthJwtDecoder` therefore keeps the key in its JWK form and hands it to
`Ed25519Verifier` directly. RSA and EC keys still go through the standard
verifier factory, so a future rotation onto a different algorithm keeps working.

---

## 3. Indexing pipeline

```
Upload  ──▶ validate (size, magic bytes, content hash)
        ──▶ store in B2 under a server-generated key
        ──▶ create paper + processing_job rows
        ──▶ 202 Accepted
                │
                ▼  after commit, on a bounded thread pool
        ──▶ FastAPI /internal/process-pdf
                │
                ├─ 10%  download from B2 (its own credentials)
                ├─ 25%  extract text, strip running headers, fix hyphenation,
                │       normalize ligatures, strip control bytes
                ├─ 40%  detect sections
                ├─ 55%  chunk, section-bounded, 512 tokens, 64 overlap
                ├─ 70%  embed locally (all-MiniLM-L6-v2, 384 dims)
                ├─ 90%  store sections and chunks in one transaction
                └─      duplicate check against existing embeddings
                │
                ▼  fast-tier model tidies title, authors, year, venue
        ──▶ Spring Boot writes metadata and marks the job complete
```

Progress is written to `processing_jobs` at each step, so the UI shows the actual
step rather than a spinner, and a restart resumes rather than restarting. On
boot, `PaperIndexingWorker` picks up any job still marked queued or running.

Failures are classified. Permanent ones (corrupt PDF, no text layer, text
Postgres will not store) fail immediately with a specific message. Transient ones
(provider outage, storage hiccup) retry up to three times with exponential
backoff. **The stored PDF is never deleted on failure**, so a failed index never
costs the user their upload.

---

## 4. Retrieval

One SQL statement does the ownership filter, the section weighting and the
ranking together:

```sql
SELECT c.id, c.content, c.section_type,
       1 - (c.embedding <=> :query) AS similarity,
       (1 - (c.embedding <=> :query))
         * CASE WHEN c.section_type = ANY(:boost) THEN :factor ELSE 1.0 END AS score
FROM paper_chunks c
JOIN papers p ON p.id = c.paper_id
WHERE p.user_id = :user_id
  AND (:project_id::uuid IS NULL OR p.project_id = :project_id::uuid)
  ...
ORDER BY score DESC
LIMIT :top_k
```

The owner filter is inside the statement, never applied to the results
afterwards. A post-filter would mean another user's chunks were read and ranked
before being discarded.

| Task | Boosted sections |
|---|---|
| `research_gap` | limitations, future_work, discussion |
| `comparison` | methodology, results |
| `novelty` | abstract, introduction, conclusion |
| `summary` | abstract, introduction, results, conclusion |
| `literature_review` | abstract, methodology, results, discussion |
| `qa` | none, deliberately |

Retrieved chunks are deduplicated, then ordered by paper and section for the
prompt, while *selection* under a tight context budget is by score, so the
lowest-scoring chunks are the ones dropped.

If the best similarity falls below `RETRIEVAL_MIN_SCORE`, the request is refused
before any model is called.

---

## 5. Analysis and caching

Every analysis follows the same path:

```
retrieve evidence (local embeddings, no cost)
        │
        ▼
cacheKey = SHA-256(task | model | normalized query | sorted evidence ids)
        │
        ├─ hit  ──▶ return the stored result, no model call
        │
        └─ miss ──▶ call FastAPI ──▶ validate ──▶ store ──▶ return
```

Retrieval happens first because it is free, which is what makes the fingerprint
possible before any spend. `/internal/retrieve` returns the model id that *would*
answer the task, so the fingerprint covers the model without a second round trip.

`analysis_results` has a unique constraint on `(user_id, cache_key)`, so two
concurrent identical requests cannot both pay: the loser reads the winner's row.

---

## 6. Object storage

Backblaze B2, reached through its S3-compatible API with the AWS SDK. One
implementation, in `StorageService`; nothing else in the codebase writes files.

```
papers/{user_id}/{paper_id}/original.pdf
```

Keys are built from ids the server already trusts. A user-supplied filename never
reaches a key, so a crafted name cannot escape its prefix or collide with another
user's object.

### What lives where

| Backblaze B2 | Neon PostgreSQL |
|---|---|
| The PDF bytes | `papers.storage_object_key`, the pointer |
| | `file_size`, `content_hash`, and the rest of the metadata |

No file content is ever stored in Postgres. The column is named for what it holds
rather than for the vendor holding it, which is why moving providers cost a
rename rather than a redesign.

### Access model

The bucket is private. Nothing is served from a public URL.

- **Writes** happen only in Spring Boot, after upload validation.
- **Reads by a user** go through a presigned GET, default TTL 300 seconds, issued
  only after an ownership check. The browser never receives a credential, and a
  link stops working when the object is deleted.
- **Reads by the AI service** use its own server-side credentials, given a key
  that Spring Boot has already authorized.
- **Deletes** run when a paper is deleted, after its rows are gone. A storage
  failure there is logged as an orphaned object rather than failing the user's
  delete.

### Three settings B2 requires

These are not tuning. Each one produces a failure that names an HTTP header
rather than the cause.

1. **Checksums are turned down to when-required.** Since 2.30 the AWS SDK adds
   `x-amz-sdk-checksum-algorithm` and `x-amz-checksum-mode` to every request by
   default; B2 rejects both with "Unsupported header ... received for this API
   call". The same applies to botocore from 1.36, so the AI service sets the
   equivalent options.
2. **Chunked encoding is off.** B2 does not accept the `aws-chunked` streaming
   payload signature.
3. **The region is real and matches the endpoint host.** B2 derives it from the
   host (`s3.us-west-004.backblazeb2.com` means `us-west-004`) and signs over it,
   so a placeholder fails authentication rather than routing.

Locally, MinIO stands in. It speaks the same API, so the exercised code path is
the one that runs against B2, and its region is pinned to the same value so a
wrong `B2_REGION` fails locally too rather than only in production.

---

## 7. Model routing

A static table, not a learned policy.

```
fast   tier: metadata_cleanup, keyword_extraction, classification
strong tier: summary, qa, comparison, research_gap, novelty, literature_review

fast   order: Groq   -> Gemini -> OpenRouter
strong order: Gemini -> Groq   -> OpenRouter
```

- Every call has a timeout, and at most two retries with exponential backoff.
- A 429 is **never** retried. The provider is put in a cooldown window and
  skipped entirely, which is what stops a retry storm extending a rate limit.
- Output that fails schema validation gets exactly one repair attempt, then the
  chain moves to the next provider.
- Failover is logged with provider, task and reason. Never credentials, never
  document content.
- The user never learns which provider answered, or that one failed.

---

## 8. Trust boundaries

Uploaded PDFs are untrusted input. A paper can contain "ignore previous
instructions", and a PDF is exactly where someone would put it.

1. Retrieved text is fenced between `<<<EVIDENCE` and `EVIDENCE>>>` markers.
2. Every system prompt states that content inside those markers is data, and that
   anything resembling an instruction inside them must be ignored.
3. Output must validate against a Pydantic schema, so a hijacked response fails
   validation instead of rendering.
4. Claims citing a chunk that was not in the retrieved context are dropped before
   the user sees them.
5. Model output is escaped before rendering and never used to build SQL.

Other boundaries:

- **Uploads** are verified by magic bytes, not by extension or declared type,
  and size-capped before anything is written.
- **Object keys** are built from server-side ids. A user-supplied filename never
  reaches a path.
- **PDF access** is a signed URL with a 300-second default TTL, issued only after
  an ownership check. The browser never receives a storage credential.
- **Rate limits** apply per user to upload, search and analysis.
- **Errors** carry a trace id; the detail behind it stays in the logs.

---

## 9. Data model

Eight tables. `users` is the application's own projection of an authenticated
identity, deliberately separate from Neon Auth's schema so application data has
no foreign key into a provider's tables.

Notable constraints, each solving a real problem:

| Constraint | Prevents |
|---|---|
| `papers(user_id, content_hash)` unique | The same bytes entering one library twice |
| `paper_chunks(paper_id, chunk_index)` unique | A retried index doubling a paper's chunks |
| `analysis_results(user_id, cache_key)` unique | Two concurrent identical requests both paying |
| `concepts(paper_id, concept, concept_type)` unique | A re-run duplicating extracted terms |
| `papers.project_id ON DELETE SET NULL` | A project deletion destroying indexed papers |

`paper_chunks.content_tsv` is a stored generated column, so the keyword index
cannot drift from the text it describes.

---

## 10. Deliberately excluded

Each of these was considered and left out.

| Excluded | Reason |
|---|---|
| Message queue | Job state is already durable; a broker adds a service to run for no benefit at one instance |
| Dedicated vector database | Two systems and a cross-system join, to do what one SQL statement already does |
| Redis | Database-backed result caching already removes the expensive calls |
| Numeric novelty score | Implies precision the evidence cannot support |
| OCR | A large dependency with poor accuracy on multi-column layouts; failure is reported clearly instead |
| Figure and table extraction | A stretch feature; the table and entity were removed rather than left unused |
| Kubernetes | Two containers |
