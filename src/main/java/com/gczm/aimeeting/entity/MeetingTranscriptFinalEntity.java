package com.gczm.aimeeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meeting_transcript_final")
public class MeetingTranscriptFinalEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("meeting_id")
    private String meetingId;

    @TableField("segment_order")
    private Integer segmentOrder;

    @TableField("speaker")
    private String speaker;

    @TableField("start_ms")
    private Integer startMs;

    @TableField("end_ms")
    private Integer endMs;

    @TableField("confidence")
    private Integer confidence;

    @TableField("text")
    private String text;
}
