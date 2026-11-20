FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY control/pom.xml ./control/pom.xml
COPY control/src ./control/src
COPY engine/pom.xml ./engine/pom.xml
COPY engine/src ./engine/src
RUN mvn -B -pl control,engine -am package -DskipTests

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

FROM runtime AS engine
COPY --from=build /workspace/engine/target/engine-0.1.0-SNAPSHOT.jar /app/engine.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/engine.jar"]

FROM runtime AS control
COPY --from=build /workspace/control/target/control-0.1.0-SNAPSHOT.jar /app/control.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/control.jar"]
