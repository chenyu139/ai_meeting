package com.gczm.aimeeting.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gczm.aimeeting.common.ApiException;
import com.gczm.aimeeting.dto.ParseJobResponse;
import com.gczm.aimeeting.entity.MeetingEntity;
import com.gczm.aimeeting.entity.ParseJobEntity;
import com.gczm.aimeeting.enums.ParseJobStage;
import com.gczm.aimeeting.enums.ParseJobStatus;
import com.gczm.aimeeting.mapper.MeetingMapper;
import com.gczm.aimeeting.mapper.ParseJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final MeetingMapper meetingMapper;
    private final ParseJobMapper parseJobMapper;
    private final WorkerService workerService;

    @Transactional
    public ParseJobResponse resetParseJob(String meetingId, String stage, String userId) {
        MeetingEntity meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Meeting not found");
        }
        if (!Objects.equals(meeting.getOwnerId(), userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        ParseJobStage parseJobStage;
        try {
            parseJobStage = ParseJobStage.valueOf(stage);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid stage");
        }

        ParseJobEntity job = parseJobMapper.selectOne(
                Wrappers.<ParseJobEntity>lambdaQuery()
                        .eq(ParseJobEntity::getMeetingId, meetingId)
                        .eq(ParseJobEntity::getStage, parseJobStage.name())
                        .last("limit 1")
        );
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Parse job not found");
        }

        job.setStatus(ParseJobStatus.PENDING.name());
        job.setNextRetryAt(LocalDateTime.now());
        job.setLastError(null);
        parseJobMapper.updateById(job);

        return ParseJobResponse.builder()
                .meetingId(meetingId)
                .stage(job.getStage())
                .status(ParseJobStatus.valueOf(job.getStatus()))
                .retryCount(job.getRetryCount())
                .build();
    }

    public Map<String, Object> triggerWorkerRunOnce() {
        workerService.runOnce();
        return Map.of("ok", true, "timestamp", LocalDateTime.now().toString());
    }
}
