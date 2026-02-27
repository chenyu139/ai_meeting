package com.gczm.aimeeting.common;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;

public final class Headers {

    private Headers() {
    }

    public static String requiredUserId(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing X-User-Id header");
        }
        return userId;
    }
}
