from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from app.config import get_settings


@dataclass
class TingwuTask:
    task_id: str
    task_status: str
    meeting_join_url: str
    started_at: float
    stopped_at: float | None = None


class TingwuClient:
    _mock_tasks: dict[str, TingwuTask] = {}

    def __init__(self) -> None:
        self.settings = get_settings()
        self.mode = self.settings.tingwu_mode.lower()
        self._sdk_client = None
        self._sdk_models = None

        if self.mode in {"sdk", "real"}:
            self._init_sdk_client()

    def _init_sdk_client(self) -> None:
        required = [
            self.settings.tingwu_access_key_id,
            self.settings.tingwu_access_key_secret,
            self.settings.tingwu_app_key,
        ]
        if not all(required):
            raise RuntimeError("Missing Tingwu credentials. Set TINGWU_ACCESS_KEY_ID/SECRET and TINGWU_APP_KEY")

        from alibabacloud_tea_openapi import models as open_api_models
        from alibabacloud_tingwu20230930 import models as tingwu_models
        from alibabacloud_tingwu20230930.client import Client as TingwuSdkClient

        config = open_api_models.Config(
            access_key_id=self.settings.tingwu_access_key_id,
            access_key_secret=self.settings.tingwu_access_key_secret,
            region_id=self.settings.tingwu_region,
        )
        config.endpoint = (
            self.settings.tingwu_endpoint
            if self.settings.tingwu_endpoint
            else f"tingwu.{self.settings.tingwu_region}.aliyuncs.com"
        )

        self._sdk_client = TingwuSdkClient(config)
        self._sdk_models = tingwu_models

    def create_realtime_task(self, task_key: str, title: str, webhook_url: str) -> dict[str, Any]:
        if self.mode == "mock":
            task_id = f"mock-{uuid.uuid4()}"
            join_url = f"wss://mock-tingwu.example.com/ws/{task_id}"
            self._mock_tasks[task_id] = TingwuTask(
                task_id=task_id,
                task_status="INITIATED",
                meeting_join_url=join_url,
                started_at=time.time(),
            )
            return {"task_id": task_id, "meeting_join_url": join_url}

        req = self._sdk_models.CreateTaskRequest(
            app_key=self.settings.tingwu_app_key,
            type="realtime",
            operation="start",
            input=self._sdk_models.CreateTaskRequestInput(
                task_key=task_key,
                source_language="cn",
                format="pcm",
                sample_rate=16000,
                progressive_callbacks_enabled=True,
            ),
            parameters=self._sdk_models.CreateTaskRequestParameters(
                meeting_assistance_enabled=True,
                meeting_assistance=self._sdk_models.CreateTaskRequestParametersMeetingAssistance(
                    types=["Actions", "KeyInformation"]
                ),
                summarization_enabled=True,
                summarization=self._sdk_models.CreateTaskRequestParametersSummarization(
                    types=["Paragraph", "Conversational", "QuestionsAnswering"]
                ),
                auto_chapters_enabled=True,
                transcription=self._sdk_models.CreateTaskRequestParametersTranscription(
                    diarization_enabled=True,
                    realtime_diarization_enabled=True,
                ),
            ),
        )

        response = self._sdk_client.create_task(req)
        data = (response.body.data.to_map() if response and response.body and response.body.data else {})

        task_id = data.get("TaskId")
        join_url = data.get("MeetingJoinUrl")
        if not task_id:
            raise RuntimeError(f"CreateTask missing TaskId: {response.body.to_map() if response.body else {}}")

        return {
            "task_id": task_id,
            "meeting_join_url": join_url,
            "raw": response.body.to_map() if response and response.body else {},
        }

    def stop_task(self, task_id: str) -> dict[str, Any]:
        if self.mode == "mock":
            task = self._mock_tasks.get(task_id)
            if not task:
                return {"ok": False, "error": "task_not_found"}
            task.task_status = "ONGOING"
            task.stopped_at = time.time()
            return {"ok": True}

        req = self._sdk_models.CreateTaskRequest(
            app_key=self.settings.tingwu_app_key,
            type="realtime",
            operation="stop",
            input=self._sdk_models.CreateTaskRequestInput(task_id=task_id, source_language="cn"),
        )
        response = self._sdk_client.create_task(req)
        body = response.body.to_map() if response and response.body else {}
        code = body.get("Code")
        if code and code != "0":
            return {"ok": False, "error": body.get("Message", "unknown_error"), "raw": body}
        return {"ok": True, "raw": body}

    def get_task_info(self, task_id: str) -> dict[str, Any]:
        if self.mode == "mock":
            task = self._mock_tasks.get(task_id)
            if not task:
                return {"TaskId": task_id, "TaskStatus": "FAILED", "ErrorMessage": "task_not_found"}

            if task.task_status == "ONGOING" and task.stopped_at and time.time() - task.stopped_at > 2:
                task.task_status = "COMPLETED"

            payload: dict[str, Any] = {
                "TaskId": task.task_id,
                "TaskStatus": task.task_status,
                "MeetingJoinUrl": task.meeting_join_url,
            }
            if task.task_status == "COMPLETED":
                payload["InlineResult"] = {
                    "transcript": [
                        {
                            "speaker": "说话人1",
                            "start_ms": 0,
                            "end_ms": 1200,
                            "text": "这是一次关于AI听会后端方案的讨论。",
                        },
                        {
                            "speaker": "说话人2",
                            "start_ms": 1200,
                            "end_ms": 3200,
                            "text": "我们决定采用通义听悟直连并在完成态入库。",
                        },
                    ],
                    "summary": {
                        "overview": "会议明确采用听悟直连、完成态入库与无MQ异步任务表方案。",
                        "chapters": [
                            {"title": "架构决策", "start": "00:00", "content": "确认技术路径"}
                        ],
                        "decisions": ["前端直连听悟WS", "后端回调+轮询兜底", "不使用RocketMQ"],
                        "highlights": ["完成态入库"],
                        "todos": [
                            {
                                "text": "完善生产环境TINGWU SDK实现",
                                "owner": "研发",
                            }
                        ],
                    },
                }
            return payload

        response = self._sdk_client.get_task_info(task_id)
        body_map = response.body.to_map() if response and response.body else {}
        data = body_map.get("Data", {}) or {}
        result = data.get("Result", {}) or {}

        payload: dict[str, Any] = {
            "TaskId": data.get("TaskId") or task_id,
            "TaskStatus": data.get("TaskStatus") or body_map.get("Code"),
            "Result": result,
            "OutputMp3Path": data.get("OutputMp3Path"),
            "OutputMp4Path": data.get("OutputMp4Path"),
            "RawResponse": body_map,
        }
        if data.get("ErrorCode"):
            payload["ErrorCode"] = data.get("ErrorCode")
        if data.get("ErrorMessage"):
            payload["ErrorMessage"] = data.get("ErrorMessage")
        return payload

    @staticmethod
    def extract_task_id(payload: dict[str, Any]) -> str | None:
        for key in ("TaskId", "taskId", "task_id"):
            value = payload.get(key)
            if isinstance(value, str) and value:
                return value
        data = payload.get("Data")
        if isinstance(data, dict):
            return TingwuClient.extract_task_id(data)
        return None

    @staticmethod
    def extract_task_status(payload: dict[str, Any]) -> str | None:
        for key in ("TaskStatus", "taskStatus", "task_status"):
            value = payload.get(key)
            if isinstance(value, str) and value:
                return value.upper()
        data = payload.get("Data")
        if isinstance(data, dict):
            return TingwuClient.extract_task_status(data)
        return None

    @staticmethod
    def extract_result_urls(task_info: dict[str, Any]) -> dict[str, str]:
        candidates: dict[str, str] = {}

        result = task_info.get("Result")
        if isinstance(result, dict):
            if isinstance(result.get("Transcription"), str):
                candidates["transcript"] = result["Transcription"]
            if isinstance(result.get("Summarization"), str):
                candidates["summary"] = result["Summarization"]
            if isinstance(result.get("AutoChapters"), str):
                candidates["chapters"] = result["AutoChapters"]
            if isinstance(result.get("MeetingAssistance"), str):
                candidates["meeting_assistance"] = result["MeetingAssistance"]

        for media_key, media_name in (("OutputMp3Path", "mp3"), ("OutputMp4Path", "mp4")):
            media_url = task_info.get(media_key)
            if isinstance(media_url, str) and media_url.startswith("http"):
                candidates[media_name] = media_url

        def walk(node: Any, path: str = "") -> None:
            if isinstance(node, dict):
                for k, v in node.items():
                    lower_key = k.lower()
                    current = f"{path}.{lower_key}" if path else lower_key
                    if isinstance(v, str) and v.startswith("http"):
                        if "summary" in lower_key or "summary" in current:
                            candidates.setdefault("summary", v)
                        elif "chapter" in lower_key:
                            candidates.setdefault("chapters", v)
                        elif "meetingassistance" in lower_key or "assist" in lower_key:
                            candidates.setdefault("meeting_assistance", v)
                        elif "trans" in lower_key or "text" in lower_key:
                            candidates.setdefault("transcript", v)
                        else:
                            candidates.setdefault("raw", v)
                    else:
                        walk(v, current)
            elif isinstance(node, list):
                for item in node:
                    walk(item, path)

        walk(task_info)
        return candidates


class TaskEventNormalizer:
    @staticmethod
    def normalize(payload: dict[str, Any]) -> dict[str, Any]:
        client = TingwuClient()
        return {
            "task_id": client.extract_task_id(payload),
            "task_status": client.extract_task_status(payload),
            "event_time": payload.get("EventTime") or datetime.utcnow().isoformat(),
            "raw": payload,
        }
