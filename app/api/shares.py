from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.encoders import jsonable_encoder
from sqlalchemy import and_, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.deps import get_current_user_id, get_optional_user_id
from app.enums import ShareStatus
from app.models import Meeting, MeetingSummary, ShareAccessLog, ShareLink
from app.schemas import (
    MeetingDetailResponse,
    MeetingSummaryResponse,
    ShareContentResponse,
    ShareLinkCreateRequest,
    ShareLinkCreateResponse,
    ShareVerifyRequest,
    ShareVerifyResponse,
)
from app.utils.idempotency import build_request_hash, load_idempotency, save_idempotency
from app.utils.security import generate_share_token, hash_passcode, verify_passcode

router = APIRouter(tags=["shares"])


@router.post("/meetings/{meeting_id}/share-links", response_model=ShareLinkCreateResponse)
def create_share_link(
    meeting_id: UUID,
    payload: ShareLinkCreateRequest,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
) -> ShareLinkCreateResponse:
    meeting = db.get(Meeting, meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")
    if meeting.owner_id != user_id:
        raise HTTPException(status_code=403, detail="Forbidden")

    req_hash = build_request_hash(payload.model_dump())
    if idempotency_key:
        cached = load_idempotency(
            db,
            key=idempotency_key,
            endpoint="POST:/meetings/{id}/share-links",
            user_id=user_id,
            request_hash=req_hash,
        )
        if cached:
            return ShareLinkCreateResponse(**cached["body"])

    expire_at = None if payload.expire_days == 0 else datetime.utcnow() + timedelta(days=payload.expire_days)
    token = generate_share_token()
    share = ShareLink(
        meeting_id=meeting.id,
        created_by=user_id,
        token=token,
        passcode_hash=hash_passcode(payload.passcode) if payload.passcode else None,
        expire_at=expire_at,
        status=ShareStatus.ACTIVE,
    )
    db.add(share)
    db.commit()

    settings = get_settings()
    share_url = f"{settings.public_base_url.rstrip('/')}/share/{token}"

    response = ShareLinkCreateResponse(
        token=share.token,
        share_url=share_url,
        expire_at=share.expire_at,
        status=share.status,
    )

    if idempotency_key:
        save_idempotency(
            db,
            key=idempotency_key,
            endpoint="POST:/meetings/{id}/share-links",
            user_id=user_id,
            request_hash=req_hash,
            response_body=jsonable_encoder(response),
            status_code=200,
        )
        db.commit()

    return response


@router.post("/share/{token}/verify", response_model=ShareVerifyResponse)
def verify_share_link(
    token: str,
    payload: ShareVerifyRequest,
    db: Session = Depends(get_db),
) -> ShareVerifyResponse:
    share = db.scalar(select(ShareLink).where(ShareLink.token == token))
    if not share:
        return ShareVerifyResponse(valid=False)

    if share.status != ShareStatus.ACTIVE:
        return ShareVerifyResponse(valid=False)

    if share.expire_at and share.expire_at < datetime.utcnow():
        return ShareVerifyResponse(valid=False)

    if share.passcode_hash:
        if not payload.passcode:
            return ShareVerifyResponse(valid=False)
        if not verify_passcode(payload.passcode, share.passcode_hash):
            return ShareVerifyResponse(valid=False)

    return ShareVerifyResponse(valid=True)


@router.get("/share/{token}/content", response_model=ShareContentResponse)
def get_share_content(
    token: str,
    request: Request,
    passcode: str | None = None,
    db: Session = Depends(get_db),
    user_id: str | None = Depends(get_optional_user_id),
) -> ShareContentResponse:
    share = db.scalar(select(ShareLink).where(ShareLink.token == token))
    if not share:
        raise HTTPException(status_code=404, detail="Share link not found")

    if share.status != ShareStatus.ACTIVE:
        raise HTTPException(status_code=403, detail="Share link revoked")

    if share.expire_at and share.expire_at < datetime.utcnow():
        raise HTTPException(status_code=403, detail="Share link expired")

    if share.passcode_hash and (not passcode or not verify_passcode(passcode, share.passcode_hash)):
        raise HTTPException(status_code=403, detail="Invalid passcode")

    meeting = db.get(Meeting, share.meeting_id)
    if not meeting or meeting.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Meeting not found")

    summary = db.scalar(select(MeetingSummary).where(MeetingSummary.meeting_id == meeting.id))

    db.add(
        ShareAccessLog(
            share_link_id=share.id,
            meeting_id=meeting.id,
            viewer_id=user_id,
            ip=request.client.host if request.client else None,
            user_agent=request.headers.get("user-agent"),
        )
    )
    db.commit()

    meeting_detail = MeetingDetailResponse(
        id=meeting.id,
        title=meeting.title,
        owner_id=meeting.owner_id,
        status=meeting.status,
        started_at=meeting.started_at,
        ended_at=meeting.ended_at,
        duration_sec=meeting.duration_sec,
        task_id=meeting.task_id,
        meeting_join_url=meeting.meeting_join_url,
        processing_progress=meeting.processing_progress,
    )

    summary_detail = None
    if summary:
        summary_detail = MeetingSummaryResponse(
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

    return ShareContentResponse(meeting=meeting_detail, summary=summary_detail)


@router.post("/share/{token}/revoke")
def revoke_share_link(
    token: str,
    db: Session = Depends(get_db),
    user_id: str = Depends(get_current_user_id),
) -> dict[str, Any]:
    share = db.scalar(select(ShareLink).where(ShareLink.token == token))
    if not share:
        raise HTTPException(status_code=404, detail="Share link not found")

    meeting = db.get(Meeting, share.meeting_id)
    if not meeting or meeting.owner_id != user_id:
        raise HTTPException(status_code=403, detail="Forbidden")

    share.status = ShareStatus.REVOKED
    db.commit()
    return {"ok": True}
