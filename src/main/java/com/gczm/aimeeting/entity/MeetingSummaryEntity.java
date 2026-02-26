package com.gczm.aimeeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meeting_summary")
public class MeetingSummaryEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("meeting_id")
    private String meetingId;

    @TableField("overview")
    private String overview;

    @TableField("chapters")
    private String chapters;

    @TableField("decisions")
    private String decisions;

    @TableField("highlights")
    private String highlights;

    @TableField("todos")
    private String todos;

    @TableField("raw_result_refs")
    private String rawResultRefs;

    @TableField("knowledge_links")
    private String knowledgeLinks;

    @TableField("version")
    private Integer version;
}
