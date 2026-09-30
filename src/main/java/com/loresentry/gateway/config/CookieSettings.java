package com.loresentry.gateway.config;

public record CookieSettings(String oauthName,boolean secure) {
    public String consentName(){return (secure ? "__Host-" : "")+"ls_consent";}
    public String sessionName(){return (secure ? "__Host-" : "")+"ls_session";}
}
