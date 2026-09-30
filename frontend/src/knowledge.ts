import axios from "axios";
import type { Role } from "./types";

/** 静态策略与观战使用记录分开；页面数据不会回传成 AgentContext。 */
export type KnowledgeScope = "COMMON" | Role;
export type KnowledgeMode = "NONE" | "COMMON" | "ROLE";
export interface KnowledgeEntry {
  id: string;
  title: string;
  scope: KnowledgeScope;
  phases: string[];
  content: string;
  priority: number;
  enabled: boolean;
}
export interface KnowledgeSnapshot {
  schemaVersion: number;
  revision: string;
  entries: KnowledgeEntry[];
}
export interface KnowledgeUsage {
  actionSequence: number;
  day: number;
  phase: string;
  eventIndex: number;
  playerId: string;
  entryIds: string[];
}
export interface KnowledgeRun {
  mode: KnowledgeMode;
  snapshot: KnowledgeSnapshot | null;
  usages: KnowledgeUsage[];
}
export const scopeName: Record<KnowledgeScope, string> = {
  COMMON: "通用",
  WEREWOLF: "狼人",
  VILLAGER: "村民",
  SEER: "预言家",
  WITCH: "女巫",
};
export const knowledgeModeName: Record<KnowledgeMode, string> = {
  NONE: "关闭策略知识",
  COMMON: "仅通用知识",
  ROLE: "通用 + 对应角色知识",
};
const headers = { "X-Werewolf-Knowledge": "1" };
export const knowledgeApi = {
  get: async () =>
    (
      await axios.get<{ snapshot: KnowledgeSnapshot; directory: string }>(
        "/api/knowledge",
      )
    ).data,
  add: async (entry: KnowledgeEntry) =>
    (await axios.post<KnowledgeSnapshot>("/api/knowledge", entry, { headers }))
      .data,
  update: async (entry: KnowledgeEntry, revision: string) =>
    (
      await axios.put<KnowledgeSnapshot>(`/api/knowledge/${entry.id}`, entry, {
        headers,
        params: { revision },
      })
    ).data,
  remove: async (id: string, revision: string) =>
    (
      await axios.delete<KnowledgeSnapshot>(`/api/knowledge/${id}`, {
        headers,
        params: { revision },
      })
    ).data,
  importFile: async (file: File) => {
    const body = new FormData();
    body.append("file", file);
    return (
      await axios.post<KnowledgeSnapshot>("/api/knowledge/import", body, {
        headers,
      })
    ).data;
  },
  exportFile: async () =>
    (await axios.get<Blob>("/api/knowledge/export", { responseType: "blob" }))
      .data,
};
export function knowledgeError(error: unknown) {
  return axios.isAxiosError(error)
    ? error.response?.data?.error || "知识库操作失败，请检查后端状态。"
    : "知识库操作失败。";
}
