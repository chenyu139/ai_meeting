package com.gczm.aimeeting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gczm.aimeeting.entity.MeetingEntity;
import com.gczm.aimeeting.entity.MeetingSummaryEntity;
import com.gczm.aimeeting.entity.MeetingTranscriptFinalEntity;
import com.gczm.aimeeting.entity.ParseJobEntity;
import com.gczm.aimeeting.enums.MeetingStatus;
import com.gczm.aimeeting.enums.ParseJobStage;
import com.gczm.aimeeting.enums.ParseJobStatus;
import com.gczm.aimeeting.mapper.*;
import com.gczm.aimeeting.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerService {

    private final MeetingMapper meetingMapper;
    private final ParseJobMapper parseJobMapper;
    private final MeetingTranscriptFinalMapper meetingTranscriptFinalMapper;
    private final MeetingSummaryMapper meetingSummaryMapper;
    private final TingwuClient tingwuClient;
    private final TingwuResultParser tingwuResultParser;
    private final KnowledgeService knowledgeService;
    private final TaskEventLogService taskEventLogService;
    private final ObjectMapper objectMapper;
    private final com.gczm.aimeeting.config.AppProperties appProperties;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    public void runOnce() {
        pollProcessingMeetings();
        processParseJobs(10);
    }

    @Transactional
    public void pollProcessingMeetings() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(appProperties.getWorker().getProcessingScanIntervalSec());
        List<MeetingEntity> meetings = meetingMapper.selectList(
                Wrappers.<MeetingEntity>lambdaQuery()
                        .eq(MeetingEntity::getStatus, MeetingStatus.PROCESSING.name())
                        .le(MeetingEntity::getUpdatedAt, cutoff)
                        .isNull(MeetingEntity::getDeletedAt)
        );

        for (MeetingEntity meeting : meetings) {
            Map<String, Object> taskInfo = tingwuClient.getTaskInfo(meeting.getTaskId());
            String status = tingwuClient.extractTaskStatus(taskInfo);
            taskEventLogService.log(meeting.getId(), "worker", "poll_task_info", taskInfo);

            if ("COMPLETED".equals(status)) {
                int progress = meeting.getProcessingProgress() == null ? 0 : meeting.getProcessingProgress();
                meeting.setProcessingProgress(Math.max(progress, 80));
                meetingMapper.updateById(meeting);
                ensureParseJob(meeting.getId(), meeting.getTaskId(), ParseJobStage.RESULT_PARSE);
            } else if ("FAILED".equals(status)) {
                meeting.setStatus(MeetingStatus.FAILED.name());
                meeting.setProcessingProgress(0);
                meetingMapper.updateById(meeting);
            }
        }
    }

    @Transactional
    public void processParseJobs(int limit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleRunningAt = now.minusSeconds(appProperties.getWorker().getTaskTimeoutSec());

        LambdaQueryWrapper<ParseJobEntity> wrapper = Wrappers.<ParseJobEntity>lambdaQuery()
                .and(w -> w.eq(ParseJobEntity::getStatus, ParseJobStatus.PENDING.name())
                        .or(w2 -> w2.eq(ParseJobEntity::getStatus, ParseJobStatus.RUNNING.name())
                                .le(ParseJobEntity::getLockedAt, staleRunningAt)))
                .le(ParseJobEntity::getNextRetryAt, now)
                .orderByAsc(ParseJobEntity::getCreatedAt)
                .last("limit " + limit);

        List<ParseJobEntity> jobs = parseJobMapper.selectList(wrapper);
        for (ParseJobEntity job : jobs) {
            String lockKey = "parse_job:" + job.getId();
            if (!acquireLock(lockKey, 120)) {
                continue;
            }
            try {
                processSingleJob(job);
            } finally {
                releaseLock(lockKey);
            }
        }
    }

    @Transactional
    public ParseJobEntity ensureParseJob(String meetingId, String taskId, ParseJobStage stage) {
        ParseJobEntity existing = parseJobMapper.selectOne(
                Wrappers.<ParseJobEntity>lambdaQuery()
                        .eq(ParseJobEntity::getMeetingId, meetingId)
                        .eq(ParseJobEntity::getStage, stage.name())
                        .last("limit 1")
        );
        if (existing != null) {
            if (ParseJobStatus.FAILED.name().equals(existing.getStatus())
                    && existing.getRetryCount() < existing.getMaxRetries()) {
                existing.setStatus(ParseJobStatus.PENDING.name());
                existing.setNextRetryAt(LocalDateTime.now());
                parseJobMapper.updateById(existing);
            }
            return existing;
        }

        ParseJobEntity job = new ParseJobEntity();
        job.setMeetingId(meetingId);
        job.setTaskId(taskId);
        job.setStage(stage.name());
        job.setStatus(ParseJobStatus.PENDING.name());
        job.setRetryCount(0);
        job.setMaxRetries(3);
        job.setNextRetryAt(LocalDateTime.now());
        job.setPayload("{}");
        try {
            parseJobMapper.insert(job);
        } catch (DuplicateKeyException ignored) {
            return parseJobMapper.selectOne(
                    Wrappers.<ParseJobEntity>lambdaQuery()
                            .eq(ParseJobEntity::getMeetingId, meetingId)
                            .eq(ParseJobEntity::getStage, stage.name())
                            .last("limit 1")
            );
        }
        return job;
    }

    @Transactional
    public void processSingleJob(ParseJobEntity job) {
        MeetingEntity meeting = meetingMapper.selectById(job.getMeetingId());
        if (meeting == null || meeting.getDeletedAt() != null) {
            job.setStatus(ParseJobStatus.FAILED.name());
            job.setLastError("meeting_not_found");
            parseJobMapper.updateById(job);
            return;
        }

        job.setStatus(ParseJobStatus.RUNNING.name());
        job.setLockedBy(resolveWorkerId());
        job.setLockedAt(LocalDateTime.now());
        parseJobMapper.updateById(job);

        try {
            if (ParseJobStage.RESULT_PARSE.name().equals(job.getStage())) {
                handleResultParse(meeting, job);
            } else if (ParseJobStage.KNOWLEDGE_LINK.name().equals(job.getStage())) {
                handleKnowledgeLink(meeting, job);
            } else {
                throw new IllegalArgumentException("unsupported stage: " + job.getStage());
            }

            job.setStatus(ParseJobStatus.SUCCESS.name());
            job.setLastError(null);
            parseJobMapper.updateById(job);
        } catch (Exception ex) {
            retryOrFail(job, ex.getMessage());
            log.warn("parse job failed: meetingId={}, stage={}, retryCount={}, error={}",
                    meeting.getId(), job.getStage(), job.getRetryCount(), ex.getMessage(), ex);
            taskEventLogService.log(meeting.getId(), "worker", "parse_job_failed", Map.of(
                    "error", ex.getMessage(),
                    "stage", job.getStage()
            ));
        }
    }

    @Transactional
    public void handleResultParse(MeetingEntity meeting, ParseJobEntity job) {
        Map<String, Object> taskInfo = tingwuClient.getTaskInfo(job.getTaskId());
        String status = tingwuClient.extractTaskStatus(taskInfo);
        taskEventLogService.log(meeting.getId(), "worker", "task_info_for_parse", taskInfo);

        if (!"COMPLETED".equals(status)) {
            throw new RuntimeException("task_not_completed: " + status);
        }

        Map<String, String> resultUrls = tingwuClient.extractResultUrls(taskInfo);
        Map<String, Object> parsed = tingwuResultParser.parse(taskInfo, resultUrls);

        meetingTranscriptFinalMapper.delete(
                Wrappers.<MeetingTranscriptFinalEntity>lambdaQuery()
                        .eq(MeetingTranscriptFinalEntity::getMeetingId, meeting.getId())
        );

        List<Map<String, Object>> segments = castListMap(parsed.get("segments"));
        for (Map<String, Object> seg : segments) {
            MeetingTranscriptFinalEntity entity = new MeetingTranscriptFinalEntity();
            entity.setMeetingId(meeting.getId());
            entity.setSegmentOrder(asInt(seg.get("segment_order"), 0));
            entity.setSpeaker(asString(seg.get("speaker")));
            entity.setStartMs(asInt(seg.get("start_ms"), null));
            entity.setEndMs(asInt(seg.get("end_ms"), null));
            entity.setConfidence(asInt(seg.get("confidence"), null));
            entity.setText(asString(seg.get("text")));
            meetingTranscriptFinalMapper.insert(entity);
        }

        Map<String, Object> summary = castMap(parsed.get("summary"));
        MeetingSummaryEntity summaryEntity = meetingSummaryMapper.selectOne(
                Wrappers.<MeetingSummaryEntity>lambdaQuery()
                        .eq(MeetingSummaryEntity::getMeetingId, meeting.getId())
                        .last("limit 1")
        );

        if (summaryEntity == null) {
            summaryEntity = new MeetingSummaryEntity();
            summaryEntity.setMeetingId(meeting.getId());
            summaryEntity.setVersion(1);
            applySummary(summaryEntity, summary, castMap(parsed.get("raw_result_refs")));
            meetingSummaryMapper.insert(summaryEntity);
        } else {
            applySummary(summaryEntity, summary, castMap(parsed.get("raw_result_refs")));
            summaryEntity.setVersion(summaryEntity.getVersion() + 1);
            meetingSummaryMapper.updateById(summaryEntity);
        }

        meeting.setStatus(MeetingStatus.COMPLETED.name());
        meeting.setProcessingProgress(100);
        meetingMapper.updateById(meeting);

        ensureParseJob(meeting.getId(), meeting.getTaskId(), ParseJobStage.KNOWLEDGE_LINK);
        taskEventLogService.log(meeting.getId(), "worker", "parse_result_success", Map.of("segments", segments.size()));
    }

    @Transactional
    public void handleKnowledgeLink(MeetingEntity meeting, ParseJobEntity job) {
        MeetingSummaryEntity summary = meetingSummaryMapper.selectOne(
                Wrappers.<MeetingSummaryEntity>lambdaQuery()
                        .eq(MeetingSummaryEntity::getMeetingId, meeting.getId())
                        .last("limit 1")
        );
        if (summary == null) {
            throw new RuntimeException("summary_not_found");
        }

        String overview = summary.getOverview();
        String query = (overview == null || overview.isBlank()) ? meeting.getTitle()
                : overview.substring(0, Math.min(overview.length(), 180));

        List<Map<String, Object>> links = knowledgeService.search(query, 5);
        summary.setKnowledgeLinks(Jsons.toJson(objectMapper, links));
        summary.setVersion(summary.getVersion() + 1);
        meetingSummaryMapper.updateById(summary);
    }

    @Transactional
    public void retryOrFail(ParseJobEntity job, String error) {
        int retryCount = (job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1;
        job.setRetryCount(retryCount);
        job.setLastError(error);

        if (retryCount >= job.getMaxRetries()) {
            job.setStatus(ParseJobStatus.FAILED.name());
            parseJobMapper.updateById(job);
            return;
        }

        int backoff = switch (Math.min(retryCount, 3)) {
            case 1 -> 30;
            case 2 -> 120;
            default -> 600;
        };

        job.setStatus(ParseJobStatus.PENDING.name());
        job.setNextRetryAt(LocalDateTime.now().plusSeconds(backoff));
        parseJobMapper.updateById(job);
    }

    private void applySummary(MeetingSummaryEntity summaryEntity,
                              Map<String, Object> summary,
                              Map<String, Object> rawResultRefs) {
        summaryEntity.setOverview(asString(summary.getOrDefault("overview", "")));
        summaryEntity.setChapters(Jsons.toJson(objectMapper, summary.getOrDefault("chapters", List.of())));
        summaryEntity.setDecisions(Jsons.toJson(objectMapper, summary.getOrDefault("decisions", List.of())));
        summaryEntity.setHighlights(Jsons.toJson(objectMapper, summary.getOrDefault("highlights", List.of())));
        summaryEntity.setTodos(Jsons.toJson(objectMapper, summary.getOrDefault("todos", List.of())));
        summaryEntity.setRawResultRefs(Jsons.toJson(objectMapper, rawResultRefs));
        if (summaryEntity.getKnowledgeLinks() == null) {
            summaryEntity.setKnowledgeLinks("[]");
        }
    }

    private String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "worker";
        }
    }

    private boolean acquireLock(String key, int ttlSeconds) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return true;
        }
        try {
            ValueOperations<String, String> ops = redis.opsForValue();
            Boolean ok = ops.setIfAbsent(key, "1", ttlSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception ex) {
            log.warn("redis lock unavailable, fallback local execution: {}", ex.getMessage());
            return true;
        }
    }

    private void releaseLock(String key) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(key);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castListMap(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private Integer asInt(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
