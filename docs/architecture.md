# AI听会后端架构（V4）

## 目标
- 前端直连通义听悟 WebSocket，后端不承载实时转写链路。
- 录音结束后通过听悟结果完成态入库。
- 不使用 RocketMQ，采用 `parse_job` 表 + 定时 Worker。

## 云组件
- SAE：`meeting-api`、`callback-service`、`worker-service`
- RDS PostgreSQL：业务主库
- Redis：分布式锁、幂等键、编辑锁
- OSS：音频分片及回放资产
- SLS + ARMS：日志/监控

## 核心流程
1. `POST /api/v1/meetings` 创建听悟任务并返回 `meeting_join_url`。
2. 前端直连听悟 WS；并行上传音频分片到后端 OSS。
3. `POST /api/v1/meetings/{id}/finish` 调听悟 stop，会议转 `PROCESSING`。
4. 听悟回调 `POST /api/v1/callbacks/tingwu` 标记完成并写 `parse_job(PENDING)`。
5. Worker 拉任务，调用 `GetTaskInfo` + 结果URL，解析转写与纪要入库。
6. 解析成功后会议转 `COMPLETED`，随后执行智库单接口关联。
7. 若回调丢失，Worker 轮询 `PROCESSING` 会议兜底。

## 状态机
- `RECORDING <-> PAUSED -> PROCESSING -> COMPLETED/FAILED -> DELETED`

## 无MQ异步控制
- 任务表：`parse_job`
- 任务状态：`PENDING/RUNNING/SUCCESS/FAILED`
- 重试：3次，指数退避（30s/2m/10m）
- 任务超时：120秒
