# 测试方案（Java）

## 已实现自动化测试
- `MeetingStateMachineTest`
  - 状态机合法/非法转换。
- `TingwuIntegrationTest`
  - 使用真实听悟 AK/SK 的创建/停止/状态查询集成测试（无 mock）。

## 运行命令
- 全量单测：
  ```bash
  mvn test
  ```
- 听悟真实集成：
  ```bash
  APP_TINGWU_MODE=sdk \
  APP_TINGWU_ACCESS_KEY_ID=xxx \
  APP_TINGWU_ACCESS_KEY_SECRET=xxx \
  APP_TINGWU_APP_KEY=NUZKS8AveuPWMwn6 \
  mvn -Dtest=TingwuIntegrationTest test
  ```

## 建议补充
- API 契约测试：创建/结束/分享/回调/重试。
- 异步可靠性：回调丢失、重复回调、任务超时重试。
- 性能压测：并发录音 <100，60分钟会议完成时延 P95 <= 8 分钟。

## 真实听悟测试前置权限
- `tingwu:CreateTask`
- `tingwu:GetTaskInfo`
