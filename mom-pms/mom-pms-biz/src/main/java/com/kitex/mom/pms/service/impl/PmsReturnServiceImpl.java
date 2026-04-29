package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.pms.entity.PmsReturnDetailEntity;
import com.kitex.mom.pms.entity.PmsReturnEntity;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.mapper.PmsPlanMapper;
import com.kitex.mom.pms.mapper.PmsReturnDetailMapper;
import com.kitex.mom.pms.mapper.PmsReturnMapper;
import com.kitex.mom.pms.service.PmsReturnService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采购退货
 *
 * @author kitex
 * @date 2025-02-24 08:38:40
 */
@Service
@RequiredArgsConstructor
public class PmsReturnServiceImpl extends ServiceImpl<PmsReturnMapper, PmsReturnEntity> implements PmsReturnService {

    private final PmsReturnDetailMapper pmsReturnDetailMapper;

    private final PmsOrderDetailMapper pmsOrderDetailMapper;

    private final PmsPlanMapper pmsPlanMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(PmsReturnEntity pmsReturn) {
        baseMapper.insert(pmsReturn);
        for (PmsReturnDetailEntity pmsReturnDetail : pmsReturn.getPmsReturnDetailList()) {
            pmsReturnDetail.setReturnId(pmsReturn.getId());
            pmsReturnDetail.setFactoryCode(pmsReturn.getFactoryCode());
            pmsReturnDetailMapper.insert(pmsReturnDetail);

            // 采购订单到货数量修改
            pmsOrderReturn(pmsReturnDetail);
            // 采购计划到货数量修改
            pmsPlanReturn(pmsReturnDetail);
        }

        return Boolean.TRUE;
    }

    /**
     * 采购订单到货数量修改
     *
     * @param pmsReturnDetail
     */
    private void pmsOrderReturn(PmsReturnDetailEntity pmsReturnDetail) {
        PmsOrderDetailEntity pmsOrderDetailEntity = pmsOrderDetailMapper.selectById(pmsReturnDetail.getOrderDetailId());
        if (pmsOrderDetailEntity.getArrivalNum() < pmsReturnDetail.getReturnNum()) {
            throw new CheckedException("采购退货数量大于采购订单到货数量");
        }
        pmsOrderDetailEntity.setArrivalNum(pmsOrderDetailEntity.getArrivalNum() - pmsReturnDetail.getReturnNum());
        pmsOrderDetailMapper.updateById(pmsOrderDetailEntity);
    }

    /**
     * 采购计划到货数量修改
     *
     * @param pmsReturnDetail
     */
    private void pmsPlanReturn(PmsReturnDetailEntity pmsReturnDetail) {
        if (StrUtil.isNotBlank(pmsReturnDetail.getPlanNo())) {
            PmsPlanEntity pmsPlan = pmsPlanMapper.selectOne(new LambdaQueryWrapper<PmsPlanEntity>().eq(PmsPlanEntity::getPmsPlanNo, pmsReturnDetail.getPlanNo()));
            if (pmsPlan.getPlanArrivalNum() < pmsReturnDetail.getReturnNum()) {
                throw new CheckedException("采购退货数量大于采购计划到货数量");
            }
            pmsPlan.setPlanArrivalNum(pmsPlan.getPlanArrivalNum() - pmsReturnDetail.getReturnNum());
            pmsPlanMapper.updateById(pmsPlan);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(PmsReturnEntity pmsReturn) {
        baseMapper.updateById(pmsReturn);

        pmsReturnDetailMapper.delete(Wrappers.<PmsReturnDetailEntity>lambdaQuery().eq(PmsReturnDetailEntity::getReturnId,
                pmsReturn.getId()));
        for (PmsReturnDetailEntity pmsReturnDetail : pmsReturn.getPmsReturnDetailList()) {
            pmsReturnDetail.setId(null);
            pmsReturnDetail.setReturnId(pmsReturn.getId());
            pmsReturnDetailMapper.insert(pmsReturnDetail);
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        pmsReturnDetailMapper.delete(Wrappers.<PmsReturnDetailEntity>lambdaQuery().in(PmsReturnDetailEntity::getReturnId, ids));
        return Boolean.TRUE;
    }
}
