# 部署设计（阿里云托管）

## 环境
- `dev`
- `staging`
- `prod`

## 应用拆分
- `meeting-api`：FastAPI主服务
- `worker-service`：`scripts/run_worker.py` 定时轮询与解析
- `callback-service`：可与 `meeting-api` 合并部署，或单独部署并路由 `/callbacks/*`

## 发布流程
1. 云效流水线构建镜像。
2. 执行测试与静态检查。
3. SAE灰度发布（10% -> 50% -> 100%）。
4. 回滚使用 SAE 历史版本。

## 配置与密钥
- 所有配置通过环境变量注入（见 `.env.example`）。
- 听悟AK/SK、OSS密钥建议走 KMS/密钥管理。

## 监控指标
- `meeting_processing_count`
- `parse_job_pending_count`
- `parse_job_failed_count`
- `tingwu_callback_delay`
- `summary_parse_latency`

## 运维SOP
1. 若 `PROCESSING` 长时间不收敛：检查 callback -> worker -> GetTaskInfo。
2. 若解析失败率上升：检查结果URL可访问性和JSON结构变化。
3. 人工补偿：调用 parse-job reset 接口重置为 `PENDING`。
