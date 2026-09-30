package com.example.werewolf.agent;

import com.example.werewolf.ai.LlmClient;
import com.example.werewolf.ai.LlmConfig;
import com.example.werewolf.game.ActionValidator;
import com.example.werewolf.message.GameMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 将本局可见信息转为模型请求；模型输出仍由规则引擎校验。 */
public final class LlmAgent implements Agent {
    private static final int HISTORY_CHAR_LIMIT = 6000;
    private static final int MESSAGE_CHAR_LIMIT = 500;
    private final LlmClient client;
    private final LlmConfig config;
    private final ObjectMapper mapper;

    public LlmAgent(LlmClient client, LlmConfig config, ObjectMapper mapper) {
        this.client = client;
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public AgentResponse act(AgentContext context) {
        String prompt = prompt(context);
        String lastProblem = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            // 网络或模型服务异常直接向上报告；只有回复格式及动作问题才请求模型纠正。
            String content = client.chat(config, attempt == 0 ? prompt : prompt +
                    "\n上次回复无法作为合法动作（" + lastProblem + "）。请重新只输出一个简短 JSON 对象。");
            try {
                AgentResponse response = parse(content);
                ActionValidator.validate(context, response);
                return response;
            } catch (JsonProcessingException | IllegalArgumentException e) {
                lastProblem = e instanceof JsonProcessingException ? "JSON 格式错误" : "动作或目标不合法";
            }
        }
        throw new IllegalStateException("模型连续两次未返回合法动作，请检查模型输出格式或增大 Max Tokens（" + lastProblem + "）");
    }

    private String prompt(AgentContext context) {
        var game = context.gameConfig();
        AgentContext compact = new AgentContext(context.playerId(), context.role(), context.phase(),
                context.dayNumber(), context.alivePlayerIds(), recentMessages(context.visibleMessages()),
                context.privateInformation(), context.availableActions(), context.legalTargets(),
                context.attackedPlayerId(), game);
        String format = context.availableActions().contains(ActionType.SPEAK)
                ? "发言只输出 {\"action\":\"SPEAK\",\"speech\":\"公开发言内容\",\"reasoning\":\"一句简短决策说明\"}，发言尽量简洁。"
                : "只输出 {\"action\":\"合法动作\",\"targetPlayerId\":\"合法玩家ID或null\",\"reasoning\":\"一句简短决策说明\"}；PASS 时目标为 null。";
        try {
            return "你正在参与 " + game.playerCount() + " 人狼人杀：" + game.werewolves() +
                    " 狼人、" + game.villagers() + " 村民、" + game.seers() +
                    " 预言家、" + game.witches() + " 女巫。" +
                    "狼人全死则好人胜；存活狼人数量不少于好人数量则狼人胜。" +
                    "不得自投；狼人不能杀狼人；女巫每夜最多一个动作，解药和毒药各限一次。" +
                    "只依据提供的信息决策，不得臆造隐藏身份。" +
                    "action 必须从 availableActions 中选；有目标时必须从对应 legalTargets 中原样复制玩家 ID。" +
                    "reasoning 只写不超过 80 字的决策摘要，不要输出详细思考过程、Markdown 或 JSON 以外的文字。" + format +
                    "游戏信息：" + mapper.writeValueAsString(compact);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法生成模型请求", e);
        }
    }

    /** 只裁剪模型输入中的旧发言；引擎保存的完整记录不受影响。 */
    private List<GameMessage> recentMessages(List<GameMessage> messages) {
        List<GameMessage> recent = new ArrayList<>();
        int chars = 0;
        for (int i = messages.size() - 1; i >= 0 && chars < HISTORY_CHAR_LIMIT; i--) {
            GameMessage message = messages.get(i);
            String content = message.content();
            if (content.length() > MESSAGE_CHAR_LIMIT)
                content = content.substring(0, MESSAGE_CHAR_LIMIT) + "…";
            if (!recent.isEmpty() && chars + content.length() > HISTORY_CHAR_LIMIT) break;
            recent.add(new GameMessage(message.day(), message.senderId(), content));
            chars += content.length();
        }
        return recent.reversed();
    }

    private AgentResponse parse(String content) throws JsonProcessingException {
        // 部分兼容模型会在 JSON 前后附加说明或代码围栏，提取首个完整对象再解析。
        JsonNode node = mapper.readTree(firstJsonObject(content == null ? "" : content));
        String actionText = node.path("action").asText("").strip().toUpperCase(Locale.ROOT);
        ActionType action;
        try {
            action = ActionType.valueOf(actionText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知动作", e);
        }
        String target = node.path("targetPlayerId").isTextual() ? node.path("targetPlayerId").asText() : null;
        String speech = node.path("speech").isTextual() ? node.path("speech").asText() : null;
        String reasoning = node.path("reasoning").isTextual() ? node.path("reasoning").asText() : null;
        return new AgentResponse(reasoning, speech, action, target);
    }

    private String firstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) throw new IllegalArgumentException("回复中没有 JSON 对象");
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\' && quoted) { escaped = true; continue; }
            if (ch == '"') { quoted = !quoted; continue; }
            if (!quoted && ch == '{') depth++;
            if (!quoted && ch == '}' && --depth == 0) return text.substring(start, i + 1);
        }
        throw new IllegalArgumentException("JSON 对象未完整结束");
    }
}
