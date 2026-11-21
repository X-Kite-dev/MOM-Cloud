package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import com.kitex.mom.mes.mapper.MesWrapRecordMapper;
import com.kitex.mom.mes.service.MesWrapRecordService;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 包装装箱扫码记录
 *
 * @author kitex
 * @since 2025-04-01
 */
@Service
@RequiredArgsConstructor
public class MesWrapRecordServiceImpl extends ServiceImpl<MesWrapRecordMapper, MesWrapRecordEntity> implements MesWrapRecordService {

    private final MesTaskReportMapper reports;

    /** {@inheritDoc} */
    @Override
    public void loadRepackModes(List<MesWrapRecordEntity> records, String factory) {
        List<Long> ids = records.stream().map(MesWrapRecordEntity::getReportId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            records.forEach(record -> record.setSourceReportMode(null));
            return;
        }
        Long tenant = TenantContextHolder.getTenantId();
        var byId = reports.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .select(MesTaskReportEntity::getId, MesTaskReportEntity::getReportMode, MesTaskReportEntity::getTaskCode,
                        MesTaskReportEntity::getArrangeCode, MesTaskReportEntity::getProduceTaskCode,
                        MesTaskReportEntity::getTransferCode)
                .eq(MesTaskReportEntity::getTenantId, tenant)
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .in(MesTaskReportEntity::getId, ids))
                .stream().collect(Collectors.toMap(MesTaskReportEntity::getId, report -> report));
        for (MesWrapRecordEntity record : records) {
            MesTaskReportEntity report = byId.get(record.getReportId());
            boolean matches = report != null && Objects.equals(tenant, record.getTenantId())
                    && Objects.equals(factory, record.getFactoryCode())
                    && Objects.equals(record.getTaskCode(), report.getTaskCode())
                    && Objects.equals(record.getProcedureTaskCode(), report.getProduceTaskCode())
                    && Objects.equals(record.getArrangeCode(), report.getArrangeCode())
                    && Objects.equals(record.getTransferCode(), report.getTransferCode());
            record.setSourceReportMode(matches ? report.getReportMode() : null);
        }
    }

    /**
     * 删除
     *
     * @param id
     */
    @Override
    public void removeData(Long id) {
        throw new CheckedException("装箱明细不能直接删除，请通过报工冲销恢复库存、物料和生产数量");
    }
}
