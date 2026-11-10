package com.kitex.mom.plan.agent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.oms.agent.dto.OmsDeliveryPromiseDTO;
import com.kitex.mom.oms.entity.OmsDeliveryPromiseEntity;
import com.kitex.mom.oms.entity.OmsDeliveryPromiseLineEntity;
import com.kitex.mom.oms.entity.OmsOrderBaseTrackEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.vo.*;
import com.kitex.mom.plan.service.OmsDeliveryPromiseService;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * 销售订单交期答复事实查询。
 *
 * <p>该服务复用订单生命周期中的PLAN/MRP/MES/PMS/WMS权威事实。只有已有正式发运计划，
 * 或调用方明确给出发运提前期时，才生成客户候选日期；否则只给内部就绪日期和缺口。</p>
 */
@Service
@RequiredArgsConstructor
public class OmsDeliveryPromiseQueryService {

    public static final String TOOL_CODE = "OMS_DELIVERY_PROMISE_QUERY";

    private final OmsOrderDetailsService detailsService;
    private final OmsOrderBaseTrackService trackService;
    private final OmsDeliveryPromiseService promiseService;
    private final ObjectMapper objectMapper;

    public OmsDeliveryPromiseDTO.Evidence query(OmsDeliveryPromiseDTO.Query query) {
        validate(query);
        String factory = requireFactory(query.getFactoryCode());
        String orderCode = query.getOrderCode().trim();
        String productCode = StrUtil.trim(query.getProductCode());
        int limit = query.getLineLimit() == null ? 20 : Math.max(1, Math.min(query.getLineLimit(), 50));
        OmsOrderBaseTrackEntity order = order(factory, orderCode, query.getOrderTrackId());
        LambdaQueryWrapper<OmsOrderDetailsEntity> scope = scope(factory, orderCode, productCode);
        long total = detailsService.count(scope);
        require(total > 0, "当前工厂未找到指定销售订单交付行");
        List<OmsOrderDetailsEntity> details = detailsService.list(scope(factory, orderCode, productCode)
                .orderByAsc(OmsOrderDetailsEntity::getDeliverTime)
                .orderByAsc(OmsOrderDetailsEntity::getProductCode)
                .orderByAsc(OmsOrderDetailsEntity::getId)
                .last("LIMIT " + limit));
        List<OmsDeliveryPromiseDTO.LineAssessment> lines = new ArrayList<>();
        LinkedHashSet<String> globalRefs = new LinkedHashSet<>();
        List<String> globalGaps = new ArrayList<>();
        for (OmsOrderDetailsEntity detail : details) {
            try {
                OmsDeliveryPromiseDTO.LineAssessment line = assess(detail, trackService.lifePlan(detail.getId()),
                        query.getDispatchLeadDays());
                lines.add(line);
                globalRefs.addAll(line.getEvidenceRefs());
            } catch (Exception exception) {
                lines.add(unavailable(detail, exception));
                globalRefs.add("OMS:oms_order_details:" + detail.getId());
            }
        }
        if (total > details.size()) {
            globalGaps.add("订单交付行共" + total + "条，本次仅返回" + details.size()
                    + "条；不得把返回行的候选日期当作整单承诺");
        }
        List<String> limitations = new ArrayList<>();
        if (query.getDispatchLeadDays() == null) {
            limitations.add("未提供受控发运提前期；没有正式WMS发运计划的行只返回内部就绪日期，不推造客户到达日期");
        }
        if (lines.stream().anyMatch(line -> "INSUFFICIENT_EVIDENCE".equals(line.getPromiseStatus())))
            globalGaps.add("存在内部就绪日或跨模块供应/生产事实不可核验的交付行");
        LocalDate latestPromise = lines.stream().map(OmsDeliveryPromiseDTO.LineAssessment::getCurrentPromisedDate)
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        boolean candidateComplete = total == lines.size() && lines.stream()
                .allMatch(line -> "COMPLETED".equals(line.getPromiseStatus()) || line.getCandidatePromiseDate() != null);
        LocalDate latestCandidate = candidateComplete ? lines.stream()
                .map(line -> "COMPLETED".equals(line.getPromiseStatus()) ? LocalDate.now() : line.getCandidatePromiseDate())
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null) : null;
        List<String> actions = recommendedActions(lines, candidateComplete);
        OmsDeliveryPromiseDTO.FormalReply formalReply = latestFormalReply(order, details,
                StrUtil.isBlank(productCode) && total == details.size());
        if (formalReply != null) globalRefs.add("OMS:oms_delivery_promise:" + formalReply.getPromiseId());
        OmsDeliveryPromiseDTO.Evidence evidence = new OmsDeliveryPromiseDTO.Evidence().setToolCode(TOOL_CODE)
                .setMetricDefinition("按当前factory-code精确读取OMS订单交付行，并复用订单生命周期中的PLAN来源唯一性、MRP、MES计划/完工、PMS供应、WMS质量净可交和正式发运计划。内部就绪日不等于客户到达日；无正式发运计划且未明确发运提前期时不生成客户候选日期。")
                .setFactoryCode(factory).setOrderScoped(StrUtil.isNotBlank(query.getOrderTrackId()))
                .setOrderTrackId(String.valueOf(order.getId())).setOrderCode(orderCode).setProductCode(productCode)
                .setCustomerCode(order.getCustomerCode()).setCustomerName(order.getCustomerName())
                .setObservedAt(LocalDateTime.now()).setMatchedLineCount(total).setReturnedLineCount(lines.size())
                .setOverallPromiseStatus(overall(lines, total > lines.size()))
                .setLatestCurrentPromisedDate(latestPromise).setLatestCandidatePromiseDate(latestCandidate)
                .setCandidateComplete(candidateComplete).setDispatchLeadDays(query.getDispatchLeadDays())
                .setLines(lines).setRecommendedActions(actions).setLimitations(limitations).setDataGaps(globalGaps)
                .setEvidenceRefs(new ArrayList<>(globalRefs)).setLatestFormalReply(formalReply)
                .setTruncated(total > lines.size());
        return evidence.setFactFingerprint(fingerprint(evidence));
    }

    private OmsOrderBaseTrackEntity order(String factory, String orderCode, String orderTrackId) {
        LambdaQueryWrapper<OmsOrderBaseTrackEntity> wrapper = new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getFactoryCode, factory)
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderCode);
        if (StrUtil.isNotBlank(orderTrackId)) {
            require(orderTrackId.matches("[1-9][0-9]{0,18}"), "订单跟踪主键格式无效");
            try {
                wrapper.eq(OmsOrderBaseTrackEntity::getId, Long.parseLong(orderTrackId));
            } catch (NumberFormatException invalid) {
                throw new CheckedException("订单跟踪主键超出数据库范围");
            }
        }
        List<OmsOrderBaseTrackEntity> matches = trackService.list(wrapper.orderByDesc(
                OmsOrderBaseTrackEntity::getUpdateTime).orderByDesc(OmsOrderBaseTrackEntity::getId).last("LIMIT 2"));
        require(matches.size() == 1, matches.isEmpty() ? "当前工厂未找到指定销售订单"
                : "当前工厂的销售订单号不唯一，请从订单跟踪列表按主键发起");
        return matches.get(0);
    }

    private OmsDeliveryPromiseDTO.FormalReply latestFormalReply(OmsOrderBaseTrackEntity order,
                                                                List<OmsOrderDetailsEntity> current,
                                                                boolean completeOrderScope) {
        OmsDeliveryPromiseEntity latest = promiseService.getOne(new LambdaQueryWrapper<OmsDeliveryPromiseEntity>()
                .eq(OmsDeliveryPromiseEntity::getFactoryCode, order.getFactoryCode())
                .eq(OmsDeliveryPromiseEntity::getOrderTrackId, order.getId())
                .eq(OmsDeliveryPromiseEntity::getOrderCode, order.getOrderCode())
                .orderByDesc(OmsDeliveryPromiseEntity::getCreateTime)
                .orderByDesc(OmsDeliveryPromiseEntity::getId).last("LIMIT 1"), false);
        if (latest == null) return null;
        boolean scheduleMatched = false;
        if (completeOrderScope && "REPLIED".equals(latest.getStatus())) {
            com.kitex.mom.oms.dto.OmsDeliveryPromiseDTO.Detail detail = promiseService.detail(latest.getId());
            scheduleMatched = sameSchedule(current, detail.getLines());
        }
        return new OmsDeliveryPromiseDTO.FormalReply()
                .setPromiseId(String.valueOf(latest.getId())).setPromiseNo(latest.getPromiseNo())
                .setStatus(latest.getStatus()).setOrderChangeStatus(latest.getOrderChangeStatus())
                .setReadyTime(latest.getReadyTime()).setReplyChannel(latest.getReplyChannel())
                .setReplyReference(latest.getReplyReference()).setRepliedTime(latest.getRepliedTime())
                .setCurrentScheduleMatched(scheduleMatched);
    }

    private boolean sameSchedule(List<OmsOrderDetailsEntity> current, List<OmsDeliveryPromiseLineEntity> frozen) {
        if (current == null || frozen == null || current.size() != frozen.size()) return false;
        Map<String, Long> currentSchedule = current.stream().collect(java.util.stream.Collectors.groupingBy(
                row -> scheduleKey(row.getProductCode(), row.getProductSpec(), row.getProductUnit(),
                        row.getDeliverTime(), row.getDeliverNum()), TreeMap::new, java.util.stream.Collectors.counting()));
        Map<String, Long> frozenSchedule = frozen.stream().collect(java.util.stream.Collectors.groupingBy(
                row -> scheduleKey(row.getProductCode(), row.getProductSpec(), row.getProductUnit(),
                        row.getProposedDeliverTime(), row.getProposedDeliverNum()), TreeMap::new,
                java.util.stream.Collectors.counting()));
        return currentSchedule.equals(frozenSchedule);
    }

    private String scheduleKey(String productCode, String productSpec, String unit, LocalDate date, BigDecimal quantity) {
        return String.join("|", Objects.toString(productCode, ""), Objects.toString(productSpec, ""),
                Objects.toString(unit, ""), Objects.toString(date, ""),
                quantity == null ? "" : quantity.stripTrailingZeros().toPlainString());
    }

    private String fingerprint(OmsDeliveryPromiseDTO.Evidence evidence) {
        try {
            ObjectNode value = objectMapper.valueToTree(evidence);
            value.remove(List.of("observedAt", "factFingerprint"));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("交付承诺事实指纹生成失败");
        }
    }

    private OmsDeliveryPromiseDTO.LineAssessment assess(OmsOrderDetailsEntity detail, OmsLifeVO life,
                                                        Integer dispatchLeadDays) {
        require(life != null && life.getFulfillmentSummary() != null, "订单生命周期未形成履约摘要");
        OmsFulfillmentSummaryVO summary = life.getFulfillmentSummary();
        List<String> blockers = new ArrayList<>(Optional.ofNullable(summary.getRiskMessages()).orElse(List.of()));
        List<String> refs = new ArrayList<>();
        refs.add("OMS:oms_order_details:" + detail.getId());
        if (StrUtil.isNotBlank(summary.getDemandNo())) refs.add("PLAN:pln_product_demand:" + summary.getDemandNo());
        Optional.ofNullable(life.getMesList()).orElse(List.of()).stream().map(OmsLifeMesTaskVO::getCode)
                .filter(StrUtil::isNotBlank).forEach(code -> refs.add("MES:mes_task:" + code));
        Optional.ofNullable(life.getPmsList()).orElse(List.of()).stream().map(OmsLifePmsPlanVO::getPmsPlanNo)
                .filter(StrUtil::isNotBlank).forEach(code -> refs.add("PMS:pms_plan:" + code));
        Optional.ofNullable(life.getDeliverList()).orElse(List.of()).stream().map(OmsLifeWmsDeliverVO::getDeliverPlanCode)
                .filter(StrUtil::isNotBlank).forEach(code -> refs.add("WMS:wms_deliver_plan:" + code));

		BigDecimal remaining = summary.getRemainingQuantity();
		BigDecimal ordered = summary.getOrderedQuantity();
		BigDecimal shipped = summary.getDeliveredQuantity();
		// 实际发货与计划来源分别核对，计划异常仍保留在阻断依据。
		boolean fulfilled = "AVAILABLE".equals(summary.getWmsDataStatus())
				&& ordered != null && ordered.signum() > 0
				&& shipped != null && shipped.compareTo(ordered) >= 0
				&& remaining != null && remaining.signum() == 0;
		boolean outstandingKnown = remaining != null && remaining.signum() > 0;
		BigDecimal deliverable = value(summary.getNetDeliverableQuantity()).max(BigDecimal.ZERO);
		LocalDate internalReady = null;
		String readyBasis = null;
		if (fulfilled) {
			internalReady = LocalDate.now();
			readyBasis = "订单交付行已完成";
		} else if (!outstandingKnown) {
			blockers.add("订单剩余量或足量发货依据待核，暂不能形成交付承诺");
		} else if ("AVAILABLE".equals(summary.getQualityDeliverabilityStatus())
				&& deliverable.compareTo(remaining) >= 0) {
			internalReady = LocalDate.now();
			readyBasis = "WMS质量净可交量已覆盖全部剩余量";
		} else {
			Projection projection = productionProjection(detail, life, blockers);
			internalReady = projection.date();
			readyBasis = projection.basis();
		}
        LocalDate dispatchPlan = Optional.ofNullable(life.getDeliverList()).orElse(List.of()).stream()
                .filter(row -> value(row.getDeliveredNum()).compareTo(value(row.getDeliverNum())) < 0)
                .map(OmsLifeWmsDeliverVO::getPlanDeliverTime).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
        LocalDate candidate = null;
        String confidence = "LOW";
        if (internalReady != null && dispatchPlan != null && !dispatchPlan.isBefore(internalReady)) {
            candidate = dispatchPlan;
            confidence = Stream.of(summary.getPlanDataStatus(), summary.getMesDataStatus(),
                            summary.getPmsDataStatus(), summary.getWmsDataStatus())
                    .allMatch(status -> "AVAILABLE".equals(status) || "NOT_APPLICABLE".equals(status))
                    ? "HIGH" : "MEDIUM";
        } else if (internalReady != null && dispatchLeadDays != null) {
            candidate = internalReady.plusDays(dispatchLeadDays);
            confidence = "MEDIUM";
            if (dispatchPlan != null && dispatchPlan.isBefore(internalReady)) {
                blockers.add("WMS当前计划发运日期早于内部就绪日期，候选已改按内部就绪日和明确提前期计算，原发运计划须调整");
            }
        } else if (dispatchPlan != null && internalReady == null) {
            blockers.add("WMS虽有计划发运日期，但内部就绪日期不可核验，不能把发运计划直接当作可承诺日期");
        } else if (dispatchPlan != null && internalReady != null && dispatchPlan.isBefore(internalReady)) {
            blockers.add("WMS当前计划发运日期早于内部就绪日期，且未提供发运提前期，不能形成客户候选日期");
        }
        boolean unavailable = Stream.of(summary.getPlanDataStatus(), summary.getMesDataStatus(),
                        summary.getPmsDataStatus(), summary.getWmsDataStatus(), summary.getQualityDeliverabilityStatus())
                .anyMatch("UNAVAILABLE"::equals);
        boolean inconsistent = "INCONSISTENT".equals(summary.getPlanDataStatus())
                || "INCONSISTENT".equals(summary.getQualityDeliverabilityStatus());
		String status = fulfilled ? "COMPLETED"
				: !outstandingKnown || detail.getDeliverTime() == null ? "INSUFFICIENT_EVIDENCE"
				: inconsistent || "BLOCKED".equals(summary.getOverallStatus()) ? "BLOCKED"
				: unavailable || internalReady == null ? "INSUFFICIENT_EVIDENCE"
				: candidate == null ? "INTERNAL_DATE_ONLY"
				: detail.getDeliverTime() != null && candidate.isAfter(detail.getDeliverTime())
				? "LATE_RISK" : "ON_TIME_SUPPORTED";
        if ("ON_TIME_SUPPORTED".equals(status) && confidence.equals("MEDIUM")) {
            blockers.add("候选客户日期使用调用方明确提供的发运提前期，仍须销售/PMC确认并核对物流资源");
        }
        List<OmsDeliveryPromiseDTO.Scenario> scenarios = scenarios(detail, summary, internalReady,
                dispatchPlan, candidate, dispatchLeadDays, status);
        return new OmsDeliveryPromiseDTO.LineAssessment().setOrderDetailId(detail.getId())
                .setProductCode(detail.getProductCode()).setProductName(detail.getProductName())
                .setProductUnit(detail.getProductUnit()).setCurrentPromisedDate(detail.getDeliverTime())
                .setOrderedQuantity(summary.getOrderedQuantity()).setDeliveredQuantity(summary.getDeliveredQuantity())
                .setRemainingQuantity(summary.getRemainingQuantity()).setNetDeliverableQuantity(summary.getNetDeliverableQuantity())
                .setProjectedInternalReadyDate(internalReady).setInternalReadyBasis(readyBasis)
                .setCurrentDispatchPlanDate(dispatchPlan).setCandidatePromiseDate(candidate)
                .setPromiseStatus(status).setConfidence(confidence)
                .setOverallFulfillmentStatus(summary.getOverallStatus()).setRiskLevel(summary.getRiskLevel())
                .setScenarios(scenarios).setBlockers(blockers.stream().distinct().toList())
                .setEvidenceRefs(refs.stream().distinct().toList());
    }

    private Projection productionProjection(OmsOrderDetailsEntity detail, OmsLifeVO life, List<String> blockers) {
        List<OmsLifeMesTaskVO> rootTasks = Optional.ofNullable(life.getMesList()).orElse(List.of()).stream()
                .filter(row -> Objects.equals(detail.getProductCode(), row.getMatCode()))
                .filter(row -> value(row.getCompleteNum()).compareTo(value(row.getNoticeNum())) < 0)
                .toList();
        if (rootTasks.isEmpty()) {
            blockers.add("剩余量尚无可核验的成品MES未完工任务，无法推算内部就绪日期");
            return new Projection(null, null);
        }
        if (rootTasks.stream().anyMatch(row -> row.getPlanEndDate() == null)) {
            blockers.add("存在未冻结计划结束时间的MES成品任务，无法推算内部就绪日期");
            return new Projection(null, null);
        }
        LocalDate productionReady = rootTasks.stream().map(row -> row.getPlanEndDate().toLocalDate())
                .max(LocalDate::compareTo).orElse(null);
        List<OmsLifePmsPlanVO> openSupply = Optional.ofNullable(life.getPmsList()).orElse(List.of()).stream()
                .filter(row -> !"3".equals(row.getStatus())).toList();
        if (openSupply.stream().anyMatch(row -> row.getPlanDeliveryTime() == null)) {
            blockers.add("存在未给出计划到货日期的采购/外协供应，MES计划结束日不能作为可靠就绪日");
            return new Projection(null, null);
        }
        LocalDate supplyReady = openSupply.stream().map(OmsLifePmsPlanVO::getPlanDeliveryTime)
                .max(LocalDate::compareTo).orElse(null);
        if (supplyReady != null && productionReady != null && supplyReady.isAfter(productionReady)) {
            blockers.add("采购/外协最晚到货日晚于MES计划结束日，当前供应与生产时序冲突，须重排后再答复");
            return new Projection(null, null);
        }
        return new Projection(productionReady, openSupply.isEmpty()
                ? "MES未完工成品任务的最晚计划结束日"
                : "已核对供应计划不晚于MES成品任务最晚计划结束日");
    }

    private List<OmsDeliveryPromiseDTO.Scenario> scenarios(OmsOrderDetailsEntity detail,
                                                           OmsFulfillmentSummaryVO summary, LocalDate internalReady, LocalDate dispatchPlan,
                                                           LocalDate candidate, Integer leadDays, String status) {
        List<OmsDeliveryPromiseDTO.Scenario> rows = new ArrayList<>();
        rows.add(new OmsDeliveryPromiseDTO.Scenario().setScenarioCode("CURRENT_COMMITMENT")
                .setScenarioName("当前客户承诺").setInternalReadyDate(internalReady)
                .setCustomerPromiseDate(detail.getDeliverTime()).setSupportQuantity(summary.getRemainingQuantity())
                .setStatus(status).setAssumptions(List.of("沿用OMS当前交付日期，不自动改写订单")));
        List<String> baselineAssumptions = new ArrayList<>();
        if (dispatchPlan != null) baselineAssumptions.add("客户候选日期取WMS当前正式计划发运日期");
        else if (leadDays != null) baselineAssumptions.add("客户候选日期=内部就绪日+明确发运提前期" + leadDays + "天");
        else baselineAssumptions.add("缺少正式发运计划和明确发运提前期，仅能形成内部就绪日期");
        rows.add(new OmsDeliveryPromiseDTO.Scenario().setScenarioCode("CURRENT_EXECUTION")
                .setScenarioName("当前执行事实").setInternalReadyDate(internalReady).setCustomerPromiseDate(candidate)
                .setSupportQuantity(summary.getRemainingQuantity()).setStatus(status).setAssumptions(baselineAssumptions));
        BigDecimal deliverable = value(summary.getNetDeliverableQuantity()).min(value(summary.getRemainingQuantity()));
        if (deliverable.signum() > 0 && deliverable.compareTo(value(summary.getRemainingQuantity())) < 0) {
            rows.add(new OmsDeliveryPromiseDTO.Scenario().setScenarioCode("PARTIAL_SHIPMENT")
                    .setScenarioName("现有合格库存先行分批").setInternalReadyDate(LocalDate.now())
                    .setCustomerPromiseDate(leadDays == null ? null : LocalDate.now().plusDays(leadDays))
                    .setSupportQuantity(deliverable).setStatus("PARTIAL_ONLY")
                    .setAssumptions(List.of("仅覆盖当前WMS质量净可交量，不代表剩余量承诺", "分批发运须销售、PMC和客户确认")));
        }
        return rows;
    }

    private OmsDeliveryPromiseDTO.LineAssessment unavailable(OmsOrderDetailsEntity detail, Exception exception) {
        return new OmsDeliveryPromiseDTO.LineAssessment().setOrderDetailId(detail.getId())
                .setProductCode(detail.getProductCode()).setProductName(detail.getProductName())
                .setProductUnit(detail.getProductUnit()).setCurrentPromisedDate(detail.getDeliverTime())
                .setOrderedQuantity(detail.getDeliverNum()).setPromiseStatus("INSUFFICIENT_EVIDENCE")
                .setConfidence("LOW").setBlockers(List.of("履约事实查询失败：" + safeMessage(exception)))
                .setEvidenceRefs(List.of("OMS:oms_order_details:" + detail.getId()));
    }

    private List<String> recommendedActions(List<OmsDeliveryPromiseDTO.LineAssessment> lines, boolean complete) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (lines.stream().anyMatch(line -> "LATE_RISK".equals(line.getPromiseStatus()))) {
            actions.add("由PMC基于瓶颈行发起有限产能重排或供应催交情景，并重新计算候选日期");
        }
        if (lines.stream().anyMatch(line -> "INTERNAL_DATE_ONLY".equals(line.getPromiseStatus()))) {
            actions.add("补充受控物流提前期或建立正式WMS发运计划后，才能形成客户候选日期");
        }
        if (lines.stream().anyMatch(line -> Set.of("BLOCKED", "INSUFFICIENT_EVIDENCE").contains(line.getPromiseStatus()))) {
            actions.add("先处理来源不一致、供应日期缺口、MES计划结束日缺口或质量净可交异常，再重新问询");
        }
        if (lines.stream().flatMap(line -> line.getScenarios().stream())
                .anyMatch(scenario -> "PARTIAL_SHIPMENT".equals(scenario.getScenarioCode()))) {
            actions.add("可评估当前合格库存先行分批，但数量、客户接受和发运计划须由销售/PMC确认");
        }
        if (complete) actions.add("候选日期仅用于答复草案；订单承诺日期写回和对外发送必须经过销售/PMC确认");
        return new ArrayList<>(actions);
    }

    private String overall(List<OmsDeliveryPromiseDTO.LineAssessment> lines, boolean truncated) {
        if (truncated || lines.stream().anyMatch(line -> "INSUFFICIENT_EVIDENCE".equals(line.getPromiseStatus()))) {
            return "INSUFFICIENT_EVIDENCE";
        }
        if (lines.stream().anyMatch(line -> "BLOCKED".equals(line.getPromiseStatus()))) return "BLOCKED";
        if (lines.stream().anyMatch(line -> "LATE_RISK".equals(line.getPromiseStatus()))) return "LATE_RISK";
        if (lines.stream().anyMatch(line -> "INTERNAL_DATE_ONLY".equals(line.getPromiseStatus())))
            return "INTERNAL_DATE_ONLY";
        if (lines.stream().allMatch(line -> "COMPLETED".equals(line.getPromiseStatus()))) return "COMPLETED";
        return "ON_TIME_SUPPORTED";
    }

    private LambdaQueryWrapper<OmsOrderDetailsEntity> scope(String factory, String orderCode, String productCode) {
        return new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getFactoryCode, factory)
                .eq(OmsOrderDetailsEntity::getOrderCode, orderCode)
                .eq(StrUtil.isNotBlank(productCode), OmsOrderDetailsEntity::getProductCode, productCode);
    }

    private void validate(OmsDeliveryPromiseDTO.Query query) {
        require(query != null && StrUtil.isNotBlank(query.getFactoryCode())
                        && StrUtil.isNotBlank(query.getOrderCode()),
                "交期答复必须提供工厂和销售订单号");
        require(query.getDispatchLeadDays() == null
                        || (query.getDispatchLeadDays() >= 0 && query.getDispatchLeadDays() <= 30),
                "发运提前期须为0至30天");
    }

    private String requireFactory(String requestedFactory) {
        String actual = FactoryContextHolder.getFactoryCode();
        boolean valid = SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null
                && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(actual);
        require(valid, "交期答复必须实名登录并选择实际工厂，不能跳过租户或工厂隔离");
        require(Objects.equals(actual, requestedFactory), "交期答复工厂与当前factory-code不一致");
        return actual;
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return StrUtil.maxLength(StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName()), 300);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Projection(LocalDate date, String basis) {
    }
}
