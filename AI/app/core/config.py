"""Configuration for the AI service.

Everything the service needs is read from the environment. Required values are
checked at import time so the process refuses to start rather than failing
halfway through a user's first upload (Section 26.1).
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_REPO_ROOT = Path(__file__).resolve().parents[3]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(_REPO_ROOT / ".env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # --- Service ---
    port: int = Field(default=8000, alias="PORT")
    log_level: str = Field(default="info", alias="LOG_LEVEL")
    allowed_caller_token: str = Field(alias="ALLOWED_CALLER_TOKEN")

    # --- Database ---
    database_url: str = Field(alias="DATABASE_URL")

    # --- Object storage (Backblaze B2, S3-compatible API) ---
    b2_endpoint: str = Field(default="", alias="B2_ENDPOINT")
    b2_key_id: str = Field(default="", alias="B2_KEY_ID")
    b2_application_key: str = Field(default="", alias="B2_APPLICATION_KEY")
    b2_bucket_name: str = Field(default="rlna-papers", alias="B2_BUCKET_NAME")
    # No default: B2 signs over the region, so a placeholder fails to
    # authenticate rather than failing to route.
    b2_region: str = Field(default="", alias="B2_REGION")

    # --- Embeddings and retrieval ---
    embedding_model: str = Field(default="all-MiniLM-L6-v2", alias="EMBEDDING_MODEL")
    embedding_dimension: int = Field(default=384, alias="EMBEDDING_DIMENSION")
    chunk_size_tokens: int = Field(default=512, alias="CHUNK_SIZE_TOKENS")
    chunk_overlap_tokens: int = Field(default=64, alias="CHUNK_OVERLAP_TOKENS")
    retrieval_top_k: int = Field(default=8, alias="RETRIEVAL_TOP_K")
    retrieval_min_score: float = Field(default=0.35, alias="RETRIEVAL_MIN_SCORE")
    section_boost_factor: float = Field(default=1.25, alias="SECTION_BOOST_FACTOR")
    duplicate_similarity_threshold: float = Field(
        default=0.92, alias="DUPLICATE_SIMILARITY_THRESHOLD"
    )

    # --- Providers ---
    gemini_api_key: str = Field(default="", alias="GEMINI_API_KEY")
    gemini_model: str = Field(default="gemini-3.8-flash", alias="GEMINI_MODEL")
    gemini_fast_model: str = Field(default="gemini-3.5-flash-lite", alias="GEMINI_FAST_MODEL")

    groq_api_key: str = Field(default="", alias="GROQ_API_KEY")
    groq_model: str = Field(default="openai/gpt-oss-120b", alias="GROQ_MODEL")
    groq_fast_model: str = Field(default="openai/gpt-oss-20b", alias="GROQ_FAST_MODEL")

    openrouter_api_key: str = Field(default="", alias="OPENROUTER_API_KEY")
    openrouter_model: str = Field(
        default="nvidia/nemotron-3-super-120b-a12b:free", alias="OPENROUTER_MODEL"
    )

    # --- AI behaviour ---
    ai_request_timeout_seconds: int = Field(default=60, alias="AI_REQUEST_TIMEOUT_SECONDS")
    ai_max_retries: int = Field(default=2, alias="AI_MAX_RETRIES")
    ai_provider_cooldown_seconds: int = Field(default=60, alias="AI_PROVIDER_COOLDOWN_SECONDS")
    # Provider order per tier, comma separated. Empty keeps the built-in order,
    # which is chosen from measured free-tier capacity (see router).
    llm_strong_order: str = Field(default="", alias="LLM_STRONG_ORDER")
    llm_fast_order: str = Field(default="", alias="LLM_FAST_ORDER")
    dual_model_verification: bool = Field(default=False, alias="DUAL_MODEL_VERIFICATION")

    # --- Safety limits for untrusted PDFs (Section 26.3) ---
    max_pdf_pages: int = Field(default=400, alias="MAX_PDF_PAGES")
    max_pdf_characters: int = Field(default=4_000_000, alias="MAX_PDF_CHARACTERS")
    # Sized for free-tier provider limits, measured rather than guessed:
    # Groq's free tier allows 8,000 tokens per minute and rejects a single
    # larger request outright with 413, and Gemini's free tier starts
    # shedding requests around 20,000 characters. ~18k characters is roughly
    # 4,500 tokens of evidence, which leaves headroom for the prompt on both.
    max_context_characters: int = Field(default=18_000, alias="MAX_CONTEXT_CHARACTERS")

    @field_validator("allowed_caller_token")
    @classmethod
    def _token_must_be_strong(cls, value: str) -> str:
        if len(value) < 24:
            raise ValueError(
                "ALLOWED_CALLER_TOKEN must be at least 24 characters. "
                "This token is the only thing standing between the internal API and the internet."
            )
        return value

    @property
    def b2_endpoint_url(self) -> str:
        """The B2 console shows a bare host; boto3 needs a scheme."""
        endpoint = self.b2_endpoint.strip()
        if not endpoint:
            return endpoint
        return endpoint if "://" in endpoint else f"https://{endpoint}"

    @property
    def storage_configured(self) -> bool:
        return bool(
            self.b2_endpoint
            and self.b2_region
            and self.b2_key_id
            and self.b2_application_key
        )

    @property
    def any_provider_configured(self) -> bool:
        return bool(self.gemini_api_key or self.groq_api_key or self.openrouter_api_key)

    def psycopg_url(self) -> str:
        """psycopg accepts the libpq URL as-is; only the scheme alias differs."""
        if self.database_url.startswith("postgres://"):
            return "postgresql://" + self.database_url[len("postgres://") :]
        return self.database_url


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
