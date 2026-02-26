package com.gczm.aimeeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meeting_audio_asset")
public class MeetingAudioAssetEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("meeting_id")
    private String meetingId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    @TableField("object_key")
    private String objectKey;

    @TableField("content_type")
    private String contentType;

    @TableField("size_bytes")
    private Integer sizeBytes;
}
