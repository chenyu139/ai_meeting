from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field

from app.enums import MeetingStatus, ParseJobStatus, ShareStatus


class MeetingCreateRequest(BaseModel):
    title: str = Field(default="新录音")


class MeetingCreateResponse(BaseModel):
    meeting_id: UUID
    task_id: str
    meeting_join_url: str
    status: MeetingStatus


class MeetingListItem(BaseModel):
    id: UUID
    title: str
    status: MeetingStatus
    owner_id: str
    started_at: datetime
    ended_at: datetime | None
    processing_progress: int


class MeetingDetailResponse(BaseModel):
    id: UUID
    title: str
    owner_id: str
    status: MeetingStatus
    started_at: datetime
    ended_at: datetime | None
    duration_sec: int
    task_id: str | None
    meeting_join_url: str | None
    processing_progress: int


class MeetingSummaryResponse(BaseModel):
    meeting_id: UUID
    overview: str
    chapters: list[Any]
    decisions: list[Any]
    highlights: list[Any]
    todos: list[Any]
    knowledge_links: list[Any]
    raw_result_refs: dict[str, Any]
    version: int


class MeetingSummaryUpdateRequest(BaseModel):
    overview: str | None = None
    chapters: list[Any] | None = None
    decisions: list[Any] | None = None
    highlights: list[Any] | None = None
    todos: list[Any] | None = None


class ShareLinkCreateRequest(BaseModel):
    expire_days: int = Field(default=7, ge=0, le=3650)
    passcode: str | None = Field(default=None, min_length=4, max_length=8)


class ShareLinkCreateResponse(BaseModel):
    token: str
    share_url: str
    expire_at: datetime | None
    status: ShareStatus


class ShareVerifyRequest(BaseModel):
    passcode: str | None = None


class ShareVerifyResponse(BaseModel):
    valid: bool


class ShareContentResponse(BaseModel):
    meeting: MeetingDetailResponse
    summary: MeetingSummaryResponse | None


class CallbackAckResponse(BaseModel):
    ok: bool


class AudioChunkUploadResponse(BaseModel):
    object_key: str
    size_bytes: int


class ParseJobAdminResetRequest(BaseModel):
    stage: str


class ParseJobResponse(BaseModel):
    meeting_id: UUID
    stage: str
    status: ParseJobStatus
    retry_count: int
