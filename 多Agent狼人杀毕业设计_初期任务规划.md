# 多 Agent 狼人杀毕业设计：初期开发任务规划

> 用途：本文件用于将项目背景、技术路线、第一阶段开发目标、任务拆分、验收标准和后续扩展方向完整交接给其他 AI 或开发者。  
> 当前阶段重点：**先完成一个可独立运行、规则可靠、与 AI 解耦的 Java 狼人杀核心引擎。**

---

## 1. 项目背景

本毕业设计研究方向为：

**多 Agent 场景下的协同与对抗。**

选择狼人杀作为实验环境，不同 Agent 在游戏中拥有不同身份和阵营：

- 狼人阵营内部需要协作；
- 好人阵营需要通过发言、信息分析和投票进行合作；
- 狼人阵营与好人阵营之间存在明显对抗；
- 各 Agent 在不完全信息环境中进行推理、决策和博弈；
- 最终所有 Agent 都以各自阵营获胜为目标。

后期计划接入大语言模型，使每个玩家成为可独立推理和行动的 LLM Agent。

本项目不限定固定模型提供商，而是采用 **OpenAI-Compatible API** 兼容设计，使用户可自行配置：

- API Base URL
- API Key
- Model
- Temperature
- Max Tokens 等参数

从而可以接入 OpenAI、DeepSeek、Qwen、本地 vLLM、Ollama 的兼容接口或其他提供 OpenAI 格式 API 的模型服务。

---

# 2. 已确定技术栈

## 2.1 后端

主要技术：

- Java 17 或 Java 21
- Spring Boot 3.x
- Maven
- Spring Web
- Jackson
- Lombok（可选）
- Spring WebSocket（后期）
- Spring Data JPA（后期）
- MySQL（后期）

### 后端职责

Spring Boot 后端负责：

1. 狼人杀游戏规则；
2. 游戏状态维护；
3. Agent 调度；
4. 信息隔离；
5. Agent 合法行动校验；
6. AI 模型请求；
7. 游戏日志；
8. 实验数据记录；
9. 为 Vue3 提供 REST / WebSocket 接口。

---

## 2.2 前端

后期采用：

- Vue 3
- Vite
- TypeScript
- Pinia
- Vue Router
- Axios
- Element Plus
- WebSocket

### 前端职责

Vue3 主要负责：

- 创建游戏；
- AI 模型配置；
- 游戏过程展示；
- 玩家状态展示；
- 发言记录；
- 昼夜切换；
- 身份牌、角色素材展示；
- 对局历史；
- 实验结果和统计数据展示。

**注意：第一阶段暂时不开发 Vue3 前端。**

---

# 3. 核心设计原则

整个项目必须遵守以下原则。

## 3.1 LLM 负责决策，程序负责规则

这是整个系统最重要的设计原则。

例如：

LLM 可以决定：

- 狼人杀谁；
- 预言家验谁；
- 女巫是否用药；
- 玩家投谁；
- 玩家如何发言。

但以下内容必须由 Java 程序决定：

- 当前处于白天还是夜晚；
- 哪些玩家存活；
- 某个行动是否合法；
- 药是否已经使用；
- 谁死亡；
- 游戏是否结束；
- 哪个阵营获胜。

严禁让 LLM 直接修改游戏状态。

---

## 3.2 游戏逻辑与 AI 解耦

即使完全没有配置任何 AI API，游戏引擎仍然必须能够运行。

因此 Agent 统一抽象为接口，例如：

```java
public interface Agent {

    AgentResponse act(AgentContext context);

}
```

初期实现：

- RandomAgent
- RuleAgent

后期实现：

- LlmAgent

GameEngine 不应该关心 Agent 内部是随机逻辑、规则逻辑还是大语言模型。

---

## 3.3 信息隔离必须由代码保证

狼人杀属于不完全信息游戏。

不能仅通过 Prompt 告诉模型：

> “你不应该知道这些信息。”

而应该在 Java 程序中直接保证该 Agent 无法获取这些信息。

例如：

- 公共发言：所有存活玩家可见；
- 狼人夜间讨论：仅狼人可见；
- 预言家验人结果：仅预言家可见；
- 女巫获得的夜间死亡信息：仅女巫可见；
- 玩家真实身份：仅 GameEngine 知道；
- 每个 Agent 只知道自己被允许知道的信息。

---

## 3.4 Agent 输出必须结构化

不要让 Agent 仅返回自然语言。

例如狼人行动应返回类似：

```json
{
  "reasoning": "Player5 可能是预言家",
  "action": "KILL",
  "targetPlayerId": "player5"
}
```

投票：

```json
{
  "action": "VOTE",
  "targetPlayerId": "player3"
}
```

这样 Java 后端可以直接校验。

---

# 4. 第一阶段游戏规则

第一阶段采用简化 **7 人狼人杀**。

## 4.1 身份配置

| 身份 | 数量 |
|---|---:|
| 狼人 | 2 |
| 普通村民 | 3 |
| 预言家 | 1 |
| 女巫 | 1 |

共 7 人。

---

## 4.2 暂不加入的角色

第一阶段不要加入：

- 猎人
- 守卫
- 白狼王
- 丘比特
- 狐狸
- 警长
- 其他扩展角色

这样可以降低游戏状态机复杂度。

---

# 5. 第一阶段基础游戏流程

建议游戏流程如下。

```text
创建游戏
  ↓
创建 7 名玩家
  ↓
随机分配身份
  ↓
Night 1
  ↓
狼人行动
  ↓
预言家行动
  ↓
女巫行动
  ↓
统一结算夜间死亡
  ↓
Day 1
  ↓
公布昨夜死亡玩家
  ↓
存活玩家依次发言
  ↓
投票
  ↓
放逐玩家
  ↓
胜负判断
  ↓
Night 2
  ↓
……
  ↓
游戏结束
```

---

# 6. 第一阶段胜负规则

建议暂时采用简单规则。

## 好人胜利

所有狼人全部死亡。

## 狼人胜利

当：

```text
存活狼人数量 >= 存活好人数量
```

则狼人阵营获胜。

此规则后期可配置。

---

# 7. 建议 Java 项目结构

建议包结构如下：

```text
src/main/java/com/example/werewolf/
│
├── WerewolfApplication.java
│
├── config/
│
├── controller/
│
├── game/
│   ├── GameEngine.java
│   ├── GameState.java
│   ├── GamePhase.java
│   ├── GameResult.java
│   └── GameRule.java
│
├── player/
│   ├── Player.java
│   ├── PlayerStatus.java
│   └── Role.java
│
├── role/
│   ├── WerewolfRole.java
│   ├── VillagerRole.java
│   ├── SeerRole.java
│   └── WitchRole.java
│
├── agent/
│   ├── Agent.java
│   ├── RandomAgent.java
│   ├── RuleAgent.java
│   ├── LlmAgent.java
│   ├── AgentContext.java
│   ├── AgentAction.java
│   └── AgentResponse.java
│
├── message/
│   ├── GameMessage.java
│   ├── MessageType.java
│   ├── MessageVisibility.java
│   └── MessageBus.java
│
├── ai/
│   ├── LlmClient.java
│   ├── OpenAiCompatibleClient.java
│   ├── LlmRequest.java
│   ├── LlmResponse.java
│   └── LlmConfig.java
│
├── memory/
│   ├── AgentMemory.java
│   └── MemoryService.java
│
├── service/
│   ├── GameService.java
│   └── AgentService.java
│
└── evaluation/
    ├── GameMetrics.java
    └── EvaluationService.java
```

注意：

**第一阶段不需要把这些模块全部实现。**

当前重点仅实现：

```text
game
player
agent
少量 message
```

---

# 8. 第一阶段需要实现的核心类

## 8.1 Role

推荐：

```java
public enum Role {

    WEREWOLF,
    VILLAGER,
    SEER,
    WITCH

}
```

如果后续角色逻辑越来越复杂，可以再引入 RoleStrategy 或角色类层级。

第一阶段不必过度设计。

---

## 8.2 PlayerStatus

例如：

```java
public enum PlayerStatus {

    ALIVE,
    DEAD

}
```

后续可扩展：

- EXILED
- KILLED
- POISONED

但第一版可以简单记录 ALIVE / DEAD，再额外记录死亡原因。

---

## 8.3 Player

至少包含：

```text
id
name
role
status
agent
```

示例设计：

```java
public class Player {

    private String id;

    private String name;

    private Role role;

    private PlayerStatus status;

    private Agent agent;

}
```

注意：

Player 代表游戏内玩家实体。

Agent 代表控制该玩家进行决策的智能体。

两者不要混为一个类。

---

## 8.4 GamePhase

例如：

```java
public enum GamePhase {

    GAME_START,

    NIGHT_WEREWOLF,

    NIGHT_SEER,

    NIGHT_WITCH,

    NIGHT_RESOLVE,

    DAY_ANNOUNCEMENT,

    DAY_DISCUSSION,

    DAY_VOTE,

    DAY_RESOLVE,

    GAME_OVER

}
```

使用明确的 Phase 可以让状态机更加清晰。

---

## 8.5 GameResult

例如：

```java
public enum GameResult {

    ONGOING,

    WEREWOLF_WIN,

    VILLAGER_WIN

}
```

---

## 8.6 GameState

GameState 表示当前完整游戏状态。

建议包含：

```text
gameId
dayNumber
gamePhase
players
lastNightDeaths
currentSpeaker
gameResult
```

后续增加：

- voteRecords
- nightActions
- publicMessages
- privateMessages
- actionHistory
- roundHistory

---

# 9. Agent 抽象

## 9.1 Agent

建议：

```java
public interface Agent {

    AgentResponse act(AgentContext context);

}
```

后期如果需要支持不同类型行为，可以进一步拆分：

```text
speak()
vote()
nightAction()
```

但第一阶段建议先保持统一接口。

---

## 9.2 AgentContext

AgentContext 的目标是：

**只向 Agent 提供它有权知道的信息。**

建议至少包含：

```text
playerId
role
phase
alivePlayers
visibleMessages
privateInformation
availableActions
```

例如：

```java
public class AgentContext {

    private String playerId;

    private Role role;

    private GamePhase phase;

    private List<String> alivePlayerIds;

    private List<GameMessage> visibleMessages;

    private List<String> privateInformation;

    private List<ActionType> availableActions;

}
```

严禁直接把整个 GameState 无过滤传给 Agent。

否则以后 LLM 可能获取不应该知道的信息。

---

# 10. AgentAction / ActionType

建议定义：

```java
public enum ActionType {

    SPEAK,

    VOTE,

    KILL,

    CHECK,

    SAVE,

    POISON,

    PASS

}
```

后续可增加：

- END_DISCUSSION
- SELF_EXPLODE
- PROTECT

---

# 11. AgentResponse

建议：

```java
public class AgentResponse {

    private String reasoning;

    private String speech;

    private ActionType action;

    private String targetPlayerId;

}
```

说明：

### reasoning

Agent 内部推理。

不应该直接展示给其他玩家。

### speech

需要公开发布的发言。

只有 SPEAK 阶段使用。

### action

结构化动作。

### targetPlayerId

动作目标。

---

# 12. 第一阶段 Agent 类型

## 12.1 RandomAgent

这是第一阶段最重要的 Agent。

目标：

从当前允许的合法行动中随机选择。

例如：

### 狼人

随机选择一名非狼人存活玩家。

### 预言家

随机选择一名其他存活玩家查验。

### 女巫

随机选择：

- SAVE
- POISON
- PASS

但必须符合游戏规则。

### 投票

随机投给一名其他存活玩家。

RandomAgent 用于：

- 验证 GameEngine；
- 验证游戏可以完整跑完；
- 发现状态机 Bug；
- 后期作为实验基线。

---

## 12.2 RuleAgent

第二阶段可以开发。

RuleAgent 不调用 LLM，使用简单规则决策。

例如：

```text
狼人：
优先杀疑似预言家玩家。

村民：
优先投票给票型异常的人。

预言家：
查验未被验过的人。

女巫：
第一夜倾向不毒人。
```

第一阶段不强制完成。

---

# 13. GameEngine 职责

GameEngine 是整个项目第一阶段的核心。

它应该负责：

1. 初始化游戏；
2. 创建玩家；
3. 分配角色；
4. 控制阶段；
5. 调用 Agent；
6. 校验 Agent 动作；
7. 更新状态；
8. 结算死亡；
9. 处理投票；
10. 判断胜负；
11. 记录日志。

GameEngine 不负责：

- AI Prompt；
- HTTP 请求；
- Vue 界面；
- 数据库存储。

---

# 14. 行动合法性校验

建议实现：

```text
ActionValidator
```

或在 GameEngine 内部先实现基础校验。

必须保证：

## 狼人

只能：

```text
KILL
PASS
```

且不能：

- 杀自己；
- 杀其他狼人；
- 杀死人；
- 杀不存在的 Player。

---

## 预言家

只能：

```text
CHECK
```

目标必须：

- 存活；
- 不是自己。

---

## 女巫

可以：

```text
SAVE
POISON
PASS
```

要求：

- 解药只能使用一次；
- 毒药只能使用一次；
- 不能毒已死亡玩家；
- 是否允许自救请先固定规则。

推荐第一版：

**允许第一夜自救与否不重要，但必须在 GameRule 中写死，保证所有实验规则一致。**

---

## 白天投票

只能：

```text
VOTE
```

且：

- 死亡玩家不能投票；
- 不能投死人；
- 是否允许自投必须写清楚。

推荐第一版：

**禁止自投。**

---

# 15. 狼人协同行动

因为存在 2 名狼人，需要解决两名狼人杀人目标不一致的问题。

第一阶段建议最简单：

```text
Wolf1 -> Player4

Wolf2 -> Player4

=> kill Player4
```

若：

```text
Wolf1 -> Player4

Wolf2 -> Player6
```

可以采用以下一种规则：

### 推荐第一版

随机从狼人投票目标中选择一个。

或者采用多数票。

但两名狼人情况下可能平票，所以建议：

```text
平票时随机选择一个目标
```

后期接入 LLM 后，再增加：

```text
狼人夜间私聊
↓
协商
↓
统一投票
```

第一阶段不必实现复杂协商。

---

# 16. 预言家逻辑

预言家行动：

```text
CHECK PlayerX
```

GameEngine 返回：

```text
WEREWOLF
```

或者：

```text
GOOD
```

建议不要直接返回具体角色：

```text
WITCH
SEER
VILLAGER
```

而只返回：

```text
WEREWOLF
GOOD
```

更加符合常见狼人杀规则。

该结果：

**只能进入预言家自己的 privateInformation。**

---

# 17. 女巫逻辑

女巫拥有：

```text
1 瓶解药
1 瓶毒药
```

建议字段：

```java
private boolean antidoteAvailable = true;

private boolean poisonAvailable = true;
```

夜晚 GameEngine 告诉女巫：

```text
今晚被狼人攻击的是 Player5
```

女巫可以：

```text
SAVE Player5
```

也可以：

```text
POISON Player3
```

也可以：

```text
PASS
```

第一阶段建议明确：

**同一夜是否允许同时使用解药和毒药？**

推荐简化：

```text
每晚最多执行一个女巫动作
```

即：

SAVE / POISON / PASS 三选一。

后期如果需要更贴近具体狼人杀版本，再修改规则。

---

# 18. 夜间死亡统一结算

非常重要：

不要在狼人行动后立即：

```java
player.setDead();
```

应该先记录：

```text
NightAction
```

例如：

```text
wolfKillTarget = Player5
witchSaveTarget = Player5
witchPoisonTarget = null
```

全部行动完成后进入：

```text
NIGHT_RESOLVE
```

再统一结算。

例如：

```text
狼人杀 Player5
女巫救 Player5
=> Player5 不死
```

如果：

```text
狼人杀 Player5
女巫毒 Player3
=> Player5 + Player3 死亡
```

这样可以避免顺序错误。

---

# 19. 白天发言

第一阶段即使 RandomAgent 不真正生成语言，也建议保留 SPEAK 阶段。

例如：

```text
Player1: Random speech from Player1
Player2: Random speech from Player2
```

原因：

后期接入 LLM 时可以直接替换。

GameEngine 不需要重新设计流程。

---

# 20. 白天投票

所有存活玩家投票。

统计：

```text
Player1 -> Player3
Player2 -> Player3
Player3 -> Player2
Player4 -> Player3
```

Player3 被放逐。

## 平票规则

第一阶段推荐：

```text
平票时随机从最高票玩家中选择一个放逐
```

原因：

简单且可以保证游戏正常推进。

后期可扩展：

```text
PK 发言
重新投票
```

但当前不要做。

---

# 21. 游戏日志

第一阶段必须有完整 Console 日志。

期望运行效果：

```text
======== GAME START ========

Player1 -> WEREWOLF
Player2 -> VILLAGER
Player3 -> VILLAGER
Player4 -> SEER
Player5 -> WITCH
Player6 -> WEREWOLF
Player7 -> VILLAGER

======== NIGHT 1 ========

Wolf Player1 votes kill Player4
Wolf Player6 votes kill Player4

Werewolves choose: Player4

Seer Player4 checks Player2
Result: GOOD

Witch chooses PASS

Night result:
Player4 died

======== DAY 1 ========

Player4 died last night.

Player1 speaks...
Player2 speaks...
...

======== VOTE ========

Player1 -> Player3
Player2 -> Player6
...

Player6 is exiled.

======== CHECK WINNER ========

Game continues.

======== NIGHT 2 ========
...
```

最后：

```text
======== GAME OVER ========

VILLAGER WIN
```

或：

```text
WEREWOLF WIN
```

---

# 22. Milestone 1：第一阶段任务列表

以下任务必须按顺序完成。

---

## Task 1：初始化 Spring Boot 项目

要求：

- Java 17/21
- Spring Boot 3.x
- Maven
- 项目可正常启动
- 不需要数据库
- 不需要前端
- 不需要 AI

验收：

```bash
mvn test
```

成功。

Spring Boot 正常启动。

---

## Task 2：实现基础枚举

至少：

```text
Role
PlayerStatus
GamePhase
GameResult
ActionType
```

验收：

代码可编译。

---

## Task 3：实现 Player

要求包含：

```text
id
name
role
status
agent
```

提供：

```text
isAlive()
die()
```

等基础方法。

---

## Task 4：实现 Agent 接口

实现：

```text
Agent
AgentContext
AgentResponse
```

要求：

GameEngine 通过 Agent 接口获取玩家行为。

---

## Task 5：实现 RandomAgent

至少支持：

```text
KILL
CHECK
SAVE
POISON
PASS
SPEAK
VOTE
```

但 RandomAgent 不能直接修改游戏状态。

---

## Task 6：实现 GameState

能够记录：

```text
players
phase
dayNumber
result
nightDeaths
```

---

## Task 7：实现角色随机分配

要求：

每局固定：

```text
2 WEREWOLF
3 VILLAGER
1 SEER
1 WITCH
```

并随机打乱。

---

## Task 8：实现夜晚狼人阶段

实现：

```text
NIGHT_WEREWOLF
```

调用所有存活狼人 Agent。

最终确定击杀目标。

---

## Task 9：实现预言家阶段

实现：

```text
NIGHT_SEER
```

预言家选择目标。

GameEngine 返回：

```text
WEREWOLF / GOOD
```

结果只能保存在预言家的私有信息中。

---

## Task 10：实现女巫阶段

实现：

```text
NIGHT_WITCH
```

支持：

```text
SAVE
POISON
PASS
```

并追踪药物剩余状态。

---

## Task 11：实现夜间统一结算

实现：

```text
NIGHT_RESOLVE
```

保证：

- 狼人杀人；
- 女巫救人；
- 女巫毒人；

按照统一规则进行结算。

---

## Task 12：实现白天公布死亡

进入：

```text
DAY_ANNOUNCEMENT
```

输出夜晚死亡玩家。

---

## Task 13：实现白天发言

存活玩家依次执行：

```text
SPEAK
```

第一阶段 RandomAgent 可以输出简单占位内容。

---

## Task 14：实现投票

进入：

```text
DAY_VOTE
```

要求：

- 所有存活玩家投票；
- 统计票数；
- 最高票被放逐；
- 平票随机处理。

---

## Task 15：实现胜负判断

每次：

```text
夜间结算后
白天放逐后
```

都必须判断胜负。

---

## Task 16：实现完整自动游戏循环

要求：

```java
gameEngine.start();
```

之后游戏可以自动运行到结束。

不需要人工输入。

---

## Task 17：添加日志

必须能看清：

```text
当前天数
当前阶段
玩家行动
死亡情况
投票情况
最终结果
```

---

## Task 18：编写基础测试

至少测试：

```text
角色数量是否正确

狼人全部死亡 -> 好人胜利

狼人数量 >= 好人数 -> 狼人胜利

死人不能行动

女巫药不能重复使用

预言家不能查死人

狼人不能杀狼人
```

推荐使用：

```text
JUnit 5
```

---

# 23. Milestone 1 验收标准

第一阶段完成时，必须满足：

- [ ] Spring Boot 项目可正常运行
- [ ] 7 名玩家创建成功
- [ ] 身份随机分配正确
- [ ] 2 狼 + 3 村 + 1 预言家 + 1 女巫
- [ ] 游戏具备完整昼夜循环
- [ ] 狼人可以选择击杀目标
- [ ] 预言家可以查验
- [ ] 女巫可以使用解药 / 毒药
- [ ] 白天可以发言
- [ ] 白天可以投票
- [ ] 可以放逐玩家
- [ ] 死亡玩家不会继续行动
- [ ] 游戏可以判断胜负
- [ ] RandomAgent 可以自动完成整局游戏
- [ ] 无需人工输入即可从开始运行到结束
- [ ] Console 日志可以完整查看全过程
- [ ] 不依赖任何 AI API
- [ ] 不依赖数据库
- [ ] 不依赖 Vue
- [ ] 核心逻辑具有基础单元测试

---

# 24. 第一阶段明确禁止提前开发的功能

为了防止范围失控，第一阶段不要提前开发以下内容：

## 不做 Vue3

当前只要求 Console 游戏。

---

## 不做 OpenAI API

AI 接入属于下一阶段。

---

## 不做数据库

所有数据存内存。

---

## 不做 WebSocket

等 Vue 开发时再加入。

---

## 不做复杂 Prompt

当前没有 LLM。

---

## 不做 Agent Memory

Memory 属于后期研究功能。

---

## 不做 RAG

当前项目没有必要。

---

## 不做强化学习

RL / GRPO 等属于非常后期可选扩展。

---

## 不做完整狼人杀角色

当前只：

```text
狼人
村民
预言家
女巫
```

---

# 25. 第二阶段规划：OpenAI-Compatible AI 接入

Milestone 1 完成后开始。

目标：

实现统一：

```java
public interface LlmClient {

    String chat(LlmRequest request);

}
```

然后：

```text
LlmAgent
      ↓
LlmClient
      ↓
OpenAiCompatibleClient
      ↓
任意 OpenAI-Compatible API
```

配置：

```text
baseUrl
apiKey
model
temperature
maxTokens
```

例如：

```yaml
ai:
  base-url: https://api.example.com/v1
  api-key: ${AI_API_KEY:}
  model: model-name
  temperature: 0.7
  max-tokens: 1000
```

---

# 26. AI 配置安全原则

严禁：

```text
Vue -> 模型 API
```

因为会泄漏 API Key。

必须：

```text
Vue
 ↓
Spring Boot
 ↓
AI API
```

API Key 只由后端使用。

开发阶段允许：

```text
配置保存在内存
```

后期再考虑加密数据库存储。

---

# 27. Model Profile 规划

后期建议允许用户保存多个模型配置。

例如：

```text
GPT Profile

Base URL
API Key
Model
```

```text
DeepSeek Profile
```

```text
Local Qwen Profile
```

这样可以配置：

```text
Player1 -> GPT
Player2 -> GPT
Player3 -> DeepSeek
Player4 -> Qwen
```

为后续模型对抗实验提供支持。

---

# 28. 第三阶段规划：LLM Agent

实现：

```text
LlmAgent
```

LLM 接收到：

```text
自己的身份
游戏规则
当前阶段
公共信息
私有信息
可执行动作
历史消息
```

返回 JSON。

例如：

```json
{
  "reasoning": "Player5 更像预言家",
  "speech": null,
  "action": "KILL",
  "targetPlayerId": "player5"
}
```

Java 必须再次校验动作是否合法。

---

# 29. Private Reasoning 与 Public Speech 分离

后期 LLM Agent 必须严格区分：

```text
reasoning
```

和：

```text
speech
```

例如狼人内部可能推理：

```text
我是狼人。
Player2 是狼队友。
Player5 可能是预言家。
今天考虑踩 Player2 做身份。
```

但公开发言只能是：

```text
Player2 今天的逻辑存在明显问题，
我暂时更怀疑 Player2。
```

不能把内部身份信息泄露给公共频道。

---

# 30. 第四阶段规划：消息与信息隔离系统

建议：

```java
public enum MessageVisibility {

    PUBLIC,

    WEREWOLF_ONLY,

    PRIVATE

}
```

后续：

```text
MessageBus
```

提供：

```text
getVisibleMessages(Player player)
```

而不是：

```text
getAllMessages()
```

保证每个 Agent 只能接收到合法信息。

---

# 31. 第五阶段规划：Memory

后期增加：

```text
AgentMemory
```

可分：

```text
短期记忆
```

当前对局的信息。

和：

```text
长期记忆
```

历史游戏经验。

研究：

```text
无 Memory
VS
有 Memory
```

是否影响 Agent 表现。

---

# 32. 第六阶段规划：Vue3

后期 UI 至少包括：

## 首页

```text
开始游戏
历史对局
AI 设置
实验统计
```

## 游戏页面

显示：

```text
Day / Night
玩家
身份隐藏牌
死亡状态
当前发言
投票
游戏日志
```

使用之前准备的狼人杀身份牌、白天/黑夜背景、解药和毒药等素材。

---

# 33. 第七阶段规划：WebSocket

游戏事件通过 WebSocket 推送给 Vue。

例如：

```json
{
  "type": "PLAYER_SPEAK",
  "playerId": "player3",
  "content": "我认为 Player5 有问题"
}
```

```json
{
  "type": "PLAYER_DIED",
  "playerId": "player5"
}
```

```json
{
  "type": "PHASE_CHANGED",
  "phase": "NIGHT_WEREWOLF"
}
```

---

# 34. 第八阶段规划：数据库

后期加入 MySQL。

可能的数据表：

```text
game

game_player

game_message

agent_action

game_result

model_profile

experiment
```

注意：

API Key 如果保存必须加密。

---

# 35. 毕业论文后期研究方向

项目不能仅停留在：

> “实现一个 AI 狼人杀游戏。”

应该围绕可验证问题开展实验。

推荐研究：

> 不同协作机制对多 Agent 狼人杀博弈性能的影响。

---

# 36. 实验分组建议

## Group A：Baseline

```text
LLM
+
当前游戏信息
```

没有额外 Memory。

---

## Group B：Memory

```text
LLM
+
当前信息
+
历史记忆
```

---

## Group C：Cooperation

```text
LLM
+
Memory
+
阵营协作机制
```

例如狼人私聊、结构化团队信息共享。

---

# 37. 后期实验指标

建议记录：

| 指标 | 说明 |
|---|---|
| 阵营胜率 | 狼人 / 好人获胜比例 |
| 平均游戏轮数 | 一局平均持续多少天 |
| 身份判断准确率 | Agent 对其他玩家身份判断是否正确 |
| 投票准确率 | 好人投中狼人的比例 |
| 非法行动率 | LLM 返回非法动作的比例 |
| Agent 协作一致性 | 同阵营策略是否一致 |
| Token 消耗 | 每局模型调用成本 |
| API 调用次数 | 每局调用次数 |
| 平均响应时间 | 每次 Agent 决策耗时 |

---

# 38. 推荐开发路线总览

```text
Milestone 1
Java 核心狼人杀引擎
        ↓
Milestone 2
RandomAgent / RuleAgent
        ↓
Milestone 3
OpenAI-Compatible LlmClient
        ↓
Milestone 4
LlmAgent
        ↓
Milestone 5
信息隔离
        ↓
Milestone 6
狼人协作通信
        ↓
Milestone 7
Agent Memory
        ↓
Milestone 8
Vue3
        ↓
Milestone 9
WebSocket
        ↓
Milestone 10
MySQL + 日志
        ↓
Milestone 11
实验平台
        ↓
Milestone 12
毕业论文实验
```

---

# 39. 给后续 AI 的明确执行要求

如果你是接手本项目的 AI，请遵循以下要求。

## 要求 1

不要一次性开发完整系统。

当前任务只执行：

```text
Milestone 1
```

---

## 要求 2

代码必须首先保证：

```text
完整运行
规则正确
结构清晰
容易扩展
```

而不是追求复杂设计模式。

---

## 要求 3

不要为了“架构高级”进行过度抽象。

如果第一阶段简单 enum + class 可以解决，就不要建立过深继承体系。

---

## 要求 4

每完成一个核心模块后，应提供：

```text
代码
说明
测试方法
运行结果
```

---

## 要求 5

如果发现需求存在未明确细节，优先使用本文给出的推荐简化规则，不要自行扩大项目范围。

---

## 要求 6

保持 AI 与 GameEngine 解耦。

不能在 GameEngine 中直接写 OpenAI HTTP 请求。

---

## 要求 7

所有游戏动作必须经过 Java 规则验证。

不能相信 Agent 输出一定合法。

---

## 要求 8

永远不要把完整 GameState 直接暴露给 Agent。

必须通过：

```text
AgentContext
```

进行信息过滤。

---

# 40. 当前最优先任务

现在开始开发时，优先顺序为：

```text
1. 创建 Spring Boot 项目

2. Role

3. PlayerStatus

4. GamePhase

5. GameResult

6. ActionType

7. Player

8. Agent

9. AgentContext

10. AgentResponse

11. RandomAgent

12. GameState

13. GameEngine

14. 角色分配

15. 夜晚流程

16. 白天流程

17. 胜负判断

18. 完整自动游戏

19. Console 日志

20. JUnit 测试
```

最终目标：

```text
只运行 Spring Boot / Java 程序，
不连接 AI，
不打开浏览器，
不需要数据库，
即可让 7 个 RandomAgent
从开局自动运行到某一阵营获胜。
```

如果这一目标实现，即认为：

**Milestone 1 完成。**

---

# 41. 项目当前阶段的一句话总结

> 当前不要急于接入 AI。先用 Java Spring Boot 构建一个规则完全由程序控制、Agent 与游戏逻辑解耦、能够由 RandomAgent 自动完成整局 7 人狼人杀的可靠核心引擎，为后续 LLM、多 Agent 协同、信息隔离、记忆系统、Vue3 可视化和毕业论文实验打基础。
