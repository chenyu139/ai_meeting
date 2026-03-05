package com.gczm.aimeeting.dto;

import com.gczm.aimeeting.enums.ShareStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShareLinkCreateResponse {
    private String token;
    private String shareUrl;
    private LocalDateTime expireAt;
    private ShareStatus status;
}
