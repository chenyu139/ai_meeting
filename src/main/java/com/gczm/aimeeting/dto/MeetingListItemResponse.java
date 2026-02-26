package com.gczm.aimeeting.dto;

import com.gczm.aimeeting.enums.MeetingStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MeetingListItemResponse {
    private String id;
    private String title;
    private MeetingStatus status;
    private String ownerId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer processingProgress;
}
