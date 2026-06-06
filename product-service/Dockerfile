FROM eclipse-temurin:21-jre
ENV TZ=UTC
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8083
HEALTHCHECK --interval=20s --timeout=10s --retries=5 --start-period=60s \
  CMD curl -f http://localhost:8083/actuator/health || exit 1
ENTRYPOINT ["java","-Duser.timezone=UTC","-jar","/app/app.jar"]
