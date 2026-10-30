package com.kitex.mom.mes.service.impl;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.mapper.MesPhysicalTransformSourceLockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 转换来源独占域：普通写者共享并行，转换观察/后续claim短事务独占实际工厂。
 * 全部锁均try/NOWAIT；旧写者先取行锁时也不能形成反向无限等待。
 */
@Component
@RequiredArgsConstructor
public class MesPhysicalTransformSourceScopeLock {
    public static final String VERSION = "MES_PHYSICAL_SOURCE_SCOPE_V1";
    private final MesPhysicalTransformSourceLockMapper mapper;
    private final MesPhysicalTransformCodec codec;
    private final MesActualFactoryAccessService access;

    /**
     * 必须在同一个真实事务内调用；返回时没有持久实物占用或请求许可。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void acquire(Long tenant, String factory) {
        require(tenant != null && tenant > 0 && Objects.equals(tenant, TenantContextHolder.getTenantId())
                && Objects.equals(factory, access.require("read")), "来源写域只能保护当前真实租户工厂");
        codec.code(factory, "实际工厂", 100);
        require(Boolean.TRUE.equals(mapper.scopeReady()),
                "MES全部实际写表的来源守卫未齐备或被禁用，请先执行V2第250节；不能以局部服务锁证明来源稳定");
        require(Boolean.TRUE.equals(mapper.tryScope(tenant, factory)),
                "实际工厂仍有库存/质量/派工写事务，请保留原号重新读取来源；未取得转换占用");
    }

    /**
     * 已持独占源域后按真实工单编码稳定排序调用；与原派工/报工父锁完全一致。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void task(Long tenant, String factory, String code) {
        require(tenant != null && Objects.equals(tenant, TenantContextHolder.getTenantId())
                && Objects.equals(factory, access.require("read")), "原工单锁只取当前真实租户工厂");
        codec.code(code, "实际原工单", 100);
        require(Boolean.TRUE.equals(mapper.tryTask(codec.snapshot(List.of("MES_NODE_TASK_LOCK_V1", tenant.toString(), factory, code)))),
                "原工单仍有执行事务，来源核验立即回滚；不持库存行锁等待另一工单");
    }
}
