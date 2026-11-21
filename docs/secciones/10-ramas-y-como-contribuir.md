[⬅ Volver al README Principal](../../README.md)

---

## 10. RAMAS Y CÓMO CONTRIBUIR

El equipo sigue el modelo de trabajo **GitFlow adaptado para microservicios**.

### Estructura Oficial de Ramas

```text
main                    <-- Código en producción (Desplegado en Azure PROD - Sprint 2/3)
└── develop             <-- Rama de integración continua (Staging / QA)
    ├── feature/scaffolding                    <-- Chasis inicial, rutas reactivas y Docker
    ├── feature/PT8080-<ID>-<descripcion>      <-- Nuevas políticas y rutas del Sprint
    ├── bugfix/PT8080-<ID>-<descripcion>       <-- Corrección de defectos reportados
    └── hotfix/<descripcion-urgente>           <-- Parche crítico directo a producción
```

### Convención de Commits (*Conventional Commits*)

```text
<tipo>(<alcance>): <descripción imperativa en español> [#PT8080-XX]

Ejemplos válidos en api-gateway:
feat(routes): configurar enrutamiento reactivo hacia ms-probador-virtual en puerto 8500
fix(cors): admitir cabeceras de autorizacion para clientes en localhost 5173
test(filter): pruebas unitarias para inyeccion de X-Correlation-Id perimetral
docs(readme): documentar matriz de rutas y politicas de circuit breaker
```

### Checklist Obligatorio para Pull Requests

- [x] La rama parte de la versión más reciente de `develop`.
- [x] El código compila limpiamente sin advertencias críticas con `./mvnw clean compile`.
- [x] Todas las pruebas automatizadas pasan en verde (`./mvnw clean verify`).
- [x] La cobertura de código en JaCoCo supera el 85% obligatorio (alcanzado 92.3% en SonarCloud).
- [x] No se introducen credenciales, secretos ni contraseñas en el código (`.env.example`).
- [x] Los contenedores OCI se construyen exitosamente con `docker compose build`.
- [ ] El PR cuenta con al menos un revisor aprobado del squad antes de mezclarse a `develop`.

---

[⬅ Volver al README Principal](../../README.md)
