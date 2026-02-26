from __future__ import annotations

import hashlib
import json
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import IdempotencyRecord


def build_request_hash(payload: dict[str, Any]) -> str:
    encoded = json.dumps(payload, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def load_idempotency(
    db: Session,
    *,
    key: str,
    endpoint: str,
    user_id: str,
    request_hash: str,
) -> dict[str, Any] | None:
    record = db.scalar(select(IdempotencyRecord).where(IdempotencyRecord.key == key))
    if not record:
        return None
    if record.endpoint != endpoint or record.user_id != user_id or record.request_hash != request_hash:
        return None
    return {"body": record.response_body, "status_code": record.status_code}


def save_idempotency(
    db: Session,
    *,
    key: str,
    endpoint: str,
    user_id: str,
    request_hash: str,
    response_body: dict[str, Any],
    status_code: int,
) -> None:
    existing = db.scalar(select(IdempotencyRecord).where(IdempotencyRecord.key == key))
    if existing:
        return
    db.add(
        IdempotencyRecord(
            key=key,
            endpoint=endpoint,
            user_id=user_id,
            request_hash=request_hash,
            response_body=response_body,
            status_code=status_code,
        )
    )
