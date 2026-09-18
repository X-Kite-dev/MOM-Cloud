package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
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
        detail.setReservationNo(judge.getReservationNo());
        detail.setRequirementMatCode(judge.getRequirementMatCode());
        detail.setRequirementMatName(judge.getRequirementMatName());
        detail.setSubstituteFlag(judge.getSubstituteFlag());
        detail.setSubstituteRatio(judge.getSubstituteRatio());
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
        if (submitDTO == null || submitDTO.getBackNum() == null
                || submitDTO.getBackNum().compareTo(BigDecimal.ZERO) <= 0
                || submitDTO.getNum() == null || submitDTO.getBackNum().compareTo(submitDTO.getNum()) > 0) {
            throw new CheckedException("退料数量必须大于0且不得超过当前可退数量");
        }
        MesWarehouseStockFeedJudgeEntity judge = new MesWarehouseStockFeedJudgeEntity();
        BeanUtil.copyProperties(submitDTO, judge);
        judge.setCode(remoteSeqConfigService.create("mes_stock_feed_back_judge").getData());
        judge.setReason(submitDTO.getReason());
        judge.setStatus("0");
        judge.setNum(submitDTO.getBackNum());
        judge.setFactoryCode(submitDTO.getFactoryCode());
        this.save(judge);

        // 更新审批状态为待审批
        boolean locked = liveStockService.update(new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                .isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
                .eq(MesWarehouseLiveStockEntity::getBillNo, submitDTO.getBillNo())
                .eq(MesWarehouseLiveStockEntity::getMatCode, submitDTO.getMatCode())
                .eq(submitDTO.getReservationNo() != null, MesWarehouseLiveStockEntity::getReservationNo, submitDTO.getReservationNo())
                .eq(submitDTO.getRequirementMatCode() != null, MesWarehouseLiveStockEntity::getRequirementMatCode, submitDTO.getRequirementMatCode())
                .eq(MesWarehouseLiveStockEntity::getWarehouseCode, submitDTO.getWarehouseCode())
                .eq(MesWarehouseLiveStockEntity::getSiteCode, submitDTO.getSiteCode())
                .eq(MesWarehouseLiveStockEntity::getMatBatch, submitDTO.getMatBatch())
                .and(status -> status.isNull(MesWarehouseLiveStockEntity::getJudgeStatus)
                        .or().eq(MesWarehouseLiveStockEntity::getJudgeStatus, ""))
                .set(MesWarehouseLiveStockEntity::getJudgeStatus, "0"));
        if (!locked) {
            throw new CheckedException("库存已进入其他退料审批或已发生变化，请刷新后重试");
        }
    }

    /**
     * 审批
     *
     * @param submitDTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void judge(MesWarehouseFeedJudgeDTO submitDTO) {
        MesWarehouseStockFeedJudgeEntity judge = this.getById(submitDTO.getId());
        MomUser user = SecurityUtils.getUser();
        if (Objects.isNull(judge)) {
            throw new RuntimeException("未找到审批申请单");
        }
        if (!"0".equals(judge.getStatus())) {
            throw new CheckedException("退料申请已审批，不能重复处理");
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
        boolean stateChanged = this.update(new LambdaUpdateWrapper<MesWarehouseStockFeedJudgeEntity>()
                .eq(MesWarehouseStockFeedJudgeEntity::getId, judge.getId())
                .eq(MesWarehouseStockFeedJudgeEntity::getStatus, "0")
                .set(MesWarehouseStockFeedJudgeEntity::getStatus, judge.getStatus())
                .set(MesWarehouseStockFeedJudgeEntity::getOpinion, judge.getOpinion())
                .set(MesWarehouseStockFeedJudgeEntity::getJudgePersonCode, judge.getJudgePersonCode())
                .set(MesWarehouseStockFeedJudgeEntity::getJudgePersonName, judge.getJudgePersonName())
                .set(MesWarehouseStockFeedJudgeEntity::getJudgeTime, judge.getJudgeTime()));
        if (!stateChanged) {
            throw new CheckedException("退料申请已被其他审批人处理，请刷新后查看");
        }

        List<MesWarehouseLiveStockEntity> updateList = new ArrayList<>();
        // 审核通过时处理
        if (submitDTO.getPass()) {
            // 查询符合条件的库存记录
            List<MesWarehouseLiveStockEntity> stockList = liveStockService.list(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
                    .eq(MesWarehouseLiveStockEntity::getBillNo, judge.getBillNo())
                    .eq(MesWarehouseLiveStockEntity::getMatCode, judge.getMatCode())
                    .eq(judge.getReservationNo() != null, MesWarehouseLiveStockEntity::getReservationNo, judge.getReservationNo())
                    .eq(judge.getRequirementMatCode() != null, MesWarehouseLiveStockEntity::getRequirementMatCode, judge.getRequirementMatCode())
                    .eq(MesWarehouseLiveStockEntity::getWarehouseCode, judge.getWarehouseCode())
                    .eq(MesWarehouseLiveStockEntity::getSiteCode, judge.getSiteCode())
                    .eq(MesWarehouseLiveStockEntity::getMatBatch, judge.getMatBatch())
                    .orderByAsc(MesWarehouseLiveStockEntity::getCreateTime)
                    .last("FOR UPDATE"));

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

            if (remainingNum.compareTo(BigDecimal.ZERO) > 0) {
                throw new CheckedException("线边库存不足，尚缺退料数量：" + remainingNum.stripTrailingZeros().toPlainString());
            }

            liveStockService.update(new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                .isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
                    .eq(MesWarehouseLiveStockEntity::getBillNo, judge.getBillNo())
                    .eq(MesWarehouseLiveStockEntity::getMatCode, judge.getMatCode())
                    .eq(judge.getReservationNo() != null, MesWarehouseLiveStockEntity::getReservationNo, judge.getReservationNo())
                    .eq(judge.getRequirementMatCode() != null, MesWarehouseLiveStockEntity::getRequirementMatCode, judge.getRequirementMatCode())
                    .eq(MesWarehouseLiveStockEntity::getWarehouseCode, judge.getWarehouseCode())
                    .eq(MesWarehouseLiveStockEntity::getSiteCode, judge.getSiteCode())
                    .eq(MesWarehouseLiveStockEntity::getMatBatch, judge.getMatBatch())
                    .set(MesWarehouseLiveStockEntity::getJudgeStatus, null));

            // 删除数量为0的记录
            liveStockService.remove(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
                    .eq(MesWarehouseLiveStockEntity::getBillNo, judge.getBillNo())
                    .eq(MesWarehouseLiveStockEntity::getMatCode, judge.getMatCode())
                    .eq(judge.getReservationNo() != null, MesWarehouseLiveStockEntity::getReservationNo, judge.getReservationNo())
                    .eq(judge.getRequirementMatCode() != null, MesWarehouseLiveStockEntity::getRequirementMatCode, judge.getRequirementMatCode())
                    .eq(MesWarehouseLiveStockEntity::getWarehouseCode, judge.getWarehouseCode())
                    .eq(MesWarehouseLiveStockEntity::getSiteCode, judge.getSiteCode())
                    .eq(MesWarehouseLiveStockEntity::getMatBatch, judge.getMatBatch())
                    .eq(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO));

            // 将指定数量的物料退回到指定仓库中
            R<Boolean> result = inNoticeService.produceReturn(buildInNotice(judge));
            if (!result.isOk()) {
                throw new CheckedException("生成WMS生产退料入库通知单失败：" + result.getMsg());
            }
        } else {
            // 审核驳回时处理
            List<MesWarehouseLiveStockEntity> stockList = liveStockService.list(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
                    .eq(MesWarehouseLiveStockEntity::getBillNo, judge.getBillNo())
                    .eq(MesWarehouseLiveStockEntity::getMatCode, judge.getMatCode())
                    .eq(judge.getReservationNo() != null, MesWarehouseLiveStockEntity::getReservationNo, judge.getReservationNo())
                    .eq(judge.getRequirementMatCode() != null, MesWarehouseLiveStockEntity::getRequirementMatCode, judge.getRequirementMatCode())
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
        notice.setFactoryCode(judge.getFactoryCode());
        List<WmsInNoticeDetailEntity> detailList = getWmsInNoticeDetailEntities(judge);
        notice.setDetailList(detailList);
        return notice;
    }
}
