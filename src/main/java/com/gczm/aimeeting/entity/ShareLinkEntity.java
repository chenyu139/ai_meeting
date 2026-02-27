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
@TableName("share_link")
public class ShareLinkEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("meeting_id")
    private String meetingId;

    @TableField("created_by")
    private String createdBy;

    @TableField("token")
    private String token;

    @TableField("passcode_hash")
    private String passcodeHash;

    @TableField("expire_at")
    private LocalDateTime expireAt;

    @TableField("status")
    private String status;
}
