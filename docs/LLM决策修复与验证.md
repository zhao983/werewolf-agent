# LLM 决策修复与验证

本次修复针对对局 01 中遗忘本人行动、混淆玩家编号和死亡状态、实际投票与理由不一致，以及模型调用失败难以定位的问题。旧对局保持原始记录；历史缺失的诊断不能倒推补回。

## 1. 补齐真实观察与本人行动记忆

新增内容：`AgentContext` 增加死亡名单、公开裁判事实、本人成功行动、本人女巫药剂余量。公开事实只取 `DAY_ANNOUNCEMENT`、`PLAYER_DIED`、`PLAYER_EXILED`、`VOTE`；按现有手动顺序，已经提交的白天投票可被后续玩家看到。未结算的死亡不进入公告。预言家查验仍只在自己的 `privateInformation` 中；狼人队友信息仍只给狼人。

设计原因：仅传发言无法让模型区分谎言、旧局势和裁判状态；平安夜也无法推断女巫是否用过解药。本人行动记忆只取本人 `VALID` 记录，失败尝试不算成功行动。这里不把完整游戏状态或观战面板返回给任何 Agent。

代码位置：`backend/src/main/java/com/example/werewolf/agent/AgentContext.java`、`PersonalAction.java`、`agent/LlmAgent.java` 和 `game/GameEngine.java`。

提示词优先说明本人编号、当前存活名单、公开事实和真实私有线索，再给出玩家发言及策略知识。为限制输入长度，保留最近 100 条公开事实、60 条本人行动和约 6000 字公开发言；当前存活/死亡名单与药剂状态始终完整提供，查验线索不受发言裁剪影响。

测试：`game/AgentObservationTest` 验证查验记忆、救人后的药剂余量、放逐和夜间死亡公告、失败后原位重试、角色之间私有信息隔离；`agent/DecisionConsistencyTest` 验证新增字段确实进入模型提示。

## 2. 明确输出矛盾的保守纠错

新增内容：`DecisionConsistency` 检查句首“我是 N 号”的编号、私有摘要明确写出的投票目标、向死亡玩家发起的新提问、私有摘要明确否认已经完成的查验/用药，以及公开发言中“虽然我是女巫但不能明说”这类内心旁白。

设计原因：合法工具参数不能保证自然语言一致。检测失败后沿用最多一次纠错，再失败就停在原玩家位置，不发布错误发言、不自动换票、不扣药。格式与语义纠错都计入非法回复次数。

公开谎报角色或隐瞒本人查验仍可用于博弈；回顾死亡玩家过去的发言也允许。检查不使用其他人的隐藏身份，不判断策略好坏。这是对明确表达的检查，不是完整的自然语言理解；引用、复杂措辞和错误推断仍可能逃过检测。

代码位置：`backend/src/main/java/com/example/werewolf/agent/DecisionConsistency.java`、`LlmAgent.java`。测试位置：`backend/src/test/java/com/example/werewolf/agent/DecisionConsistencyTest.java`，涵盖 JSON、普通工具、严格工具三种模式。

## 3. 超时与输出额度参数

“AI 设置”增加单次请求超时（10–180 秒）及额度参数选项。前端新建设置默认 120 秒；旧请求/存档缺少此字段时兼容原来的 45 秒。一次行动最多两个请求，因此发生纠错时总等待可能超过单次超时。设置修改只影响新对局。

每次只发送选择的 `max_tokens` 或 `max_completion_tokens`。后者在 OpenAI 协议中包含可见输出与推理 Token，第三方服务支持情况需要按其文档选择。不会根据失败自动切换参数或决策模式，避免改变实验条件。[OpenAI Chat Completions 参数文档](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)

输出被 `finish_reason=length` 截断时停止，不执行看似完整的局部行动；缺失最终消息会报告错误。服务报告的输出 Token 超出本次配置时保留实际 usage 并记录提示，不把用量改写成设置值。它提示服务可能忽略参数或用量口径不同，不能仅凭此判定具体原因。

代码位置：`backend/src/main/java/com/example/werewolf/ai/LlmConfig.java`、`TokenLimitParameter.java`、`OpenAiCompatibleClient.java`，前端 `frontend/src/views/SettingsView.vue`。测试：`ai/ModelDiagnosticsTest` 用本机 HTTP 服务验证选定字段、真实 10 秒超时、截断与用量提示相关测试。

## 4. 用户可见、可保存的安全诊断

每次请求记录尝试序号、错误分类、HTTP 状态、结束原因、工具数量、耗时及用量超限提示。分类包括超时、连接失败、接口拒绝、响应格式错误、输出截断、格式/动作错误和一致性错误。记录不含地址、密钥、原始提示词和原始模型响应；第三方错误正文不回显。

使用方式：

1. 对局或存档回放的“观战私有信息”下展开“模型调用诊断”，查看完整记录的最近 20 次模型行动尝试；此列表独立于回放游标，失败尝试也会显示。
2. “实验记录”选择对局，在逐行动表的“请求诊断”列展开每次请求的详情；翻页可查更早的尝试。
3. 完整 JSON 和逐行动 CSV 都携带诊断，导入另一电脑后仍可查看。

代码位置：`agent/DecisionDiagnostic.java`、`ai/ModelRequestException.java`、`game/ActionRecord.java`、`experiment/ExperimentImport.java`、`ExperimentRecord.java`、`ExperimentCsv.java`；前端 `components/DiagnosticDetails.vue`、`ObserverPanel.vue`、`views/StatsView.vue`。

实验引擎标记更新为 `0.3.0-context-diagnostics-v1`。模型快照与对照配置摘要增加超时、额度参数，方便区分不同实验条件。旧记录兼容默认值，但不生成不存在的历史错误原因。测试 `experiment/DiagnosticPersistenceTest` 验证本地保存/重启/导入/CSV、旧字段缺失与无效诊断拒绝。

## 验证与手动测试

在 `backend` 运行 `.\mvnw.cmd test`；在 `frontend` 运行 `npm run build`。

创建新的手动 LLM 对局，推进查验和用药后，检查相同玩家下一次决策是否依据真实线索与剩余药剂；进入第二天，检查存活名单与放逐公告。失败后查看诊断，再手动重试；成功时应只执行一次行动。完成后导出完整 JSON，再从实验记录导入，检查行动与诊断一致。

自动页面验收使用隔离端口、本地模拟模型和临时数据目录，验证两种工具模式的完整手动对局、接口拒绝后的原位重试、多工具回复纠错、错误本人编号/投票理由纠错、诊断展示及跨存档导入。未调用真实收费模型，真实模型的幻觉和服务可用性仍需要后续新对局验证。
