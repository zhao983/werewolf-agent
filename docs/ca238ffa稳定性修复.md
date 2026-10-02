# ca238ffa：运行、输出稳定性与观察修复

日期：2026-10-03。修复依据为来源 `ca238ffa-a0f2-4c55-b20f-8ea920c406d2` 的只读分析。旧对局与本机知识库不修改，不补造历史请求或失败原因。

## 1. 模型兼容设置

本局使用 `qwen3.7-flash`、普通工具和 `max_tokens=1000`，121 次请求中有 70 次未通过动作或一致性检查，另有 1 次超时与 6 次截断。历史数据没有完整请求、失败参数或思考用量，不能断言所有失败原因相同。

百炼官方文档说明 Qwen3.7 Flash 默认开启思考，`max_tokens` 在该系列只限制最终回答，`max_completion_tokens` 则包含思考和回答；`tool_choice=required` 在 Qwen 有兼容限制，思考模式也不支持强制指定工具。因此，报告总输出高于 1000 不一定等于最终回答超预算。参考：[思考模式](https://help.aliyun.com/zh/model-studio/deep-thinking)、[Chat Completions 参数](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)。这些说明适用于对应服务，其他代理服务须核对自身支持情况。

新增设置：

- 服务端思考：服务默认不发送额外参数；显式开启／关闭发送顶层 `enable_thinking`。
- 工具选择：默认 `required` 保留旧兼容行为；兼容模式在关闭思考且仅一个工具时指定工具名，其他情况用 `auto`。后端仍要求恰好一个合法工具。
- “应用 Qwen 稳定性设置”：普通工具、兼容选择、关闭思考、`max_completion_tokens`；保留用户的模型、地址、密钥、温度、输出额度和超时。
- 没有根据模型名自动修改请求，也没有失败后自动切换模式、加大输出额度或默认行动，以免改变实验条件。

代码位置：`ai/LlmConfig.java`、`ToolChoiceMode.java`、`OpenAiCompatibleClient.java`，前端 `views/SettingsView.vue`、`types.ts`、`store.ts`。新增参数随模型快照、配置比较摘要与对局 CSV 保存，不包含服务地址或密钥。缺失参数的旧配置按旧行为读取。

## 2. 减少只因摘要格式产生的额外请求

普通工具模式的私有 `reasoning` 不再必填；缺少时保留空摘要并记录 `REASONING_DEFAULTED`。严格模式继续要求摘要字段。

摘要过长时先校验完整摘要，再本地保留最多 80 个 UTF-16 字符，避免截断代理对；记录 `REASONING_TRIMMED`。不替换模型选择的动作或目标，不裁剪公开发言，不把已有矛盾隐藏到被裁掉的后半段。缩短后的内容是摘要截取，页面明确显示已经修正，不能当作完整思考过程。

公开发言为空、超长、非法目标、错误动作或工具仍拒绝。格式与一致性问题最多再请求一次；超时、HTTP、网络、接口响应错误与截断直接暂停。合法动作不会因为已报告用量较高而被丢弃后再请求，避免重复付费。

代码位置：`agent/ActionOutput.java`、`LlmAgent.java`、`ai/GameActionTools.java`、`game/ActionValidator.java`。严格工具模式保留必填约束，普通工具参数仍禁止额外字段、重复 JSON 键和类型不符。

## 3. 防止反复点击持续消耗模型请求

同一玩家、同一轮次、同一阶段连续 3 次完整决策失败后，后端暂停新的请求。每次格式纠错最多两次，因此最坏情况下触发保护前为 6 次请求；网络或截断失败可能更少。成功后清零，进入新的行动位置也重新计数。

保护触发后继续推进不会新增请求，也不会追加一条虚假的失败行动。页面刷新后仍能从后端读到保护状态，禁用手动行动和自动推进。自动推进遇到首个错误仍立即暂停，不会等到保护阈值再停止。

创建者点击“解除重试保护”仅清除失败计数，不调用模型、不跳过玩家、不改票数或药剂，随后需明确再次推进。接口沿用创建者会话权限：`POST /api/games/{id}/retry`。在未触发保护时不能用该接口代替正常行动。

代码位置：`agent/Agent.java`、`LlmAgent.java`、`game/GameEngine.java`、`service/GameService.java`、`controller/GameController.java`，前端 `GameView.vue`、`store.ts`。游戏引擎只使用 Agent 接口，不依赖模型 HTTP 实现。

## 4. 可定位、可导出的安全诊断

诊断保留原有 SUCCESS／INVALID_ACTION／INCONSISTENT_ACTION 等主分类，新增固定子类型：缺少或多个工具、错误工具名、JSON 解析、缺少或额外字段、类型错误、非法目标、发言过长，以及编号、投票、用药历史、夜晚事实、狼人数、胜负和狼刀回执矛盾。

每次请求可记录本地修正、报告总输出 token、报告思考 token。服务未返回用量时保留未知；无效的思考用量不视作真实数值。`max_tokens` 的比较在思考用量已知时扣除思考部分；`max_completion_tokens` 使用总输出比较。该提示仍须结合实际服务的额度语义理解，不是计费或服务违约判断。

不保存原始提示、参数、响应、`reasoning_content`、密钥或服务地址；错误子类型和修正均为固定枚举。诊断随 JSON／逐行动 CSV、保存与导入保留，导入限制枚举、修正数量和非负用量。旧记录没有子类型或思考用量时显示原有诊断，不补造原因。

代码位置：`agent/DecisionDiagnostic.java`、`ai/LlmClient.java`、`OpenAiCompatibleClient.java`、`experiment/ExperimentImport.java`、`ExperimentRecord.java`，前端 `experiments.ts`、`components/DiagnosticDetails.vue`、`StatsView.vue`。引擎版本为 `0.4.0-stability-v2`。

## 5. 降低轮次、残局与狼刀记忆混淆

- `DecisionFacts` 从裁剪前的公开事实汇总已公布夜晚及死亡名单、已发言／投票名单、尚未行动的存活玩家，以及由“游戏仍进行”可推导的狼人数上界。它不读取完整角色表。
- 狼刀汇总后向每名存活狼人写入本夜最终目标的私有回执，区分个人意向和实际执行；不附带女巫用药、查验或其他角色身份。其他 Agent 不会收到该回执。
- 核心提示补充救人早于死亡结算、救人可以产生平安夜、好人胜利要求狼归零、四人残局误投后未必还有白天等固定规则；本地知识库继续只包含狼人杀知识。
- 保守检查私有摘要中的明确夜晚结果、狼人数、胜负条件与已知狼刀回执矛盾。狼人公开欺骗、隐藏查验、讨论过去轮次或假设局面仍可进行，不使用观战的真实身份纠正模型。
- 公开发言输入预算由约 6000 降为约 4500 字符；结构化进度从裁剪前计算，裁剪不能把已发言玩家变成未发言。

代码位置：`agent/DecisionFacts.java`、`DecisionConsistency.java`、`LlmAgent.java`、`game/GameEngine.java`。这些检查不能覆盖全部自然语言表述，不能保证消除幻觉或错误策略。

## 6. 验证与使用

后端：在 `backend` 运行 `.\mvnw.cmd test`；前端：在 `frontend` 运行 `npm run build`。

新增／增强的回归：

- `LlmStabilityTest`：摘要本地修正仅一次请求、普通与严格模式差异、完整摘要中被裁剪部分的矛盾、公开发言不裁剪、失败保护、原局轮次与残局错误、历史狼刀回执。
- `ProviderCompatibilityTest`：实际本地 HTTP 请求包含选定额度和思考参数、单工具具名／多工具 auto、旧配置不发送额外参数、报告思考用量的预算比较、空 choices 不纠错重试。
- `AgentObservationTest`：最终狼刀回执只向狼人提供。
- `DiagnosticPersistenceTest`：新增诊断与配置随保存、重启、导入和 CSV 保留，无效字段拒绝。
- `GameControllerSecurityTest`：陌生会话不能解除保护，未触发保护时恢复接口不推进。

本次实际验证：97 项后端测试全部通过，前端构建成功。独立模拟服务及浏览器验收验证了首次错误自动暂停、6 次无效请求后触发保护、解除保护不调用模型、恢复后单次请求成功，并完成含 22 次合法行动的完整对局；同时验证了旧 ca238ffa 记录导入和手机布局，页面无脚本错误。

模拟测试不调用真实收费模型。实际服务是否支持参数、输出质量是否改善，仍需新建真实对局观察；不要宣称历史 70 次无效回复已全部解决。

部署需要重启后端才能加载这些 Java 修改。重启会中断仍在内存中的对局，但已落盘的实验和知识库保留。新建对局后选择适合服务的配置，检查工具缺失和截断是否减少，并比较非法回复率、纠错请求、请求耗时及报告用量。

本次经用户确认后已重启实际后端，继续使用 `backend/data/experiments` 和 `backend/data/knowledge`。核对结果：14 份存档保留，ca238ffa 文件内容未变，14 条知识及知识库修订值未变；两份未完成实验标记为已中断，保留已有回放。前端地址仍为 `http://127.0.0.1:5173/`。
