# syntax=docker/dockerfile:1
#
# Build context is the repository root: the build needs Database/migrations as
# well as Backend/, because the migrations are packaged onto the classpath from
# their single canonical location rather than duplicated into the module.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer so a source-only change does not
# re-download the world.
COPY Backend/pom.xml Backend/pom.xml
RUN mvn -B -f Backend/pom.xml dependency:go-offline -q

COPY Database/migrations Database/migrations
COPY Backend/src Backend/src
RUN mvn -B -f Backend/pom.xml clean package -DskipTests -q


FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Unprivileged: nothing this process does needs root.
RUN addgroup -S rlna && adduser -S rlna -G rlna
COPY --from=build /build/Backend/target/*.jar app.jar
RUN chown -R rlna:rlna /app
USER rlna

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/health >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
