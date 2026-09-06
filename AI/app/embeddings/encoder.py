"""Local embedding model (Section 19.1).

Embeddings run in-process on CPU. That is what makes semantic search free at the
point of use, and it is why unlimited search stays viable on a free tier: the
only per-query cost is the CPU already paid for.

The model is loaded lazily and once. Loading it on first use rather than at
import keeps the test suite and the health endpoint fast.
"""

from __future__ import annotations

import logging
import threading
from functools import lru_cache

from app.core.config import get_settings

logger = logging.getLogger(__name__)

_lock = threading.Lock()
_model = None


def _load():
    global _model
    if _model is None:
        with _lock:
            if _model is None:
                from sentence_transformers import SentenceTransformer

                settings = get_settings()
                logger.info("Loading embedding model %s", settings.embedding_model)
                _model = SentenceTransformer(settings.embedding_model, device="cpu")
                dimension = _model.get_sentence_embedding_dimension()
                if dimension != settings.embedding_dimension:
                    # The vector column is fixed-width. A mismatch would fail on
                    # insert with a far less obvious message than this one.
                    raise RuntimeError(
                        f"Embedding model {settings.embedding_model} produces {dimension}-dimensional "
                        f"vectors but the schema expects {settings.embedding_dimension}."
                    )
    return _model


def model_name() -> str:
    return get_settings().embedding_model


def max_sequence_tokens() -> int:
    """The longest input the model actually reads.

    Sentence Transformers truncates silently past this point, so text beyond it
    is stored and displayed as evidence while contributing nothing to the vector
    it is retrieved by. Callers size their chunks against this rather than
    against a number in configuration.
    """
    return int(_load().max_seq_length)


def encode(texts: list[str]) -> list[list[float]]:
    """Embeds a batch of texts, normalized for cosine similarity."""
    if not texts:
        return []
    model = _load()
    vectors = model.encode(
        texts,
        batch_size=16,
        convert_to_numpy=True,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    return [vector.tolist() for vector in vectors]


def encode_one(text: str) -> list[float]:
    return encode([text])[0]


@lru_cache(maxsize=1)
def _tokenizer():
    return _load().tokenizer


_tokenizer_lock = threading.Lock()


def count_tokens(text: str) -> int:
    """Token count from the embedding model's own tokenizer.

    Using the real tokenizer rather than a word-count heuristic is what keeps a
    chunk actually inside the model's window.

    Serialised because the Rust-backed fast tokenizer is a single shared object
    that panics with "Already borrowed" when two threads encode at once.
    Indexing runs in a thread pool, so two concurrent uploads hit exactly that,
    and the paper failed with a 500 rather than a reason anyone could act on.
    Chunking calls this per sentence, so the lock is held briefly and only ever
    contends with other indexing work.
    """
    if not text:
        return 0
    with _tokenizer_lock:
        return len(_tokenizer().encode(text, add_special_tokens=False))


def warm_up() -> None:
    """Pre-loads the model so the first user request is not the one that pays for it."""
    try:
        encode_one("warm up")
    except Exception:  # noqa: BLE001 - startup warmth is best effort
        logger.warning("Embedding model warm-up failed; it will load on first use", exc_info=True)
