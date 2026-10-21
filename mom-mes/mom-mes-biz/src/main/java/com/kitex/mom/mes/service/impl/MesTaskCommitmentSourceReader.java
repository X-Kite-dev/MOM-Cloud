package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesTaskCommitmentSourceDTO;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesApsBatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 实际组织内精确工单→原直接/APS来源；只取身份，不以结算字段猜报工贡献。 */
@Service
@RequiredArgsConstructor
public class MesTaskCommitmentSourceReader {
    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskMapper taskMapper;
    private final MesApsBatchAllocationMapper allocationMapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesTaskCommitmentSourceDTO.Evidence read(String code) {
        String factory = factoryAccess.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(StrUtil.isNotBlank(code) && code.equals(code.trim()) && code.length() <= 100, "须精确指定真实工单编码");
        var tasks = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, code).last("LIMIT 2"));
        require(tasks.size() == 1, "当前实际组织未找到唯一可读工单");
        var task = tasks.get(0);
        require(StrUtil.isNotBlank(task.getMatCode()) && task.getNoticeNum() != null && task.getNoticeNum().signum() > 0,
                "原物理工单产品/数量不完整，禁止猜来源");
        var allocations = allocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                .eq(MesApsBatchAllocationEntity::getTenantId, tenant).eq(MesApsBatchAllocationEntity::getFactoryCode, factory)
                .eq(MesApsBatchAllocationEntity::getMesTaskId, task.getId())
                .orderByAsc(MesApsBatchAllocationEntity::getAllocationSeq).orderByAsc(MesApsBatchAllocationEntity::getId)
                .last("LIMIT 1001"));
        require(allocations.size() <= 1000, "完整来源超过1000行，不能截断摘要");
        List<MesTaskCommitmentSourceDTO.Source> sources;
        if (allocations.isEmpty()) {
            require(StrUtil.isBlank(task.getApsBatchNo()) && StrUtil.isNotBlank(task.getPlanNo()), "原合批缺来源或直接计划号缺失");
            sources = List.of(new MesTaskCommitmentSourceDTO.Source().setRelationType("DIRECT_PLAN")
                    .setIssueNo(task.getPlanNo()).setAllocatedQuantity(decimal(task.getNoticeNum())));
        } else {
            Set<Long> ids = new HashSet<>();
            Set<String> numbers = new HashSet<>();
            BigDecimal total = BigDecimal.ZERO;
            for (var row : allocations) {
                require(Objects.equals(row.getMesTaskCode(), code) && row.getIssueId() != null && ids.add(row.getIssueId())
                                && StrUtil.isNotBlank(row.getIssueNo()) && numbers.add(row.getIssueNo())
                                && row.getAllocatedQuantity() != null && row.getAllocatedQuantity().signum() > 0,
                        "APS来源身份、唯一性或原分配数量不完整");
                total = total.add(row.getAllocatedQuantity());
            }
            require(total.compareTo(task.getNoticeNum()) == 0, "全部APS来源分配量与物理工单原量不守恒");
            sources = allocations.stream().map(row -> new MesTaskCommitmentSourceDTO.Source()
                    .setRelationType("APS_BATCH_ALLOCATION").setAllocationId(row.getId().toString())
                    .setIssueId(row.getIssueId().toString()).setIssueNo(row.getIssueNo())
                    .setAllocatedQuantity(decimal(row.getAllocatedQuantity()))).toList();
        }
        return new MesTaskCommitmentSourceDTO.Evidence().setTenantId(tenant.toString()).setFactoryCode(factory)
                .setTaskId(task.getId().toString()).setTaskCode(code).setProductCode(task.getMatCode())
                .setPhysicalQuantity(decimal(task.getNoticeNum())).setSources(sources);
    }

    private String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
