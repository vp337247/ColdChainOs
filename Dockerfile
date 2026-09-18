# =========================================================================
# Stage 1: Builder
# =========================================================================
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /workspace

# Cache Gradle dependencies
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies || true

# Copy source code and build production jar
COPY src src
COPY docs docs
RUN ./gradlew --no-daemon bootJar -x test

# =========================================================================
# Stage 2: Hardened Production Runtime
# =========================================================================
FROM eclipse-temurin:17-jre-jammy AS runtime

# Metadata
LABEL maintainer="ColdChainOS Engineering Team <eng@coldchainos.com>"
LABEL description="Production-grade, multi-tenant modular monolith for temperature-controlled cold chain logistics"

# Security: Create unprivileged system user and group
RUN groupadd --system --gid 10001 coldchain \
    && useradd --system --uid 10001 --gid coldchain --shell /bin/false --no-create-home coldchain

WORKDIR /app

# Copy executable jar from builder stage
COPY --from=builder --chown=coldchain:coldchain /workspace/build/libs/*.jar /app/coldchainos.jar

# Install curl for container health check
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Switch to non-root user
USER coldchain:coldchain

# Expose HTTP application & Actuator port
EXPOSE 8080

# Production-tuned JVM ergonomics for container cgroups
ENV JAVA_OPTS="-XX:+UseG1GC \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.profiles.active=prod"

# Container liveness health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/coldchainos.jar"]
