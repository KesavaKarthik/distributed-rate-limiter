# ---- Stage 1: build the jar -------------------------------------------------
# Full JDK + Maven here; none of it ships in the final image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM alone first so the dependency download layer is cached and only
# re-runs when dependencies actually change, not on every source edit.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Stage 2: runtime -------------------------------------------------------
# JRE only: no compiler, no Maven, no build cache in the shipped image.
FROM eclipse-temurin:21-jre
WORKDIR /app

# Don't run as root.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
USER appuser

COPY --from=build /build/target/*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
