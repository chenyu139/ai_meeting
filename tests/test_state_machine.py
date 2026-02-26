import pytest
from fastapi import HTTPException

from app.api.meetings import transition
from app.enums import MeetingStatus
from app.models import Meeting


@pytest.mark.parametrize(
    "current,next_status",
    [
        (MeetingStatus.RECORDING, MeetingStatus.PAUSED),
        (MeetingStatus.PAUSED, MeetingStatus.RECORDING),
        (MeetingStatus.PAUSED, MeetingStatus.PROCESSING),
        (MeetingStatus.PROCESSING, MeetingStatus.COMPLETED),
    ],
)
def test_valid_transitions(current: MeetingStatus, next_status: MeetingStatus) -> None:
    meeting = Meeting(owner_id="u1", title="t", status=current)
    transition(meeting, next_status)
    assert meeting.status == next_status


def test_invalid_transition_raises() -> None:
    meeting = Meeting(owner_id="u1", title="t", status=MeetingStatus.RECORDING)
    with pytest.raises(HTTPException):
        transition(meeting, MeetingStatus.COMPLETED)
