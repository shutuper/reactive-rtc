package com.qqsuccubus.core.hash;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable hash functions for consistent hashing and key distribution.
 * <p>
 * <b>Important:</b> Hash function choice affects distribution quality.
 * Murmur3 is fast and well-distributed; SHA-256 is cryptographically secure
 * but slower (use for signing/verification, not hot path).
 * </p>
 */
public final class Hashers {
    private Hashers() {
    }

    /**
     * Computes Murmur3 128-bit hash and returns the lower 64 bits as a long.
     * <p>
     * Murmur3 is non-cryptographic but has excellent distribution properties
     * and is very fast. Suitable for consistent hashing.
     * </p>
     *
     * @param data Input bytes
     * @return 64-bit hash value (signed long)
     */
    public static long murmur3Hash(byte[] data) {
        HashCode hash = Hashing.murmur3_128().hashBytes(data);
        return hash.asLong(); // Returns lower 64 bits
    }

    /**
     * Computes Murmur3 hash of a UTF-8 string.
     *
     * @param str Input string
     * @return 64-bit hash value
     */
    public static long murmur3Hash(String str) {
        return murmur3Hash(str.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes SHA-256 hash and returns the full 256 bits.
     * <p>
     * Use this for HMAC, token generation, or when cryptographic
     * security is required. DO NOT use on hot path (message routing).
     * </p>
     *
     * @param data Input bytes
     * @return 32-byte SHA-256 hash
     */
    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Computes SHA-256 hash of a UTF-8 string.
     *
     * @param str Input string
     * @return 32-byte SHA-256 hash
     */
    public static byte[] sha256(String str) {
        return sha256(str.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Converts a byte array to a hex string (lowercase).
     *
     * @param bytes Input bytes
     * @return Hex string (e.g., "a3f2...")
     */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}











