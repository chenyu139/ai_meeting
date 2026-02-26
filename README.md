# AI Meeting Backend (Spring Boot)

AI 听会后端，采用 `Spring Boot + MyBatis-Plus + Redis + PostgreSQL`，对接阿里云通义听悟（前端直连 WS，后端完成态入库）。

## 快速启动

1. 准备 JDK 17+ 与 Maven 3.9+。
2. 配置环境变量（参考 `.env.example`）。
3. 启动服务：
   ```bash
   mvn spring-boot:run
   ```

默认端口 `8080`，健康检查：`GET /healthz`。

## Worker 运行方式

- 同进程调度：默认开启（`WORKER_ENABLED=true`）。
- API 单独部署：设置 `WORKER_ENABLED=false`。
- Worker 单独部署：同一 Jar 独立实例启动并设置 `WORKER_ENABLED=true`。

## 文档

- `docs/architecture.md`
- `docs/api-data-model.md`
- `docs/testing.md`
- `docs/deployment.md`

## 真实听悟集成测试

```bash
TINGWU_MODE=sdk \
TINGWU_ACCESS_KEY_ID=xxx \
TINGWU_ACCESS_KEY_SECRET=xxx \
TINGWU_APP_KEY=NUZKS8AveuPWMwn6 \
mvn -Dtest=TingwuIntegrationTest test
```
