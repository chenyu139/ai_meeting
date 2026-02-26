package com.gczm.aimeeting.service;

import com.gczm.aimeeting.entity.TaskEventLogEntity;
import com.gczm.aimeeting.mapper.TaskEventLogMapper;
import com.gczm.aimeeting.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskEventLogService {

    private final TaskEventLogMapper taskEventLogMapper;
    private final ObjectMapper objectMapper;

    public void log(String meetingId, String source, String eventType, Map<String, Object> payload) {
        TaskEventLogEntity event = new TaskEventLogEntity();
        event.setMeetingId(meetingId);
        event.setSource(source);
        event.setEventType(eventType);
        event.setPayload(Jsons.toJson(objectMapper, payload));
        event.setCreatedAt(LocalDateTime.now());
        taskEventLogMapper.insert(event);
    }
}
