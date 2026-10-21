package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesIdentifierBindingOperationEntity;
import com.kitex.mom.mes.mapper.MesIdentifierBindingOperationMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingOperationService;
import com.kitex.mom.mes.service.MesIdentifierBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** 精确实例的完整不可变操作链查询，不按外码将多次占用拼成一条假历史。 */
@Service
@RequiredArgsConstructor
public class MesIdentifierBindingOperationServiceImpl
        extends ServiceImpl<MesIdentifierBindingOperationMapper, MesIdentifierBindingOperationEntity>
        implements MesIdentifierBindingOperationService {

    private final MesActualFactoryAccessService factoryAccess;
    private final MesIdentifierBindingService bindings;

    @Override
    @Transactional(readOnly = true)
    public List<MesIdentifierBindingOperationEntity> history(Long bindingId) {
        String factory = factoryAccess.require("read");
        bindings.evidence(bindingId);
        List<MesIdentifierBindingOperationEntity> rows = baseMapper.selectList(new LambdaQueryWrapper<MesIdentifierBindingOperationEntity>()
                .eq(MesIdentifierBindingOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesIdentifierBindingOperationEntity::getFactoryCode, factory)
                .eq(MesIdentifierBindingOperationEntity::getBindingId, bindingId)
                .orderByAsc(MesIdentifierBindingOperationEntity::getId).last("LIMIT 5001"));
        if (rows.size() > 5000) throw new CheckedException("操作链超过完整核对上限，禁止截断后宣称完整");
        return rows;
    }

    @Override
    public MesIdentifierBindingOperationMapper getBaseMapper() { throw new CheckedException("节点操作永久账禁止通用CRUD"); }

    @Override
    public boolean saveBatch(Collection<MesIdentifierBindingOperationEntity> rows, int size) { throw new CheckedException("节点操作禁止独立批量追加"); }

    @Override
    public boolean saveOrUpdateBatch(Collection<MesIdentifierBindingOperationEntity> rows, int size) { throw new CheckedException("节点操作永久账禁止批量覆盖"); }

    @Override
    public boolean updateBatchById(Collection<MesIdentifierBindingOperationEntity> rows, int size) { throw new CheckedException("节点操作永久账禁止批量更新"); }
}
