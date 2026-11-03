package com.loresentry.gateway.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 레이어 클래스의 public 메서드인데도 진입 로그를 남기지 않는 메서드에 붙인다(LOG_SCHEMA.md 5절 "모든 public 메서드 진입 로그"의 예외).
 * LoggingArchitectureTest가 이 annotation이 붙은 메서드는 검사하지 않는다.
 *
 * <p>값을 돌려주기만 하는 getter처럼, 줄을 남겨도 흐름에 새 정보가 없고 한 요청 안에서 여러 번 불려 같은 줄만 쌓이는 메서드에만 쓴다.
 * 예: {@code UpstreamClient.name()}은 헬스체크 하나에서 대상 수만큼 여러 번 불린다.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface SkipEntryLog {
    /** 로그를 남기지 않는 이유. 코드를 읽는 사람이 예외인 까닭을 알 수 있게 적는다. */
    String value();
}
