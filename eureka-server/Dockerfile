FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8761
HEALTHCHECK --interval=20s --timeout=10s --retries=5 --start-period=30s \
  CMD curl -f http://localhost:8761/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
