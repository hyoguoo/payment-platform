FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8084
HEALTHCHECK --interval=20s --timeout=10s --retries=5 --start-period=60s \
  CMD curl -f http://localhost:8084/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
