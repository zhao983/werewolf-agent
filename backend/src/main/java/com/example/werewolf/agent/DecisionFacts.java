package com.example.werewolf.agent;

import com.example.werewolf.game.GameEvent;
import java.util.*;

/** 从已允许读取的公开事件推导结构化事实，不使用真实角色或其他玩家私有行动。 */
public record DecisionFacts(List<NightReport> announcedNights, NightReport lastAnnouncedNight,
                            List<String> alreadySpokenPlayerIds, List<String> remainingSpeakerIds,
                            List<String> alreadyVotedPlayerIds, List<String> remainingVoterIds,
                            int maximumLivingWolvesWhileOngoing, List<String> knownWolfTeammateIds,
                            List<WolfTarget> wolfTeamTargets) {
    public record NightReport(int night, List<String> deaths, boolean peaceful) { }
    public record WolfTarget(int night, String finalTargetPlayerId) { }
    public static DecisionFacts from(AgentContext context) {
        List<NightReport> nights = context.publicFacts().stream()
                .filter(e -> e.type().equals("DAY_ANNOUNCEMENT")).map(GameEvent::day).distinct().sorted()
                .map(day -> {
                    List<String> deaths = context.publicFacts().stream()
                            .filter(e -> e.day() == day && e.type().equals("PLAYER_DIED") && e.targetId() != null)
                            .map(GameEvent::targetId).distinct().toList();
                    return new NightReport(day, deaths, deaths.isEmpty());
                }).toList();
        List<String> spoken = context.visibleMessages().stream().filter(m -> m.day() == context.dayNumber())
                .map(m -> m.senderId()).distinct().toList();
        List<String> voted = context.publicFacts().stream().filter(e -> e.day() == context.dayNumber() && e.type().equals("VOTE"))
                .map(GameEvent::actorId).filter(Objects::nonNull).distinct().toList();
        List<String> teammates = new ArrayList<>(); List<WolfTarget> targets = new ArrayList<>();
        if (context.role() == com.example.werewolf.player.Role.WEREWOLF) for (String clue : context.privateInformation()) {
            var teammate = java.util.regex.Pattern.compile("Wolf teammate: (player[0-9]+)").matcher(clue);
            if (teammate.matches()) teammates.add(teammate.group(1));
            var target = java.util.regex.Pattern.compile("Wolf team night ([0-9]+) final target: (player[0-9]+|nobody); individual KILL is only a proposal").matcher(clue);
            if (target.matches()) targets.add(new WolfTarget(Integer.parseInt(target.group(1)), target.group(2).equals("nobody") ? null : target.group(2)));
        }
        return new DecisionFacts(nights, nights.isEmpty() ? null : nights.getLast(), spoken,
                context.alivePlayerIds().stream().filter(id -> !spoken.contains(id)).toList(), voted,
                context.alivePlayerIds().stream().filter(id -> !voted.contains(id)).toList(),
                Math.min(context.gameConfig().werewolves(), Math.max(0, (context.alivePlayerIds().size() - 1) / 2)),
                List.copyOf(teammates), List.copyOf(targets));
    }
}
