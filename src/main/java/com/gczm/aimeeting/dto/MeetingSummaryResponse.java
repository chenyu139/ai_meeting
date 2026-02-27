package com.gczm.aimeeting.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class MeetingSummaryResponse {
    private String meetingId;
    private String overview;
    private List<Object> chapters;
    private List<Object> decisions;
    private List<Object> highlights;
    private List<Object> todos;
    private List<Object> knowledgeLinks;
    private Map<String, Object> rawResultRefs;
    private Integer version;
}
