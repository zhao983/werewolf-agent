package com.example.werewolf.game;

/** 回放和 Console 共用的事件；进行中对局的私密事件由服务层过滤。 */
public record GameEvent(int day, GamePhase phase, String type, String actorId, String targetId, String text) { }
