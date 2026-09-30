package com.example.werewolf.game;

/** 仅供本地观战页面查看的私有记录；eventIndex 用于回放时隐藏未来信息。 */
public record ObserverNote(int day, GamePhase phase, int eventIndex,
                           String playerId, String kind, String text) { }
