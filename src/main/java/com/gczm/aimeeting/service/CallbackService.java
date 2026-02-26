package com.gczm.aimeeting.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gczm.aimeeting.common.ApiException;
import com.gczm.aimeeting.entity.MeetingEntity;
import com.gczm.aimeeting.enums.MeetingStatus;
import com.gczm.aimeeting.enums.ParseJobStage;
import com.gczm.aimeeting.mapper.MeetingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CallbackService {

    private final TingwuClient tingwuClient;
    private final MeetingMapper meetingMapper;
    private final WorkerService workerService;
    private final TaskEventLogService taskEventLogService;

    @Transactional
    public boolean handleTingwuCallback(Map<String, Object> payload) {
        Map<String, Object> event = tingwuClient.normalizeEvent(payload);
        String taskId = (String) event.get("task_id");
        if (taskId == null || taskId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing task_id");
        }

        MeetingEntity meeting = meetingMapper.selectOne(
                Wrappers.<MeetingEntity>lambdaQuery().eq(MeetingEntity::getTaskId, taskId).last("limit 1")
        );

        if (meeting == null) {
            taskEventLogService.log(null, "tingwu", "callback_unmatched", payload);
            return true;
        }

        taskEventLogService.log(meeting.getId(), "tingwu", "callback_received", payload);

        String status = String.valueOf(event.getOrDefault("task_status", "")).toUpperCase();
        if ("COMPLETED".equals(status)) {
            if (meeting.getStatus().equals(MeetingStatus.PROCESSING.name())
                    || meeting.getStatus().equals(MeetingStatus.FAILED.name())
                    || meeting.getStatus().equals(MeetingStatus.RECORDING.name())
                    || meeting.getStatus().equals(MeetingStatus.PAUSED.name())) {
                meeting.setStatus(MeetingStatus.PROCESSING.name());
                int progress = meeting.getProcessingProgress() == null ? 0 : meeting.getProcessingProgress();
                meeting.setProcessingProgress(Math.max(80, progress));
                meetingMapper.updateById(meeting);
                workerService.ensureParseJob(meeting.getId(), taskId, ParseJobStage.RESULT_PARSE);
            }
        } else if ("FAILED".equals(status)) {
            meeting.setStatus(MeetingStatus.FAILED.name());
            meeting.setProcessingProgress(0);
            meetingMapper.updateById(meeting);
        }

        return true;
    }

    public Map<String, Object> triggerWorkerScan() {
        workerService.runOnce();
        return Map.of("ok", true, "timestamp", LocalDateTime.now().toString());
    }
}
