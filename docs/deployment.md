# 部署设计（阿里云托管，Spring Boot）

## 环境
- `dev`
- `staging`
- `prod`

## 部署形态（非 K8s）
- 单一 Spring Boot 应用部署到 SAE。
- Worker 仅支持开关控制：
- 启用：`app.worker.enabled=true`
- 禁用：`app.worker.enabled=false`

## 发布流程
1. 云效流水线：编译、测试、打包。
2. SAE 发布：10% -> 50% -> 100% 灰度。
3. 回滚：SAE 历史版本回滚。

## 配置与密钥
- 使用 Spring Boot 标准配置机制：
- 应用配置文件：`application.yml` / `application-{profile}.yml`
- 环境变量映射：`APP_*`、`SPRING_*`
- 听悟 AK/SK、OSS 密钥建议托管到阿里云密钥管理。

## 监控指标
- `meeting_processing_count`
- `parse_job_pending_count`
- `parse_job_failed_count`
- `tingwu_callback_delay`
- `summary_parse_latency`

## 运维SOP
1. `PROCESSING` 长时间不收敛：检查 callback 到达率 -> worker 扫描 -> `GetTaskInfo`。
2. 解析失败率升高：检查结果 URL 可达性与 JSON 结构变更。
3. 人工补偿：调用 parse-job reset 接口重置为 `PENDING`。
