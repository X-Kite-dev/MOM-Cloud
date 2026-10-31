package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.mapper.MesInspectionRevisionAdmissionMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

/**
 * 生产写锁和只读质量门共享冻结事实；不重写检验状态或颁发执行许可。
 */
@Service
@RequiredArgsConstructor
public class MesInspectionRevisionHold {
    private final MesInspectionRevisionAdmissionMapper mapper;

    void installed() {
        MesQualityGateSourceEvidence.scope();
        Integer v;
        try {
            v = mapper.ready();
        } catch (RuntimeException e) {
            throw new CheckedException("请先安装并核验修订准入结构363", e);
        }
        if (!Integer.valueOf(1).equals(v)) throw new CheckedException("修订准入结构或永久守卫未就绪");
    }

    public boolean held(String root) {
        installed();
        MesProductionTaskMutex.key(
                TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(), root);
        return mapper.held(
                TenantContextHolder.getTenantId(),
                FactoryContextHolder.getFactoryCode(),
                root)
                > 0;
    }

    public void requireOpen(String root) {
        installed();
        if (!"read committed".equals(mapper.isolation()))
            throw new CheckedException("生产写锁须使用READ COMMITTED事务，禁止沿旧快照写入");
        if (mapper.held(
                TenantContextHolder.getTenantId(),
                FactoryContextHolder.getFactoryCode(),
                root)
                > 0) throw new CheckedException("生产工单正在进行质检修订核验，请等待完成或正式取消");
    }
}
