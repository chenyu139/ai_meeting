package com.gczm.aimeeting.controller;

import com.gczm.aimeeting.common.Headers;
import com.gczm.aimeeting.dto.*;
import com.gczm.aimeeting.service.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("${app.api-prefix:/api/v1}")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    @PostMapping("/meetings/{meetingId}/share-links")
    public ShareLinkCreateResponse createShareLink(
            @PathVariable String meetingId,
            @RequestBody @Valid ShareLinkCreateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return shareService.createShareLink(meetingId, Headers.requiredUserId(userId), request, idempotencyKey);
    }

    @PostMapping("/share/{token}/verify")
    public ShareVerifyResponse verifyShareLink(
            @PathVariable String token,
            @RequestBody ShareVerifyRequest request
    ) {
        return shareService.verifyShareLink(token, request);
    }

    @GetMapping("/share/{token}/content")
    public ShareContentResponse getShareContent(
            @PathVariable String token,
            @RequestParam(value = "passcode", required = false) String passcode,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            HttpServletRequest servletRequest
    ) {
        String ip = servletRequest.getRemoteAddr();
        String ua = servletRequest.getHeader("user-agent");
        return shareService.getShareContent(token, passcode, userId, ip, ua);
    }

    @PostMapping("/share/{token}/revoke")
    public Map<String, Object> revokeShareLink(
            @PathVariable String token,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return shareService.revokeShareLink(token, Headers.requiredUserId(userId));
    }
}
