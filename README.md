<!-- ==============================================================================
  STYLE RADAR · PLANTILLA MAESTRA DE README PARA MICROSERVICIOS
  Asignatura: DOSW (Diseño y Operación de Soluciones Web) · 2026
  Estándar obligatorio unificado para los componentes del proyecto StyleRadar.
  Componente: PT8080-ApiGateway-Service (API Gateway Perimetral)
  Squad Asignado: Portal8080 (PT8080) · Puerto Oficial: 8080
============================================================================== -->

<div align="center">

  <a href="https://github.com/StyleRadar1">
    <img src="https://raw.githubusercontent.com/JUANNICOLASA/Frontend-Style-Radar/main/styleradar-frontend-v3/assets/logo/styleradar-logo.svg" width="340" alt="StyleRadar Logo Oficial" />
  </a>

  <h1>STYLE<font color="#5600EF">RADAR</font> &nbsp;·&nbsp; <code>api-gateway</code></h1>

  <p align="center">
    <img src="https://img.shields.io/badge/BOUNDED_CONTEXT-EDGE__GATEWAY-12063A?style=for-the-badge" alt="Context" />
    <img src="https://img.shields.io/badge/PUERTO_OFICIAL-8080-5600EF?style=for-the-badge" alt="Port" />
    <img src="https://img.shields.io/badge/MOTOR-NETTY__REACTIVO-16A34A?style=for-the-badge" alt="Reactive Netty" />
    <img src="https://img.shields.io/badge/SQUAD-PORTAL8080-12063A?style=for-the-badge" alt="Squad" />
  </p>

  <p align="center">
    <strong><font color="#5600EF">Descubre tu estilo. En tu ciudad.</font></strong><br />
    <em>Puerta de entrada unificada, enrutamiento perimetral reactivo, agregación de Swagger y tolerancia a fallos para StyleRadar Bogotá.</em>
  </p>

  <p align="center">
    <a href="docs/secciones/03-endpoints-y-enlace-a-swagger.md"><img src="https://img.shields.io/badge/DEV_LOCAL-Active-16A34A?style=for-the-badge&logo=docker&logoColor=white" alt="DEV Local Active" /></a>
    <a href="docs/secciones/03-endpoints-y-enlace-a-swagger.md"><img src="https://img.shields.io/badge/QA_CLOUD-Active-5600EF?style=for-the-badge&logo=azure-app-services&logoColor=white" alt="QA Cloud Active" /></a>
    <a href="docs/secciones/03-endpoints-y-enlace-a-swagger.md"><img src="https://img.shields.io/badge/PROD_CLOUD-Active-16A34A?style=for-the-badge&logo=azure-app-services&logoColor=white" alt="PROD Cloud Active" /></a>
    <a href="https://github.com/StyleRadar1/PT8080-ApiGateway-Service/actions"><img src="https://img.shields.io/badge/CI_MAIN-passing-16A34A?style=for-the-badge&logo=githubactions&logoColor=white" alt="CI Main Status" /></a>
    <a href="https://github.com/StyleRadar1/PT8080-ApiGateway-Service/actions"><img src="https://img.shields.io/badge/CI_DEVELOP-passing-16A34A?style=for-the-badge&logo=githubactions&logoColor=white" alt="CI Develop Status" /></a>
    <a href="docs/secciones/08-pruebas-y-cobertura.md"><img src="https://img.shields.io/badge/JaCoCo_Coverage-97.4%25-16A34A?style=for-the-badge&logo=codecov&logoColor=white" alt="Coverage" /></a>
    <a href="docs/secciones/09-cicd-pipeline.md"><img src="https://img.shields.io/badge/SonarCloud-Quality_Gate_Passed_Rating_A-16A34A?style=for-the-badge&logo=sonarcloud&logoColor=white" alt="SonarCloud Quality Gate" /></a>
  </p>

  <p align="center">
    <a href="docs/secciones/05-tecnologias-y-dependencias.md"><img src="https://img.shields.io/badge/JAVA-21_LTS-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21" /></a>
    <a href="docs/secciones/05-tecnologias-y-dependencias.md"><img src="https://img.shields.io/badge/SPRING_CLOUD-2023.0.3-12063A?style=for-the-badge&logo=spring&logoColor=white" alt="Spring Cloud" /></a>
    <a href="docs/secciones/05-tecnologias-y-dependencias.md"><img src="https://img.shields.io/badge/SPRING_BOOT-3.2.5-12063A?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot" /></a>
    <a href="docs/secciones/06-como-ejecutar-local-y-docker.md"><img src="https://img.shields.io/badge/DOCKER-OCI_CONTAINER-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker" /></a>
    <a href="docs/secciones/05-tecnologias-y-dependencias.md"><img src="https://img.shields.io/badge/RESILIENCE4J-2.1.0-FF6600?style=for-the-badge&logo=circuitverse&logoColor=white" alt="Resilience4j" /></a>
  </p>

</div>

---

## <img src="https://img.shields.io/badge/ÍNDICE-5600EF?style=for-the-badge" height="20" align="absmiddle" /> ÍNDICE DE CONTENIDOS

1. [Nombre del servicio y su bounded context](docs/secciones/01-nombre-del-servicio-y-bounded-context.md)
2. [Arquitectura hexagonal con sus diagramas](docs/secciones/02-arquitectura-hexagonal-y-diagramas.md)
3. [Endpoints y enlace a Swagger](docs/secciones/03-endpoints-y-enlace-a-swagger.md)
4. [Eventos que publica y consume / Contratos de integración](docs/secciones/04-eventos-publica-consume-y-contratos.md)
5. [Tecnologías y dependencias](docs/secciones/05-tecnologias-y-dependencias.md)
6. [Cómo ejecutar local y con Docker](docs/secciones/06-como-ejecutar-local-y-docker.md)
7. [Variables de entorno](docs/secciones/07-variables-de-entorno.md)
8. [Pruebas y cobertura (≥ 85%)](docs/secciones/08-pruebas-y-cobertura.md)
9. [CI/CD con el badge del pipeline](docs/secciones/09-cicd-pipeline.md)
10. [Ramas y cómo contribuir](docs/secciones/10-ramas-y-como-contribuir.md)
11. [Seguridad y análisis OWASP Top 10:2025](docs/secciones/11-seguridad-y-owasp.md)
12. [Equipo, responsables y enlace a la épica en Jira](docs/secciones/12-equipo-responsables-y-epica-jira.md)
