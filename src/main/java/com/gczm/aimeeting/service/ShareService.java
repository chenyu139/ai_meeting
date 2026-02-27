package com.gczm.aimeeting.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gczm.aimeeting.common.ApiException;
import com.gczm.aimeeting.config.AppProperties;
import com.gczm.aimeeting.dto.*;
import com.gczm.aimeeting.entity.*;
import com.gczm.aimeeting.enums.ShareStatus;
import com.gczm.aimeeting.mapper.MeetingMapper;
import com.gczm.aimeeting.mapper.MeetingSummaryMapper;
import com.gczm.aimeeting.mapper.ShareAccessLogMapper;
import com.gczm.aimeeting.mapper.ShareLinkMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ShareService {

    private final MeetingMapper meetingMapper;
    private final MeetingSummaryMapper meetingSummaryMapper;
    private final ShareLinkMapper shareLinkMapper;
    private final ShareAccessLogMapper shareAccessLogMapper;
    private final SecurityService securityService;
    private final IdempotencyService idempotencyService;
    private final MeetingService meetingService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @Transactional
    public ShareLinkCreateResponse createShareLink(String meetingId,
                                                   String userId,
                                                   ShareLinkCreateRequest payload,
                                                   String idempotencyKey) {
        MeetingEntity meeting = meetingService.getNotDeletedMeeting(meetingId);
        meetingService.ensureOwner(meeting, userId);

        String reqHash = idempotencyService.buildRequestHash(payload);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyService.CachedResponse> cached = idempotencyService.load(
                    idempotencyKey,
                    "POST:/meetings/{id}/share-links",
                    userId,
                    reqHash
            );
            if (cached.isPresent()) {
                return objectMapper.convertValue(cached.get().body(), ShareLinkCreateResponse.class);
            }
        }

        LocalDateTime expireAt = payload.getExpireDays() == null || payload.getExpireDays() == 0
                ? null
                : LocalDateTime.now().plusDays(payload.getExpireDays());

        ShareLinkEntity share = new ShareLinkEntity();
        share.setMeetingId(meetingId);
        share.setCreatedBy(userId);
        share.setToken(securityService.generateShareToken());
        share.setPasscodeHash(payload.getPasscode() == null || payload.getPasscode().isBlank()
                ? null : securityService.hashPasscode(payload.getPasscode()));
        share.setExpireAt(expireAt);
        share.setStatus(ShareStatus.ACTIVE.name());
        shareLinkMapper.insert(share);

        String shareUrl = appProperties.getPublicBaseUrl().replaceAll("/+$", "") + "/share/" + share.getToken();

        ShareLinkCreateResponse response = ShareLinkCreateResponse.builder()
                .token(share.getToken())
                .shareUrl(shareUrl)
                .expireAt(share.getExpireAt())
                .status(ShareStatus.valueOf(share.getStatus()))
                .build();

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.save(
                    idempotencyKey,
                    "POST:/meetings/{id}/share-links",
                    userId,
                    reqHash,
                    objectMapper.convertValue(response, new TypeReference<>() {
                    }),
                    200
            );
        }

        return response;
    }

    public ShareVerifyResponse verifyShareLink(String token, ShareVerifyRequest payload) {
        ShareLinkEntity share = shareLinkMapper.selectOne(
                Wrappers.<ShareLinkEntity>lambdaQuery().eq(ShareLinkEntity::getToken, token).last("limit 1")
        );
        if (!isShareValid(share)) {
            return new ShareVerifyResponse(false);
        }

        if (share.getPasscodeHash() != null) {
            if (payload.getPasscode() == null || payload.getPasscode().isBlank()) {
                return new ShareVerifyResponse(false);
            }
            if (!securityService.verifyPasscode(payload.getPasscode(), share.getPasscodeHash())) {
                return new ShareVerifyResponse(false);
            }
        }

        return new ShareVerifyResponse(true);
    }

    @Transactional
    public ShareContentResponse getShareContent(String token,
                                                String passcode,
                                                String optionalUserId,
                                                String ip,
                                                String userAgent) {
        ShareLinkEntity share = shareLinkMapper.selectOne(
                Wrappers.<ShareLinkEntity>lambdaQuery().eq(ShareLinkEntity::getToken, token).last("limit 1")
        );
        if (share == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Share link not found");
        }

        if (!ShareStatus.ACTIVE.name().equals(share.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Share link revoked");
        }

        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Share link expired");
        }

        if (share.getPasscodeHash() != null
                && (passcode == null || !securityService.verifyPasscode(passcode, share.getPasscodeHash()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Invalid passcode");
        }

        MeetingEntity meeting = meetingMapper.selectById(share.getMeetingId());
        if (meeting == null || meeting.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Meeting not found");
        }

        MeetingSummaryEntity summary = meetingSummaryMapper.selectOne(
                Wrappers.<MeetingSummaryEntity>lambdaQuery()
                        .eq(MeetingSummaryEntity::getMeetingId, meeting.getId())
                        .last("limit 1")
        );

        ShareAccessLogEntity accessLog = new ShareAccessLogEntity();
        accessLog.setShareLinkId(share.getId());
        accessLog.setMeetingId(meeting.getId());
        accessLog.setViewerId(optionalUserId);
        accessLog.setIp(ip);
        accessLog.setUserAgent(userAgent);
        shareAccessLogMapper.insert(accessLog);

        MeetingDetailResponse meetingDetail = meetingService.getMeetingDetail(meeting.getId(),
                Objects.requireNonNullElse(optionalUserId, meeting.getOwnerId()));
        MeetingSummaryResponse summaryResponse = summary == null ? null : meetingService.toSummaryResponse(summary);
        return new ShareContentResponse(meetingDetail, summaryResponse);
    }

    @Transactional
    public Map<String, Object> revokeShareLink(String token, String userId) {
        ShareLinkEntity share = shareLinkMapper.selectOne(
                Wrappers.<ShareLinkEntity>lambdaQuery().eq(ShareLinkEntity::getToken, token).last("limit 1")
        );
        if (share == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Share link not found");
        }

        MeetingEntity meeting = meetingMapper.selectById(share.getMeetingId());
        if (meeting == null || !Objects.equals(meeting.getOwnerId(), userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        share.setStatus(ShareStatus.REVOKED.name());
        shareLinkMapper.updateById(share);
        return Map.of("ok", true);
    }

    private boolean isShareValid(ShareLinkEntity share) {
        if (share == null) {
            return false;
        }
        if (!ShareStatus.ACTIVE.name().equals(share.getStatus())) {
            return false;
        }
        return share.getExpireAt() == null || !share.getExpireAt().isBefore(LocalDateTime.now());
    }
}
