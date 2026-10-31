package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.kitex.mom.mes.controller.MesWipTimeoutPreviewController;
import com.kitex.mom.mes.dto.MesWipTimeoutPreviewDTO;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.*;

class MesWipTimeoutPreviewAccessTest {
    AnnotationConfigApplicationContext app;
    MockMvc mvc;
    MesWipTimeoutPreviewService service;

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean(name = "pms")
        public Check check() {
            return new Check();
        }
    }

    public static class Check {
        public boolean hasPermission(String p) {
            var a = SecurityContextHolder.getContext().getAuthentication();
            return a != null
                    && a.getAuthorities().stream().anyMatch(v -> v.getAuthority().equals(p));
        }
    }

    @RestControllerAdvice
    static class Advice {
        @ExceptionHandler(AccessDeniedException.class)
        public ResponseEntity<?> denied() {
            return ResponseEntity.status(403).build();
        }
    }

    @BeforeEach
    void setup() {
        service = mock(MesWipTimeoutPreviewService.class);
        when(service.sources("BC1", null))
                .thenReturn(
                        new MesWipTimeoutPreviewDTO.Sources(
                                "MES_WIP_TIMEOUT_PREVIEW@1",
                                "1",
                                "F1",
                                "7",
                                "BC1",
                                null,
                                List.of(),
                                false,
                                null));
        app = new AnnotationConfigApplicationContext();
        app.register(Config.class);
        app.getBeanFactory().registerSingleton("service", service);
        app.registerBean(MesWipTimeoutPreviewController.class);
        app.refresh();
        mvc =
                MockMvcBuilders.standaloneSetup(app.getBean(MesWipTimeoutPreviewController.class))
                        .setControllerAdvice(new Advice())
                        .build();
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        app.close();
    }

    void login(List<String> rights) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "test",
                                "N/A",
                                rights.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mesWipTimeoutConfig:show", "mes_mesWarehouseLiveStock_view"})
    void bothReadPermissionsRequiredDespiteInnerHeader(String missing) throws Exception {
        login(
                List.of("mesWipTimeoutConfig:show", "mes_mesWarehouseLiveStock_view").stream()
                        .filter(p -> !p.equals(missing))
                        .toList());
        assertEquals(
                403,
                mvc.perform(
                                get("/mesWipTimeoutConfig/preview/sources")
                                        .param("barcode", "BC1")
                                        .header("from", "Y"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        verify(service, never()).sources("BC1", null);
    }

    @Test
    void normalAuthorizedReaderCanUsePublicEndpoint() throws Exception {
        login(List.of("mesWipTimeoutConfig:show", "mes_mesWarehouseLiveStock_view"));
        assertEquals(
                200,
                mvc.perform(get("/mesWipTimeoutConfig/preview/sources").param("barcode", "BC1"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        verify(service).sources("BC1", null);
    }
}
