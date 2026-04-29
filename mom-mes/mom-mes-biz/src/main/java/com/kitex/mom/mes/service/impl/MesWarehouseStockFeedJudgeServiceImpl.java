package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedJudgeDTO;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;
import com.kitex.mom.mes.mapper.MesWarehouseStockFeedJudgeMapper;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.MesWarehouseStockFeedJudgeService;
import com.kitex.mom.wms.api.feign.RemoteInNoticeService;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import lombok.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;
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
public class MesWarehouseStockFeedJudgeServiceImpl extends ServiceImpl<MesWarehouseStockFeedJudgeMapper, MesWarehouseStockFeedJudgeEntity> implements MesWarehouseStockFeedJudgeService {

    private final RemoteSeqConfigService remoteSeqConfigService;
    private final MesWarehouseLiveStockService liveStockService;
    private final RemoteInNoticeService inNoticeService;

    @NotNull
    private static List<WmsInNoticeDetailEntity> getWmsInNoticeDetailEntities(MesWarehouseStockFeedJudgeEntity judge) {
        List<WmsInNoticeDetailEntity> detailList = new ArrayList<>();
        WmsInNoticeDetailEntity detail = new WmsInNoticeDetailEntity();
        detail.setMatCode(judge.getMatCode());
        detail.setMatName(judge.getMatName());
        detail.setMatBatch(judge.getMatBatch());
        detail.setMatUnit(judge.getMatUnit());
        detail.setWorkshop(judge.getSiteCode());
        detail.setNoticeInNum(judge.getNum().doubleValue());
        detail.setWarehouseCode(judge.getWarehouseCode());
        detail.setWarehouseName(judge.getWarehouseName());
        detail.setSupplierCode(judge.getSupplierCode());
        detail.setSupplierName(judge.getSupplierName());
        detailList.add(detail);
        return detailList;
    }

    /**
     * 提交审批
     *
     * @param submitDTO 退料审批提交参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitReturnMaterial(MesWarehouseFeedDTO submitDTO) {
        MesWarehouseStockFeedJudgeEntity judge = new MesWarehouseStockFeedJudgeEntity();
        BeanUtil.copyProperties(submitDTO, judge);
        judge.setCode(remoteSeqConfigService.create("mes_stock_feed_back_judge").getData());
        judge.setReason(submitDTO.getReason());
        judge.setStatus("0");
        judge.setNum(submitDTO.getBackNum());
        judge.setFactoryCode(submitDTO.getFactoryCode());
        this.save(judge);

        // 更新审批状态为待审批
        liveStockService.update(new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getBillNo, submitDTO.getBillNo())
                .eq(MesWarehouseLiveStockEntity::getMatCode, submitDTO.getMatCode())
                .eq(MesWarehouseLiveStockEntity::getWarehouseCode, submitDTO.getWarehouseCode())
                .eq(MesWarehouseLiveStockEntity::getSiteCode, submitDTO.getSiteCode())
                .eq(MesWarehouseLiveStockEntity::getMatBatch, submitDTO.getMatBatch())
                .set(MesWarehouseLiveStockEntity::getJudgeStatus, "0"));
    }

    /**
     * 审批
     *
     * @param submitDTO
     */
    @Override
    public void judge(MesWarehouseFeedJudgeDTO submitDTO) {
        MesWarehouseStockFeedJudgeEntity judge = this.getById(submitDTO.getId());
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

        List<MesWarehouseLiveStockEntity> updateList = new ArrayList<>();
        // 审核通过时处理
        if (submitDTO.getPass()) {
            // 查询符合条件的库存记录
            List<MesWarehouseLiveStockEntity> stockList = liveStockService.list(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                    .eq(MesWarehouseLiveStockEntity::getBillNo, judge.getBillNo())
                    .eq(MesWarehouseLiveStockEntity::getMatCode, judge.getMatCode())
                    .eq(MesWarehouseLiveStockEntity::getWarehouseCode, judge.getWarehouseCode())
                    .eq(MesWarehouseLiveStockEntity::getSiteCode, judge.getSiteCode())
                    .eq(MesWarehouseLiveStockEntity::getMatBatch, judge.getMatBatch())
                    .orderByAsc(MesWarehouseLiveStockEntity::getCreateTime));

            BigDecimal remainingNum = judge.getNum();
            for (MesWarehouseLiveStockEntity stock : stockList) {
                if (remainingNum.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }

                // 当前记录的数量
                BigDecimal currentStockNum = stock.getNum();

                if (currentStockNum.compareTo(remainingNum) <= 0) {
                    // 如果当前记录数量小于等于需要扣除的数量，则直接删除该记录
                    remainingNum = remainingNum.subtract(currentStockNum);
                    liveStockService.removeById(stock.getId());
                } else {
                    // 如果当前记录数量大于需要扣除的数量，则更新剩余数量
                    stock.setNum(currentStockNum.subtract(remainingNum));
                    remainingNum = BigDecimal.ZERO;
                    stock.setJudgeStatus(null);
                    updateList.add(stock);
                }
            }

            // 更新所有需要更新的记录
            if (!updateList.isEmpty()) {
                liveStockService.updateBatchById(updateList);
            }

            // 删除数量为0的记录
            liveStockService.remove(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                    .eq(MesWarehouseLiveStockEntity::getBillNo, judge.getBillNo())
                    .eq(MesWarehouseLiveStockEntity::getMatCode, judge.getMatCode())
                    .eq(MesWarehouseLiveStockEntity::getWarehouseCode, judge.getWarehouseCode())
                    .eq(MesWarehouseLiveStockEntity::getSiteCode, judge.getSiteCode())
                    .eq(MesWarehouseLiveStockEntity::getMatBatch, judge.getMatBatch())
                    .eq(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO));

            // 将指定数量的物料退回到指定仓库中
            inNoticeService.produceReturn(buildInNotice(judge));
        } else {
            // 审核驳回时处理
            List<MesWarehouseLiveStockEntity> stockList = liveStockService.list(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                    .eq(MesWarehouseLiveStockEntity::getBillNo, judge.getBillNo())
                    .eq(MesWarehouseLiveStockEntity::getMatCode, judge.getMatCode())
                    .eq(MesWarehouseLiveStockEntity::getWarehouseCode, judge.getWarehouseCode())
                    .eq(MesWarehouseLiveStockEntity::getSiteCode, judge.getSiteCode())
                    .eq(MesWarehouseLiveStockEntity::getMatBatch, judge.getMatBatch()));

            // 更新所有记录的审批状态为0
            for (MesWarehouseLiveStockEntity stock : stockList) {
                stock.setJudgeStatus(null);
                updateList.add(stock);
            }

            if (!updateList.isEmpty()) {
                liveStockService.updateBatchById(updateList);
            }
        }
    }

    /**
     * 构建退料通知单
     *
     * @param judge
     * @return
     */
    private WmsInNoticeEntity buildInNotice(MesWarehouseStockFeedJudgeEntity judge) {
        WmsInNoticeEntity notice = new WmsInNoticeEntity();
        notice.setBillNo(judge.getBillNo());
        notice.setNoticeInNum(judge.getNum().doubleValue());
        notice.setSupplierCode(judge.getSupplierCode());
        notice.setWarehouseCode(judge.getWarehouseCode());
        notice.setWarehouseName(judge.getWarehouseName());
        notice.setWorkshop(judge.getSiteCode());
        List<WmsInNoticeDetailEntity> detailList = getWmsInNoticeDetailEntities(judge);
        notice.setDetailList(detailList);
        return notice;
    }
} 