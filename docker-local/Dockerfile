# Builds every service jar inside a JDK 17 container (the Gradle toolchain requires Java 17),
# then produces one small runtime image per service. Used by ../docker-compose.yml.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain \
      :customer-service:customer-service-main:bootJar \
      :order-service:order-service-main:bootJar \
      :order-history-service:order-history-service-main:bootJar \
      :api-gateway-service:api-gateway-service-main:bootJar \
 && mkdir /out \
 && for m in customer-service/customer-service-main order-service/order-service-main \
             order-history-service/order-history-service-main api-gateway-service/api-gateway-service-main; do \
      cp "$(ls $m/build/libs/*.jar | grep -v -- '-plain.jar')" "/out/$(basename $m).jar"; \
    done

# JDK (not JRE): the API Gateway calls ReactorDebugAgent.init(), which needs the jdk.attach module
FROM eclipse-temurin:17-jdk AS runtime
WORKDIR /app
ENV JAVA_OPTS=""
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=40 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/service.jar"]

FROM runtime AS customer-service
COPY --from=build /out/customer-service-main.jar /app/service.jar

FROM runtime AS order-service
COPY --from=build /out/order-service-main.jar /app/service.jar

FROM runtime AS order-history-service
COPY --from=build /out/order-history-service-main.jar /app/service.jar

FROM runtime AS api-gateway-service
COPY --from=build /out/api-gateway-service-main.jar /app/service.jar
