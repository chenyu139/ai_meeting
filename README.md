# AI Meeting Backend (Spring Boot)

AI 听会后端，采用 `Spring Boot + MyBatis-Plus + Redis + PostgreSQL`，对接阿里云通义听悟（前端直连 WS，后端完成态入库）。

## 快速启动

1. 准备 JDK 21 与 Maven 3.9+。
2. 使用 Spring Boot 标准配置方式：
   - `src/main/resources/application.yml` 默认配置。
   - 生产环境建议在 `./config/application-prod.yml` 覆盖，并通过 `--spring.profiles.active=prod` 启动。
   - 也可用环境变量覆盖（示例：`APP_TINGWU_ACCESS_KEY_ID`、`SPRING_DATASOURCE_URL`）。
3. 启动服务：
   ```bash
   mvn spring-boot:run
   ```

默认端口 `8080`，健康检查：`GET /healthz`。
Swagger 文档：
- UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Web全流程模拟页：`http://localhost:8080/simulator/index.html`

## 版本基线

- JDK：21（`pom.xml` 已强制要求 `[21,22)`）
- Spring Boot：`3.3.x`（当前 `3.3.7`）

## Worker 开关

- 仅支持两种状态：
- 启用：`app.worker.enabled=true`
- 禁用：`app.worker.enabled=false`

## 文档

- `docs/architecture.md`
- `docs/api-data-model.md`
- `docs/testing.md`
- `docs/deployment.md`

## 真实听悟集成测试

```bash
APP_TINGWU_MODE=sdk \
APP_TINGWU_ACCESS_KEY_ID=xxx \
APP_TINGWU_ACCESS_KEY_SECRET=xxx \
APP_TINGWU_APP_KEY=NUZKS8AveuPWMwn6 \
mvn -Dtest=TingwuIntegrationTest test
```
