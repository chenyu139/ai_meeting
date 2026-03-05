# AI听会全流程联调报告（Web模拟器 + 真实听悟Key）

- 执行日期：2026-03-05
- 执行人：Codex
- 代码分支：`codex/springboot-java-migration`
- 后端版本基线：Spring Boot `3.3.7`，JDK `21.0.10`
- 运行方式：本地启动后端（真实听悟 SDK 模式）+ 浏览器打开 `/simulator/index.html` 执行全流程

## 1. 测试目标
验证以下链路在真实阿里云听悟环境可用：
1. 创建会议并返回 `meeting_join_url`。
2. 前端直连听悟 WebSocket 推流（虚拟音源模式）。
3. 暂停/继续/结束会议。
4. 后端状态推进至 `COMPLETED`，可查询任务实时状态。
5. 纪要查询与在线编辑保存。
6. 音频回放地址获取。
7. 分享创建、提取码校验、获取分享内容、撤销分享。

## 2. 测试环境与关键配置

### 2.1 启动命令
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
export APP_TINGWU_MODE=sdk
export APP_TINGWU_REGION=cn-beijing
export APP_TINGWU_ENDPOINT=tingwu.cn-beijing.aliyuncs.com
export APP_TINGWU_ACCESS_KEY_ID=<REAL_KEY_ID>
export APP_TINGWU_ACCESS_KEY_SECRET=<REAL_KEY_SECRET>
export APP_TINGWU_APP_KEY=NUZKS8AveuPWMwn6
export APP_WORKER_ENABLED=true
mvn -q -DskipTests spring-boot:run
```

### 2.2 页面入口
- `http://127.0.0.1:8080/simulator/index.html`

### 2.3 模拟器参数
- `X-User-Id`: `web_user_e2e`
- 推流模式：`虚拟音源（synthetic）`
- 虚拟频率：`520Hz`

## 3. 全流程执行记录（真实跑通）

### 3.1 关键业务ID
- `meeting_id`: `32bcf59f4b6b4f6d230cb0a3bd97bfc9`
- `task_id`: `dd1cb7500aba4f02935cd8d9e08b156e`
- `meeting_join_url` 前缀：`wss://tingwu-realtime-cn-beijing.aliyuncs.com/...`

### 3.2 页面流程结果
1. 创建会议成功，拿到 `meeting_id + task_id + meeting_join_url`。
2. WebSocket 推流成功（StartTranscription 已发送），音频分片上传计数最终为 `2`。
3. 暂停与继续接口成功。
4. 结束会议后状态进入处理，随后推进到 `COMPLETED`。
5. 查询纪要成功；在线编辑并保存成功（版本递增）。
6. 回放地址接口返回可访问 URL。
7. 分享链路完整成功：创建 -> 校验提取码 -> 获取内容 -> 撤销 -> 再次获取返回 403。

## 4. 接口核验结果（带真实数据）

### 4.1 会议详情
```json
{
  "id": "32bcf59f4b6b4f6d230cb0a3bd97bfc9",
  "status": "COMPLETED",
  "processingProgress": 100,
  "taskId": "dd1cb7500aba4f02935cd8d9e08b156e",
  "durationSec": 13
}
```

### 4.2 实时任务查询
```json
{
  "task_id": "dd1cb7500aba4f02935cd8d9e08b156e",
  "task_status": "COMPLETED",
  "hasTaskInfo": true
}
```

### 4.3 纪要查询（完成态入库）
```json
{
  "meetingId": "32bcf59f4b6b4f6d230cb0a3bd97bfc9",
  "version": 2,
  "overviewLen": 0,
  "chapters": 0,
  "decisions": 0,
  "highlights": 0,
  "todos": 0
}
```
说明：该次模拟音频较短且为纯正弦波，听悟可完成任务并产出结构化结果引用，但文本内容为空属于预期现象。

### 4.4 纪要编辑保存
```json
{
  "meetingId": "32bcf59f4b6b4f6d230cb0a3bd97bfc9",
  "version": 3,
  "overview": "回归验证_1772673534"
}
```

### 4.5 音频回放
```json
{
  "url": "http://localhost:8080/api/v1/files/meetings/32bcf59f4b6b4f6d230cb0a3bd97bfc9/chunks/1.m4a"
}
```

### 4.6 分享链路
- 创建分享：成功，`token=ef6lIpQvlxshwQtxw9225qD9`
- 提取码校验：`{"valid": true}`
- 获取分享内容：成功（包含 meeting/summary/transcript 字段）
- 撤销分享：`{"ok": true}`
- 撤销后访问分享内容：`HTTP 403`，返回 `Share link revoked`（符合预期）

## 5. 自动化测试执行
执行：
```bash
mvn -q test
```
结果：通过（包含 `TingwuIntegrationTest` 真实听悟集成测试）。

## 6. 结论
1. Web模拟页已可覆盖并跑通 AI听会核心业务全流程。
2. 后端在 JDK21 + Spring Boot 3.3.7 下可稳定启动并完成真实听悟链路调用。
3. 会议状态流转、完成态入库、纪要编辑、回放、分享链路均已验证可用。
4. 当前本地未启 Redis 时会打印锁降级告警（fallback local execution），不影响单机联调；生产建议接入 Redis 保持分布式锁语义。
