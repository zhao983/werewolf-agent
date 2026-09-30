# 多 Agent 狼人杀实验室

本科毕业设计项目：以狼人杀为环境，研究多 Agent 的协同与对抗。后端使用 Java 21 和 Spring Boot，前端使用 Vue 3、TypeScript 和 Vite。

## 已实现功能

- 独立于前端和 AI 接口的狼人杀规则引擎；默认 7 人局，也可配置 3–20 人及狼人、村民、预言家、女巫人数。
- RandomAgent、RuleAgent，以及可配置 Base URL、API Key 和 Model 的 OpenAI-Compatible LLM Agent。
- AI 对局逐人、逐阶段手动推进；展示公共事件、回放和仅供用户查看的身份及私有信息。
- 浏览器会话隔离对局；模型密钥仅在后端使用。

## 本地运行

需要 Java 21、Node.js 20.19+ 或 22.12+、npm。在两个终端分别运行：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

```powershell
cd frontend
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。不配置模型也能使用 RandomAgent 或 RuleAgent 完成对局。后端测试运行 `cd backend; .\mvnw.cmd test`，前端构建运行 `cd frontend; npm run build`。

## 项目结构

- `backend/src/main/java/com/example/werewolf`：规则引擎、Agent、模型客户端及 REST 接口。
- `backend/src/test`：规则、手动推进、模型解析和接口安全测试。
- `frontend/src`：对局设置、观战、回放、历史及统计页面。
- [`多Agent狼人杀毕业设计_初期任务规划.md`](./多Agent狼人杀毕业设计_初期任务规划.md)：需求、规则与后续实验规划。

目前对局保存在后端内存中，重启后清空。长期 Memory、狼人私聊、批量论文实验、数据库和 WebSocket 尚未实现。
