FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY engine/pom.xml ./engine/pom.xml
COPY engine/src ./engine/src
RUN mvn -B -pl engine -am package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/engine/target/engine-0.1.0-SNAPSHOT.jar /app/engine.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/engine.jar"]
