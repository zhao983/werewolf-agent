package com.example.werewolf.ai;

import com.example.werewolf.agent.*;
import com.example.werewolf.agent.ActionOutput.Problem;
import com.example.werewolf.agent.DecisionDiagnostic.OutputIssue;
import com.example.werewolf.game.ActionValidator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

/** 将本次合法动作映射为白名单工具；仅构造行动请求，状态修改仍由游戏引擎负责。 */
public final class GameActionTools {
    private GameActionTools() { }

    public static String name(ActionType action) {
        return switch (action) {
            case KILL -> "wolf_attack";
            case VOTE -> "vote_player";
            case CHECK -> "check_player";
            case SAVE -> "use_antidote";
            case POISON -> "use_poison";
            case PASS -> "skip_action";
            case SPEAK -> "speak";
        };
    }

    public static List<Map<String, Object>> definitions(AgentContext context, boolean strict) {
        return context.availableActions().stream().map(action -> definition(context, action, strict)).toList();
    }

    private static Map<String, Object> definition(AgentContext context, ActionType action, boolean strict) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reasoning", Map.of("type", "string", "description", "不超过80字的简短决策说明，可为空；不是公开发言"));
        if (action == ActionType.SPEAK)
            properties.put("speech", Map.of("type", "string", "description", "不超过500字的公开发言，必须非空"));
        else if (action != ActionType.PASS)
            properties.put("targetPlayerId", Map.of("type", "string", "enum", context.legalTargets().getOrDefault(action, List.of()),
                    "description", "必须从合法玩家ID枚举中选择"));
        String description = switch (action) {
            case KILL -> "提交狼人袭击意向；狼队汇总后每晚只有一个最终袭击目标";
            case VOTE -> "提交白天放逐投票";
            case CHECK -> "查验一名玩家，结果只向自己提供";
            case SAVE -> "使用一次解药救本晚受袭击者；本夜不能同时用毒药";
            case POISON -> "使用一次毒药；本夜不能同时用解药";
            case PASS -> "放弃本次夜间行动，不消耗药物";
            case SPEAK -> "提交一段公开发言";
        };
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name(action)); function.put("description", description);
        function.put("parameters", Map.of("type", "object", "properties", properties,
                "required", properties.keySet().stream().filter(field -> strict || !field.equals("reasoning")).toList(), "additionalProperties", false));
        // 普通工具模式省略 strict，兼容只支持基础 function calling 的服务。
        if (strict) function.put("strict", true);
        return Map.of("type", "function", "function", function);
    }

    public static AgentResponse parse(ObjectMapper mapper, AgentContext context, List<LlmClient.ToolCall> calls) {
        return parse(mapper, context, calls, true);
    }

    /** 普通工具模式允许省略私有摘要；严格模式保持所有字段必填。 */
    public static AgentResponse parse(ObjectMapper mapper, AgentContext context, List<LlmClient.ToolCall> calls, boolean requireReasoning) {
        // 即使接口忽略 parallel_tool_calls=false，也拒绝整批多工具，不能执行第一条后再报错。
        if (calls.isEmpty()) throw new Problem(OutputIssue.MISSING_TOOL, "缺少工具调用；必须通过本次提供的行动工具提交动作");
        if (calls.size() != 1) throw new Problem(OutputIssue.MULTIPLE_TOOLS, "返回多个工具；每次只能提交一个行动工具");
        var call = calls.getFirst();
        ActionType action = context.availableActions().stream().filter(a -> name(a).equals(call.name())).findFirst()
                .orElseThrow(() -> new Problem(OutputIssue.UNKNOWN_TOOL, "工具名不属于本次合法行动；请复制本次提供的工具名"));
        if (!"function".equals(call.type())) throw new Problem(OutputIssue.TOOL_TYPE, "工具类型必须为 function");
        if (call.arguments() == null) throw new Problem(OutputIssue.MISSING_FIELD, "缺少工具 arguments");
        if (call.arguments().length() > 8000) throw new Problem(OutputIssue.ARGUMENTS_TOO_LARGE, "工具参数超过8000字符；请缩短摘要和发言");
        JsonNode arguments;
        try {
            // 工具参数必须是单个真实对象；拒绝重复字段、代码围栏、尾随对象和类型自动转换。
            ObjectMapper reader = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            arguments = reader.readTree(call.arguments());
        } catch (IOException e) { throw new Problem(OutputIssue.JSON_SYNTAX, "工具参数不是合法 JSON；禁止重复字段、代码围栏和尾随对象"); }
        Set<String> fields = action == ActionType.SPEAK ? Set.of("reasoning", "speech")
                : action == ActionType.PASS ? Set.of("reasoning") : Set.of("reasoning", "targetPlayerId");
        if (arguments == null || !arguments.isObject()) throw new Problem(OutputIssue.JSON_OBJECT, "工具参数必须为单个 JSON 对象");
        for (var names = arguments.fieldNames(); names.hasNext(); )
            if (!fields.contains(names.next())) throw new Problem(OutputIssue.UNEXPECTED_FIELD, "工具参数包含未定义字段；只使用本次定义的字段");
        for (String field : fields) {
            if (!arguments.has(field) && (!field.equals("reasoning") || requireReasoning))
                throw new Problem(OutputIssue.MISSING_FIELD, "工具参数缺少必填字段 " + field);
            if (arguments.has(field) && !arguments.path(field).isTextual())
                throw new Problem(OutputIssue.FIELD_TYPE, "工具字段 " + field + " 必须是文本");
        }
        String reason = arguments.has("reasoning") ? arguments.get("reasoning").asText() : null;
        String speech = action == ActionType.SPEAK ? arguments.get("speech").asText() : null;
        if (speech != null && speech.length() > 500)
            throw new Problem(OutputIssue.SPEECH_TOO_LONG, "公开发言超过500字符；请缩短 speech，保留明确立场");
        AgentResponse response = new AgentResponse(reason, speech, action,
                action == ActionType.SPEAK || action == ActionType.PASS ? null : arguments.get("targetPlayerId").asText());
        ActionValidator.validate(context, response);
        return response;
    }
}
