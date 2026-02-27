# AI听会后端架构（V4 - Spring Boot）

## 目标
- 前端直连通义听悟 WebSocket，后端不承载实时转写链路。
- 录音结束后以听悟可查询结果为准，完成态入库。
- 不使用 MQ，采用 `parse_job` 表 + Worker 定时扫描。

## 技术选型（国内互联网常见）
- 框架：Spring Boot 3.x
- 数据访问：MyBatis-Plus
- 数据库：PostgreSQL（阿里云 RDS）
- 缓存与锁：Redis（阿里云 Redis）
- 对象存储：OSS
- 调度：Spring `@Scheduled`（无 MQ）
- 监控：ARMS + SLS

## 模块职责（单应用内）
- `meeting-api`：会议创建、状态控制、列表详情、分享、音频上传。
- `callback-handler`：接收听悟回调，推进状态并创建解析任务。
- `worker-scheduler`：扫描 `parse_job`，拉取听悟结果并解析入库。
- `knowledge-linker`：调用单接口智库检索并回填。

## 核心流程
1. `POST /api/v1/meetings` 创建听悟任务并返回 `meeting_join_url`。
2. 前端直连 `meeting_join_url` 进行实时转写展示（不回传后端入库）。
3. 前端并行上传音频分片到后端 OSS。
4. `POST /api/v1/meetings/{id}/finish` 调听悟 `operation=stop`，会议转 `PROCESSING`。
5. 听悟完成回调 `POST /api/v1/callbacks/tingwu` 后写入 `parse_job(PENDING)`。
6. Worker 拉取 `GetTaskInfo` 与结果 URL，解析后写入最终转写与纪要，会议转 `COMPLETED`。
7. 回调缺失时，Worker 轮询 `PROCESSING` 会议兜底推进。
8. `COMPLETED` 后异步调用智库搜索接口回填关联内容。

## 状态机
- `RECORDING <-> PAUSED -> PROCESSING -> COMPLETED/FAILED -> DELETED`

## Worker控制
- 仅支持两种状态：
- 启用：`app.worker.enabled=true`
- 禁用：`app.worker.enabled=false`

## 无MQ异步控制
- 任务表：`parse_job`
- 状态：`PENDING/RUNNING/SUCCESS/FAILED`
- 重试：3次，指数退避（30s / 2m / 10m）
- 分布式互斥：Redis 锁（无 Redis 时降级为单实例执行）
- 任务超时：120秒
