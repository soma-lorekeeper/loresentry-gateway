package com.loresentry.gateway.config;

public record CookieSettings(String accessName,String refreshName,String oauthName,boolean secure) {
    public String sessionName() { return (secure ? "__Host-" : "") + "ls_session"; }
}
