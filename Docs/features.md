# RLNA — Research Literature Review & Novelty Assistant

**Feature Specification**

An AI-assisted research workspace that helps students and researchers upload a set of papers, search them semantically, ask grounded questions, compare methodologies, and get evidence-backed answers about *what has already been done* — and where the gaps are.

| | |
|---|---|
| **Project type** | Full-stack + AI application |
| **Difficulty** | High-medium to advanced |
| **Built by** | Individual developer (B.Tech CSE final year / fresher) |
| **Stack** | React · Spring Boot · FastAPI · PostgreSQL + pgvector · Cloudflare R2 · Gemini / Groq |
| **Deployment** | Vercel + Render + Neon + R2 (free tier) |

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Problem Statement](#2-problem-statement)
3. [Project Goals & Scope](#3-project-goals--scope)
4. [Target Users](#4-target-users)
5. [What Makes RLNA Different](#5-what-makes-rlna-different)
6. [Technology Stack](#6-technology-stack)
7. [System Architecture](#7-system-architecture)
8. [Core Features](#8-core-features)
9. [Advanced Features](#9-advanced-features)
10. [Optional / Stretch Features](#10-optional--stretch-features)
11. [AI Features](#11-ai-features)
12. [AI Model Strategy](#12-ai-model-strategy)
13. [RAG Pipeline](#13-rag-pipeline)
14. [Research Gap Identification](#14-research-gap-identification)
15. [Novelty Assessment](#15-novelty-assessment)
16. [User Workflows](#16-user-workflows)
17. [Functional Requirements](#17-functional-requirements)
18. [Database Design](#18-database-design)
19. [Vector Search Design](#19-vector-search-design)
20. [File Storage Design](#20-file-storage-design)
21. [API Design](#21-api-design)
22. [Authentication & Authorization](#22-authentication--authorization)
23. [Frontend Design](#23-frontend-design)
24. [Background Processing](#24-background-processing)
25. [Caching & Cost Control](#25-caching--cost-control)
26. [Security Requirements](#26-security-requirements)
27. [Error Handling](#27-error-handling)
28. [Non-Functional Requirements](#28-non-functional-requirements)
29. [Testing Strategy](#29-testing-strategy)
30. [Environment Configuration](#30-environment-configuration)
31. [Deployment](#31-deployment)
32. [MVP Definition](#32-mvp-definition)
33. [Implementation Phases](#33-implementation-phases)
34. [Intentionally Not Included](#34-intentionally-not-included)
35. [Future Enhancements](#35-future-enhancements)
36. [Resume Summary](#36-resume-summary)

---

# 1. Project Overview

**RLNA (Research Literature Review & Novelty Assistant)** is a web application that turns a folder of research PDFs into a searchable, queryable, comparable knowledge base — and then helps the user answer the two questions that actually matter at the start of a research project:

> *"What has already been done in this area?"*
> *"Is my idea actually new, and where is the unexplored space?"*

A user creates a research project, uploads papers (or finds them through free academic APIs), and RLNA automatically extracts the text, detects the paper's sections, splits it into chunks, embeds those chunks, and stores them in PostgreSQL using `pgvector`.

Once indexed, the user can:

- Search their entire library in plain English instead of by filename
- Ask questions and get answers grounded in the actual retrieved text, with citations back to the source paper and section
- Compare two or more papers across methodology, dataset, results, and limitations
- Get research-gap suggestions derived from the limitations and future-work sections of real papers
- Run a novelty check on a proposed idea against the papers that were actually retrieved

The defining constraint of the project is **honesty about evidence**. Every AI output is built from retrieved chunks, references the papers it came from, and reports its own confidence and limitations. The system never claims to prove novelty — it reports what the retrieved literature shows.

---

# 2. Problem Statement

A student starting a thesis or a final-year project typically collects 20–60 papers and then hits four problems:

| Problem | Current reality |
|---|---|
| **Volume** | Reading 40 papers properly takes weeks |
| **Retrieval** | Files are named `1706.03762.pdf`; finding "the one that used a transformer on ECG data" means opening them one by one |
| **Comparison** | Comparing methodology across five papers means five tabs and a manual table |
| **Novelty** | There is no cheap way to check whether an idea already exists before investing months in it |

Generic AI chat tools do not solve this well, because they answer from model memory rather than from *your* papers. They hallucinate citations, cannot see the PDF you actually have, and give no way to trace a claim back to a page in a real document.

RLNA solves the retrieval problem first and the generation problem second: **retrieve from the user's own indexed corpus, then reason only over what was retrieved.**

---

# 3. Project Goals & Scope

### Product goals

1. Make a personal research library semantically searchable within seconds of upload.
2. Answer research questions with evidence, not recall — every claim traceable to a chunk.
3. Reduce the time to a first literature-review draft from days to under an hour.
4. Give an evidence-grounded, clearly-bounded novelty signal on a proposed research idea.
5. Present AI output honestly: confidence, evidence, and limitations always visible.

### Engineering goals

1. Clean separation between the application backend (Spring Boot) and the AI/document backend (FastAPI).
2. One database, one vector-search technology — no infrastructure sprawl.
3. Provider-agnostic AI layer: models are configuration, not code.
4. Graceful degradation when an AI provider is rate-limited or down.
5. Fully deployable and demonstrable on free tiers.
6. Every architectural decision defensible in an interview in under two minutes.

### In scope

Paper management · PDF processing · semantic search · RAG Q&A · summarization · paper comparison · research-gap identification · novelty assessment · academic API search · analytics dashboard · export.

### Out of scope

See [Section 34 — Intentionally Not Included](#34-intentionally-not-included).

---

# 4. Target Users

| User | Primary need | Key feature for them |
|---|---|---|
| Final-year B.Tech / M.Tech student | Understand an unfamiliar area fast; validate a project topic | Semantic search, novelty check |
| Research scholar (MS / PhD) | Build and maintain a literature review | Multi-paper synthesis, gap analysis |
| Early-career researcher | Check whether an idea is already published | Novelty assessment |
| Small R&D team member | Compare approaches before choosing one | Methodology comparison |

RLNA is designed around **one researcher and their own library**. Multi-user collaboration is deliberately excluded.

---

# 5. What Makes RLNA Different

This project is not "another PDF chatbot." The differentiation is in the workflows, not the technology list.

| # | Differentiator | Why it matters |
|---|---|---|
| 1 | **Section-aware chunking** | Chunks carry their section label (Methodology, Limitations, Future Work). Retrieval can be *weighted by section* — gap analysis searches Limitations and Future Work specifically, not the whole paper. This single idea makes gap analysis genuinely useful instead of generic. |
| 2 | **Evidence-first output contract** | Every AI response returns structured JSON with `claims[]`, each carrying `paper_id`, `section`, and the supporting chunk. Unsupported claims are dropped before rendering. |
| 3 | **Novelty assessment as a bounded, honest feature** | Reports overlap, differences, and confidence against *the retrieved corpus*, and explicitly states that absence of evidence is not proof of novelty. |
| 4 | **Two-tier model routing with fallback** | Cheap model for extraction/classification, strong model for reasoning, automatic failover across three providers. Demonstrates real cost and reliability engineering. |
| 5 | **Duplicate detection on upload** | Cosine similarity against existing embeddings catches the same paper uploaded twice under a different filename. |
| 6 | **Analysis reuse cache** | Identical analysis requests are served from stored results, cutting API usage dramatically. Fingerprint = hash of (task + model + evidence chunk IDs). |
| 7 | **Polyglot backend with a real reason** | Java for the application layer, Python for the ML/PDF ecosystem — a defensible architecture decision, not a résumé keyword. |

---

# 6. Technology Stack

### Frontend
- **React.js** (JavaScript, no TypeScript)
- React Router, Axios
- Tailwind CSS or a component library
- Recharts (analytics dashboard)

### Application Backend
- **Java 17+**
- **Spring Boot 3.x**
- Spring Web (REST), Spring Data JPA, Spring Validation
- Maven

### AI / Document Backend
- **Python 3.11+**
- **FastAPI** + Uvicorn
- PyMuPDF (PDF text extraction)
- Sentence Transformers (`all-MiniLM-L6-v2`)
- Pydantic (structured AI output validation)
- httpx (async provider calls)

### Database
- **Neon PostgreSQL** (serverless Postgres, free tier)
- **pgvector** extension for vector similarity search
- Flyway (Spring Boot) for schema migrations

### Object Storage
- **Cloudflare R2** (S3-compatible, free egress)

### Authentication
- **Neon Auth** — Google and GitHub sign-in

### AI Providers
- **Google Gemini API** — primary (long-context reasoning)
- **Groq API** — primary (fast inference)
- **OpenRouter API** — fallback

### External Data
- **Free academic APIs** (e.g. Semantic Scholar / OpenAlex / arXiv) for paper metadata and discovery

### DevOps
- **Docker** (both backends)
- **Git + GitHub**
- **Vercel** (frontend) · **Render** (backends)

### Why two backends?

> **Interview answer:** Spring Boot handles what Java is strongest at — REST APIs, validation, transactional data access, authorization, and orchestration. The PDF and ML ecosystem (PyMuPDF, Sentence Transformers, provider SDKs) is Python-native, and reimplementing it in Java would be significantly worse. These are two services with one clear boundary, not a microservice architecture: FastAPI holds no business logic and is never called by the browser.

---

# 7. System Architecture

```text
┌──────────────────────────────────────────────────────────┐
│                    React Frontend                        │
│                       (Vercel)                           │
│   Dashboard · Library · Paper View · Chat · Compare      │
└───────────────────────────┬──────────────────────────────┘
                            │ HTTPS / REST
                            │ Neon Auth session
┌───────────────────────────▼──────────────────────────────┐
│                Spring Boot — Application API             │
│                        (Render)                          │
│                                                          │
│  Auth verification · Authorization · Projects · Papers   │
│  Upload handling · R2 integration · Academic API client  │
│  Job orchestration · Analysis history · Caching          │
└──────┬──────────────────────┬────────────────────┬───────┘
       │                      │                    │
       │                 internal REST             │
       │                (service token)            │
┌──────▼───────┐   ┌──────────▼──────────┐  ┌──────▼───────┐
│ Neon         │   │  FastAPI — AI API   │  │ Cloudflare   │
│ PostgreSQL   │◄──┤       (Render)      ├─►│ R2           │
│              │   │                     │  │              │
│ • users      │   │ • PDF extraction    │  │ • PDFs       │
│ • projects   │   │ • Section detection │  │ • assets     │
│ • papers     │   │ • Chunking          │  └──────────────┘
│ • sections   │   │ • Embeddings        │
│ • chunks     │   │ • Vector retrieval  │
│ • pgvector   │   │ • RAG + analysis    │
│ • analyses   │   │ • AI router         │
└──────────────┘   └──────────┬──────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
        ┌──────────┐   ┌──────────┐   ┌────────────┐
        │  Gemini  │   │   Groq   │   │ OpenRouter │
        │ primary  │   │ primary  │   │  fallback  │
        └──────────┘   └──────────┘   └────────────┘

External: Free Academic APIs (metadata, discovery, references)
```

### Responsibility boundary

| Spring Boot owns | FastAPI owns |
|---|---|
| Authentication verification | PDF text extraction |
| Authorization and ownership checks | Text cleaning and section detection |
| Projects, papers, metadata CRUD | Chunking |
| Upload validation and R2 writes | Embedding generation |
| Academic API integration | Vector retrieval (pgvector) |
| Job creation and status tracking | Prompt construction |
| Analysis history and caching | LLM provider calls and routing |
| All request authorization | Structured output validation |

**Rule:** business logic lives in Spring Boot only. FastAPI receives an already-authorized request with explicit IDs and returns structured results. It never decides who may see what.

---

# 8. Core Features

These are **mandatory**. The project is not complete without them.

## 8.1 Authentication & User Management

- Sign in with **Google**
- Sign in with **GitHub**
- Session management and logout
- Protected frontend routes and protected APIs
- Complete per-user data isolation

## 8.2 Research Projects

- Create, rename, and delete research projects
- Each project has a name, description, and research topic
- Papers are organized under projects
- A project dashboard shows paper count, processing status, and recent activity

## 8.3 Paper Upload & Library

- Upload PDF (single or multiple)
- File type and size validation before storage
- Library view with title, authors, year, venue, tags, status
- Search, filter (year, tag, project, status), and sort
- Paper detail view with metadata, extracted sections, and original PDF access
- Delete paper (removes database rows and R2 objects)

## 8.4 Automated PDF Processing

```text
Upload → Validate → Store in R2 → Queue job
   → Extract text → Clean → Detect sections → Chunk
   → Generate embeddings → Store in pgvector → Mark complete
```

- Text extraction with PyMuPDF
- Cleaning: headers, footers, page numbers, hyphenation, ligature artifacts
- **Section detection**: Abstract, Introduction, Related Work, Methodology, Results, Discussion, Limitations, Conclusion, Future Work, References
- Section-aware chunking with configurable size and overlap
- Metadata extraction: title, authors, year, DOI where recoverable
- Live processing status visible in the UI

## 8.5 Semantic Search

- Natural-language search over the user's own papers
- Query embedding → `pgvector` cosine similarity → ranked chunks
- Results show the matching text, its section, and the source paper
- Combinable with metadata filters (year, project, tag)
- Always scoped to the authenticated user

## 8.6 AI Paper Summary

Structured summary containing:
- Research problem
- Approach / methodology
- Key findings
- Limitations
- One-line takeaway

Generated once, stored, and reused. Regeneration is explicit and user-initiated.

## 8.7 RAG-Based Q&A

- Chat interface scoped to a single paper or to an entire project
- Retrieval → context construction → LLM → grounded answer
- Every answer displays its source chunks (paper + section), expandable inline
- When retrieval finds insufficient evidence, the system says so instead of guessing

## 8.8 Paper Comparison

Compare 2–3 papers across:
- Research problem · Objectives · Methodology · Dataset · Models/algorithms · Results · Contributions · Limitations · Future work

Rendered as an aligned comparison table. Dimensions a paper does not cover are marked **Not reported** — never inferred.

## 8.9 Academic Paper Search

- Search free academic APIs by topic, title, or author
- Display title, authors, abstract, year, venue, DOI, open-access link
- Import metadata into a project
- Attach a PDF later to an imported metadata-only record

## 8.10 Analysis History

- Every generated analysis is stored with its inputs, evidence references, and model used
- History view: re-open past results, filter by type, delete
- Prevents paying twice for the same question

---

# 9. Advanced Features

These raise the project from "good" to **resume-worthy**. All are implementable by a fresher and explainable in an interview.

## 9.1 Section-Weighted Retrieval

The differentiator of the project.

Because every chunk stores its `section_type`, retrieval can be biased toward the sections that matter for a given task:

| Task | Section weighting |
|---|---|
| Research-gap analysis | Limitations, Future Work, Discussion |
| Methodology comparison | Methodology, Results |
| Novelty check | Abstract, Introduction, Contributions |
| General Q&A | Unweighted |

Implemented as a SQL boost on the similarity score:

```sql
SELECT c.id, c.content, c.section_type,
       (1 - (c.embedding <=> :query_embedding))
         * CASE WHEN c.section_type = ANY(:boost_sections) THEN 1.25 ELSE 1.0 END
         AS score
FROM paper_chunks c
JOIN papers p ON p.id = c.paper_id
WHERE p.user_id = :user_id AND p.project_id = :project_id
ORDER BY score DESC
LIMIT :top_k;
```

**Interview value:** shows understanding that retrieval quality, not model choice, determines RAG output quality.

## 9.2 Research Gap Identification

See [Section 14](#14-research-gap-identification).

## 9.3 Novelty Assessment

See [Section 15](#15-novelty-assessment). This is the feature the project is named after and the strongest single talking point.

## 9.4 Two-Tier Model Routing with Automatic Fallback

See [Section 12](#12-ai-model-strategy). Demonstrates cost awareness, rate-limit handling, and reliability engineering.

## 9.5 Multi-Paper Literature Synthesis

Generate a structured literature review draft across all papers in a project:
- Overview of the area
- Major themes with supporting papers
- Methodological approaches observed
- Datasets and evaluation practices
- Points of agreement
- Contradictions between papers
- Reported limitations
- Reference list of papers actually used

Papers that contributed no retrieved evidence are not cited.

## 9.6 Duplicate Paper Detection

On upload, embed the abstract (or first N chunks) and compare against existing papers in the user's library. If similarity exceeds a threshold, warn before completing the import.

Cheap to implement (the vector index already exists), immediately useful, and a good demo moment.

## 9.7 Analysis Result Caching

Cache key = SHA-256 of `(analysis_type + model_id + sorted evidence chunk IDs + normalized query)`.

If a matching result exists, return it and skip the LLM call entirely. Directly extends free-tier lifespan and demonstrates real cost engineering.

## 9.8 Research Analytics Dashboard

Simple, high-value charts using stored data only — no extra AI calls:

- Papers per publication year (trend)
- Top keywords/concepts across the project
- Concept frequency over time (which topics are rising)
- Methodology distribution
- Library growth and processing success rate

## 9.9 Export

Export a literature review or comparison to:
- **Markdown**
- **PDF**
- **BibTeX** (reference list for the papers used)

Practical, demo-friendly, and genuinely useful to the target user.

## 9.10 Reading Status & Tagging

- Per-paper status: `To Read` → `Reading` → `Read`
- User-defined tags, filterable in the library
- Personal notes on a paper, stored and searchable

Small features, but they turn a demo into something a user would actually keep using.

---

# 10. Optional / Stretch Features

Build these **only after Core and Advanced features are complete and deployed.** None are required for the project to be strong.

| Feature | Description | Effort | Value |
|---|---|---|---|
| **Dual-model verification** | For novelty assessment only, run Gemini and Groq on the same evidence and surface agreements and disagreements | Medium | High — strong interview story |
| **Simple citation list** | Show a paper's references and cited-by count as a flat list from the academic API | Low | Medium |
| **Figure & table extraction** | Extract images/tables from PDFs into R2 and link to the paper | Medium | Medium |
| **Hybrid search** | Combine PostgreSQL full-text search with vector similarity and merge rankings | Medium | High — measurable retrieval improvement |
| **Chunk reranking** | Re-score top-30 retrieved chunks with a cheap LLM before context construction | Medium | Medium |
| **Saved searches & alerts** | Save a query; periodically check academic APIs for new matching papers | Medium | Medium |
| **PDF viewer with highlight jump** | Click a citation in an answer to open the PDF at the source page | High | High demo value |
| **Dark mode & keyboard shortcuts** | Polish for daily use | Low | Low |

### Note on dual-model verification

If implemented, keep it **scoped to novelty assessment only**. It doubles token consumption and latency, so it must be a deliberate choice for the single highest-value task — never a default for ordinary requests. Justifying that boundary is itself a good interview answer.

---

# 11. AI Features

| Feature | Input | AI task | Output |
|---|---|---|---|
| Paper summary | Paper chunks | Summarization | Structured summary (problem, method, findings, limitations, takeaway) |
| Section analysis | One section's chunks | Extraction | Key points of that section |
| Concept extraction | Paper chunks | Structured extraction | Keywords, methods, datasets, metrics |
| Metadata cleanup | Raw extracted header text | Light extraction | Clean title, authors, year |
| Q&A | Query + retrieved chunks | Grounded generation | Answer + cited evidence |
| Paper comparison | Aligned chunks from N papers | Comparative reasoning | Structured comparison table |
| Literature synthesis | Project-wide chunks | Multi-document synthesis | Structured review draft |
| Research-gap analysis | Limitation/future-work chunks | Pattern reasoning | Gap statements + evidence |
| Novelty assessment | Idea + similar-paper chunks | Comparative reasoning | Overlap, differences, confidence |

## 11.1 Structured output contract

Every AI call requests **JSON output validated with Pydantic**. Free-text responses are not accepted into the database.

Example — Q&A response schema:

```json
{
  "answer": "string",
  "claims": [
    {
      "statement": "string",
      "paper_id": "uuid",
      "section": "methodology",
      "chunk_id": "uuid"
    }
  ],
  "evidence_sufficient": true,
  "confidence": "high | medium | low",
  "limitations": "string"
}
```

Rules:
- Validation failure → one retry with a repair instruction → then fail cleanly.
- Claims whose `chunk_id` was not in the retrieved context are **dropped before rendering**.
- `evidence_sufficient: false` renders as an explicit "not enough evidence in your library" message rather than a generated answer.

This contract is what makes "evidence-grounded" a real property of the system instead of a claim in a README.

---

# 12. AI Model Strategy

## 12.1 Providers

| Role | Provider | Used for |
|---|---|---|
| Primary | **Google Gemini** | Long-context analysis, synthesis, novelty reasoning |
| Primary | **Groq** | Fast inference, high-volume extraction, independent reasoning |
| Fallback | **OpenRouter** | Used only when both primaries fail |

## 12.2 Two-tier routing

Deliberately simple: **two tiers, chosen by task type.** No learned or adaptive routing.

### Fast tier — cheap, high volume

Metadata cleanup · keyword extraction · classification · simple structured extraction · short summaries · formatting

→ `GEMINI_FAST_MODEL` or `GROQ_FAST_MODEL`

### Strong tier — reasoning-heavy

Section analysis · full paper summaries · Q&A · paper comparison · literature synthesis · research-gap analysis · novelty assessment

→ `GEMINI_MODEL` or `GROQ_MODEL`

The mapping is a static configuration table in the FastAPI router:

```python
TASK_TIER = {
    "metadata_cleanup":   "fast",
    "keyword_extraction": "fast",
    "classification":     "fast",
    "summary":            "strong",
    "qa":                 "strong",
    "comparison":         "strong",
    "research_gap":       "strong",
    "novelty":            "strong",
}
```

## 12.3 Model configuration

Model IDs are **environment variables**, never hard-coded, so a deprecated model never breaks the application.

```bash
GEMINI_MODEL=gemini-3.8-flash
GEMINI_FAST_MODEL=gemini-3.5-flash-lite

GROQ_MODEL=openai/gpt-oss-120b
GROQ_FAST_MODEL=openai/gpt-oss-20b

OPENROUTER_MODEL=openai/gpt-oss-120b:free
```

> **Verify before deploying.** Provider catalogues change frequently. Confirm current model IDs and free-tier availability in the provider documentation, and update the environment variables — not the code. Note that free-tier limits are usually bound by **tokens per day** rather than requests per day, which matters for long research prompts.

## 12.4 Fallback chain

```text
Selected primary provider
        │  timeout / 5xx / rate limit
        ▼
Other primary provider
        │  failure
        ▼
OpenRouter (fallback)
        │  failure
        ▼
Clear retry message to the user
```

Rules:
- Every call has a timeout (default 60s).
- Maximum 2 retries with exponential backoff.
- On HTTP 429, the provider is marked unavailable for a cooldown window and skipped.
- Failover events are logged with provider, task, and reason — never with credentials or user document content.
- The user never sees provider names, model IDs, stack traces, or raw provider errors.

**Interview value:** this is real reliability engineering — timeouts, bounded retries, circuit-breaker-style cooldown, and graceful degradation — implemented at a scale a fresher can fully explain.

---

# 13. RAG Pipeline

## 13.1 Indexing (once per paper)

```text
PDF in R2
   ↓
Text extraction (PyMuPDF)
   ↓
Cleaning (headers, footers, hyphenation)
   ↓
Section detection
   ↓
Section-aware chunking (512 tokens, 64 overlap)
   ↓
Sentence Transformers → 384-dim vectors
   ↓
paper_chunks (content + section_type + embedding)
```

## 13.2 Retrieval & generation (per query)

```text
User query
   ↓
Query embedding
   ↓
pgvector similarity search
   + user_id / project_id filter (mandatory)
   + section weighting (task-dependent)
   ↓
Top-k chunks
   ↓
Deduplicate · order by paper and section
   ↓
Build context with provenance headers
   ↓
Fit to model context budget
   ↓
LLM (fast or strong tier)
   ↓
Structured JSON output
   ↓
Pydantic validation
   ↓
Drop claims not backed by retrieved chunks
   ↓
Evidence-grounded response + citations
```

## 13.3 Context construction rules

1. Retrieve top-k (default 8; configurable).
2. Filter by ownership — **always**, at the SQL level, never in application code alone.
3. Deduplicate near-identical chunks.
4. Order chunks by paper, then by section order, for coherence.
5. Prefix each chunk with `[Paper: <title> | Section: <section>]`.
6. Truncate lowest-scoring chunks first if the context budget is exceeded.
7. If the top score falls below a relevance threshold, return `evidence_sufficient: false` instead of calling the LLM.

Step 7 matters: **the best RAG behaviour is often refusing to answer.**

---

# 14. Research Gap Identification

## 14.1 Approach

Research gaps are not invented by the model — they are **derived from patterns in what authors themselves wrote**, primarily in Limitations, Discussion, and Future Work sections.

## 14.2 Signals

- Limitations repeated across multiple papers
- Unresolved problems explicitly stated by authors
- Contradictory results between studies
- Methodological weaknesses acknowledged by authors
- Missing datasets or evaluation approaches
- Future-work recommendations that no other paper in the corpus addresses

## 14.3 Pipeline

```text
Project papers
      ↓
Section-weighted retrieval (Limitations, Future Work, Discussion)
      ↓
Evidence chunks grouped by paper
      ↓
Strong-tier LLM — pattern analysis
      ↓
Structured gap output
      ↓
Validation: every gap must cite ≥ 1 real chunk
      ↓
Potential Research Gaps
```

## 14.4 Output

```json
{
  "gaps": [
    {
      "statement": "string",
      "supporting_papers": ["uuid"],
      "evidence": [{"chunk_id": "uuid", "section": "limitations", "quote": "string"}],
      "why_underexplored": "string",
      "confidence": "high | medium | low"
    }
  ],
  "corpus_size": 24,
  "limitations": "Based only on the 24 papers in this project."
}
```

## 14.5 Honesty rules

- Always labelled **"Potential Research Gap"** in the UI.
- A gap with no supporting evidence is discarded, not displayed.
- The corpus size is always shown — a gap found across 5 papers is not the same as one found across 50.
- The system never claims a gap is a verified scientific discovery.

---

# 15. Novelty Assessment

The signature feature of the project.

## 15.1 What it actually does

The user describes a research idea. RLNA embeds it, retrieves the most similar work from the user's library and from free academic APIs, and produces a structured comparison between the idea and what already exists.

## 15.2 Pipeline

```text
Research idea (user text)
      ↓
Idea embedding
      ↓
Similarity search: user's library + academic API results
      ↓
Top similar papers → retrieve their relevant chunks
      ↓
Strong-tier LLM comparative analysis
      ↓
(Optional) Dual-model verification — Gemini + Groq
      ↓
Evidence validation
      ↓
Structured Novelty Assessment
```

## 15.3 Output

| Field | Description |
|---|---|
| Similar existing work | Ranked list with similarity scores |
| Areas of overlap | Where the idea matches published work |
| Existing methodologies | Approaches already used for this problem |
| Differences | What appears different in the user's idea |
| Potentially novel aspects | Explicitly framed as *potentially* |
| Supporting evidence | Paper + section references for every point |
| Confidence | High / medium / low, driven by corpus size and retrieval scores |
| Limitations | Always populated, always shown |

## 15.4 Honesty requirements — non-negotiable

The UI must display, alongside every result:

> Based on **N papers** retrieved from your library and academic search. This is not a proof of novelty — the absence of similar work in this corpus does not mean none exists.

- Confidence is automatically **lowered** when the corpus is small or retrieval scores are weak.
- The system never outputs a single "novelty score" percentage — that would imply a precision it does not have.
- If dual-model verification is enabled and the two models disagree, the disagreement is **shown**, not averaged away.

**Interview value:** this demonstrates something rarer than technical skill — knowing the limits of your own system and designing the interface around them.

---

# 16. User Workflows

## 16.1 First-time user

```text
Land on homepage
   → Sign in with Google/GitHub
   → Create first project ("Federated Learning for Healthcare")
   → Upload 5 PDFs
   → Watch processing status (extract → chunk → embed)
   → Papers appear as "Ready"
   → Dashboard now shows 5 papers, year distribution, top concepts
```

## 16.2 Understanding a new area

```text
Open project
   → Semantic search: "how do they handle non-IID data?"
   → Review top chunks with source labels
   → Open the most relevant paper
   → Read AI summary
   → Ask follow-up questions in the chat panel
   → Each answer shows its source chunks
   → Save the useful answers to analysis history
```

## 16.3 Comparing approaches

```text
Library → select Paper A and Paper B → Compare
   → System retrieves aligned Methodology and Results chunks
   → Structured comparison table renders
   → Dimensions not covered show "Not reported"
   → Export comparison to Markdown
```

## 16.4 Finding research gaps

```text
Project → "Find Research Gaps"
   → Section-weighted retrieval (Limitations, Future Work)
   → Gap list renders with supporting quotes
   → Expand a gap to see which papers support it
   → Save to project
```

## 16.5 Checking novelty

```text
Project → "Novelty Check"
   → Enter idea: "Using federated learning with differential privacy
      for ECG arrhythmia detection on edge devices"
   → Retrieval across library + academic APIs
   → Result: similar work, overlap, differences, potentially novel aspects
   → Confidence: Medium — based on 18 papers
   → Limitations banner always visible
   → Save assessment; export to Markdown
```

## 16.6 Building a literature review

```text
Project with 20+ processed papers
   → "Generate Literature Review"
   → Project-wide retrieval and synthesis
   → Structured draft: themes, methods, agreements, contradictions, gaps
   → Every claim references its source papers
   → Export to Markdown / PDF / BibTeX
```

---

# 17. Functional Requirements

| ID | Requirement | Priority |
|---|---|---|
| FR-01 | User can sign in with Google | Must |
| FR-02 | User can sign in with GitHub | Must |
| FR-03 | User can log out; session is invalidated | Must |
| FR-04 | User can create, rename, and delete research projects | Must |
| FR-05 | User can upload one or more PDF files to a project | Must |
| FR-06 | System validates file type and size before storage | Must |
| FR-07 | System stores the original PDF in Cloudflare R2 | Must |
| FR-08 | System extracts text from an uploaded PDF | Must |
| FR-09 | System detects standard paper sections | Must |
| FR-10 | System splits text into section-aware chunks | Must |
| FR-11 | System generates and stores embeddings for each chunk | Must |
| FR-12 | System exposes live processing status per paper | Must |
| FR-13 | User can perform semantic search over their own papers | Must |
| FR-14 | Search results display source paper and section | Must |
| FR-15 | User can generate an AI summary for a paper | Must |
| FR-16 | User can ask questions about a paper or project via RAG | Must |
| FR-17 | Every AI answer displays its supporting evidence | Must |
| FR-18 | System refuses to answer when retrieved evidence is insufficient | Must |
| FR-19 | User can compare 2–3 papers across defined dimensions | Must |
| FR-20 | User can search academic papers through a free external API | Must |
| FR-21 | User can view, re-open, and delete analysis history | Must |
| FR-22 | User cannot access another user's data under any circumstance | Must |
| FR-23 | System applies section weighting to task-specific retrieval | Should |
| FR-24 | System identifies potential research gaps with cited evidence | Should |
| FR-25 | System performs novelty assessment with confidence and limitations | Should |
| FR-26 | System routes tasks between fast and strong model tiers | Should |
| FR-27 | System falls back across providers on failure or rate limit | Should |
| FR-28 | System reuses cached analysis results for identical requests | Should |
| FR-29 | System warns when a duplicate paper is uploaded | Should |
| FR-30 | System generates a multi-paper literature review draft | Should |
| FR-31 | Dashboard displays research analytics charts | Should |
| FR-32 | User can export results to Markdown, PDF, or BibTeX | Should |
| FR-33 | User can tag papers and set reading status | Could |
| FR-34 | User can add personal notes to a paper | Could |
| FR-35 | System supports dual-model verification for novelty checks | Could |
| FR-36 | System supports hybrid keyword + vector search | Could |

---

# 18. Database Design

**Neon PostgreSQL** is the single database. `pgvector` provides vector search. There is no second database and no separate vector store.

## 18.1 Schema

### `users`
```sql
id                     UUID PRIMARY KEY
email                  VARCHAR(255) UNIQUE NOT NULL
name                   VARCHAR(255)
auth_provider          VARCHAR(50)        -- google | github
auth_provider_user_id  VARCHAR(255)
created_at             TIMESTAMPTZ DEFAULT now()
updated_at             TIMESTAMPTZ DEFAULT now()
```

### `projects`
```sql
id              UUID PRIMARY KEY
user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE
name            VARCHAR(255) NOT NULL
description     TEXT
research_topic  VARCHAR(500)
created_at      TIMESTAMPTZ DEFAULT now()
updated_at      TIMESTAMPTZ DEFAULT now()
```

### `papers`
```sql
id                UUID PRIMARY KEY
user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE
project_id        UUID REFERENCES projects(id) ON DELETE SET NULL
title             VARCHAR(1000)
authors           TEXT[]
abstract          TEXT
publication_year  INTEGER
venue             VARCHAR(500)
doi               VARCHAR(255)
external_id       VARCHAR(255)      -- academic API identifier
r2_object_key     VARCHAR(500)      -- pointer to the PDF in R2
file_size         BIGINT
processing_status VARCHAR(50)       -- queued|processing|completed|failed
processing_error  TEXT
summary           JSONB
reading_status    VARCHAR(20)       -- to_read|reading|read
tags              TEXT[]
notes             TEXT
created_at        TIMESTAMPTZ DEFAULT now()
updated_at        TIMESTAMPTZ DEFAULT now()
```

### `paper_sections`
```sql
id            UUID PRIMARY KEY
paper_id      UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE
section_type  VARCHAR(50)   -- abstract|introduction|related_work|methodology|
                            -- results|discussion|limitations|conclusion|future_work
heading       VARCHAR(500)
content       TEXT
order_index   INTEGER
```

### `paper_chunks`
```sql
id               UUID PRIMARY KEY
paper_id         UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE
section_id       UUID REFERENCES paper_sections(id) ON DELETE CASCADE
section_type     VARCHAR(50)      -- denormalized for fast filtering/weighting
chunk_index      INTEGER
content          TEXT NOT NULL
token_count      INTEGER
embedding        VECTOR(384)      -- pgvector
embedding_model  VARCHAR(100)
created_at       TIMESTAMPTZ DEFAULT now()
```

### `concepts`
```sql
id            UUID PRIMARY KEY
paper_id      UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE
concept       VARCHAR(255)
concept_type  VARCHAR(50)   -- keyword|method|dataset|metric|domain
confidence    REAL
```

### `analysis_results`
```sql
id             UUID PRIMARY KEY
user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE
project_id     UUID REFERENCES projects(id) ON DELETE CASCADE
paper_id       UUID REFERENCES papers(id) ON DELETE CASCADE
analysis_type  VARCHAR(50)   -- summary|qa|comparison|research_gap|novelty|review
input_query    TEXT
result         JSONB NOT NULL
evidence_refs  JSONB          -- chunk and paper IDs used
provider_used  VARCHAR(50)
model_id       VARCHAR(100)
confidence     VARCHAR(20)
cache_key      VARCHAR(64)    -- SHA-256 fingerprint
created_at     TIMESTAMPTZ DEFAULT now()
```

### `processing_jobs`
```sql
id            UUID PRIMARY KEY
paper_id      UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE
job_type      VARCHAR(50)   -- pdf_processing|embedding|analysis
status        VARCHAR(50)   -- queued|running|completed|failed
current_step  VARCHAR(100)
progress      INTEGER       -- 0-100
error_message TEXT
attempts      INTEGER DEFAULT 0
started_at    TIMESTAMPTZ
completed_at  TIMESTAMPTZ
```

### `paper_assets` *(optional feature)*
```sql
id             UUID PRIMARY KEY
paper_id       UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE
asset_type     VARCHAR(20)   -- figure|table
r2_object_key  VARCHAR(500)
page_number    INTEGER
```

## 18.2 Indexes

```sql
CREATE INDEX idx_papers_user        ON papers(user_id);
CREATE INDEX idx_papers_project     ON papers(project_id);
CREATE INDEX idx_papers_status      ON papers(processing_status);
CREATE INDEX idx_chunks_paper       ON paper_chunks(paper_id);
CREATE INDEX idx_chunks_section     ON paper_chunks(section_type);
CREATE INDEX idx_analysis_cache     ON analysis_results(cache_key);
CREATE INDEX idx_concepts_paper     ON concepts(paper_id);

-- Vector index (create once the table has meaningful volume)
CREATE INDEX idx_chunks_embedding
  ON paper_chunks USING hnsw (embedding vector_cosine_ops);
```

## 18.3 Data rules

- Every user-owned row is reachable to a `user_id`, and every query filters on it.
- Deleting a paper cascades to sections, chunks, concepts, jobs, and assets, and deletes its R2 objects.
- No PDF binary is ever stored in PostgreSQL — only the object key.
- `embedding_model` is stored per chunk so a model change is detectable and re-embedding can be targeted.

---

# 19. Vector Search Design

## 19.1 Embedding model

| Property | Value |
|---|---|
| Model | `all-MiniLM-L6-v2` (Sentence Transformers) |
| Dimension | 384 |
| Runs | Locally inside the FastAPI container |
| Cost | Zero per call |
| Why | Small, fast, CPU-friendly, and strong enough for retrieval; keeps the project free-tier viable |

## 19.2 Chunking strategy

| Parameter | Default | Reason |
|---|---|---|
| Chunk size | 512 tokens | Fits the embedding model's window with room for context |
| Overlap | 64 tokens | Prevents losing meaning at chunk boundaries |
| Boundary | Section-aware | A chunk never spans two sections |
| Minimum | 50 tokens | Fragments below this are merged into the neighbouring chunk |

## 19.3 Similarity search

Cosine distance (`<=>`) with an HNSW index. Ownership filtering is applied in the same SQL statement as the similarity ordering — never as a post-filter in application code.

## 19.4 Why pgvector and not a dedicated vector database

> **Interview answer:** The corpus is thousands of chunks, not millions. `pgvector` handles that comfortably, and keeping vectors in Postgres means I can filter by `user_id`, `project_id`, year, and section in the *same query* as the similarity search. A separate vector database would mean two systems to deploy, two consistency problems, and a cross-system join I would have to do in application code. It would be added complexity with no benefit at this scale.

---

# 20. File Storage Design

## 20.1 Responsibility split

| Cloudflare R2 stores | PostgreSQL stores |
|---|---|
| Original PDF files | Object keys |
| Extracted figures and tables (optional) | File metadata (size, type) |
| Exported documents (optional) | Paper metadata, processing state, analysis results |

## 20.2 Key layout

```text
papers/{user_id}/{paper_id}/original.pdf
papers/{user_id}/{paper_id}/assets/figure-{n}.png
exports/{user_id}/{export_id}.pdf
```

Keys are **server-generated**. User-supplied filenames are never used to build a path.

## 20.3 Access model

- R2 credentials exist only in backend environment variables.
- The frontend never receives R2 credentials.
- PDF access is served through a **short-lived signed URL** (default TTL 300s) issued by Spring Boot after an ownership check.
- FastAPI reads objects with its own server-side credentials, given an object key by Spring Boot.
- Deleting a paper deletes its R2 objects in the same operation as the database rows.

## 20.4 Why R2 and not S3

Free-tier storage allowance with **zero egress fees**, S3-compatible API (so the AWS SDK works unchanged), and no surprise bill from a demo that gets shared. For a student project served over the public internet, egress cost is the risk that matters.

---

# 21. API Design

## 21.1 Spring Boot — public REST API

```http
# Auth
GET    /api/auth/session
POST   /api/auth/logout

# Projects
GET    /api/projects
POST   /api/projects
GET    /api/projects/{id}
PATCH  /api/projects/{id}
DELETE /api/projects/{id}

# Papers
GET    /api/papers?projectId=&status=&year=&tag=&sort=
POST   /api/papers                       # multipart upload
GET    /api/papers/{id}
PATCH  /api/papers/{id}                  # tags, reading status, notes
DELETE /api/papers/{id}
GET    /api/papers/{id}/file             # signed R2 URL
GET    /api/papers/{id}/status           # processing progress
POST   /api/papers/{id}/reprocess

# Search
POST   /api/search/semantic
GET    /api/search/keyword

# Analysis
POST   /api/papers/{id}/summary
POST   /api/papers/{id}/concepts
POST   /api/papers/{id}/question
POST   /api/projects/{id}/question
POST   /api/analysis/compare
POST   /api/analysis/research-gap
POST   /api/analysis/novelty
POST   /api/analysis/literature-review
GET    /api/analysis/history?type=&projectId=
DELETE /api/analysis/{id}

# Academic search
GET    /api/academic/search?q=
POST   /api/academic/import

# Analytics & export
GET    /api/projects/{id}/analytics
POST   /api/export
```

## 21.2 FastAPI — internal service API

```http
POST /internal/process-pdf
POST /internal/embed
POST /internal/retrieve
POST /internal/summarize
POST /internal/extract-concepts
POST /internal/question
POST /internal/compare
POST /internal/research-gap
POST /internal/novelty
POST /internal/literature-review
GET  /health
```

## 21.3 Conventions

- REST, JSON, plural resource nouns.
- Standard status codes: `200`, `201`, `202` (async job accepted), `400`, `401`, `403`, `404`, `409` (duplicate), `422`, `429`, `500`.
- Consistent error envelope:

```json
{
  "error": {
    "code": "PAPER_NOT_FOUND",
    "message": "This paper does not exist or you do not have access to it.",
    "traceId": "a1b2c3d4"
  }
}
```

- Pagination on all list endpoints: `?page=&size=`.
- FastAPI is **not publicly routable**; it accepts only requests carrying the internal service token and never receives a browser session.

---

# 22. Authentication & Authorization

## 22.1 Flow

```text
User clicks "Continue with Google"
        ↓
Neon Auth handles the OAuth flow
        ↓
Frontend receives a session token
        ↓
Token sent with every API request
        ↓
Spring Boot verifies the token server-side
        ↓
user_id extracted from the verified session
        ↓
Every query filtered by that user_id
```

## 22.2 Authorization rules

1. The `user_id` used for data access comes **only** from the verified session — never from a request body, path, or query parameter.
2. Every repository method that touches user data takes `user_id` as a parameter. There is no "fetch by ID" without an ownership check.
3. A request for another user's resource returns `404`, not `403` — the system does not confirm that someone else's resource exists.
4. FastAPI trusts Spring Boot. It receives already-authorized IDs and performs no authorization of its own — which is exactly why it must never be publicly reachable.
5. Frontend route protection is a UX convenience, not a security boundary.

## 22.3 Scope

- Providers: **Google and GitHub only**.
- No admin panel, no role hierarchy, no team permissions, no organization model.

---

# 23. Frontend Design

## 23.1 Pages

| Page | Contents |
|---|---|
| Landing | Product explanation, sign-in |
| Dashboard | Projects, recent papers, analytics charts, quick actions |
| Project view | Paper list, filters, project-level analysis actions |
| Paper detail | Metadata, sections, summary, concepts, notes, PDF access |
| Chat / Q&A | Question input, answers with expandable evidence |
| Comparison | Paper selectors, comparison table, export |
| Research gaps | Gap cards with supporting quotes and corpus size |
| Novelty check | Idea input, results, confidence, limitations banner |
| Search | Semantic and keyword search with filters |
| History | Past analyses with re-open and delete |

## 23.2 UI requirements

- Responsive from laptop to tablet.
- **Honest loading states**: processing shows the current step (extracting → chunking → embedding), not a generic spinner.
- **Useful empty states**: a new project explains what to do next, not "No data".
- **Explicit error states** with a retry action.
- AI-generated content is visually distinct from extracted paper text.
- Evidence is always one click away from any AI claim.
- Confidence and limitations are shown as part of the result — never hidden behind a tooltip.
- Accessible contrast, visible focus states, and full keyboard navigation on forms.

## 23.3 State management

React Context + hooks is sufficient. No Redux — the state is not complex enough to justify it, and being able to explain *why* a library was not added is worth more in an interview than adding it.

---

# 24. Background Processing

PDF processing takes 10–60 seconds. It must never block an HTTP request.

## 24.1 Design

```text
POST /api/papers  →  Validate → Store in R2 → Create paper (queued)
                  →  Create processing_job
                  →  Return 202 Accepted + paperId
                          ↓
              Spring Boot @Async worker picks up the job
                          ↓
              Calls FastAPI /internal/process-pdf
                          ↓
              FastAPI: extract → clean → sections → chunk → embed
                          ↓
              Job progress updated at each step
                          ↓
              Frontend polls GET /api/papers/{id}/status
                          ↓
              Status: completed → UI refreshes
```

## 24.2 Implementation

- Spring Boot `@Async` with a bounded `ThreadPoolTaskExecutor` (small pool, bounded queue).
- Job state persisted in `processing_jobs` so progress survives a restart.
- Frontend polls every 2–3 seconds while any paper is processing, then stops.
- Batch upload processes papers sequentially with limited concurrency to respect embedding and API limits.
- Failed jobs are retryable from the UI; the stored PDF is never lost on failure.

## 24.3 Why not a message queue

> **Interview answer:** RabbitMQ or Kafka would be the right answer at scale, but for a single-instance deployment on a free tier they add a service to run, monitor, and pay for — with no benefit at this volume. A bounded thread pool with persisted job state gives me durability, visible progress, and retry. If the workload grew to multiple instances, the migration path is clear: replace the executor with a queue consumer and leave the job table as it is.

That answer — knowing the right tool *and* why you did not use it — is stronger than having used it.

---

# 25. Caching & Cost Control

Free-tier LLM quotas are the real constraint of this project. Caching is a feature, not an optimization.

## 25.1 What is cached

| Item | Strategy | Impact |
|---|---|---|
| Paper summaries | Generated once, stored in `papers.summary`, reused forever | Very high |
| Analysis results | Cached by fingerprint in `analysis_results.cache_key` | Very high |
| Embeddings | Computed once per chunk, never recomputed | Very high |
| Academic API responses | Short-TTL in-memory cache | Medium |
| Concept extraction | Stored per paper | Medium |

## 25.2 Cache key

```text
SHA-256( analysis_type | model_id | normalized_query | sorted(chunk_ids) )
```

If the same question is asked over the same evidence with the same model, the stored result is returned and **no API call is made**. Changing any input produces a different key.

## 25.3 Cost control rules

- Send retrieved chunks only — never a whole paper.
- Use the fast tier for extraction and classification.
- Enforce a hard cap on context size per request.
- Respect provider rate-limit headers and route away from throttled providers.
- Bounded retries with backoff — never retry into a rate limit.
- Embeddings run locally: **zero cost per search**, which is what makes unlimited semantic search viable on a free tier.

---

# 26. Security Requirements

## 26.1 Secrets

- All credentials in environment variables; nothing hard-coded.
- `.env` git-ignored; `.env.example` documents names without values.
- Required variables validated at startup — the service fails fast rather than failing mid-request.
- Database, R2, and LLM keys never reach the browser.

## 26.2 Authentication & authorization

- Server-side session verification on every protected endpoint.
- Query-level user isolation (Section 22.2).
- Ownership checked before every read, download, and delete.

## 26.3 Input & file handling

- Validation on every endpoint (Spring Validation + Pydantic).
- PDF-only uploads, verified by content type **and** magic bytes — not by file extension.
- Maximum file size enforced (default 25 MB).
- Server-generated object keys; user filenames never used in paths.
- Defensive PDF parsing: bounded time and memory, isolated failures, rejection of decompression-bomb characteristics.

## 26.4 Prompt injection

Uploaded PDFs are **untrusted input**. A paper can contain text like *"ignore previous instructions."*

Mitigations:
- Retrieved chunks are inserted into prompts inside explicit delimiters, labelled as data.
- The system prompt states that instructions found inside retrieved content must be ignored.
- Structured JSON output is required and schema-validated — a hijacked response fails validation instead of rendering.
- Model output is escaped before rendering, never executed, and never used to build SQL.

## 26.5 Transport & platform

- HTTPS everywhere.
- CORS restricted to the deployed frontend origin.
- FastAPI reachable only via the internal service token.
- Rate limiting on upload, search, and analysis endpoints.
- SQL injection prevented by JPA parameter binding and parameterized queries in Python.

## 26.6 Logging

- No credentials, tokens, or full document contents in logs.
- Errors logged with a trace ID that the user can quote to support; internals never returned to the client.

---

# 27. Error Handling

| Scenario | Handling | User sees |
|---|---|---|
| Invalid file type | Reject before storage | "Only PDF files are supported." |
| File too large | Reject before storage | "Maximum file size is 25 MB." |
| Corrupt PDF | Mark job failed, keep the file | "We couldn't read this PDF. Try re-uploading." |
| No extractable text (scanned) | Mark failed with a specific reason | "This looks like a scanned PDF. Text extraction isn't supported yet." |
| Duplicate paper | Return `409` with the existing paper | "This paper is already in your library." |
| Embedding failure | Retry with backoff; paper stays metadata-searchable | "Indexing failed. Retry?" |
| Insufficient evidence | Skip the LLM call entirely | "Not enough in your library to answer this. Try adding related papers." |
| LLM provider failure | Fail over per Section 12.4 | Nothing — the fallback is invisible |
| All providers failed | Clean error, no internals | "AI analysis is temporarily unavailable. Please try again shortly." |
| Rate limited | Cooldown and retry guidance | "You've hit the analysis limit. Try again in a few minutes." |
| Academic API down | Degrade to local library only | "External search is unavailable; showing your library." |
| Unauthorized access attempt | `404`, logged | "Not found." |

### Principles

1. Partial failure never destroys completed work.
2. Every error message states what happened **and** what to do next.
3. Internal details (providers, models, stack traces, infrastructure) are never exposed.
4. Long operations always reach a terminal state — never an infinite spinner.
5. Retries are bounded, backed off, and idempotent where they touch storage.

---

# 28. Non-Functional Requirements

| Category | Requirement |
|---|---|
| **Performance** | Semantic search returns in < 2s for a 5,000-chunk library; page loads < 3s; PDF processing 10–60s depending on length |
| **Scalability** | Comfortable up to ~500 papers and ~50,000 chunks per user — well beyond realistic single-user needs |
| **Availability** | Best-effort on free tiers; cold starts on Render are expected and handled with honest loading states |
| **Reliability** | No single AI provider is a hard dependency; processing state survives restarts |
| **Security** | Complete per-user isolation; no secrets in code or client |
| **Usability** | A new user reaches their first grounded answer within 5 minutes of signing in |
| **Maintainability** | Clear service boundary; models and parameters are configuration, not code |
| **Portability** | Both backends run identically via Docker locally and on Render |
| **Cost** | Runs entirely on free tiers at student-scale usage |
| **Honesty** | Confidence and limitations shown on every AI output; the system refuses rather than fabricates |

---

# 29. Testing Strategy

Testing is included because "I wrote tests" is a differentiator in a fresher portfolio — most projects have none.

## 29.1 Spring Boot

| Type | Tool | Coverage |
|---|---|---|
| Unit | JUnit 5 + Mockito | Service logic, validation, cache key generation, authorization checks |
| Integration | Spring Boot Test + Testcontainers (or H2) | Repository queries, transaction behaviour |
| API | MockMvc | Endpoint contracts, status codes, error envelope |
| Security | Spring Security Test | **Cross-user access must return 404** — the single most important test in the project |

## 29.2 FastAPI

| Type | Tool | Coverage |
|---|---|---|
| Unit | pytest | Chunking boundaries, section detection, cleaning, cache fingerprint |
| Schema | pytest + Pydantic | Structured output validation, malformed-response repair path |
| Integration | pytest + httpx | Endpoint behaviour with mocked providers |
| Mocked providers | `unittest.mock` / `respx` | Fallback chain, timeout, 429 cooldown — **no real API calls in tests** |

## 29.3 Frontend

| Type | Tool | Coverage |
|---|---|---|
| Component | React Testing Library | Rendering, evidence expansion, empty and error states |
| Integration | RTL + MSW | API interaction with mocked responses |

## 29.4 Manual & API testing

- Postman / Thunder Client collection covering every endpoint, committed to the repo.
- A documented end-to-end manual pass: sign in → create project → upload → process → search → ask → compare → gap → novelty → export.

## 29.5 Priority test cases

1. A user cannot read, modify, or delete another user's paper.
2. Uploading a non-PDF is rejected before anything is written to R2.
3. A corrupt PDF fails the job without losing the stored file.
4. Chunking never spans a section boundary.
5. Semantic search results never include another user's chunks.
6. Insufficient evidence returns a refusal, not a generated answer.
7. Claims referencing chunks outside the retrieved context are dropped.
8. Provider failure triggers fallback and the user sees no internal error.
9. An identical analysis request hits the cache and makes no API call.
10. Deleting a paper removes its chunks, jobs, and R2 objects.

---

# 30. Environment Configuration

```bash
# ---------- Database ----------
DATABASE_URL=postgresql://user:password@host.neon.tech/rlna?sslmode=require

# ---------- Neon Auth ----------
NEON_AUTH_PROJECT_ID=
NEON_AUTH_PUBLISHABLE_KEY=        # frontend-safe
NEON_AUTH_SECRET_KEY=             # backend only

# ---------- Cloudflare R2 ----------
R2_ACCOUNT_ID=
R2_ACCESS_KEY_ID=
R2_SECRET_ACCESS_KEY=
R2_BUCKET_NAME=rlna-papers
R2_ENDPOINT=
R2_SIGNED_URL_TTL_SECONDS=300

# ---------- LLM providers ----------
GEMINI_API_KEY=
GEMINI_MODEL=gemini-3.8-flash
GEMINI_FAST_MODEL=gemini-3.5-flash-lite

GROQ_API_KEY=
GROQ_MODEL=openai/gpt-oss-120b
GROQ_FAST_MODEL=openai/gpt-oss-20b

OPENROUTER_API_KEY=
OPENROUTER_MODEL=openai/gpt-oss-120b:free

# ---------- AI behaviour ----------
AI_REQUEST_TIMEOUT_SECONDS=60
AI_MAX_RETRIES=2
AI_PROVIDER_COOLDOWN_SECONDS=60
AI_CACHE_ENABLED=true
DUAL_MODEL_VERIFICATION=false     # optional; novelty assessment only

# ---------- Embeddings & retrieval ----------
EMBEDDING_MODEL=all-MiniLM-L6-v2
EMBEDDING_DIMENSION=384
CHUNK_SIZE_TOKENS=512
CHUNK_OVERLAP_TOKENS=64
RETRIEVAL_TOP_K=8
RETRIEVAL_MIN_SCORE=0.35
SECTION_BOOST_FACTOR=1.25

# ---------- Academic API ----------
ACADEMIC_API_BASE_URL=
ACADEMIC_API_KEY=
ACADEMIC_API_RATE_LIMIT_PER_MINUTE=60

# ---------- Spring Boot ----------
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=prod
FRONTEND_ORIGIN=https://rlna.vercel.app
FASTAPI_BASE_URL=https://rlna-ai.onrender.com
FASTAPI_INTERNAL_TOKEN=
MAX_UPLOAD_SIZE_MB=25
ASYNC_POOL_SIZE=2

# ---------- FastAPI ----------
PORT=8000
ALLOWED_CALLER_TOKEN=
LOG_LEVEL=info
```

**Rules**
- No secrets committed, ever.
- `.env.example` lists every variable with empty values.
- Both backends validate required variables at startup and refuse to start if any are missing.
- Model IDs are configuration — a deprecated model is fixed by changing an environment variable, not by deploying code.

---

# 31. Deployment

| Component | Platform | Notes |
|---|---|---|
| React frontend | **Vercel** | Auto-deploy from `main` |
| Spring Boot | **Render** | Docker deploy, free instance |
| FastAPI | **Render** | Docker deploy, free instance |
| PostgreSQL | **Neon** | Serverless, `pgvector` enabled |
| Object storage | **Cloudflare R2** | S3-compatible |
| Auth | **Neon Auth** | Google + GitHub |

## 31.1 Containers

Both backends ship with a Dockerfile:
- Spring Boot: multi-stage Maven build → slim JRE runtime image
- FastAPI: Python slim base, with the embedding model pre-downloaded into the image so the first request is not slow

`docker-compose.yml` runs the full stack locally (frontend, both backends, local Postgres with pgvector).

## 31.2 Deployment notes

- Free Render instances sleep when idle; the frontend handles cold starts with an honest loading state rather than a failed request.
- Flyway migrations run at startup; `CREATE EXTENSION IF NOT EXISTS vector` runs in the first migration.
- `/health` endpoints on both backends for platform checks.
- CORS on Spring Boot allows only the deployed Vercel origin.
- The FastAPI service is protected by the internal token and is never called from the browser.

## 31.3 Repository structure

```text
rlna/
├── frontend/            # React + JavaScript
├── backend/             # Spring Boot
├── ai-service/          # FastAPI
├── docs/
│   ├── features.md
│   ├── architecture.md
│   └── api.md
├── docker-compose.yml
├── .env.example
└── README.md
```

---

# 32. MVP Definition

The MVP is the smallest version that is genuinely useful and demonstrable. **Build this first, deploy it, then add advanced features.**

### MVP scope

- [ ] Google + GitHub sign-in
- [ ] Create a project
- [ ] Upload a PDF
- [ ] Store the PDF in R2
- [ ] Extract text and detect sections
- [ ] Chunk and embed
- [ ] Store chunks in pgvector
- [ ] Processing status in the UI
- [ ] Semantic search over the user's papers
- [ ] AI paper summary
- [ ] RAG Q&A with visible evidence
- [ ] Paper library with filters
- [ ] Per-user data isolation
- [ ] Deployed end to end on Vercel + Render + Neon + R2

### Explicitly not in the MVP

Paper comparison · research-gap analysis · novelty assessment · literature synthesis · analytics dashboard · export · model routing (single provider is fine initially) · caching · duplicate detection · academic API search.

> **Why this matters:** a deployed, working MVP is worth more than a half-finished advanced system. Ship the MVP, then layer features on top of a system that already runs.

---

# 33. Implementation Phases

### Phase 1 — Foundation *(Weeks 1–2)*
Project setup · Docker Compose · Neon + pgvector · Spring Boot skeleton · FastAPI skeleton · Neon Auth with Google and GitHub · React app shell with protected routes · database schema and migrations

**Milestone:** a user can sign in and see an empty dashboard.

### Phase 2 — Papers & Storage *(Weeks 3–4)*
Project CRUD · PDF upload with validation · R2 integration · signed URLs · paper library UI · delete with cascade

**Milestone:** a user can upload a PDF and see it in their library.

### Phase 3 — Processing Pipeline *(Weeks 5–6)*
Text extraction · cleaning · section detection · chunking · Sentence Transformers · pgvector storage · async job handling · status polling

**Milestone:** an uploaded paper is fully indexed and its status is visible live.

### Phase 4 — Search & RAG *(Weeks 7–8)*
Semantic search · retrieval with ownership filtering · context construction · Gemini integration · summaries · Q&A with evidence display · structured output validation

**Milestone:** ✅ **MVP complete — deploy it.**

### Phase 5 — Intelligence *(Weeks 9–11)*
Section-weighted retrieval · paper comparison · research-gap identification · novelty assessment · literature synthesis · analysis history

**Milestone:** the features that make the project distinctive are live.

### Phase 6 — Engineering Depth *(Weeks 12–13)*
Two-tier model routing · Groq integration · OpenRouter fallback · analysis caching · duplicate detection · rate-limit handling · academic API search

**Milestone:** the system is cost-efficient and resilient to provider failure.

### Phase 7 — Polish & Ship *(Weeks 14–15)*
Analytics dashboard · export (Markdown / PDF / BibTeX) · tags and reading status · error-state pass · tests · README with architecture diagram · demo video · deployment hardening

**Milestone:** portfolio-ready.

> Timeline assumes part-time work alongside coursework. Phases 1–4 are mandatory; 5–7 are what make it resume-worthy.

---

# 34. Intentionally Not Included

Every item below was considered and **deliberately excluded**. Being able to explain these decisions is worth as much in an interview as the code itself.

| Excluded | Why |
|---|---|
| **Interactive citation network graph** | Free academic APIs return incomplete citation data, so the graph would be misleading. It is also mostly visual work with little backend depth. A flat reference list (optional feature) delivers most of the value for a fraction of the effort. |
| **Knowledge graph / entity-relationship extraction** | Requires scientific NER, entity resolution, and a graph store. Months of work, brittle results, and it duplicates what section-weighted vector retrieval already achieves. |
| **Fine-tuning or training a custom LLM** | Needs labelled data, GPUs, and evaluation infrastructure. A well-built RAG pipeline outperforms a poorly fine-tuned small model for this task — and that comparison is itself a good interview answer. |
| **Multi-agent orchestration** | Agent frameworks add non-determinism, cost, and debugging difficulty. Every workflow here is a known sequence of steps, which a deterministic pipeline handles better. |
| **Kubernetes** | Two containers on Render. K8s would add cluster management, ingress, and cost for zero benefit at one instance per service. |
| **Microservice decomposition** | Two services with one clear boundary is a deliberate architecture. Splitting further would create distributed transactions and network overhead without solving any real problem. |
| **Message queue (Kafka / RabbitMQ)** | A bounded thread pool with persisted job state gives durability, progress, and retry on a single instance. The migration path to a queue is documented in Section 24.3 if scale ever demands it. |
| **Numeric novelty score (e.g. "73% novel")** | Would imply a precision the system cannot deliver. Structured evidence with a confidence level is honest; a percentage is not. |
| **OCR for scanned PDFs** | Adds Tesseract, a large dependency, and poor accuracy on multi-column academic layouts. Text-based PDFs cover the overwhelming majority of real papers. Failure is handled with a clear, specific message. |
| **Real-time collaboration** | Requires WebSockets, presence, and conflict resolution. RLNA is single-researcher by design. |
| **Admin dashboard / role hierarchy** | No second user type exists. Building roles nobody uses is dead code. |
| **Redis / distributed caching** | Database-backed result caching already eliminates the expensive calls. Redis would be another service to run for a marginal gain. |
| **Mobile app** | Research reading happens on a laptop. A responsive web app covers the real use case. |

### The principle

> Every technology in this project earns its place by solving a problem the project actually has. Anything that would only make the stack look impressive was left out — and this section exists so that choice is visible.

---

# 35. Future Enhancements

Realistic next steps if the project continues past the portfolio stage:

- **Hybrid search** — combine PostgreSQL full-text with vector similarity and merge rankings
- **Chunk reranking** — cheap LLM re-scoring of the top-30 retrieved chunks
- **Retrieval evaluation harness** — a labelled query set to measure recall@k, turning retrieval tuning from guesswork into measurement
- **Zotero / Mendeley import**
- **OCR pipeline** for scanned papers
- **Saved searches with new-paper alerts**
- **Team projects** with shared libraries
- **Additional LLM providers** behind the existing router interface — no feature code changes required
- **Streaming responses** for long analyses

---

# 36. Resume Summary

### Project line

> **RLNA — Research Literature Review & Novelty Assistant** · React, Spring Boot, FastAPI, PostgreSQL + pgvector, Cloudflare R2, Gemini/Groq
>
> Built a full-stack AI research assistant that indexes academic PDFs into a vector database and answers research questions with evidence-grounded citations. Implemented section-aware chunking and section-weighted retrieval, a RAG pipeline with schema-validated structured outputs, two-tier model routing with automatic multi-provider fallback, and fingerprint-based result caching that eliminates repeated LLM calls for identical analyses. Deployed as containerized services on Vercel and Render with per-user data isolation and asynchronous document processing.

> Replace the caching claim with your own measured number once you have one — a real figure you can defend beats a rounded estimate you can't.

### Talking points

| Topic | What to say |
|---|---|
| **Architecture** | Why two backends, and where the boundary sits |
| **RAG quality** | Why section-weighted retrieval beats naive top-k for gap analysis |
| **Reliability** | Timeouts, bounded retries, provider cooldown, graceful degradation |
| **Cost engineering** | Fingerprint-based caching, two-tier routing, local embeddings |
| **Security** | Query-level isolation, prompt injection defence for untrusted PDFs |
| **Async design** | Thread pool with persisted job state — and why not a message queue |
| **Judgement** | Section 34: what was deliberately left out, and why |

### Skills demonstrated

`Java` `Spring Boot` `Python` `FastAPI` `React` `PostgreSQL` `pgvector` `Vector search` `RAG` `LLM integration` `Prompt engineering` `Sentence Transformers` `REST API design` `OAuth` `Object storage` `Async processing` `Caching` `Docker` `Testing` `System design`

---

*RLNA is built to be understood, explained, and defended — not just demoed.*
