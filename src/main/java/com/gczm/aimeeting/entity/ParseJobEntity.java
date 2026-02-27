package com.gczm.aimeeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("parse_job")
public class ParseJobEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("meeting_id")
    private String meetingId;

    @TableField("task_id")
    private String taskId;

    @TableField("stage")
    private String stage;

    @TableField("status")
    private String status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    @TableField("locked_by")
    private String lockedBy;

    @TableField("locked_at")
    private LocalDateTime lockedAt;

    @TableField("last_error")
    private String lastError;

    @TableField("payload")
    private String payload;
}
