package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmMaterialAuthorizationEntity;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialAuthorizationService;
import com.kitex.mom.mdm.feign.RemoteSupplierService;
import com.kitex.mom.pms.dto.*;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.pms.entity.PmsPriceEntity;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.enums.PmsPlanStatusEnum;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.service.PmsAgentPurchaseService;
import com.kitex.mom.pms.service.PmsOrderService;
import com.kitex.mom.pms.service.PmsPlanService;
import com.kitex.mom.pms.service.PmsPriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PmsAgentPurchaseServiceImpl implements PmsAgentPurchaseService {
    private static final String SUPPLIER_CERTIFICATION = "SUPPLIER_CERTIFICATION";
    private final RemoteMaterialAuthorizationService authorizationService;
    private final RemoteSupplierService supplierService;
    private final RemoteSeqConfigService seqConfigService;
    private final PmsPriceService priceService;
    private final PmsPlanService planService;
    private final PmsOrderService orderService;
    private final PmsOrderDetailMapper orderDetailMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public PmsAgentPurchaseOptionsDTO options(PmsAgentPurchaseCandidateQueryDTO query) {
        validateOptionsQuery(query);
        LocalDate today = LocalDate.now();
        R<List<MdmMaterialAuthorizationEntity>> authorizationResponse;
        try {
            authorizationResponse = authorizationService.active(
                    SUPPLIER_CERTIFICATION, query.getMaterialCode(), null, today);
        } catch (Exception ex) {
            return unavailable("SUPPLIER_CERTIFICATION_UNAVAILABLE", "供应商物料认证服务不可用");
        }
        if (authorizationResponse == null || !authorizationResponse.isOk() || authorizationResponse.getData() == null) {
            return unavailable("SUPPLIER_CERTIFICATION_UNAVAILABLE", "供应商物料认证证据不可用");
        }
        List<MdmMaterialAuthorizationEntity> authorizations = authorizationResponse.getData().stream()
                .filter(item -> Objects.equals(query.getFactoryCode(), item.getFactoryCode()))
                .filter(item -> StrUtil.isNotBlank(item.getPartnerCode()))
                .toList();
        if (authorizations.isEmpty()) {
            return unavailable("SUPPLIER_CERTIFICATION_MISSING", "没有覆盖该物料的有效供应商认证");
        }

        R<List<MdmSupplierEntity>> supplierResponse;
        try {
            supplierResponse = supplierService.getList(null);
        } catch (Exception ex) {
            return unavailable("SUPPLIER_MASTER_UNAVAILABLE", "供应商主数据服务不可用");
        }
        if (supplierResponse == null || !supplierResponse.isOk() || supplierResponse.getData() == null) {
            return unavailable("SUPPLIER_MASTER_UNAVAILABLE", "供应商主数据证据不可用");
        }
        Map<String, MdmSupplierEntity> suppliers = supplierResponse.getData().stream()
                .filter(item -> Objects.equals(query.getFactoryCode(), item.getFactoryCode()))
                .filter(item -> Boolean.TRUE.equals(item.getStatus()) && Boolean.TRUE.equals(item.getIsDeliver()))
                .filter(item -> "1".equals(item.getCheckStatus()))
                .collect(Collectors.toMap(MdmSupplierEntity::getSupplierCode, Function.identity(), (left, right) -> left));
        if (suppliers.isEmpty()) {
            return unavailable("SUPPLIER_INELIGIBLE", "没有已审核、启用且允许供货的供应商");
        }

        LocalDateTime now = LocalDateTime.now();
        List<PmsPriceEntity> prices = priceService.list(new LambdaQueryWrapper<PmsPriceEntity>()
                .eq(PmsPriceEntity::getFactoryCode, query.getFactoryCode())
                .eq(PmsPriceEntity::getMatCode, query.getMaterialCode())
                .eq(PmsPriceEntity::getStatus, true)
                .le(PmsPriceEntity::getEffectiveTime, now)
                .and(wrapper -> wrapper.isNull(PmsPriceEntity::getExpirationTime)
                        .or().ge(PmsPriceEntity::getExpirationTime, now))
                .orderByDesc(PmsPriceEntity::getEffectiveTime).orderByDesc(PmsPriceEntity::getId));
        Map<String, PmsPriceEntity> priceBySupplier = prices.stream()
                .filter(item -> positive(item.getUnitPriceWithTax()) || positive(item.getUnitPriceWithoutTax()))
                .collect(Collectors.toMap(PmsPriceEntity::getSupplierCode, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));

        PmsPlanEntity targetPlan = resolveTargetPlan(query);
        String purchaseMode = targetPlan == null ? "SUPPLEMENT_PLAN" : "RELEASE_PLAN";
        BigDecimal targetRemaining = targetPlan == null ? BigDecimal.ZERO
                : decimal(targetPlan.getPmsNum()).subtract(decimal(targetPlan.getPlanOrderNum())).max(BigDecimal.ZERO);
        List<PmsAgentPurchaseCandidateDTO> candidates = new ArrayList<>();
        for (MdmMaterialAuthorizationEntity authorization : authorizations) {
            MdmSupplierEntity supplier = suppliers.get(authorization.getPartnerCode());
            PmsPriceEntity price = priceBySupplier.get(authorization.getPartnerCode());
            if (supplier == null || price == null) continue;
            int leadTime = Math.max(0, Objects.requireNonNullElse(supplier.getDeliveryLeadTime(), 0));
            LocalDate earliest = today.plusDays(leadTime);
            if (!covers(authorization, earliest) || !priceCovers(price, earliest)) continue;
            PmsAgentPurchaseCandidateDTO candidate = new PmsAgentPurchaseCandidateDTO()
                    .setSupplierId(supplier.getId()).setSupplierCode(supplier.getSupplierCode())
                    .setSupplierName(supplier.getSupplierName()).setDeliveryLeadTime(leadTime)
                    .setEarliestDeliveryDate(earliest)
                    .setOnTime(query.getDemandDate() != null && !earliest.isAfter(query.getDemandDate()))
                    .setAuthorizationId(authorization.getId()).setAuthorizationNo(authorization.getAuthorizationNo())
                    .setAuthorizationValidEnd(authorization.getValidEnd())
                    .setInspectionLevel(authorization.getInspectionLevel()).setCurrency(authorization.getCurrency())
                    .setPriceId(price.getId()).setUnitPriceWithoutTax(price.getUnitPriceWithoutTax())
                    .setUnitPriceWithTax(price.getUnitPriceWithTax()).setTaxRate(price.getTaxRate())
                    .setPriceEffectiveTime(price.getEffectiveTime()).setPriceExpirationTime(price.getExpirationTime())
                    .setPurchaseMode(purchaseMode)
                    .setTargetPmsPlanNo(targetPlan == null ? null : targetPlan.getPmsPlanNo())
                    .setTargetPlanRemainingNum(targetRemaining);
            candidate.setCandidateFingerprint(candidateFingerprint(query, candidate));
            candidates.add(candidate);
        }
        candidates.sort(Comparator.comparing(PmsAgentPurchaseCandidateDTO::getOnTime).reversed()
                .thenComparing(item -> Optional.ofNullable(item.getUnitPriceWithTax())
                        .orElse(Optional.ofNullable(item.getUnitPriceWithoutTax()).orElse(BigDecimal.ZERO)))
                .thenComparing(PmsAgentPurchaseCandidateDTO::getSupplierCode));
        if (candidates.isEmpty()) {
            String status = priceBySupplier.isEmpty() ? "PRICE_MISSING_OR_EXPIRED" : "CERTIFICATION_OR_PRICE_NOT_COVER_DELIVERY";
            return unavailable(status, "没有同时满足供应商资格、交货日认证和有效价格的候选");
        }
        return new PmsAgentPurchaseOptionsDTO().setStatus("READY").setEvidenceComplete(true)
                .setDataAsOf(now).setCandidates(candidates);
    }

    @Override
    public PmsAgentPurchaseReceiptDTO execute(PmsAgentPurchaseCommandDTO command) {
        validateCommand(command);
        String currentFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(currentFactory) && !Objects.equals(currentFactory, command.getFactoryCode())) {
            throw new CheckedException("Agent采购工厂超出当前数据范围");
        }
        String commandHash = commandHash(command);
        PmsOrderEntity existing = findByRequestId(command.getRequestId());
        if (existing != null) {
            ensureSameCommand(existing, commandHash);
            submitWorkflowIfRequired(existing);
            return receipt(existing, command);
        }

        verifyCandidate(command);
        PmsOrderEntity created = transactionTemplate.execute(status -> createOrder(command, commandHash));
        if (created == null) throw new CheckedException("Agent采购订单事务未返回结果");
        submitWorkflowIfRequired(created);
        return receipt(findByRequestId(command.getRequestId()), command);
    }

    @Override
    public PmsAgentPurchaseReceiptDTO receipt(String requestId, Long mrpId) {
        if (StrUtil.isBlank(requestId) || mrpId == null) throw new CheckedException("采购回执查询缺少请求号或MRP");
        PmsOrderEntity order = findByRequestId(requestId);
        if (order == null) return null;
        if (!Objects.equals(order.getAgentMrpId(), mrpId)) throw new CheckedException("采购幂等号已属于其他MRP");
        return receipt(order, null);
    }

    private PmsOrderEntity createOrder(PmsAgentPurchaseCommandDTO command, String commandHash) {
        PmsOrderEntity duplicated = findByRequestId(command.getRequestId());
        if (duplicated != null) {
            ensureSameCommand(duplicated, commandHash);
            return duplicated;
        }
        String pmsPlanNo = command.getTargetPmsPlanNo();
        if ("RELEASE_PLAN".equals(command.getPurchaseMode())) {
            PmsPlanEntity plan = planService.getOne(new LambdaQueryWrapper<PmsPlanEntity>()
                    .eq(PmsPlanEntity::getPmsPlanNo, pmsPlanNo).last("FOR UPDATE"), false);
            validateTargetPlan(command, plan);
        } else {
            pmsPlanNo = sequence("pms_plan", "采购计划号");
            PmsPlanEntity plan = new PmsPlanEntity();
            plan.setPmsPlanNo(pmsPlanNo);
            plan.setOrderNo(command.getPlanDemandNo());
            plan.setMatCode(command.getMaterialCode());
            plan.setMatName(command.getMaterialName());
            plan.setMatUnit(command.getMaterialUnit());
            plan.setMatSpec(command.getMaterialSpec());
            plan.setPmsNum(command.getPurchaseNum().doubleValue());
            plan.setPlanOrderNum(0D);
            plan.setPlanArrivalNum(0D);
            plan.setStatus(PmsPlanStatusEnum.NO_CREATEK.getCode());
            plan.setPlanDeliveryTime(command.getRequestedDeliveryDate());
            plan.setType("采购");
            plan.setFactoryCode(command.getFactoryCode());
            planService.save(plan);
        }

        PmsOrderDetailAddDTO detail = new PmsOrderDetailAddDTO();
        detail.setPmsPlanNo(pmsPlanNo);
        detail.setOrderNo(command.getPlanDemandNo());
        detail.setMatCode(command.getMaterialCode());
        detail.setMatName(command.getMaterialName());
        detail.setMatUnit(command.getMaterialUnit());
        detail.setMatSpec(command.getMaterialSpec());
        detail.setOrderNum(command.getPurchaseNum().doubleValue());
        detail.setPlanOrderNum(command.getPurchaseNum().doubleValue());
        detail.setDeliveryTime(command.getRequestedDeliveryDate());
        detail.setPrice(Optional.ofNullable(command.getUnitPriceWithTax()).orElse(command.getUnitPriceWithoutTax()));
        detail.setPriceId(command.getPriceId());
        detail.setMaterialAuthorizationId(command.getAuthorizationId());
        detail.setFactoryCode(command.getFactoryCode());

        PmsOrderAddDTO order = new PmsOrderAddDTO();
        order.setPmsOrderNo(sequence("pms_order", "采购订单号"));
        order.setSupplierCode(command.getSupplierCode());
        order.setSupplierName(command.getSupplierName());
        order.setFactoryCode(command.getFactoryCode());
        order.setType(Boolean.FALSE);
        order.setSourceType("AGENT_SHORTAGE");
        order.setAgentPurchaseMode(command.getPurchaseMode());
        order.setAgentRequestId(command.getRequestId());
        order.setAgentCommandHash(commandHash);
        order.setAgentMrpId(command.getMrpId());
        order.setAgentBusinessType(command.getBusinessType());
        order.setAgentBusinessNo(command.getBusinessNo());
        order.setAgentEvidenceFingerprint(command.getCandidateFingerprint());
        order.setAgentPriceId(command.getPriceId());
        order.setAgentAuthorizationId(command.getAuthorizationId());
        order.setAgentReason(command.getReason());
        order.setPmsOrderDetailList(List.of(detail));
        if (!Boolean.TRUE.equals(orderService.saveDeep(order))) {
            throw new CheckedException("PMS未确认Agent采购订单创建成功");
        }
        return Optional.ofNullable(findByRequestId(command.getRequestId()))
                .orElseThrow(() -> new CheckedException("Agent采购订单创建后无法按幂等号回查"));
    }

    private void submitWorkflowIfRequired(PmsOrderEntity order) {
        if (order == null) throw new CheckedException("Agent采购订单不存在");
        if (PmsOrderStatusEnum.NO_CHECK.getCode().equals(order.getStatus())) {
            orderService.check(new Long[]{order.getId()});
        } else if (!Set.of(PmsOrderStatusEnum.CHECKING.getCode(), PmsOrderStatusEnum.CHECK_SUCCESS.getCode(),
                PmsOrderStatusEnum.RECEIVING.getCode(), PmsOrderStatusEnum.PART_RECEIVE.getCode(),
                PmsOrderStatusEnum.COMPLETE.getCode(), PmsOrderStatusEnum.REJECT.getCode(),
                PmsOrderStatusEnum.CANCELLED.getCode(), PmsOrderStatusEnum.PART_CANCELLED.getCode()).contains(order.getStatus())) {
            throw new CheckedException("Agent采购订单处于未知状态，禁止重复提交审批");
        }
    }

    private void verifyCandidate(PmsAgentPurchaseCommandDTO command) {
        PmsAgentPurchaseCandidateQueryDTO query = new PmsAgentPurchaseCandidateQueryDTO();
        query.setFactoryCode(command.getFactoryCode());
        query.setPlanDemandNo(command.getPlanDemandNo());
        query.setMaterialCode(command.getMaterialCode());
        query.setDemandDate(command.getDemandDate());
        query.setSuggestedPurchaseNum(command.getPurchaseNum());
        query.setPmsPlanNos(StrUtil.isBlank(command.getTargetPmsPlanNo()) ? List.of() : List.of(command.getTargetPmsPlanNo()));
        PmsAgentPurchaseCandidateDTO current = options(query).getCandidates().stream()
                .filter(item -> Objects.equals(item.getSupplierCode(), command.getSupplierCode())
                        && Objects.equals(item.getPriceId(), command.getPriceId())
                        && Objects.equals(item.getAuthorizationId(), command.getAuthorizationId()))
                .findFirst().orElseThrow(() -> new CheckedException("供应商认证、可供货状态或价格已变化，旧采购审批失效"));
        if (!Objects.equals(current.getCandidateFingerprint(), command.getCandidateFingerprint())
                || !Objects.equals(current.getPurchaseMode(), command.getPurchaseMode())
                || !Objects.equals(current.getTargetPmsPlanNo(), command.getTargetPmsPlanNo())
                || !Objects.equals(current.getSupplierId(), command.getSupplierId())
                || compare(current.getUnitPriceWithTax(), command.getUnitPriceWithTax()) != 0
                || compare(current.getUnitPriceWithoutTax(), command.getUnitPriceWithoutTax()) != 0
                || compare(current.getTaxRate(), command.getTaxRate()) != 0) {
            throw new CheckedException("采购候选计划、供应商、认证或价格冻结证据已变化，旧审批失效");
        }
        if (command.getRequestedDeliveryDate().isBefore(current.getEarliestDeliveryDate())) {
            throw new CheckedException("要求交货日早于供应商提前期可达日期");
        }
        if (current.getAuthorizationValidEnd() != null
                && current.getAuthorizationValidEnd().isBefore(command.getRequestedDeliveryDate())) {
            throw new CheckedException("供应商物料认证不能覆盖要求交货日");
        }
        if (current.getPriceExpirationTime() != null
                && current.getPriceExpirationTime().isBefore(command.getRequestedDeliveryDate().atTime(LocalTime.MAX))) {
            throw new CheckedException("价格有效期不能覆盖要求交货日");
        }
    }

    private PmsPlanEntity resolveTargetPlan(PmsAgentPurchaseCandidateQueryDTO query) {
        List<String> planNos = Optional.ofNullable(query.getPmsPlanNos()).orElse(List.of()).stream()
                .filter(StrUtil::isNotBlank).distinct().toList();
        if (planNos.isEmpty()) return null;
        return planService.list(new LambdaQueryWrapper<PmsPlanEntity>()
                        .eq(PmsPlanEntity::getFactoryCode, query.getFactoryCode())
                        .eq(PmsPlanEntity::getOrderNo, query.getPlanDemandNo())
                        .eq(PmsPlanEntity::getMatCode, query.getMaterialCode())
                        .in(PmsPlanEntity::getPmsPlanNo, planNos)
                        .in(PmsPlanEntity::getStatus, PmsPlanStatusEnum.NO_CREATEK.getCode(), PmsPlanStatusEnum.CREATEING.getCode())
                        .orderByAsc(PmsPlanEntity::getPlanDeliveryTime).orderByAsc(PmsPlanEntity::getId))
                .stream().filter(plan -> decimal(plan.getPmsNum()).subtract(decimal(plan.getPlanOrderNum()))
                        .compareTo(query.getSuggestedPurchaseNum()) >= 0)
                .findFirst().orElse(null);
    }

    private void validateTargetPlan(PmsAgentPurchaseCommandDTO command, PmsPlanEntity plan) {
        if (plan == null || !Objects.equals(plan.getFactoryCode(), command.getFactoryCode())
                || !Objects.equals(plan.getOrderNo(), command.getPlanDemandNo())
                || !Objects.equals(plan.getMatCode(), command.getMaterialCode())
                || !Set.of(PmsPlanStatusEnum.NO_CREATEK.getCode(), PmsPlanStatusEnum.CREATEING.getCode()).contains(plan.getStatus())) {
            throw new CheckedException("冻结采购计划不存在、已下达或业务范围已变化");
        }
        BigDecimal remaining = decimal(plan.getPmsNum()).subtract(decimal(plan.getPlanOrderNum())).max(BigDecimal.ZERO);
        if (remaining.compareTo(command.getPurchaseNum()) < 0) {
            throw new CheckedException("冻结采购计划剩余可下单量不足");
        }
    }

    private void validateOptionsQuery(PmsAgentPurchaseCandidateQueryDTO query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode()) || StrUtil.isBlank(query.getPlanDemandNo())
                || StrUtil.isBlank(query.getMaterialCode()) || query.getSuggestedPurchaseNum() == null
                || query.getSuggestedPurchaseNum().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CheckedException("Agent采购候选查询必须指定工厂、计划需求、物料和正数建议量");
        }
    }

    private void validateCommand(PmsAgentPurchaseCommandDTO command) {
        if (command == null || StrUtil.isBlank(command.getRequestId()) || command.getRequestId().length() > 64
                || StrUtil.isBlank(command.getFactoryCode()) || StrUtil.isBlank(command.getBusinessType())
                || StrUtil.isBlank(command.getBusinessNo()) || StrUtil.isBlank(command.getPlanDemandNo())
                || command.getMrpId() == null || StrUtil.isBlank(command.getMaterialCode())
                || command.getPurchaseNum() == null || command.getPurchaseNum().compareTo(BigDecimal.ZERO) <= 0
                || command.getDemandDate() == null || command.getRequestedDeliveryDate() == null
                || command.getExpectedShortageNum() == null || command.getExpectedOpenApprovedNum() == null
                || command.getExpectedPendingApprovalNum() == null || command.getExpectedInTransitNum() == null
                || command.getExpectedPurchaseUncoveredNum() == null || StrUtil.isBlank(command.getPurchaseMode())
                || command.getSupplierId() == null || StrUtil.isBlank(command.getSupplierCode())
                || command.getAuthorizationId() == null || command.getPriceId() == null
                || StrUtil.isBlank(command.getCandidateFingerprint()) || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("Agent采购命令缺少业务范围、数量事实、供应商、认证、价格、交期或原因");
        }
        if (!Set.of("RELEASE_PLAN", "SUPPLEMENT_PLAN").contains(command.getPurchaseMode())
                || ("RELEASE_PLAN".equals(command.getPurchaseMode()) && StrUtil.isBlank(command.getTargetPmsPlanNo()))
                || command.getReason().length() > 500) {
            throw new CheckedException("Agent采购模式、采购计划或原因无效");
        }
    }

    private PmsAgentPurchaseReceiptDTO receipt(PmsOrderEntity order, PmsAgentPurchaseCommandDTO command) {
        if (order == null) throw new CheckedException("Agent采购订单回执不存在");
        PmsOrderDetailEntity detail = orderDetailMapper.selectOne(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId())
                .eq(command != null && StrUtil.isNotBlank(command.getMaterialCode()),
                        PmsOrderDetailEntity::getMatCode, command == null ? null : command.getMaterialCode())
                .last("LIMIT 1"), false);
        if (detail == null) throw new CheckedException("Agent采购订单行回执不存在");
        return new PmsAgentPurchaseReceiptDTO().setRequestId(order.getAgentRequestId()).setMrpId(order.getAgentMrpId())
                .setPmsOrderId(order.getId()).setPmsOrderNo(order.getPmsOrderNo())
                .setPmsOrderDetailId(detail.getId()).setPmsPlanNo(detail.getPmsPlanNo())
                .setPurchasePlanCreated("SUPPLEMENT_PLAN".equals(order.getAgentPurchaseMode())).setSupplierCode(order.getSupplierCode())
                .setMaterialCode(detail.getMatCode()).setPurchaseNum(decimal(detail.getOrderNum()))
                .setRequestedDeliveryDate(detail.getDeliveryTime()).setOrderStatus(order.getStatus())
                .setWorkflowSubmitted(!PmsOrderStatusEnum.NO_CHECK.getCode().equals(order.getStatus()));
    }

    private PmsOrderEntity findByRequestId(String requestId) {
        if (StrUtil.isBlank(requestId)) return null;
        return orderService.getOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getAgentRequestId, requestId), false);
    }

    private void ensureSameCommand(PmsOrderEntity order, String hash) {
        if (!Objects.equals(order.getAgentCommandHash(), hash)) {
            throw new CheckedException("Agent采购幂等号已被不同命令使用");
        }
    }

    private String sequence(String code, String label) {
        R<String> response = seqConfigService.create(code);
        if (response == null || !response.isOk() || StrUtil.isBlank(response.getData())) {
            throw new CheckedException(label + "生成失败");
        }
        return response.getData();
    }

    private PmsAgentPurchaseOptionsDTO unavailable(String status, String message) {
        return new PmsAgentPurchaseOptionsDTO().setStatus(status).setEvidenceComplete(false)
                .setGapMessage(message).setDataAsOf(LocalDateTime.now()).setCandidates(List.of());
    }

    private boolean covers(MdmMaterialAuthorizationEntity authorization, LocalDate date) {
        return "ACTIVE".equals(authorization.getStatus())
                && (authorization.getValidStart() == null || !authorization.getValidStart().isAfter(date))
                && (authorization.getValidEnd() == null || !authorization.getValidEnd().isBefore(date));
    }

    private boolean priceCovers(PmsPriceEntity price, LocalDate date) {
        LocalDateTime endOfDate = date.atTime(LocalTime.MAX);
        return Boolean.TRUE.equals(price.getStatus())
                && price.getEffectiveTime() != null && !price.getEffectiveTime().isAfter(LocalDateTime.now())
                && (price.getExpirationTime() == null || !price.getExpirationTime().isBefore(endOfDate));
    }

    private String candidateFingerprint(PmsAgentPurchaseCandidateQueryDTO query,
                                        PmsAgentPurchaseCandidateDTO candidate) {
        return sha256(String.join("|", query.getFactoryCode(), query.getPlanDemandNo(), query.getMaterialCode(),
                decimalText(query.getSuggestedPurchaseNum()), Objects.toString(query.getDemandDate(), ""),
                candidate.getSupplierCode(), Objects.toString(candidate.getSupplierId(), ""),
                Objects.toString(candidate.getAuthorizationId(), ""), Objects.toString(candidate.getAuthorizationNo(), ""),
                Objects.toString(candidate.getAuthorizationValidEnd(), ""), Objects.toString(candidate.getPriceId(), ""),
                decimalText(candidate.getUnitPriceWithoutTax()), decimalText(candidate.getUnitPriceWithTax()),
                decimalText(candidate.getTaxRate()), Objects.toString(candidate.getPriceEffectiveTime(), ""),
                Objects.toString(candidate.getPriceExpirationTime(), ""), Objects.toString(candidate.getDeliveryLeadTime(), ""),
                Objects.toString(candidate.getEarliestDeliveryDate(), ""), candidate.getPurchaseMode(),
                Objects.toString(candidate.getTargetPmsPlanNo(), ""), decimalText(candidate.getTargetPlanRemainingNum())));
    }

    private String commandHash(PmsAgentPurchaseCommandDTO command) {
        return sha256(String.join("|", command.getRequestId(), command.getFactoryCode(), command.getBusinessType(),
                command.getBusinessNo(), command.getPlanDemandNo(), Objects.toString(command.getMrpId(), ""),
                command.getMaterialCode(), decimalText(command.getPurchaseNum()), Objects.toString(command.getDemandDate(), ""),
                Objects.toString(command.getRequestedDeliveryDate(), ""), decimalText(command.getExpectedShortageNum()),
                decimalText(command.getExpectedOpenApprovedNum()), decimalText(command.getExpectedPendingApprovalNum()),
                decimalText(command.getExpectedInTransitNum()), decimalText(command.getExpectedPurchaseUncoveredNum()),
                command.getPurchaseMode(), Objects.toString(command.getTargetPmsPlanNo(), ""), command.getSupplierCode(),
                Objects.toString(command.getAuthorizationId(), ""), Objects.toString(command.getPriceId(), ""),
                command.getCandidateFingerprint(), command.getReason()));
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new CheckedException("采购证据摘要计算失败");
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private int compare(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return Objects.equals(left, right) ? 0 : -1;
        return left.compareTo(right);
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
