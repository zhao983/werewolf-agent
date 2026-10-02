# LLM 行动工具

## 使用方式

1. 在 AI 设置填写模型服务连接信息，选择“行动决策方式”。
2. JSON 回复是原有兼容模式；工具调用需要服务支持 Chat Completions 的 tools、tool_choice 与 parallel_tool_calls；严格参数模式额外要求服务支持 function.strict。
3. 返回总览，选择 LLM Agent 并新建对局。对局仍由按钮逐人、逐阶段推进。
4. 工具响应的简短 reasoning 继续只显示在观战私有信息中，公开发言来自 speak 的 speech。

设置在创建对局时冻结，修改 AI 设置不会改变已创建对局。旧存档未记录 decisionMode 时按 JSON 处理。工具模式也会保存到完整 JSON、对局 CSV 和实验详情，并在对照配置摘要中提示模式差异。

## 工具与规则

| 工具 | 用途 | 参数与执行边界 |
|---|---|---|
| wolf_attack | 狼人袭击意向 | targetPlayerId 仅含存活非狼人；汇总后最高票确定一刀，平票随机 |
| vote_player | 白天投票 | targetPlayerId 仅含其他存活玩家；最终由投票结算放逐 |
| check_player | 预言家查验 | targetPlayerId 仅含其他存活玩家；结果写入本人私有信息 |
| use_antidote | 女巫解药 | 仅在解药可用且存在可救袭击目标时提供；只允许该目标 |
| use_poison | 女巫毒药 | 仅在毒药可用时提供，目标为其他存活玩家 |
| skip_action | 狼人或女巫跳过 | 无目标，不消耗药物 |
| speak | 公开发言 | speech 为非空文本，最多 500 字 |

所有工具均需 reasoning 字符串，可为空，最多 80 字；它是决策摘要，不是完整思考过程。除对应参数外不接受额外字段。带目标工具的 targetPlayerId 用合法玩家 ID 枚举约束。

## 设计原因

模型通过标准 function calling 返回 message.tool_calls，不再依赖普通消息中的 JSON 动作名称。参数仍以 JSON 字符串传输，工具调用不能保证所有兼容服务都严格遵守协议，因此保留本地解析与游戏规则校验。

请求设置 tool_choice=required、parallel_tool_calls=false。严格模式还设置 strict=true、additionalProperties=false，并将所有参数列为 required；普通工具模式省略 strict，便于兼容基础工具接口。参考 [OpenAI 官方 Function calling 文档](https://developers.openai.com/api/docs/guides/function-calling)。

只接收一个完整调用，不执行多个工具中的第一条。名称通过静态白名单映射到已有 AgentResponse，再由 GameEngine 修改状态；没有反射执行、任意代码执行或完整状态查询工具。每次按钮推进只提交一次合法行动，因此女巫仍不能一晚同时用两瓶药。

本项目将行动提交视为当前模型决策的终点，执行后不额外请求模型生成回执。下一次该玩家行动时，用新的可见信息构造请求，例如查验结果通过本人私有信息提供。工具层不持有完整 GameState、不拼入观战面板，也不改变 Agent 间信息隔离。

## 自动推进与暂停

进行中的对局页面增加“自动推进”按钮，点击后按后端 `nextCommand` 顺序执行玩家行动、完成阶段、白天结算和进入下一轮；再次点击“暂停自动推进”停止后续调度。创建对局仍不自动请求模型。

前端等待每一步及观战刷新完成后，再间隔 1 秒继续，始终只有一个推进请求。自动模式期间手动推进按钮禁用，暂停按钮始终可点；已发送的当前行动会执行完毕，暂停不撤销该行动。暂停后可继续手动操作或重新开启自动推进。

失败时自动暂停，保留原有错误与诊断，不循环重试失败玩家。对局结束、切换到另一场对局或离开页面都会停止；刷新或重新进入页面不会恢复自动运行。历史存档回放继续使用原有时间线播放按钮，不调用模型。

代码位于 `frontend/src/views/GameView.vue` 的串行调度及取消逻辑、`frontend/src/store.ts` 的请求互斥和过期响应保护。沿用原有后端推进接口与规则，无须重启后端。

测试：前端构建 `npm run build`；隔离页面验收使用本地模拟 LLM，检查行动等待期间暂停、暂停后的手动操作、自动跨阶段和进入下一轮、接口错误停止、离开页面/切换对局停止，以及完整对局结束后不再发出请求。手动验收可创建 LLM 对局后点击自动推进，观察行动更新，再暂停确认后续停止；已有请求可能还需等待完成。

## 错误处理

- 回复缺少工具、工具名越权、参数缺失、目标非法：计入非法回复，最多纠正一次（总计最多两次请求），不执行失败回复。
- 参数拒绝重复字段、额外字段、尾随对象、代码围栏、非字符串字段以及超长文本；不会把原始非法参数回显到页面。
- 多个工具调用：整批拒绝，防止部分执行后再失败。
- HTTP、网络、输出额度不足：直接报告并记录已返回用量；finish_reason=length 即使包含看似完整参数也不执行，提示增大 Max Tokens。
- 服务忽略工具要求而返回普通 JSON：作为缺失工具处理，不自动当作合法工具动作。
- 不支持工具的服务：提示选择 JSON 或普通工具模式后新建对局，不自动降级，避免混合实验条件。
- 最终失败时，当前玩家索引、票数和药物保持不变；失败尝试与用量仍计入实验记录，可在同一位置重试。

## 代码位置与测试

- backend/.../ai/DecisionMode.java、LlmConfig.java：模式配置与旧调用兼容。
- backend/.../ai/GameActionTools.java：阶段工具定义、参数解析、白名单校验。
- backend/.../ai/OpenAiCompatibleClient.java、LlmClient.java：发送 tools、读取 tool_calls、保留 usage。
- backend/.../agent/LlmAgent.java：模式路由、一次纠正、统一行动响应。
- backend/.../experiment/ExperimentRecord.java、ExperimentAnalysis.java、ExperimentCsv.java：存档与比较条件。
- frontend/src/views/SettingsView.vue：模式选择；StatsView.vue：记录详情展示。

在 backend 运行 `.\mvnw.cmd test`，frontend 运行 `npm run build`。新增测试使用本地模拟 HTTP 服务，不访问真实模型，覆盖工具定义、null content 合法调用、错误目标重试、未知及跨阶段工具、重复参数、多工具整批拒绝、失败后同位置重试、截断回复拒绝、服务不支持时不降级、导入旧存档兼容及密钥不进入存档。
