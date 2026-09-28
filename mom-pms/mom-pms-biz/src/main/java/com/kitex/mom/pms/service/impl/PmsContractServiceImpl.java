package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.entity.PmsContractDetailEntity;
import com.kitex.mom.pms.entity.PmsContractEntity;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.enums.PmsContractStatusEnum;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.mapper.PmsContractDetailMapper;
import com.kitex.mom.pms.mapper.PmsContractMapper;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.mapper.PmsOrderMapper;
import com.kitex.mom.pms.service.PmsContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    private final PmsOrderDetailMapper pmsOrderDetailMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(PmsContractEntity pmsContract) {
        requireContractableOrder(pmsContract.getPmsOrderNo());
        validateDraftContent(pmsContract);
        pmsContract.setStatus(PmsContractStatusEnum.ADD.getCode());
        pmsContract.setCurrentVersion(1);
        pmsContract.setCurrentContentFingerprint(null);
        pmsContract.setLastRevisionRequestId(null);
        pmsContract.setLastRevisedTime(null);
        pmsContract.setTerminationRequestId(null);
        pmsContract.setTerminatedTime(null);
        pmsContract.setTerminationReason(null);
        pmsContract.setBodyFormat(StrUtil.blankToDefault(pmsContract.getBodyFormat(), "PLAIN"));
        baseMapper.insert(pmsContract);
        List<PmsContractDetailEntity> contractDetails = pmsContract.getPmsContractDetailList() == null
                ? java.util.Collections.emptyList() : pmsContract.getPmsContractDetailList();
        normalizeNewContractDetails(pmsContract, contractDetails);
        for (PmsContractDetailEntity pmsContractDetail : contractDetails) {
            pmsContractDetail.setContractId(pmsContract.getId());
            pmsContractDetail.setId(null);
            pmsContractDetailMapper.insert(pmsContractDetail);
        }
        // 修改采购订单的状态为签订合同
        pmsOrderMapper.update(new LambdaUpdateWrapper<PmsOrderEntity>().eq(PmsOrderEntity::getPmsOrderNo, pmsContract.getPmsOrderNo())
                .set(PmsOrderEntity::getContractStatus, Boolean.TRUE));
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeepBatch(List<PmsContractEntity> contracts) {
        if (CollUtil.isEmpty(contracts)) return Boolean.TRUE;
        for (PmsContractEntity contract : contracts) {
            saveDeep(contract);
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(PmsContractEntity pmsContract) {
        if (pmsContract == null || pmsContract.getId() == null) {
            throw new CheckedException("合同编辑必须指定合同");
        }
        PmsContractEntity current = baseMapper.selectOne(new LambdaQueryWrapper<PmsContractEntity>()
                .eq(PmsContractEntity::getId, pmsContract.getId()).last("FOR UPDATE"));
        if (current == null) throw new CheckedException("采购合同不存在");
        if (PmsContractStatusEnum.TERMINATED.getCode().equals(current.getStatus())) {
            throw new CheckedException("已终止合同及条款必须保留历史，禁止再次编辑");
        }
        if (Set.of(PmsContractStatusEnum.COMPLETE.getCode(), PmsContractStatusEnum.ARCHIVIST.getCode()).contains(current.getStatus())) {
            throw new CheckedException("已结算或归档合同禁止直接编辑");
        }
        if (PmsContractStatusEnum.TERMINATED.getCode().equals(pmsContract.getStatus())) {
            throw new CheckedException("合同终止必须通过双人复核流程，禁止直接修改状态");
        }
        if (pmsContract.getTerminationRequestId() != null || pmsContract.getTerminatedTime() != null
                || StrUtil.isNotBlank(pmsContract.getTerminationReason())) {
            throw new CheckedException("合同终止证据字段只能由受控终止流程写入");
        }
        if ((pmsContract.getCurrentVersion() != null && !Objects.equals(pmsContract.getCurrentVersion(), current.getCurrentVersion()))
                || (pmsContract.getCurrentContentFingerprint() != null
                && !Objects.equals(pmsContract.getCurrentContentFingerprint(), current.getCurrentContentFingerprint()))
                || (pmsContract.getLastRevisionRequestId() != null
                && !Objects.equals(pmsContract.getLastRevisionRequestId(), current.getLastRevisionRequestId()))
                || (pmsContract.getLastRevisedTime() != null
                && !Objects.equals(pmsContract.getLastRevisedTime(), current.getLastRevisedTime()))) {
            throw new CheckedException("合同版本号、内容指纹和生效结果只能由正文版本变更流程写入");
        }
        boolean contentEditable = PmsContractStatusEnum.ADD.getCode().equals(current.getStatus());
        if (!contentEditable && (hasContentChange(current, pmsContract)
                || CollUtil.isNotEmpty(pmsContract.getPmsContractDetailList()))) {
            throw new CheckedException("非起草合同不得直接改写正文或条款；请进入正式合同正文版本变更流程");
        }
        if (contentEditable && hasContentChange(current, pmsContract)) validateDraftContent(pmsContract);
        // 校验采购订单状态
        if (PmsContractStatusEnum.SIGN.getCode().equals(pmsContract.getStatus()) || StrUtil.isBlank(pmsContract.getStatus())) {
            pmsContract.setStatus(checkOrderStatus(pmsContract.getPmsOrderNo()));
        }
        if (PmsContractStatusEnum.ADD.getCode().equals(current.getStatus())
                && !PmsContractStatusEnum.ADD.getCode().equals(pmsContract.getStatus())) {
            validateDraftContent(hasContentChange(current, pmsContract) ? pmsContract : current);
        }
        validateTransition(current.getStatus(), pmsContract.getStatus());

        baseMapper.updateById(pmsContract);
        if (CollUtil.isNotEmpty(pmsContract.getPmsContractDetailList())) {
            for (PmsContractDetailEntity pmsContractDetail : pmsContract.getPmsContractDetailList()) {
                if (Objects.isNull(pmsContractDetail.getId())) {
                    normalizeNewContractDetails(pmsContract, List.of(pmsContractDetail));
                    pmsContractDetail.setContractId(pmsContract.getId());
                    pmsContractDetailMapper.insert(pmsContractDetail);
                } else {
                    PmsContractDetailEntity currentDetail = pmsContractDetailMapper.selectById(pmsContractDetail.getId());
                    if (currentDetail == null || !Objects.equals(currentDetail.getContractId(), current.getId())) {
                        throw new CheckedException("合同条款不存在或不属于当前合同");
                    }
                    pmsContractDetail.setContractId(currentDetail.getContractId());
                    pmsContractDetail.setOrderDetailId(currentDetail.getOrderDetailId());
                    pmsContractDetail.setPmsPlanNo(currentDetail.getPmsPlanNo());
                    pmsContractDetail.setCancelledNum(currentDetail.getCancelledNum());
                    pmsContractDetail.setCancellationRequestId(currentDetail.getCancellationRequestId());
                    pmsContractDetail.setChangeVersion(currentDetail.getChangeVersion());
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
        if (pmsOrderEntity == null) {
            throw new CheckedException("采购订单不存在，禁止维护合同");
        }
        if (PmsOrderStatusEnum.RECEIVING.getCode().equals(pmsOrderEntity.getStatus()) ||
                PmsOrderStatusEnum.PART_RECEIVE.getCode().equals(pmsOrderEntity.getStatus())) {
            return PmsContractStatusEnum.EXECUTION.getCode();
        }
        return PmsContractStatusEnum.SIGN.getCode();
    }

    private void validateTransition(String before, String after) {
        Set<String> allowed = switch (before) {
            case "0" -> Set.of(PmsContractStatusEnum.ADD.getCode(), PmsContractStatusEnum.SIGN.getCode(),
                    PmsContractStatusEnum.EXECUTION.getCode());
            case "1" -> Set.of(PmsContractStatusEnum.SIGN.getCode(), PmsContractStatusEnum.EXECUTION.getCode(),
                    PmsContractStatusEnum.COMPLETE.getCode());
            case "2" -> Set.of(PmsContractStatusEnum.EXECUTION.getCode(), PmsContractStatusEnum.COMPLETE.getCode());
            case "3" -> Set.of(PmsContractStatusEnum.CHANGE.getCode(), PmsContractStatusEnum.SIGN.getCode(),
                    PmsContractStatusEnum.EXECUTION.getCode());
            default -> Set.of(before);
        };
        if (!allowed.contains(after)) {
            throw new CheckedException("合同状态不允许从" + PmsContractStatusEnum.getDescByCode(before)
                    + "直接变更为" + PmsContractStatusEnum.getDescByCode(after));
        }
    }

    private boolean hasContentChange(PmsContractEntity current, PmsContractEntity submitted) {
        return changed(submitted.getPmsOrderNo(), current.getPmsOrderNo())
                || changed(submitted.getTemplateCode(), current.getTemplateCode())
                || changed(submitted.getTemplateName(), current.getTemplateName())
                || changed(submitted.getTitle(), current.getTitle())
                || changed(submitted.getContractName(), current.getContractName())
                || changed(submitted.getContractManager(), current.getContractManager())
                || changed(submitted.getContractNo(), current.getContractNo())
                || changed(submitted.getContractDate(), current.getContractDate())
                || changed(submitted.getContractAmount(), current.getContractAmount())
                || changed(submitted.getContractDesc(), current.getContractDesc())
                || changed(submitted.getContractDemand(), current.getContractDemand())
                || changed(submitted.getContractBody(), current.getContractBody())
                || changed(submitted.getBodyFormat(), current.getBodyFormat())
                || changed(submitted.getSupplierCode(), current.getSupplierCode())
                || changed(submitted.getSupplierName(), current.getSupplierName())
                || changed(submitted.getLegalRepresentative(), current.getLegalRepresentative())
                || changed(submitted.getAddress(), current.getAddress())
                || changed(submitted.getBank(), current.getBank())
                || changed(submitted.getAccount(), current.getAccount())
                || changed(submitted.getContactPerson(), current.getContactPerson())
                || changed(submitted.getPhoneNumber(), current.getPhoneNumber());
    }

    private void validateDraftContent(PmsContractEntity contract) {
        if (StrUtil.isBlank(contract.getTitle()) || StrUtil.isBlank(contract.getContractName())
                || StrUtil.isBlank(contract.getContractManager()) || StrUtil.isBlank(contract.getContractDesc())
                || StrUtil.isBlank(contract.getContractDemand()) || StrUtil.isBlank(contract.getContractBody())) {
            throw new CheckedException("合同标题、合同名称、签约主体、重要条款、需求说明和正文全文不能为空");
        }
        String format = StrUtil.blankToDefault(contract.getBodyFormat(), "PLAIN");
        if (!Set.of("PLAIN", "MARKDOWN").contains(format)) {
            throw new CheckedException("合同正文格式仅支持PLAIN或MARKDOWN");
        }
        if (contract.getContractBody().length() > 200000) {
            throw new CheckedException("合同正文不能超过20万字符");
        }
    }

    private boolean changed(Object submitted, Object current) {
        return submitted != null && !Objects.equals(submitted, current);
    }

    /**
     * 将合同明细与采购订单行建立永久、显式的追溯关系。旧页面创建合同时会把订单行ID暂放在
     * 合同明细ID中，这里只把它当作来源线索，合同明细自身仍重新生成主键。
     */
    private void normalizeNewContractDetails(PmsContractEntity contract, List<PmsContractDetailEntity> details) {
        if (details == null || details.isEmpty()) return;
        PmsOrderEntity order = pmsOrderMapper.selectOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getPmsOrderNo, contract.getPmsOrderNo()));
        if (order == null) throw new CheckedException("合同关联采购订单不存在");
        List<PmsOrderDetailEntity> orderDetails = pmsOrderDetailMapper.selectList(
                new LambdaQueryWrapper<PmsOrderDetailEntity>()
                        .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId())
                        .orderByAsc(PmsOrderDetailEntity::getId));
        Map<Long, PmsOrderDetailEntity> byId = orderDetails.stream()
                .collect(Collectors.toMap(PmsOrderDetailEntity::getId, Function.identity()));
        Set<Long> used = new java.util.HashSet<>();
        for (PmsContractDetailEntity detail : details) {
            Long sourceId = detail.getOrderDetailId() != null ? detail.getOrderDetailId() : detail.getId();
            PmsOrderDetailEntity source = byId.get(sourceId);
            if (source == null) {
                List<PmsOrderDetailEntity> candidates = orderDetails.stream()
                        .filter(value -> Objects.equals(value.getMatCode(), detail.getMatCode()))
                        .filter(value -> !used.contains(value.getId())).toList();
                if (candidates.size() != 1) {
                    throw new CheckedException("合同物料" + detail.getMatCode()
                            + "无法唯一关联采购订单行，请重新从采购订单创建合同");
                }
                source = candidates.get(0);
            }
            if (!used.add(source.getId())) throw new CheckedException("同一采购订单行不能重复形成合同条款");
            if (!Objects.equals(source.getMatCode(), detail.getMatCode())) {
                throw new CheckedException("合同条款物料与采购订单行不一致");
            }
            detail.setId(null);
            detail.setOrderDetailId(source.getId());
            detail.setPmsPlanNo(source.getPmsPlanNo());
            detail.setCancelledNum(BigDecimal.ZERO);
            detail.setCancellationRequestId(null);
            detail.setChangeVersion(0);
        }
    }

    private void requireContractableOrder(String pmsOrderNo) {
        if (StrUtil.isBlank(pmsOrderNo)) {
            throw new CheckedException("采购合同必须绑定采购订单");
        }
        PmsOrderEntity order = pmsOrderMapper.selectOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getPmsOrderNo, pmsOrderNo).last("FOR UPDATE"));
        if (order == null || !Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(),
                PmsOrderStatusEnum.RECEIVING.getCode(), PmsOrderStatusEnum.PART_RECEIVE.getCode(),
                PmsOrderStatusEnum.COMPLETE.getCode()).contains(order.getStatus())) {
            throw new CheckedException("仅审核通过、收货中、部分到货或完成收货的采购订单允许签订合同");
        }
        if (Boolean.TRUE.equals(order.getContractStatus())) {
            throw new CheckedException("采购订单已绑定合同，禁止重复签订");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        if (ids == null || ids.length == 0) return Boolean.TRUE;
        List<PmsContractEntity> contracts = baseMapper.selectList(new LambdaQueryWrapper<PmsContractEntity>()
                .in(PmsContractEntity::getId, Arrays.asList(ids)).last("FOR UPDATE"));
        if (contracts.size() != Arrays.stream(ids).distinct().count()
                || contracts.stream().anyMatch(value -> !PmsContractStatusEnum.ADD.getCode().equals(value.getStatus()))) {
            throw new CheckedException("只能删除全部存在且仍在起草状态的合同；已签订、执行、结算或终止合同必须保留历史");
        }
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        pmsContractDetailMapper.delete(Wrappers.<PmsContractDetailEntity>lambdaQuery().in(PmsContractDetailEntity::getContractId, ids));
        for (PmsContractEntity contract : contracts) {
            long remaining = baseMapper.selectCount(new LambdaQueryWrapper<PmsContractEntity>()
                    .eq(PmsContractEntity::getPmsOrderNo, contract.getPmsOrderNo()));
            if (remaining == 0) {
                pmsOrderMapper.update(new LambdaUpdateWrapper<PmsOrderEntity>()
                        .eq(PmsOrderEntity::getPmsOrderNo, contract.getPmsOrderNo())
                        .set(PmsOrderEntity::getContractStatus, Boolean.FALSE));
            }
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeChild(Long[] ids) {
        if (ids == null || ids.length == 0) return Boolean.TRUE;
        List<PmsContractDetailEntity> details = pmsContractDetailMapper.selectList(
                new LambdaQueryWrapper<PmsContractDetailEntity>().in(PmsContractDetailEntity::getId, Arrays.asList(ids)).last("FOR UPDATE"));
        if (details.size() != Arrays.stream(ids).distinct().count())
            throw new CheckedException("合同明细不存在或已删除");
        Set<Long> contractIds = details.stream().map(PmsContractDetailEntity::getContractId).collect(java.util.stream.Collectors.toSet());
        List<PmsContractEntity> contracts = baseMapper.selectList(new LambdaQueryWrapper<PmsContractEntity>()
                .in(PmsContractEntity::getId, contractIds).last("FOR UPDATE"));
        if (contracts.size() != contractIds.size()
                || contracts.stream().anyMatch(value -> !PmsContractStatusEnum.ADD.getCode().equals(value.getStatus()))) {
            throw new CheckedException("只有起草合同允许删除条款明细，已形成合同事实必须保留历史");
        }
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
        if (PmsContractStatusEnum.TERMINATED.getCode().equals(pmsContract.getStatus())) {
            throw new CheckedException("采购合同已受控终止，禁止通过收料等旁路恢复为执行中");
        }
        pmsContract.setStatus(status);
        return this.updateById(pmsContract);
    }

    @Override
    public void assertReceivableContract(String pmsOrderNo) {
        long terminated = baseMapper.selectCount(new LambdaQueryWrapper<PmsContractEntity>()
                .eq(PmsContractEntity::getPmsOrderNo, pmsOrderNo)
                .eq(PmsContractEntity::getStatus, PmsContractStatusEnum.TERMINATED.getCode()));
        if (terminated > 0) {
            throw new CheckedException("采购订单关联合同已受控终止，禁止新增或继续编辑收料单；请完成订单剩余量撤回");
        }
    }
}
