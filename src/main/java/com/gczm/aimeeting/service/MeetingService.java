package com.gczm.aimeeting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gczm.aimeeting.common.ApiException;
import com.gczm.aimeeting.common.MeetingStateMachine;
import com.gczm.aimeeting.config.AppProperties;
import com.gczm.aimeeting.dto.*;
import com.gczm.aimeeting.entity.*;
import com.gczm.aimeeting.enums.MeetingStatus;
import com.gczm.aimeeting.enums.ParseJobStage;
import com.gczm.aimeeting.mapper.*;
import com.gczm.aimeeting.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MeetingService {

    private final MeetingMapper meetingMapper;
    private final MeetingAudioAssetMapper meetingAudioAssetMapper;
    private final MeetingSummaryMapper meetingSummaryMapper;
    private final ShareAccessLogMapper shareAccessLogMapper;
    private final TingwuClient tingwuClient;
    private final StorageService storageService;
    private final TaskEventLogService taskEventLogService;
    private final IdempotencyService idempotencyService;
    private final AppProperties appProperties;
    private final WorkerService workerService;
    private final ObjectMapper objectMapper;

    @Transactional
    public MeetingCreateResponse createMeeting(String userId, MeetingCreateRequest payload, String idempotencyKey) {
        String reqHash = idempotencyService.buildRequestHash(payload);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyService.CachedResponse> cached = idempotencyService.load(
                    idempotencyKey,
                    "POST:/meetings",
                    userId,
                    reqHash
            );
            if (cached.isPresent()) {
                return objectMapper.convertValue(cached.get().body(), MeetingCreateResponse.class);
            }
        }

        MeetingEntity meeting = new MeetingEntity();
        meeting.setOwnerId(userId);
        meeting.setTitle(payload.getTitle() == null || payload.getTitle().isBlank() ? "新录音" : payload.getTitle());
        meeting.setStatus(MeetingStatus.RECORDING.name());
        meeting.setProcessingProgress(0);
        meeting.setStartedAt(LocalDateTime.now());
        meeting.setDurationSec(0);
        meetingMapper.insert(meeting);

        String callbackUrl = appProperties.getTingwu().getCallbackBaseUrl().replaceAll("/+$", "")
                + appProperties.getApiPrefix() + "/callbacks/tingwu";
        Map<String, Object> task = tingwuClient.createRealtimeTask(meeting.getId(), meeting.getTitle(), callbackUrl);

        meeting.setTaskId((String) task.get("task_id"));
        meeting.setMeetingJoinUrl((String) task.get("meeting_join_url"));
        meetingMapper.updateById(meeting);

        taskEventLogService.log(meeting.getId(), "system", "meeting_created", Map.of("task_id", meeting.getTaskId()));

        MeetingCreateResponse response = MeetingCreateResponse.builder()
                .meetingId(meeting.getId())
                .taskId(meeting.getTaskId())
                .meetingJoinUrl(meeting.getMeetingJoinUrl())
                .status(MeetingStatus.valueOf(meeting.getStatus()))
                .build();

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Map<String, Object> responseBody = objectMapper.convertValue(response, new TypeReference<>() {
            });
            idempotencyService.save(idempotencyKey, "POST:/meetings", userId, reqHash, responseBody, 200);
        }
        return response;
    }

    @Transactional
    public Map<String, Object> pauseMeeting(String meetingId, String userId) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);
        transition(meeting, MeetingStatus.PAUSED);
        meetingMapper.updateById(meeting);
        taskEventLogService.log(meeting.getId(), "client", "pause", Map.of());
        return Map.of("ok", true, "status", MeetingStatus.valueOf(meeting.getStatus()));
    }

    @Transactional
    public Map<String, Object> resumeMeeting(String meetingId, String userId) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);
        transition(meeting, MeetingStatus.RECORDING);
        meetingMapper.updateById(meeting);
        taskEventLogService.log(meeting.getId(), "client", "resume", Map.of());
        return Map.of("ok", true, "status", MeetingStatus.valueOf(meeting.getStatus()));
    }

    @Transactional
    public Map<String, Object> finishMeeting(String meetingId, String userId, String idempotencyKey) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);

        String reqHash = idempotencyService.buildRequestHash(Map.of("meeting_id", meetingId, "action", "finish"));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyService.CachedResponse> cached = idempotencyService.load(
                    idempotencyKey,
                    "POST:/meetings/{id}/finish",
                    userId,
                    reqHash
            );
            if (cached.isPresent()) {
                return cached.get().body();
            }
        }

        transition(meeting, MeetingStatus.PROCESSING);
        meeting.setProcessingProgress(10);
        meeting.setEndedAt(LocalDateTime.now());

        Map<String, Object> result = tingwuClient.stopTask(meeting.getTaskId());
        if (!(result.get("ok") instanceof Boolean ok && ok)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to stop Tingwu task");
        }

        meetingMapper.updateById(meeting);
        taskEventLogService.log(meeting.getId(), "client", "finish", result);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("status", MeetingStatus.valueOf(meeting.getStatus()));
        body.put("task_id", meeting.getTaskId());

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.save(
                    idempotencyKey,
                    "POST:/meetings/{id}/finish",
                    userId,
                    reqHash,
                    body,
                    200
            );
        }

        return body;
    }

    @Transactional
    public AudioChunkUploadResponse uploadAudioChunk(String meetingId,
                                                     int chunkIndex,
                                                     MultipartFile file,
                                                     String userId) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);

        StorageService.UploadResult result = storageService.uploadAudioChunk(meetingId, chunkIndex, file);

        MeetingAudioAssetEntity asset = new MeetingAudioAssetEntity();
        asset.setMeetingId(meetingId);
        asset.setChunkIndex(chunkIndex);
        asset.setObjectKey(result.objectKey());
        asset.setContentType(file.getContentType() == null ? "audio/m4a" : file.getContentType());
        asset.setSizeBytes(result.sizeBytes());
        meetingAudioAssetMapper.insert(asset);

        return new AudioChunkUploadResponse(result.objectKey(), result.sizeBytes());
    }

    public List<MeetingListItemResponse> listMeetings(String tab, String userId) {
        List<String> sharedMeetingIds = shareAccessLogMapper.selectList(
                        Wrappers.<ShareAccessLogEntity>lambdaQuery()
                                .eq(ShareAccessLogEntity::getViewerId, userId))
                .stream().map(ShareAccessLogEntity::getMeetingId).distinct().toList();

        LambdaQueryWrapper<MeetingEntity> wrapper = Wrappers.<MeetingEntity>lambdaQuery()
                .isNull(MeetingEntity::getDeletedAt);

        if ("mine".equals(tab)) {
            wrapper.eq(MeetingEntity::getOwnerId, userId);
        } else if ("shared".equals(tab)) {
            if (sharedMeetingIds.isEmpty()) {
                return List.of();
            }
            wrapper.in(MeetingEntity::getId, sharedMeetingIds)
                    .ne(MeetingEntity::getOwnerId, userId);
        } else {
            if (sharedMeetingIds.isEmpty()) {
                wrapper.eq(MeetingEntity::getOwnerId, userId);
            } else {
                wrapper.and(w -> w.eq(MeetingEntity::getOwnerId, userId)
                        .or()
                        .in(MeetingEntity::getId, sharedMeetingIds));
            }
        }

        List<MeetingEntity> meetings = meetingMapper.selectList(wrapper.orderByDesc(MeetingEntity::getStartedAt));
        return meetings.stream().map(this::toListItem).toList();
    }

    public MeetingDetailResponse getMeetingDetail(String meetingId, String userId) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);

        if (!Objects.equals(meeting.getOwnerId(), userId)) {
            ShareAccessLogEntity shared = shareAccessLogMapper.selectOne(
                    Wrappers.<ShareAccessLogEntity>lambdaQuery()
                            .eq(ShareAccessLogEntity::getMeetingId, meetingId)
                            .eq(ShareAccessLogEntity::getViewerId, userId)
                            .last("limit 1")
            );
            if (shared == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
            }
        }

        int duration = meeting.getDurationSec() == null ? 0 : meeting.getDurationSec();
        if (meeting.getEndedAt() != null && meeting.getStartedAt() != null) {
            duration = (int) Duration.between(meeting.getStartedAt(), meeting.getEndedAt()).getSeconds();
        }

        return MeetingDetailResponse.builder()
                .id(meeting.getId())
                .title(meeting.getTitle())
                .ownerId(meeting.getOwnerId())
                .status(MeetingStatus.valueOf(meeting.getStatus()))
                .startedAt(meeting.getStartedAt())
                .endedAt(meeting.getEndedAt())
                .durationSec(duration)
                .taskId(meeting.getTaskId())
                .meetingJoinUrl(meeting.getMeetingJoinUrl())
                .processingProgress(meeting.getProcessingProgress())
                .build();
    }

    public MeetingSummaryResponse getMeetingSummary(String meetingId, String userId) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);

        MeetingSummaryEntity summary = meetingSummaryMapper.selectOne(
                Wrappers.<MeetingSummaryEntity>lambdaQuery()
                        .eq(MeetingSummaryEntity::getMeetingId, meetingId)
                        .last("limit 1"));
        if (summary == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Summary not found");
        }

        return toSummaryResponse(summary);
    }

    @Transactional
    public MeetingSummaryResponse updateMeetingSummary(String meetingId,
                                                       String userId,
                                                       MeetingSummaryUpdateRequest payload) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);

        MeetingSummaryEntity summary = meetingSummaryMapper.selectOne(
                Wrappers.<MeetingSummaryEntity>lambdaQuery()
                        .eq(MeetingSummaryEntity::getMeetingId, meetingId)
                        .last("limit 1"));
        if (summary == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Summary not found");
        }

        if (payload.getOverview() != null) {
            summary.setOverview(payload.getOverview());
        }
        if (payload.getChapters() != null) {
            summary.setChapters(Jsons.toJson(objectMapper, payload.getChapters()));
        }
        if (payload.getDecisions() != null) {
            summary.setDecisions(Jsons.toJson(objectMapper, payload.getDecisions()));
        }
        if (payload.getHighlights() != null) {
            summary.setHighlights(Jsons.toJson(objectMapper, payload.getHighlights()));
        }
        if (payload.getTodos() != null) {
            summary.setTodos(Jsons.toJson(objectMapper, payload.getTodos()));
        }
        summary.setVersion(summary.getVersion() + 1);
        meetingSummaryMapper.updateById(summary);

        return toSummaryResponse(summary);
    }

    public Map<String, Object> getAudioPlayback(String meetingId, String userId) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);

        MeetingAudioAssetEntity asset = meetingAudioAssetMapper.selectOne(
                Wrappers.<MeetingAudioAssetEntity>lambdaQuery()
                        .eq(MeetingAudioAssetEntity::getMeetingId, meetingId)
                        .orderByDesc(MeetingAudioAssetEntity::getChunkIndex)
                        .last("limit 1")
        );
        if (asset == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Audio not found");
        }

        return Map.of("url", storageService.getObjectUrl(asset.getObjectKey()));
    }

    public Map<String, Object> getLiveTranscript(String meetingId, String userId) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);
        if (meeting.getTaskId() == null || meeting.getTaskId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Task not found");
        }

        Map<String, Object> taskInfo = tingwuClient.getTaskInfo(meeting.getTaskId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", meeting.getTaskId());
        out.put("task_status", tingwuClient.extractTaskStatus(taskInfo));
        out.put("task_info", taskInfo);
        return out;
    }

    @Transactional
    public Map<String, Object> deleteMeeting(String meetingId, String userId) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);

        transition(meeting, MeetingStatus.DELETED);
        meeting.setDeletedAt(LocalDateTime.now());
        meetingMapper.updateById(meeting);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> retryProcessing(String meetingId, String userId) {
        MeetingEntity meeting = getNotDeletedMeeting(meetingId);
        ensureOwner(meeting, userId);

        if (!MeetingStatus.FAILED.name().equals(meeting.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Meeting is not FAILED");
        }

        transition(meeting, MeetingStatus.PROCESSING);
        meetingMapper.updateById(meeting);

        workerService.ensureParseJob(meeting.getId(), meeting.getTaskId(), ParseJobStage.RESULT_PARSE);
        return Map.of("ok", true, "status", MeetingStatus.valueOf(meeting.getStatus()));
    }

    public MeetingEntity getNotDeletedMeeting(String meetingId) {
        MeetingEntity meeting = meetingMapper.selectById(meetingId);
        if (meeting == null || meeting.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Meeting not found");
        }
        return meeting;
    }

    public void ensureOwner(MeetingEntity meeting, String userId) {
        if (!Objects.equals(meeting.getOwnerId(), userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    public void transition(MeetingEntity meeting, MeetingStatus nextStatus) {
        MeetingStateMachine.transition(meeting, nextStatus);
    }

    private MeetingListItemResponse toListItem(MeetingEntity meeting) {
        return MeetingListItemResponse.builder()
                .id(meeting.getId())
                .title(meeting.getTitle())
                .status(MeetingStatus.valueOf(meeting.getStatus()))
                .ownerId(meeting.getOwnerId())
                .startedAt(meeting.getStartedAt())
                .endedAt(meeting.getEndedAt())
                .processingProgress(meeting.getProcessingProgress())
                .build();
    }

    public MeetingSummaryResponse toSummaryResponse(MeetingSummaryEntity summary) {
        return MeetingSummaryResponse.builder()
                .meetingId(summary.getMeetingId())
                .overview(summary.getOverview() == null ? "" : summary.getOverview())
                .chapters(parseList(summary.getChapters()))
                .decisions(parseList(summary.getDecisions()))
                .highlights(parseList(summary.getHighlights()))
                .todos(parseList(summary.getTodos()))
                .knowledgeLinks(parseList(summary.getKnowledgeLinks()))
                .rawResultRefs(parseMap(summary.getRawResultRefs()))
                .version(summary.getVersion())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Object> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof List<?> list) {
                return (List<Object>) list;
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }
}
