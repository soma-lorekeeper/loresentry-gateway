package com.loresentry.gateway.application;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class ChatAllowlist {
    private final boolean everyone;
    private final Set<UUID> users;

    private ChatAllowlist(boolean everyone, Set<UUID> users) {
        this.everyone = everyone;
        this.users = Set.copyOf(users);
    }

    public static ChatAllowlist parse(String value) {
        boolean everyone = false;
        var users = new HashSet<UUID>();
        if (value != null) for (var entry : value.split(",")) {
            var item = entry.trim();
            if (item.isEmpty()) continue;
            if ("*".equals(item)) everyone = true;
            else users.add(user(item));
        }
        return new ChatAllowlist(everyone, users);
    }

    private static UUID user(String value) {
        try {
            var id = UUID.fromString(value);
            if (id.toString().equals(value.toLowerCase(Locale.ROOT))) return id;
        } catch (IllegalArgumentException ignored) {
        }
        throw new IllegalStateException("Invalid entry in loresentry.chat.allowed-users");
    }

    public boolean allows(UUID user) {
        return user != null && (everyone || users.contains(user));
    }
}
