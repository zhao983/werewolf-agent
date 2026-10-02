import type { ObserverNote } from "./types";
import type { ExperimentGame } from "./experiments";

/** 两个回放入口共用格式化，模型提供的决策摘要按普通文本显示。 */
export function describeNote(note: ObserverNote): string {
  if (note.kind === "CLUE") {
    const teammate = /^Wolf teammate: (.*)$/.exec(note.text);
    if (teammate) return `狼队友：${teammate[1] || "无"}`;
    // 最终狼刀回执属于狼人私有线索，观战页面仅负责中文展示。
    const wolfTarget =
      /^Wolf team night (\d+) final target: (player\d+|nobody); individual KILL is only a proposal$/.exec(
        note.text,
      );
    if (wolfTarget)
      return `第 ${wolfTarget[1]} 夜狼队最终袭击目标：${wolfTarget[2] === "nobody" ? "无人" : wolfTarget[2]}；个人选人只是袭击意向。`;
    const check = /^Night (\d+): (player\d+) is (WEREWOLF|GOOD)$/.exec(
      note.text,
    );
    if (check)
      return `第 ${check[1]} 夜查验 ${check[2]}：${check[3] === "WEREWOLF" ? "狼人" : "好人"}`;
    return note.text;
  }
  const decision = /^([A-Z]+)(?: → (player\d+))?(?:｜([\s\S]*))?$/.exec(
    note.text,
  );
  if (!decision) return note.text;
  const action: Record<string, string> = {
    SPEAK: "发言",
    VOTE: "投票",
    KILL: "袭击",
    CHECK: "查验",
    SAVE: "救人",
    POISON: "用毒",
    PASS: "跳过",
  };
  return `${action[decision[1]] ?? decision[1]}${decision[2] ? ` → ${decision[2]}` : ""}${decision[3] ? ` · ${decision[3]}` : " · 未提供决策说明"}`;
}

/** 旧文件只能还原确定的线索和合法行动，不推测已经丢失的决策说明。 */
export function restoreLegacyNotes(game: ExperimentGame): ObserverNote[] {
  const notes: ObserverNote[] = [];
  const firstNight = game.events.findIndex((e) => e.phase === "NIGHT_WEREWOLF");
  const wolves = game.seats.filter((s) => s.role === "WEREWOLF");
  if (firstNight >= 0)
    for (const wolf of wolves)
      notes.push({
        day: 1,
        phase: "GAME_START",
        eventIndex: firstNight,
        playerId: wolf.playerId,
        kind: "CLUE",
        text: `Wolf teammate: ${wolves
          .filter((s) => s !== wolf)
          .map((s) => s.playerId)
          .join(", ")}`,
      });
  const types: Record<string, string> = {
    NIGHT_WEREWOLF: "WOLF_VOTE",
    NIGHT_SEER: "SEER_CHECK",
    NIGHT_WITCH: "WITCH_ACTION",
    DAY_DISCUSSION: "PLAYER_SPEAK",
    DAY_VOTE: "VOTE",
  };
  let from = 0;
  for (const action of game.actions) {
    if (action.status !== "VALID" || !action.action) continue;
    const index = game.events.findIndex(
      (e, i) =>
        i >= from &&
        e.day === action.day &&
        e.phase === action.phase &&
        e.actorId === action.playerId &&
        e.type === types[action.phase],
    );
    if (index < 0) continue;
    from = index + 1;
    notes.push({
      day: action.day,
      phase: action.phase,
      eventIndex: index,
      playerId: action.playerId,
      kind: "DECISION",
      text: `${action.action}${action.targetPlayerId ? ` → ${action.targetPlayerId}` : ""}｜旧存档未保存原始决策说明`,
    });
    if (action.action === "CHECK") {
      const target = game.seats.find(
        (s) => s.playerId === action.targetPlayerId,
      );
      if (target)
        notes.push({
          day: action.day,
          phase: action.phase,
          eventIndex: index,
          playerId: action.playerId,
          kind: "CLUE",
          text: `Night ${action.day}: ${target.playerId} is ${target.role === "WEREWOLF" ? "WEREWOLF" : "GOOD"}`,
        });
    }
  }
  return notes;
}
