# --- build stage ---
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
COPY pom.xml .
RUN mvn -B dependency:go-offline || true
COPY src ./src
RUN mvn -B package -DskipTests

# --- runtime stage ---
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/weka-api-*-shaded.jar /app/app.jar
RUN mkdir -p /app/models /app/data
EXPOSE 7070
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
