package com.gczm.aimeeting.dto;

import com.gczm.aimeeting.enums.MeetingStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MeetingCreateResponse {
    private String meetingId;
    private String taskId;
    private String meetingJoinUrl;
    private MeetingStatus status;
}
