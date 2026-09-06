"""Caller authentication for the internal API.

This service performs no user authorization of its own: it receives requests
that Spring Boot has already authorized, carrying explicit ids (Section 22.2,
rule 4). The shared token is therefore the entire trust boundary, which is why
the service must never be publicly routable.
"""

from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status

from app.core.config import get_settings


async def require_internal_token(authorization: str = Header(default="")) -> None:
    settings = get_settings()
    expected = settings.allowed_caller_token

    scheme, _, presented = authorization.partition(" ")
    if scheme.lower() != "bearer" or not presented:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "UNAUTHENTICATED", "message": "Internal token required."},
        )
    # Constant-time comparison: a timing-varying check would leak the token one
    # byte at a time to anyone who can reach this service.
    if not hmac.compare_digest(presented, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "UNAUTHENTICATED", "message": "Internal token required."},
        )
