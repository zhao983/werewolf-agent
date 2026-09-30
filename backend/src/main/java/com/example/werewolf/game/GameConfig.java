package com.example.werewolf.game;

import com.example.werewolf.player.Role;
import java.util.ArrayList;
import java.util.List;

/** 对局身份配置。构造时一次性校验，避免非法配置进入状态机。 */
public record GameConfig(int playerCount, int werewolves, int villagers, int seers, int witches) {
    public GameConfig {
        if (playerCount < 3 || playerCount > 20) throw new IllegalArgumentException("玩家总数须在 3 到 20 人之间");
        if (werewolves < 1 || villagers < 0 || seers < 0 || witches < 0)
            throw new IllegalArgumentException("至少需要 1 名狼人，其他身份数量不能为负数");
        // 先限制单项数量，防止恶意大整数让总和溢出后绕过校验并分配海量身份牌。
        if (werewolves > playerCount || villagers > playerCount || seers > playerCount || witches > playerCount)
            throw new IllegalArgumentException("单种身份数量不能超过玩家总数");
        if (werewolves + villagers + seers + witches != playerCount)
            throw new IllegalArgumentException("各身份数量之和必须等于玩家总数");
        if (werewolves >= playerCount - werewolves)
            throw new IllegalArgumentException("开局好人数量必须多于狼人，否则游戏会立即结束");
    }

    public static GameConfig classicSeven() { return new GameConfig(7, 2, 3, 1, 1); }

    /** 按固定配比生成角色牌，洗牌由 GameEngine 负责。 */
    public List<Role> roles() {
        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < werewolves; i++) roles.add(Role.WEREWOLF);
        for (int i = 0; i < villagers; i++) roles.add(Role.VILLAGER);
        for (int i = 0; i < seers; i++) roles.add(Role.SEER);
        for (int i = 0; i < witches; i++) roles.add(Role.WITCH);
        return roles;
    }
}
