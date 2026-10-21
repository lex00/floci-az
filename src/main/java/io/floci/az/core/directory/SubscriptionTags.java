package io.floci.az.core.directory;

import io.floci.az.core.Resettable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tags on each subscription, written through the Tags API at subscription scope. Memory-only: they go with
 * the emulator, as the first-use subscriptions they may sit on do.
 */
@ApplicationScoped
public class SubscriptionTags implements Resettable {

    private final Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();

    /** The subscription's tags, empty when it has none. */
    public Map<String, String> get(String subscriptionId) {
        return Map.copyOf(tags.getOrDefault(subscriptionId.toLowerCase(Locale.ROOT), Map.of()));
    }

    /** Replaces the subscription's tags; an empty map removes them. */
    public void put(String subscriptionId, Map<String, String> value) {
        String key = subscriptionId.toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            tags.remove(key);
        } else {
            tags.put(key, new LinkedHashMap<>(value));
        }
    }

    @Override
    public void clear() {
        tags.clear();
    }
}
