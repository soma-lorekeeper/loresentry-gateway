package com.loresentry.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/**
 * 로그는 AppLogger로만 찍는다(LOG_SCHEMA.md 5절). logging 패키지 밖의 코드는 로깅 라이브러리와 표준 출력을 직접 쓰지 못한다.
 * 테스트 코드는 검사하지 않는다.
 */
@AnalyzeClasses(packages = "com.loresentry.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class LoggingArchitectureTest {

    @ArchTest
    static final ArchRule onlyTheLoggingPackageUsesLoggingLibraries = noClasses()
            .that().resideOutsideOfPackage("..logging..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.slf4j..", "ch.qos.logback..", "java.util.logging..", "org.apache.commons.logging..", "org.apache.logging.log4j..")
            .because("로그는 com.loresentry.gateway.logging.AppLogger로만 찍는다");

    @ArchTest
    static final ArchRule nothingWritesToStandardStreams = noClasses()
            .that().resideOutsideOfPackage("..logging..")
            .should(GeneralCodingRules.ACCESS_STANDARD_STREAMS)
            .because("System.out·System.err 줄은 ECS JSON이 아니어서 Logstash가 필드로 읽지 못한다");
}
