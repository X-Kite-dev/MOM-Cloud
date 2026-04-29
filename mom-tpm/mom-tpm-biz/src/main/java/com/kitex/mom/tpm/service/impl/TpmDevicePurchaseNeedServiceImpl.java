package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmDevicePurchaseNeedEntity;
import com.kitex.mom.tpm.entity.TpmDevicePurchaseNeedReviewEntity;
import com.kitex.mom.tpm.enums.PurchaseNeedStateEnum;
import com.kitex.mom.tpm.mapper.TpmDevicePurchaseNeedMapper;
import com.kitex.mom.tpm.mapper.TpmDevicePurchaseNeedReviewMapper;
import com.kitex.mom.tpm.service.TpmDevicePurchaseNeedService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备采购需求表
 *
 * @author kitex
 * @date 2025-03-26 10:04:27
 */
@Service
@AllArgsConstructor
public class TpmDevicePurchaseNeedServiceImpl extends ServiceImpl<TpmDevicePurchaseNeedMapper, TpmDevicePurchaseNeedEntity> implements TpmDevicePurchaseNeedService {

    //需求评审
    private final TpmDevicePurchaseNeedReviewMapper tpmDevicePurchaseNeedReviewMapper;

    @Override
    public Boolean saveNeed(TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeed) {
        tpmDevicePurchaseNeed.setState(PurchaseNeedStateEnum.WAIT_COMMIT.getCode());
        return this.save(tpmDevicePurchaseNeed);
    }

    @Override
    public Boolean updateNeedById(TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeed) {
        tpmDevicePurchaseNeed.setState(PurchaseNeedStateEnum.WAIT_COMMIT.getCode());
        return this.updateById(tpmDevicePurchaseNeed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean commitById(List<Long> ids) {
        LocalDate now = LocalDate.now();
        //查询主表
        List<TpmDevicePurchaseNeedEntity> tpmDevicePurchaseNeedList = this.listByIds(ids);


        //一个采购需求保证只有一个评审单据
        List<String> needCodes = tpmDevicePurchaseNeedList.stream().map(TpmDevicePurchaseNeedEntity::getPurchaseNeedCode).distinct().toList();
        tpmDevicePurchaseNeedReviewMapper.delete(new LambdaQueryWrapper<TpmDevicePurchaseNeedReviewEntity>()
                .in(TpmDevicePurchaseNeedReviewEntity::getPurchaseNeedCode, needCodes));

        List<TpmDevicePurchaseNeedReviewEntity> reviewEntityList = new ArrayList<>();
        for (TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeedEntity : tpmDevicePurchaseNeedList) {
            //修改状态
            tpmDevicePurchaseNeedEntity.setApplicationTime(now);
            tpmDevicePurchaseNeedEntity.setState(PurchaseNeedStateEnum.WAIT_APPROVAL.getCode());
            //创建评审表
            TpmDevicePurchaseNeedReviewEntity review = new TpmDevicePurchaseNeedReviewEntity();
            BeanUtils.copyProperties(tpmDevicePurchaseNeedEntity, review, "id", "reject_reason", "state");
            review.setApplicationTime(now);
            review.setState(PurchaseNeedStateEnum.WAIT_APPROVAL.getCode());
            reviewEntityList.add(review);
        }
        this.updateBatchById(tpmDevicePurchaseNeedList);
        //插入评审表
        tpmDevicePurchaseNeedReviewMapper.insert(reviewEntityList);
        return Boolean.TRUE;
    }
}