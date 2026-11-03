package com.loresentry.gateway.client;

import java.util.Set;

public final class SupportedLocale {
    /** 내부 서비스에 넘기는 언어. 진입 로그도 이 목록으로 locale 값을 거른다(LogField.code). */
    public static final Set<String> VALUES = Set.of("ko", "en");
    private SupportedLocale() {}
    public static boolean valid(String value) {return value!=null&&VALUES.contains(value);}
}
