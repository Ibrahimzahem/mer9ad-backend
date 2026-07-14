# syntax=docker/dockerfile:1

############################
#  Stage 1 — build the jar
############################
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy the Maven wrapper + pom first so dependency resolution is cached as its own layer
# (only re-runs when pom.xml or the wrapper change, not on every source edit).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Now the sources; build the executable Spring Boot jar (tests run in CI, skipped here).
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

############################
#  Stage 2 — slim runtime
############################
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as an unprivileged user.
RUN groupadd --system shield && useradd --system --gid shield --home /app shield

# The fat jar produced above (artifactId-version.jar).
COPY --from=build /app/target/Ra9ed-HAmad-*.jar app.jar
USER shield

# The app listens on 8080 (see application.properties / Spring default).
EXPOSE 8080

# OPENROUTER_API_KEY / OPENROUTER_MODEL are read from the environment. If no key is set the
# app falls back to the deterministic StubShieldAi, so the container runs fully offline.
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
