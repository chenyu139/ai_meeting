# 测试方案

## 已实现自动化测试
- `tests/test_state_machine.py`
  - 状态机合法/非法转换
- `tests/test_tingwu_and_worker.py`
  - 使用真实听悟 Key 的创建/停止/状态查询集成测试（`TINGWU_MODE=sdk`）
  - 必要环境变量：`TINGWU_MODE`、`TINGWU_ACCESS_KEY_ID`、`TINGWU_ACCESS_KEY_SECRET`（`TINGWU_APP_KEY` 默认 `NUZKS8AveuPWMwn6`）

## 建议补充测试
- API契约测试（FastAPI TestClient）
  - 创建/结束/分享/回调/重试
- 异步可靠性测试
  - 回调丢失时轮询兜底
  - 重复回调幂等处理
- 性能测试
  - 并发录音 <100
  - 60分钟会议完成时延 P95 <= 8分钟

## 手工验收场景
1. 前端拿 `meeting_join_url` 能直连听悟。
2. 结束会议后进入 `PROCESSING`，最终变 `COMPLETED`。
3. 纪要包含 overview/chapters/decisions/highlights/todos。
4. 分享链接按有效期与提取码生效。
5. parse_job 失败后可通过管理接口重置重跑。

## 真实听悟测试前置权限
- 使用 AK/SK 跑集成测试时，RAM 子账号至少需允许：
- `tingwu:CreateTask`
- `tingwu:GetTaskInfo`
- 若使用资源级授权，`Resource` 需覆盖：`acs:tingwu::*`
- 如出现 `Forbidden.NoPermission`，优先检查 RAM 策略是否已附加到当前 AccessKey 所属身份。
