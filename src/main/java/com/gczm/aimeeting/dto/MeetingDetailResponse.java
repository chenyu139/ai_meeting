package com.gczm.aimeeting.dto;

import com.gczm.aimeeting.enums.MeetingStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MeetingDetailResponse {
    private String id;
    private String title;
    private String ownerId;
    private MeetingStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer durationSec;
    private String taskId;
    private String meetingJoinUrl;
    private Integer processingProgress;
}
