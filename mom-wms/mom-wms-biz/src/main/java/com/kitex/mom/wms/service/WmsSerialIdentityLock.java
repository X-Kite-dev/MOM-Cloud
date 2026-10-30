package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在实物库存写入事务内串行化同一工厂、同一序列号的查重与入库。
 */
@Service
@RequiredArgsConstructor
public class WmsSerialIdentityLock {
    private static final String LOCK_SQL = "SELECT 1 FROM (SELECT "
            + "pg_advisory_xact_lock(hashtext(?), hashtext(?))) AS held";

    private final JdbcTemplate jdbcTemplate;

    public void lock(Long tenantId, String factoryCode, String barcode) {
        if (tenantId == null || StrUtil.isBlank(factoryCode) || StrUtil.isBlank(barcode)
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new CheckedException("单件序列号查重必须在有效库存事务内执行");
        }
        Integer acquired = jdbcTemplate.queryForObject(LOCK_SQL, Integer.class,
                tenantId + ":" + factoryCode, barcode);
        if (!Integer.valueOf(1).equals(acquired)) {
            throw new CheckedException("单件序列号锁定失败，禁止写入库存");
        }
    }
}
