package com.gczm.aimeeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("idempotency_record")
public class IdempotencyRecordEntity {

    @TableId(value = "idempotency_key", type = IdType.INPUT)
    private String idempotencyKey;

    @TableField("endpoint")
    private String endpoint;

    @TableField("user_id")
    private String userId;

    @TableField("request_hash")
    private String requestHash;

    @TableField("response_body")
    private String responseBody;

    @TableField("status_code")
    private Integer statusCode;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
