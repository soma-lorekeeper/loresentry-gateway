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
    /** 로그용(error.code): auth가 오류 본문으로 돌려준 code. 알려진 code만 CONTRACT가 되므로 임의 문자열은 없다. gateway가 붙인 code면 null */
    public String upstreamCode(){return kind==Kind.CONTRACT?code:null;}
    /** 로그용(error.type): gateway가 본 실패 분류. auth가 오류 본문을 돌려줬으면 AUTH_ERROR_RESPONSE, 그 밖은 gateway가 붙인 code(AUTH_UNAVAILABLE 등) */
    public String reason(){return kind==Kind.CONTRACT?"AUTH_ERROR_RESPONSE":code;}
    public static AuthCallFailure invalid(Boolean consumed) { return new AuthCallFailure(Kind.INVALID_RESPONSE,502,"UPSTREAM_INVALID_RESPONSE","NONE",consumed,false); }
    public static AuthCallFailure unavailable(boolean notSent) { return new AuthCallFailure(Kind.UNAVAILABLE,503,"AUTH_UNAVAILABLE","NONE",null,notSent); }
}
