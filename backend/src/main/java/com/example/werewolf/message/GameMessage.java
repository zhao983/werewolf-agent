package com.example.werewolf.message;

/** 公开发言消息；夜间私有信息单独保存在 GameState。 */
public record GameMessage(int day, String senderId, String content) { }
