package com.loresentry.gateway.application;

public class AuthOperationFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final String nextAction;
    public AuthOperationFailure(int status,String code,String nextAction) {super(code);this.status=status;this.code=code;this.nextAction=nextAction;}
    public int status(){return status;} public String code(){return code;} public String nextAction(){return nextAction;}
    public static AuthOperationFailure refreshRejected(){return new AuthOperationFailure(401,"REFRESH_REJECTED","RELOGIN");}
    public static AuthOperationFailure refreshUnknown(){return new AuthOperationFailure(503,"REFRESH_OUTCOME_UNKNOWN","RELOGIN");}
}
