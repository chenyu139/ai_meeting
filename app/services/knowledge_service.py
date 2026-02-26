from __future__ import annotations

from typing import Any

import requests

from app.config import get_settings


class KnowledgeService:
    def __init__(self) -> None:
        self.settings = get_settings()

    def search(self, query: str, top_k: int = 5) -> list[dict[str, Any]]:
        if not self.settings.knowledge_search_url:
            return []

        response = requests.get(
            self.settings.knowledge_search_url,
            params={"q": query, "top_k": top_k},
            timeout=self.settings.knowledge_search_timeout_sec,
        )
        response.raise_for_status()
        payload = response.json()

        if isinstance(payload, list):
            return payload
        if isinstance(payload, dict):
            for key in ("items", "data", "results"):
                value = payload.get(key)
                if isinstance(value, list):
                    return value
        return []
