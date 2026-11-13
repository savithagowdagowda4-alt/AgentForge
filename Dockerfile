# ---- Build stage: compile the Spring Boot jar with Maven -------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Runtime stage: slim Java 21 runtime + Tesseract OCR --------------------------
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 agentforge \
 && mkdir -p /app/data \
 && chown -R agentforge /app
WORKDIR /app
COPY --from=build /src/target/agentforge-2.0.0.jar /app/app.jar
USER agentforge

# Data (H2 database + uploads) lives here; mount a persistent disk at /app/data to keep it across deploys.
ENV AGENTFORGE_DATA_DIR=/app/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
