[⬅ Volver al README Principal](../../README.md)

---

## 9. CI/CD CON EL BADGE DEL PIPELINE

El ciclo de vida del API Gateway implementa integración continua automatizada mediante **GitHub Actions** en el archivo `.github/workflows/ci.yml`.

### Matriz de Pipelines por Rama Protegida

| Pipeline | Rama / Entorno | Trigger | Quality Gate Requerido | Estado |
| :--- | :---: | :---: | :--- | :---: |
| **CI - Develop** | `develop`<br>*(Staging)* | Push / PR | Compilación + Pruebas + JaCoCo ≥ 85% + Docker Build | [![CI Develop](https://img.shields.io/badge/develop-passing-16A34A?style=flat-square&logo=githubactions&logoColor=white)](https://github.com/StyleRadar1/PT8080-ApiGateway-Service/actions) |
| **CI - Main** | `main`<br>*(Producción)* | Push / PR | Compilación + Pruebas + JaCoCo ≥ 85% + Docker Build | [![CI Main](https://img.shields.io/badge/main-passing-16A34A?style=flat-square&logo=githubactions&logoColor=white)](https://github.com/StyleRadar1/PT8080-ApiGateway-Service/actions) |
| **CD - QA Azure** | `develop`<br>*(Cloud QA)* | Push a `develop` | Build + Pruebas + JaCoCo + SonarCloud + Docker + Azure Deploy + Smoke Test | [![CD QA](https://img.shields.io/badge/azure_qa-passing-5600EF?style=flat-square&logo=azure-app-services&logoColor=white)](https://github.com/StyleRadar1/PT8080-ApiGateway-Service/actions) |
| **CD - PROD Azure** | `main`<br>*(Cloud PROD)* | Push a `main` / Tag `v*` | Build + Pruebas + JaCoCo + SonarCloud + Docker + Azure Deploy + Smoke Test | [![CD PROD](https://img.shields.io/badge/azure_prod-passing-16A34A?style=flat-square&logo=azure-app-services&logoColor=white)](https://github.com/StyleRadar1/PT8080-ApiGateway-Service/actions) |
| **SonarCloud Gate** | `main`, `develop`<br>*(Estático)* | Push / PR | Gate A (0 Bugs + 0 Vulnerabilidades + JaCoCo ≥ 85%) | [![SonarCloud](https://img.shields.io/badge/SonarCloud-passed-16A34A?style=flat-square&logo=sonarcloud&logoColor=white)](https://sonarcloud.io/project/overview?id=StyleRadar1_PT8080-ApiGateway-Service) |

### Diagrama del Flujo Automatizado (Pipeline Stages)

```mermaid
flowchart LR
    A["Push / PR<br>(main / develop)"] --> B["Setup JDK 21<br>(Temurin & Cache)"]
    B --> C["Build & Tests<br>(mvn clean verify)"]
    C --> D["JaCoCo Verify<br>(Coverage ≥ 85%)"]
    D --> E["SonarCloud Gate A<br>(Obligatorio)"]
    E --> F["Docker Build & Push<br>(Tags SHA & Latest)"]
    F --> G["Azure Deploy & Smoke Test<br>(Commit SHA Verify)"]

    classDef step fill:#12063A,stroke:#5600EF,stroke-width:2px,color:#FFFFFF;
    classDef pass fill:#DCFCE7,stroke:#16A34A,stroke-width:2px,color:#0C0C12;

    class A,B step;
    class C,D,E,F,G pass;
```

---

[⬅ Volver al README Principal](../../README.md)
