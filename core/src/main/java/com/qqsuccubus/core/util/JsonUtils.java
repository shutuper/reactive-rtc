package com.qqsuccubus.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jittered exponential backoff calculator for graceful reconnect.
 * <p>
 * <b>Formula:</b> {@code t = min(max, base * phi^attempt) + uniform(0, jitterMax)}
 * <ul>
 *   <li>{@code base}: Initial delay</li>
 *   <li>{@code phi}: Growth factor (typically 2.0 for exponential)</li>
 *   <li>{@code max}: Maximum delay (cap)</li>
 *   <li>{@code jitterMax}: Maximum jitter to add</li>
 * </ul>
 * </p>
 * <p>
 * <b>Jitter rationale:</b> Prevents thundering herd when many clients reconnect simultaneously.
 * </p>
 */
public final class JsonUtils {
    private JsonUtils() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String writeValueAsString(Object object) {
        try {
            return mapper().writeValueAsString(object);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T readValue(String json, Class<T> clazz) {
        try {
            return mapper().readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T readValue(String json, TypeReference<T> clazz) {
        try {
            return mapper().readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}






