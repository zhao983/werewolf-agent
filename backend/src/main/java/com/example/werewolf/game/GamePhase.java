package com.example.werewolf.game;

/** 状态机阶段，手动推进只能按此顺序改变阶段。 */
public enum GamePhase {
    GAME_START, NIGHT_WEREWOLF, NIGHT_SEER, NIGHT_WITCH, NIGHT_RESOLVE,
    DAY_ANNOUNCEMENT, DAY_DISCUSSION, DAY_VOTE, DAY_RESOLVE, GAME_OVER
}
