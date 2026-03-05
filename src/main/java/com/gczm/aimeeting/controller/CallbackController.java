package com.gczm.aimeeting.controller;

import com.gczm.aimeeting.dto.CallbackAckResponse;
import com.gczm.aimeeting.service.CallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("${app.api-prefix:/api/v1}/callbacks")
@RequiredArgsConstructor
public class CallbackController {

    private final CallbackService callbackService;

    @PostMapping("/tingwu")
    public CallbackAckResponse tingwuCallback(@RequestBody Map<String, Object> payload) {
        callbackService.handleTingwuCallback(payload);
        return new CallbackAckResponse(true);
    }
}
