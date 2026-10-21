package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlanMrpInventoryPeggingEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.OmsOrderChangeOperationMapper;
import com.kitex.mom.plan.mapper.OmsOrderChangeReviewBatchMapper;
import com.kitex.mom.plan.mapper.OmsOrderChangeVersionMapper;
import com.kitex.mom.plan.mapper.OmsOrderPlanRevisionMapper;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlanMrpInventoryPeggingService;
import com.kitex.mom.plan.service.PlnProductDemandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * OMS订单变更版本账：每一轮申请独占版本号，终态与操作履历永久保留。调用方须在本地事务中锁定订单跟踪头。
 */
@Service
@RequiredArgsConstructor
public class OmsOrderChangeLedgerService {
    private final OmsOrderChangeVersionMapper versionMapper;
    private final OmsOrderChangeOperationMapper operationMapper;
    private final OmsOrderDetailsService detailsService;
    private final PlnProductDemandService demandService;
    private final OmsOrderChangeReviewBatchMapper batchMapper;
    private final OmsOrderPlanRevisionMapper planRevisionMapper;
    private final PlanMatMrpService mrpService;
    private final PlanMrpInventoryPeggingService peggingService;
    private final OmsDeliveryPromiseOutcomeProjector deliveryPromiseOutcomeProjector;
    private final OmsOrderSupplyReplanOutcomeProjector supplyReplanOutcomeProjector;

    /**
     * 审批写回与OMS行/PLAN需求/版本终态同一数据库事务提交。
     */
    public void recordPlanRevision(OmsOrderChangeVersionEntity version, OmsOrderDetailsEntity source,
                                   PlnProductDemandEntity before, PlnProductDemandEntity after) {
        require(version != null && "SUBMITTED".equals(version.getStatus()) && version.getTenantId() != null
                        && source != null && before != null && after != null
                        && Objects.equals(version.getOrderCode(), source.getOrderCode())
                        && Objects.equals(version.getFactoryCode(), source.getFactoryCode())
                        && Objects.equals(version.getFactoryCode(), before.getFactoryCode())
                        && Objects.equals(version.getFactoryCode(), after.getFactoryCode())
                        && Objects.equals(before.getId(), after.getId())
                        && Objects.equals(before.getSaleOrderCodeDetails(), source.getId())
                        && Objects.equals(after.getSaleOrderCodeDetails(), source.getId())
                        && Objects.equals(before.getPlanState(), after.getPlanState()),
                "OMS→PLAN变更回执缺少同一版本、工厂或需求来源");
        boolean supplyCascade = operationMapper.selectCount(
                new LambdaQueryWrapper<OmsOrderChangeOperationEntity>()
                        .eq(OmsOrderChangeOperationEntity::getVersionId, version.getId())
                        .eq(OmsOrderChangeOperationEntity::getOperationType, "SUPPLY_CASCADE_REBASE")) != 0;
        OmsOrderPlanRevisionEntity row = new OmsOrderPlanRevisionEntity()
                .setVersionId(version.getId()).setOrderDetailId(source.getId())
                .setDemandId(before.getId()).setOrderCode(version.getOrderCode())
                .setDemandNo(before.getDemandNo())
                .setApplyMode(supplyCascade ? "MRP_SUPPLY_CASCADE_RECALCULATE"
                        : "2".equals(before.getPlanState()) ? "MRP_DRAFT_RECALCULATE" : "DRAFT_UPDATE")
                .setBeforeState(before.getPlanState())
                .setBeforeQuantity(before.getNeedNum()).setBeforeDate(before.getPlanEndDate())
                .setAfterQuantity(after.getNeedNum()).setAfterDate(after.getPlanEndDate())
                .setBeforeFingerprint(hash(planFact(before)))
                .setAfterFingerprint(hash(planFact(after)))
                .setAppliedTime(LocalDateTime.now()).setFactoryCode(version.getFactoryCode());
        row.setTenantId(version.getTenantId());
        require(planRevisionMapper.insert(row) == 1, "OMS→PLAN逐行变更回执保存失败");
    }

    public List<OmsOrderPlanRevisionEntity> planRevisions(Long versionId) {
        OmsOrderChangeVersionEntity version = requireVersion(versionId, false);
        requireFactory(version.getFactoryCode());
        return planRevisionMapper.selectList(new LambdaQueryWrapper<OmsOrderPlanRevisionEntity>()
                .eq(OmsOrderPlanRevisionEntity::getVersionId, versionId)
                .orderByAsc(OmsOrderPlanRevisionEntity::getOrderDetailId));
    }

    private String planFact(PlnProductDemandEntity row) {
        return JSONUtil.toJsonStr(Arrays.asList(row.getId(), row.getDemandNo(), row.getFactoryCode(),
                row.getSaleOrderCode(), row.getSaleOrderCodeDetails(), row.getPlanState(),
                quantity(row.getNeedNum()), row.getPlanEndDate()));
    }

    public String proposalFromOrder(List<OmsOrderDetailsEntity> rows) {
        List<List<Object>> facts = new ArrayList<>();
        for (OmsOrderDetailsEntity row : rows) {
            facts.add(Arrays.asList(row.getId(), row.getProductCode(), row.getProductName(), row.getProductSpec(),
                    row.getProductUnit(), row.getDeliverTime(), quantity(row.getDeliverNum()),
                    StrUtil.nullToEmpty(row.getRemark())));
        }
        facts.sort(Comparator.comparing(JSONUtil::toJsonStr));
        return JSONUtil.toJsonStr(facts);
    }

    public String proposalFromReview(List<OmsOrderDetailsReviewEntity> rows) {
        List<List<Object>> facts = new ArrayList<>();
        for (OmsOrderDetailsReviewEntity row : rows) {
            facts.add(Arrays.asList(row.getSourceDetailId(), row.getProductCode(), row.getProductName(),
                    row.getProductSpec(), row.getProductUnit(), row.getDeliverTime(),
                    quantity(row.getDeliverNum()), StrUtil.nullToEmpty(row.getRemark())));
        }
        facts.sort(Comparator.comparing(JSONUtil::toJsonStr));
        return JSONUtil.toJsonStr(facts);
    }

    public String currentSnapshot(String orderCode, String factoryCode) {
        requireFactory(factoryCode);
        List<OmsOrderDetailsEntity> rows = detailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, orderCode).orderByAsc(OmsOrderDetailsEntity::getId));
        List<PlnProductDemandEntity> demands = demandService.list(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, orderCode)
                .orderByAsc(PlnProductDemandEntity::getId));
        require(rows.stream().allMatch(row -> Objects.equals(row.getFactoryCode(), factoryCode))
                        && demands.stream().allMatch(row -> Objects.equals(row.getFactoryCode(), factoryCode)),
                "OMS订单行或PLAN来源需求的工厂与订单不一致，禁止生成变更版本");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("orderCode", orderCode);
        facts.put("factoryCode", factoryCode);
        facts.put("lines", rows.stream().map(row -> Arrays.asList(row.getId(), row.getOrderCode(),
                row.getFactoryCode(), row.getProductCode(), row.getProductName(), row.getProductSpec(),
                row.getProductUnit(), row.getDeliverTime(), quantity(row.getDeliverNum()),
                quantity(row.getDeliveredNum()), row.getRemark())).toList());
        facts.put("planDemands", demands.stream().map(row -> Arrays.asList(row.getId(), row.getDemandNo(),
                row.getSaleOrderCodeDetails(), row.getFactoryCode(), row.getProductCode(), row.getPlanState(),
                quantity(row.getNeedNum()), row.getPlanEndDate(), quantity(row.getCompleteNum()),
                row.getDemandWarehouseCode(), row.getMrpPriority(), row.getUpdateTime())).toList());
        List<String> demandNos = demands.stream().map(PlnProductDemandEntity::getDemandNo)
                .filter(StrUtil::isNotBlank).distinct().toList();
        List<PlanMatMrpEntity> mrp = demandNos.isEmpty() ? List.of() : mrpService.list(
                new LambdaQueryWrapper<PlanMatMrpEntity>()
                        .in(PlanMatMrpEntity::getPlanDemandNo, demandNos)
                        .orderByAsc(PlanMatMrpEntity::getId));
        List<Long> mrpIds = mrp.stream().map(PlanMatMrpEntity::getId).toList();
        List<PlanMrpInventoryPeggingEntity> pegging = mrpIds.isEmpty() ? List.of() : peggingService.list(
                new LambdaQueryWrapper<PlanMrpInventoryPeggingEntity>()
                        .in(PlanMrpInventoryPeggingEntity::getMrpId, mrpIds)
                        .orderByAsc(PlanMrpInventoryPeggingEntity::getId));
        facts.put("mrp", mrp.stream().map(JSONUtil::parseObj).toList());
        facts.put("inventoryPegging", pegging.stream().map(JSONUtil::parseObj).toList());
        return JSONUtil.toJsonStr(facts);
    }

    public boolean replaySubmit(String requestId, String orderCode, String factoryCode, String proposal) {
        String id = required(requestId, "订单变更提交请求号");
        versionMapper.lockKey("REQUEST:" + id);
        OmsOrderChangeVersionEntity existing = versionMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                        .eq(OmsOrderChangeVersionEntity::getSubmitRequestId, id));
        if (existing == null) {
            require(operationMapper.selectCount(new LambdaQueryWrapper<OmsOrderChangeOperationEntity>()
                            .eq(OmsOrderChangeOperationEntity::getRequestId, id)) == 0
                            && batchMapper.selectCount(new LambdaQueryWrapper<OmsOrderChangeReviewBatchEntity>()
                            .eq(OmsOrderChangeReviewBatchEntity::getRequestId, id)) == 0,
                    "提交请求号已用于其他OMS变更操作");
            return false;
        }
        require(Objects.equals(existing.getOrderCode(), orderCode)
                        && Objects.equals(existing.getFactoryCode(), factoryCode)
                        && Objects.equals(existing.getRequestFingerprint(), submitFingerprint(orderCode, factoryCode, proposal)),
                "订单变更提交请求号已被不同命令使用");
        requireFactory(existing.getFactoryCode());
        return true;
    }

    public OmsOrderChangeVersionEntity submit(String requestId, String orderCode, String factoryCode,
                                              Long reviewId, String before, String proposal, MomUser applicant) {
        require(reviewId != null && applicant != null && StrUtil.isNotBlank(applicant.getUsername()),
                "订单变更申请缺少评审主记录或申请人");
        String id = required(requestId, "订单变更提交请求号");
        requireFactory(factoryCode);
        versionMapper.lockKey("ORDER:" + factoryCode + ":" + orderCode);
        OmsOrderChangeVersionEntity latest = versionMapper.selectOne(new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                .eq(OmsOrderChangeVersionEntity::getOrderCode, orderCode)
                .eq(OmsOrderChangeVersionEntity::getFactoryCode, factoryCode)
                .orderByDesc(OmsOrderChangeVersionEntity::getVersionNo).last("LIMIT 1 FOR UPDATE"));
        int nextVersion = latest == null ? 1 : latest.getVersionNo() + 1;
        if (latest != null && "SUBMITTED".equals(latest.getStatus())) {
            latest.setStatus("SUPERSEDED").setAfterSnapshot(before);
            require(versionMapper.updateById(latest) == 1, "旧OMS变更版本取代失败");
            audit(latest, "OMS-SUP-" + hash(id + "|" + latest.getId()).substring(0, 48),
                    hash(id + "|" + latest.getId()), "SUPERSEDE", "SUBMITTED", "SUPERSEDED",
                    "由新申请号" + id + "取代", applicant);
            deliveryPromiseOutcomeProjector.project(latest);
            supplyReplanOutcomeProjector.project(latest);
        }
        OmsOrderChangeVersionEntity version = new OmsOrderChangeVersionEntity()
                .setOrderCode(orderCode).setReviewId(reviewId).setVersionNo(nextVersion)
                .setSubmitRequestId(id).setRequestFingerprint(submitFingerprint(orderCode, factoryCode, proposal))
                .setBeforeSnapshot(before).setProposedSnapshot(proposal).setStatus("SUBMITTED")
                .setApplicantCode(applicant.getUsername()).setApplicantName(userName(applicant))
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(factoryCode);
        require(TenantContextHolder.getTenantId() != null, "OMS受控订单变更必须有明确租户");
        version.setTenantId(TenantContextHolder.getTenantId());
        require(versionMapper.insert(version) == 1, "OMS订单变更版本保存失败");
        audit(version, id, version.getRequestFingerprint(), "SUBMIT", null, "SUBMITTED",
                "第" + nextVersion + "版订单交付变更申请", applicant);
        return version;
    }

    public boolean replayReview(String requestId, Long reviewId, String decision, String remark) {
        String id = required(requestId, "订单变更复核请求号");
        versionMapper.lockKey("REQUEST:" + id);
        OmsOrderChangeOperationEntity operation = operationMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderChangeOperationEntity>()
                        .eq(OmsOrderChangeOperationEntity::getRequestId, id));
        if (operation == null) {
            require(versionMapper.selectCount(new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                            .eq(OmsOrderChangeVersionEntity::getSubmitRequestId, id)) == 0
                            && batchMapper.selectCount(new LambdaQueryWrapper<OmsOrderChangeReviewBatchEntity>()
                            .eq(OmsOrderChangeReviewBatchEntity::getRequestId, id)) == 0,
                    "复核请求号已用于订单变更申请");
            return false;
        }
        OmsOrderChangeVersionEntity version = requireVersion(operation.getVersionId(), false);
        require(Objects.equals(version.getReviewId(), reviewId)
                        && Objects.equals(operation.getRequestFingerprint(), reviewFingerprint(reviewId, decision, remark))
                        && Objects.equals(operation.getOperationType(), decisionType(decision)),
                "复核请求号已被不同命令使用");
        requireFactory(version.getFactoryCode());
        return true;
    }

    public String childReviewRequestId(String batchRequestId, Long reviewId) {
        return "OMS-REV-" + hash(required(batchRequestId, "订单变更批量复核请求号")
                + "|" + reviewId).substring(0, 56);
    }

    public boolean replayBatch(String requestId, List<Long> reviewIds, String decision, String remark) {
        String id = required(requestId, "订单变更批量复核请求号");
        versionMapper.lockKey("BATCH:" + id);
        String fingerprint = batchFingerprint(reviewIds, decision, remark);
        OmsOrderChangeReviewBatchEntity batch = batchMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderChangeReviewBatchEntity>()
                        .eq(OmsOrderChangeReviewBatchEntity::getRequestId, id));
        if (batch != null) {
            require(Objects.equals(batch.getRequestFingerprint(), fingerprint),
                    "批量复核请求号已被不同命令使用");
            return true;
        }
        require(versionMapper.selectCount(new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                        .eq(OmsOrderChangeVersionEntity::getSubmitRequestId, id)) == 0
                        && operationMapper.selectCount(new LambdaQueryWrapper<OmsOrderChangeOperationEntity>()
                        .eq(OmsOrderChangeOperationEntity::getRequestId, id)) == 0,
                "批量复核请求号已用于其他订单变更命令");
        return false;
    }

    public void finishBatch(String requestId, List<Long> reviewIds, String decision,
                            String remark, MomUser reviewer) {
        require(reviewer != null && StrUtil.isNotBlank(reviewer.getUsername()), "批量复核缺少操作人");
        OmsOrderChangeReviewBatchEntity batch = new OmsOrderChangeReviewBatchEntity()
                .setRequestId(required(requestId, "订单变更批量复核请求号"))
                .setRequestFingerprint(batchFingerprint(reviewIds, decision, remark))
                .setReviewIdsSnapshot(JSONUtil.toJsonStr(reviewIds.stream().sorted().toList()))
                .setReviewType(decision).setRemark(remark)
                .setOperatorCode(reviewer.getUsername()).setCompletedTime(LocalDateTime.now());
        require(batchMapper.insert(batch) == 1, "订单变更批量复核命令账保存失败");
    }

    private String batchFingerprint(List<Long> reviewIds, String decision, String remark) {
        return hash(JSONUtil.toJsonStr(reviewIds.stream().sorted().toList()) + "|" + decision + "|" + remark);
    }

    public OmsOrderChangeVersionEntity requireSubmitted(Long reviewId, String orderCode, String factoryCode,
                                                        String current, String proposal) {
        requireFactory(factoryCode);
        OmsOrderChangeVersionEntity version = versionMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                        .eq(OmsOrderChangeVersionEntity::getOrderCode, orderCode)
                        .eq(OmsOrderChangeVersionEntity::getFactoryCode, factoryCode)
                        .eq(OmsOrderChangeVersionEntity::getStatus, "SUBMITTED")
                        .last("FOR UPDATE"));
        require(version != null && Objects.equals(version.getReviewId(), reviewId)
                        && Objects.equals(version.getFactoryCode(), factoryCode),
                "订单变更缺少本轮待审版本，历史申请须重新提交");
        require(Objects.equals(version.getBeforeSnapshot(), current)
                        && Objects.equals(version.getProposedSnapshot(), proposal),
                "订单或PLAN来源事实/评审候选已变化，请重新提交订单变更");
        return version;
    }

    /**
     * 在已审批的供应级联编排完成后，把版本的“当前事实基线”推进到撤回后的状态。
     * 目标候选保持不变，基线推进本身写入不可变操作账，最终订单评审仍会再次比对完整快照。
     */
    public void rebaseAfterSupplyCascade(OmsOrderChangeVersionEntity version, String requestId,
                                         String currentSnapshot, String content, MomUser operator) {
        require(version != null && version.getId() != null && "SUBMITTED".equals(version.getStatus())
                        && StrUtil.isNotBlank(currentSnapshot) && operator != null
                        && StrUtil.isNotBlank(operator.getUsername()),
                "供应级联后订单变更基线推进参数不完整");
        String id = required(requestId, "供应级联基线请求号");
        String fingerprint = hash(version.getId() + "|" + currentSnapshot + "|" + content);
        versionMapper.lockKey("REQUEST:" + id);
        OmsOrderChangeOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderChangeOperationEntity>()
                        .eq(OmsOrderChangeOperationEntity::getRequestId, id));
        if (replay != null) {
            require(Objects.equals(replay.getVersionId(), version.getId())
                            && Objects.equals(replay.getRequestFingerprint(), fingerprint),
                    "供应级联基线请求号已被不同命令使用");
            return;
        }
        require("SUBMITTED".equals(version.getStatus()), "订单变更版本已终结，不能推进供应级联基线");
        version.setBeforeSnapshot(currentSnapshot);
        require(versionMapper.updateById(version) == 1, "供应级联后订单变更基线保存失败");
        audit(version, id, fingerprint, "SUPPLY_CASCADE_REBASE", "SUBMITTED", "SUBMITTED", content, operator);
    }

    public void finish(OmsOrderChangeVersionEntity version, String requestId, String decision,
                       String remark, String after, MomUser reviewer) {
        String status = "0".equals(decision) ? "APPROVED" : "1".equals(decision) ? "REJECTED" : "CANCELLED";
        String fingerprint = reviewFingerprint(version.getReviewId(), decision, remark);
        require("SUBMITTED".equals(version.getStatus()) && reviewer != null
                        && StrUtil.isNotBlank(reviewer.getUsername())
                        && !Objects.equals(reviewer.getUsername(), version.getApplicantCode()),
                "订单变更版本已终结或申请/复核人未分离");
        version.setStatus(status).setAfterSnapshot(after).setReviewRequestId(requestId)
                .setReviewFingerprint(fingerprint).setReviewRemark(remark)
                .setReviewerCode(reviewer.getUsername()).setReviewerName(userName(reviewer))
                .setReviewedTime(LocalDateTime.now());
        require(versionMapper.updateById(version) == 1, "OMS订单变更终态版本保存失败");
        audit(version, requestId, fingerprint, decisionType(decision), "SUBMITTED", status, remark, reviewer);
        deliveryPromiseOutcomeProjector.project(version);
        supplyReplanOutcomeProjector.project(version);
    }

    public List<OmsOrderChangeVersionEntity> versions(String orderCode, String factoryCode) {
        requireFactory(factoryCode);
        return versionMapper.selectList(new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                .eq(OmsOrderChangeVersionEntity::getOrderCode, orderCode)
                .eq(OmsOrderChangeVersionEntity::getFactoryCode, factoryCode)
                .orderByDesc(OmsOrderChangeVersionEntity::getVersionNo));
    }

    public List<OmsOrderChangeOperationEntity> operations(Long versionId) {
        OmsOrderChangeVersionEntity version = requireVersion(versionId, false);
        requireFactory(version.getFactoryCode());
        return operationMapper.selectList(new LambdaQueryWrapper<OmsOrderChangeOperationEntity>()
                .eq(OmsOrderChangeOperationEntity::getVersionId, versionId)
                .orderByAsc(OmsOrderChangeOperationEntity::getOperationTime)
                .orderByAsc(OmsOrderChangeOperationEntity::getId));
    }

    private OmsOrderChangeVersionEntity requireVersion(Long id, boolean lock) {
        require(id != null, "订单变更版本不能为空");
        LambdaQueryWrapper<OmsOrderChangeVersionEntity> query = new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                .eq(OmsOrderChangeVersionEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        OmsOrderChangeVersionEntity version = versionMapper.selectOne(query);
        require(version != null, "订单变更版本不存在");
        return version;
    }

    private void audit(OmsOrderChangeVersionEntity version, String requestId, String fingerprint,
                       String type, String before, String after, String content, MomUser user) {
        OmsOrderChangeOperationEntity operation = new OmsOrderChangeOperationEntity()
                .setVersionId(version.getId()).setOrderCode(version.getOrderCode()).setRequestId(requestId)
                .setRequestFingerprint(fingerprint).setOperationType(type)
                .setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorCode(user.getUsername()).setOperatorName(userName(user))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(version.getFactoryCode());
        require(operationMapper.insert(operation) == 1, "订单变更不可变操作履历保存失败");
    }

    private String submitFingerprint(String orderCode, String factoryCode, String proposal) {
        return hash(orderCode + "|" + factoryCode + "|" + proposal);
    }

    private String reviewFingerprint(Long reviewId, String decision, String remark) {
        return hash(reviewId + "|" + decision + "|" + remark);
    }

    private String decisionType(String decision) {
        return "0".equals(decision) ? "APPROVE" : "1".equals(decision) ? "REJECT" : "CANCEL";
    }

    private String required(String value, String label) {
        String id = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(id) && id.length() <= 64, label + "不能为空且不能超过64位");
        return id;
    }

    private String quantity(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String userName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void requireFactory(String factoryCode) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(selected) || Objects.equals(selected, factoryCode), "当前工厂无权查看或操作订单变更版本");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("OMS订单变更指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
