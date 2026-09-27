# --- build stage ------------------------------------------------------------
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /home/gradle/src

COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

# The Gradle cache lives in a BuildKit cache mount rather than an image layer:
# Gradle 8 renames directories inside its transforms cache, which fails on
# overlayfs when those directories were created in an earlier layer
# ("Could not move inconsistent immutable workspace"). The mount still keeps
# downloaded dependencies between builds.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar --no-daemon -x test

# --- runtime stage ----------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# The vision pipeline decodes JPEGs through ImageIO, which needs fontconfig and
# the headless AWT libraries that the JRE-slim base image leaves out.
RUN apk add --no-cache fontconfig freetype ttf-dejavu curl \
    && addgroup -S app && adduser -S -G app app

COPY --from=build /home/gradle/src/build/libs/*.jar app.jar
RUN chown app:app /app/app.jar
USER app

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
