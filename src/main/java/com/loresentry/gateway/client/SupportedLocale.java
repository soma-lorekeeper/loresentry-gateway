package com.loresentry.gateway.client;

public final class SupportedLocale {
    private SupportedLocale() {}
    public static boolean valid(String value) {return "ko".equals(value)||"en".equals(value);}
}
