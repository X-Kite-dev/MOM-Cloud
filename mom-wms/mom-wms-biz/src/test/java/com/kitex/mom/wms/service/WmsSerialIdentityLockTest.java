package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class WmsSerialIdentityLockTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WmsSerialIdentityLock lock = new WmsSerialIdentityLock(jdbc);

    @Test
    void refusesToClaimSerialWithoutInventoryTransaction() {
        assertThrows(CheckedException.class, () -> lock.lock(1L, "F1", "SN-1"));
        verifyZeroInteractions(jdbc);
    }

    @Test
    void holdsScopedSerialLockInActiveTransaction() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("1:F1"), eq("SN-1")))
                .thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertDoesNotThrow(() -> lock.lock(1L, "F1", "SN-1"));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq("1:F1"), eq("SN-1"));
    }
}
