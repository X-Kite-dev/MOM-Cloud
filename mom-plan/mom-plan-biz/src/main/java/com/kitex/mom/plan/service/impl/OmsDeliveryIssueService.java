package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.enums.MatAttributeEnum;
import com.kitex.mom.mdm.feign.RemoteCustomerService;
import com.kitex.mom.oms.dto.OmsDeliveryIssueCommand;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.plan.constant.PlanStatusEnum;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.OmsDeliveryIssueMapper;
import com.kitex.mom.plan.mapper.OmsDeliveryWithdrawalMapper;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.dto.WmsOmsDeliveryIssueCommand;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.enums.DeliverStateEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * OMS全部/发货下发：持久化冻结命令→WMS原子回执→PLAN/OMS本地终态，可原号续跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OmsDeliveryIssueService {
    private final OmsDeliveryIssueMapper mapper;
    private final OmsDeliveryWithdrawalMapper withdrawalMapper;
    private final OmsOrderBaseTrackService trackService;
    private final OmsOrderDetailsService detailsService;
    private final PlnProductDemandService demandService;
    private final RemoteDeliverService deliverService;
    private final RemoteCustomerService customerService;
    private final RemoteSeqConfigService seqService;
    private final OmsConfigService configService;
    private final PlanMatMrpService mrpService;
    private final PlnAutomationEventService automationEventService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    public OmsDeliveryIssueCommand.Receipt submit(Long detailId, String mode,
                                                  OmsDeliveryIssueCommand.Submit command) {
        require(detailId != null && command != null, "下发交付行和请求内容不能为空");
        require("ALL".equals(mode) || "DELIVER".equals(mode), "仅支持全部下发或发货下发");
        String requestId = required(command.getRequestId(), 64, "OMS下发请求号");
        tx().execute(status -> {
            stage(detailId, mode, requestId);
            return null;
        });
        return execute(requestId);
    }

    public OmsDeliveryIssueCommand.Receipt retry(String requestId, String expectedMode) {
        String id = required(requestId, 64, "OMS下发请求号");
        OmsDeliveryIssueEntity action = byRequest(id);
        require(action != null && Objects.equals(action.getMode(), expectedMode),
                "下发请求不存在或与授权下发模式不符");
        return execute(id);
    }

    public List<OmsDeliveryIssueCommand.History> list(String orderCode) {
        require(StrUtil.isNotBlank(orderCode), "订单号不能为空");
        String factory = FactoryContextHolder.getFactoryCode();
        return mapper.selectList(new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                        .eq(OmsDeliveryIssueEntity::getOrderCode, orderCode.trim())
                        .eq(StrUtil.isNotBlank(factory), OmsDeliveryIssueEntity::getFactoryCode, factory)
                        .orderByDesc(OmsDeliveryIssueEntity::getSubmittedTime)).stream()
                .map(a -> new OmsDeliveryIssueCommand.History().setRequestId(a.getRequestId())
                        .setOrderDetailId(a.getOrderDetailId()).setMode(a.getMode())
                        .setStatus(a.getStatus()).setLastError(a.getLastError())
                        .setAttemptCount(a.getAttemptCount()).setSubmittedTime(a.getSubmittedTime())
                        .setWmsCompletedTime(a.getWmsCompletedTime()).setCompletedTime(a.getCompletedTime()))
                .toList();
    }

    /**
     * XXL-JOB每分钟调用；各租户独立，连续五次失败转人工对账。
     */
    public int processDue(int limit) {
        List<OmsDeliveryIssueEntity> due = mapper.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 100))),
                new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                        .in(OmsDeliveryIssueEntity::getStatus, "PENDING_WMS", "PENDING_LOCAL",
                                "FAILED_REMOTE", "FAILED_LOCAL")
                        .le(OmsDeliveryIssueEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(OmsDeliveryIssueEntity::getNextAttemptTime)).getRecords();
        int completed = 0;
        for (OmsDeliveryIssueEntity item : due) {
            try {
                OmsDeliveryIssueCommand.Receipt receipt = item.getTenantId() == null
                        ? execute(item.getRequestId())
                        : TenantBroker.applyAs(item.getTenantId(), ignored -> execute(item.getRequestId()));
                if ("SUCCEEDED".equals(receipt.getStatus())) completed++;
            } catch (RuntimeException ex) {
                log.error("OMS下发自动续跑异常，requestId={}，tenantId={}",
                        item.getRequestId(), item.getTenantId(), ex);
            }
        }
        return completed;
    }

    private void stage(Long detailId, String mode, String requestId) {
        mapper.lockKey("REQUEST:" + requestId);
        OmsDeliveryIssueEntity old = byRequest(requestId);
        if (old != null) {
            require(Objects.equals(old.getOrderDetailId(), detailId) && Objects.equals(old.getMode(), mode),
                    "OMS下发请求号已被不同订单行或动作使用");
            requireFactory(old.getFactoryCode());
            return;
        }
        OmsOrderDetailsEntity detail = detailsService.getById(detailId);
        require(detail != null, "订单交付行不存在");
        OmsOrderBaseTrackEntity track = trackService.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, detail.getOrderCode()).last("FOR UPDATE"), false);
        require(track != null && trackService.count(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                        .eq(OmsOrderBaseTrackEntity::getOrderCode, detail.getOrderCode())) == 1,
                "订单交付行缺少唯一跟踪主记录");
        detail = detailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getId, detailId).last("FOR UPDATE"), false);
        require(detail != null && Objects.equals(detail.getOrderCode(), track.getOrderCode())
                && Objects.equals(detail.getFactoryCode(), track.getFactoryCode()), "订单交付行或工厂已变化");
        requireFactory(track.getFactoryCode());
        require(!OrderStateEnum.WAIT_REVIEW.getCode().equals(track.getOrderState())
                        && !YesOrNoStateEnum.YES.getCode().equals(detail.getChangeSign()),
                "订单存在待评审变更，不能下发");
        require(isIssueable(detail.getState()), "订单交付行当前状态不能下发");
        require(mapper.selectCount(new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                        .eq(OmsDeliveryIssueEntity::getOrderDetailId, detailId)
                        .ne(OmsDeliveryIssueEntity::getStatus, "SUCCEEDED")) == 0
                        && withdrawalMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                        .eq(OmsDeliveryWithdrawalEntity::getOrderDetailId, detailId)
                        .ne(OmsDeliveryWithdrawalEntity::getStatus, "SUCCEEDED")) == 0,
                "订单行有待处理的下发或撤回命令，请先续跑或对账");

        List<PlnProductDemandEntity> demands = demandService.list(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, detail.getOrderCode())
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, detailId));
        require(demands.size() <= 1, "订单行存在多个PLAN来源需求");
        PlnProductDemandEntity demand = demands.isEmpty() ? null : demandService.getOne(
                new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getId, demands.get(0).getId()).last("FOR UPDATE"), false);
        require(demand == null || Objects.equals(demand.getFactoryCode(), track.getFactoryCode()),
                "PLAN来源需求工厂不一致");
        require(hasPlanSource(detail.getState()) == (demand != null),
                "OMS交付行状态与PLAN来源需求不一致，须先对账");
        if ("ALL".equals(mode) && demand != null && PlanStatusEnum.CANCEL.getCode().equals(demand.getPlanState())) {
            demandService.assertDraftDeletable(demand.getId());
        }
        R<List<WmsDeliverGoodsPlanBaseEntity>> response = deliverService.getByOrder(detail.getOrderCode(), detailId);
        require(response != null && response.isOk() && response.getData() != null,
                "查询WMS来源计划失败，不能冻结下发证据");
        List<WmsDeliverGoodsPlanBaseEntity> plans = response.getData();
        require(plans.size() <= 1 && plans.stream().allMatch(p -> p.getId() != null
                        && Objects.equals(p.getOrderCode(), track.getOrderCode())
                        && Objects.equals(p.getOrderCodeDetails(), detailId)
                        && Objects.equals(p.getFactoryCode(), track.getFactoryCode())),
                "WMS来源发货计划重复或跨工厂");
        require(hasWmsSource(detail.getState()) == !plans.isEmpty(),
                "OMS交付行状态与WMS来源计划不一致，须先对账");
        WmsDeliverGoodsPlanBaseEntity before = plans.isEmpty() ? null : plans.get(0);
        require(before == null || !DeliverStateEnum.COMPLETE.getCode().equals(before.getDeliverState())
                        && !DeliverStateEnum.CLOSE.getCode().equals(before.getDeliverState()),
                "WMS发货计划已终结，不得由OMS下发自动重新打开");
        require(before != null || zero(detail.getDeliveredNum()),
                "OMS已有交付量但WMS来源计划缺失，须先对账");
        if ("ALL".equals(mode) && demand == null && before != null) {
            require(DeliverStateEnum.NOT_START.getCode().equals(before.getDeliverState())
                            && zero(before.getDeliveredNum()) && zero(before.getReturnNum())
                            && zero(before.getReissueNum()),
                    "WMS已有发运事实，不能自动新建全量PLAN需求；须先做剩余量承诺与生产评审");
        }
        WmsDeliverGoodsPlanBaseEntity desired = buildDesired(track, detail, before);
        String demandNo = null;
        if ("ALL".equals(mode)) {
            demandNo = demand == null ? sequence("plan_demand") : demand.getDemandNo();
            require(StrUtil.isNotBlank(demandNo), "PLAN来源需求号不可用");
        }
        MomUser applicant = SecurityUtils.getUser();
        require(applicant != null && StrUtil.isNotBlank(applicant.getUsername()), "OMS下发操作人身份不可用");
        OmsDeliveryIssueEntity saved = new OmsDeliveryIssueEntity()
                .setRequestId(requestId).setMode(mode).setOrderCode(detail.getOrderCode())
                .setOrderDetailId(detailId).setOriginalState(detail.getState())
                .setOmsLineSnapshot(OmsOrderLineEvidence.snapshot(detail))
                .setDemandId(demand == null ? null : demand.getId())
                .setDemandSnapshot(demand == null ? null : demandFacts(demand))
                .setDemandNo(demandNo).setWmsAction(before == null ? "CREATE" : "UPDATE")
                .setWmsBeforeSnapshot(before == null ? null : json(before))
                .setWmsDesiredSnapshot(json(desired)).setWmsRequestId(requestId)
                .setStatus("PENDING_WMS").setAttemptCount(0)
                .setNextAttemptTime(LocalDateTime.now()).setApplicantCode(applicant.getUsername())
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(track.getFactoryCode());
        require(mapper.insert(saved) == 1, "OMS下发协调命令保存失败");
    }

    private WmsDeliverGoodsPlanBaseEntity buildDesired(OmsOrderBaseTrackEntity track,
                                                       OmsOrderDetailsEntity detail,
                                                       WmsDeliverGoodsPlanBaseEntity before) {
        WmsDeliverGoodsPlanBaseEntity desired = new WmsDeliverGoodsPlanBaseEntity();
        if (before != null) org.springframework.beans.BeanUtils.copyProperties(before, desired);
        desired.setDeliverPlanCode(before == null ? sequence("deliver_plan") : before.getDeliverPlanCode());
        desired.setOrderCode(detail.getOrderCode());
        desired.setOrderCodeDetails(detail.getId());
        desired.setFactoryCode(track.getFactoryCode());
        desired.setCustomerCode(track.getCustomerCode());
        desired.setCustomerName(track.getCustomerName());
        desired.setProductCode(detail.getProductCode());
        desired.setProductName(detail.getProductName());
        desired.setProductSpec(detail.getProductSpec());
        desired.setPlanDeliverTime(detail.getDeliverTime());
        desired.setDeliverNum(detail.getDeliverNum());
        if (before == null) {
            desired.setDeliverState(DeliverStateEnum.NOT_START.getCode());
            desired.setDeliveredNum(BigDecimal.ZERO);
            desired.setReturnNum(BigDecimal.ZERO);
            desired.setReissueNum(BigDecimal.ZERO);
            R<List<MdmCustomerEntity>> result = customerService.getListByOms();
            require(result != null && result.isOk() && result.getData() != null,
                    "客户主数据不可用，不能冻结WMS收货联系事实");
            List<MdmCustomerEntity> matched = result.getData().stream()
                    .filter(c -> Objects.equals(c.getCustomerCode(), track.getCustomerCode())
                            && Objects.equals(c.getFactoryCode(), track.getFactoryCode()))
                    .toList();
            require(matched.size() == 1, "当前工厂的客户主数据缺失或重复，不能下发WMS联系事实");
            MdmCustomerEntity customer = matched.get(0);
            desired.setCustomerWarehouse(customer.getLocalWarehouse());
            desired.setCustomerContact(customer.getContactPerson());
            desired.setCustomerPhone(customer.getPhone());
        }
        return desired;
    }

    private OmsDeliveryIssueCommand.Receipt execute(String requestId) {
        OmsDeliveryIssueEntity action = byRequest(requestId);
        require(action != null, "OMS下发请求不存在");
        requireFactory(action.getFactoryCode());
        if ("SUCCEEDED".equals(action.getStatus())) return receipt(action);
        try {
            WmsDeliverGoodsPlanBaseEntity before = action.getWmsBeforeSnapshot() == null ? null
                    : objectMapper.readValue(action.getWmsBeforeSnapshot(), WmsDeliverGoodsPlanBaseEntity.class);
            WmsDeliverGoodsPlanBaseEntity desired = objectMapper.readValue(action.getWmsDesiredSnapshot(),
                    WmsDeliverGoodsPlanBaseEntity.class);
            R<WmsOmsDeliveryIssueCommand.Receipt> response = deliverService.issueOmsCommand(
                    new WmsOmsDeliveryIssueCommand.Command().setRequestId(action.getWmsRequestId())
                            .setAction(action.getWmsAction()).setExpectedPlan(before).setDesiredPlan(desired));
            WmsOmsDeliveryIssueCommand.Receipt wms = response == null ? null : response.getData();
            require(response != null && response.isOk() && wms != null
                            && "SUCCEEDED".equals(wms.getStatus())
                            && Objects.equals(wms.getRequestId(), action.getWmsRequestId())
                            && Objects.equals(wms.getAction(), action.getWmsAction())
                            && Objects.equals(wms.getOrderCode(), action.getOrderCode())
                            && Objects.equals(wms.getOrderDetailId(), action.getOrderDetailId())
                            && Objects.equals(wms.getPlanCode(), desired.getDeliverPlanCode())
                            && (action.getWmsPlanId() == null || Objects.equals(wms.getPlanId(), action.getWmsPlanId())),
                    "WMS下发未返回匹配的永久成功回执");
            tx().execute(status -> {
                OmsDeliveryIssueEntity locked = lockRequest(requestId);
                if (!"SUCCEEDED".equals(locked.getStatus())) {
                    locked.setStatus("PENDING_LOCAL").setWmsPlanId(wms.getPlanId())
                            .setWmsReceiptFingerprint(wms.getRequestFingerprint())
                            .setWmsCompletedTime(wms.getCompletedTime()).setLastError(null)
                            .setNextAttemptTime(LocalDateTime.now());
                    require(mapper.updateById(locked) == 1, "OMS下发WMS回执保存失败");
                }
                return null;
            });
        } catch (Exception ex) {
            markFailure(requestId, "FAILED_REMOTE", ex);
            return receipt(byRequest(requestId));
        }
        try {
            tx().execute(status -> {
                finish(requestId);
                return null;
            });
        } catch (Exception ex) {
            markFailure(requestId, "FAILED_LOCAL", ex);
        }
        return receipt(byRequest(requestId));
    }

    private void finish(String requestId) {
        OmsDeliveryIssueEntity action = byRequest(requestId);
        require(action != null, "OMS下发请求不存在");
        OmsOrderBaseTrackEntity track = trackService.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, action.getOrderCode()).last("FOR UPDATE"), false);
        require(track != null && Objects.equals(track.getFactoryCode(), action.getFactoryCode()),
                "订单跟踪主记录或工厂已变化");
        OmsOrderDetailsEntity detail = detailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getId, action.getOrderDetailId()).last("FOR UPDATE"), false);
        action = lockRequest(requestId);
        if ("SUCCEEDED".equals(action.getStatus())) return;
        require(detail != null && Objects.equals(detail.getOrderCode(), action.getOrderCode())
                        && Objects.equals(detail.getState(), action.getOriginalState())
                        && Objects.equals(OmsOrderLineEvidence.snapshot(detail), action.getOmsLineSnapshot()),
                "OMS订单行已变化，WMS可能已下发，请在工作台对账");
        require(StrUtil.isNotBlank(action.getWmsReceiptFingerprint()) && action.getWmsPlanId() != null,
                "尚未取得WMS永久成功回执，禁止本地完结");
        requireCurrentWmsPlan(action);
        if ("ALL".equals(action.getMode())) finishDemand(track, detail, action);
        detail.setState("ALL".equals(action.getMode())
                || OrderStateEnum.ISSUED_PLAN.getCode().equals(action.getOriginalState())
                || OrderStateEnum.ISSUED.getCode().equals(action.getOriginalState())
                ? OrderStateEnum.ISSUED.getCode() : OrderStateEnum.ISSUED_DELIVER.getCode());
        require(detailsService.updateById(detail), "OMS下发交付行状态保存失败");
        action.setStatus("SUCCEEDED").setLastError(null).setNextAttemptTime(null)
                .setCompletedTime(LocalDateTime.now());
        require(mapper.updateById(action) == 1, "OMS下发终态回执保存失败");
    }

    private void requireCurrentWmsPlan(OmsDeliveryIssueEntity action) {
        WmsDeliverGoodsPlanBaseEntity desired;
        try {
            desired = objectMapper.readValue(action.getWmsDesiredSnapshot(), WmsDeliverGoodsPlanBaseEntity.class);
        } catch (Exception ex) {
            throw new CheckedException("OMS下发冻结的WMS目标计划无法解析");
        }
        R<List<WmsDeliverGoodsPlanBaseEntity>> response = deliverService.getByOrder(
                action.getOrderCode(), action.getOrderDetailId());
        require(response != null && response.isOk() && response.getData() != null
                && response.getData().size() == 1, "WMS下发计划缺失或重复，不能完结OMS状态");
        WmsDeliverGoodsPlanBaseEntity current = response.getData().get(0);
        require(Objects.equals(current.getId(), action.getWmsPlanId())
                        && Objects.equals(current.getOrderCode(), desired.getOrderCode())
                        && Objects.equals(current.getOrderCodeDetails(), desired.getOrderCodeDetails())
                        && Objects.equals(current.getFactoryCode(), desired.getFactoryCode())
                        && Objects.equals(current.getDeliverPlanCode(), desired.getDeliverPlanCode())
                        && Objects.equals(current.getCustomerCode(), desired.getCustomerCode())
                        && Objects.equals(current.getCustomerName(), desired.getCustomerName())
                        && Objects.equals(current.getProductCode(), desired.getProductCode())
                        && Objects.equals(current.getProductName(), desired.getProductName())
                        && Objects.equals(current.getProductSpec(), desired.getProductSpec())
                        && Objects.equals(current.getPlanDeliverTime(), desired.getPlanDeliverTime())
                        && equalQuantity(current.getDeliverNum(), desired.getDeliverNum())
                        && atLeast(current.getDeliveredNum(), desired.getDeliveredNum())
                        && atLeast(current.getReturnNum(), desired.getReturnNum())
                        && atLeast(current.getReissueNum(), desired.getReissueNum()),
                "WMS计划来源或订单权威字段已变化，不能按旧下发事实完结OMS；请人工对账");
    }

    private void finishDemand(OmsOrderBaseTrackEntity track, OmsOrderDetailsEntity detail,
                              OmsDeliveryIssueEntity action) {
        List<PlnProductDemandEntity> linked = demandService.list(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, action.getOrderCode())
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, detail.getId()));
        require(linked.size() <= 1 && (action.getDemandId() == null ? linked.isEmpty()
                        : linked.size() == 1 && Objects.equals(linked.get(0).getId(), action.getDemandId())),
                "PLAN来源需求已变化，WMS可能已下发，请人工对账");
        PlnProductDemandEntity demand;
        if (action.getDemandId() == null) {
            demand = new PlnProductDemandEntity();
            demand.setDemandNo(action.getDemandNo());
            demand.setSaleOrderCode(action.getOrderCode());
            demand.setSaleOrderCodeDetails(detail.getId());
            demand.setFactoryCode(track.getFactoryCode());
            demand.setCustomerCode(track.getCustomerCode());
            demand.setCustomerName(track.getCustomerName());
            demand.setProductCode(detail.getProductCode());
            demand.setProductName(detail.getProductName());
            demand.setSpecification(detail.getProductSpec());
            demand.setProductUnit(detail.getProductUnit());
            demand.setPlanEndDate(detail.getDeliverTime());
            demand.setNeedNum(detail.getDeliverNum());
            require(Boolean.TRUE.equals(demandService.savePlan(demand)), "OMS下发PLAN草案需求失败");
        } else {
            demand = demandService.getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                    .eq(PlnProductDemandEntity::getId, action.getDemandId()).last("FOR UPDATE"), false);
            require(demand != null && Objects.equals(demandFacts(demand), action.getDemandSnapshot()),
                    "PLAN需求事实已变化，WMS可能已下发，请人工对账");
            demand.setCustomerCode(track.getCustomerCode());
            demand.setCustomerName(track.getCustomerName());
            demand.setProductCode(detail.getProductCode());
            demand.setProductName(detail.getProductName());
            demand.setSpecification(detail.getProductSpec());
            demand.setProductUnit(detail.getProductUnit());
            demand.setPlanEndDate(detail.getDeliverTime());
            demand.setNeedNum(detail.getDeliverNum());
            require(Boolean.TRUE.equals(demandService.updateFromOms(demand)), "OMS同步PLAN来源需求失败");
        }
        OmsConfigEntity config = configService.getOne(Wrappers.lambdaQuery(), false);
        if (config != null && Boolean.TRUE.equals(config.getIssue())) {
            PlanMatMrpEntity mesMrp = mrpService.getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                    .eq(PlanMatMrpEntity::getPlanDemandNo, action.getDemandNo())
                    .eq(PlanMatMrpEntity::getType, MatAttributeEnum.MES.getCode())
                    .last("LIMIT 1"), false);
            if (mesMrp != null) automationEventService.receiveMrpReady(track.getFactoryCode(),
                    action.getDemandNo(), "DAY", detail.getDeliverTime(), "订单下发后");
        }
    }

    private void markFailure(String requestId, String failedStatus, Exception error) {
        tx().execute(status -> {
            OmsDeliveryIssueEntity action = lockRequest(requestId);
            if (!"SUCCEEDED".equals(action.getStatus())) {
                int attempts = (action.getAttemptCount() == null ? 0 : action.getAttemptCount()) + 1;
                String actual = attempts >= 5 ? "NEEDS_MANUAL"
                        : "PENDING_LOCAL".equals(action.getStatus()) || "FAILED_LOCAL".equals(action.getStatus())
                        ? "FAILED_LOCAL" : failedStatus;
                String safe = StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName());
                action.setStatus(actual).setAttemptCount(attempts)
                        .setNextAttemptTime(attempts >= 5 ? null : LocalDateTime.now()
                                .plusSeconds(Math.min(1800, 60L << Math.min(attempts, 5))))
                        .setLastError(safe.substring(0, Math.min(1000, safe.length())));
                require(mapper.updateById(action) == 1, "OMS下发失败原因保存失败");
            }
            return null;
        });
    }

    private String demandFacts(PlnProductDemandEntity d) {
        return JSONUtil.toJsonStr(Arrays.asList(d.getId(), d.getDemandNo(), d.getSaleOrderCode(),
                d.getSaleOrderCodeDetails(), d.getFactoryCode(), d.getCustomerCode(), d.getCustomerName(),
                d.getProductCode(), d.getProductName(), d.getSpecification(), d.getProductUnit(),
                d.getPlanEndDate(), quantity(d.getNeedNum()), d.getPlanState(), quantity(d.getCompleteNum()),
                d.getDemandWarehouseCode(), d.getMrpPriority(), d.getUpdateTime()));
    }

    private OmsDeliveryIssueEntity byRequest(String requestId) {
        return mapper.selectOne(new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                .eq(OmsDeliveryIssueEntity::getRequestId, requestId));
    }

    private OmsDeliveryIssueEntity lockRequest(String requestId) {
        OmsDeliveryIssueEntity action = mapper.selectOne(new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                .eq(OmsDeliveryIssueEntity::getRequestId, requestId).last("FOR UPDATE"));
        require(action != null, "OMS下发请求不存在");
        return action;
    }

    private OmsDeliveryIssueCommand.Receipt receipt(OmsDeliveryIssueEntity a) {
        return new OmsDeliveryIssueCommand.Receipt().setRequestId(a.getRequestId())
                .setOrderDetailId(a.getOrderDetailId()).setMode(a.getMode())
                .setStatus(a.getStatus()).setLastError(a.getLastError()).setCompletedTime(a.getCompletedTime());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("OMS下发WMS计划证据序列化失败");
        }
    }

    private String sequence(String code) {
        R<String> response = seqService.create(code);
        require(response != null && response.isOk() && StrUtil.isNotBlank(response.getData()),
                "下发编号服务不可用：" + code);
        return response.getData();
    }

    private String quantity(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private boolean zero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private boolean equalQuantity(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private boolean atLeast(BigDecimal current, BigDecimal issued) {
        return current != null && issued != null && current.compareTo(issued) >= 0;
    }

    private boolean isIssueable(String s) {
        return OrderStateEnum.REVIEW.getCode().equals(s)
                || OrderStateEnum.ISSUED_PLAN.getCode().equals(s)
                || OrderStateEnum.ISSUED_DELIVER.getCode().equals(s)
                || OrderStateEnum.ISSUED.getCode().equals(s);
    }

    private boolean hasPlanSource(String s) {
        return OrderStateEnum.ISSUED.getCode().equals(s)
                || OrderStateEnum.ISSUED_PLAN.getCode().equals(s);
    }

    private boolean hasWmsSource(String s) {
        return OrderStateEnum.ISSUED.getCode().equals(s)
                || OrderStateEnum.ISSUED_DELIVER.getCode().equals(s);
    }

    private String required(String value, int max, String label) {
        String trimmed = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(trimmed) && trimmed.length() <= max,
                label + "不能为空且不能超过" + max + "字");
        return trimmed;
    }

    private void requireFactory(String factory) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected) || Objects.equals(selected, factory)),
                "当前工厂无权查看或执行该OMS下发命令");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
