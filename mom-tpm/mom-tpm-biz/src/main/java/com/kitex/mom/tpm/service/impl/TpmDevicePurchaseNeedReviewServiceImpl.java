package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.tpm.entity.TpmDevicePurchaseNeedEntity;
import com.kitex.mom.tpm.entity.TpmDevicePurchaseNeedReviewEntity;
import com.kitex.mom.tpm.enums.PurchaseNeedStateEnum;
import com.kitex.mom.tpm.mapper.TpmDevicePurchaseNeedReviewMapper;
import com.kitex.mom.tpm.service.TpmDevicePurchaseNeedReviewService;
import com.kitex.mom.tpm.service.TpmDevicePurchaseNeedService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 设备采购需求审批表
 *
 * @author kitex
 * @date 2025-03-26 10:10:02
 */
@Service
@AllArgsConstructor
public class TpmDevicePurchaseNeedReviewServiceImpl extends ServiceImpl<TpmDevicePurchaseNeedReviewMapper, TpmDevicePurchaseNeedReviewEntity> implements TpmDevicePurchaseNeedReviewService {

    private final TpmDevicePurchaseNeedService tpmDevicePurchaseNeedService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean approval(Long id, String type, String rejectReason) {
        MomUser user = SecurityUtils.getUser();
        LocalDateTime now = LocalDateTime.now();
        //查询主表
        TpmDevicePurchaseNeedReviewEntity byId = this.getById(id);
        byId.setReviewCode(user.getUsername());
        byId.setReviewName(user.getName());
        byId.setReviewTime(now);
        //通过逻辑 1. 更新主表状态 2. 更新需求表状态
        if (YesOrNoStateEnum.YES.getCode().equals(type)) {
            byId.setState(PurchaseNeedStateEnum.PASS.getCode());
            this.updateById(byId);
            tpmDevicePurchaseNeedService.update(new UpdateWrapper<TpmDevicePurchaseNeedEntity>()
                    .lambda().eq(TpmDevicePurchaseNeedEntity::getPurchaseNeedCode, byId.getPurchaseNeedCode())
                    .set(TpmDevicePurchaseNeedEntity::getState, PurchaseNeedStateEnum.PASS.getCode()));
        }
        // 不通过逻辑 1. 更新主表状态不通过，以及驳回原因 2. 更新需求表状态,以及驳回原因
        if (YesOrNoStateEnum.NO.getCode().equals(type)) {
            byId.setState(PurchaseNeedStateEnum.REJECT.getCode());
            byId.setRejectReason(rejectReason);
            this.updateById(byId);
            tpmDevicePurchaseNeedService.update(new UpdateWrapper<TpmDevicePurchaseNeedEntity>()
                    .lambda().eq(TpmDevicePurchaseNeedEntity::getPurchaseNeedCode, byId.getPurchaseNeedCode())
                    .set(TpmDevicePurchaseNeedEntity::getState, PurchaseNeedStateEnum.REJECT.getCode())
                    .set(TpmDevicePurchaseNeedEntity::getRejectReason, rejectReason));
        }
        return Boolean.TRUE;
    }
}