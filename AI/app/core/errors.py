"""Error types shared across the service.

Codes are part of the contract with Spring Boot: it maps them onto user-facing
messages and decides whether an attempt is worth retrying. Anything permanent
(a corrupt file, a scanned PDF) must not be retried; anything transient (a
provider outage) must be.
"""

from __future__ import annotations

from fastapi import HTTPException, status


class ServiceError(HTTPException):
    def __init__(self, code: str, message: str, status_code: int) -> None:
        super().__init__(status_code=status_code, detail={"code": code, "message": message})
        self.code = code


class PermanentError(ServiceError):
    """The request cannot succeed however many times it is retried."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(code, message, status.HTTP_422_UNPROCESSABLE_ENTITY)


class TransientError(ServiceError):
    """A retry, possibly against another provider, may succeed."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(code, message, status.HTTP_503_SERVICE_UNAVAILABLE)


class InsufficientEvidence(PermanentError):
    def __init__(self, message: str = "Not enough retrieved evidence to answer.") -> None:
        super().__init__("INSUFFICIENT_EVIDENCE", message)
