package com.jumunhasyeo.ratelimiter.queue.enums;

public enum QueueResult {

    ALREADY_ACTIVE,
    ACTIVE,
    QUEUED,
    ADMITTED,
    NOT_IN_QUEUE;

    private static final String SEPARATOR = ":";

    public String withPayload(String payload) {
        return name() + SEPARATOR + payload;
    }

    public static QueueResult from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Lua 스크립트 반환값이 null입니다.");
        }
        int idx = raw.indexOf(SEPARATOR);
        String prefix = idx >= 0 ? raw.substring(0, idx) : raw;
        try {
            return valueOf(prefix);
        } catch (IllegalArgumentException e) {
            return null; // rank 숫자인 경우
        }
    }

    public static String extractPayload(String raw) {
        int idx = raw.indexOf(SEPARATOR);
        return idx >= 0 ? raw.substring(idx + 1) : null;
    }

    public static QueuedPayload extractQueuedPayload(String raw) {
        String payload = extractPayload(raw);
        if (payload == null) {
            throw new IllegalArgumentException("QUEUED payload가 없습니다.");
        }

        int rank;
        try {
            rank = Integer.parseInt(payload);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("QUEUED rank 형식이 올바르지 않습니다.", e);
        }

        if (rank < 0) {
            throw new IllegalArgumentException("QUEUED rank는 0 이상이어야 합니다.");
        }

        return new QueuedPayload(rank);
    }

    public static boolean isRank(String raw) {
        return raw != null && !raw.isBlank() && raw.chars().allMatch(Character::isDigit);
    }

    public boolean matches(String raw) {
        return this == from(raw);
    }

    public record QueuedPayload(int rank) {
    }
}
