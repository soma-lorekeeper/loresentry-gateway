FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies || true

COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre-alpine

# 이미지 태그(build-<run>-<attempt>). 로그와 span의 service.version이 된다
ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}

WORKDIR /app

RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app

COPY --from=build /workspace/build/libs/*.jar app.jar

USER app

EXPOSE 8000

# 컨테이너 메모리 한도의 75%를 heap 상한으로 쓴다
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
