package com.loresentry.gateway;
import java.lang.annotation.*;
import org.springframework.test.context.ActiveProfiles;
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ActiveProfiles({"local","testfixture"})
public @interface LocalTestEnvironment {}
