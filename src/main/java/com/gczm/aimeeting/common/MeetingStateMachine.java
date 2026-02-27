package com.gczm.aimeeting.common;

import com.gczm.aimeeting.entity.MeetingEntity;
import com.gczm.aimeeting.enums.MeetingStatus;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;

public final class MeetingStateMachine {

    private static final Map<MeetingStatus, Set<MeetingStatus>> ALLOWED_TRANSITIONS = Map.of(
            MeetingStatus.RECORDING, Set.of(MeetingStatus.PAUSED, MeetingStatus.PROCESSING, MeetingStatus.DELETED),
            MeetingStatus.PAUSED, Set.of(MeetingStatus.RECORDING, MeetingStatus.PROCESSING, MeetingStatus.DELETED),
            MeetingStatus.PROCESSING, Set.of(MeetingStatus.COMPLETED, MeetingStatus.FAILED, MeetingStatus.DELETED),
            MeetingStatus.COMPLETED, Set.of(MeetingStatus.DELETED),
            MeetingStatus.FAILED, Set.of(MeetingStatus.PROCESSING, MeetingStatus.DELETED),
            MeetingStatus.DELETED, Set.of()
    );

    private MeetingStateMachine() {
    }

    public static void transition(MeetingEntity meeting, MeetingStatus nextStatus) {
        MeetingStatus current = MeetingStatus.valueOf(meeting.getStatus());
        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(nextStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "STATE_INVALID_TRANSITION: " + current + " -> " + nextStatus);
        }
        meeting.setStatus(nextStatus.name());
    }
}
