# API与数据模型

## API清单
- `POST /api/v1/meetings` 创建会议（返回 `meeting_join_url`）
- `POST /api/v1/meetings/{id}/pause` 暂停
- `POST /api/v1/meetings/{id}/resume` 继续
- `POST /api/v1/meetings/{id}/finish` 结束并触发听悟 stop
- `POST /api/v1/meetings/{id}/audio-chunks?chunk_index=n` 上传音频分片
- `GET /api/v1/meetings?tab=all|mine|shared` 列表
- `GET /api/v1/meetings/{id}` 详情
- `GET /api/v1/meetings/{id}/summary` 获取纪要
- `PATCH /api/v1/meetings/{id}/summary` 在线编辑纪要
- `GET /api/v1/meetings/{id}/audio-playback` 获取回放链接
- `GET /api/v1/meetings/{id}/transcript-live` 可选透传查询听悟状态
- `DELETE /api/v1/meetings/{id}` 软删除
- `POST /api/v1/meetings/{id}/retry` 失败重试
- `POST /api/v1/meetings/{id}/share-links` 创建分享
- `POST /api/v1/share/{token}/verify` 提取码校验
- `GET /api/v1/share/{token}/content` 获取分享内容
- `POST /api/v1/share/{token}/revoke` 失效分享
- `POST /api/v1/callbacks/tingwu` 听悟回调
- `POST /api/v1/callbacks/poll/process` 手动触发worker扫描
- `POST /api/v1/admin/meetings/{id}/parse-jobs/reset` 人工补偿

## 核心表
- `meeting`：会话主表（task_id、join_url、状态）
- `meeting_transcript_final`：最终转写（完成态）
- `meeting_summary`：结构化纪要（5模块）
- `meeting_audio_asset`：音频分片资产
- `share_link`：分享链接配置
- `share_access_log`：分享访问记录
- `parse_job`：异步解析任务
- `task_event_log`：回调/轮询/解析事件日志
- `idempotency_record`：幂等记录

## 关键约束
- `Idempotency-Key`：创建会议、结束会议、创建分享支持幂等。
- `meeting_id + stage`：`parse_job` 唯一，防重复入队。
- 最终结果写入时清空并重建 `meeting_transcript_final`，保证终态一致。
