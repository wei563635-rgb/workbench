package com.yiqi.aitoolbench.security;

public enum DataScope {
    SELF(1),
    PROJECT(2),
    DEPARTMENT(3),
    ALL(4);

    private final int priority;

    DataScope(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    public static DataScope from(String value) {
        if (value == null || value.isBlank()) {
            return SELF;
        }
        return switch (value) {
            case "ALL" -> ALL;
            case "DEPARTMENT" -> DEPARTMENT;
            case "PROJECT", "SELF_PROJECT" -> PROJECT;
            case "SELF" -> SELF;
            default -> SELF;
        };
    }
}
