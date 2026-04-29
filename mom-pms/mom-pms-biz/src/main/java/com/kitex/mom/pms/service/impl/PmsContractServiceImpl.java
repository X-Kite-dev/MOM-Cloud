package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.pms.entity.PmsContractDetailEntity;
import com.kitex.mom.pms.entity.PmsContractEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.enums.PmsContractStatusEnum;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.mapper.PmsContractDetailMapper;
import com.kitex.mom.pms.mapper.PmsContractMapper;
import com.kitex.mom.pms.mapper.PmsOrderMapper;
import com.kitex.mom.pms.service.PmsContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 合同管理
 *
 * @author kitex
 * @date 2025-04-02 16:10:18
 */
@Service
@RequiredArgsConstructor
public class PmsContractServiceImpl extends ServiceImpl<PmsContractMapper, PmsContractEntity> implements PmsContractService {

    private final PmsContractDetailMapper pmsContractDetailMapper;

    private final PmsOrderMapper pmsOrderMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(PmsContractEntity pmsContract) {
        pmsContract.setStatus(PmsContractStatusEnum.ADD.getCode());
        baseMapper.insert(pmsContract);
        for (PmsContractDetailEntity pmsContractDetail : pmsContract.getPmsContractDetailList()) {
            pmsContractDetail.setContractId(pmsContract.getId());
            pmsContractDetailMapper.insert(pmsContractDetail);
        }
        // 修改采购订单的状态为签订合同
        pmsOrderMapper.update(new LambdaUpdateWrapper<PmsOrderEntity>().eq(PmsOrderEntity::getPmsOrderNo, pmsContract.getPmsOrderNo())
                .set(PmsOrderEntity::getContractStatus, Boolean.TRUE));
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(PmsContractEntity pmsContract) {
        // 校验采购订单状态
        if (PmsContractStatusEnum.SIGN.getCode().equals(pmsContract.getStatus()) || StrUtil.isBlank(pmsContract.getStatus())) {
            pmsContract.setStatus(checkOrderStatus(pmsContract.getPmsOrderNo()));
        }

        baseMapper.updateById(pmsContract);
        if (CollUtil.isNotEmpty(pmsContract.getPmsContractDetailList())) {
            for (PmsContractDetailEntity pmsContractDetail : pmsContract.getPmsContractDetailList()) {
                if (Objects.isNull(pmsContractDetail.getId())) {
                    pmsContractDetail.setContractId(pmsContract.getId());
                    pmsContractDetailMapper.insert(pmsContractDetail);
                } else {
                    pmsContractDetailMapper.updateById(pmsContractDetail);
                }
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 校验采购订单状态
     *
     * @param pmsOrderNo 采购订单号
     */
    private String checkOrderStatus(String pmsOrderNo) {
        PmsOrderEntity pmsOrderEntity = pmsOrderMapper.selectOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getPmsOrderNo, pmsOrderNo));
        if (PmsOrderStatusEnum.RECEIVING.getCode().equals(pmsOrderEntity.getStatus()) ||
                PmsOrderStatusEnum.PART_RECEIVE.getCode().equals(pmsOrderEntity.getStatus())) {
            return PmsContractStatusEnum.EXECUTION.getCode();
        }
        return PmsContractStatusEnum.SIGN.getCode();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        pmsContractDetailMapper.delete(Wrappers.<PmsContractDetailEntity>lambdaQuery().in(PmsContractDetailEntity::getContractId, ids));
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeChild(Long[] ids) {
        pmsContractDetailMapper.deleteBatchIds(CollUtil.toList(ids));
        return Boolean.TRUE;
    }

    /**
     * 通过采购订单号改变合同状态
     *
     * @param pmsOrderNo 采购订单号
     * @param status     状态
     * @return R
     */
    @Override
    public Boolean statusUpdate(String pmsOrderNo, String status) {
        PmsContractEntity pmsContract = this.getOne(new LambdaQueryWrapper<PmsContractEntity>().eq(PmsContractEntity::getPmsOrderNo, pmsOrderNo), false);
        if (Objects.isNull(pmsContract)) {
            return Boolean.TRUE;
        }
        pmsContract.setStatus(status);
        return this.updateById(pmsContract);
    }
}
