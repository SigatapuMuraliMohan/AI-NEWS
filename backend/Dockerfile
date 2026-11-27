FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/news-meter-1.0.0.jar /app/app.jar
USER 65534
EXPOSE 8086
ENTRYPOINT ["java","-jar","/app/app.jar"]
