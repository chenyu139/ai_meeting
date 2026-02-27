package com.gczm.aimeeting.controller;

import com.gczm.aimeeting.common.Headers;
import com.gczm.aimeeting.dto.*;
import com.gczm.aimeeting.service.MeetingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${app.api-prefix:/api/v1}/meetings")
@RequiredArgsConstructor
@Validated
public class MeetingController {

    private final MeetingService meetingService;

    @PostMapping
    public MeetingCreateResponse createMeeting(
            @RequestBody MeetingCreateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return meetingService.createMeeting(Headers.requiredUserId(userId), request, idempotencyKey);
    }

    @PostMapping("/{meetingId}/pause")
    public Map<String, Object> pauseMeeting(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.pauseMeeting(meetingId, Headers.requiredUserId(userId));
    }

    @PostMapping("/{meetingId}/resume")
    public Map<String, Object> resumeMeeting(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.resumeMeeting(meetingId, Headers.requiredUserId(userId));
    }

    @PostMapping("/{meetingId}/finish")
    public Map<String, Object> finishMeeting(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return meetingService.finishMeeting(meetingId, Headers.requiredUserId(userId), idempotencyKey);
    }

    @PostMapping("/{meetingId}/audio-chunks")
    public AudioChunkUploadResponse uploadAudioChunk(
            @PathVariable String meetingId,
            @RequestParam("chunk_index") int chunkIndex,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.uploadAudioChunk(meetingId, chunkIndex, file, Headers.requiredUserId(userId));
    }

    @GetMapping
    public List<MeetingListItemResponse> listMeetings(
            @RequestParam(value = "tab", defaultValue = "all")
            @Pattern(regexp = "^(all|mine|shared)$") String tab,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.listMeetings(tab, Headers.requiredUserId(userId));
    }

    @GetMapping("/{meetingId}")
    public MeetingDetailResponse getMeetingDetail(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.getMeetingDetail(meetingId, Headers.requiredUserId(userId));
    }

    @GetMapping("/{meetingId}/summary")
    public MeetingSummaryResponse getMeetingSummary(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.getMeetingSummary(meetingId, Headers.requiredUserId(userId));
    }

    @PatchMapping("/{meetingId}/summary")
    public MeetingSummaryResponse updateMeetingSummary(
            @PathVariable String meetingId,
            @RequestBody MeetingSummaryUpdateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.updateMeetingSummary(meetingId, Headers.requiredUserId(userId), request);
    }

    @GetMapping("/{meetingId}/audio-playback")
    public Map<String, Object> getAudioPlayback(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.getAudioPlayback(meetingId, Headers.requiredUserId(userId));
    }

    @GetMapping("/{meetingId}/transcript-live")
    public Map<String, Object> getLiveTranscript(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.getLiveTranscript(meetingId, Headers.requiredUserId(userId));
    }

    @DeleteMapping("/{meetingId}")
    public Map<String, Object> deleteMeeting(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.deleteMeeting(meetingId, Headers.requiredUserId(userId));
    }

    @PostMapping("/{meetingId}/retry")
    public Map<String, Object> retryMeeting(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return meetingService.retryProcessing(meetingId, Headers.requiredUserId(userId));
    }
}
