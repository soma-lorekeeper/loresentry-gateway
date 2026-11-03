package com.loresentry.gateway.config;

import java.util.List;

import com.loresentry.gateway.logging.AppLogger;
import com.loresentry.gateway.logging.LogField;
import com.loresentry.gateway.security.CurrentUserArgumentResolver;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUser;

    public WebConfig(CurrentUserArgumentResolver currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
    }

    /**
     * 우리 예외 처리기(@ExceptionHandler)가 다루지 않은 프레임워크 예외(405, 415, 406, 없는 경로, 비동기 시간 초과, 경로·파라미터 변환 실패 등)의
     * 클래스 이름을 결과 줄(request completed)의 error.type으로 맡긴다(LOG_SCHEMA.md 5절 "실패의 결과는 이 한 줄이다").
     *
     * <p>기본 목록은 ExceptionHandlerExceptionResolver → ResponseStatusExceptionResolver → DefaultHandlerExceptionResolver 순서다.
     * 첫 resolver(우리 @ExceptionHandler) 바로 뒤에 넣는다. 그래서 우리 처리기가 다룬 예외는 여기 오지 않는다.
     * 늘 null을 돌려주므로 응답은 뒤의 Spring 기본 resolver가 정한 그대로다. 예외 메시지는 남기지 않는다.
     */
    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(Math.min(1, resolvers.size()), (request, response, handler, exception) -> {
            AppLogger.current().addToOutcome(LogField.errorType(exception.getClass().getName()));
            return null;
        });
    }

}
