package com.kitex.mom.mes.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.service.impl.MesMaterialReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesMaterialReceiptSitePermissionTest {
    public static class Permissions {
        private final Set<String> permissions;

        public Permissions(Set<String> permissions) {
            this.permissions = permissions;
        }

        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }
    }

    @Test
    void stationChoicesAreAvailableToTheReceivingRoleWithoutGrantingOtherReceiptReads() throws Exception {
        var expression = MesMaterialReceiptController.class.getMethod("sites", Long.class)
                .getAnnotation(PreAuthorize.class).value();
        var parser = new SpelExpressionParser();
        for (var permissions : Set.of(Set.of("mes_materialReceipt_receive"), Set.of("mes_materialReceipt_view"))) {
            var context = new StandardEvaluationContext();
            context.setBeanResolver((evaluation, name) -> new Permissions(permissions));
            assertEquals(Boolean.TRUE, parser.parseExpression(expression).getValue(context), permissions.toString());
        }
        for (var permissions : Set.of(Set.<String>of(), Set.of("mes_materialReceipt_import"), Set.of("mes_materialReceipt_reverse"))) {
            var context = new StandardEvaluationContext();
            context.setBeanResolver((evaluation, name) -> new Permissions(permissions));
            assertEquals(Boolean.FALSE, parser.parseExpression(expression).getValue(context), permissions.toString());
        }
        assertEquals("@pms.hasPermission('mes_materialReceipt_view')", MesMaterialReceiptController.class
                .getMethod("notice", Long.class).getAnnotation(PreAuthorize.class).value());
    }

    @Test
    void authorizedStationReadStillDelegatesToTheNoticeFactoryScopeCheck() {
        var service = mock(MesMaterialReceiptService.class);
        var denied = new CheckedException("未获该工厂read授权");
        when(service.sites(42L)).thenThrow(denied);

        assertSame(denied, assertThrows(CheckedException.class, () -> new MesMaterialReceiptController(service).sites(42L)));
        verify(service).sites(42L);
    }
}
