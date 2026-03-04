# AI听会后端接口测试说明报告（真实Key全链路）

## 1. 测试目标
验证 AI 听会后端在真实阿里云通义听悟凭证下的完整业务链路和接口可用性，包括：
- 会议生命周期（创建、暂停、继续、结束、处理、删除）
- 听悟回调与轮询兜底
- 最终纪要生成与在线编辑
- 音频分片上传与回放
- 分享链路（创建、验证、访问、撤销）
- 管理接口（解析任务重置）
- 失败重试路径（FAILED -> retry -> 恢复）

## 2. 环境与凭证
- 运行时间: 2026-03-04 18:27 (Asia/Shanghai)
- 服务地址: `http://127.0.0.1:8080`
- 部署方式: 本地 Spring Boot（JDK 25, Maven）
- 数据库: H2 内存库（PostgreSQL 模式）
- 听悟调用: 真实 `AccessKeyId/AccessKeySecret/AppKey`（已通过环境变量注入）
- Worker: `enabled=true`，轮询间隔 2s

## 3. 核心测试数据
- 会议ID: `f0b23b371f0b626409c6584ce3bdbf06`
- 听悟任务ID: `48b35d40fb0f4847bbbbe60a9c30e7c4`
- 分享Token: `4etjsLk0mOHqKdIbdYW5kr5I`
- 创建幂等一致性: 是（两次创建返回同一 `meeting_id/task_id`）
- 结束后任务状态: `COMPLETED`
- 会议最终状态: `COMPLETED`（删除前）

## 4. 接口覆盖与结果
本轮共执行 38 个有效检查步骤，38/38 通过。

| 模块 | 接口 | 结果 |
|---|---|---|
| 健康检查 | `GET /healthz` | PASS |
| OpenAPI | `GET /v3/api-docs` | PASS |
| 会议创建 | `POST /api/v1/meetings` | PASS |
| 创建幂等 | `POST /api/v1/meetings` (同 Idempotency-Key 重放) | PASS |
| 暂停 | `POST /api/v1/meetings/{id}/pause` | PASS |
| 继续 | `POST /api/v1/meetings/{id}/resume` | PASS |
| 音频分片上传 | `POST /api/v1/meetings/{id}/audio-chunks` | PASS |
| 回放地址 | `GET /api/v1/meetings/{id}/audio-playback` | PASS |
| 回放文件 | `GET /api/v1/files/**` | PASS |
| 会议列表 | `GET /api/v1/meetings?tab=all/mine/shared` | PASS |
| 会议详情 | `GET /api/v1/meetings/{id}` | PASS |
| 实时任务查询 | `GET /api/v1/meetings/{id}/transcript-live` | PASS |
| 结束录音 | `POST /api/v1/meetings/{id}/finish` | PASS |
| 结束幂等 | `POST /api/v1/meetings/{id}/finish` (同 Idempotency-Key 重放) | PASS |
| 听悟回调(未匹配) | `POST /api/v1/callbacks/tingwu` | PASS |
| 听悟回调(匹配完成) | `POST /api/v1/callbacks/tingwu` | PASS |
| Worker 触发 | `POST /api/v1/callbacks/poll/process` | PASS |
| 纪要查询 | `GET /api/v1/meetings/{id}/summary` | PASS（200） |
| 纪要编辑 | `PATCH /api/v1/meetings/{id}/summary` | PASS |
| 创建分享 | `POST /api/v1/meetings/{id}/share-links` | PASS |
| 分享幂等 | `POST /api/v1/meetings/{id}/share-links` (同 Idempotency-Key 重放) | PASS |
| 提取码校验 | `POST /api/v1/share/{token}/verify` (错误/正确) | PASS |
| 访问分享(无码) | `GET /api/v1/share/{token}/content` | PASS（403） |
| 访问分享(有码) | `GET /api/v1/share/{token}/content?passcode=...` | PASS |
| 共享列表可见性 | `GET /api/v1/meetings?tab=shared` | PASS |
| 撤销分享 | `POST /api/v1/share/{token}/revoke` | PASS |
| 撤销后访问 | `GET /api/v1/share/{token}/content?passcode=...` | PASS（403） |
| 解析任务重置 | `POST /api/v1/admin/meetings/{id}/parse-jobs/reset` | PASS |
| retry 非FAILED | `POST /api/v1/meetings/{id}/retry` | PASS（409） |
| retry FAILED | `POST /api/v1/meetings/{id}/retry` | PASS（200） |
| 删除会议 | `DELETE /api/v1/meetings/{id}` | PASS |
| 删除后详情 | `GET /api/v1/meetings/{id}` | PASS（404） |

## 5. 关键问题与修复
测试过程中定位并修复了以下会阻断联调/上线的问题：

1. 幂等表主键字段命名问题
- 问题: `idempotency_record` 查询生成别名 `AS key`，在 H2 下触发 SQL 语法错误。
- 修复: 实体主键字段由 `key` 改为 `idempotencyKey`，同步服务赋值逻辑。

2. JDK 23+ 下 Lombok 注解处理失效
- 问题: JDK 25 默认不自动执行注解处理器，导致大量 getter/setter/`@Slf4j` 缺失编译失败。
- 修复: `maven-compiler-plugin` 增加 `-proc:full` 和 `annotationProcessorPaths`；显式升级 Lombok 版本。

3. 幂等重放 DTO 反序列化失败
- 问题: `MeetingCreateResponse`、`ShareLinkCreateResponse` 仅 `@Builder`，`ObjectMapper.convertValue` 反序列化失败。
- 修复: 增加 `@NoArgsConstructor/@AllArgsConstructor`。

4. 听悟结果URL拉取签名失配
- 问题: 结果 URL 是签名 OSS 链接，`RestTemplate.getForEntity(String)` 发生二次编码，导致 `SignatureDoesNotMatch`。
- 修复: 改为 `RestTemplate.getForEntity(URI.create(url), String.class)`，并在解析器内做异常容错返回 `null`。

## 6. 结论
- 真实 Key 下，后端主业务流和所有目标接口已完整跑通。
- 最终结果：`PASS=38, FAIL=0`。
- 会议从创建到完成、纪要生成与编辑、分享闭环、重试路径、删除路径均验证通过。

## 7. 附件
- 原始逐步请求/响应证据（含每步请求体与响应体）:
  - `docs/reports/api_e2e_real_report_20260304_182748.md`
