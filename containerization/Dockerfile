FROM clojure:temurin-24-lein AS builder
WORKDIR /app
COPY project.clj .
RUN lein deps
COPY . .
RUN lein uberjar

FROM eclipse-temurin:24-jdk-alpine AS jre-builder
RUN jlink \
    --add-modules java.base,java.desktop,java.instrument,java.naming,java.security.jgss,java.sql,jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /custom-jre

FROM alpine:3.21
COPY --from=jre-builder /custom-jre /opt/java
ENV PATH="/opt/java/bin:$PATH"
WORKDIR /app
COPY --from=builder /app/target/uberjar/*-standalone.jar app.jar
EXPOSE 3000
CMD ["java", "-jar", "app.jar"]
