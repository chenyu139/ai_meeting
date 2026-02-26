from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, File, Header, HTTPException, Query, Request, Response, UploadFile, status
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.deps import get_current_user_id
from app.enums import MeetingStatus, ParseJobStage
from app.models import Meeting, MeetingAudioAsset, MeetingSummary, ParseJob, ShareAccessLog, TaskEventLog
from app.schemas import (
    AudioChunkUploadResponse,
    MeetingCreateRequest,
    MeetingCreateResponse,
    MeetingDetailResponse,
    MeetingListItem,
    MeetingSummaryResponse,
    MeetingSummaryUpdateRequest,
)
from app.services.storage_service import StorageService
from app.services.tingwu_client import TingwuClient
from app.services.worker_service import WorkerService
from app.utils.idempotency import build_request_hash, load_idempotency, save_idempotency

router = APIRouter(prefix="/meetings", tags=["meetings"])


ALLOWED_TRANSITIONS = {
    MeetingStatus.RECORDING: {MeetingStatus.PAUSED, MeetingStatus.PROCESSING, MeetingStatus.DELETED},
    MeetingStatus.PAUSED: {MeetingStatus.RECORDING, MeetingStatus.PROCESSING, MeetingStatus.DELETED},
    MeetingStatus.PROCESSING: {MeetingStatus.COMPLETED, MeetingStatus.FAILED, MeetingStatus.DELETED},
    MeetingStatus.COMPLETED: {MeetingStatus.DELETED},
    MeetingStatus.FAILED: {MeetingStatus.PROCESSING, MeetingStatus.DELETED},
    MeetingStatus.DELETED: set(),
}


def ensure_owner(meeting: Meeting, user_id: str) -> None:
    if meeting.owner_id != user_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Forbidden")



def transition(meeting: Meeting, next_status: MeetingStatus) -> None:
    if next_status not in ALLOWED_TRANSITIONS[meeting.status]:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"STATE_INVALID_TRANSITION: {meeting.status} -> {next_status}",
        )
    meeting.status = next_status


@router.post("", response_model=MeetingCreateResponse)
def create_meeting(
    payload: MeetingCreateRequest,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
) -> MeetingCreateResponse:
    req_hash = build_request_hash(payload.model_dump())
    if idempotency_key:
        cached = load_idempotency(
            db,
            key=idempotency_key,
            endpoint="POST:/meetings",
            user_id=user_id,
            request_hash=req_hash,
        )
        if cached:
            return MeetingCreateResponse(**cached["body"])

    meeting = Meeting(owner_id=user_id, title=payload.title, status=MeetingStatus.RECORDING)
    db.add(meeting)
    db.flush()

    settings = get_settings()
    callback_url = f"{settings.tingwu_callback_base_url.rstrip('/')}{settings.api_prefix}/callbacks/tingwu"

    tingwu = TingwuClient()
    task = tingwu.create_realtime_task(task_key=str(meeting.id), title=meeting.title, webhook_url=callback_url)

    meeting.task_id = task["task_id"]
    meeting.meeting_join_url = task["meeting_join_url"]

    db.add(
        TaskEventLog(
            meeting_id=meeting.id,
            source="system",
            event_type="meeting_created",
            payload={"task_id": meeting.task_id},
        )
    )
    db.commit()

    response = MeetingCreateResponse(
        meeting_id=meeting.id,
        task_id=meeting.task_id,
        meeting_join_url=meeting.meeting_join_url,
        status=meeting.status,
    )

    if idempotency_key:
        save_idempotency(
            db,
            key=idempotency_key,
            endpoint="POST:/meetings",
            user_id=user_id,
            request_hash=req_hash,
            response_body=jsonable_encoder(response),
            status_code=200,
        )
        db.commit()

    return response


@router.post("/{meeting_id}/pause")
def pause_meeting(
    meeting_id: UUID,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> dict[str, Any]:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)
    transition(meeting, MeetingStatus.PAUSED)
    db.add(TaskEventLog(meeting_id=meeting.id, source="client", event_type="pause", payload={}))
    db.commit()
    return {"ok": True, "status": meeting.status}


@router.post("/{meeting_id}/resume")
def resume_meeting(
    meeting_id: UUID,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> dict[str, Any]:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)
    transition(meeting, MeetingStatus.RECORDING)
    db.add(TaskEventLog(meeting_id=meeting.id, source="client", event_type="resume", payload={}))
    db.commit()
    return {"ok": True, "status": meeting.status}


@router.post("/{meeting_id}/finish")
def finish_meeting(
    meeting_id: UUID,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
) -> dict[str, Any]:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)

    req_hash = build_request_hash({"meeting_id": str(meeting_id), "action": "finish"})
    if idempotency_key:
        cached = load_idempotency(
            db,
            key=idempotency_key,
            endpoint="POST:/meetings/{id}/finish",
            user_id=user_id,
            request_hash=req_hash,
        )
        if cached:
            return cached["body"]

    transition(meeting, MeetingStatus.PROCESSING)
    meeting.processing_progress = 10
    meeting.ended_at = datetime.utcnow()

    tingwu = TingwuClient()
    result = tingwu.stop_task(meeting.task_id or "")
    if not result.get("ok", True):
        raise HTTPException(status_code=502, detail="Failed to stop Tingwu task")

    db.add(TaskEventLog(meeting_id=meeting.id, source="client", event_type="finish", payload=result))
    db.commit()

    body = {"ok": True, "status": meeting.status, "task_id": meeting.task_id}

    if idempotency_key:
        save_idempotency(
            db,
            key=idempotency_key,
            endpoint="POST:/meetings/{id}/finish",
            user_id=user_id,
            request_hash=req_hash,
            response_body=body,
            status_code=200,
        )
        db.commit()

    return body


@router.post("/{meeting_id}/audio-chunks", response_model=AudioChunkUploadResponse)
def upload_audio_chunk(
    meeting_id: UUID,
    chunk_index: int = Query(..., ge=0),
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> AudioChunkUploadResponse:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)

    storage = StorageService()
    object_key, size = storage.upload_audio_chunk(str(meeting.id), chunk_index, file)

    db.add(
        MeetingAudioAsset(
            meeting_id=meeting.id,
            chunk_index=chunk_index,
            object_key=object_key,
            content_type=file.content_type or "audio/m4a",
            size_bytes=size,
        )
    )
    db.commit()

    return AudioChunkUploadResponse(object_key=object_key, size_bytes=size)


@router.get("", response_model=list[MeetingListItem])
def list_meetings(
    tab: str = Query(default="all", pattern="^(all|mine|shared)$"),
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> list[MeetingListItem]:
    shared_meeting_ids = select(ShareAccessLog.meeting_id).where(ShareAccessLog.viewer_id == user_id)

    if tab == "mine":
        stmt = select(Meeting).where(and_(Meeting.owner_id == user_id, Meeting.deleted_at.is_(None)))
    elif tab == "shared":
        stmt = select(Meeting).where(
            and_(
                Meeting.id.in_(shared_meeting_ids),
                Meeting.owner_id != user_id,
                Meeting.deleted_at.is_(None),
            )
        )
    else:
        stmt = select(Meeting).where(
            and_(
                or_(Meeting.owner_id == user_id, Meeting.id.in_(shared_meeting_ids)),
                Meeting.deleted_at.is_(None),
            )
        )

    meetings = db.scalars(stmt.order_by(Meeting.started_at.desc())).all()
    return [
        MeetingListItem(
            id=m.id,
            title=m.title,
            status=m.status,
            owner_id=m.owner_id,
            started_at=m.started_at,
            ended_at=m.ended_at,
            processing_progress=m.processing_progress,
        )
        for m in meetings
    ]


@router.get("/{meeting_id}", response_model=MeetingDetailResponse)
def get_meeting_detail(
    meeting_id: UUID,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> MeetingDetailResponse:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")

    if meeting.owner_id != user_id:
        shared = db.scalar(
            select(ShareAccessLog).where(
                and_(ShareAccessLog.meeting_id == meeting.id, ShareAccessLog.viewer_id == user_id)
            )
        )
        if not shared:
            raise HTTPException(status_code=403, detail="Forbidden")

    duration = meeting.duration_sec
    if meeting.ended_at:
        duration = int((meeting.ended_at - meeting.started_at).total_seconds())

    return MeetingDetailResponse(
        id=meeting.id,
        title=meeting.title,
        owner_id=meeting.owner_id,
        status=meeting.status,
        started_at=meeting.started_at,
        ended_at=meeting.ended_at,
        duration_sec=duration,
        task_id=meeting.task_id,
        meeting_join_url=meeting.meeting_join_url,
        processing_progress=meeting.processing_progress,
    )


@router.get("/{meeting_id}/summary", response_model=MeetingSummaryResponse)
def get_meeting_summary(
    meeting_id: UUID,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> MeetingSummaryResponse:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)

    summary = db.scalar(select(MeetingSummary).where(MeetingSummary.meeting_id == meeting.id))
    if not summary:
        raise HTTPException(status_code=404, detail="Summary not found")

    return MeetingSummaryResponse(
        meeting_id=meeting.id,
        overview=summary.overview,
        chapters=summary.chapters,
        decisions=summary.decisions,
        highlights=summary.highlights,
        todos=summary.todos,
        knowledge_links=summary.knowledge_links,
        raw_result_refs=summary.raw_result_refs,
        version=summary.version,
    )


@router.patch("/{meeting_id}/summary", response_model=MeetingSummaryResponse)
def update_meeting_summary(
    meeting_id: UUID,
    payload: MeetingSummaryUpdateRequest,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> MeetingSummaryResponse:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)

    summary = db.scalar(select(MeetingSummary).where(MeetingSummary.meeting_id == meeting.id))
    if not summary:
        raise HTTPException(status_code=404, detail="Summary not found")

    for field in ("overview", "chapters", "decisions", "highlights", "todos"):
        value = getattr(payload, field)
        if value is not None:
            setattr(summary, field, value)

    summary.version += 1
    db.commit()

    return MeetingSummaryResponse(
        meeting_id=meeting.id,
        overview=summary.overview,
        chapters=summary.chapters,
        decisions=summary.decisions,
        highlights=summary.highlights,
        todos=summary.todos,
        knowledge_links=summary.knowledge_links,
        raw_result_refs=summary.raw_result_refs,
        version=summary.version,
    )


@router.get("/{meeting_id}/audio-playback")
def get_audio_playback(
    meeting_id: UUID,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> dict[str, Any]:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)

    asset = db.scalar(
        select(MeetingAudioAsset)
        .where(MeetingAudioAsset.meeting_id == meeting.id)
        .order_by(MeetingAudioAsset.chunk_index.desc())
    )
    if not asset:
        raise HTTPException(status_code=404, detail="Audio not found")

    storage = StorageService()
    return {"url": storage.get_object_url(asset.object_key)}


@router.get("/{meeting_id}/transcript-live")
def get_live_transcript(
    meeting_id: UUID,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> dict[str, Any]:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)

    if not meeting.task_id:
        raise HTTPException(status_code=400, detail="Task not found")

    tingwu = TingwuClient()
    task_info = tingwu.get_task_info(meeting.task_id)
    return {
        "task_id": meeting.task_id,
        "task_status": tingwu.extract_task_status(task_info),
        "task_info": task_info,
    }


@router.delete("/{meeting_id}")
def delete_meeting(
    meeting_id: UUID,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> dict[str, Any]:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)

    transition(meeting, MeetingStatus.DELETED)
    meeting.deleted_at = datetime.utcnow()
    db.commit()
    return {"ok": True}


@router.post("/{meeting_id}/retry")
def retry_processing(
    meeting_id: UUID,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> dict[str, Any]:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    ensure_owner(meeting, user_id)

    if meeting.status != MeetingStatus.FAILED:
        raise HTTPException(status_code=409, detail="Meeting is not FAILED")

    transition(meeting, MeetingStatus.PROCESSING)
    worker = WorkerService()
    worker.ensure_parse_job(db, meeting.id, meeting.task_id or "", ParseJobStage.RESULT_PARSE)
    db.commit()
    return {"ok": True, "status": meeting.status}

