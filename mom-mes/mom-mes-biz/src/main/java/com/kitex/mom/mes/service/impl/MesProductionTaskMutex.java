package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.mapper.MesIdentifierBindingGroupMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 仅技术互斥，不授予人员权限；内部调用者必须核验来源，人员调用者必须先实名核权。
 */
@Service
@RequiredArgsConstructor
public class MesProductionTaskMutex {
    private final MesIdentifierBindingGroupMapper locks;

    static String key(Long tenant, String factory, String code) {
        if (tenant == null
                || tenant <= 0
                || factory == null
                || factory.isBlank()
                || code == null
                || code.isBlank()
                || code.length() > 100
                || code.chars().anyMatch(Character::isISOControl))
            throw new CheckedException("生产工单锁缺少有效实际组织或工单号");
        return JSONUtil.toJsonStr(
                List.of("MES_NODE_TASK_LOCK_V1", tenant.toString(), factory, code));
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lock(String code) {
        MesQualityGateSourceEvidence.scope();
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new CheckedException("生产工单互斥必须处于实际事务");
        if (!Integer.valueOf(1)
                .equals(
                        locks.lockTaskBusinessKey(
                                key(
                                        TenantContextHolder.getTenantId(),
                                        FactoryContextHolder.getFactoryCode(),
                                        code)))) throw new CheckedException("未取得生产工单业务锁");
    }
}
