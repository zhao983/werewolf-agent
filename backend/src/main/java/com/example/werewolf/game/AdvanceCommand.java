package com.example.werewolf.game;

/** 前端可提交的推进指令；每次请求最多执行一名 Agent 的行动。 */
public enum AdvanceCommand { NEXT_ACTION, COMPLETE_PHASE, END_DAY, NONE }
