package io.floci.az.core.arm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ARM tag names are case-insensitive: a resource holds one tag per name, and a write that spells a name another
 * way replaces it. {@link #foldCase} applies that to a write body, keeping the spelling and value written last.
 */
public final class ArmTags {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArmTags() {
    }

    /**
     * The write body with tag names that differ only by case folded into one, or {@code body} itself when its
     * {@code tags} (top-level, or under {@code properties} for the Tags API) hold no such pair or it is not JSON.
     */
    public static byte[] foldCase(byte[] body) {
        if (body.length == 0) {
            return body;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            return body;
        }
        if (!(root instanceof ObjectNode object)) {
            return body;
        }
        boolean folded = fold(object.get("tags"));
        if (object.get("properties") instanceof ObjectNode properties) {
            folded |= fold(properties.get("tags"));
        }
        if (!folded) {
            return body;
        }
        try {
            return MAPPER.writeValueAsBytes(object);
        } catch (JsonProcessingException e) {
            return body;
        }
    }

    /** Folds {@code tags} in place when it is an object holding two names that differ only by case. */
    private static boolean fold(JsonNode tags) {
        if (!(tags instanceof ObjectNode object)) {
            return false;
        }
        Map<String, Map.Entry<String, JsonNode>> byName = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            String key = name.toLowerCase(Locale.ROOT);
            byName.remove(key);
            byName.put(key, Map.entry(name, object.get(name)));
        }
        if (byName.size() == names.size()) {
            return false;
        }
        object.removeAll();
        byName.values().forEach(entry -> object.set(entry.getKey(), entry.getValue()));
        return true;
    }
}
