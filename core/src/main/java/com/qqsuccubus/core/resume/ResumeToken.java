package com.qqsuccubus.core.resume;

import com.qqsuccubus.core.hash.Hashers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Resume token generation and verification for graceful reconnect.
 * <p>
 * <b>Token format:</b> {@code clientId:offset:ts:hmac}
 * <ul>
 *   <li>{@code clientId}: User identifier</li>
 *   <li>{@code offset}: Last acknowledged message offset</li>
 *   <li>{@code ts}: Token issue timestamp (epoch seconds)</li>
 *   <li>{@code hmac}: HMAC-SHA256 signature over "clientId:offset:ts"</li>
 * </ul>
 * </p>
 * <p>
 * <b>Resume semantics:</b> Client reconnects with this token; server verifies signature,
 * checks TTL, and replays messages from {@code offset+1}.
 * </p>
 */
public final class ResumeToken {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DELIMITER = ":";

    private ResumeToken() {
    }

    /**
     * Generates a resume token.
     *
     * @param clientId User identifier
     * @param offset Last acknowledged message offset
     * @param ts     Token issue timestamp
     * @param secret HMAC secret key (must be same across cluster)
     * @return Base64-encoded resume token
     */
    public static String generate(String clientId, long offset, Instant ts, String secret) {
        long epochSec = ts.getEpochSecond();
        String payload = clientId + DELIMITER + offset + DELIMITER + epochSec;
        String hmac = computeHmac(payload, secret);
        String token = payload + DELIMITER + hmac;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verifies a resume token and extracts the offset.
     *
     * @param token  Base64-encoded resume token
     * @param secret HMAC secret key
     * @return Parsed offset, or -1 if verification fails
     */
    public static long verify(String token, String secret) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(DELIMITER);
            if (parts.length != 4) {
                return -1;
            }

            String clientId = parts[0];
            long offset = Long.parseLong(parts[1]);
            long epochSec = Long.parseLong(parts[2]);
            String providedHmac = parts[3];

            String payload = clientId + DELIMITER + offset + DELIMITER + epochSec;
            String expectedHmac = computeHmac(payload, secret);

            if (!expectedHmac.equals(providedHmac)) {
                return -1; // HMAC mismatch
            }

            // Optional: Check TTL (e.g., reject if > 1 hour old)
            long nowSec = Instant.now().getEpochSecond();
            if (nowSec - epochSec > 3600) {
                return -1; // Token expired
            }

            return offset;
        } catch (Exception e) {
            return -1; // Parse error
        }
    }

    /**
     * Parses clientId from a token without full verification (for logging/routing).
     *
     * @param token Base64-encoded resume token
     * @return User ID, or null if parse fails
     */
    public static String extractClientId(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(DELIMITER);
            return parts.length >= 1 ? parts[0] : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String computeHmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Hashers.toHex(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}











