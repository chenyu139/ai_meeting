from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import and_, select
from sqlalchemy.orm import Session

from app.database import get_db
from app.enums import MeetingStatus, ParseJobStage
from app.models import Meeting, ParseJob, TaskEventLog
from app.schemas import CallbackAckResponse
from app.services.tingwu_client import TaskEventNormalizer
from app.services.worker_service import WorkerService

router = APIRouter(prefix="/callbacks", tags=["callbacks"])


@router.post("/tingwu", response_model=CallbackAckResponse)
async def tingwu_callback(request: Request, db: Session = Depends(get_db)) -> CallbackAckResponse:
    try:
        payload = await request.json()
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail="Invalid callback payload") from exc

    event = TaskEventNormalizer.normalize(payload)
    task_id = event["task_id"]
    if not task_id:
        raise HTTPException(status_code=400, detail="Missing task_id")

    meeting = db.scalar(select(Meeting).where(Meeting.task_id == task_id))
    if not meeting:
        db.add(TaskEventLog(meeting_id=None, source="tingwu", event_type="callback_unmatched", payload=payload))
        db.commit()
        return CallbackAckResponse(ok=True)

    db.add(TaskEventLog(meeting_id=meeting.id, source="tingwu", event_type="callback_received", payload=payload))

    status = (event["task_status"] or "").upper()
    worker = WorkerService()

    if status == "COMPLETED":
        if meeting.status in {MeetingStatus.PROCESSING, MeetingStatus.FAILED, MeetingStatus.RECORDING, MeetingStatus.PAUSED}:
            meeting.status = MeetingStatus.PROCESSING
            meeting.processing_progress = 80
            worker.ensure_parse_job(db, meeting.id, task_id, ParseJobStage.RESULT_PARSE)
    elif status == "FAILED":
        meeting.status = MeetingStatus.FAILED
        meeting.processing_progress = 0

    db.commit()
    return CallbackAckResponse(ok=True)


@router.post("/poll/process")
def trigger_worker_scan(db: Session = Depends(get_db)) -> dict[str, Any]:
    worker = WorkerService()
    worker.run_once()
    return {"ok": True, "timestamp": datetime.utcnow().isoformat()}
