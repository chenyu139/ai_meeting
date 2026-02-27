package com.gczm.aimeeting.dto;

import com.gczm.aimeeting.enums.ParseJobStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ParseJobResponse {
    private String meetingId;
    private String stage;
    private ParseJobStatus status;
    private Integer retryCount;
}
