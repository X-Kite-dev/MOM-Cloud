package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesTaskInWmsReceiptDetailEntity;
import com.kitex.mom.mes.entity.MesTaskInWmsReceiptReverseDetailEntity;
import com.kitex.mom.mes.mapper.MesTaskInWmsReceiptDetailMapper;
import com.kitex.mom.mes.mapper.MesTaskInWmsReceiptReverseDetailMapper;
import com.kitex.mom.mes.service.MesInboundAllocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 成品入库来源按“原分配减不可变红冲明细”提供统一净额口径。
 */
@Service
@RequiredArgsConstructor
public class MesInboundAllocationServiceImpl
        extends ServiceImpl<MesTaskInWmsReceiptDetailMapper, MesTaskInWmsReceiptDetailEntity>
        implements MesInboundAllocationService {

    private final MesTaskInWmsReceiptReverseDetailMapper reverseDetailMapper;

    @Override
    public List<MesTaskInWmsReceiptDetailEntity> activeByTask(String taskCode, String factoryCode, boolean lock) {
        List<MesTaskInWmsReceiptDetailEntity> original = list(new LambdaQueryWrapper<MesTaskInWmsReceiptDetailEntity>()
                .eq(MesTaskInWmsReceiptDetailEntity::getTaskCode, taskCode)
                .eq(MesTaskInWmsReceiptDetailEntity::getFactoryCode, factoryCode)
                .orderByAsc(MesTaskInWmsReceiptDetailEntity::getId)
                .last((lock ? "LIMIT 10001 FOR UPDATE" : "LIMIT 10001")));
        if (original.size() > 10000) {
            throw new CheckedException("工单成品入库来源明细超过10000条，禁止截断净额核验");
        }
        if (original.isEmpty()) return original;
        List<MesTaskInWmsReceiptReverseDetailEntity> reversed = reverseDetailMapper.selectList(
                new LambdaQueryWrapper<MesTaskInWmsReceiptReverseDetailEntity>()
                        .in(MesTaskInWmsReceiptReverseDetailEntity::getReceiptDetailId,
                                original.stream().map(MesTaskInWmsReceiptDetailEntity::getId).toList())
                        .orderByAsc(MesTaskInWmsReceiptReverseDetailEntity::getId)
                        .last(lock ? "FOR UPDATE" : ""));
        Map<Long, BigDecimal> reversedByDetail = new HashMap<>();
        for (MesTaskInWmsReceiptReverseDetailEntity item : reversed) {
            if (item.getReversedQuantity() == null || item.getReversedQuantity().signum() <= 0) {
                throw new CheckedException("成品入库来源存在无效红冲数量，请先完成业务账修复");
            }
            reversedByDetail.merge(item.getReceiptDetailId(), item.getReversedQuantity(), BigDecimal::add);
        }
        return original.stream().filter(item -> {
            BigDecimal allocated = item.getAllocatedQuantity() == null
                    ? BigDecimal.ZERO : item.getAllocatedQuantity();
            BigDecimal remaining = allocated.subtract(
                    reversedByDetail.getOrDefault(item.getId(), BigDecimal.ZERO));
            if (remaining.signum() < 0) {
                throw new CheckedException("成品入库来源累计红冲超过原分配数量，请先完成业务账修复");
            }
            item.setAllocatedQuantity(remaining);
            return remaining.signum() > 0;
        }).toList();
    }
}
