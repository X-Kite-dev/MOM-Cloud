package com.kitex.mom.iot.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kitex.mom.iot.agent.dto.IotProcessTelemetryDTO;
import com.kitex.mom.iot.agent.service.IotProcessTelemetryQueryService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Real MVC binding/validation and actual method-security proxy; only domain query is an adapted
 * port.
 */
class IotParameterComparisonAccessTest {
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    IotProcessTelemetryQueryService query;
    static final List<String> ALL =
            List.of("mesTask:show", "mesProduceTask:show", "iot_device_view");

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean(name = "pms")
        PermissionCheck permissions() {
            return new PermissionCheck();
        }

        @Bean
        IotProcessTelemetryQueryService query() {
            return mock(IotProcessTelemetryQueryService.class);
        }

        @Bean
        Object comparison(IotProcessTelemetryQueryService query) throws Exception {
            try {
                return Class.forName("com.kitex.mom.iot.controller.IotProcessParameterController")
                        .getDeclaredConstructor(IotProcessTelemetryQueryService.class)
                        .newInstance(query);
            } catch (ClassNotFoundException notBuiltYet) {
                return new Object();
            }
        }
    }

    public static class PermissionCheck {
        public boolean hasPermission(String permission) {
            var a = SecurityContextHolder.getContext().getAuthentication();
            return a != null
                    && a.getAuthorities().stream()
                            .anyMatch(x -> permission.equals(x.getAuthority()));
        }
    }

    @RestControllerAdvice
    static class ForbiddenAdvice {
        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void denied() {}
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        query = context.getBean(IotProcessTelemetryQueryService.class);
        when(query.query(any()))
                .thenAnswer(
                        c -> {
                            IotProcessTelemetryDTO.Query q = c.getArgument(0);
                            return new IotProcessTelemetryDTO.Evidence()
                                    .setFactoryCode(q.getFactoryCode())
                                    .setTaskCode(q.getTaskCode())
                                    .setProcessComplianceStatus("PARTIAL");
                        });
        mvc =
                MockMvcBuilders.standaloneSetup(context.getBean("comparison"))
                        .setControllerAdvice(new ForbiddenAdvice())
                        .build();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    void authorize(List<String> permissions) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "test-worker",
                                "N/A",
                                permissions.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    void authorizedProductionReadUsesTheRealPublicGetEndpoint() throws Exception {
        authorize(ALL);
        mvc.perform(
                        get("/process-parameters/comparison")
                                .param("factoryCode", "F1")
                                .param("taskCode", "MT1")
                                .param("acceptedSnapshotSchemaVersion", "ROUTE_NODE_V2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskCode").value("MT1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mesTask:show", "mesProduceTask:show", "iot_device_view"})
    void missingEitherOriginalPermissionCannotReadEvenWithInternalHeader(String denied)
            throws Exception {
        authorize(ALL.stream().filter(x -> !x.equals(denied)).toList());
        mvc.perform(
                        get("/process-parameters/comparison")
                                .header("from", "Y")
                                .param("factoryCode", "F1")
                                .param("taskCode", "MT1"))
                .andExpect(status().isForbidden());
        verify(query, never()).query(any());
    }

    @Test
    void missingTaskIsRejectedBeforeQuery() throws Exception {
        authorize(ALL);
        mvc.perform(get("/process-parameters/comparison").param("factoryCode", "F1"))
                .andExpect(status().isBadRequest());
        verify(query, never()).query(any());
    }
}
