from __future__ import annotations

import socket
from datetime import datetime, timedelta
from typing import Any
from uuid import UUID

from redis import Redis
from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import SessionLocal
from app.enums import MeetingStatus, ParseJobStage, ParseJobStatus
from app.models import (
    Meeting,
    MeetingSummary,
    MeetingTranscriptFinal,
    ParseJob,
    TaskEventLog,
)
from app.services.knowledge_service import KnowledgeService
from app.services.result_parser import TingwuResultParser
from app.services.tingwu_client import TingwuClient
from app.utils.redis_lock import redis_lock


class WorkerService:
    def __init__(self) -> None:
        self.settings = get_settings()
        self.tingwu = TingwuClient()
        self.parser = TingwuResultParser()
        self.knowledge = KnowledgeService()
        self.worker_id = socket.gethostname()

        self.redis: Redis | None = None
        try:
            self.redis = Redis.from_url(self.settings.redis_url, decode_responses=True)
            self.redis.ping()
        except Exception:
            self.redis = None

    def run_once(self) -> None:
        with SessionLocal() as db:
            self.poll_processing_meetings(db)
            self.process_parse_jobs(db)

    def poll_processing_meetings(self, db: Session) -> None:
        cutoff = datetime.utcnow() - timedelta(seconds=self.settings.worker_processing_scan_interval_sec)
        meetings = db.scalars(
            select(Meeting).where(
                and_(
                    Meeting.status == MeetingStatus.PROCESSING,
                    Meeting.updated_at <= cutoff,
                    Meeting.deleted_at.is_(None),
                )
            )
        ).all()

        for meeting in meetings:
            task_info = self.tingwu.get_task_info(meeting.task_id or "")
            status = self.tingwu.extract_task_status(task_info)
            self._log_event(db, meeting.id, "worker", "poll_task_info", task_info)

            if status == "COMPLETED":
                meeting.processing_progress = max(meeting.processing_progress, 80)
                self.ensure_parse_job(db, meeting.id, meeting.task_id or "", ParseJobStage.RESULT_PARSE)
            elif status == "FAILED":
                meeting.status = MeetingStatus.FAILED
                meeting.processing_progress = 0

        db.commit()

    def process_parse_jobs(self, db: Session, limit: int = 10) -> None:
        jobs = db.scalars(
            select(ParseJob)
            .where(
                and_(
                    or_(
                        ParseJob.status == ParseJobStatus.PENDING,
                        and_(
                            ParseJob.status == ParseJobStatus.RUNNING,
                            ParseJob.locked_at <= datetime.utcnow() - timedelta(seconds=self.settings.worker_task_timeout_sec),
                        ),
                    ),
                    ParseJob.next_retry_at <= datetime.utcnow(),
                )
            )
            .order_by(ParseJob.created_at)
            .limit(limit)
        ).all()

        for job in jobs:
            lock_key = f"parse_job:{job.id}"
            with redis_lock(self.redis, lock_key, ttl_seconds=120) as locked:
                if not locked:
                    continue
                self._process_single_job(db, job)

    def _process_single_job(self, db: Session, job: ParseJob) -> None:
        meeting = db.get(Meeting, job.meeting_id)
        if not meeting or meeting.deleted_at is not None:
            job.status = ParseJobStatus.FAILED
            job.last_error = "meeting_not_found"
            db.commit()
            return

        job.status = ParseJobStatus.RUNNING
        job.locked_by = self.worker_id
        job.locked_at = datetime.utcnow()
        db.commit()

        try:
            if job.stage == ParseJobStage.RESULT_PARSE:
                self._handle_result_parse(db, meeting, job)
            elif job.stage == ParseJobStage.KNOWLEDGE_LINK:
                self._handle_knowledge_link(db, meeting, job)
            else:
                raise ValueError(f"unsupported stage: {job.stage}")
        except Exception as exc:  # noqa: BLE001
            self._retry_or_fail(db, job, str(exc))
            self._log_event(db, meeting.id, "worker", "parse_job_failed", {"error": str(exc), "stage": job.stage.value})
        else:
            job.status = ParseJobStatus.SUCCESS
            job.last_error = None
            db.commit()

    def _handle_result_parse(self, db: Session, meeting: Meeting, job: ParseJob) -> None:
        task_info = self.tingwu.get_task_info(job.task_id)
        status = self.tingwu.extract_task_status(task_info)
        self._log_event(db, meeting.id, "worker", "task_info_for_parse", task_info)

        if status != "COMPLETED":
            raise RuntimeError(f"task_not_completed: {status}")

        result_urls = self.tingwu.extract_result_urls(task_info)
        parsed = self.parser.parse(task_info, result_urls)

        db.query(MeetingTranscriptFinal).filter(MeetingTranscriptFinal.meeting_id == meeting.id).delete()

        for seg in parsed["segments"]:
            db.add(
                MeetingTranscriptFinal(
                    meeting_id=meeting.id,
                    segment_order=seg["segment_order"],
                    speaker=seg.get("speaker"),
                    start_ms=seg.get("start_ms"),
                    end_ms=seg.get("end_ms"),
                    confidence=seg.get("confidence"),
                    text=seg["text"],
                )
            )

        summary = db.scalar(select(MeetingSummary).where(MeetingSummary.meeting_id == meeting.id))
        content = parsed["summary"]
        if not summary:
            summary = MeetingSummary(
                meeting_id=meeting.id,
                overview=content["overview"],
                chapters=content["chapters"],
                decisions=content["decisions"],
                highlights=content["highlights"],
                todos=content["todos"],
                raw_result_refs=parsed["raw_result_refs"],
            )
            db.add(summary)
        else:
            summary.overview = content["overview"]
            summary.chapters = content["chapters"]
            summary.decisions = content["decisions"]
            summary.highlights = content["highlights"]
            summary.todos = content["todos"]
            summary.raw_result_refs = parsed["raw_result_refs"]
            summary.version += 1

        meeting.status = MeetingStatus.COMPLETED
        meeting.processing_progress = 100
        db.commit()

        self.ensure_parse_job(db, meeting.id, meeting.task_id or "", ParseJobStage.KNOWLEDGE_LINK)
        self._log_event(db, meeting.id, "worker", "parse_result_success", {"segments": len(parsed["segments"])})

    def _handle_knowledge_link(self, db: Session, meeting: Meeting, job: ParseJob) -> None:
        summary = db.scalar(select(MeetingSummary).where(MeetingSummary.meeting_id == meeting.id))
        if not summary:
            raise RuntimeError("summary_not_found")

        query = summary.overview[:180] if summary.overview else meeting.title
        links = self.knowledge.search(query=query, top_k=5)
        summary.knowledge_links = links
        summary.version += 1
        db.commit()

    def _retry_or_fail(self, db: Session, job: ParseJob, error: str) -> None:
        job.retry_count += 1
        job.last_error = error
        if job.retry_count >= job.max_retries:
            job.status = ParseJobStatus.FAILED
            db.commit()
            return

        backoff_seconds = [30, 120, 600][min(job.retry_count - 1, 2)]
        job.status = ParseJobStatus.PENDING
        job.next_retry_at = datetime.utcnow() + timedelta(seconds=backoff_seconds)
        db.commit()

    def ensure_parse_job(
        self,
        db: Session,
        meeting_id: UUID,
        task_id: str,
        stage: ParseJobStage,
    ) -> ParseJob:
        existing = db.scalar(
            select(ParseJob).where(and_(ParseJob.meeting_id == meeting_id, ParseJob.stage == stage))
        )
        if existing:
            if existing.status == ParseJobStatus.FAILED and existing.retry_count < existing.max_retries:
                existing.status = ParseJobStatus.PENDING
                existing.next_retry_at = datetime.utcnow()
                db.commit()
            return existing

        job = ParseJob(
            meeting_id=meeting_id,
            task_id=task_id,
            stage=stage,
            status=ParseJobStatus.PENDING,
            next_retry_at=datetime.utcnow(),
        )
        db.add(job)
        db.commit()
        return job

    def _log_event(self, db: Session, meeting_id: UUID | None, source: str, event_type: str, payload: dict[str, Any]) -> None:
        db.add(TaskEventLog(meeting_id=meeting_id, source=source, event_type=event_type, payload=payload))
        db.commit()
