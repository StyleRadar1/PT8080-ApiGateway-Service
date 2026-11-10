# ===================================================================
# Multi-stage Dockerfile para PT8080-ApiGateway-Service
# Microservicio API Gateway Perimetral - Puerto 8080
# Squad Portal8080 · DOSW 2026
# ===================================================================

# Stage 1: Compilación y empaquetado del artefacto
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copiar wrapper y descriptores Maven para aprovechar la caché de capas
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copiar código fuente y compilar
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Imagen final liviana de ejecución (JRE Alpine)
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Crear usuario sin privilegios por seguridad
RUN addgroup -S styleradar && adduser -S gateway -G styleradar

# Copiar el jar compilado desde el builder
COPY --from=builder /workspace/target/*.jar app.jar
RUN chown -R gateway:styleradar /app

USER gateway

# Exponer el puerto oficial del API Gateway
EXPOSE 8080

# Variables de entorno por defecto
ENV SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=dev \
    JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Verificación de salud periódica (Healthcheck perimetral)
HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

