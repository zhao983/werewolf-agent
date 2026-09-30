package com.example.werewolf.player;

import com.example.werewolf.agent.Agent;
import java.util.Objects;

/** 游戏内玩家实体；Agent 只是该玩家的决策实现。 */
public final class Player {
    private final String id;
    private final String name;
    private final Role role;
    private final Agent agent;
    private PlayerStatus status = PlayerStatus.ALIVE;

    public Player(String id, String name, Role role, Agent agent) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.role = Objects.requireNonNull(role);
        this.agent = Objects.requireNonNull(agent);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Role getRole() { return role; }
    public Agent getAgent() { return agent; }
    public PlayerStatus getStatus() { return status; }
    public boolean isAlive() { return status == PlayerStatus.ALIVE; }
    public void die() { status = PlayerStatus.DEAD; }
}
