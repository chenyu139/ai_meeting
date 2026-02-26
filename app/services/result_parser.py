from __future__ import annotations

from typing import Any

import requests


def _load_json_url(url: str) -> dict[str, Any] | list[Any]:
    response = requests.get(url, timeout=20)
    response.raise_for_status()
    return response.json()


class TingwuResultParser:
    def parse(self, task_info: dict[str, Any], result_urls: dict[str, str]) -> dict[str, Any]:
        inline_result = task_info.get("InlineResult")

        transcript_source: Any = None
        summary_source: Any = None

        if isinstance(inline_result, dict):
            transcript_source = inline_result.get("transcript")
            summary_source = inline_result.get("summary")

        if transcript_source is None and result_urls.get("transcript"):
            transcript_source = _load_json_url(result_urls["transcript"])

        if summary_source is None and result_urls.get("summary"):
            summary_source = _load_json_url(result_urls["summary"])

        segments = self._parse_transcript_segments(transcript_source)
        summary = self._parse_summary(summary_source)

        if result_urls.get("chapters"):
            chapters = _load_json_url(result_urls["chapters"])
            if isinstance(chapters, list):
                summary["chapters"] = chapters

        if result_urls.get("todos"):
            todos = _load_json_url(result_urls["todos"])
            if isinstance(todos, list):
                summary["todos"] = todos

        return {
            "segments": segments,
            "summary": summary,
            "raw_result_refs": result_urls,
        }

    def _parse_transcript_segments(self, payload: Any) -> list[dict[str, Any]]:
        if payload is None:
            return []

        if isinstance(payload, list):
            source = payload
        elif isinstance(payload, dict):
            for key in ("segments", "sentences", "SentenceList", "data"):
                value = payload.get(key)
                if isinstance(value, list):
                    source = value
                    break
            else:
                source = []
        else:
            source = []

        normalized: list[dict[str, Any]] = []
        for idx, item in enumerate(source):
            if not isinstance(item, dict):
                continue
            text = item.get("text") or item.get("Text") or item.get("content") or ""
            if not text:
                continue
            normalized.append(
                {
                    "segment_order": idx,
                    "speaker": item.get("speaker") or item.get("Speaker"),
                    "start_ms": item.get("start_ms") or item.get("StartTime") or item.get("begin_time"),
                    "end_ms": item.get("end_ms") or item.get("EndTime") or item.get("end_time"),
                    "confidence": item.get("confidence") or item.get("Confidence"),
                    "text": text,
                }
            )
        return normalized

    def _parse_summary(self, payload: Any) -> dict[str, Any]:
        default = {
            "overview": "",
            "chapters": [],
            "decisions": [],
            "highlights": [],
            "todos": [],
        }
        if payload is None:
            return default

        if isinstance(payload, dict):
            return {
                "overview": payload.get("overview")
                or payload.get("Overview")
                or payload.get("summary")
                or "",
                "chapters": payload.get("chapters") or payload.get("Chapters") or [],
                "decisions": payload.get("decisions") or payload.get("Decisions") or [],
                "highlights": payload.get("highlights") or payload.get("Highlights") or [],
                "todos": payload.get("todos") or payload.get("Todos") or [],
            }

        if isinstance(payload, list):
            return {**default, "highlights": payload}

        return default
