package com.gczm.aimeeting.dto;

import lombok.Data;

import java.util.List;

@Data
public class MeetingSummaryUpdateRequest {
    private String overview;
    private List<Object> chapters;
    private List<Object> decisions;
    private List<Object> highlights;
    private List<Object> todos;
}
