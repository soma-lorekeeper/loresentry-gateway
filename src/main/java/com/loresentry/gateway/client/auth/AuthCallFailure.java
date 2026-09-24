package com.loresentry.gateway.client.auth;

public class AuthCallFailure extends RuntimeException {
    public enum Kind { CONTRACT, UNAVAILABLE, INVALID_RESPONSE }
    private final Kind kind;
    private final int status;
    private final String code;
    private final String nextAction;
    private final Boolean consumed;
    private final boolean notSent;
    public AuthCallFailure(Kind kind,int status,String code,String nextAction,Boolean consumed,boolean notSent) {
        super(code);this.kind=kind;this.status=status;this.code=code;this.nextAction=nextAction;this.consumed=consumed;this.notSent=notSent;
    }
    public Kind kind(){return kind;} public int status(){return status;} public String code(){return code;}
    public String nextAction(){return nextAction;} public Boolean consumed(){return consumed;} public boolean notSent(){return notSent;}
    public static AuthCallFailure invalid(Boolean consumed) { return new AuthCallFailure(Kind.INVALID_RESPONSE,502,"UPSTREAM_INVALID_RESPONSE","NONE",consumed,false); }
    public static AuthCallFailure unavailable(boolean notSent) { return new AuthCallFailure(Kind.UNAVAILABLE,503,"AUTH_UNAVAILABLE","NONE",null,notSent); }
}
