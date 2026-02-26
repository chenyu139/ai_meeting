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
@TableName("meeting")
public class MeetingEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("owner_id")
    private String ownerId;

    @TableField("title")
    private String title;

    @TableField("status")
    private String status;

    @TableField("task_id")
    private String taskId;

    @TableField("meeting_join_url")
    private String meetingJoinUrl;

    @TableField("processing_progress")
    private Integer processingProgress;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("ended_at")
    private LocalDateTime endedAt;

    @TableField("duration_sec")
    private Integer durationSec;

    @TableField("deleted_at")
    private LocalDateTime deletedAt;
}
