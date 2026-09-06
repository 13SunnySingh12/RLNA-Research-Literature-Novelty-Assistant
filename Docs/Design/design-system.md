# Interface design

The frontend is dense product UI for people reading research, not a marketing
site. The design is built around one question: can a reader always tell what a
paper said from what a model said about it?

---

## Tokens

Every colour is a CSS custom property defined in `Frontend/src/styles/index.css`.
Components never hard-code a colour, so the whole application themes from one
place.

| Group | Purpose |
|---|---|
| `--surface`, `--surface-raised`, `--surface-sunken`, `--surface-inset` | Four elevation levels, nothing deeper |
| `--text`, `--text-muted`, `--text-subtle` | Three levels of emphasis |
| `--border`, `--border-strong` | Hairlines, and the edges of interactive controls |
| `--accent`, `--accent-hover`, `--accent-soft`, `--accent-contrast` | One accent for the whole application |
| `--ai-surface`, `--ai-border`, `--ai-text` | Model-generated content |
| `--evidence-surface`, `--evidence-border` | Text quoted verbatim from a paper |
| `--ok`, `--warn`, `--danger` and their soft variants | Status |

### The accent

A deep teal (`#0f6b62` light, `#3fb5a5` dark). Chosen partly because it clears
WCAG AA against both the raised and sunken surfaces in both themes, and partly
because the default blue-violet of most AI products carries an association this
tool is specifically trying not to invoke.

One accent, used identically everywhere. There is no second accent for a
"special" section.

### Two extra surfaces, for one specific reason

`--ai-surface` is warm; `--evidence-surface` is cool. This is the single most
load-bearing decision in the interface. A summary and the passage it was drawn
from must never look like the same kind of object, because the whole claim of
the product is that you can tell them apart.

Any block a model produced is wrapped in `AiContent`, which carries the warm
surface and a labelled header. Any block quoted from a paper uses the cool
surface and shows its paper and section.

---

## Type and shape

- **IBM Plex Sans** for interface text, **IBM Plex Mono** for numbers,
  identifiers and section labels. Self-hosted through `@fontsource`, so there is
  no third-party request on first paint. Plex was designed for technical
  documentation, which is what this application mostly renders.
- Numbers use `tabular-nums` so a polling progress figure does not shift as it
  changes.
- **One radius scale**: `--radius-control` (8px) for inputs, buttons and small
  blocks, `--radius-panel` (12px) for panels. Nothing else.

---

## Theme

Light, dark, and system, in that cycle, from one control in the header. The
choice persists in `localStorage`; with no choice stored, `prefers-color-scheme`
decides.

The whole page is one theme. No section inverts.

---

## States

Every surface that loads data handles four states, and they are components
rather than conventions, so a screen cannot quietly ship with only one.

| State | Component | Rule |
|---|---|---|
| Loading | `Skeleton`, `SkeletonRows`, `SkeletonText` | Shaped like the content that is coming, so nothing jumps when it arrives |
| Empty | `EmptyState` | Says what to do next, never just "no data" |
| Error | `ErrorState`, `InlineError` | Says what happened, offers a retry when retrying could help, shows the trace id |
| Success | the content | |

Two states specific to this product:

- **`ProcessingProgress`** shows the step the server is actually on
  ("Generating embeddings", 70%), read from the persisted job. A spinner would
  tell a user to keep waiting; a step tells them it is working.
- **`InsufficientEvidence`** renders a refusal as a legitimate outcome rather
  than a failure, because a refusal is the correct answer when retrieval found
  too little.

---

## Honesty in the interface

These are interface requirements, not copy suggestions.

1. **Confidence and limitations render with the result.** `ConfidenceNotice` is
   part of `AnalysisResult`, never behind a tooltip or a disclosure. An
   assessment read without its limitations is an assessment misread.
2. **Corpus size is always visible.** A gap found across five papers is not the
   same claim as one found across fifty.
3. **Novelty carries a fixed banner**: absence of similar work in this corpus is
   not evidence that none exists.
4. **"Not reported" is styled as absence**, in italic muted text, so a comparison
   table never dresses a gap up as a finding.
5. **Gaps are labelled "Potential research gap"**, every time.
6. **Evidence is one click from any claim**, through `EvidenceDisclosure`.
7. **Provider and model are never shown.** They are stored for operations and
   deliberately excluded from every response.

---

## Accessibility

- A visible focus ring on every interactive element, on every surface, in both
  themes. It is defined once, on `:focus-visible`, rather than per component.
- Labels sit above inputs; helper text and errors below. No placeholder-as-label.
- Errors use `role="alert"`; progress bars carry `aria-valuenow`; the search mode
  switch is a real `radiogroup`.
- A skip link to `#main`.
- Icon-only buttons always carry an `aria-label`.
- All motion is short state transition, and `prefers-reduced-motion` collapses
  it. No parallax, no scroll hijacking, no infinite loops.

---

## Layout

- Content is capped at 1500px with a sidebar that collapses below `lg`.
- Wide content, notably the comparison table, scrolls inside its own container
  so the page body never scrolls sideways.
- Grid, not flexbox percentage arithmetic.
- `min-h-[100dvh]` rather than `h-screen`, so mobile browser chrome does not
  cause a jump.
