package com.kitex.mom.plan.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.dto.MdmBomSubstituteOptionDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.entity.MdmMaterialAuthorizationEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.enums.MatAttributeEnum;
import com.kitex.mom.mdm.feign.*;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.oms.entity.OmsDeliveryIssueEntity;
import com.kitex.mom.oms.entity.OmsDeliveryWithdrawalEntity;
import com.kitex.mom.oms.entity.OmsOrderChangeVersionEntity;
import com.kitex.mom.plan.constant.MrpReadinessStatusEnum;
import com.kitex.mom.plan.constant.MrpSubstituteStatusEnum;
import com.kitex.mom.plan.constant.PlanStatusEnum;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.plan.vo.PlnProductDemandExcelVO;
import com.kitex.mom.plan.vo.PlnTemporaryDemandDateVO;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.dto.WmsLiveStockByProductCodeDto;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产计划表
 *
 * @author kitex
 * @date 2024-11-06 17:36:16
 */
@Service
@AllArgsConstructor
@EnableAsync
@Slf4j
public class PlnProductDemandServiceImpl extends ServiceImpl<PlnProductDemandMapper, PlnProductDemandEntity>
        implements PlnProductDemandService {

    private final RemoteCustomerService remoteCustomerService;

    private final RemoteMaterialService remoteMaterialService;

    private final RemoteMaterialAuthorizationService remoteMaterialAuthorizationService;

    private final RemoteWarehouseService remoteWarehouseService;

    private final RemoteBomService remoteBomService;

    private final RemoteLiveStockService remoteLiveStockService;

    private final PlanMatMrpService planMatMrpService;

    private final PlanMrpInventoryPeggingService inventoryPeggingService;

    private final RemoteSeqConfigService remoteSeqConfigService;

    private final PlnIssueService plnIssueService;

    private final RemoteMesTaskService remoteMesTaskService;

    private final PlnSchedulingBatchAllocationMapper batchAllocationMapper;

    private final PlnSchedulingBatchMapper schedulingBatchMapper;

    private final PlnSchedulingTaskDetailMapper schedulingTaskDetailMapper;

    private final PlnSchedulingOperationMapper schedulingOperationMapper;

    private final PlnProductDemandChangeMapper demandChangeMapper;
    private final PlnMpsService mpsService;
    private final PlnMpsCapacityPublicationLock publicationLock;
    private final PlnQualitySupplementPlanningProjector supplementPlanning;
    private final OmsDeliveryWithdrawalMapper deliveryWithdrawalMapper;
    private final OmsDeliveryIssueMapper deliveryIssueMapper;
    private final OmsOrderChangeVersionMapper omsOrderChangeVersionMapper;
    private final PlnFormalPlanningGate formalPlanningGate;
    private final PlnFormalPlanContractService formalPlanContractService;

    @Override
    @Transactional(readOnly = true)
    public PlnDemandPlanningParametersDTO.Snapshot getPlanningParameters(Long demandId) {
        PlnProductDemandEntity demand = requireOmsPlanningDraft(demandId, false);
        var snapshot = new PlnDemandPlanningParametersDTO.Snapshot();
        snapshot.setDemandId(demand.getId());
        snapshot.setDemandNo(demand.getDemandNo());
        snapshot.setSaleOrderCode(demand.getSaleOrderCode());
        snapshot.setProductCode(demand.getProductCode());
        snapshot.setProductName(demand.getProductName());
        snapshot.setProductUnit(demand.getProductUnit());
        snapshot.setNeedNum(demand.getNeedNum());
        snapshot.setPlanEndDate(demand.getPlanEndDate());
        snapshot.setDemandWarehouseCode(demand.getDemandWarehouseCode());
        snapshot.setDemandWarehouseName(demand.getDemandWarehouseName());
        snapshot.setMrpPriority(normalizeMrpPriority(demand.getMrpPriority()));
        snapshot.setEvidenceFingerprint(planningParametersFingerprint(demand));
        return snapshot;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePlanningParameters(PlnDemandPlanningParametersDTO.UpdateCommand command) {
        if (command == null || command.getDemandId() == null
                || StrUtil.isBlank(command.getDemandWarehouseCode()) || command.getDemandWarehouseCode().length() > 128
                || command.getMrpPriority() == null || command.getExpectedEvidenceFingerprint() == null
                || !command.getExpectedEvidenceFingerprint().matches("[a-f0-9]{64}")) {
            throw new CheckedException("请提供需求、领料仓、MRP优先级及原计划参数快照");
        }
        int priority = normalizeMrpPriority(command.getMrpPriority());
        PlnProductDemandEntity demand = requireOmsPlanningDraft(command.getDemandId(), true);
        if (!Objects.equals(command.getExpectedEvidenceFingerprint(), planningParametersFingerprint(demand))) {
            throw new CheckedException("需求或计划参数已变化，请重新读取后再保存");
        }
        var parameters = new PlnProductDemandEntity();
        parameters.setFactoryCode(demand.getFactoryCode());
        parameters.setDemandWarehouseCode(command.getDemandWarehouseCode().trim());
        MdmWarehouseEntity warehouse = applyDemandWarehouse(parameters, true);
        if (!Objects.equals(demand.getFactoryCode(), warehouse.getFactoryCode())
                || !Objects.equals(demand.getTenantId(), warehouse.getTenantId())
                || !Objects.equals(command.getDemandWarehouseCode().trim(), warehouse.getWarehouseCode())) {
            throw new CheckedException("需求领料仓库不属于当前需求的租户和工厂");
        }
        // 空实体仅参与公共修改审计填充；业务字段全部由SET白名单控制，禁止覆盖OMS权威字段。
        boolean updated = update(new PlnProductDemandEntity(), new LambdaUpdateWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, demand.getId())
                .eq(PlnProductDemandEntity::getTenantId, demand.getTenantId())
                .eq(PlnProductDemandEntity::getFactoryCode, demand.getFactoryCode())
                .eq(PlnProductDemandEntity::getPlanState, PlanStatusEnum.CANCEL.getCode())
                .set(PlnProductDemandEntity::getDemandWarehouseCode, warehouse.getWarehouseCode())
                .set(PlnProductDemandEntity::getDemandWarehouseName, warehouse.getWarehouseName())
                .set(PlnProductDemandEntity::getMrpPriority, priority));
        if (!updated) throw new CheckedException("计划参数未保存，请重新读取需求");
        return true;
    }

    private PlnProductDemandEntity requireOmsPlanningDraft(Long demandId, boolean lock) {
        String factory = FactoryContextHolder.getFactoryCode();
        Long tenant = TenantContextHolder.getTenantId();
        if (demandId == null || StrUtil.isBlank(factory) || tenant == null
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())) {
            throw new CheckedException("请在有效租户和工厂内配置计划参数");
        }
        var query = new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, demandId)
                .eq(PlnProductDemandEntity::getTenantId, tenant)
                .eq(PlnProductDemandEntity::getFactoryCode, factory);
        if (lock) query.last("FOR UPDATE");
        PlnProductDemandEntity demand = getOne(query);
        if (demand == null) throw new CheckedException("需求不存在或不属于当前租户和工厂");
        if (StrUtil.isBlank(demand.getSaleOrderCode()) || demand.getSaleOrderCodeDetails() == null
                || StrUtil.isNotBlank(demand.getSourceType()) || demand.getSourceId() != null) {
            throw new CheckedException("此入口仅配置OMS来源需求的接收前计划参数");
        }
        requireDraftEditable(demand);
        if (mpsService.hasActiveLine(demandId)
                || demandChangeMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                .eq(PlnProductDemandChangeEntity::getDemandId, demandId)
                .eq(PlnProductDemandChangeEntity::getStatus, "SUBMITTED")) != 0) {
            throw new CheckedException("需求已有活动MPS或待处理变更，不能修改计划参数");
        }
        requireNoPendingOmsDelivery(demand);
        return demand;
    }

    private String planningParametersFingerprint(PlnProductDemandEntity demand) {
        // 原始时间另行编码，保留数据库小数秒精度，不依赖JSON日期格式或浏览器反序列化。
        return hash(JSONUtil.toJsonStr(demand) + "\n" + Objects.toString(demand.getUpdateTime(), ""));
    }

    private void requireNoPendingOmsDelivery(PlnProductDemandEntity demand) {
        if (StrUtil.isNotBlank(demand.getSaleOrderCode())
                && (deliveryIssueMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                .eq(OmsDeliveryIssueEntity::getOrderDetailId, demand.getSaleOrderCodeDetails())
                .ne(OmsDeliveryIssueEntity::getStatus, "SUCCEEDED")) != 0
                || deliveryWithdrawalMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                .eq(OmsDeliveryWithdrawalEntity::getOrderDetailId, demand.getSaleOrderCodeDetails())
                .ne(OmsDeliveryWithdrawalEntity::getStatus, "SUCCEEDED")) != 0)) {
            throw new CheckedException("OMS交付行正在下发/撤回或待对账，不能接收或修改该来源需求");
        }
    }

    /**
     * 导出Excel
     *
     * @param plnProductDemand
     * @param ids
     * @return
     */
    @Override
    public List<PlnProductDemandExcelVO> exportExcel(PlnProductDemandEntity plnProductDemand, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(plnProductDemand).in(ArrayUtil.isNotEmpty(ids), PlnProductDemandEntity::getId, ids))
                .stream()
                .map(entity -> {
                    PlnProductDemandExcelVO excelVO = new PlnProductDemandExcelVO();
                    BeanUtil.copyProperties(entity, excelVO);
                    excelVO.setPlanState(PlanStatusEnum.getDescByCode(entity.getPlanState()));
                    return excelVO;
                }).toList();
    }

    /**
     * 新增生产计划
     *
     * @param plnProductDemand
     * @return
     */
    @Override
    public Boolean savePlan(PlnProductDemandEntity plnProductDemand) {
        if (plnProductDemand == null || plnProductDemand.getId() != null) {
            throw new CheckedException("新建产品需求不能携带已有记录ID");
        }
        if (StrUtil.isBlank(plnProductDemand.getDemandNo())) {
            plnProductDemand.setDemandNo(remoteSeqConfigService.create("plan_demand").getData());
        }
        // 校验需求数量
        if (plnProductDemand.getNeedNum() == null || plnProductDemand.getNeedNum().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("需求数量无效：需求数量必须大于零");
        }
        if (StrUtil.isNotBlank(plnProductDemand.getDemandWarehouseCode())) {
            applyDemandWarehouse(plnProductDemand, false);
        }
        plnProductDemand.setMrpPriority(normalizeMrpPriority(plnProductDemand.getMrpPriority()));
        plnProductDemand.setPlanState(PlanStatusEnum.CANCEL.getCode());
        return this.save(plnProductDemand);
    }

    @Override
    public Boolean saveManualPlan(PlnProductDemandEntity input) {
        if (input == null || input.getId() != null || StrUtil.isBlank(input.getProductCode())
                || StrUtil.isBlank(input.getCustomerCode()) || input.getPlanEndDate() == null) {
            throw new CheckedException("人工产品需求必须提供客户、产品和需求日期，且不能携带已有记录ID");
        }
        String factory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isBlank(factory)) throw new CheckedException("请先选择工厂后再新建产品需求");
        PlnProductDemandEntity demand = new PlnProductDemandEntity();
        demand.setFactoryCode(factory);
        demand.setCustomerCode(input.getCustomerCode());
        demand.setCustomerName(input.getCustomerName());
        demand.setProductCode(input.getProductCode());
        demand.setProductName(input.getProductName());
        demand.setSpecification(input.getSpecification());
        demand.setProductUnit(input.getProductUnit());
        demand.setPlanEndDate(input.getPlanEndDate());
        demand.setNeedNum(input.getNeedNum());
        demand.setMrpPriority(input.getMrpPriority());
        demand.setDemandWarehouseCode(input.getDemandWarehouseCode());
        return savePlan(demand);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePlan(PlnProductDemandEntity command) {
        return updateDraft(command, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateFromOms(PlnProductDemandEntity command) {
        return updateDraft(command, true);
    }

    private Boolean updateDraft(PlnProductDemandEntity command, boolean fromOms) {
        if (command == null || command.getId() == null) {
            throw new CheckedException("更新产品需求必须指定记录");
        }
        PlnProductDemandEntity existing = Optional.ofNullable(getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getId, command.getId()).last("FOR UPDATE")))
                .orElseThrow(() -> new CheckedException("产品需求不存在"));
        String currentFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(currentFactory) && !Objects.equals(currentFactory, existing.getFactoryCode())) {
            throw new CheckedException("当前工厂无权变更该产品需求");
        }
        if (fromOms && (!Objects.equals(command.getFactoryCode(), existing.getFactoryCode())
                || StrUtil.isNotBlank(existing.getSourceType()) || existing.getSourceId() != null)) {
            throw new CheckedException("OMS来源工厂或业务来源不匹配");
        }
        if (fromOms && (PlanStatusEnum.RECEIVE.getCode().equals(existing.getPlanState())
                || PlanStatusEnum.MRP.getCode().equals(existing.getPlanState()))) {
            if (sameOmsDemand(existing, command)) return true;
            throw new CheckedException("OMS订单需求已接收或生成MRP，内容变化必须走受控计划变更");
        }
        requireDraftEditable(existing);
        if (StrUtil.isNotBlank(existing.getSourceType()) || existing.getSourceId() != null) {
            throw new CheckedException("此需求已有独立业务来源，必须由来源闭环变更");
        }
        if (fromOms != StrUtil.isNotBlank(existing.getSaleOrderCode())) {
            throw new CheckedException(fromOms ? "仅OMS来源需求允许通过OMS接口更新" : "OMS来源需求必须由OMS变更");
        }
        if (fromOms && (!Objects.equals(command.getSaleOrderCode(), existing.getSaleOrderCode())
                || !Objects.equals(command.getSaleOrderCodeDetails(), existing.getSaleOrderCodeDetails()))) {
            throw new CheckedException("OMS更新必须带上与原需求一致的销售订单和订单行");
        }
        if (StrUtil.isBlank(command.getCustomerCode()) || StrUtil.isBlank(command.getProductCode())
                || command.getPlanEndDate() == null || command.getNeedNum() == null
                || command.getNeedNum().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CheckedException("产品需求必须提供客户、产品、需求日期和正数数量");
        }
        rejectImmutableChange(command.getFactoryCode(), existing.getFactoryCode(), "工厂");
        rejectImmutableChange(command.getDemandNo(), existing.getDemandNo(), "需求单号");
        rejectImmutableChange(command.getPlanState(), existing.getPlanState(), "需求状态");
        rejectImmutableChange(command.getSaleOrderCode(), existing.getSaleOrderCode(), "销售订单");
        rejectImmutableChange(command.getSaleOrderCodeDetails(), existing.getSaleOrderCodeDetails(), "销售订单行");
        rejectImmutableChange(command.getSourceType(), existing.getSourceType(), "来源类型");
        rejectImmutableChange(command.getSourceId(), existing.getSourceId(), "来源ID");
        rejectImmutableChange(command.getSourceNo(), existing.getSourceNo(), "来源单号");
        rejectImmutableChange(command.getSourceQuantitySnapshot(), existing.getSourceQuantitySnapshot(), "来源数量");
        rejectImmutableChange(command.getSourceEvidenceFingerprint(), existing.getSourceEvidenceFingerprint(), "来源证据");
        rejectImmutableChange(command.getCompleteNum(), existing.getCompleteNum(), "完成数量");
        existing.setCustomerCode(command.getCustomerCode());
        existing.setCustomerName(command.getCustomerName());
        existing.setProductCode(command.getProductCode());
        existing.setProductName(command.getProductName());
        existing.setSpecification(command.getSpecification());
        existing.setProductUnit(command.getProductUnit());
        existing.setPlanEndDate(command.getPlanEndDate());
        existing.setNeedNum(command.getNeedNum());
        existing.setMrpPriority(normalizeMrpPriority(fromOms || command.getMrpPriority() == null
                ? existing.getMrpPriority() : command.getMrpPriority()));
        if (!fromOms) existing.setDemandWarehouseCode(command.getDemandWarehouseCode());
        applyDemandWarehouse(existing, !fromOms);
        return updateById(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteDrafts(Long[] ids) {
        if (ids == null || ids.length == 0 || Arrays.stream(ids).anyMatch(Objects::isNull)) {
            throw new CheckedException("删除产品需求必须提供有效ID");
        }
        for (Long id : Arrays.stream(ids).distinct().sorted().toList()) {
            PlnProductDemandEntity demand = getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                    .eq(PlnProductDemandEntity::getId, id).last("FOR UPDATE"));
            if (demand == null || StrUtil.isNotBlank(demand.getSaleOrderCode())) {
                throw new CheckedException("OMS来源需求不能通过普通删除入口撤回：" + id);
            }
            if (!removeById(id)) throw new CheckedException("删除产品需求失败：" + id);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertDraftDeletable(Long id) {
        if (id == null) throw new CheckedException("产品需求ID不能为空");
        PlnProductDemandEntity demand = getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, id).last("FOR UPDATE"));
        if (demand == null) throw new CheckedException("产品需求不存在：" + id);
        requireDraftEditable(demand);
        if (StrUtil.isNotBlank(demand.getSourceType()) || demand.getSourceId() != null) {
            throw new CheckedException("来源业务需求不能直接删除");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertOmsReviewChangeable(Long id, String orderCode, Long orderDetailId) {
        requireOmsReviewChangeable(id, orderCode, orderDetailId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public PlnProductDemandEntity applyOmsReviewedChange(Long id, String orderCode, Long orderDetailId,
                                                         Long orderChangeVersionId,
                                                         BigDecimal beforeQuantity, LocalDate beforeDate, BigDecimal afterQuantity, LocalDate afterDate) {
        if (beforeQuantity == null || beforeDate == null || afterQuantity == null || afterQuantity.signum() <= 0
                || afterDate == null || afterDate.isBefore(LocalDate.now())) {
            throw new CheckedException("OMS受控变更须提供原数量/交期和有效目标数量/交期");
        }
        PlnProductDemandEntity demand = requireOmsReviewChangeable(id, orderCode, orderDetailId);
        boolean recalculateMrp = PlanStatusEnum.MRP.getCode().equals(demand.getPlanState());
        if (demand.getNeedNum() == null || demand.getNeedNum().compareTo(beforeQuantity) != 0
                || !Objects.equals(demand.getPlanEndDate(), beforeDate)) {
            throw new CheckedException("OMS订单行与PLAN需求原数量或交期已漂移，请重新提交变更");
        }
        demand.setNeedNum(afterQuantity);
        demand.setPlanEndDate(afterDate);
        if (recalculateMrp) demand.setPlanState(PlanStatusEnum.RECEIVE.getCode());
        if (!updateById(demand)) throw new CheckedException("OMS评审同步PLAN需求失败");
        if (!recalculateMrp) return demand;
        R<?> result = runMrp(new Long[]{id}, orderChangeVersionId);
        if (result == null || !result.isOk()) {
            throw new CheckedException("OMS评审批准后的MRP原子重算失败");
        }
        PlnProductDemandEntity recalculated = getById(id);
        if (recalculated == null || !PlanStatusEnum.MRP.getCode().equals(recalculated.getPlanState())
                || planMatMrpService.count(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demand.getDemandNo())) == 0) {
            throw new CheckedException("OMS评审未形成有效MRP重算结果，整个审批已回滚");
        }
        return recalculated;
    }

    private PlnProductDemandEntity requireOmsReviewChangeable(Long id, String orderCode, Long orderDetailId) {
        if (id == null || StrUtil.isBlank(orderCode) || orderDetailId == null) {
            throw new CheckedException("OMS来源需求身份不完整");
        }
        PlnProductDemandEntity demand = getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, id).last("FOR UPDATE"));
        if (demand == null || !Objects.equals(demand.getSaleOrderCode(), orderCode)
                || !Objects.equals(demand.getSaleOrderCodeDetails(), orderDetailId)
                || StrUtil.isBlank(demand.getFactoryCode()) || StrUtil.isNotBlank(demand.getSourceType())
                || demand.getSourceId() != null || optional(demand.getCompleteNum()).signum() != 0
                || !mpsService.demandChangeAllowed(demand) || mpsService.hasActiveLine(id)) {
            throw new CheckedException("OMS来源、工厂、MPS冻结或需求完成事实不允许本轮订单变更");
        }
        String selectedFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(selectedFactory) && !Objects.equals(selectedFactory, demand.getFactoryCode())) {
            throw new CheckedException("当前工厂无权修改OMS来源需求");
        }
        if (demandChangeMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                .eq(PlnProductDemandChangeEntity::getDemandId, id)
                .eq(PlnProductDemandChangeEntity::getStatus, "SUBMITTED")) != 0
                || deliveryIssueMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                .eq(OmsDeliveryIssueEntity::getOrderDetailId, orderDetailId)
                .ne(OmsDeliveryIssueEntity::getStatus, "SUCCEEDED")) != 0
                || deliveryWithdrawalMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                .eq(OmsDeliveryWithdrawalEntity::getOrderDetailId, orderDetailId)
                .ne(OmsDeliveryWithdrawalEntity::getStatus, "SUCCEEDED")) != 0) {
            throw new CheckedException("PLAN需求变更或OMS下发/撤回命令仍在处理，须先续跑或对账");
        }
        if (PlanStatusEnum.CANCEL.getCode().equals(demand.getPlanState())) {
            requireDraftEditable(demand);
        } else if (PlanStatusEnum.RECEIVE.getCode().equals(demand.getPlanState())) {
            requireNoDownstreamFacts(demand);
        } else if (PlanStatusEnum.MRP.getCode().equals(demand.getPlanState())) {
            requireNoExecutionFacts(demand);
            requireDraftMrpRecalculable(demand);
        } else {
            throw new CheckedException("OMS来源需求已形成MRP或执行事实，须走分阶段受控撤回/重算");
        }
        return demand;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        if (!(id instanceof Long demandId)) throw new CheckedException("删除产品需求必须提供单个有效ID");
        PlnProductDemandEntity demand = getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, demandId).last("FOR UPDATE"));
        if (demand == null) throw new CheckedException("产品需求不存在：" + demandId);
        requireDraftEditable(demand);
        if (StrUtil.isNotBlank(demand.getSourceType()) || demand.getSourceId() != null) {
            throw new CheckedException("来源业务需求不能直接删除");
        }
        return super.removeById(demandId);
    }

    private void requireDraftEditable(PlnProductDemandEntity demand) {
        String currentFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(currentFactory) && !Objects.equals(currentFactory, demand.getFactoryCode())) {
            throw new CheckedException("当前工厂无权变更该产品需求");
        }
        if (!PlanStatusEnum.CANCEL.getCode().equals(demand.getPlanState())
                || optional(demand.getCompleteNum()).signum() != 0) {
            throw new CheckedException("产品需求已接收、生成MRP、撤回或存在完成事实，不能直接修改或删除");
        }
        requireNoDownstreamFacts(demand);
    }

    private void requireNoDownstreamFacts(PlnProductDemandEntity demand) {
        String demandNo = demand.getDemandNo();
        if (StrUtil.isBlank(demandNo)
                || this.count(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getDemandNo, demandNo)
                .ne(PlnProductDemandEntity::getId, demand.getId())) != 0
                || planMatMrpService.count(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demandNo)) != 0
                || inventoryPeggingService.count(new LambdaQueryWrapper<PlanMrpInventoryPeggingEntity>()
                .eq(PlanMrpInventoryPeggingEntity::getPlanDemandNo, demandNo)) != 0) {
            throw new CheckedException("产品需求已关联MRP或库存Pegging，不能直接修改或删除");
        }
        requireNoExecutionFacts(demand);
    }

    private void requireNoExecutionFacts(PlnProductDemandEntity demand) {
        String demandNo = demand.getDemandNo();
        List<PlnIssueEntity> issues = plnIssueService.list(new LambdaQueryWrapper<PlnIssueEntity>()
                .and(query -> query.eq(PlnIssueEntity::getSourceDemandNo, demandNo)
                        .or(legacy -> legacy.isNull(PlnIssueEntity::getSourceDemandNo)
                                .eq(PlnIssueEntity::getIssueNo, demandNo))));
        if (issues.stream().anyMatch(row -> !("4".equals(row.getStatus())
                && optional(row.getFinishNum()).signum() == 0) && !pristineMpsRoot(row))) {
            throw new CheckedException("产品需求已关联生产计划或APS，不能直接修改或重算");
        }
        Set<String> issueNos = issues.stream().map(PlnIssueEntity::getIssueNo)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        issueNos.add(demandNo);
        List<PlnSchedulingBatchAllocationEntity> allocations = batchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .in(PlnSchedulingBatchAllocationEntity::getIssueNo, issueNos));
        for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
            PlnSchedulingBatchEntity batch = schedulingBatchMapper.selectById(allocation.getBatchId());
            if (batch == null || !"WITHDRAWN".equals(batch.getBatchStatus()) || Boolean.TRUE.equals(batch.getLockStatus())) {
                throw new CheckedException("产品需求仍有关联的活动APS批次，不能修改或重算");
            }
        }
        List<PlnSchedulingTaskDetailEntity> taskDetails = schedulingTaskDetailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .in(PlnSchedulingTaskDetailEntity::getIssueNo, issueNos));
        if (taskDetails.stream().anyMatch(row -> !"WITHDRAWN".equals(row.getOperationScheduleStatus())
                || Boolean.TRUE.equals(row.getLockStatus()))) {
            throw new CheckedException("产品需求仍有关联的活动APS任务明细，不能修改或重算");
        }
        List<PlnSchedulingOperationEntity> operations = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .in(PlnSchedulingOperationEntity::getIssueNo, issueNos));
        if (operations.stream().anyMatch(row -> !"WITHDRAWN".equals(row.getScheduleStatus())
                || Boolean.TRUE.equals(row.getLockStatus()))) {
            throw new CheckedException("产品需求仍有关联的活动APS工序占用，不能修改或重算");
        }
        for (String issueNo : issueNos) {
            R<List<MesTaskEntity>> mes = remoteMesTaskService.omsLife(issueNo);
            if (mes == null || !mes.isOk() || mes.getData() == null) {
                throw new CheckedException("MES来源事实不可核验，禁止修改或重算产品需求");
            }
            if (mes.getData().stream().anyMatch(row -> !"CLOSE".equals(row.getStatus())
                    || row.getStartTime() != null || row.getFinishTime() != null
                    || optional(row.getCompleteNum()).signum() != 0
                    || optional(row.getInStockNum()).signum() != 0
                    || optional(row.getUnInStockNum()).signum() != 0
                    || optional(row.getUnPassNum()).signum() != 0)) {
                throw new CheckedException("产品需求已关联MES工单，不能修改或重算");
            }
        }
    }

    /**
     * MPS发布先形成独立桶根计划，再以同一活动桶执行MRP。只有尚未排产、下达或产生任何
     * 执行/质量事实的根计划可穿过首次MRP门禁；不能把这一白名单扩展到人工计划或下层供应。
     */
    private boolean pristineMpsRoot(PlnIssueEntity issue) {
        return issue.getMpsLineId() != null && issue.getSourceMrpId() == null
                && "issue".equals(issue.getType()) && "0".equals(issue.getStatus())
                && !Boolean.TRUE.equals(issue.getLockStatus()) && issue.getLockTaskId() == null
                && issue.getMesTaskId() == null && StrUtil.isAllBlank(issue.getMesIssueRequestId(),
                issue.getMesIssueFingerprint(), issue.getMesTaskCode())
                && optional(issue.getFinishNum()).signum() == 0
                && optional(issue.getUnqualifiedNum()).signum() == 0
                && optional(issue.getQualityIsolatedNum()).signum() == 0
                && optional(issue.getQualityReleasedNum()).signum() == 0
                && optional(issue.getQualityRejectedNum()).signum() == 0
                && optional(issue.getScrapNum()).signum() == 0
                && optional(issue.getReworkNum()).signum() == 0
                && optional(issue.getReworkCompletedNum()).signum() == 0
                && optional(issue.getReworkQualifiedNum()).signum() == 0
                && optional(issue.getReworkRejectedNum()).signum() == 0;
    }

    private List<PlanMatMrpEntity> requireDraftMrpRecalculable(PlnProductDemandEntity demand) {
        List<PlanMatMrpEntity> rows = planMatMrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demand.getDemandNo())
                .orderByAsc(PlanMatMrpEntity::getId));
        if (rows.isEmpty()) throw new CheckedException("MRP状态需求缺少可核验的MRP草案");
        Set<Long> ids = new HashSet<>();
        for (PlanMatMrpEntity row : rows) {
            ids.add(row.getId());
            boolean safeDraft = Boolean.FALSE.equals(row.getStatus())
                    && optional(row.getCompleteNum()).signum() == 0
                    && Objects.equals(row.getFactoryCode(), demand.getFactoryCode())
                    && Objects.equals(row.getDemandWarehouseCode(), demand.getDemandWarehouseCode())
                    && row.getMpsVersionId() == null && row.getMpsLineId() == null
                    && StrUtil.isAllBlank(row.getSupplySourceType(), row.getSupplySourceNo(),
                    row.getSupplySourceStatus(), row.getSupplyWithdrawalRequestId(),
                    row.getMainMaterialReservationNo(), row.getMainMaterialReservationStatus(),
                    row.getMainMaterialReservationSnapshot(), row.getMaterialReservationNo(),
                    row.getMaterialReservationStatus(), row.getMaterialReservationSnapshot(),
                    row.getPurchaseRequestId(), row.getPurchaseOrderNo(), row.getPurchaseOrderStatus(),
                    row.getSubstituteAllocation(), row.getSubstituteDecisionRequestId(), row.getSubstituteCancelRequestId())
                    && row.getSupplySourceId() == null && row.getPurchaseOrderId() == null
                    && row.getPurchaseOrderDetailId() == null && row.getMainMaterialReservationTime() == null
                    && row.getMaterialReservationTime() == null && row.getSupplyWithdrawalTime() == null
                    && optional(row.getPurchaseOrderNum()).signum() == 0
                    && !Set.of("APPROVED", "PARTIAL_APPROVED").contains(
                    StrUtil.blankToDefault(row.getSubstituteStatus(), "NONE"))
                    && Set.of("NONE", "PLANNED").contains(
                    StrUtil.blankToDefault(row.getInventoryPeggingStatus(), "NONE"));
            boolean hasSupplyIdentity = row.getSupplySourceId() != null
                    || StrUtil.isNotBlank(row.getSupplySourceType()) || StrUtil.isNotBlank(row.getSupplySourceNo());
            boolean supplyClosed = !hasSupplyIdentity
                    || row.getSupplySourceId() != null && StrUtil.isNotBlank(row.getSupplySourceType())
                    && StrUtil.isNotBlank(row.getSupplySourceNo()) && "WITHDRAWN".equals(row.getSupplySourceStatus())
                    && StrUtil.isNotBlank(row.getSupplyWithdrawalRequestId()) && row.getSupplyWithdrawalTime() != null;
            boolean reservationsClosed = (StrUtil.isBlank(row.getMainMaterialReservationNo())
                    || "RELEASED".equals(row.getMainMaterialReservationStatus()))
                    && (StrUtil.isBlank(row.getMaterialReservationNo())
                    || "RELEASED".equals(row.getMaterialReservationStatus()));
            boolean safeCascadeWithdrawal = Boolean.FALSE.equals(row.getStatus())
                    && optional(row.getCompleteNum()).signum() == 0
                    && Objects.equals(row.getFactoryCode(), demand.getFactoryCode())
                    && Objects.equals(row.getDemandWarehouseCode(), demand.getDemandWarehouseCode())
                    && row.getMpsVersionId() == null && row.getMpsLineId() == null
                    && supplyClosed && reservationsClosed
                    && row.getPurchaseOrderId() == null && row.getPurchaseOrderDetailId() == null
                    && StrUtil.isAllBlank(row.getPurchaseRequestId(), row.getPurchaseOrderNo(), row.getPurchaseOrderStatus())
                    && optional(row.getPurchaseOrderNum()).signum() == 0
                    && Set.of("NONE", "RELEASED").contains(
                    StrUtil.blankToDefault(row.getInventoryPeggingStatus(), "NONE"));
            if (!safeDraft && !safeCascadeWithdrawal) {
                throw new CheckedException("MRP已有未收口的MPS、下达、预留、采购或供应事实，须先完成级联撤回");
            }
        }
        List<PlanMrpInventoryPeggingEntity> peggings = inventoryPeggingService.list(
                new LambdaQueryWrapper<PlanMrpInventoryPeggingEntity>()
                        .eq(PlanMrpInventoryPeggingEntity::getPlanDemandNo, demand.getDemandNo())
                        .orderByAsc(PlanMrpInventoryPeggingEntity::getId));
        for (PlanMrpInventoryPeggingEntity row : peggings) {
            if (!ids.contains(row.getMrpId()) || !Objects.equals(row.getFactoryCode(), demand.getFactoryCode())
                    || !Objects.equals(row.getWarehouseCode(), demand.getDemandWarehouseCode())
                    || StrUtil.isNotBlank(row.getReservationNo()) && !"RELEASED".equals(row.getStatus())
                    || row.getReservationTime() != null && !"RELEASED".equals(row.getStatus())
                    || row.getSourceStockId() == null || optional(row.getPeggedNum()).signum() <= 0
                    || StrUtil.isBlank(row.getEvidenceFingerprint())
                    || !Set.of("PLANNED", "RELEASED").contains(row.getStatus())) {
                throw new CheckedException("MRP库存Pegging已预留、消费、孤立或状态异常，须走级联撤回");
            }
        }
        for (PlanMatMrpEntity row : rows) {
            BigDecimal pegged = peggings.stream().filter(line -> Objects.equals(line.getMrpId(), row.getId()))
                    .map(line -> optional(line.getPeggedNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (pegged.compareTo(optional(row.getInventoryPeggedNum())) != 0
                    || pegged.compareTo(optional(row.getStockAllocatedNum())) != 0) {
                throw new CheckedException("MRP库存Pegging数量不守恒，禁止覆盖旧计算结果");
            }
        }
        return rows;
    }

    private void rejectImmutableChange(Object offered, Object actual, String label) {
        boolean equal = offered instanceof BigDecimal left && actual instanceof BigDecimal right
                ? left.compareTo(right) == 0
                : offered instanceof String text && StrUtil.isBlank(text) && (actual == null || StrUtil.isBlank(actual.toString()))
                || Objects.equals(offered, actual);
        if (offered != null && !equal) {
            throw new CheckedException("产品需求" + label + "属于来源或执行事实，不允许通过编辑接口改写");
        }
    }

    private boolean sameOmsDemand(PlnProductDemandEntity existing, PlnProductDemandEntity command) {
        return StrUtil.isNotBlank(existing.getSaleOrderCode())
                && Objects.equals(existing.getSaleOrderCode(), command.getSaleOrderCode())
                && Objects.equals(existing.getSaleOrderCodeDetails(), command.getSaleOrderCodeDetails())
                && Objects.equals(existing.getCustomerCode(), command.getCustomerCode())
                && Objects.equals(existing.getCustomerName(), command.getCustomerName())
                && Objects.equals(existing.getProductCode(), command.getProductCode())
                && Objects.equals(existing.getProductName(), command.getProductName())
                && Objects.equals(existing.getSpecification(), command.getSpecification())
                && Objects.equals(existing.getProductUnit(), command.getProductUnit())
                && Objects.equals(existing.getPlanEndDate(), command.getPlanEndDate())
                && existing.getNeedNum() != null && command.getNeedNum() != null
                && existing.getNeedNum().compareTo(command.getNeedNum()) == 0;
    }

    /**
     * 通过条件查询需求日期
     *
     * @param templateQueryDTO 查询条件
     */
    @Override
    public List<Map<String, Object>> getDemandDate(TemporaryPlanQueryDTO templateQueryDTO) {
        LambdaQueryWrapper<PlnProductDemandEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(PlnProductDemandEntity::getPlanState, PlanStatusEnum.RECEIVE.getCode());
        wrapper.in(ObjUtil.isNotEmpty(templateQueryDTO.getMatCodes()), PlnProductDemandEntity::getProductCode, templateQueryDTO.getMatCodes());
        wrapper.like(ObjUtil.isNotEmpty(templateQueryDTO.getMatName()), PlnProductDemandEntity::getProductName, templateQueryDTO.getMatName());
        wrapper.like(ObjUtil.isNotEmpty(templateQueryDTO.getCustomerCode()), PlnProductDemandEntity::getCustomerCode, templateQueryDTO.getCustomerCode());
        if (templateQueryDTO.getPlanNum() == null) {
            templateQueryDTO.setPlanNum(1);
        }
        LocalDate enTime = templateQueryDTO.getTime().plusDays(templateQueryDTO.getPlanNum() - 1);
        wrapper.between(PlnProductDemandEntity::getPlanEndDate, templateQueryDTO.getTime(), enTime);
        wrapper.groupBy(PlnProductDemandEntity::getProductCode, PlnProductDemandEntity::getPlanEndDate);
        List<PlnTemporaryDemandDateVO> demandDate = baseMapper.getDemandDate(wrapper);
        // 转换数据结构
        List<Map<String, Object>> resultList = demandDate.stream().collect(Collectors.groupingBy(
                PlnTemporaryDemandDateVO::getProductCode,
                Collectors.toMap(
                        PlnTemporaryDemandDateVO::getMonthDay,
                        vo -> vo.getNum().toString()
                )
        )).entrySet().stream().map(entry -> {
            Map<String, Object> map = new HashMap<>();
            map.put("productCode", entry.getKey());
            map.put("date", entry.getValue());
            return map;
        }).collect(Collectors.toList());
        return resultList;
    }

    /**
     * 导入客户订单excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @Override
    public R importExcel(List<PlnProductDemandExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 主数据-客户map
        Map<String, MdmCustomerEntity> customerMap = remoteCustomerService.getList().getData()
                .stream()
                .collect(Collectors.toMap(MdmCustomerEntity::getCustomerCode, Function.identity(),
                        (a, b) -> a));
        // 主数据-物料map
        Map<String, MdmMaterialEntity> materialMap = remoteMaterialService.getList().getData()
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(),
                        (a, b) -> a));
        Map<String, MdmWarehouseEntity> warehouseMap = Optional.ofNullable(
                        remoteWarehouseService.getList(new MdmWarehouseEntity()).getData()).orElse(List.of()).stream()
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .collect(Collectors.toMap(MdmWarehouseEntity::getWarehouseCode, Function.identity(), (a, b) -> a));
        List<PlnProductDemandEntity> insertList = new ArrayList<>();
        for (PlnProductDemandExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            MdmCustomerEntity mdmCustomerEntity = customerMap.get(excelVO.getCustomerCode());
            if (mdmCustomerEntity == null) {
                errorMsg.add(excelVO.getCustomerCode() + "客户编码不存在");
            }
            MdmMaterialEntity mdmMaterialEntity = materialMap.get(excelVO.getProductCode());
            if (mdmMaterialEntity == null) {
                errorMsg.add(excelVO.getProductCode() + "物料编码不存在");
            }
            MdmWarehouseEntity warehouse = warehouseMap.get(excelVO.getDemandWarehouseCode());
            if (warehouse == null) {
                errorMsg.add(StrUtil.blankToDefault(excelVO.getDemandWarehouseCode(), "未填写") + "需求领料仓库不存在或未启用");
            }
            if (excelVO.getMrpPriority() != null
                    && (excelVO.getMrpPriority() < 1 || excelVO.getMrpPriority() > 999)) {
                errorMsg.add("MRP优先级必须在1到999之间");
            }
            if (CollUtil.isEmpty(errorMsg)) {
                insertList.add(insertExcel(excelVO, mdmCustomerEntity, mdmMaterialEntity, warehouse));
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "物料需求导入成功");
    }

    /**
     * mrp运算
     *
     * @param ids 计划ids
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R mrp(Long[] ids) {
        return runMrp(ids, null);
    }

    private R runMrp(Long[] ids, Long allowedOmsChangeVersionId) {
        if (ids == null || ids.length == 0 || Arrays.stream(ids).anyMatch(Objects::isNull)) {
            throw new CheckedException("MRP运算必须提供有效产品需求ID");
        }
        MdmFactoryPlanningPolicyDTO planningPolicy = formalPlanningGate.requireMomMrp(
                FactoryContextHolder.getFactoryCode());
        // 只为分阶段补产增加发布互斥。其他来源的受控变更可能已经持有需求锁，
        // 不能在这些旧事务中追加反向的“需求→发布”锁序。
        if (listByIds(Arrays.stream(ids).distinct().toList()).stream().anyMatch(supplementPlanning::isStaged)) {
            publicationLock.acquire(planningPolicy.getFactoryCode());
        }
        // 与需求变更复核共用行锁；同一批次按ID稳定取锁，避免MRP读到复核前旧数量。
        List<PlnProductDemandEntity> plnProductDemandEntities = new ArrayList<>();
        for (Long id : Arrays.stream(ids).distinct().sorted().toList()) {
            PlnProductDemandEntity demand = this.getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                    .eq(PlnProductDemandEntity::getId, id).last("FOR UPDATE"));
            if (demand == null) throw new CheckedException("MRP目标产品需求不存在：" + id);
            if (demandChangeMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                    .eq(PlnProductDemandChangeEntity::getDemandId, id)
                    .eq(PlnProductDemandChangeEntity::getStatus, "SUBMITTED")) != 0) {
                throw new CheckedException("产品需求[" + demand.getDemandNo() + "]有待复核变更，请先复核或取消后再运算MRP");
            }
            if (StrUtil.isNotBlank(demand.getSaleOrderCode())) {
                List<OmsOrderChangeVersionEntity> pending = omsOrderChangeVersionMapper.selectList(
                        new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                                .eq(OmsOrderChangeVersionEntity::getOrderCode, demand.getSaleOrderCode())
                                .eq(OmsOrderChangeVersionEntity::getFactoryCode, demand.getFactoryCode())
                                .eq(OmsOrderChangeVersionEntity::getStatus, "SUBMITTED"));
                boolean allowed = allowedOmsChangeVersionId != null && pending.size() == 1
                        && Objects.equals(pending.get(0).getId(), allowedOmsChangeVersionId);
                if (!pending.isEmpty() && !allowed) {
                    throw new CheckedException("OMS订单[" + demand.getSaleOrderCode()
                            + "]有待复核版本，禁止在评审外重算MRP");
                }
            }
            plnProductDemandEntities.add(demand);
        }
        for (PlnProductDemandEntity demand : plnProductDemandEntities) {
            if (!PlanStatusEnum.RECEIVE.getCode().equals(demand.getPlanState())) {
                throw new CheckedException("产品需求[" + demand.getDemandNo() + "]不是已接收状态，不能运算MRP");
            }
            supplementPlanning.validateForMrp(demand);
            requireNoExecutionFacts(demand);
            applyDemandWarehouse(demand, true);
            demand.setMrpPriority(normalizeMrpPriority(demand.getMrpPriority()));
        }
        updateBatchById(plnProductDemandEntities);
        // MPS按独立时间桶展开本次MRP上下文，不回写客户原始需求交期和全量数量。
        mpsService.lockPoliciesForMrp(plnProductDemandEntities);
        List<PlnProductDemandEntity> planningDemands = new ArrayList<>();
        Map<PlnProductDemandEntity, PlnMpsLineEntity> mpsLines = new IdentityHashMap<>();
        Map<Long, List<PlnMpsLineEntity>> sourceBuckets = new HashMap<>();
        for (PlnProductDemandEntity source : plnProductDemandEntities) {
            List<PlnMpsLineEntity> buckets = mpsService.forMrp(source);
            sourceBuckets.put(source.getId(), buckets);
            if (buckets.isEmpty()) {
                planningDemands.add(source);
                continue;
            }
            for (PlnMpsLineEntity bucket : buckets) {
                PlnProductDemandEntity planning = new PlnProductDemandEntity();
                BeanUtil.copyProperties(source, planning);
                planning.setNeedNum(bucket.getPlanQuantity());
                planning.setPlanEndDate(bucket.getPlannedDate());
                planning.setMrpPriority(bucket.getMrpPriority());
                planningDemands.add(planning);
                mpsLines.put(planning, bucket);
            }
        }
        planningDemands.sort(Comparator
                .comparing(PlnProductDemandEntity::getPlanEndDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PlnProductDemandEntity::getMrpPriority,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PlnProductDemandEntity::getDemandNo)
                .thenComparing(item -> Optional.ofNullable(mpsLines.get(item))
                        .map(PlnMpsLineEntity::getBucketNo).orElse(1)));
        planMatMrpService.lockInventoryAllocationPools(planningDemands.stream()
                .map(item -> StrUtil.blankToDefault(item.getFactoryCode(), "_") + "\u0001"
                        + StrUtil.blankToDefault(item.getDemandWarehouseCode(), "_"))
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        List<String> demandNos = plnProductDemandEntities.stream()
                .map(PlnProductDemandEntity::getDemandNo).distinct().toList();
        List<PlanMatMrpEntity> existingMrp = planMatMrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .in(PlanMatMrpEntity::getPlanDemandNo, demandNos));
        for (PlnProductDemandEntity demand : plnProductDemandEntities) {
            if (existingMrp.stream().anyMatch(row -> Objects.equals(row.getPlanDemandNo(), demand.getDemandNo()))) {
                requireDraftMrpRecalculable(demand);
            }
        }
        if (CollUtil.isNotEmpty(existingMrp)) {
            inventoryPeggingService.remove(new LambdaQueryWrapper<PlanMrpInventoryPeggingEntity>()
                    .in(PlanMrpInventoryPeggingEntity::getMrpId,
                            existingMrp.stream().map(PlanMatMrpEntity::getId).toList()));
            planMatMrpService.removeByIds(existingMrp.stream().map(PlanMatMrpEntity::getId).toList());
        }
        // 去重获取产品编码列表
        List<String> productCodes = planningDemands.stream().map(PlnProductDemandEntity::getProductCode)
                .distinct().collect(Collectors.toList());
        // 查询bom
        Map<String, MdmBomChildDTO> productBomMap = remoteBomService.getAllChildMaterials(productCodes).getData();
        // 以Map形式存储扁平化的数据
        Map<String, List<MdmBomChildDTO>> bomDataMap = new HashMap<>();
        for (MdmBomChildDTO bomChild : productBomMap.values()) {
            List<MdmBomChildDTO> bomDataNew = new ArrayList<>();
            // 获取当前节点的子节点并递归扁平化
            List<MdmBomChildDTO> children = bomChild.getChildren();
            if (children != null && !children.isEmpty()) {
                flattenBomData(children, bomDataNew);
            }
            // 将当前节点本身也加入到扁平化结果中
            bomDataNew.add(bomChild);
            bomDataMap.put(bomChild.getMaterialCode(), bomDataNew);
        }
        Set<String> materialCodeSet = bomDataMap.values().stream() // 获取所有值（List<MdmBomChildDTO>）
                .flatMap(List::stream) // 将每个 List<MdmBomChildDTO> 扁平化为单个流
                .map(MdmBomChildDTO::getMaterialCode) // 提取 materialCode
                .filter(Objects::nonNull) // 过滤掉可能为 null 的值
                .collect(Collectors.toCollection(LinkedHashSet::new));
        bomDataMap.values().stream().flatMap(List::stream)
                .forEach(child -> materialCodeSet.addAll(getSubstituteCodes(child)));
        // 查询物料信息
        Map<String, MdmMaterialEntity> materialMap = remoteMaterialService.getListByCodes(new ArrayList<>(materialCodeSet)).getData().stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (a, b) -> a));
        // 按需求领料仓查询库存；不同仓库绝不进入同一个MRP分配池。
        Set<String> demandWarehouseCodes = planningDemands.stream()
                .map(PlnProductDemandEntity::getDemandWarehouseCode).collect(Collectors.toCollection(LinkedHashSet::new));
        List<WmsLiveStockEntity> liveStocks = new ArrayList<>();
        for (String warehouseCode : demandWarehouseCodes) {
            WmsLiveStockByProductCodeDto liveStock = new WmsLiveStockByProductCodeDto();
            liveStock.setProductCodeSet(materialCodeSet);
            liveStock.setWarehouseCode(warehouseCode);
            R<List<WmsLiveStockEntity>> response = remoteLiveStockService.getLiveStockByProductCodeList(liveStock);
            if (response == null || !response.isOk() || response.getData() == null) {
                throw new CheckedException("无法取得需求仓库[" + warehouseCode + "]实时库存，MRP已故障关闭");
            }
            if (response.getData().stream().filter(Objects::nonNull)
                    .anyMatch(stock -> stock.getPlanAvailableNum() == null)) {
                throw new CheckedException("需求仓库[" + warehouseCode
                        + "]未返回扣除活动预留后的计划可用量，请先同步部署WMS库存接口");
            }
            response.getData().stream().filter(this::isGenerallyUsableStock).forEach(liveStocks::add);
        }
        Map<String, List<WmsLiveStockEntity>> stockRowsByMaterial = liveStocks.stream()
                .collect(Collectors.groupingBy(stock -> stockPoolKey(stock.getWarehouseCode(), stock.getMatCode())));
        Map<String, BigDecimal> physicalStockMap = liveStocks.stream()
                .collect(Collectors.groupingBy(
                        stock -> stockPoolKey(stock.getWarehouseCode(), stock.getMatCode()),
                        Collectors.reducing(BigDecimal.ZERO,
                                entity -> entity.getMatNum() == null ? BigDecimal.ZERO : BigDecimal.valueOf(entity.getMatNum()),
                                BigDecimal::add)
                ));
        Map<String, BigDecimal> unreservedStockMap = liveStocks.stream()
                .collect(Collectors.groupingBy(
                        stock -> stockPoolKey(stock.getWarehouseCode(), stock.getMatCode()),
                        Collectors.reducing(BigDecimal.ZERO,
                                entity -> optional(entity.getPlanAvailableNum()), BigDecimal::add)));
        Map<String, BigDecimal> usableStockMap = new HashMap<>();
        for (String warehouseCode : demandWarehouseCodes) {
            for (String code : materialCodeSet) {
                BigDecimal safetyStock = Optional.ofNullable(materialMap.get(code))
                        .map(MdmMaterialEntity::getMinStock).orElse(BigDecimal.ZERO);
                String poolKey = stockPoolKey(warehouseCode, code);
                usableStockMap.put(poolKey, unreservedStockMap.getOrDefault(poolKey, BigDecimal.ZERO)
                        .subtract(safetyStock).max(BigDecimal.ZERO));
            }
        }
        Map<String, List<InventoryRowBalance>> inventoryRowsByPool = buildInventoryRows(liveStocks);
        reserveExistingPlanAllocations(usableStockMap, inventoryRowsByPool);
        String mrpRunNo = "MRP-" + UUID.randomUUID();
        AtomicInteger stockAllocationSequence = new AtomicInteger(0);
        // 创建列表
        List<PlanMatMrpEntity> completableFutures = new ArrayList<>();
        // 创建错误列表
        List<String> errorList = new ArrayList<>();
        // 提交任务
        int demandAllocationSequence = 0;
        for (PlnProductDemandEntity plnProductDemandEntity : planningDemands) {
            demandAllocationSequence++;
            // 计算MRP数据
            AtomicReference<String> errorMsg = new AtomicReference<>("");
            List<PlanMatMrpEntity> planMatMrpEntities = calculateMrpData(plnProductDemandEntity,
                    bomDataMap.get(plnProductDemandEntity.getProductCode()), materialMap, usableStockMap,
                    inventoryRowsByPool, mrpRunNo, demandAllocationSequence, stockAllocationSequence, errorMsg);
            if (StrUtil.isNotEmpty(errorMsg.get())) {
                errorList.add(errorMsg.get());
                continue;
            }
            // 收集结果
            if (!planMatMrpEntities.isEmpty()) {
                planMatMrpEntities.forEach(planMatMrpEntity -> {
                    planMatMrpEntity.setFactoryCode(plnProductDemandEntity.getFactoryCode());
                    PlnMpsLineEntity mpsLine = mpsLines.get(plnProductDemandEntity);
                    if (mpsLine != null) {
                        planMatMrpEntity.setMpsLineId(mpsLine.getId());
                        planMatMrpEntity.setMpsVersionId(mpsLine.getVersionId());
                        planMatMrpEntity.setMpsBucketNo(mpsLine.getBucketNo());
                        planMatMrpEntity.setPlanningSourceKey(mpsLine.getBucketKey());
                    } else {
                        planMatMrpEntity.setPlanningSourceKey("DEMAND-" + plnProductDemandEntity.getId());
                    }
                });
                completableFutures.addAll(planMatMrpEntities);
            }
        }
        // 检查是否有错误
        if (!errorList.isEmpty()) {
            // 如果有错误，抛出异常
            throw new CheckedException(String.join("; ", errorList));
        }
        // 如果没有MRP实体，直接返回
        if (completableFutures.isEmpty()) {
            if (plnProductDemandEntities.stream().anyMatch(supplementPlanning::isStaged))
                throw new CheckedException("补产未形成可下达MRP结果，请检查BOM和物料属性");
            formalPlanningGate.revalidate(planningPolicy);
            return R.ok();
        }
        applySubstituteSuggestions(completableFutures, usableStockMap, physicalStockMap,
                stockRowsByMaterial, materialMap);
        // 更新计划状态
        this.update(new LambdaUpdateWrapper<PlnProductDemandEntity>()
                .set(PlnProductDemandEntity::getPlanState, PlanStatusEnum.MRP.getCode())
                .in(PlnProductDemandEntity::getId, Arrays.asList(ids)));
        // 保存MRP数据
        planMatMrpService.saveBatch(completableFutures);
        List<PlanMrpInventoryPeggingEntity> peggingLines = completableFutures.stream()
                .flatMap(mrp -> Optional.ofNullable(mrp.getInventoryPeggingDetails()).orElse(List.of()).stream()
                        .peek(pegging -> {
                            pegging.setMrpId(mrp.getId());
                            pegging.setFactoryCode(mrp.getFactoryCode());
                        }))
                .toList();
        if (!peggingLines.isEmpty()) {
            inventoryPeggingService.saveBatch(peggingLines);
        }
        formalPlanningGate.revalidate(planningPolicy);
        formalPlanContractService.publishMomMrpContract(mrpRunNo, "MOM-MRP-CONTRACT-" + mrpRunNo);
        for (var demand : plnProductDemandEntities) {
            supplementPlanning.onMrpReady(demand, completableFutures.stream()
                            .filter(row -> Objects.equals(row.getPlanDemandNo(), demand.getDemandNo())).toList(),
                    sourceBuckets.getOrDefault(demand.getId(), List.of()));
        }
        return R.ok();
    }

    /**
     * 计算MRP数据，不进行数据库操作
     *
     * @param plnProductDemandEntity 计划订单实体
     * @param bomData                扁平化后的bom
     * @param materialMap            物料map
     * @param usableStockMap         扣除安全库存后、在本次运算中可分配的共享库存
     * @param errorMsg               错误信息
     * @return 包含MRP计算结果的Map
     */
    private List<PlanMatMrpEntity> calculateMrpData(PlnProductDemandEntity plnProductDemandEntity, List<MdmBomChildDTO> bomData,
                                                    Map<String, MdmMaterialEntity> materialMap, Map<String, BigDecimal> usableStockMap,
                                                    Map<String, List<InventoryRowBalance>> inventoryRowsByPool,
                                                    String mrpRunNo, int demandAllocationSequence,
                                                    AtomicInteger stockAllocationSequence,
                                                    AtomicReference<String> errorMsg) {
        if (CollUtil.isEmpty(bomData) || bomData.size() == 1) {
            errorMsg.set(String.format("客户订单号【%s】产品名称【%s】产品编码【%s】没有BOM信息",
                    plnProductDemandEntity.getDemandNo(), plnProductDemandEntity.getProductName(), plnProductDemandEntity.getProductCode()));
            return new ArrayList<>();
        }
        // 提取所有物料编码
        Set<String> allMaterialCodes = bomData.stream().map(MdmBomChildDTO::getMaterialCode)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        allMaterialCodes.add(plnProductDemandEntity.getProductCode());

        // 从materialMap和liveStockMap中获取与allMaterialCodes对应的数据组建物料基本信息
        List<MatMrpDTO> matMrpDTOList = new ArrayList<>();
        // 根据allMaterialCodes组装物料和库存数据到MatMrpDTO对象中
        for (String materialCode : allMaterialCodes) {
            if (materialMap.containsKey(materialCode)) {
                MatMrpDTO matMrpDTO = new MatMrpDTO();
                // 设置物料信息
                MdmMaterialEntity material = materialMap.get(materialCode);
                matMrpDTO.setMaterialCode(material.getMaterialCode());
                matMrpDTO.setMaterialName(material.getMaterialName());
                matMrpDTO.setMaterialAttribute(material.getMaterialAttribute());
                matMrpDTO.setBasicUnit(material.getBasicUnit());
                matMrpDTO.setSpecificationModel(material.getSpecificationModel());
                matMrpDTO.setFixedLeadTime(material.getFixedLeadTime() != null ? material.getFixedLeadTime() : new BigDecimal(0));
                matMrpDTO.setIssuanceBatch(material.getIssuanceBatch() != null ? material.getIssuanceBatch() : new BigDecimal(1));
                matMrpDTO.setMinOrderQuantity(material.getMinOrderQuantity() != null ? material.getMinOrderQuantity() : 1);
                matMrpDTO.setMinStock(material.getMinStock() != null ? material.getMinStock() : new BigDecimal(0));
                // 设置库存信息
//                matMrpDTO.setStock(liveStockMap.getOrDefault(materialCode, 0.0));
                // 添加到列表
                matMrpDTOList.add(matMrpDTO);
            } else {
                errorMsg.set(String.format("未查询到客户订单号【%s】产品【%s】bom中物料编码【%s】对应的物料信息",
                        plnProductDemandEntity.getDemandNo(), plnProductDemandEntity.getProductName(), materialCode));
                return new ArrayList<>();
            }
        }
        // 计算MRP数据
        Map<String, PlanMatMrpEntity> grossDemand = new LinkedHashMap<>();
        Map<String, List<PlanBomRequirementPathDTO>> pathsByMaterial = new LinkedHashMap<>();
        Map<String, List<PlanMrpInventoryPeggingEntity>> peggingByMaterial = new LinkedHashMap<>();
        // 获取产品编码和需求数量
        BigDecimal needNum = plnProductDemandEntity.getNeedNum();
        LocalDate planEndDate = plnProductDemandEntity.getPlanEndDate();
        MatMrpDTO rootMaterial = matMrpDTOList.stream()
                .filter(item -> Objects.equals(item.getMaterialCode(), plnProductDemandEntity.getProductCode()))
                .findFirst().orElseThrow(() -> new CheckedException("产品物料主数据缺失"));
        LocalDate rootMaterialNeedDate = planEndDate.minusDays(rootMaterial.getFixedLeadTime().intValue());
        // 逐层先净算父项，再仅按父项未覆盖数量展开自制子BOM，避免中间件已有库存时仍重复生成下层需求。
        Long rootBomId = bomData.stream()
                .filter(item -> Objects.equals(item.getMaterialCode(), plnProductDemandEntity.getProductCode()))
                .filter(item -> item.getSubitemBomId() == null || item.getSubitemBomId() == 0L)
                .map(MdmBomChildDTO::getBomId).filter(Objects::nonNull).findFirst()
                .orElseGet(() -> bomData.stream().map(MdmBomChildDTO::getBomId)
                        .filter(Objects::nonNull).findFirst().orElse(null));
        if (rootBomId == null) throw new CheckedException("产品BOM根节点缺少有效BOM ID");
        explodeBOM(plnProductDemandEntity, rootBomId, plnProductDemandEntity.getProductCode(),
                "ROOT:" + plnProductDemandEntity.getProductCode(), needNum, rootMaterialNeedDate,
                grossDemand, pathsByMaterial, bomData, matMrpDTOList, usableStockMap, 1,
                inventoryRowsByPool, peggingByMaterial, mrpRunNo, demandAllocationSequence,
                stockAllocationSequence, new LinkedHashSet<>());
        // 计算净需求、时间分段、批量调整和生成计划订单
        List<PlanMatMrpEntity> matMrpList = new ArrayList<>();
        for (Map.Entry<String, PlanMatMrpEntity> entry : grossDemand.entrySet()) {
            PlanMatMrpEntity mrpEntity = entry.getValue();
            if (mrpEntity != null) {
                BigDecimal gross = optional(mrpEntity.getGrossDemandNum());
                BigDecimal allocatedStock = optional(mrpEntity.getStockAllocatedNum());
                BigDecimal net = optional(mrpEntity.getNetDemandNum());
                mrpEntity.setGrossDemandNum(gross);
                mrpEntity.setStockAllocatedNum(allocatedStock);
                mrpEntity.setNetDemandNum(net);
                mrpEntity.setRemainingDemandNum(net);
                mrpEntity.setReadinessStatus(net.compareTo(BigDecimal.ZERO) == 0
                        ? MrpReadinessStatusEnum.READY.getCode() : MrpReadinessStatusEnum.SHORTAGE.getCode());
                if (!MrpSubstituteStatusEnum.POLICY_CONFLICT.getCode().equals(mrpEntity.getSubstituteStatus())) {
                    mrpEntity.setSubstituteStatus(MrpSubstituteStatusEnum.NONE.getCode());
                }
                mrpEntity.setNum(adjustLotSize(mrpEntity, net));
                mrpEntity.setStatus(false);
                mrpEntity.setPlanDemandNo(plnProductDemandEntity.getDemandNo());
                mrpEntity.setSaleOrderCode(plnProductDemandEntity.getSaleOrderCode());
                mrpEntity.setDemandWarehouseCode(plnProductDemandEntity.getDemandWarehouseCode());
                mrpEntity.setDemandWarehouseName(plnProductDemandEntity.getDemandWarehouseName());
                mrpEntity.setMrpRunNo(mrpRunNo);
                mrpEntity.setInventoryAllocationPriority(plnProductDemandEntity.getMrpPriority());
                mrpEntity.setInventoryAllocationSequence(demandAllocationSequence);
                List<PlanMrpInventoryPeggingEntity> peggingDetails = peggingByMaterial
                        .getOrDefault(mrpEntity.getMatCode(), List.of()).stream()
                        .sorted(Comparator.comparing(PlanMrpInventoryPeggingEntity::getAllocationSequence)).toList();
                BigDecimal peggedNum = peggingDetails.stream().map(PlanMrpInventoryPeggingEntity::getPeggedNum)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                mrpEntity.setInventoryPeggedNum(peggedNum);
                mrpEntity.setInventoryPeggingStatus(peggedNum.signum() > 0 ? "PLANNED" : "NONE");
                mrpEntity.setInventoryPeggingDetails(peggingDetails);
                mrpEntity.setInventoryPeggingFingerprint(peggedNum.signum() > 0
                        ? inventoryPeggingFingerprint(peggingDetails) : null);
                List<PlanBomRequirementPathDTO> paths = pathsByMaterial.getOrDefault(mrpEntity.getMatCode(), List.of())
                        .stream().sorted(Comparator.comparing(PlanBomRequirementPathDTO::getBomPath)).toList();
                String pathSnapshot = JSONUtil.toJsonStr(paths);
                mrpEntity.setBomPathSnapshot(pathSnapshot);
                mrpEntity.setBomPathFingerprint(hash(pathSnapshot));
                mrpEntity.setMaxBomLevel(paths.stream().map(PlanBomRequirementPathDTO::getBomLevel)
                        .filter(Objects::nonNull).max(Integer::compareTo).orElse(0));
                matMrpList.add(mrpEntity);
            }
        }
        // 返回计算结果
        return matMrpList;
    }

    /**
     * 将树形结构的BOM数据扁平化
     *
     * @param bomData 树形结构的BOM数据
     * @param result  存储扁平化结果的列表
     */
    private void flattenBomData(List<MdmBomChildDTO> bomData, List<MdmBomChildDTO> result) {
        if (bomData == null || bomData.isEmpty()) {
            return;
        }
        for (MdmBomChildDTO bom : bomData) {
            // 创建一个新的DTO对象，避免引用原对象
            MdmBomChildDTO newBom = new MdmBomChildDTO();
            BeanUtil.copyProperties(bom, newBom);
            // 清空子节点，避免重复
            newBom.setChildren(null);
            // 添加到结果列表
            result.add(newBom);
            // 递归处理子节点
            if (bom.getChildren() != null && !bom.getChildren().isEmpty()) {
                flattenBomData(bom.getChildren(), result);
            }
        }
    }

    /**
     * BOM展开，计算毛需求
     *
     * @param plnProductDemandEntity 产品需求
     * @param bomId                  bom id
     * @param quantity               数量
     * @param date                   日期
     * @param grossDemand            毛需求结果
     * @param bomData                BOM数据
     */
    private void explodeBOM(PlnProductDemandEntity plnProductDemandEntity, Long bomId,
                            String parentMaterialCode, String parentPath, BigDecimal quantity, LocalDate date,
                            Map<String, PlanMatMrpEntity> grossDemand,
                            Map<String, List<PlanBomRequirementPathDTO>> pathsByMaterial,
                            List<MdmBomChildDTO> bomData, List<MatMrpDTO> matMrpDTOList,
                            Map<String, BigDecimal> usableStockMap, int level,
                            Map<String, List<InventoryRowBalance>> inventoryRowsByPool,
                            Map<String, List<PlanMrpInventoryPeggingEntity>> peggingByMaterial,
                            String mrpRunNo, int demandAllocationSequence,
                            AtomicInteger stockAllocationSequence, Set<Long> bomStack) {
        if (bomId == null || bomId == 0L) {
            return;
        }
        if (!bomStack.add(bomId)) {
            throw new CheckedException("BOM存在循环引用，需求[" + plnProductDemandEntity.getDemandNo()
                    + "]在BOM[" + bomId + "]处停止展开");
        }
        // 找到当前bomId下的所有直接子物料
        List<MdmBomChildDTO> children = bomData.stream().filter(item -> bomId.equals(item.getBomId()))
                .sorted(Comparator.comparing(MdmBomChildDTO::getId, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(MdmBomChildDTO::getMaterialCode, Comparator.nullsLast(String::compareTo)))
                .toList();

        // 处理每个子物料
        int childIndex = 0;
        for (MdmBomChildDTO child : children) {
            childIndex++;
            if (child.getQuantity() == null || child.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("BOM[" + bomId + "]物料[" + child.getMaterialCode()
                        + "]单位用量必须大于0");
            }
            // 计算子物料的需求数量 = 父物料需求数量 * 子物料单位用量
            BigDecimal childQuantity = quantity.multiply(child.getQuantity());

            // 更新或创建该物料的毛需求记录
            String materialCode = child.getMaterialCode();

            // 查找对应的物料信息
            MatMrpDTO material = matMrpDTOList.stream()
                    .filter(m -> m.getMaterialCode().equals(materialCode))
                    .findFirst()
                    .orElse(null);

            if (material == null) {
                throw new CheckedException("BOM物料[" + materialCode + "]主数据缺失");
            }
            String lineIdentity = child.getId() == null ? materialCode + "#" + childIndex : String.valueOf(child.getId());
            String nodePath = parentPath + "/" + lineIdentity;
            StockAllocationResult stockAllocation = allocateStock(usableStockMap, inventoryRowsByPool,
                    plnProductDemandEntity, material, materialCode, child.getMaterialName(), nodePath,
                    level, date, childQuantity, mrpRunNo, demandAllocationSequence, stockAllocationSequence);
            BigDecimal allocatedStock = stockAllocation.allocatedNum();
            if (!stockAllocation.peggingLines().isEmpty()) {
                peggingByMaterial.computeIfAbsent(materialCode, ignored -> new ArrayList<>())
                        .addAll(stockAllocation.peggingLines());
            }
            BigDecimal netDemand = childQuantity.subtract(allocatedStock).max(BigDecimal.ZERO);
            LocalDate plannedReleaseDate = date.minusDays(material.getFixedLeadTime().intValue());
            boolean hasChildBom = child.getSubitemBomId() != null && child.getSubitemBomId() != 0L;
            boolean explodeChildBom = hasChildBom && netDemand.compareTo(BigDecimal.ZERO) > 0
                    && MatAttributeEnum.MES.getCode().equals(material.getMaterialAttribute());

            PlanBomRequirementPathDTO path = new PlanBomRequirementPathDTO()
                    .setBomLineId(child.getId()).setBomId(bomId).setSubitemBomId(child.getSubitemBomId())
                    .setBomPath(nodePath).setParentBomPath(parentPath).setBomLevel(level)
                    .setRootProductCode(plnProductDemandEntity.getProductCode())
                    .setParentMaterialCode(parentMaterialCode).setMaterialCode(materialCode)
                    .setMaterialName(child.getMaterialName()).setSupplyType(material.getMaterialAttribute())
                    .setQuantityPerParent(child.getQuantity()).setGrossDemandNum(childQuantity)
                    .setStockAllocatedNum(allocatedStock).setNetDemandNum(netDemand)
                    .setRequirementDate(date).setPlannedReleaseDate(plannedReleaseDate)
                    .setHasChildBom(hasChildBom).setChildBomExploded(explodeChildBom);
            pathsByMaterial.computeIfAbsent(materialCode, ignored -> new ArrayList<>()).add(path);

            PlanMatMrpEntity planMatMrpEntity = new PlanMatMrpEntity();
            planMatMrpEntity.setBomId(bomId);
            planMatMrpEntity.setSubitemBomId(child.getSubitemBomId());
            planMatMrpEntity.setCustomerCode(plnProductDemandEntity.getCustomerCode());
            planMatMrpEntity.setCustomerName(plnProductDemandEntity.getCustomerName());
            planMatMrpEntity.setMatCode(child.getMaterialCode());
            planMatMrpEntity.setMatName(child.getMaterialName());
            planMatMrpEntity.setMatUnit(material.getBasicUnit());
            planMatMrpEntity.setMatSpec(material.getSpecificationModel());
            planMatMrpEntity.setType(material.getMaterialAttribute());
            planMatMrpEntity.setLeadTime(material.getFixedLeadTime().intValue());
            planMatMrpEntity.setSafetyStock(material.getMinStock().intValue());
            planMatMrpEntity.setMinOrderQuantity(material.getMinOrderQuantity());
            planMatMrpEntity.setLotSize(material.getIssuanceBatch().intValue());
            planMatMrpEntity.setOrderDate(plannedReleaseDate);
            planMatMrpEntity.setDemandDate(date);
            planMatMrpEntity.setNum(netDemand);
            planMatMrpEntity.setGrossDemandNum(childQuantity);
            planMatMrpEntity.setStockAllocatedNum(allocatedStock);
            planMatMrpEntity.setNetDemandNum(netDemand);
            planMatMrpEntity.setSubstitutePolicySnapshot(normalizeSubstitutePolicy(child));
            planMatMrpEntity.setSubstituteMixAllowed(Boolean.TRUE.equals(child.getSubstituteMixAllowed()));
            planMatMrpEntity.setSubstituteStrategy(StrUtil.blankToDefault(child.getSubstituteStrategy(), "MAIN_FIRST"));

            // 同一需求中的同物料毛需求必须累加，不能由后出现的BOM路径覆盖前一条。
            PlanMatMrpEntity existing = grossDemand.get(materialCode);
            if (existing == null) {
                grossDemand.put(materialCode, planMatMrpEntity);
            } else {
                existing.setNum(optional(existing.getNum()).add(netDemand));
                existing.setGrossDemandNum(optional(existing.getGrossDemandNum()).add(childQuantity));
                existing.setStockAllocatedNum(optional(existing.getStockAllocatedNum()).add(allocatedStock));
                existing.setNetDemandNum(optional(existing.getNetDemandNum()).add(netDemand));
                if (date.isBefore(existing.getDemandDate())) existing.setDemandDate(date);
                if (plannedReleaseDate.isBefore(existing.getOrderDate())) existing.setOrderDate(plannedReleaseDate);
                boolean samePolicy = Objects.equals(existing.getSubstitutePolicySnapshot(), planMatMrpEntity.getSubstitutePolicySnapshot())
                        && Objects.equals(existing.getSubstituteMixAllowed(), planMatMrpEntity.getSubstituteMixAllowed())
                        && Objects.equals(existing.getSubstituteStrategy(), planMatMrpEntity.getSubstituteStrategy());
                if (!samePolicy) {
                    // 多条BOM路径的替代授权不一致时不能取并集放大权限，保留缺料并要求先统一BOM策略。
                    existing.setSubstitutePolicySnapshot(null);
                    existing.setSubstituteMixAllowed(Boolean.FALSE);
                    existing.setSubstituteStatus(MrpSubstituteStatusEnum.POLICY_CONFLICT.getCode());
                }
            }

            // 如果该子物料还有下级BOM，则递归处理
            if (explodeChildBom) {
                explodeBOM(plnProductDemandEntity, child.getSubitemBomId(), materialCode, nodePath,
                        netDemand, plannedReleaseDate, grossDemand, pathsByMaterial, bomData,
                        matMrpDTOList, usableStockMap, level + 1, inventoryRowsByPool,
                        peggingByMaterial, mrpRunNo, demandAllocationSequence, stockAllocationSequence,
                        new LinkedHashSet<>(bomStack));
            }
        }
        bomStack.remove(bomId);
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(StrUtil.nullToEmpty(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("BOM逐层需求证据指纹生成失败");
        }
    }

    /**
     * 从本次MRP共享库存池分配主料。分配后立即扣减，避免多张需求重复占用同一库存。
     */
    private StockAllocationResult allocateStock(Map<String, BigDecimal> usableStockMap,
                                                Map<String, List<InventoryRowBalance>> inventoryRowsByPool,
                                                PlnProductDemandEntity demand, MatMrpDTO material,
                                                String materialCode, String materialName, String bomPath,
                                                int bomLevel, LocalDate demandDate, BigDecimal gross,
                                                String mrpRunNo, int demandAllocationSequence,
                                                AtomicInteger stockAllocationSequence) {
        String key = stockPoolKey(demand.getDemandWarehouseCode(), materialCode);
        BigDecimal poolAvailable = usableStockMap.getOrDefault(key, BigDecimal.ZERO).max(BigDecimal.ZERO);
        BigDecimal target = poolAvailable.min(gross);
        BigDecimal remaining = target;
        List<PlanMrpInventoryPeggingEntity> lines = new ArrayList<>();
        for (InventoryRowBalance balance : inventoryRowsByPool.getOrDefault(key, List.of())) {
            if (remaining.signum() <= 0) break;
            BigDecimal take = balance.available.min(remaining);
            if (take.signum() <= 0) continue;
            balance.available = balance.available.subtract(take);
            WmsLiveStockEntity stock = balance.stock;
            PlanMrpInventoryPeggingEntity pegging = new PlanMrpInventoryPeggingEntity();
            pegging.setMrpRunNo(mrpRunNo);
            pegging.setPlanDemandNo(demand.getDemandNo());
            pegging.setSaleOrderCode(demand.getSaleOrderCode());
            pegging.setWarehouseCode(demand.getDemandWarehouseCode());
            pegging.setWarehouseName(demand.getDemandWarehouseName());
            pegging.setMaterialCode(materialCode);
            pegging.setMaterialName(StrUtil.blankToDefault(materialName, material.getMaterialName()));
            pegging.setMaterialUnit(material.getBasicUnit());
            pegging.setBomPath(bomPath);
            pegging.setBomLevel(bomLevel);
            pegging.setDemandDate(demandDate);
            pegging.setTimeBucket(demand.getPlanEndDate());
            pegging.setDemandPriority(demand.getMrpPriority());
            pegging.setAllocationSequence(stockAllocationSequence.incrementAndGet());
            pegging.setSourceType("ON_HAND");
            pegging.setSourceStockId(stock.getId());
            pegging.setSourceBatchNo(stock.getMatBatch());
            pegging.setSourceSiteCode(stock.getSiteCode());
            pegging.setSourceSiteName(stock.getSiteName());
            pegging.setSourceContainerCode(stock.getContainerCode());
            pegging.setSourceSupplierCode(stock.getSupplierCode());
            pegging.setSourceQualityResult(stock.getQualityResult());
            pegging.setSourceValidEndDate(stock.getMatValidEndDate());
            pegging.setSourceInTime(stock.getInTime());
            pegging.setPeggedNum(take);
            pegging.setStatus("PLANNED");
            pegging.setFactoryCode(demand.getFactoryCode());
            pegging.setEvidenceFingerprint(inventoryPeggingLineFingerprint(pegging, demandAllocationSequence));
            lines.add(pegging);
            remaining = remaining.subtract(take);
        }
        BigDecimal allocated = target.subtract(remaining);
        if (allocated.compareTo(target) != 0) {
            throw new CheckedException("物料[" + materialCode + "]聚合可用量与库存行可用量不一致，MRP已故障关闭");
        }
        usableStockMap.put(key, poolAvailable.subtract(allocated));
        return new StockAllocationResult(allocated, lines);
    }

    /**
     * 批量调整
     *
     * @param material 物料信息
     * @param net      净需求
     * @return 调整后的批量
     */
    private BigDecimal adjustLotSize(PlanMatMrpEntity material, BigDecimal net) {
        if (net == null || net.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        int minOrderQuantity = Math.max(0, Objects.requireNonNullElse(material.getMinOrderQuantity(), 0));
        BigDecimal adjusted = net.max(BigDecimal.valueOf(minOrderQuantity));
        int lotSize = Math.max(1, Objects.requireNonNullElse(material.getLotSize(), 1));
        BigDecimal lot = BigDecimal.valueOf(lotSize);
        return adjusted.divide(lot, 0, RoundingMode.UP).multiply(lot);
    }

    /**
     * 将旧版 replaceCodes 与新版结构化策略统一为可冻结的 JSON，保证后续主数据修改不会改写已运算结果。
     */
    private String normalizeSubstitutePolicy(MdmBomChildDTO child) {
        List<MdmBomSubstituteOptionDTO> options = parseSubstituteOptions(child);
        if (options.isEmpty()) {
            return null;
        }
        return JSONUtil.toJsonStr(options);
    }

    private Set<String> getSubstituteCodes(MdmBomChildDTO child) {
        return parseSubstituteOptions(child).stream()
                .map(MdmBomSubstituteOptionDTO::getSubstituteCode)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<MdmBomSubstituteOptionDTO> parseSubstituteOptions(MdmBomChildDTO child) {
        List<MdmBomSubstituteOptionDTO> options = new ArrayList<>();
        if (StrUtil.isNotBlank(child.getSubstitutePolicy())) {
            try {
                options.addAll(JSONUtil.toList(child.getSubstitutePolicy(), MdmBomSubstituteOptionDTO.class));
            } catch (Exception ex) {
                throw new CheckedException("BOM物料[" + child.getMaterialCode() + "]替代策略格式错误");
            }
        } else if (StrUtil.isNotBlank(child.getReplaceCodes())) {
            int priority = 1;
            for (String code : child.getReplaceCodes().split(",")) {
                if (StrUtil.isBlank(code)) {
                    continue;
                }
                MdmBomSubstituteOptionDTO option = new MdmBomSubstituteOptionDTO();
                option.setSubstituteCode(code.trim());
                option.setSubstituteRatio(BigDecimal.ONE);
                option.setPriority(priority++);
                option.setEnabled(Boolean.TRUE);
                options.add(option);
            }
        }
        Map<String, MdmBomSubstituteOptionDTO> unique = new LinkedHashMap<>();
        int defaultPriority = 1;
        for (MdmBomSubstituteOptionDTO option : options) {
            if (option == null || StrUtil.isBlank(option.getSubstituteCode())) {
                continue;
            }
            option.setSubstituteCode(option.getSubstituteCode().trim());
            option.setSubstituteRatio(Optional.ofNullable(option.getSubstituteRatio()).orElse(BigDecimal.ONE));
            option.setPriority(Objects.requireNonNullElse(option.getPriority(), defaultPriority));
            option.setEnabled(!Boolean.FALSE.equals(option.getEnabled()));
            if (option.getSubstituteRatio().compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("BOM物料[" + child.getMaterialCode() + "]的替代料["
                        + option.getSubstituteCode() + "]比例必须大于0");
            }
            unique.putIfAbsent(option.getSubstituteCode(), option);
            defaultPriority++;
        }
        return new ArrayList<>(unique.values());
    }

    private List<MdmBomSubstituteOptionDTO> effectiveSubstituteOptions(String policySnapshot, LocalDate demandDate) {
        if (StrUtil.isBlank(policySnapshot)) {
            return List.of();
        }
        List<MdmBomSubstituteOptionDTO> options;
        try {
            options = JSONUtil.toList(policySnapshot, MdmBomSubstituteOptionDTO.class);
        } catch (Exception ex) {
            throw new CheckedException("MRP替代策略快照格式错误");
        }
        LocalDate effectiveDate = Optional.ofNullable(demandDate).orElse(LocalDate.now());
        return options.stream()
                .filter(Objects::nonNull)
                .filter(option -> !Boolean.FALSE.equals(option.getEnabled()))
                .filter(option -> StrUtil.isNotBlank(option.getSubstituteCode()))
                .filter(option -> option.getSubstituteRatio() != null
                        && option.getSubstituteRatio().compareTo(BigDecimal.ZERO) > 0)
                .filter(option -> option.getEffectiveStart() == null || !effectiveDate.isBefore(option.getEffectiveStart()))
                .filter(option -> option.getEffectiveEnd() == null || !effectiveDate.isAfter(option.getEffectiveEnd()))
                .sorted(Comparator.comparing((MdmBomSubstituteOptionDTO option) ->
                                Objects.requireNonNullElse(option.getPriority(), Integer.MAX_VALUE))
                        .thenComparing(MdmBomSubstituteOptionDTO::getSubstituteCode))
                .toList();
    }

    /**
     * 按需求日期与替代优先级生成可执行建议，并从本次运算的替代料共享池中预扣减。
     * 这是MRP运算内的防重复占用，实际库存只在人工批准后形成计划分配，不直接改写WMS现存量。
     */
    private void applySubstituteSuggestions(List<PlanMatMrpEntity> mrpLines,
                                            Map<String, BigDecimal> usableStockMap,
                                            Map<String, BigDecimal> physicalStockMap,
                                            Map<String, List<WmsLiveStockEntity>> stockRowsByMaterial,
                                            Map<String, MdmMaterialEntity> materialMap) {
        List<PlanMatMrpEntity> ordered = mrpLines.stream().sorted(Comparator
                        .comparing(PlanMatMrpEntity::getDemandDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PlanMatMrpEntity::getPlanDemandNo, Comparator.nullsLast(String::compareTo))
                        .thenComparing(PlanMatMrpEntity::getMatCode, Comparator.nullsLast(String::compareTo)))
                .toList();
        for (PlanMatMrpEntity line : ordered) {
            BigDecimal netDemand = Optional.ofNullable(line.getNetDemandNum()).orElse(BigDecimal.ZERO);
            if (netDemand.compareTo(BigDecimal.ZERO) <= 0
                    || MrpSubstituteStatusEnum.POLICY_CONFLICT.getCode().equals(line.getSubstituteStatus())) {
                continue;
            }
            List<MdmBomSubstituteOptionDTO> options = effectiveSubstituteOptions(
                    line.getSubstitutePolicySnapshot(), line.getDemandDate());
            if (options.isEmpty()) {
                continue;
            }
            if (containsExpandedChildDemand(line)) {
                // 聚合行级替代无法安全撤回已经展开的下层需求，必须先走受控MRP撤回/重算。
                applyMultilevelSubstituteGuidance(line, options, usableStockMap, physicalStockMap,
                        stockRowsByMaterial, materialMap);
                continue;
            }
            List<PlanMatSubstituteCandidateDTO> candidates = new ArrayList<>();
            BigDecimal suggestedRequirementTotal = BigDecimal.ZERO;
            if (Boolean.TRUE.equals(line.getSubstituteMixAllowed())) {
                BigDecimal remaining = netDemand;
                for (MdmBomSubstituteOptionDTO option : options) {
                    PlanMatSubstituteCandidateDTO candidate = createCandidate(option, line, materialMap,
                            usableStockMap, physicalStockMap, stockRowsByMaterial);
                    if (Objects.equals(line.getMatCode(), option.getSubstituteCode())) {
                        continue;
                    }
                    BigDecimal available = candidate.getAvailableActualNum();
                    if (!Boolean.TRUE.equals(candidate.getComplianceEligible())) {
                        candidates.add(candidate);
                        continue;
                    }
                    BigDecimal actualNeeded = remaining.multiply(option.getSubstituteRatio());
                    BigDecimal actualSuggested = available.min(actualNeeded);
                    BigDecimal requirementSuggested = actualSuggested.compareTo(actualNeeded) == 0
                            ? remaining
                            : actualSuggested.divide(option.getSubstituteRatio(), 8, RoundingMode.DOWN);
                    candidate.setSuggestedRequirementNum(requirementSuggested)
                            .setSuggestedActualNum(requirementSuggested.multiply(option.getSubstituteRatio()));
                    if (candidate.getReferenceUnitPrice() != null) {
                        candidate.setEstimatedSuggestedAmount(candidate.getSuggestedActualNum()
                                .multiply(candidate.getReferenceUnitPrice()));
                    }
                    candidates.add(candidate);
                    if (requirementSuggested.compareTo(BigDecimal.ZERO) > 0) {
                        usableStockMap.compute(stockPoolKey(line.getDemandWarehouseCode(), option.getSubstituteCode()), (code, current) ->
                                Optional.ofNullable(current).orElse(BigDecimal.ZERO)
                                        .subtract(candidate.getSuggestedActualNum()).max(BigDecimal.ZERO));
                        remaining = remaining.subtract(requirementSuggested);
                        suggestedRequirementTotal = suggestedRequirementTotal.add(requirementSuggested);
                    }
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                }
            } else {
                boolean selected = false;
                for (MdmBomSubstituteOptionDTO option : options) {
                    if (Objects.equals(line.getMatCode(), option.getSubstituteCode())) {
                        continue;
                    }
                    PlanMatSubstituteCandidateDTO candidate = createCandidate(option, line, materialMap,
                            usableStockMap, physicalStockMap, stockRowsByMaterial);
                    BigDecimal actualNeeded = netDemand.multiply(option.getSubstituteRatio());
                    if (Boolean.TRUE.equals(candidate.getComplianceEligible()) && !selected
                            && candidate.getAvailableActualNum().compareTo(actualNeeded) >= 0) {
                        candidate.setSuggestedRequirementNum(netDemand).setSuggestedActualNum(actualNeeded);
                        if (candidate.getReferenceUnitPrice() != null) {
                            candidate.setEstimatedSuggestedAmount(actualNeeded.multiply(candidate.getReferenceUnitPrice()));
                        }
                        usableStockMap.compute(stockPoolKey(line.getDemandWarehouseCode(), option.getSubstituteCode()), (code, current) ->
                                Optional.ofNullable(current).orElse(BigDecimal.ZERO)
                                        .subtract(actualNeeded).max(BigDecimal.ZERO));
                        suggestedRequirementTotal = netDemand;
                        selected = true;
                    }
                    candidates.add(candidate);
                }
            }
            if (!candidates.isEmpty()) {
                enrichCostVariance(candidates);
                line.setSubstituteSuggestion(JSONUtil.toJsonStr(candidates));
            }
            if (suggestedRequirementTotal.compareTo(BigDecimal.ZERO) > 0) {
                line.setSubstituteStatus(MrpSubstituteStatusEnum.PENDING.getCode());
                line.setReadinessStatus(suggestedRequirementTotal.compareTo(netDemand) >= 0
                        ? MrpReadinessStatusEnum.SUBSTITUTE_PENDING.getCode()
                        : MrpReadinessStatusEnum.PARTIAL_SHORTAGE.getCode());
            }
        }
    }

    private boolean containsExpandedChildDemand(PlanMatMrpEntity line) {
        if (line == null || StrUtil.isBlank(line.getBomPathSnapshot())) {
            return false;
        }
        try {
            return JSONUtil.toList(line.getBomPathSnapshot(), PlanBomRequirementPathDTO.class).stream()
                    .anyMatch(path -> Boolean.TRUE.equals(path.getChildBomExploded()));
        } catch (Exception ex) {
            throw new CheckedException("MRP物料[" + line.getMatCode() + "]的BOM路径快照损坏，不能生成替代建议");
        }
    }

    private void applyMultilevelSubstituteGuidance(PlanMatMrpEntity line,
                                                   List<MdmBomSubstituteOptionDTO> options,
                                                   Map<String, BigDecimal> usableStockMap,
                                                   Map<String, BigDecimal> physicalStockMap,
                                                   Map<String, List<WmsLiveStockEntity>> stockRowsByMaterial,
                                                   Map<String, MdmMaterialEntity> materialMap) {
        BigDecimal netDemand = optional(line.getNetDemandNum());
        List<PlanMatSubstituteCandidateDTO> candidates = new ArrayList<>();
        boolean actionable = false;
        for (MdmBomSubstituteOptionDTO option : options) {
            if (Objects.equals(line.getMatCode(), option.getSubstituteCode())) continue;
            PlanMatSubstituteCandidateDTO candidate = createCandidate(option, line, materialMap,
                    usableStockMap, physicalStockMap, stockRowsByMaterial);
            if (Boolean.TRUE.equals(candidate.getComplianceEligible())) {
                BigDecimal coverable = candidate.getAvailableActualNum()
                        .divide(candidate.getSubstituteRatio(), 8, RoundingMode.DOWN)
                        .min(netDemand);
                if (coverable.signum() > 0) {
                    candidate.setSuggestedRequirementNum(coverable)
                            .setSuggestedActualNum(coverable.multiply(candidate.getSubstituteRatio()));
                    if (candidate.getReferenceUnitPrice() != null) {
                        candidate.setEstimatedSuggestedAmount(candidate.getSuggestedActualNum()
                                .multiply(candidate.getReferenceUnitPrice()));
                    }
                    actionable = true;
                }
            }
            candidates.add(candidate);
        }
        if (!candidates.isEmpty()) {
            enrichCostVariance(candidates);
            line.setSubstituteSuggestion(JSONUtil.toJsonStr(candidates));
        }
        if (actionable) {
            line.setSubstituteStatus(MrpSubstituteStatusEnum.MULTILEVEL_RECALC_REQUIRED.getCode());
            line.setReadinessStatus(MrpReadinessStatusEnum.SHORTAGE.getCode());
        }
    }

    /**
     * 以同币种、合规可执行候选的最低参考价为本次方案基准，形成可解释的替代方案价差。
     */
    private void enrichCostVariance(List<PlanMatSubstituteCandidateDTO> candidates) {
        for (PlanMatSubstituteCandidateDTO candidate : candidates) {
            if (candidate.getReferenceUnitPrice() == null) {
                continue;
            }
            BigDecimal benchmark = candidates.stream()
                    .filter(item -> Boolean.TRUE.equals(item.getComplianceEligible()))
                    .filter(item -> item.getReferenceUnitPrice() != null)
                    .filter(item -> Objects.equals(StrUtil.blankToDefault(item.getCurrency(), "CNY"),
                            StrUtil.blankToDefault(candidate.getCurrency(), "CNY")))
                    .map(PlanMatSubstituteCandidateDTO::getReferenceUnitPrice)
                    .min(BigDecimal::compareTo).orElse(candidate.getReferenceUnitPrice());
            BigDecimal variance = candidate.getReferenceUnitPrice().subtract(benchmark);
            candidate.setCostBenchmarkUnitPrice(benchmark);
            candidate.setUnitCostVariance(variance);
            if (candidate.getSuggestedActualNum() != null) {
                candidate.setEstimatedCostVariance(candidate.getSuggestedActualNum().multiply(variance));
            }
        }
    }

    private PlanMatSubstituteCandidateDTO createCandidate(MdmBomSubstituteOptionDTO option,
                                                          PlanMatMrpEntity line,
                                                          Map<String, MdmMaterialEntity> materialMap,
                                                          Map<String, BigDecimal> usableStockMap,
                                                          Map<String, BigDecimal> physicalStockMap,
                                                          Map<String, List<WmsLiveStockEntity>> stockRowsByMaterial) {
        MdmMaterialEntity material = materialMap.get(option.getSubstituteCode());
        LocalDate effectiveDate = Optional.ofNullable(line.getDemandDate()).orElse(LocalDate.now());
        String complianceMessage = complianceMessage(option, line.getCustomerCode(), effectiveDate);
        String poolKey = stockPoolKey(line.getDemandWarehouseCode(), option.getSubstituteCode());
        BigDecimal totalPhysical = physicalStockMap.getOrDefault(poolKey, BigDecimal.ZERO);
        BigDecimal globalAvailable = usableStockMap.getOrDefault(poolKey, BigDecimal.ZERO)
                .max(BigDecimal.ZERO);
        BigDecimal eligiblePhysical = stockRowsByMaterial.getOrDefault(poolKey, List.of()).stream()
                .filter(stock -> isCompliantStock(stock, option))
                .map(this::stockQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 安全库存、主料占用及前序替代建议无法精确归属到批次时，优先从合规库存扣除，避免高估。
        BigDecimal unavailable = totalPhysical.subtract(globalAvailable).max(BigDecimal.ZERO);
        BigDecimal compliantAvailable = eligiblePhysical.subtract(unavailable).max(BigDecimal.ZERO)
                .min(globalAvailable);
        boolean eligible = complianceMessage == null;
        return new PlanMatSubstituteCandidateDTO()
                .setSubstituteCode(option.getSubstituteCode())
                .setSubstituteName(StrUtil.blankToDefault(option.getSubstituteName(),
                        material == null ? option.getSubstituteCode() : material.getMaterialName()))
                .setSubstituteSpec(StrUtil.blankToDefault(option.getSubstituteSpec(),
                        material == null ? null : material.getSpecificationModel()))
                .setSubstituteUnit(StrUtil.blankToDefault(option.getSubstituteUnit(),
                        material == null ? null : material.getBasicUnit()))
                .setSubstituteRatio(option.getSubstituteRatio())
                .setPriority(option.getPriority())
                .setAvailableActualNum(eligible ? compliantAvailable : BigDecimal.ZERO)
                .setSuggestedRequirementNum(BigDecimal.ZERO)
                .setSuggestedActualNum(BigDecimal.ZERO)
                .setApplicableCustomerCodes(option.getApplicableCustomerCodes())
                .setCertifiedSupplierCodes(option.getCertifiedSupplierCodes())
                .setSupplierCertificateRef(option.getSupplierCertificateRef())
                .setSupplierCertificateValidEnd(option.getSupplierCertificateValidEnd())
                .setInspectionLevel(option.getInspectionLevel())
                .setReferenceUnitPrice(option.getReferenceUnitPrice())
                .setCurrency(option.getCurrency())
                .setAllowedQualityResults(option.getAllowedQualityResults())
                .setComplianceMode(StrUtil.blankToDefault(option.getComplianceMode(), "STANDARD"))
                .setDeviationPermitNo(option.getDeviationPermitNo())
                .setDeviationReason(option.getDeviationReason())
                .setDeviationValidEnd(option.getDeviationValidEnd())
                .setComplianceEligible(eligible)
                .setComplianceMessage(eligible
                        ? (compliantAvailable.compareTo(BigDecimal.ZERO) > 0 ? "合规校验通过" : "无符合约束的可用批次")
                        : complianceMessage);
    }

    private String complianceMessage(MdmBomSubstituteOptionDTO option, String customerCode, LocalDate requiredDate) {
        LocalDate validationDate = requiredDate.isAfter(LocalDate.now()) ? requiredDate : LocalDate.now();
        if (CollUtil.isNotEmpty(option.getApplicableCustomerCodes())
                && !option.getApplicableCustomerCodes().contains(customerCode)) {
            return "当前客户不在BOM替代授权范围";
        }
        if (CollUtil.isNotEmpty(option.getCertifiedSupplierCodes())
                && (StrUtil.isBlank(option.getSupplierCertificateRef()) || option.getSupplierCertificateValidEnd() == null)) {
            return "供应商认证依据不完整";
        }
        if (option.getSupplierCertificateValidEnd() != null
                && validationDate.isAfter(option.getSupplierCertificateValidEnd())) {
            return "供应商认证在需求日期前失效";
        }
        if (!"DEVIATION".equalsIgnoreCase(option.getComplianceMode())) {
            try {
                if (CollUtil.isNotEmpty(option.getApplicableCustomerCodes())
                        && !hasCurrentAuthorization("CUSTOMER_APPROVAL", option.getSubstituteCode(), customerCode, validationDate)) {
                    return "客户替代批准已暂停、撤销或在需求日期前失效";
                }
                for (String supplierCode : option.getCertifiedSupplierCodes()) {
                    if (!hasCurrentAuthorization("SUPPLIER_CERTIFICATION", option.getSubstituteCode(), supplierCode, validationDate)) {
                        return "供应商[" + supplierCode + "]认证已暂停、撤销或在需求日期前失效";
                    }
                }
            } catch (Exception ex) {
                return "权威授权台账不可用，禁止生成替代建议";
            }
        }
        if ("DEVIATION".equalsIgnoreCase(option.getComplianceMode())) {
            if (StrUtil.isBlank(option.getDeviationPermitNo()) || StrUtil.isBlank(option.getDeviationReason())
                    || option.getDeviationValidEnd() == null) {
                return "偏离许可信息不完整";
            }
            if (validationDate.isAfter(option.getDeviationValidEnd())) {
                return "偏离许可在需求日期前失效";
            }
        }
        return null;
    }

    private boolean hasCurrentAuthorization(String type, String materialCode, String partnerCode, LocalDate bizDate) {
        R<List<MdmMaterialAuthorizationEntity>> response = remoteMaterialAuthorizationService.active(
                type, materialCode, partnerCode, bizDate);
        return response != null && response.isOk() && CollUtil.isNotEmpty(response.getData());
    }

    private boolean isCompliantStock(WmsLiveStockEntity stock, MdmBomSubstituteOptionDTO option) {
        if (CollUtil.isNotEmpty(option.getCertifiedSupplierCodes())
                && !option.getCertifiedSupplierCodes().contains(stock.getSupplierCode())) {
            return false;
        }
        String quality = StrUtil.blankToDefault(StrUtil.trim(stock.getQualityResult()), "").toUpperCase(Locale.ROOT);
        if ("TIGHTENED".equals(option.getInspectionLevel()) && !"QUALIFIED".equals(quality)) {
            return false;
        }
        return CollUtil.isEmpty(option.getAllowedQualityResults())
                || option.getAllowedQualityResults().contains(quality);
    }

    private boolean isGenerallyUsableStock(WmsLiveStockEntity stock) {
        if (stock == null || stock.getStatus() == null || stock.getStatus() != 0
                || stock.getMatNum() == null || stock.getMatNum() <= 0) {
            return false;
        }
        if (stock.getMatValidEndDate() != null && stock.getMatValidEndDate().isBefore(LocalDate.now())) {
            return false;
        }
        String result = StrUtil.blankToDefault(StrUtil.trim(stock.getQualityResult()), "").toUpperCase(Locale.ROOT);
        if (Set.of("QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE").contains(result)) {
            return true;
        }
        boolean qualityRequired = StrUtil.isNotBlank(stock.getIsQualityTest()) && !"0".equals(stock.getIsQualityTest());
        return !qualityRequired && StrUtil.isBlank(result);
    }

    private BigDecimal stockQuantity(WmsLiveStockEntity stock) {
        return stock == null || stock.getMatNum() == null
                ? BigDecimal.ZERO : BigDecimal.valueOf(stock.getMatNum());
    }

    /**
     * 把其他未下达MRP已占用的主料、待确认替代建议与已批准替代分配从当前可用池扣除。
     * 这使分批运算也能避免明显的重复分配；WMS实物锁定仍由后续领料/预留环节负责。
     */
    private void reserveExistingPlanAllocations(Map<String, BigDecimal> usableStockMap,
                                                Map<String, List<InventoryRowBalance>> inventoryRowsByPool) {
        if (usableStockMap.isEmpty()) {
            return;
        }
        List<PlanMatMrpEntity> existing = planMatMrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getStatus, false)
                .orderByAsc(PlanMatMrpEntity::getDemandDate)
                .orderByDesc(PlanMatMrpEntity::getInventoryAllocationPriority)
                .orderByAsc(PlanMatMrpEntity::getPlanDemandNo));
        Set<Long> existingIds = existing.stream().map(PlanMatMrpEntity::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, List<PlanMrpInventoryPeggingEntity>> peggingByMrp = existingIds.isEmpty() ? Map.of()
                : inventoryPeggingService.list(new LambdaQueryWrapper<PlanMrpInventoryPeggingEntity>()
                        .in(PlanMrpInventoryPeggingEntity::getMrpId, existingIds)
                        .in(PlanMrpInventoryPeggingEntity::getStatus, "PLANNED", "RESERVED"))
                .stream().collect(Collectors.groupingBy(PlanMrpInventoryPeggingEntity::getMrpId));
        for (PlanMatMrpEntity line : existing) {
            if (StrUtil.isBlank(line.getDemandWarehouseCode())) {
                throw new CheckedException("未结MRP记录[" + line.getId()
                        + "]缺少需求仓库，不能安全扣减库存池；请先按需求仓库重算该MRP");
            }
            List<PlanMrpInventoryPeggingEntity> existingPegging = peggingByMrp.getOrDefault(line.getId(), List.of());
            if (!existingPegging.isEmpty()) {
                BigDecimal pegged = existingPegging.stream().map(PlanMrpInventoryPeggingEntity::getPeggedNum)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (pegged.compareTo(optional(line.getStockAllocatedNum())) != 0
                        || !Objects.equals(line.getInventoryPeggingFingerprint(),
                        inventoryPeggingFingerprint(existingPegging))) {
                    throw new CheckedException("未下达MRP记录[" + line.getId()
                            + "]库存Pegging数量或指纹不一致，请先受控重算");
                }
                existingPegging.stream().sorted(Comparator
                                .comparing(PlanMrpInventoryPeggingEntity::getAllocationSequence))
                        .forEach(pegging -> reserveSpecific(usableStockMap, inventoryRowsByPool,
                                line.getDemandWarehouseCode(), line.getMatCode(), pegging.getSourceStockId(),
                                pegging.getPeggedNum(), line.getId()));
            } else {
                reserve(usableStockMap, inventoryRowsByPool, line.getDemandWarehouseCode(),
                        line.getMatCode(), line.getStockAllocatedNum());
            }
            if (MrpSubstituteStatusEnum.PENDING.getCode().equals(line.getSubstituteStatus())
                    && StrUtil.isNotBlank(line.getSubstituteSuggestion())) {
                try {
                    JSONUtil.toList(line.getSubstituteSuggestion(), PlanMatSubstituteCandidateDTO.class)
                            .forEach(candidate -> reserve(usableStockMap, inventoryRowsByPool,
                                    line.getDemandWarehouseCode(), candidate.getSubstituteCode(),
                                    candidate.getSuggestedActualNum()));
                } catch (Exception ex) {
                    throw new CheckedException("MRP记录[" + line.getId() + "]的替代建议快照损坏，请先处理");
                }
            }
            if ((MrpSubstituteStatusEnum.APPROVED.getCode().equals(line.getSubstituteStatus())
                    || MrpSubstituteStatusEnum.PARTIAL_APPROVED.getCode().equals(line.getSubstituteStatus()))
                    && StrUtil.isNotBlank(line.getSubstituteAllocation())) {
                try {
                    JSONUtil.toList(line.getSubstituteAllocation(), PlanMatSubstituteAllocationDTO.class)
                            .forEach(allocation -> reserve(usableStockMap, inventoryRowsByPool,
                                    line.getDemandWarehouseCode(), allocation.getSubstituteCode(),
                                    allocation.getActualNum()));
                } catch (Exception ex) {
                    throw new CheckedException("MRP记录[" + line.getId() + "]的替代分配快照损坏，请先处理");
                }
            }
        }
    }

    private void reserve(Map<String, BigDecimal> usableStockMap,
                         Map<String, List<InventoryRowBalance>> inventoryRowsByPool,
                         String warehouseCode, String materialCode, BigDecimal quantity) {
        String poolKey = stockPoolKey(warehouseCode, materialCode);
        if (StrUtil.isBlank(materialCode) || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0
                || !usableStockMap.containsKey(poolKey)) {
            return;
        }
        BigDecimal available = usableStockMap.getOrDefault(poolKey, BigDecimal.ZERO);
        BigDecimal take = available.min(quantity);
        BigDecimal remaining = take;
        for (InventoryRowBalance row : inventoryRowsByPool.getOrDefault(poolKey, List.of())) {
            if (remaining.signum() <= 0) break;
            BigDecimal rowTake = row.available.min(remaining);
            row.available = row.available.subtract(rowTake);
            remaining = remaining.subtract(rowTake);
        }
        if (remaining.signum() > 0) {
            throw new CheckedException("已有计划占用与库存行余额不一致，MRP已故障关闭");
        }
        usableStockMap.put(poolKey, available.subtract(take));
    }

    private void reserveSpecific(Map<String, BigDecimal> usableStockMap,
                                 Map<String, List<InventoryRowBalance>> inventoryRowsByPool,
                                 String warehouseCode, String materialCode, Long stockId,
                                 BigDecimal quantity, Long mrpId) {
        String poolKey = stockPoolKey(warehouseCode, materialCode);
        BigDecimal available = usableStockMap.getOrDefault(poolKey, BigDecimal.ZERO);
        if (stockId == null || quantity == null || quantity.signum() <= 0 || available.compareTo(quantity) < 0) {
            throw new CheckedException("MRP记录[" + mrpId + "]的库存Pegging已超过当前计划可用量");
        }
        InventoryRowBalance row = inventoryRowsByPool.getOrDefault(poolKey, List.of()).stream()
                .filter(item -> Objects.equals(item.stock.getId(), stockId)).findFirst()
                .orElseThrow(() -> new CheckedException("MRP记录[" + mrpId
                        + "]引用的库存行[" + stockId + "]已不存在或不可用"));
        if (row.available.compareTo(quantity) < 0) {
            throw new CheckedException("MRP记录[" + mrpId + "]引用的库存行[" + stockId
                    + "]余额已不足，请先受控重算");
        }
        row.available = row.available.subtract(quantity);
        usableStockMap.put(poolKey, available.subtract(quantity));
    }

    private Map<String, List<InventoryRowBalance>> buildInventoryRows(List<WmsLiveStockEntity> liveStocks) {
        Comparator<WmsLiveStockEntity> ordering = Comparator
                .comparing(WmsLiveStockEntity::getMatValidEndDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WmsLiveStockEntity::getInTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WmsLiveStockEntity::getId, Comparator.nullsLast(Long::compareTo));
        return liveStocks.stream().sorted(ordering).collect(Collectors.groupingBy(
                stock -> stockPoolKey(stock.getWarehouseCode(), stock.getMatCode()), LinkedHashMap::new,
                Collectors.mapping(stock -> new InventoryRowBalance(stock,
                        optional(stock.getPlanAvailableNum())), Collectors.toList())));
    }

    private String inventoryPeggingLineFingerprint(PlanMrpInventoryPeggingEntity line,
                                                   int demandAllocationSequence) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("mrpRunNo", line.getMrpRunNo());
        evidence.put("planDemandNo", line.getPlanDemandNo());
        evidence.put("warehouseCode", line.getWarehouseCode());
        evidence.put("materialCode", line.getMaterialCode());
        evidence.put("bomPath", line.getBomPath());
        evidence.put("bomLevel", line.getBomLevel());
        evidence.put("demandDate", line.getDemandDate());
        evidence.put("timeBucket", line.getTimeBucket());
        evidence.put("demandPriority", line.getDemandPriority());
        evidence.put("demandAllocationSequence", demandAllocationSequence);
        evidence.put("allocationSequence", line.getAllocationSequence());
        evidence.put("sourceType", line.getSourceType());
        evidence.put("sourceStockId", line.getSourceStockId());
        evidence.put("sourceBatchNo", line.getSourceBatchNo());
        evidence.put("sourceSiteCode", line.getSourceSiteCode());
        evidence.put("sourceContainerCode", line.getSourceContainerCode());
        evidence.put("sourceSupplierCode", line.getSourceSupplierCode());
        evidence.put("sourceQualityResult", line.getSourceQualityResult());
        evidence.put("sourceValidEndDate", line.getSourceValidEndDate());
        evidence.put("sourceInTime", line.getSourceInTime());
        evidence.put("peggedNum", line.getPeggedNum());
        return hash(JSONUtil.toJsonStr(evidence));
    }

    private String inventoryPeggingFingerprint(List<PlanMrpInventoryPeggingEntity> lines) {
        List<Map<String, Object>> evidence = lines.stream().sorted(Comparator
                        .comparing(PlanMrpInventoryPeggingEntity::getAllocationSequence)
                        .thenComparing(PlanMrpInventoryPeggingEntity::getSourceStockId))
                .map(line -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("allocationSequence", line.getAllocationSequence());
                    item.put("sourceStockId", line.getSourceStockId());
                    item.put("peggedNum", line.getPeggedNum());
                    item.put("evidenceFingerprint", line.getEvidenceFingerprint());
                    return item;
                }).toList();
        return hash(JSONUtil.toJsonStr(evidence));
    }

    private int normalizeMrpPriority(Integer priority) {
        int normalized = Objects.requireNonNullElse(priority, 100);
        if (normalized < 1 || normalized > 999) {
            throw new CheckedException("MRP库存分配优先级必须在1到999之间");
        }
        return normalized;
    }

    private String stockPoolKey(String warehouseCode, String materialCode) {
        return StrUtil.nullToEmpty(warehouseCode) + "\u0001" + StrUtil.nullToEmpty(materialCode);
    }

    private PlnProductDemandEntity insertExcel(PlnProductDemandExcelVO excelVO, MdmCustomerEntity mdmCustomerEntity,
                                               MdmMaterialEntity mdmMaterialEntity,
                                               MdmWarehouseEntity warehouse) {
        PlnProductDemandEntity plnProductDemand = new PlnProductDemandEntity();
        BeanUtil.copyProperties(excelVO, plnProductDemand);
        plnProductDemand.setCustomerName(mdmCustomerEntity.getCustomerName());
        plnProductDemand.setProductName(mdmMaterialEntity.getMaterialName());
        plnProductDemand.setSpecification(mdmMaterialEntity.getSpecificationModel());
        plnProductDemand.setDemandWarehouseCode(warehouse.getWarehouseCode());
        plnProductDemand.setDemandWarehouseName(warehouse.getWarehouseName());
        plnProductDemand.setMrpPriority(normalizeMrpPriority(excelVO.getMrpPriority()));
        plnProductDemand.setPlanState(PlanStatusEnum.CANCEL.getCode());
        return plnProductDemand;
    }

    /**
     * 接收计划
     *
     * @param planIds
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean receive(Long[] planIds) {
        if (planIds == null || planIds.length == 0 || Arrays.stream(planIds).anyMatch(Objects::isNull)) {
            throw new CheckedException("接收产品需求必须提供有效ID");
        }
        List<PlnProductDemandEntity> plnProductDemandEntities = new ArrayList<>();
        // 接收与编辑/删除/变更申请按需求ID稳定串行，避免用旧的数量或领料仓接收。
        for (Long id : Arrays.stream(planIds).distinct().sorted().toList()) {
            PlnProductDemandEntity demand = this.getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                    .eq(PlnProductDemandEntity::getId, id).last("FOR UPDATE"));
            if (demand == null) throw new CheckedException("产品需求不存在：" + id);
            requireDraftEditable(demand);
            requireNoPendingOmsDelivery(demand);
            plnProductDemandEntities.add(demand);
        }
        plnProductDemandEntities.forEach(plnProductDemand -> {
            applyDemandWarehouse(plnProductDemand, true);
            plnProductDemand.setMrpPriority(normalizeMrpPriority(plnProductDemand.getMrpPriority()));
            plnProductDemand.setPlanState(PlanStatusEnum.RECEIVE.getCode());
        });
        return this.updateBatchById(plnProductDemandEntities);
    }

    /**
     * 需求仓库是计划库存承诺的边界。只接受当前工厂内已启用仓库，并冻结名称，禁止把客户交付仓库当作领料仓。
     */
    private MdmWarehouseEntity applyDemandWarehouse(PlnProductDemandEntity demand, boolean required) {
        if (StrUtil.isBlank(demand.getDemandWarehouseCode())) {
            if (required) {
                throw new CheckedException("产品需求[" + StrUtil.blankToDefault(demand.getDemandNo(), "新建")
                        + "]必须指定需求领料仓库后才能接收或运算MRP");
            }
            demand.setDemandWarehouseName(null);
            return null;
        }
        R<MdmWarehouseEntity> response = remoteWarehouseService.getByCode(demand.getDemandWarehouseCode().trim());
        MdmWarehouseEntity warehouse = response == null || !response.isOk() ? null : response.getData();
        if (warehouse == null || !Boolean.TRUE.equals(warehouse.getStatus())) {
            throw new CheckedException("需求领料仓库[" + demand.getDemandWarehouseCode() + "]不存在或未启用");
        }
        if (StrUtil.isNotBlank(demand.getFactoryCode()) && StrUtil.isNotBlank(warehouse.getFactoryCode())
                && !Objects.equals(demand.getFactoryCode(), warehouse.getFactoryCode())) {
            throw new CheckedException("需求领料仓库[" + warehouse.getWarehouseCode() + "]不属于需求工厂["
                    + demand.getFactoryCode() + "]");
        }
        demand.setDemandWarehouseCode(warehouse.getWarehouseCode());
        demand.setDemandWarehouseName(warehouse.getWarehouseName());
        return warehouse;
    }

    private static final class InventoryRowBalance {
        private final WmsLiveStockEntity stock;
        private BigDecimal available;

        private InventoryRowBalance(WmsLiveStockEntity stock, BigDecimal available) {
            this.stock = stock;
            this.available = available;
        }
    }

    private record StockAllocationResult(BigDecimal allocatedNum,
                                         List<PlanMrpInventoryPeggingEntity> peggingLines) {
    }

}
