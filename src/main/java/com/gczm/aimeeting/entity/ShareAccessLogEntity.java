package com.gczm.aimeeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("share_access_log")
public class ShareAccessLogEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("share_link_id")
    private String shareLinkId;

    @TableField("meeting_id")
    private String meetingId;

    @TableField("viewer_id")
    private String viewerId;

    @TableField("ip")
    private String ip;

    @TableField("user_agent")
    private String userAgent;
}
