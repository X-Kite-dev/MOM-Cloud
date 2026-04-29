package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.dto.MesWarehouseFeedJudgeDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedLeftDTO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedJudgeEntity;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.mapper.MesWarehouseFeedJudgeMapper;
import com.kitex.mom.mes.service.MesWarehouseFeedJudgeService;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 线边库余料退料审批
 *
 * @author kitex
 * @since 2025-04-08
 */
@Service
@AllArgsConstructor
public class MesWarehouseFeedJudgeServiceImpl extends ServiceImpl<MesWarehouseFeedJudgeMapper, MesWarehouseFeedJudgeEntity> implements MesWarehouseFeedJudgeService {

    private final RemoteSeqConfigService remoteSeqConfigService;
    private final MesWarehouseFeedService mesWarehouseFeedService;
    private final MesWarehouseLiveStockService liveStockService;

    /**
     * 提交审批
     *
     * @param submitDTO 退料审批提交参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitReturnMaterial(MesWarehouseFeedLeftDTO submitDTO) {
        MesWarehouseFeedJudgeEntity judge = new MesWarehouseFeedJudgeEntity();
        BeanUtil.copyProperties(submitDTO, judge);
        judge.setCode(remoteSeqConfigService.create("mes_feed_back_judge").getData());
        judge.setReason(submitDTO.getReason());
        judge.setStatus("0");
        judge.setNum(submitDTO.getBackNum());
        judge.setFactoryCode(submitDTO.getFactoryCode());
        this.save(judge);
        mesWarehouseFeedService.update(new LambdaUpdateWrapper<MesWarehouseFeedEntity>()
                .in(MesWarehouseFeedEntity::getTaskCode, judge.getTaskCode())
                .eq(MesWarehouseFeedEntity::getMatCode, judge.getMatCode())
                .set(MesWarehouseFeedEntity::getIsLock, Boolean.TRUE)
                .set(MesWarehouseFeedEntity::getStatus, "0"));
    }

    /**
     * 审批
     *
     * @param submitDTO
     */
    @Override
    public void judge(MesWarehouseFeedJudgeDTO submitDTO) {
        MesWarehouseFeedJudgeEntity judge = this.getById(submitDTO.getId());
        MomUser user = SecurityUtils.getUser();
        if (Objects.isNull(judge)) {
            throw new RuntimeException("未找到审批申请单");
        }
        if (submitDTO.getPass()) {
            judge.setStatus("1");
        } else {
            judge.setStatus("2");
        }
        judge.setOpinion(submitDTO.getOpinion());
        judge.setJudgePersonCode(user.getUsername());
        judge.setJudgePersonName(user.getName());
        judge.setJudgeTime(LocalDateTime.now());
        this.updateById(judge);

        List<MesWarehouseFeedEntity> updateList = new ArrayList<>();

        // 审核通过时处理
        if (submitDTO.getPass()) {
            // 更新状态
            List<MesWarehouseFeedEntity> feedList = mesWarehouseFeedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                    .eq(MesWarehouseFeedEntity::getTaskCode, judge.getTaskCode())
                    .eq(MesWarehouseFeedEntity::getMatCode, judge.getMatCode())
                    .orderByAsc(MesWarehouseFeedEntity::getFeedTime)); // 按投料时间升序排序，实现先入先出
            feedList.forEach(item -> item.setStatus(null));
            mesWarehouseFeedService.updateBatchById(feedList);

            BigDecimal remainingNum = judge.getNum(); // 需要消耗的数量

            for (MesWarehouseFeedEntity feed : feedList) {
                if (remainingNum.compareTo(BigDecimal.ZERO) <= 0) {
                    break; // 如果已经消耗完所需数量，退出循环
                }

                // 计算当前记录可消耗的数量
                BigDecimal availableNum = feed.getFeedNum().subtract(
                        feed.getUsedNum() != null ? feed.getUsedNum() : BigDecimal.ZERO
                );

                if (availableNum.compareTo(BigDecimal.ZERO) <= 0) {
                    continue; // 如果当前记录已全部消耗，跳过
                }

                // 计算本次要消耗的数量
                BigDecimal consumeNum = availableNum.min(remainingNum);

                // 更新消耗数量
                feed.setUsedNum(
                        (feed.getUsedNum() != null ? feed.getUsedNum() : BigDecimal.ZERO)
                                .add(consumeNum)
                );
                feed.setStatus(null);
                feed.setIsLock(Boolean.FALSE);
                updateList.add(feed);

                // 更新剩余需要消耗的数量
                remainingNum = remainingNum.subtract(consumeNum);
            }

            // 批量更新消耗数量
            if (!updateList.isEmpty()) {
                mesWarehouseFeedService.updateBatchById(updateList);
            }

            // 如果还有剩余数量未消耗完，抛出异常
            if (remainingNum.compareTo(BigDecimal.ZERO) > 0) {
                throw new RuntimeException("投料数量不足，无法完成退料");
            }

            MesWarehouseLiveStockInoutDTO inoutDTO = new MesWarehouseLiveStockInoutDTO();
            inoutDTO.setRouteCode(judge.getRouteCode());
            inoutDTO.setRouteName(judge.getRouteName());
            inoutDTO.setMatCode(judge.getMatCode());
            inoutDTO.setMatName(judge.getMatName());
            inoutDTO.setMatSpec(judge.getMatSpec());
            inoutDTO.setMatUnit(judge.getMatUnit());
            inoutDTO.setMatBatch(judge.getBatchNo());
            inoutDTO.setBillNo(judge.getCode());
            inoutDTO.setNum(judge.getNum());
            inoutDTO.setBizType(WarehouseInBizTypeEnum.SURPLUS.getCode());
            liveStockService.inStock(inoutDTO);
        } else {
            List<MesWarehouseFeedEntity> feedList = mesWarehouseFeedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                    .eq(MesWarehouseFeedEntity::getTaskCode, judge.getTaskCode()));
            feedList.forEach(item -> {
                item.setStatus(null);
                item.setIsLock(Boolean.FALSE);
            });
            mesWarehouseFeedService.updateBatchById(feedList);
        }
    }
} 