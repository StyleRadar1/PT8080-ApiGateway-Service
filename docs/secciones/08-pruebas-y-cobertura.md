[⬅ Volver al README Principal](../../README.md)

---

## 8. PRUEBAS Y COBERTURA (≥ 85%)

El proyecto exige una cobertura mínima obligatoria del **85%** validada por **JaCoCo** durante la fase `verify` de Maven.

### Comandos de Ejecución

```bash
# Ejecutar suite de pruebas reactivas y validación de Quality Gate
./mvnw clean verify

# Generar reporte HTML de cobertura
./mvnw jacoco:report
```

* **Ruta del Reporte HTML:** `target/site/jacoco/index.html`

### Métricas de Cobertura y Trazabilidad por Ejecución

A continuación se detalla el origen y contexto de cada métrica obtenida en el ciclo de aseguramiento de calidad:

| Métrica / Indicador | Valor Obtenido | Umbral Mínimo | Fuente / Ejecución de Referencia | Estado |
| :--- | :---: | :---: | :--- | :---: |
| **Cobertura Global de Instrucciones** | **98.0%** | $\ge 85.0\%$ | Reporte JaCoCo HTML consolidado (`target/site/jacoco/index.html`) tras 69 pruebas | 🟢 Superado |
| **Cobertura Global de Líneas** | **97.4%** | $\ge 85.0\%$ | Reporte JaCoCo HTML consolidado (`target/site/jacoco/index.html`) tras 69 pruebas | 🟢 Superado |
| **Cobertura en Filtros y Rutas Reactivas** | **100.0%** | $\ge 85.0\%$ | JaCoCo (`JwtTokenValidatorFilter`, `GlobalErrorExceptionHandler`, etc.) | 🟢 Superado |
| **Pruebas Automatizadas Ejecutadas** | **69 tests** | 100% éxito | Suite unitaria y reactiva con WebTestClient (0 errores, 0 fallos) | 🟢 Passed |
| **Cobertura de Código Nuevo (PR #3)** | **92.3%** | $\ge 80.0\%$ | Análisis SonarCloud en commit `d19963f` (Merge PR #3: contratos y tests) | 🟢 Passed (A) |
| **Cobertura de Código Nuevo (PR #4 Documental)** | **0.0%** | N/A | Análisis SonarCloud en commit `307b7c7` (0 líneas de código Java nuevo ejecutable) | 🟢 Quality Gate Passed |

> [!NOTE]
> **Aclaración sobre el 0.0% de SonarCloud en el PR Documental (#4):**
> En este Pull Request documental (`docs/PT8080-modularizacion-readme`), SonarCloud reporta 0.0% de cobertura en *New Code* debido a que los cambios corresponden exclusivamente a documentación en Markdown (`.md`) y diagramas (`.png`), sin agregar nuevas líneas de código Java ejecutable. El **Quality Gate de SonarCloud se mantiene aprobado con Calificación A** ya que no existen nuevos bugs, vulnerabilidades de seguridad, ni deuda técnica.

### Evidencia de Cobertura JaCoCo Verificada (≥ 85%)

<div align="center">
  <img src="../images/JacocoEvidence.png" alt="Evidencia 03 - Reporte Consolidado de Cobertura JaCoCo" width="100%" style="border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.15);" />
  <p><sub><b>Figura 8.1:</b> Reporte oficial consolidado de cobertura HTML generado por JaCoCo (<code>target/site/jacoco/index.html</code>), certificando un 98.0% de cobertura general de instrucciones, 100% en componentes de filtros, configuración y adaptadores REST, y 97.4% de líneas, superando con holgura el umbral mínimo del 85%.</sub></p>
</div>

---

[⬅ Volver al README Principal](../../README.md)
