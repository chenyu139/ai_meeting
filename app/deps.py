from __future__ import annotations

from fastapi import Header, HTTPException, status


def get_current_user_id(x_user_id: str | None = Header(default=None)) -> str:
    if not x_user_id:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing X-User-Id header")
    return x_user_id


def get_optional_user_id(x_user_id: str | None = Header(default=None)) -> str | None:
    return x_user_id
