package com.example.werewolf.game;

/** 与人数无关的固定玩法开关。 */
public final class GameRule {
    // 与 Milestone 1 保持一致的固定规则；人数和角色配比改由 GameConfig 管理。
    public static final boolean ALLOW_SELF_VOTE = false;
    public static final boolean ALLOW_WITCH_SELF_SAVE = true;
    public static final int WITCH_ACTIONS_PER_NIGHT = 1;

    private GameRule() { }
}
