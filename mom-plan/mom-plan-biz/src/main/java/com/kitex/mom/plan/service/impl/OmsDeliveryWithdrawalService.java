package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.oms.dto.OmsDeliveryWithdrawalCommand;
import com.kitex.mom.oms.entity.OmsDeliveryIssueEntity;
import com.kitex.mom.oms.entity.OmsDeliveryWithdrawalEntity;
import com.kitex.mom.oms.entity.OmsOrderBaseTrackEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.OmsDeliveryIssueMapper;
import com.kitex.mom.plan.mapper.OmsDeliveryWithdrawalMapper;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import com.kitex.mom.plan.service.PlnProductDemandService;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.dto.WmsOmsDeliveryWithdrawalCommand;
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
 * OMS撤回协调器：先持久化命令，再拿WMS永久回执，最后在PLAN/OMS本地事务提交。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmsDeliveryWithdrawalService {
    private final OmsDeliveryWithdrawalMapper mapper;
    private final OmsDeliveryIssueMapper issueMapper;
    private final OmsOrderBaseTrackService trackService;
    private final OmsOrderDetailsService detailsService;
    private final PlnProductDemandService demandService;
    private final RemoteDeliverService deliverService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    public OmsDeliveryWithdrawalCommand.Receipt submit(Long detailId, OmsDeliveryWithdrawalCommand.Submit command) {
        require(detailId != null && command != null, "撤回交付行与请求内容不能为空");
        String requestId = required(command.getRequestId(), 64, "撤回请求号");
        String reason = required(command.getReason(), 500, "撤回原因");
        OmsDeliveryWithdrawalEntity saved = tx().execute(status -> stage(detailId, requestId, reason));
        return execute(saved.getRequestId());
    }

    public OmsDeliveryWithdrawalCommand.Receipt retry(String requestId) {
        return execute(required(requestId, 64, "撤回请求号"));
    }

    public List<OmsDeliveryWithdrawalCommand.History> list(String orderCode) {
        require(StrUtil.isNotBlank(orderCode), "订单号不能为空");
        String selectedFactory = FactoryContextHolder.getFactoryCode();
        return mapper.selectList(new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                        .eq(OmsDeliveryWithdrawalEntity::getOrderCode, orderCode.trim())
                        .eq(StrUtil.isNotBlank(selectedFactory), OmsDeliveryWithdrawalEntity::getFactoryCode, selectedFactory)
                        .orderByDesc(OmsDeliveryWithdrawalEntity::getSubmittedTime)).stream()
                .map(a -> new OmsDeliveryWithdrawalCommand.History()
                        .setRequestId(a.getRequestId()).setOrderCode(a.getOrderCode())
                        .setOrderDetailId(a.getOrderDetailId()).setStatus(a.getStatus())
                        .setReason(a.getReason()).setLastError(a.getLastError())
                        .setAttemptCount(a.getAttemptCount()).setApplicantName(a.getApplicantName())
                        .setSubmittedTime(a.getSubmittedTime()).setWmsCompletedTime(a.getWmsCompletedTime())
                        .setCompletedTime(a.getCompletedTime()))
                .toList();
    }

    /**
     * XXL-JOB每分钟调用；各请求独立隔离，失败至多自动重试五次。
     */
    public int processDue(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 100));
        List<OmsDeliveryWithdrawalEntity> due = mapper.selectPage(new Page<>(1, batchSize),
                new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                        .in(OmsDeliveryWithdrawalEntity::getStatus, "PENDING_WMS", "PENDING_LOCAL",
                                "FAILED_REMOTE", "FAILED_LOCAL")
                        .le(OmsDeliveryWithdrawalEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(OmsDeliveryWithdrawalEntity::getNextAttemptTime)).getRecords();
        int completed = 0;
        for (OmsDeliveryWithdrawalEntity item : due) {
            try {
                OmsDeliveryWithdrawalCommand.Receipt receipt = item.getTenantId() == null
                        ? execute(item.getRequestId())
                        : TenantBroker.applyAs(item.getTenantId(), ignored -> execute(item.getRequestId()));
                if ("SUCCEEDED".equals(receipt.getStatus())) completed++;
            } catch (RuntimeException ex) {
                log.error("OMS交付行撤回自动续跑异常，requestId={}，tenantId={}",
                        item.getRequestId(), item.getTenantId(), ex);
            }
        }
        return completed;
    }

    private OmsDeliveryWithdrawalEntity stage(Long detailId, String requestId, String reason) {
        mapper.lockKey("REQUEST:" + requestId);
        OmsDeliveryWithdrawalEntity existing = byRequest(requestId);
        if (existing != null) {
            require(Objects.equals(existing.getOrderDetailId(), detailId)
                    && Objects.equals(existing.getReason(), reason), "撤回请求号已被不同命令使用");
            requireFactory(existing.getFactoryCode());
            return existing;
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
                "订单存在待评审变更，不能撤回");
        require(isIssued(detail.getState()), "只有已下发且未完成的交付行允许撤回");
        require(issueMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                        .eq(OmsDeliveryIssueEntity::getOrderDetailId, detailId)
                        .ne(OmsDeliveryIssueEntity::getStatus, "SUCCEEDED")) == 0
                        && mapper.selectCount(new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                        .eq(OmsDeliveryWithdrawalEntity::getOrderDetailId, detailId)
                        .ne(OmsDeliveryWithdrawalEntity::getStatus, "SUCCEEDED")) == 0,
                "该订单行已有进行中或待处理的下发/撤回，请在工作台续跑原请求");

        List<PlnProductDemandEntity> demands = demandService.list(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, detail.getOrderCode())
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, detailId));
        require(demands.size() <= 1, "订单行存在多个PLAN来源需求，禁止自动撤回");
        PlnProductDemandEntity demand = demands.isEmpty() ? null : demands.get(0);
        require(demand == null || Objects.equals(demand.getFactoryCode(), track.getFactoryCode()),
                "PLAN来源需求工厂不一致");
        if (demand != null) demandService.assertDraftDeletable(demand.getId());
        R<List<WmsDeliverGoodsPlanBaseEntity>> response = deliverService.getByOrder(detail.getOrderCode(), detailId);
        require(response != null && response.isOk() && response.getData() != null,
                "查询WMS来源计划失败，不能冻结撤回证据");
        List<WmsDeliverGoodsPlanBaseEntity> plans = response.getData();
        require(plans.size() <= 1 && plans.stream().allMatch(p -> p.getId() != null
                        && Objects.equals(p.getOrderCode(), track.getOrderCode())
                        && Objects.equals(p.getOrderCodeDetails(), detailId)
                        && Objects.equals(p.getFactoryCode(), track.getFactoryCode())),
                "订单行存在重复或跨工厂WMS来源计划");
        require(!hasPlanSource(detail.getState()) || demand != null,
                "OMS标记已下发PLAN但来源需求缺失，须先对账");
        require(!hasWmsSource(detail.getState()) || !plans.isEmpty(),
                "OMS标记已下发WMS但来源计划缺失，须先对账");
        WmsDeliverGoodsPlanBaseEntity plan = plans.isEmpty() ? null : plans.get(0);
        if (plan != null) {
            require(DeliverStateEnum.NOT_START.getCode().equals(plan.getDeliverState())
                    && zero(plan.getDeliveredNum()) && zero(plan.getReturnNum())
                    && zero(plan.getReissueNum()), "WMS发货计划已有执行事实，须走受控发货变更");
        }
        MomUser applicant = SecurityUtils.getUser();
        require(applicant != null && StrUtil.isNotBlank(applicant.getUsername()), "撤回申请人身份不可用");
        OmsDeliveryWithdrawalEntity saved = new OmsDeliveryWithdrawalEntity()
                .setRequestId(requestId).setOrderCode(detail.getOrderCode())
                .setOrderDetailId(detailId).setOriginalState(detail.getState())
                .setOmsLineSnapshot(OmsOrderLineEvidence.snapshot(detail))
                .setDemandId(demand == null ? null : demand.getId())
                .setDemandSnapshot(demand == null ? null : demandFacts(demand))
                .setWmsPlanId(plan == null ? null : plan.getId())
                .setWmsPlanSnapshot(plan == null ? null : json(plan))
                .setWmsRequestId(plan == null ? null : requestId)
                .setReason(reason).setStatus(plan == null ? "PENDING_LOCAL" : "PENDING_WMS")
                .setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setApplicantCode(applicant.getUsername())
                .setApplicantName(StrUtil.blankToDefault(applicant.getName(), applicant.getUsername()))
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(track.getFactoryCode());
        require(mapper.insert(saved) == 1, "OMS撤回命令保存失败");
        return saved;
    }

    private OmsDeliveryWithdrawalCommand.Receipt execute(String requestId) {
        OmsDeliveryWithdrawalEntity action = byRequest(requestId);
        require(action != null, "撤回请求不存在");
        requireFactory(action.getFactoryCode());
        if ("SUCCEEDED".equals(action.getStatus())) return receipt(action);
        if (action.getWmsPlanId() != null) {
            try {
                WmsDeliverGoodsPlanBaseEntity frozen = objectMapper.readValue(action.getWmsPlanSnapshot(),
                        WmsDeliverGoodsPlanBaseEntity.class);
                R<WmsOmsDeliveryWithdrawalCommand.Receipt> response = deliverService.withdrawOmsCommand(
                        new WmsOmsDeliveryWithdrawalCommand.Command()
                                .setRequestId(action.getWmsRequestId()).setReason(action.getReason())
                                .setExpectedPlan(frozen));
                WmsOmsDeliveryWithdrawalCommand.Receipt wms = response == null ? null : response.getData();
                require(response != null && response.isOk() && wms != null
                                && "SUCCEEDED".equals(wms.getStatus())
                                && Objects.equals(wms.getRequestId(), action.getWmsRequestId())
                                && Objects.equals(wms.getPlanId(), action.getWmsPlanId())
                                && Objects.equals(wms.getOrderCode(), action.getOrderCode())
                                && Objects.equals(wms.getOrderDetailId(), action.getOrderDetailId()),
                        "WMS撤回未返回匹配的成功回执");
                tx().execute(status -> {
                    OmsDeliveryWithdrawalEntity locked = lockRequest(requestId);
                    if (!"SUCCEEDED".equals(locked.getStatus())) {
                        locked.setStatus("PENDING_LOCAL").setWmsReceiptFingerprint(wms.getRequestFingerprint())
                                .setWmsCompletedTime(wms.getCompletedTime()).setLastError(null)
                                .setNextAttemptTime(LocalDateTime.now());
                        require(mapper.updateById(locked) == 1, "OMS保存WMS回执失败");
                    }
                    return null;
                });
            } catch (Exception ex) {
                markFailure(requestId, "FAILED_REMOTE", ex);
                return receipt(byRequest(requestId));
            }
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
        OmsDeliveryWithdrawalEntity action = byRequest(requestId);
        require(action != null, "撤回请求不存在");
        OmsOrderBaseTrackEntity track = trackService.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, action.getOrderCode()).last("FOR UPDATE"), false);
        require(track != null && Objects.equals(track.getFactoryCode(), action.getFactoryCode()),
                "订单跟踪主记录或工厂已变化");
        OmsOrderDetailsEntity detail = detailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getId, action.getOrderDetailId()).last("FOR UPDATE"), false);
        action = lockRequest(requestId);
        if ("SUCCEEDED".equals(action.getStatus())) return;
        require(detail != null && Objects.equals(detail.getOrderCode(), action.getOrderCode())
                        && Objects.equals(detail.getFactoryCode(), action.getFactoryCode())
                        && Objects.equals(detail.getState(), action.getOriginalState())
                        && Objects.equals(OmsOrderLineEvidence.snapshot(detail), action.getOmsLineSnapshot()),
                "OMS交付行已变化，禁止按旧撤回命令提交");
        require(action.getWmsPlanId() == null || StrUtil.isNotBlank(action.getWmsReceiptFingerprint()),
                "尚未取得WMS永久回执，不能本地完结");
        List<PlnProductDemandEntity> demands = demandService.list(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, action.getOrderCode())
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, action.getOrderDetailId()));
        require(demands.size() <= 1 && (action.getDemandId() == null ? demands.isEmpty()
                        : demands.size() == 1 && Objects.equals(demands.get(0).getId(), action.getDemandId())),
                "PLAN来源需求已变化，不能按旧撤回命令完结");
        if (action.getDemandId() != null) {
            PlnProductDemandEntity demand = demandService.getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                    .eq(PlnProductDemandEntity::getId, action.getDemandId()).last("FOR UPDATE"), false);
            require(demand != null && Objects.equals(demandFacts(demand), action.getDemandSnapshot()),
                    "PLAN需求事实已变化，WMS可能已撤回，请人工对账后处理");
            demandService.assertDraftDeletable(demand.getId());
            require(demandService.removeById(demand.getId()), "PLAN草案需求删除失败");
        }
        detail.setState(OrderStateEnum.REVIEW.getCode());
        require(detailsService.updateById(detail), "OMS交付行撤回状态保存失败");
        action.setStatus("SUCCEEDED").setLastError(null).setNextAttemptTime(null)
                .setCompletedTime(LocalDateTime.now());
        require(mapper.updateById(action) == 1, "OMS撤回终态回执保存失败");
    }

    private void markFailure(String requestId, String failedStatus, Exception error) {
        tx().execute(status -> {
            OmsDeliveryWithdrawalEntity action = lockRequest(requestId);
            if (!"SUCCEEDED".equals(action.getStatus())) {
                String safe = StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName());
                int attempts = (action.getAttemptCount() == null ? 0 : action.getAttemptCount()) + 1;
                String actual = attempts >= 5 ? "NEEDS_MANUAL"
                        : "PENDING_LOCAL".equals(action.getStatus()) || "FAILED_LOCAL".equals(action.getStatus())
                        ? "FAILED_LOCAL" : failedStatus;
                action.setStatus(actual).setAttemptCount(attempts)
                        .setNextAttemptTime(attempts >= 5 ? null
                                : LocalDateTime.now().plusSeconds(Math.min(1800, 60L << Math.min(attempts, 5))))
                        .setLastError(safe.substring(0, Math.min(safe.length(), 1000)));
                require(mapper.updateById(action) == 1, "OMS撤回失败原因保存失败");
            }
            return null;
        });
    }

    private OmsDeliveryWithdrawalEntity byRequest(String requestId) {
        return mapper.selectOne(new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                .eq(OmsDeliveryWithdrawalEntity::getRequestId, requestId));
    }

    private OmsDeliveryWithdrawalEntity lockRequest(String requestId) {
        OmsDeliveryWithdrawalEntity action = mapper.selectOne(new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                .eq(OmsDeliveryWithdrawalEntity::getRequestId, requestId).last("FOR UPDATE"));
        require(action != null, "撤回请求不存在");
        return action;
    }

    private OmsDeliveryWithdrawalCommand.Receipt receipt(OmsDeliveryWithdrawalEntity action) {
        return new OmsDeliveryWithdrawalCommand.Receipt().setRequestId(action.getRequestId())
                .setOrderDetailId(action.getOrderDetailId()).setStatus(action.getStatus())
                .setLastError(action.getLastError()).setCompletedTime(action.getCompletedTime());
    }

    private String demandFacts(PlnProductDemandEntity d) {
        return JSONUtil.toJsonStr(Arrays.asList(d.getId(), d.getDemandNo(), d.getSaleOrderCode(),
                d.getSaleOrderCodeDetails(), d.getFactoryCode(), d.getProductCode(), d.getProductName(),
                d.getSpecification(), d.getProductUnit(), d.getPlanEndDate(), quantity(d.getNeedNum()),
                d.getPlanState(), quantity(d.getCompleteNum()), d.getDemandWarehouseCode(),
                d.getMrpPriority(), d.getUpdateTime()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("撤回来源计划快照序列化失败");
        }
    }

    private String quantity(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private boolean zero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private boolean isIssued(String s) {
        return OrderStateEnum.ISSUED.getCode().equals(s)
                || OrderStateEnum.ISSUED_PLAN.getCode().equals(s)
                || OrderStateEnum.ISSUED_DELIVER.getCode().equals(s);
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
        require(StrUtil.isNotBlank(trimmed) && trimmed.length() <= max, label + "不能为空且不能超过" + max + "字");
        return trimmed;
    }

    private void requireFactory(String factoryCode) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factoryCode) && (StrUtil.isBlank(selected)
                || Objects.equals(selected, factoryCode)), "当前工厂无权查看或执行该撤回命令");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
