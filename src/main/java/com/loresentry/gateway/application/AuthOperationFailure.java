package com.loresentry.gateway.application;

public class AuthOperationFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final String nextAction;
    /** 로그용(결과 줄의 error.code): auth가 오류 본문으로 돌려준 code. gateway가 정한 실패면 null이다. 응답 본문에는 쓰지 않는다 */
    private final String upstreamCode;
    public AuthOperationFailure(int status,String code,String nextAction) {this(status,code,nextAction,null);}
    public AuthOperationFailure(int status,String code,String nextAction,String upstreamCode) {
        super(code);this.status=status;this.code=code;this.nextAction=nextAction;this.upstreamCode=upstreamCode;
    }
    public int status(){return status;} public String code(){return code;} public String nextAction(){return nextAction;}
    public String upstreamCode(){return upstreamCode;}
}
