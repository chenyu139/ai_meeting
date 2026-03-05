package com.gczm.aimeeting.dto;

import com.gczm.aimeeting.enums.MeetingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingCreateResponse {
    private String meetingId;
    private String taskId;
    private String meetingJoinUrl;
    private MeetingStatus status;
}
