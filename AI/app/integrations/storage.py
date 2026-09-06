"""Object storage access (Backblaze B2, S3-compatible API).

This service reads PDFs with its own server-side credentials, given an object
key that Spring Boot has already checked ownership of. It never writes paper
objects and never issues URLs, so a compromised prompt has nothing here to reach
for.

The checksum settings matter. Since botocore 1.36 the SDK attaches data-integrity
headers to every request by default, and B2 rejects them outright
("Unsupported header 'x-amz-checksum-mode' received for this API call"). Turning
them down to when-required is what makes downloads work at all.
"""

from __future__ import annotations

import logging
import threading

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

from app.core.config import get_settings
from app.core.errors import PermanentError, TransientError

logger = logging.getLogger(__name__)

_lock = threading.Lock()
_client = None

# Object missing or bucket wrong: retrying cannot change the outcome.
_PERMANENT_ERROR_CODES = {"NoSuchKey", "NoSuchBucket", "404"}
# Credential or permission problems: also permanent from this request's point of
# view, but they mean a misconfiguration rather than a missing file.
_AUTH_ERROR_CODES = {"AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "403"}


def _get_client():
    global _client
    if _client is None:
        with _lock:
            if _client is None:
                settings = get_settings()
                if not settings.storage_configured:
                    raise TransientError(
                        "STORAGE_UNAVAILABLE", "Object storage is not configured."
                    )
                _client = boto3.client(
                    "s3",
                    endpoint_url=settings.b2_endpoint_url,
                    aws_access_key_id=settings.b2_key_id,
                    aws_secret_access_key=settings.b2_application_key,
                    # B2 verifies the SigV4 signature over the region, so it has
                    # to match the endpoint host rather than being a placeholder.
                    region_name=settings.b2_region,
                    config=Config(
                        signature_version="s3v4",
                        request_checksum_calculation="when_required",
                        response_checksum_validation="when_required",
                        retries={"max_attempts": 3, "mode": "standard"},
                        connect_timeout=10,
                        read_timeout=60,
                    ),
                )
    return _client


def download(object_key: str) -> bytes:
    settings = get_settings()
    try:
        response = _get_client().get_object(Bucket=settings.b2_bucket_name, Key=object_key)
        return response["Body"].read()
    except ClientError as exc:
        error = exc.response.get("Error", {})
        code = str(error.get("Code", ""))
        status = str(exc.response.get("ResponseMetadata", {}).get("HTTPStatusCode", ""))

        if code in _PERMANENT_ERROR_CODES or status == "404":
            raise PermanentError(
                "FILE_NOT_FOUND", "The stored file for this paper could not be found."
            ) from exc
        if code in _AUTH_ERROR_CODES or status == "403":
            # Logged loudly: this is an operator problem, not a user problem, and
            # it would otherwise look like an intermittent outage.
            logger.error(
                "Storage denied access to %s (code %s). Check the B2 application key "
                "and that it grants read access to bucket '%s'.",
                object_key, code or status, settings.b2_bucket_name,
            )
            raise PermanentError(
                "STORAGE_READ_FAILED", "The stored file could not be read."
            ) from exc

        logger.error("Storage read failed for key %s: %s", object_key, code or status)
        raise TransientError(
            "STORAGE_READ_FAILED", "The stored file could not be read right now."
        ) from exc
    except BotoCoreError as exc:
        logger.error("Storage transport failure for key %s", object_key, exc_info=True)
        raise TransientError(
            "STORAGE_READ_FAILED", "The stored file could not be read right now."
        ) from exc


def available() -> bool:
    return get_settings().storage_configured
