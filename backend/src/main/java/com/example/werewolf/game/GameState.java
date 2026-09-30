package com.example.werewolf.game;

import com.example.werewolf.message.GameMessage;
import com.example.werewolf.player.Player;
import com.example.werewolf.player.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 引擎内部的完整可变状态，不直接返回给 Agent 或前端。 */
public final class GameState {
    private final String gameId;
    private final GameConfig config;
    private final Consumer<String> logger;
    private final List<Player> players;
    private final List<GameEvent> events = new ArrayList<>();
    private final List<GameMessage> publicMessages = new ArrayList<>();
    private final Map<String, List<String>> privateInformation = new HashMap<>();
    private final List<ObserverNote> observerNotes = new ArrayList<>();
    private int dayNumber = 1;
    private GamePhase phase = GamePhase.GAME_START;
    private GameResult result = GameResult.ONGOING;
    private List<String> lastNightDeaths = List.of();
    // 多女巫局中药剂属于具体玩家，而不是整局共用。
    private final Map<String, Boolean> antidotes = new HashMap<>();
    private final Map<String, Boolean> poisons = new HashMap<>();

    public GameState(String gameId, GameConfig config, List<Player> players, Consumer<String> logger) {
        if (players.size() != config.playerCount()) throw new IllegalArgumentException("玩家数量与配置不一致");
        this.gameId = gameId;
        this.config = config;
        this.players = List.copyOf(players);
        this.logger = logger;
        for (Player player : players) {
            if (player.getRole() == Role.WITCH) {
                antidotes.put(player.getId(), true);
                poisons.put(player.getId(), true);
            }
        }
    }
    /** 供测试使用：从传入的玩家身份推导配置。 */
    public GameState(String gameId, List<Player> players, Consumer<String> logger) {
        this(gameId, new GameConfig(players.size(),
                (int) players.stream().filter(p -> p.getRole() == Role.WEREWOLF).count(),
                (int) players.stream().filter(p -> p.getRole() == Role.VILLAGER).count(),
                (int) players.stream().filter(p -> p.getRole() == Role.SEER).count(),
                (int) players.stream().filter(p -> p.getRole() == Role.WITCH).count()), players, logger);
    }
    public String getGameId() { return gameId; }
    public GameConfig getConfig() { return config; }
    public List<Player> getPlayers() { return players; }
    public List<GameEvent> getEvents() { return List.copyOf(events); }
    public List<GameMessage> getPublicMessages() { return List.copyOf(publicMessages); }
    public List<String> getPrivateInformation(String id) { return List.copyOf(privateInformation.getOrDefault(id, List.of())); }
    public List<ObserverNote> getObserverNotes() { return List.copyOf(observerNotes); }
    public int getDayNumber() { return dayNumber; }
    public GamePhase getPhase() { return phase; }
    public GameResult getResult() { return result; }
    public List<String> getLastNightDeaths() { return lastNightDeaths; }
    public boolean isAntidoteAvailable(String witchId) { return antidotes.getOrDefault(witchId, false); }
    public boolean isPoisonAvailable(String witchId) { return poisons.getOrDefault(witchId, false); }

    void phase(GamePhase value) { phase = value; }
    void result(GameResult value) { result = value; }
    void nextDay() { dayNumber++; }
    void lastNightDeaths(List<String> value) { lastNightDeaths = List.copyOf(value); }
    void event(String type, String actor, String target, String text) {
        events.add(new GameEvent(dayNumber, phase, type, actor, target, text));
        logger.accept("[Day " + dayNumber + " " + phase + "] " + text);
    }
    void publicMessage(GameMessage message) { publicMessages.add(message); }
    void privateInfo(String id, String info) {
        privateInformation.computeIfAbsent(id, k -> new ArrayList<>()).add(info);
        // 观战记录单独保存，其他玩家的 AgentContext 仍只读取自己的 privateInformation。
        observerNotes.add(new ObserverNote(dayNumber, phase, events.size(), id, "CLUE", info));
    }
    void observerNote(String id, String kind, String text) {
        observerNotes.add(new ObserverNote(dayNumber, phase, events.size(), id, kind, text));
    }
    void useAntidote(String witchId) {
        if (!isAntidoteAvailable(witchId)) throw new IllegalStateException("解药已使用或该玩家不是女巫");
        antidotes.put(witchId, false);
    }
    void usePoison(String witchId) {
        if (!isPoisonAvailable(witchId)) throw new IllegalStateException("毒药已使用或该玩家不是女巫");
        poisons.put(witchId, false);
    }
}
