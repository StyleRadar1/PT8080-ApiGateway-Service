[⬅ Volver al README Principal](../../README.md)

---

## 6. CÓMO EJECUTAR LOCAL Y CON DOCKER

### Prerrequisitos de Entorno

* **Java Development Kit (JDK):** Versión 21 LTS instalada (`java -version`).
* **Apache Maven:** Versión 3.9+ o uso del wrapper incluido `./mvnw`.
* **Docker Engine & Docker Compose:** Versión 24+ en ejecución (`docker --version`).

### Ejecución Local con Maven

```bash
# 1. Clonar el repositorio
git clone https://github.com/StyleRadar1/PT8080-ApiGateway-Service.git
cd PT8080-ApiGateway-Service

# 2. Ejecutar el Gateway localmente en el puerto 8080
./mvnw clean spring-boot:run -Dspring-boot.run.profiles=dev
```

### Ejecución Contenerizada Completa con Docker Compose

```bash
# Levantar el contenedor del API Gateway en segundo plano
docker compose up -d --build

# Verificar estado y salud del contenedor
docker compose ps

# Ver logs en tiempo real
docker compose logs -f api-gateway

# Detener el servicio
docker compose down
```

### Evidencia de Contenedor OCI en Ejecución (Docker Desktop & Logs)

<div align="center">
  <img src="../images/DockerEvidence.png" alt="Evidencia 02 - Contenedor OCI en Ejecución (docker compose ps y logs)" width="100%" style="border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.15);" />
  <p><sub><b>Figura 6.1:</b> Ejecución contenerizada en Docker Desktop verificando el contenedor <code>sr-api-gateway</code> levantado en el puerto <code>8080:8080</code>, inicialización en 11.12 segundos y mapeo dinámico de rutas hacia los microservicios de StyleRadar.</sub></p>
</div>

### Comprobación de Salud (Health Check)

```bash
curl -s http://localhost:8080/actuator/health
# Esperado: {"status":"UP"}
```

---

[⬅ Volver al README Principal](../../README.md)
