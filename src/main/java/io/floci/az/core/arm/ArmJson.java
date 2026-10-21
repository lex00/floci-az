package io.floci.az.core.arm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON request-body helpers shared by ARM-style handlers. */
public final class ArmJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArmJson() {
    }

    /** Signals an unparseable request body; map to an ARM {@code 400 InvalidRequestContent}. */
    public static final class InvalidBodyException extends RuntimeException {
        public InvalidBodyException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Parses the body, degrading to an immutable empty map when the body is missing or
     * malformed — the historical tolerant behavior of the ARM-plane handlers.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseBodyLenient(AzureRequest req) {
        InputStream body = req.bodyStream();
        try {
            if (body == null || body.available() == 0) {
                return Map.of();
            }
            return MAPPER.readValue(body, Map.class);
        } catch (IOException e) {
            return Map.of();
        }
    }

    /**
     * Parses the body, degrading to a fresh mutable map when the body is missing or
     * malformed — for handlers that mutate the parsed map in place.
     */
    public static Map<String, Object> parseBodyMutable(AzureRequest req) {
        InputStream body = req.bodyStream();
        if (body == null) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(body, new TypeReference<>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Parses the body strictly: an absent body yields an empty map, a malformed one
     * throws {@link InvalidBodyException} (real ARM answers {@code 400 InvalidRequestContent}).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseBodyStrict(AzureRequest req) {
        InputStream body = req.bodyStream();
        byte[] bytes;
        try {
            bytes = body == null ? new byte[0] : body.readAllBytes();
        } catch (IOException e) {
            throw new InvalidBodyException(e);
        }
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(bytes, Map.class);
        } catch (IOException e) {
            throw new InvalidBodyException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> cast(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** A response's entity as JSON values: handlers answer with maps, or with JSON text. */
    public static Object responseBody(Response response) {
        Object entity = response.getEntity();
        if (entity instanceof String text && !text.isBlank()) {
            try {
                return MAPPER.readValue(text, Object.class);
            } catch (JsonProcessingException e) {
                return Map.of("error", Map.of("code", String.valueOf(response.getStatus()), "message", text));
            }
        }
        if (entity instanceof byte[] bytes && bytes.length > 0) {
            try {
                return MAPPER.readValue(bytes, Object.class);
            } catch (IOException e) {
                return Map.of();
            }
        }
        if (entity instanceof Map<?, ?> || entity instanceof List<?>) {
            return MAPPER.convertValue(entity, Object.class);
        }
        return entity == null ? Map.of() : MAPPER.convertValue(entity, Object.class);
    }

    public static String string(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v instanceof String s ? s : defaultValue;
    }
}
