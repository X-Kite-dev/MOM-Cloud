package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.pms.entity.PmsInvoiceDetailEntity;
import com.kitex.mom.pms.entity.PmsInvoiceEntity;
import com.kitex.mom.pms.entity.PmsInvoiceReconciliationEntity;
import com.kitex.mom.pms.mapper.PmsInvoiceDetailMapper;
import com.kitex.mom.pms.mapper.PmsInvoiceMapper;
import com.kitex.mom.pms.mapper.PmsInvoiceReconciliationMapper;
import com.kitex.mom.pms.service.PmsInvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 采购发票
 *
 * @author kitex
 * @date 2025-04-07 17:53:31
 */
@Service
@RequiredArgsConstructor
public class PmsInvoiceServiceImpl extends ServiceImpl<PmsInvoiceMapper, PmsInvoiceEntity> implements PmsInvoiceService {

    private final PmsInvoiceReconciliationMapper pmsInvoiceReconciliationMapper;

    private final PmsInvoiceDetailMapper pmsInvoiceDetailMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(PmsInvoiceEntity pmsInvoice) {
        baseMapper.insert(pmsInvoice);

        // 对账单信息保存逻辑
        for (PmsInvoiceReconciliationEntity pmsInvoiceReconciliation : pmsInvoice.getPmsReconciliationDetailList()) {
            pmsInvoiceReconciliation.setInvoiceId(pmsInvoice.getId());
            pmsInvoiceReconciliationMapper.insert(pmsInvoiceReconciliation);
        }
        // 发票明细保存逻辑
        for (PmsInvoiceDetailEntity pmsInvoiceDetail : pmsInvoice.getPmsInvoiceDetailList()) {
            pmsInvoiceDetail.setInvoiceId(pmsInvoice.getId());
            pmsInvoiceDetailMapper.insert(pmsInvoiceDetail);
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(PmsInvoiceEntity pmsInvoice) {
        baseMapper.updateById(pmsInvoice);

        // 对账单信息保存逻辑
        for (PmsInvoiceReconciliationEntity pmsInvoiceReconciliation : pmsInvoice.getPmsReconciliationDetailList()) {
            if (Objects.isNull(pmsInvoiceReconciliation.getId())) {
                pmsInvoiceReconciliation.setInvoiceId(pmsInvoice.getId());
                pmsInvoiceReconciliationMapper.insert(pmsInvoiceReconciliation);
            } else {
                pmsInvoiceReconciliationMapper.updateById(pmsInvoiceReconciliation);
            }
        }
        // 发票明细保存逻辑
        for (PmsInvoiceDetailEntity pmsInvoiceDetail : pmsInvoice.getPmsInvoiceDetailList()) {
            if (Objects.isNull(pmsInvoiceDetail.getId())) {
                pmsInvoiceDetail.setInvoiceId(pmsInvoice.getId());
                pmsInvoiceDetailMapper.insert(pmsInvoiceDetail);
            } else {
                pmsInvoiceDetailMapper.updateById(pmsInvoiceDetail);
            }
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        ArrayList<Long> invoiceIds = CollUtil.toList(ids);
        this.removeBatchByIds(invoiceIds);
        pmsInvoiceReconciliationMapper.delete(Wrappers.<PmsInvoiceReconciliationEntity>lambdaQuery().in(PmsInvoiceReconciliationEntity::getInvoiceId, invoiceIds));
        pmsInvoiceDetailMapper.delete(Wrappers.<PmsInvoiceDetailEntity>lambdaQuery().in(PmsInvoiceDetailEntity::getInvoiceId, invoiceIds));
        return Boolean.TRUE;
    }

    /**
     * 根据发票id查询发票关联的业务对账单
     *
     * @param ids 发票ids
     * @return R
     */
    @Override
    public List<PmsInvoiceReconciliationEntity> getReconciliationByInvoiceId(Long[] ids) {
        return pmsInvoiceReconciliationMapper.selectList(new LambdaQueryWrapper<PmsInvoiceReconciliationEntity>()
                .in(PmsInvoiceReconciliationEntity::getInvoiceId, CollUtil.toList(ids)));
    }
}
