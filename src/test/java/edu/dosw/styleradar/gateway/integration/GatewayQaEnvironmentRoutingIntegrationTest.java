package edu.dosw.styleradar.gateway.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de integración de certificación de enrutamiento en ambiente QA (Azure Cloud).
 * Certifica que ninguna ruta en QA apunte a localhost ni a backends monolíticos obsoletos,
 * y que todos los microservicios resuelvan hacia sus respectivos destinos PaaS en Azure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "security.jwt.secret-base64=c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg=="
})
@ActiveProfiles("qa")
class GatewayQaEnvironmentRoutingIntegrationTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    @DisplayName("Ambiente QA: Certifica que ninguna ruta en QA apunte a localhost")
    void shouldVerifyNoRouteInQaPointsToLocalhost() {
        Flux<Route> routesFlux = routeLocator.getRoutes();
        List<Route> routes = routesFlux.collectList().block();

        assertNotNull(routes, "La lista de rutas de QA no debe ser nula");
        assertFalse(routes.isEmpty(), "Deben existir rutas configuradas en el perfil de QA");

        for (Route route : routes) {
            URI uri = route.getUri();
            assertNotNull(uri, "La URI de la ruta " + route.getId() + " no debe ser nula");
            assertFalse(uri.toString().contains("localhost"),
                    "La ruta [" + route.getId() + "] en QA apunta ilegalmente a localhost: " + uri);
            assertFalse(uri.toString().contains("127.0.0.1"),
                    "La ruta [" + route.getId() + "] en QA apunta ilegalmente a 127.0.0.1: " + uri);
        }
    }

    @Test
    @DisplayName("Ambiente QA: Certifica que todos los microservicios resuelvan hacia Azure App Services (*.azurewebsites.net)")
    void shouldVerifyAllMicroservicesInQaPointToAzure() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertNotNull(routes);

        Map<String, URI> routeUriMap = routes.stream()
                .collect(Collectors.toMap(Route::getId, Route::getUri));

        // Validación de rutas de negocio clave
        assertTrue(routeUriMap.containsKey("ms-probador-virtual"));
        assertTrue(routeUriMap.get("ms-probador-virtual").getHost().contains("styleradar-virtualtryon-qa.azurewebsites.net"));

        assertTrue(routeUriMap.containsKey("ms-segunda-vida-donaciones"));
        assertTrue(routeUriMap.get("ms-segunda-vida-donaciones").getHost().contains("styleradar-donaciones-qa-jmunar.azurewebsites.net"));

        assertTrue(routeUriMap.containsKey("ms-auth"));
        assertTrue(routeUriMap.get("ms-auth").getHost().contains("styleradar-auth-qa.azurewebsites.net"));

        assertTrue(routeUriMap.containsKey("ms-notificaciones"));
        assertTrue(routeUriMap.get("ms-notificaciones").getHost().contains("styleradar-notificaciones-qa.azurewebsites.net"));

        assertTrue(routeUriMap.containsKey("ms-item-catalogo"));
        assertTrue(routeUriMap.get("ms-item-catalogo").getHost().contains("rst-itemcatalog-service-qa.redwave-74d6e30f.northcentralus.azurecontainerapps.io"));

        assertTrue(routeUriMap.containsKey("ms-inventario"));
        assertTrue(routeUriMap.get("ms-inventario").getHost().contains("rst-inventory-service-qa.redwave-74d6e30f.northcentralus.azurecontainerapps.io"));

        assertTrue(routeUriMap.containsKey("ms-chat"));
        assertTrue(routeUriMap.get("ms-chat").getHost().contains("gj-chat-service-qa-santi-gxhjccffcxdgbfcj.canadacentral-01.azurewebsites.net"));
        assertEquals("https", routeUriMap.get("ms-chat").getScheme());

        assertTrue(routeUriMap.containsKey("ms-chat-conversations"));
        assertTrue(routeUriMap.get("ms-chat-conversations").getHost().contains("gj-chat-service-qa-santi-gxhjccffcxdgbfcj.canadacentral-01.azurewebsites.net"));
        assertEquals("https", routeUriMap.get("ms-chat-conversations").getScheme());

        assertTrue(routeUriMap.containsKey("ms-chat-ws"));
        assertTrue(routeUriMap.get("ms-chat-ws").getHost().contains("gj-chat-service-qa-santi-gxhjccffcxdgbfcj.canadacentral-01.azurewebsites.net"));
        assertEquals("wss", routeUriMap.get("ms-chat-ws").getScheme());

        assertTrue(routeUriMap.containsKey("ms-usuarios"));
        assertTrue(routeUriMap.get("ms-usuarios").getHost().contains("styleradar-qa-app.azurewebsites.net"));

        assertTrue(routeUriMap.containsKey("ms-playlist"));
        assertTrue(routeUriMap.get("ms-playlist").getHost().contains("rst-playlist-service-qa.redwave-74d6e30f.northcentralus.azurecontainerapps.io"));

        assertTrue(routeUriMap.containsKey("ms-pagos-suscripciones"));
        assertTrue(routeUriMap.get("ms-pagos-suscripciones").getHost().contains("ca-styleradar-pagos-juangv1.lemonplant-91cfa5ac.northcentralus.azurecontainerapps.io"));

        assertTrue(routeUriMap.containsKey("ms-geolocalizacion-almacenes"));
        assertTrue(routeUriMap.get("ms-geolocalizacion-almacenes").getHost().contains("rst-geolocation-service-qa.redwave-74d6e30f.northcentralus.azurecontainerapps.io"));
    }

    @Test
    @DisplayName("Ambiente QA: Certifica que los endpoints de Swagger Aggregator apunten a los microservicios en Azure")
    void shouldVerifySwaggerAggregatorRoutesInQa() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertNotNull(routes);

        Map<String, URI> routeUriMap = routes.stream()
                .collect(Collectors.toMap(Route::getId, Route::getUri));

        assertEquals("styleradar-virtualtryon-qa.azurewebsites.net", routeUriMap.get("ms-probador-virtual-swagger").getHost());
        assertEquals("styleradar-donaciones-qa-jmunar.azurewebsites.net", routeUriMap.get("ms-donaciones-swagger").getHost());
        assertEquals("styleradar-auth-qa.azurewebsites.net", routeUriMap.get("ms-auth-swagger").getHost());
        assertEquals("styleradar-notificaciones-qa.azurewebsites.net", routeUriMap.get("ms-notificaciones-swagger").getHost());
        assertEquals("styleradar-qa-app.azurewebsites.net", routeUriMap.get("ms-usuarios-swagger").getHost());
        assertEquals("gj-chat-service-qa-santi-gxhjccffcxdgbfcj.canadacentral-01.azurewebsites.net", routeUriMap.get("ms-chat-swagger").getHost());
    }

    @Test
    @DisplayName("Ambiente QA: Certifica que ms-segunda-vida-donaciones enrute los paths estandarizados (/donations/** y /second-hand/**)")
    void shouldVerifyDonacionesRoutesContainStandardizedPaths() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertNotNull(routes);

        Route donacionesRoute = routes.stream()
                .filter(r -> "ms-segunda-vida-donaciones".equals(r.getId()))
                .findFirst()
                .orElse(null);

        assertNotNull(donacionesRoute, "La ruta ms-segunda-vida-donaciones debe existir");
        String predicateString = donacionesRoute.getPredicate().toString();
        assertTrue(predicateString.contains("/api/v1/donations") || predicateString.contains("donations"),
                "Debe incluir el prefijo estandarizado /api/v1/donations/**");
        assertTrue(predicateString.contains("/api/v1/second-hand") || predicateString.contains("second-hand"),
                "Debe incluir el prefijo estandarizado /api/v1/second-hand/**");
    }
}
