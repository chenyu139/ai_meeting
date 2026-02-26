from __future__ import annotations

from datetime import datetime
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import and_, select
from sqlalchemy.orm import Session

from app.database import get_db
from app.deps import get_current_user_id
from app.enums import ParseJobStage, ParseJobStatus
from app.models import Meeting, ParseJob
from app.schemas import ParseJobAdminResetRequest, ParseJobResponse

router = APIRouter(prefix="/admin", tags=["admin"])


@router.post("/meetings/{meeting_id}/parse-jobs/reset", response_model=ParseJobResponse)
def reset_parse_job(
    meeting_id: UUID,
    payload: ParseJobAdminResetRequest,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> ParseJobResponse:
    meeting = db.get(Meeting, meeting_id)
    if not meeting:
        raise HTTPException(status_code=404, detail="Meeting not found")
    if meeting.owner_id != user_id:
        raise HTTPException(status_code=403, detail="Forbidden")

    try:
        stage = ParseJobStage(payload.stage)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="Invalid stage") from exc

    job = db.scalar(
        select(ParseJob).where(and_(ParseJob.meeting_id == meeting.id, ParseJob.stage == stage))
    )
    if not job:
        raise HTTPException(status_code=404, detail="Parse job not found")

    job.status = ParseJobStatus.PENDING
    job.next_retry_at = datetime.utcnow()
    job.last_error = None
    db.commit()

    return ParseJobResponse(
        meeting_id=meeting.id,
        stage=job.stage.value,
        status=job.status,
        retry_count=job.retry_count,
    )
