package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在库存事务内协调物料追溯要求的变更，并串行化同一工厂、同一序列号的查重与入库。
 */
@Service
@RequiredArgsConstructor
public class WmsSerialIdentityLock {
    private static final String LOCK_SQL = "SELECT 1 FROM (SELECT "
            + "pg_advisory_xact_lock(hashtext(?), hashtext(?))) AS held";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 核对物料最低批次、单件要求前取得共享事务锁。第422节的物料变更取得同一键的排它锁，
     * 保证从查询MDM到入库明细或库存落账之间不会换用另一套身份要求。
     */
    public void lockMaterialPolicy(Long tenantId, String factoryCode, String materialCode) {
        if (tenantId == null || StrUtil.isBlank(factoryCode) || StrUtil.isBlank(materialCode)
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new CheckedException("物料追溯要求须在当前工厂库存事务内核对");
        }
        Integer acquired = jdbcTemplate.queryForObject("""
                SELECT 1 FROM pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtextextended(
                    pg_catalog.jsonb_build_array(?::bigint, ?::text, ?::text)::text, 422))
                """, Integer.class, tenantId, factoryCode, materialCode);
        if (!Integer.valueOf(1).equals(acquired)) {
            throw new CheckedException("物料追溯要求锁定失败，请重新核对原单");
        }
    }

    /** 实物写入前按组织和完整条码排它核重，与物料级共享锁分别取键。 */
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
