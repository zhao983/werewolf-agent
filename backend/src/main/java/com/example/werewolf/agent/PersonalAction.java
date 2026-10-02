package com.example.werewolf.agent;

import com.example.werewolf.game.GamePhase;

/** 本人已成功执行的行动；不携带其他玩家的私密行动、推理或观战指标。 */
public record PersonalAction(int day, GamePhase phase, ActionType action, String targetPlayerId) { }
