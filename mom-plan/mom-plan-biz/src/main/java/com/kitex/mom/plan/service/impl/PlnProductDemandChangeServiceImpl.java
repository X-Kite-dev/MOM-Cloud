package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.plan.dto.PlanDemandChangeDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnProductDemandChangeService;
import com.kitex.mom.plan.service.PlnProductDemandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 人工产品需求在已接收或仅形成未下达MRP时的受控变更；外部来源和执行事实不走本白名单。
 */
@Service
@RequiredArgsConstructor
public class PlnProductDemandChangeServiceImpl implements PlnProductDemandChangeService {
    private static final String ACCEPTED_NO_MRP = "ACCEPTED_NO_MRP";
    private static final String MRP_DRAFT_RECALCULATE = "MRP_DRAFT_RECALCULATE";
    private final PlnProductDemandChangeMapper changeMapper;
    private final PlnProductDemandChangeOperationMapper operationMapper;
    private final PlnProductDemandMapper demandMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlanMrpInventoryPeggingMapper peggingMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingTaskDetailMapper detailMapper;
    private final PlnSchedulingOperationMapper schedulingMapper;
    private final PlnSchedulingBatchAllocationMapper allocationMapper;
    private final RemoteMesTaskService remoteMesTaskService;
    private final RemoteWarehouseService remoteWarehouseService;
    private final PlnProductDemandService demandService;
    private final PlnMpsService mpsService;
    private final PlanMatMrpService planMatMrpService;

    @Override
    public PlanDemandChangeDTO.Preview preview(PlanDemandChangeDTO.SubmitCommand command) {
        validateTarget(command);
        PlnProductDemandEntity demand = requireDemand(command.getDemandId(), false);
        Evidence evidence = capture(demand, command, false);
        return new PlanDemandChangeDTO.Preview().setDemandId(demand.getId()).setDemandNo(demand.getDemandNo())
                .setFactoryCode(demand.getFactoryCode()).setEvidenceFingerprint(evidence.fingerprint())
                .setEvidenceSnapshot(evidence.snapshot()).setTargetWarehouseName(evidence.warehouseName())
                .setApplyMode(evidence.applyMode())
                .setMessage(MRP_DRAFT_RECALCULATE.equals(evidence.applyMode())
                        ? "MRP仅为未下达草案，异岗复核批准后将原子撤销旧MRP/Pegging并按新需求重算；事实变化会使申请失效"
                        : "已接收且尚无MRP/APS/MES事实，可提交异岗复核；复核前事实变化将使申请失效");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnProductDemandChangeEntity submit(PlanDemandChangeDTO.SubmitCommand command) {
        validateTarget(command);
        String requestId = required(command.getRequestId(), 64, "申请请求号");
        String reason = required(command.getReason(), 500, "变更原因");
        String expected = requiredFingerprint(command.getExpectedEvidenceFingerprint());
        String requestFingerprint = hash(command.getDemandId() + "|" + quantity(command.getTargetQuantity()) + "|"
                + command.getTargetDemandDate() + "|" + command.getTargetWarehouseCode().trim() + "|"
                + command.getTargetMrpPriority() + "|" + expected + "|" + reason);
        changeMapper.lockKey("REQUEST:" + requestId);
        PlnProductDemandChangeEntity replay = changeMapper.selectOne(
                new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                        .eq(PlnProductDemandChangeEntity::getSubmitRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint), "申请幂等号已被不同命令使用");
            requireFactory(replay.getFactoryCode());
            return replay;
        }
        require(operationByRequest(requestId) == null, "申请请求号已用于其他变更操作");
        changeMapper.lockKey("DEMAND:" + command.getDemandId());
        PlnProductDemandEntity demand = requireDemand(command.getDemandId(), true);
        List<PlnProductDemandChangeEntity> active = changeMapper.selectList(
                new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                        .eq(PlnProductDemandChangeEntity::getDemandId, demand.getId())
                        .eq(PlnProductDemandChangeEntity::getStatus, "SUBMITTED"));
        require(active.isEmpty(), "该需求已有待复核变更，请先处理原申请");
        Evidence evidence = capture(demand, command, true);
        require(Objects.equals(expected, evidence.fingerprint()), "需求或目标仓库事实已变化，请重新预览");
        MomUser user = currentUser();
        PlnProductDemandChangeEntity entity = new PlnProductDemandChangeEntity()
                .setChangeNo("PDC-" + IdUtil.getSnowflakeNextIdStr())
                .setDemandId(demand.getId()).setDemandNo(demand.getDemandNo())
                .setSubmitRequestId(requestId).setRequestFingerprint(requestFingerprint)
                .setEvidenceFingerprint(evidence.fingerprint()).setEvidenceSnapshot(evidence.snapshot())
                .setBeforeSnapshot(evidence.beforeSnapshot())
                .setApplyMode(evidence.applyMode())
                .setTargetQuantity(command.getTargetQuantity()).setTargetDemandDate(command.getTargetDemandDate())
                .setTargetWarehouseCode(command.getTargetWarehouseCode().trim())
                .setTargetWarehouseName(evidence.warehouseName())
                .setTargetMrpPriority(command.getTargetMrpPriority())
                .setReason(reason).setStatus("SUBMITTED")
                .setApplicantCode(user.getUsername()).setApplicantName(userName(user))
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(demand.getFactoryCode());
        require(changeMapper.insert(entity) == 1, "需求变更申请保存失败");
        audit(entity, requestId, requestFingerprint, "SUBMIT", null, "SUBMITTED", reason, user);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnProductDemandChangeEntity review(PlanDemandChangeDTO.ReviewCommand command) {
        require(command != null && command.getChangeId() != null && command.getApproved() != null,
                "复核必须提供变更单和审批结论");
        String requestId = required(command.getRequestId(), 64, "复核请求号");
        String reason = required(command.getReason(), 500, "复核意见");
        String expected = requiredFingerprint(command.getExpectedEvidenceFingerprint());
        String requestFingerprint = hash(command.getChangeId() + "|" + command.getApproved() + "|" + expected + "|" + reason);
        changeMapper.lockKey("REQUEST:" + requestId);
        PlnProductDemandChangeOperationEntity replay = operationByRequest(requestId);
        if (replay != null) {
            require(Objects.equals(replay.getChangeId(), command.getChangeId())
                    && Objects.equals(replay.getRequestFingerprint(), requestFingerprint), "复核幂等号已被不同命令使用");
            return detail(command.getChangeId());
        }
        require(changeBySubmitRequest(requestId) == null, "复核请求号已用于申请");
        PlnProductDemandChangeEntity selected = detail(command.getChangeId());
        changeMapper.lockKey("DEMAND:" + selected.getDemandId());
        PlnProductDemandEntity demand = requireDemand(selected.getDemandId(), true);
        PlnProductDemandChangeEntity entity = requireChange(command.getChangeId(), true);
        if (MRP_DRAFT_RECALCULATE.equals(entity.getApplyMode()) && Boolean.TRUE.equals(command.getApproved())) {
            // 变更领料仓时旧池Pegging释放和新池重分配必须同事务串行，不能只锁目标仓。
            Set<String> pools = new LinkedHashSet<>();
            pools.add(demand.getFactoryCode() + "\u0001" + demand.getDemandWarehouseCode());
            pools.add(demand.getFactoryCode() + "\u0001" + entity.getTargetWarehouseCode());
            planMatMrpService.lockInventoryAllocationPools(pools);
        }
        require("SUBMITTED".equals(entity.getStatus()), "仅待复核的需求变更可以审批");
        require(Objects.equals(entity.getEvidenceFingerprint(), expected), "申请证据指纹不一致，请刷新后复核");
        require(StrUtil.isNotBlank(entity.getEvidenceSnapshot())
                        && Objects.equals(hash(entity.getEvidenceSnapshot()), entity.getEvidenceFingerprint()),
                "申请冻结证据快照与指纹不一致，须先核查数据");
        String frozenMode = JSONUtil.parseObj(entity.getEvidenceSnapshot()).getStr("applyMode");
        require(StrUtil.isBlank(frozenMode) ? ACCEPTED_NO_MRP.equals(entity.getApplyMode())
                        : Objects.equals(frozenMode, entity.getApplyMode()),
                "申请执行方式与冻结证据不一致，须先核查数据");
        require(entity.getTargetQuantity() != null && entity.getTargetDemandDate() != null
                        && entity.getTargetWarehouseCode() != null && entity.getTargetMrpPriority() != null
                        && Objects.equals(entity.getRequestFingerprint(), hash(entity.getDemandId() + "|"
                        + quantity(entity.getTargetQuantity()) + "|" + entity.getTargetDemandDate() + "|"
                        + entity.getTargetWarehouseCode() + "|" + entity.getTargetMrpPriority() + "|"
                        + entity.getEvidenceFingerprint() + "|" + entity.getReason())),
                "申请目标字段与冻结命令不一致，须先核查数据");
        requireFactory(entity.getFactoryCode());
        MomUser user = currentUser();
        require(!Objects.equals(user.getUsername(), entity.getApplicantCode()), "申请人与复核人必须分离");
        String after;
        if (!Boolean.TRUE.equals(command.getApproved())) {
            after = "REJECTED";
        } else {
            PlanDemandChangeDTO.SubmitCommand target = target(entity);
            Evidence current;
            String staleReason = null;
            try {
                current = capture(demand, target, true);
            } catch (StaleChangeException changed) {
                current = null;
                staleReason = changed.getMessage();
            }
            if (current == null || !Objects.equals(current.fingerprint(), entity.getEvidenceFingerprint())) {
                after = "STALE";
                entity.setStaleReason(StrUtil.blankToDefault(staleReason, "需求或目标仓库事实已变化，请重新申请"));
            } else {
                require(Objects.equals(entity.getApplyMode(), current.applyMode()),
                        "申请的执行模式与当前需求事实不一致，须先核查数据");
                require(Objects.equals(entity.getTargetWarehouseName(), current.warehouseName()),
                        "目标仓库名称快照与冻结证据不一致，须先核查数据");
                demand.setNeedNum(entity.getTargetQuantity());
                demand.setPlanEndDate(entity.getTargetDemandDate());
                demand.setDemandWarehouseCode(entity.getTargetWarehouseCode());
                demand.setDemandWarehouseName(current.warehouseName());
                demand.setMrpPriority(entity.getTargetMrpPriority());
                if (MRP_DRAFT_RECALCULATE.equals(entity.getApplyMode())) demand.setPlanState("1");
                require(demandMapper.updateById(demand) == 1, "批准后的产品需求写回失败");
                entity.setAfterSnapshot(demandSnapshot(demand)).setAppliedTime(LocalDateTime.now());
                after = "APPROVED";
            }
        }
        entity.setStatus(after).setReviewRequestId(requestId).setApproved("APPROVED".equals(after))
                .setReviewReason(reason).setReviewerCode(user.getUsername()).setReviewerName(userName(user))
                .setReviewedTime(LocalDateTime.now());
        require(changeMapper.updateById(entity) == 1, "需求变更复核回执保存失败");
        if ("APPROVED".equals(after) && MRP_DRAFT_RECALCULATE.equals(entity.getApplyMode())) {
            // 本地事务内先终结待审态，再调用既有MRP引擎。引擎会删除旧草案与Pegging并重算；
            // 任一校验/计算失败回滚需求、审批单和旧草案，绝不留下只改需求未改MRP的中间态。
            R<?> result = demandService.mrp(new Long[]{demand.getId()});
            require(result != null && result.isOk(), "变更批准后MRP重算失败，已回滚本次审批");
            PlnProductDemandEntity recalculated = requireDemand(demand.getId(), true);
            List<PlanMatMrpEntity> newMrp = mrpMapper.selectList(new LambdaQueryWrapper<PlanMatMrpEntity>()
                    .eq(PlanMatMrpEntity::getPlanDemandNo, demand.getDemandNo())
                    .orderByAsc(PlanMatMrpEntity::getId));
            require("2".equals(recalculated.getPlanState()) && !newMrp.isEmpty(),
                    "MRP重算未形成有效结果，已回滚本次审批");
            Map<String, Object> applied = new LinkedHashMap<>();
            applied.put("demand", JSONUtil.parseObj(demandSnapshot(recalculated)));
            applied.put("mrp", newMrp.stream().map(JSONUtil::parseObj).toList());
            applied.put("inventoryPegging", peggingMapper.selectList(
                            new LambdaQueryWrapper<PlanMrpInventoryPeggingEntity>()
                                    .eq(PlanMrpInventoryPeggingEntity::getPlanDemandNo, demand.getDemandNo())
                                    .orderByAsc(PlanMrpInventoryPeggingEntity::getId))
                    .stream().map(JSONUtil::parseObj).toList());
            entity.setAfterSnapshot(JSONUtil.toJsonStr(applied));
            require(changeMapper.updateById(entity) == 1, "MRP重算结果回执保存失败");
        }
        audit(entity, requestId, requestFingerprint,
                "APPROVED".equals(after) ? "REVIEW_APPROVE" : "REJECTED".equals(after) ? "REVIEW_REJECT" : "REVIEW_STALE",
                "SUBMITTED", after, "STALE".equals(after) ? reason + "；" + entity.getStaleReason()
                        : MRP_DRAFT_RECALCULATE.equals(entity.getApplyMode()) && "APPROVED".equals(after)
                        ? reason + "；旧MRP/Pegging已原子撤销并重算" : reason, user);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnProductDemandChangeEntity cancel(PlanDemandChangeDTO.CancelCommand command) {
        require(command != null && command.getChangeId() != null, "取消必须提供变更单");
        String requestId = required(command.getRequestId(), 64, "取消请求号");
        String reason = required(command.getReason(), 500, "取消原因");
        String fingerprint = hash(command.getChangeId() + "|" + reason);
        changeMapper.lockKey("REQUEST:" + requestId);
        PlnProductDemandChangeOperationEntity replay = operationByRequest(requestId);
        if (replay != null) {
            require(Objects.equals(replay.getChangeId(), command.getChangeId())
                    && Objects.equals(replay.getRequestFingerprint(), fingerprint), "取消幂等号已被不同命令使用");
            return detail(command.getChangeId());
        }
        require(changeBySubmitRequest(requestId) == null, "取消请求号已用于申请");
        PlnProductDemandChangeEntity selected = detail(command.getChangeId());
        changeMapper.lockKey("DEMAND:" + selected.getDemandId());
        PlnProductDemandChangeEntity entity = requireChange(command.getChangeId(), true);
        requireFactory(entity.getFactoryCode());
        require("SUBMITTED".equals(entity.getStatus()), "只有待复核变更允许取消");
        MomUser user = currentUser();
        require(Objects.equals(user.getUsername(), entity.getApplicantCode()), "仅申请人可以取消待复核变更");
        entity.setStatus("CANCELLED");
        require(changeMapper.updateById(entity) == 1, "需求变更取消失败");
        audit(entity, requestId, fingerprint, "CANCEL", "SUBMITTED", "CANCELLED", reason, user);
        return entity;
    }

    @Override
    public PlnProductDemandChangeEntity detail(Long id) {
        return requireChange(id, false);
    }

    @Override
    public List<PlnProductDemandChangeOperationEntity> operations(Long id) {
        PlnProductDemandChangeEntity entity = detail(id);
        requireFactory(entity.getFactoryCode());
        return operationMapper.selectList(new LambdaQueryWrapper<PlnProductDemandChangeOperationEntity>()
                .eq(PlnProductDemandChangeOperationEntity::getChangeId, id)
                .orderByAsc(PlnProductDemandChangeOperationEntity::getOperationTime)
                .orderByAsc(PlnProductDemandChangeOperationEntity::getId));
    }

    @Override
    public Page<PlnProductDemandChangeEntity> page(SearchPage page, Long demandId, String status) {
        LambdaQueryWrapper<PlnProductDemandChangeEntity> query = new LambdaQueryWrapper<>();
        query.eq(demandId != null, PlnProductDemandChangeEntity::getDemandId, demandId)
                .eq(StrUtil.isNotBlank(status), PlnProductDemandChangeEntity::getStatus, status)
                .orderByDesc(PlnProductDemandChangeEntity::getSubmittedTime);
        String factory = FactoryContextHolder.getFactoryCode();
        query.eq(StrUtil.isNotBlank(factory), PlnProductDemandChangeEntity::getFactoryCode, factory);
        return changeMapper.selectPage(page.toMpPage(), query);
    }

    private Evidence capture(PlnProductDemandEntity demand, PlanDemandChangeDTO.SubmitCommand target, boolean lock) {
        requireFactory(demand.getFactoryCode());
        requireEvidence(mpsService.demandChangeAllowed(demand),
                "需求有待审MPS版本，或已发布MPS进入冻结区/形成MRP；须走计划受控修订");
        requireEvidence(!target.getTargetDemandDate().isBefore(LocalDate.now()),
                "目标需求日期已过期，请重新申请");
        requireEvidence(("1".equals(demand.getPlanState()) || "2".equals(demand.getPlanState()))
                        && optional(demand.getCompleteNum()).signum() == 0,
                "仅已接收或仅形成未下达MRP、且没有完成量的产品需求允许此类变更");
        requireEvidence(StrUtil.isBlank(demand.getSaleOrderCode()) && StrUtil.isBlank(demand.getSourceType())
                        && demand.getSourceId() == null && StrUtil.isBlank(demand.getSourceNo()),
                "OMS或其他业务来源需求必须由来源受控变更，不得在PLAN单独改写");
        requireEvidence(StrUtil.isNotBlank(demand.getDemandNo()) && StrUtil.isNotBlank(demand.getFactoryCode()),
                "产品需求缺少单号或工厂");
        String demandNo = demand.getDemandNo();
        requireEvidence(demandMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getDemandNo, demandNo)) == 1,
                "需求号不唯一，禁止跨记录改写MRP事实");
        LambdaQueryWrapper<PlanMatMrpEntity> mrpQuery = new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demandNo).orderByAsc(PlanMatMrpEntity::getId);
        if (lock) mrpQuery.last("FOR UPDATE");
        List<PlanMatMrpEntity> mrpRows = mrpMapper.selectList(mrpQuery);
        List<PlanMrpInventoryPeggingEntity> peggingRows = peggingMapper.selectList(
                new LambdaQueryWrapper<PlanMrpInventoryPeggingEntity>()
                        .eq(PlanMrpInventoryPeggingEntity::getPlanDemandNo, demandNo)
                        .orderByAsc(PlanMrpInventoryPeggingEntity::getId));
        String applyMode = "1".equals(demand.getPlanState()) ? ACCEPTED_NO_MRP : MRP_DRAFT_RECALCULATE;
        if (ACCEPTED_NO_MRP.equals(applyMode)) {
            requireEvidence(mrpRows.isEmpty() && peggingRows.isEmpty(),
                    "已接收需求存在MRP或库存Pegging事实，请先核查状态");
        } else {
            requireEvidence(!mrpRows.isEmpty(), "MRP状态需求缺少可核验的MRP草案");
            Set<Long> mrpIds = new HashSet<>();
            for (PlanMatMrpEntity row : mrpRows) {
                mrpIds.add(row.getId());
                requireEvidence(Boolean.FALSE.equals(row.getStatus())
                                && optional(row.getCompleteNum()).signum() == 0
                                && Objects.equals(row.getFactoryCode(), demand.getFactoryCode())
                                && Objects.equals(row.getDemandWarehouseCode(), demand.getDemandWarehouseCode())
                                && StrUtil.isAllBlank(row.getSupplySourceType(), row.getSupplySourceNo(),
                                row.getSupplySourceStatus(), row.getSupplyWithdrawalRequestId(),
                                row.getMainMaterialReservationNo(), row.getMainMaterialReservationStatus(),
                                row.getMainMaterialReservationSnapshot(), row.getMaterialReservationNo(),
                                row.getMaterialReservationStatus(), row.getMaterialReservationSnapshot(),
                                row.getPurchaseRequestId(), row.getPurchaseOrderNo(), row.getPurchaseOrderStatus(),
                                row.getSubstituteAllocation(), row.getSubstituteDecisionRequestId(),
                                row.getSubstituteCancelRequestId())
                                && row.getSupplySourceId() == null && row.getPurchaseOrderId() == null
                                && row.getPurchaseOrderDetailId() == null
                                && row.getMainMaterialReservationTime() == null
                                && row.getMaterialReservationTime() == null
                                && row.getSupplyWithdrawalTime() == null
                                && optional(row.getPurchaseOrderNum()).signum() == 0
                                && !Set.of("APPROVED", "PARTIAL_APPROVED").contains(
                                StrUtil.blankToDefault(row.getSubstituteStatus(), "NONE"))
                                && Set.of("NONE", "PLANNED").contains(
                                StrUtil.blankToDefault(row.getInventoryPeggingStatus(), "NONE")),
                        "MRP已下达、已有替代决策、外部供应、正式预留或完成事实，须走分阶段撤回");
            }
            for (PlanMrpInventoryPeggingEntity row : peggingRows) {
                requireEvidence(mrpIds.contains(row.getMrpId())
                                && Objects.equals(row.getFactoryCode(), demand.getFactoryCode())
                                && Objects.equals(row.getWarehouseCode(), demand.getDemandWarehouseCode())
                                && StrUtil.isBlank(row.getReservationNo())
                                && row.getReservationTime() == null
                                && row.getSourceStockId() != null
                                && optional(row.getPeggedNum()).signum() > 0
                                && StrUtil.isNotBlank(row.getEvidenceFingerprint())
                                && "PLANNED".equals(row.getStatus()),
                        "库存Pegging已预留、消费、孤立或状态异常，须走分阶段撤回");
            }
            for (PlanMatMrpEntity row : mrpRows) {
                BigDecimal pegged = peggingRows.stream()
                        .filter(line -> Objects.equals(line.getMrpId(), row.getId()))
                        .map(line -> optional(line.getPeggedNum()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                requireEvidence(pegged.compareTo(optional(row.getInventoryPeggedNum())) == 0
                                && pegged.compareTo(optional(row.getStockAllocatedNum())) == 0,
                        "MRP库存Pegging数量账不守恒，禁止覆盖旧计算结果");
            }
        }
        requireEvidence(issueMapper.selectCount(new LambdaQueryWrapper<PlnIssueEntity>()
                        .and(query -> query.eq(PlnIssueEntity::getSourceDemandNo, demandNo)
                                .or().and(legacy -> legacy.isNull(PlnIssueEntity::getSourceDemandNo)
                                        .eq(PlnIssueEntity::getIssueNo, demandNo)))) == 0
                        && detailMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getIssueNo, demandNo)) == 0
                        && schedulingMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getIssueNo, demandNo)) == 0
                        && allocationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getIssueNo, demandNo)) == 0,
                "需求已形成生产计划或APS事实，须走跨模块受控变更");
        R<List<MesTaskEntity>> mes = remoteMesTaskService.omsLife(demandNo);
        require(mes != null && mes.isOk() && mes.getData() != null, "MES事实不可核验，禁止变更产品需求");
        requireEvidence(mes.getData().isEmpty(), "需求已关联MES工单，须走跨模块受控变更");
        R<MdmWarehouseEntity> response = remoteWarehouseService.getByCode(target.getTargetWarehouseCode().trim());
        require(response != null && response.isOk(), "目标仓库事实不可核验，申请保持待复核以便重试");
        MdmWarehouseEntity warehouse = response.getData();
        requireEvidence(warehouse != null && Boolean.TRUE.equals(warehouse.getStatus())
                        && (StrUtil.isBlank(warehouse.getFactoryCode())
                        || Objects.equals(warehouse.getFactoryCode(), demand.getFactoryCode())),
                "目标领料仓不存在、未启用或不属于需求工厂");
        requireEvidence(optional(demand.getNeedNum()).compareTo(target.getTargetQuantity()) != 0
                        || !Objects.equals(demand.getPlanEndDate(), target.getTargetDemandDate())
                        || !Objects.equals(demand.getDemandWarehouseCode(), warehouse.getWarehouseCode())
                        || !Objects.equals(normalPriority(demand.getMrpPriority()), target.getTargetMrpPriority()),
                "需求数量、日期、领料仓和优先级均未变化");
        String before = demandSnapshot(demand);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("demand", JSONUtil.parseObj(before));
        evidence.put("applyMode", applyMode);
        evidence.put("mrp", mrpRows.stream().map(JSONUtil::parseObj).toList());
        evidence.put("inventoryPegging", peggingRows.stream().map(JSONUtil::parseObj).toList());
        evidence.put("targetWarehouse", Arrays.asList(warehouse.getId(), warehouse.getWarehouseCode(),
                warehouse.getWarehouseName(), warehouse.getFactoryCode(), warehouse.getStatus(), warehouse.getUpdateTime()));
        evidence.put("downstream", "ISSUE/APS/MES_EMPTY");
        String snapshot = JSONUtil.toJsonStr(evidence);
        return new Evidence(hash(snapshot), snapshot, before, warehouse.getWarehouseName(), applyMode);
    }

    private String demandSnapshot(PlnProductDemandEntity demand) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", demand.getId());
        data.put("demandNo", demand.getDemandNo());
        data.put("factoryCode", demand.getFactoryCode());
        data.put("planState", demand.getPlanState());
        data.put("source", Arrays.asList(demand.getSaleOrderCode(), demand.getSaleOrderCodeDetails(),
                demand.getSourceType(), demand.getSourceId(), demand.getSourceNo(), demand.getSourceQuantitySnapshot(),
                demand.getSourceEvidenceFingerprint()));
        data.put("product", Arrays.asList(demand.getCustomerCode(), demand.getProductCode(), demand.getProductUnit()));
        data.put("plan", Arrays.asList(demand.getNeedNum(), demand.getPlanEndDate(), demand.getDemandWarehouseCode(),
                demand.getDemandWarehouseName(), demand.getMrpPriority(), demand.getCompleteNum(), demand.getUpdateTime()));
        return JSONUtil.toJsonStr(data);
    }

    private PlanDemandChangeDTO.SubmitCommand target(PlnProductDemandChangeEntity entity) {
        PlanDemandChangeDTO.SubmitCommand command = new PlanDemandChangeDTO.SubmitCommand();
        command.setDemandId(entity.getDemandId());
        command.setTargetQuantity(entity.getTargetQuantity());
        command.setTargetDemandDate(entity.getTargetDemandDate());
        command.setTargetWarehouseCode(entity.getTargetWarehouseCode());
        command.setTargetMrpPriority(entity.getTargetMrpPriority());
        return command;
    }

    private void validateTarget(PlanDemandChangeDTO.SubmitCommand command) {
        require(command != null && command.getDemandId() != null && command.getTargetQuantity() != null
                        && command.getTargetQuantity().signum() > 0 && command.getTargetDemandDate() != null
                        && command.getTargetQuantity().scale() <= 8 && command.getTargetQuantity().precision() <= 20
                        && command.getTargetQuantity().compareTo(new BigDecimal("1000000000000")) < 0
                        && !command.getTargetDemandDate().isBefore(LocalDate.now())
                        && StrUtil.isNotBlank(command.getTargetWarehouseCode())
                        && command.getTargetWarehouseCode().trim().length() <= 100
                        && command.getTargetMrpPriority() != null && command.getTargetMrpPriority() >= 1
                        && command.getTargetMrpPriority() <= 999,
                "变更目标必须提供正数数量、今日及以后的日期、领料仓和1～999的MRP优先级");
    }

    private PlnProductDemandEntity requireDemand(Long id, boolean lock) {
        LambdaQueryWrapper<PlnProductDemandEntity> query = new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnProductDemandEntity demand = demandMapper.selectOne(query);
        require(demand != null, "产品需求不存在");
        return demand;
    }

    private PlnProductDemandChangeEntity requireChange(Long id, boolean lock) {
        require(id != null, "需求变更单不能为空");
        LambdaQueryWrapper<PlnProductDemandChangeEntity> query = new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                .eq(PlnProductDemandChangeEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnProductDemandChangeEntity entity = changeMapper.selectOne(query);
        require(entity != null, "需求变更单不存在");
        requireFactory(entity.getFactoryCode());
        return entity;
    }

    private PlnProductDemandChangeEntity changeBySubmitRequest(String requestId) {
        return changeMapper.selectOne(new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                .eq(PlnProductDemandChangeEntity::getSubmitRequestId, requestId));
    }

    private PlnProductDemandChangeOperationEntity operationByRequest(String requestId) {
        return operationMapper.selectOne(new LambdaQueryWrapper<PlnProductDemandChangeOperationEntity>()
                .eq(PlnProductDemandChangeOperationEntity::getRequestId, requestId));
    }

    private void audit(PlnProductDemandChangeEntity entity, String requestId, String fingerprint,
                       String type, String before, String after, String content, MomUser user) {
        require(operationMapper.insert(new PlnProductDemandChangeOperationEntity()
                        .setChangeId(entity.getId()).setRequestId(requestId).setRequestFingerprint(fingerprint)
                        .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                        .setOperatorCode(user.getUsername()).setOperatorName(userName(user))
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(entity.getFactoryCode())) == 1,
                "需求变更操作履历保存失败");
    }

    private MomUser currentUser() {
        MomUser user;
        try {
            user = SecurityUtils.getUser();
        } catch (Exception ex) {
            user = null;
        }
        require(user != null && StrUtil.isNotBlank(user.getUsername()), "受控需求变更必须由已登录用户操作");
        return user;
    }

    private String userName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode), "当前工厂无权操作该需求变更");
    }

    private String required(String value, int max, String label) {
        String text = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(text) && text.length() <= max, label + "不能为空且长度不得超过" + max);
        return text;
    }

    private String requiredFingerprint(String value) {
        String text = required(value, 64, "冻结证据指纹");
        require(text.length() == 64 && text.matches("[0-9a-f]{64}"), "冻结证据指纹格式错误");
        return text;
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Integer normalPriority(Integer value) {
        return value == null ? 100 : value;
    }

    private String quantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("需求变更证据指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private void requireEvidence(boolean condition, String message) {
        if (!condition) throw new StaleChangeException(message);
    }

    private static final class StaleChangeException extends CheckedException {
        private StaleChangeException(String message) {
            super(message);
        }
    }

    private record Evidence(String fingerprint, String snapshot, String beforeSnapshot,
                            String warehouseName, String applyMode) {
    }
}
