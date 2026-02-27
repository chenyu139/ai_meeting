package com.gczm.aimeeting.dto;

import com.gczm.aimeeting.enums.ShareStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShareLinkCreateResponse {
    private String token;
    private String shareUrl;
    private LocalDateTime expireAt;
    private ShareStatus status;
}
