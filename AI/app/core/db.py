"""Database access.

A small pooled connection set is shared by the whole service. Neon suspends idle
computes, so connections are recycled rather than held open indefinitely.
"""

from __future__ import annotations

import logging
from contextlib import contextmanager
from typing import Iterator

from pgvector.psycopg import register_vector
from psycopg import Connection
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from app.core.config import get_settings

logger = logging.getLogger(__name__)

_pool: ConnectionPool | None = None


def _configure(conn: Connection) -> None:
    # Teaches this connection how to adapt Python vectors to the pgvector type.
    register_vector(conn)


def get_pool() -> ConnectionPool:
    global _pool
    if _pool is None:
        settings = get_settings()
        _pool = ConnectionPool(
            conninfo=settings.psycopg_url(),
            min_size=1,
            max_size=5,
            max_idle=300,
            max_lifetime=600,
            timeout=30,
            configure=_configure,
            kwargs={"row_factory": dict_row},
            open=False,
        )
        _pool.open()
    return _pool


@contextmanager
def connection() -> Iterator[Connection]:
    with get_pool().connection() as conn:
        yield conn


def close_pool() -> None:
    global _pool
    if _pool is not None:
        _pool.close()
        _pool = None


def healthy() -> bool:
    try:
        with connection() as conn, conn.cursor() as cur:
            cur.execute("SELECT 1")
            return cur.fetchone() is not None
    except Exception:  # noqa: BLE001 - health checks must never raise
        logger.warning("Database health check failed", exc_info=True)
        return False
