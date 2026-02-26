package com.gczm.aimeeting.service;

import com.gczm.aimeeting.common.ApiException;
import com.gczm.aimeeting.common.MeetingStateMachine;
import com.gczm.aimeeting.entity.MeetingEntity;
import com.gczm.aimeeting.enums.MeetingStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeetingStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "RECORDING,PAUSED",
            "PAUSED,RECORDING",
            "PAUSED,PROCESSING",
            "PROCESSING,COMPLETED"
    })
    void validTransitions(String current, String next) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setStatus(current);
        MeetingStateMachine.transition(meeting, MeetingStatus.valueOf(next));
        assertEquals(next, meeting.getStatus());
    }

    @ParameterizedTest
    @CsvSource({
            "RECORDING,COMPLETED",
            "COMPLETED,RECORDING",
            "DELETED,RECORDING"
    })
    void invalidTransitions(String current, String next) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setStatus(current);
        assertThrows(ApiException.class,
                () -> MeetingStateMachine.transition(meeting, MeetingStatus.valueOf(next)));
    }
}
