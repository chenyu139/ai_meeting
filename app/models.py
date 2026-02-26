from __future__ import annotations

import uuid
from datetime import datetime
from typing import Optional

from sqlalchemy import JSON, DateTime, Enum, ForeignKey, Integer, String, Text, UniqueConstraint, Uuid
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base
from app.enums import MeetingStatus, ParseJobStage, ParseJobStatus, ShareStatus


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime,
        default=datetime.utcnow,
        onupdate=datetime.utcnow,
        nullable=False,
    )


class Meeting(Base, TimestampMixin):
    __tablename__ = "meeting"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    owner_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    title: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[MeetingStatus] = mapped_column(Enum(MeetingStatus), nullable=False, index=True)

    task_id: Mapped[Optional[str]] = mapped_column(String(128), unique=True)
    meeting_join_url: Mapped[Optional[str]] = mapped_column(Text)
    processing_progress: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    started_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False)
    ended_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    duration_sec: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    deleted_at: Mapped[Optional[datetime]] = mapped_column(DateTime)

    transcripts: Mapped[list[MeetingTranscriptFinal]] = relationship(back_populates="meeting")
    summary: Mapped[Optional[MeetingSummary]] = relationship(back_populates="meeting", uselist=False)
    parse_jobs: Mapped[list[ParseJob]] = relationship(back_populates="meeting")


class MeetingTranscriptFinal(Base, TimestampMixin):
    __tablename__ = "meeting_transcript_final"
    __table_args__ = (UniqueConstraint("meeting_id", "segment_order", name="uq_transcript_meeting_segment"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    meeting_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), ForeignKey("meeting.id"), index=True)
    segment_order: Mapped[int] = mapped_column(Integer, nullable=False)
    speaker: Mapped[Optional[str]] = mapped_column(String(64))
    start_ms: Mapped[Optional[int]] = mapped_column(Integer)
    end_ms: Mapped[Optional[int]] = mapped_column(Integer)
    confidence: Mapped[Optional[int]] = mapped_column(Integer)
    text: Mapped[str] = mapped_column(Text, nullable=False)

    meeting: Mapped[Meeting] = relationship(back_populates="transcripts")


class MeetingSummary(Base, TimestampMixin):
    __tablename__ = "meeting_summary"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    meeting_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("meeting.id"), unique=True, index=True
    )

    overview: Mapped[str] = mapped_column(Text, default="", nullable=False)
    chapters: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    decisions: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    highlights: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    todos: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    raw_result_refs: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    knowledge_links: Mapped[list] = mapped_column(JSON, default=list, nullable=False)

    version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)

    meeting: Mapped[Meeting] = relationship(back_populates="summary")


class MeetingAudioAsset(Base, TimestampMixin):
    __tablename__ = "meeting_audio_asset"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    meeting_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), ForeignKey("meeting.id"), index=True)
    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    object_key: Mapped[str] = mapped_column(String(512), nullable=False)
    content_type: Mapped[str] = mapped_column(String(128), default="audio/m4a", nullable=False)
    size_bytes: Mapped[int] = mapped_column(Integer, nullable=False)


class ShareLink(Base, TimestampMixin):
    __tablename__ = "share_link"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    meeting_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), ForeignKey("meeting.id"), index=True)
    created_by: Mapped[str] = mapped_column(String(64), nullable=False, index=True)

    token: Mapped[str] = mapped_column(String(128), unique=True, nullable=False)
    passcode_hash: Mapped[Optional[str]] = mapped_column(String(255))
    expire_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    status: Mapped[ShareStatus] = mapped_column(Enum(ShareStatus), default=ShareStatus.ACTIVE, nullable=False)


class ShareAccessLog(Base, TimestampMixin):
    __tablename__ = "share_access_log"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    share_link_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), ForeignKey("share_link.id"), index=True)
    meeting_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), ForeignKey("meeting.id"), index=True)
    viewer_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    ip: Mapped[Optional[str]] = mapped_column(String(64))
    user_agent: Mapped[Optional[str]] = mapped_column(String(255))


class ParseJob(Base, TimestampMixin):
    __tablename__ = "parse_job"
    __table_args__ = (UniqueConstraint("meeting_id", "stage", name="uq_parse_job_meeting_stage"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    meeting_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), ForeignKey("meeting.id"), index=True)
    task_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)

    stage: Mapped[ParseJobStage] = mapped_column(Enum(ParseJobStage), nullable=False)
    status: Mapped[ParseJobStatus] = mapped_column(Enum(ParseJobStatus), default=ParseJobStatus.PENDING)
    retry_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    max_retries: Mapped[int] = mapped_column(Integer, default=3, nullable=False)
    next_retry_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False)

    locked_by: Mapped[Optional[str]] = mapped_column(String(64))
    locked_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    last_error: Mapped[Optional[str]] = mapped_column(Text)
    payload: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)

    meeting: Mapped[Meeting] = relationship(back_populates="parse_jobs")


class TaskEventLog(Base):
    __tablename__ = "task_event_log"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    meeting_id: Mapped[Optional[uuid.UUID]] = mapped_column(Uuid(as_uuid=True), ForeignKey("meeting.id"), index=True)
    source: Mapped[str] = mapped_column(String(32), nullable=False)
    event_type: Mapped[str] = mapped_column(String(128), nullable=False)
    payload: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False)


class IdempotencyRecord(Base):
    __tablename__ = "idempotency_record"

    key: Mapped[str] = mapped_column(String(128), primary_key=True)
    endpoint: Mapped[str] = mapped_column(String(128), nullable=False)
    user_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    request_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    response_body: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    status_code: Mapped[int] = mapped_column(Integer, default=200, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False)
