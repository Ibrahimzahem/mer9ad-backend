# syntax=docker/dockerfile:1

############################
#  Stage 1 — build the jar
############################
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy the Maven wrapper + pom first so dependency resolution is cached as its own layer
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Now the sources; build the executable Spring Boot jar (tests skipped here)
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

############################
#  Stage 2 — slim runtime
############################
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as an unprivileged user
RUN groupadd --system shield && useradd --system --gid shield --home /app shield

# The fat jar produced above
COPY --from=build /app/target/Ra9ed-HAmad-*.jar app.jar
USER shield

# The app listens on 8080
EXPOSE 8080

# Environment variables (set at runtime):
#   SHIELD_AI=groq          — enable LLM agents (or "ollama" for local)
#   GROQ_API_KEY=...        — required if SHIELD_AI=groq
#   GROQ_MODEL=llama-3.3-70b-versatile
#   TAVILY_API_KEY=...      — optional, enables web search tool for latest fraud trends
#
# Without any keys, the app falls back to deterministic mode (fully offline).
ENV JAVA_OPTS="--enable-native-access=ALL-UNNAMED"
ENV SHIELD_AI=""
ENV GROQ_API_KEY=""
ENV GROQ_MODEL="llama-3.3-70b-versatile"
ENV TAVILY_API_KEY=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
