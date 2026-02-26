package com.gczm.aimeeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShareLinkCreateRequest {

    @Min(0)
    @Max(3650)
    private Integer expireDays = 7;

    @Size(min = 4, max = 8)
    private String passcode;
}
