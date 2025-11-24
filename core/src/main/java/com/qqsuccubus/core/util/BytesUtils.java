package com.qqsuccubus.core.util;

public final class BytesUtils {
    private BytesUtils() {
    }

    public static long getBytesLength(String str) {
        return str == null ? 0 : str.length() * 2L + 38;
    }

}






