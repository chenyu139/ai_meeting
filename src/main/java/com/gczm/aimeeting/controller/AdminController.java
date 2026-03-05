package com.gczm.aimeeting.controller;

import com.gczm.aimeeting.common.Headers;
import com.gczm.aimeeting.dto.ParseJobAdminResetRequest;
import com.gczm.aimeeting.dto.ParseJobResponse;
import com.gczm.aimeeting.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("${app.api-prefix:/api/v1}/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @PostMapping("/meetings/{meetingId}/parse-jobs/reset")
    public ParseJobResponse resetParseJob(
            @PathVariable String meetingId,
            @RequestBody ParseJobAdminResetRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return adminService.resetParseJob(meetingId, request.getStage(), Headers.requiredUserId(userId));
    }

    @PostMapping("/worker/run-once")
    public Map<String, Object> triggerWorkerRunOnce(
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        Headers.requiredUserId(userId);
        return adminService.triggerWorkerRunOnce();
    }
}
