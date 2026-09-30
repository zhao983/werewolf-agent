package com.example.werewolf.experiment;

/** 用户整理实验的标签，不参与 Agent 决策或游戏规则。 */
public record ExperimentMetadata(String name, String group, String notes) {
    /** 只校验标签内容，批次进度和对局推进不影响正在编辑的标签版本。 */
    public static String revision(String name, String group, String notes) {
        try {
            byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(java.util.List.of(name, group, notes));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法生成实验标签版本", e);
        }
    }
    public static ExperimentMetadata normalize(String name, String group, String notes, String fallback) {
        String title = name == null || name.isBlank() ? fallback : name.strip();
        String label = group == null ? "" : group.strip();
        String memo = notes == null ? "" : notes.strip();
        if (title == null || title.isBlank() || title.length() > 80)
            throw new IllegalArgumentException("实验名称须为 1 至 80 字");
        if (label.length() > 60) throw new IllegalArgumentException("实验分组不能超过 60 字");
        if (memo.length() > 2000) throw new IllegalArgumentException("实验备注不能超过 2000 字");
        return new ExperimentMetadata(title, label, memo);
    }
}
