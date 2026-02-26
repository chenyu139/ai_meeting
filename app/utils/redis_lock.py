from __future__ import annotations

from contextlib import contextmanager

from redis import Redis


@contextmanager
def redis_lock(redis_client: Redis | None, key: str, ttl_seconds: int = 30):
    if redis_client is None:
        yield True
        return

    token = "1"
    acquired = redis_client.set(name=key, value=token, nx=True, ex=ttl_seconds)
    if not acquired:
        yield False
        return
    try:
        yield True
    finally:
        redis_client.delete(key)
