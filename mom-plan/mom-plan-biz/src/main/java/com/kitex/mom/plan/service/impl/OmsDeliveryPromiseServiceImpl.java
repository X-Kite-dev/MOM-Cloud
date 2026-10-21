package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.oms.dto.OmsDeliveryPromiseDTO;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.plan.mapper.OmsDeliveryPromiseLineMapper;
import com.kitex.mom.plan.mapper.OmsDeliveryPromiseMapper;
import com.kitex.mom.plan.mapper.OmsDeliveryPromiseOperationMapper;
import com.kitex.mom.plan.mapper.OmsOrderChangeVersionMapper;
import com.kitex.mom.plan.service.OmsDeliveryPromiseService;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 客户交期答复业务闭环实现。
 */
@Service
@RequiredArgsConstructor
public class OmsDeliveryPromiseServiceImpl
        extends ServiceImpl<OmsDeliveryPromiseMapper, OmsDeliveryPromiseEntity>
        implements OmsDeliveryPromiseService {

    private static final Set<String> SOURCE_TYPES =
            Set.of("MANUAL", "EXTERNAL_SUGGESTION", "RISK_WORKBENCH");
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "PENDING_CONFIRMATION", "PARTIALLY_CONFIRMED", "ORDER_CHANGE_PENDING", "READY_TO_REPLY");
    private static final Set<String> REPLY_CHANNELS = Set.of("EMAIL", "PHONE", "PORTAL", "WECHAT", "OTHER");

    private final OmsDeliveryPromiseLineMapper lineMapper;
    private final OmsDeliveryPromiseOperationMapper operationMapper;
    private final OmsOrderBaseTrackService trackService;
    private final OmsOrderDetailsService detailsService;
    private final OmsOrderChangeLedgerService changeLedger;
    private final OmsOrderChangeVersionMapper changeVersionMapper;

    @Override
    public OmsDeliveryPromiseDTO.Preparation prepare(String orderCode) {
        Context context = context();
        OmsOrderBaseTrackEntity order = requireOrder(orderCode, context.factory(), false);
        List<OmsOrderDetailsEntity> lines = currentLines(order.getOrderCode(), order.getFactoryCode(), false);
        order.setOrderDetailsList(lines);
        String fact = changeLedger.currentSnapshot(order.getOrderCode(), order.getFactoryCode());
        String candidate = changeLedger.proposalFromOrder(lines);
        return new OmsDeliveryPromiseDTO.Preparation()
                .setOrder(order)
                .setCurrentLines(lines)
                .setFactHash(hash(fact))
                .setCandidateHash(hash(candidate));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OmsDeliveryPromiseDTO.Detail submit(OmsDeliveryPromiseDTO.SubmitCommand command) {
        Context context = context();
        requireExternalRequestId(command.getRequestId());
        require(SOURCE_TYPES.contains(command.getSourceType()), "交期答复来源类型不受支持");
        require(command.getReplyDueTime().isAfter(LocalDateTime.now()), "客户答复期限必须晚于当前时间");
        String requestFingerprint = submitFingerprint(command);
        baseMapper.lockKey("REQUEST:" + context.tenantId() + ":" + command.getRequestId());
        OmsDeliveryPromiseEntity replay = baseMapper.selectOne(
                new LambdaQueryWrapper<OmsDeliveryPromiseEntity>()
                        .eq(OmsDeliveryPromiseEntity::getRequestId, command.getRequestId()));
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                    "提交请求号已被不同的交期答复命令使用");
            requireFactory(replay.getFactoryCode(), context.factory());
            return detail(replay.getId());
        }
        require(operationMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryPromiseOperationEntity>()
                        .eq(OmsDeliveryPromiseOperationEntity::getRequestId, command.getRequestId())) == 0,
                "提交请求号已用于其他交期答复操作");

        OmsOrderBaseTrackEntity order = requireOrder(command.getOrderCode(), context.factory(), true);
        require(OrderStateEnum.REVIEW.getCode().equals(order.getOrderState()),
                "只有已完成订单评审且当前无变更待审的订单可以发起交期答复");
        baseMapper.lockKey("ORDER:" + context.tenantId() + ":" + context.factory() + ":" + order.getOrderCode());
        require(baseMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryPromiseEntity>()
                        .eq(OmsDeliveryPromiseEntity::getFactoryCode, context.factory())
                        .eq(OmsDeliveryPromiseEntity::getOrderCode, order.getOrderCode())
                        .in(OmsDeliveryPromiseEntity::getStatus, ACTIVE_STATUSES)) == 0,
                "该订单已有未结束的交期答复单，请先完成、答复或取消现有流程");

        List<OmsOrderDetailsEntity> current = currentLines(order.getOrderCode(), order.getFactoryCode(), true);
        List<OmsOrderDetailsEntity> proposed = buildProposed(order, current, command.getLines());
        String factSnapshot = changeLedger.currentSnapshot(order.getOrderCode(), order.getFactoryCode());
        String candidateSnapshot = changeLedger.proposalFromOrder(proposed);
        long promiseId = IdWorker.getId();
        OmsDeliveryPromiseEntity promise = new OmsDeliveryPromiseEntity()
                .setPromiseNo("DPR" + promiseId)
                .setRequestId(command.getRequestId())
                .setRequestFingerprint(requestFingerprint)
                .setOrderTrackId(order.getId())
                .setOrderCode(order.getOrderCode())
                .setCustomerCode(order.getCustomerCode())
                .setCustomerName(order.getCustomerName())
                .setSourceType(command.getSourceType())
                .setCustomerRequestRef(command.getCustomerRequestRef().trim())
                .setReason(command.getReason().trim())
                .setStatus("PENDING_CONFIRMATION")
                .setFactSnapshot(factSnapshot)
                .setFactHash(hash(factSnapshot))
                .setCandidateSnapshot(candidateSnapshot)
                .setCandidateHash(hash(candidateSnapshot))
                .setObservedAt(LocalDateTime.now())
                .setReplyDueTime(command.getReplyDueTime())
                .setSubmittedBy(context.user().getUsername())
                .setSubmittedByName(userName(context.user()))
                .setSubmittedTime(LocalDateTime.now())
                .setSalesApproved(false)
                .setPmcApproved(false)
                .setFactoryCode(context.factory());
        promise.setId(promiseId);
        promise.setTenantId(context.tenantId());
        require(baseMapper.insert(promise) == 1, "交期答复主单保存失败");

        List<OmsDeliveryPromiseLineEntity> frozenLines = freezeLines(promise, current, proposed);
        for (OmsDeliveryPromiseLineEntity line : frozenLines) {
            require(lineMapper.insert(line) == 1, "交期答复候选行保存失败");
        }
        audit(promise, command.getRequestId(), requestFingerprint, "SUBMIT", null,
                promise.getStatus(), "冻结客户询期、OMS/PLAN事实和候选交期", context.user());
        return detail(promise.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OmsDeliveryPromiseDTO.Detail confirm(OmsDeliveryPromiseDTO.ConfirmCommand command, String role) {
        require(Set.of("SALES", "PMC").contains(role), "交期答复确认岗位不受支持");
        Context context = context();
        requireExternalRequestId(command.getRequestId());
        String fingerprint = confirmFingerprint(command, role);
        OmsDeliveryPromiseOperationEntity replay = replayOperation(
                command.getRequestId(), command.getPromiseId(), role + "_CONFIRM", fingerprint);
        if (replay != null) {
            return detail(command.getPromiseId());
        }

        OmsDeliveryPromiseEntity selected = requirePromise(command.getPromiseId(), context.factory(), false);
        OmsOrderBaseTrackEntity order = requireOrder(selected.getOrderCode(), context.factory(), true);
        OmsDeliveryPromiseEntity promise = requirePromise(command.getPromiseId(), context.factory(), true);
        require(Objects.equals(order.getId(), promise.getOrderTrackId()), "答复单关联的订单跟踪主记录已变化");
        require(Set.of("PENDING_CONFIRMATION", "PARTIALLY_CONFIRMED").contains(promise.getStatus()),
                "当前交期答复状态不允许岗位确认");
        require(Objects.equals(command.getExpectedFactHash(), promise.getFactHash())
                        && Objects.equals(command.getExpectedCandidateHash(), promise.getCandidateHash()),
                "页面中的交期答复证据版本已过期，请刷新后再确认");

        String currentFact = changeLedger.currentSnapshot(order.getOrderCode(), order.getFactoryCode());
        if (!Objects.equals(hash(currentFact), promise.getFactHash())) {
            String before = promise.getStatus();
            promise.setStatus("STALE");
            require(baseMapper.updateById(promise) == 1, "陈旧交期答复状态保存失败");
            audit(promise, command.getRequestId(), fingerprint, "STALE_DETECTED", before, "STALE",
                    "OMS、PLAN、MRP或库存占用事实已变化，原候选不得继续批准", context.user());
            return detail(promise.getId());
        }

        String otherConfirmer = "SALES".equals(role)
                ? promise.getPmcConfirmedBy() : promise.getSalesConfirmedBy();
        require(StrUtil.isBlank(otherConfirmer)
                        || !Objects.equals(otherConfirmer, context.user().getUsername()),
                "销售确认人与PMC确认人必须使用不同账号");
        require("SALES".equals(role) ? promise.getSalesConfirmedTime() == null
                        : promise.getPmcConfirmedTime() == null,
                "当前岗位已经确认，重复操作必须使用原请求号重放");

        String before = promise.getStatus();
        applyConfirmation(promise, role, command, context.user());
        if (!Boolean.TRUE.equals(command.getApproved())) {
            promise.setStatus("REJECTED");
            require(baseMapper.updateById(promise) == 1, "交期答复驳回状态保存失败");
            audit(promise, command.getRequestId(), fingerprint, role + "_CONFIRM", before,
                    "REJECTED", command.getRemark(), context.user());
            return detail(promise.getId());
        }

        boolean bothApproved = Boolean.TRUE.equals(promise.getSalesApproved())
                && Boolean.TRUE.equals(promise.getPmcApproved());
        promise.setStatus(bothApproved ? "CONFIRMED" : "PARTIALLY_CONFIRMED");
        require(baseMapper.updateById(promise) == 1, "交期答复确认状态保存失败");
        audit(promise, command.getRequestId(), fingerprint, role + "_CONFIRM", before,
                promise.getStatus(), command.getRemark(), context.user());
        if (bothApproved) {
            submitControlledChangeOrReady(promise, order, context.user());
        }
        return detail(promise.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OmsDeliveryPromiseDTO.Detail cancel(OmsDeliveryPromiseDTO.CancelCommand command) {
        Context context = context();
        requireExternalRequestId(command.getRequestId());
        String fingerprint = hash(command.getPromiseId() + "|CANCEL|" + command.getReason().trim());
        if (replayOperation(command.getRequestId(), command.getPromiseId(), "CANCEL", fingerprint) != null) {
            return detail(command.getPromiseId());
        }
        OmsDeliveryPromiseEntity promise = requirePromise(command.getPromiseId(), context.factory(), true);
        require(Set.of("PENDING_CONFIRMATION", "PARTIALLY_CONFIRMED").contains(promise.getStatus()),
                "只有尚未完成双岗确认的交期答复可以取消");
        require(Objects.equals(promise.getSubmittedBy(), context.user().getUsername()),
                "只有原提交人可以取消交期答复");
        String before = promise.getStatus();
        promise.setStatus("CANCELLED").setCancelReason(command.getReason().trim());
        require(baseMapper.updateById(promise) == 1, "交期答复取消失败");
        audit(promise, command.getRequestId(), fingerprint, "CANCEL", before,
                "CANCELLED", command.getReason(), context.user());
        return detail(promise.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OmsDeliveryPromiseDTO.Detail reply(OmsDeliveryPromiseDTO.ReplyCommand command) {
        Context context = context();
        requireExternalRequestId(command.getRequestId());
        require(REPLY_CHANNELS.contains(command.getChannel()), "对客答复渠道不受支持");
        String fingerprint = hash(command.getPromiseId() + "|REPLY|" + command.getChannel()
                + "|" + command.getReference().trim() + "|" + command.getContent().trim());
        if (replayOperation(command.getRequestId(), command.getPromiseId(), "REPLY", fingerprint) != null) {
            return detail(command.getPromiseId());
        }
        OmsDeliveryPromiseEntity selected = requirePromise(command.getPromiseId(), context.factory(), false);
        OmsOrderBaseTrackEntity order = requireOrder(selected.getOrderCode(), context.factory(), true);
        OmsDeliveryPromiseEntity promise = requirePromise(command.getPromiseId(), context.factory(), true);
        require("READY_TO_REPLY".equals(promise.getStatus()), "只有业务审批完成的交期答复可以对客发送");
        List<OmsOrderDetailsEntity> current = currentLines(order.getOrderCode(), order.getFactoryCode(), true);
        require(Objects.equals(scheduleSnapshot(current), frozenScheduleSnapshot(promise.getId())),
                "正式订单交付行已偏离批准候选，禁止发送陈旧答复");
        String before = promise.getStatus();
        promise.setStatus("REPLIED")
                .setReplyChannel(command.getChannel())
                .setReplyReference(command.getReference().trim())
                .setReplyContent(command.getContent().trim())
                .setRepliedBy(context.user().getUsername())
                .setRepliedByName(userName(context.user()))
                .setRepliedTime(LocalDateTime.now());
        require(baseMapper.updateById(promise) == 1, "对客答复凭证保存失败");
        audit(promise, command.getRequestId(), fingerprint, "REPLY", before,
                "REPLIED", command.getReference(), context.user());
        return detail(promise.getId());
    }

    @Override
    public OmsDeliveryPromiseDTO.Detail detail(Long id) {
        Context context = context();
        OmsDeliveryPromiseEntity promise = requirePromise(id, context.factory(), false);
        List<OmsDeliveryPromiseLineEntity> lines = lineMapper.selectList(
                new LambdaQueryWrapper<OmsDeliveryPromiseLineEntity>()
                        .eq(OmsDeliveryPromiseLineEntity::getPromiseId, id)
                        .eq(OmsDeliveryPromiseLineEntity::getFactoryCode, context.factory())
                        .orderByAsc(OmsDeliveryPromiseLineEntity::getLineNo)
                        .orderByAsc(OmsDeliveryPromiseLineEntity::getId));
        List<OmsDeliveryPromiseOperationEntity> operations = operationMapper.selectList(
                new LambdaQueryWrapper<OmsDeliveryPromiseOperationEntity>()
                        .eq(OmsDeliveryPromiseOperationEntity::getPromiseId, id)
                        .eq(OmsDeliveryPromiseOperationEntity::getFactoryCode, context.factory())
                        .orderByAsc(OmsDeliveryPromiseOperationEntity::getOperationTime)
                        .orderByAsc(OmsDeliveryPromiseOperationEntity::getId));
        return new OmsDeliveryPromiseDTO.Detail()
                .setPromise(promise).setLines(lines).setOperations(operations);
    }

    private void submitControlledChangeOrReady(OmsDeliveryPromiseEntity promise,
                                               OmsOrderBaseTrackEntity order,
                                               MomUser operator) {
        List<OmsOrderDetailsEntity> current = currentLines(order.getOrderCode(), order.getFactoryCode(), true);
        if (Objects.equals(changeLedger.proposalFromOrder(current), promise.getCandidateSnapshot())) {
            promise.setStatus("READY_TO_REPLY").setOrderChangeStatus("NOT_REQUIRED")
                    .setReadyTime(LocalDateTime.now());
            require(baseMapper.updateById(promise) == 1, "交期答复待发送状态保存失败");
            audit(promise, "DP-SYS-READY-" + promise.getId(), hash(promise.getCandidateHash() + "|READY"),
                    "READY", "CONFIRMED", "READY_TO_REPLY", "候选与正式订单一致，无需发起订单变更", operator);
            return;
        }
        List<OmsOrderDetailsEntity> proposed = proposedFromFrozenLines(promise);
        OmsOrderBaseTrackEntity change = new OmsOrderBaseTrackEntity();
        copyOrderHeader(order, change);
        String changeRequestId = "DP-SYS-CHANGE-" + promise.getId();
        change.setChangeRequestId(changeRequestId);
        change.setOrderDetailsList(proposed);
        require(Boolean.TRUE.equals(trackService.change(change)), "交期答复转订单变更失败");
        OmsOrderChangeVersionEntity version = changeVersionMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderChangeVersionEntity>()
                        .eq(OmsOrderChangeVersionEntity::getSubmitRequestId, changeRequestId));
        require(version != null && "SUBMITTED".equals(version.getStatus()), "交期答复未生成待审订单变更版本");
        promise.setStatus("ORDER_CHANGE_PENDING")
                .setChangeRequestId(changeRequestId)
                .setChangeVersionId(version.getId())
                .setOrderChangeStatus(version.getStatus());
        require(baseMapper.updateById(promise) == 1, "交期答复关联订单变更版本失败");
        audit(promise, "DP-SYS-CHANGE-LINK-" + promise.getId(), hash(changeRequestId + "|" + version.getId()),
                "ORDER_CHANGE_SUBMIT", "CONFIRMED", "ORDER_CHANGE_PENDING",
                "双岗确认完成，已转入OMS第" + version.getVersionNo() + "版受控订单变更", operator);
    }

    private List<OmsOrderDetailsEntity> proposedFromFrozenLines(OmsDeliveryPromiseEntity promise) {
        List<OmsDeliveryPromiseLineEntity> lines = lineMapper.selectList(
                new LambdaQueryWrapper<OmsDeliveryPromiseLineEntity>()
                        .eq(OmsDeliveryPromiseLineEntity::getPromiseId, promise.getId())
                        .orderByAsc(OmsDeliveryPromiseLineEntity::getLineNo));
        List<OmsOrderDetailsEntity> proposed = new ArrayList<>();
        for (OmsDeliveryPromiseLineEntity line : lines) {
            OmsOrderDetailsEntity row = new OmsOrderDetailsEntity();
            row.setId(line.getSourceDetailId());
            row.setOrderCode(promise.getOrderCode());
            row.setFactoryCode(promise.getFactoryCode());
            row.setProductCode(line.getProductCode());
            row.setProductName(line.getProductName());
            row.setProductSpec(line.getProductSpec());
            row.setProductUnit(line.getProductUnit());
            row.setDeliverTime(line.getProposedDeliverTime());
            row.setDeliverNum(line.getProposedDeliverNum());
            row.setRemark(line.getRemark());
            proposed.add(row);
        }
        require(Objects.equals(changeLedger.proposalFromOrder(proposed), promise.getCandidateSnapshot()),
                "交期答复冻结候选行与候选指纹不一致");
        return proposed;
    }

    private List<OmsOrderDetailsEntity> buildProposed(OmsOrderBaseTrackEntity order,
                                                      List<OmsOrderDetailsEntity> current,
                                                      List<OmsDeliveryPromiseDTO.CandidateLine> commands) {
        Map<Long, OmsOrderDetailsEntity> byId = current.stream()
                .collect(Collectors.toMap(OmsOrderDetailsEntity::getId, row -> row));
        Set<Long> usedIds = new HashSet<>();
        Set<String> knownProducts = current.stream().map(OmsOrderDetailsEntity::getProductCode)
                .collect(Collectors.toSet());
        Set<String> productDates = new HashSet<>();
        List<OmsOrderDetailsEntity> proposed = new ArrayList<>();
        for (OmsDeliveryPromiseDTO.CandidateLine command : commands) {
            require(!command.getProposedDeliverTime().isBefore(LocalDate.now()), "拟答复交期不能早于今天");
            OmsOrderDetailsEntity source = command.getSourceDetailId() == null
                    ? null : byId.get(command.getSourceDetailId());
            require(command.getSourceDetailId() == null || source != null,
                    "交期候选包含不属于当前订单的来源行");
            require(command.getSourceDetailId() == null || usedIds.add(command.getSourceDetailId()),
                    "一个来源订单行只能保留一次；拆分出的其余候选行请不要携带来源行ID");
            String productCode = source == null ? StrUtil.trim(command.getProductCode()) : source.getProductCode();
            require(StrUtil.isNotBlank(productCode) && knownProducts.contains(productCode),
                    "交期答复不能新增订单中不存在的产品");
            String productDate = productCode + "|" + command.getProposedDeliverTime();
            require(productDates.add(productDate), "同一产品同一拟答复日期只能保留一行");
            OmsOrderDetailsEntity row = new OmsOrderDetailsEntity();
            row.setId(source == null ? null : source.getId());
            row.setOrderCode(order.getOrderCode());
            row.setFactoryCode(order.getFactoryCode());
            row.setProductCode(productCode);
            row.setProductName(source == null ? requiredText(command.getProductName(), "新增候选行产品名称")
                    : source.getProductName());
            row.setProductSpec(source == null ? StrUtil.trim(command.getProductSpec()) : source.getProductSpec());
            row.setProductUnit(source == null ? requiredText(command.getProductUnit(), "新增候选行产品单位")
                    : source.getProductUnit());
            row.setDeliverTime(command.getProposedDeliverTime());
            row.setDeliverNum(command.getProposedDeliverNum());
            row.setRemark(StrUtil.trim(command.getRemark()));
            proposed.add(row);
        }
        assertProductQuantityConserved(current, proposed);
        return proposed;
    }

    private void assertProductQuantityConserved(List<OmsOrderDetailsEntity> current,
                                                List<OmsOrderDetailsEntity> proposed) {
        Map<String, BigDecimal> before = sumByProduct(current);
        Map<String, BigDecimal> after = sumByProduct(proposed);
        require(before.keySet().equals(after.keySet()), "交期答复只能调整交期或拆并交付批次，不能增删产品");
        for (Map.Entry<String, BigDecimal> entry : before.entrySet()) {
            require(entry.getValue().compareTo(after.get(entry.getKey())) == 0,
                    "产品[" + entry.getKey() + "]候选总量必须等于订单总量；数量变更请走商业订单变更");
        }
    }

    private Map<String, BigDecimal> sumByProduct(List<OmsOrderDetailsEntity> lines) {
        return lines.stream().collect(Collectors.groupingBy(OmsOrderDetailsEntity::getProductCode,
                LinkedHashMap::new, Collectors.reducing(BigDecimal.ZERO,
                        OmsOrderDetailsEntity::getDeliverNum, BigDecimal::add)));
    }

    private List<OmsDeliveryPromiseLineEntity> freezeLines(OmsDeliveryPromiseEntity promise,
                                                           List<OmsOrderDetailsEntity> current,
                                                           List<OmsOrderDetailsEntity> proposed) {
        Map<Long, OmsOrderDetailsEntity> currentById = current.stream()
                .collect(Collectors.toMap(OmsOrderDetailsEntity::getId, row -> row));
        List<OmsDeliveryPromiseLineEntity> result = new ArrayList<>();
        for (int index = 0; index < proposed.size(); index++) {
            OmsOrderDetailsEntity row = proposed.get(index);
            OmsOrderDetailsEntity source = row.getId() == null ? null : currentById.get(row.getId());
            OmsDeliveryPromiseLineEntity frozen = new OmsDeliveryPromiseLineEntity()
                    .setPromiseId(promise.getId()).setLineNo(index + 1).setSourceDetailId(row.getId())
                    .setProductCode(row.getProductCode()).setProductName(row.getProductName())
                    .setProductSpec(row.getProductSpec()).setProductUnit(row.getProductUnit())
                    .setCurrentDeliverTime(source == null ? null : source.getDeliverTime())
                    .setCurrentDeliverNum(source == null ? null : source.getDeliverNum())
                    .setProposedDeliverTime(row.getDeliverTime()).setProposedDeliverNum(row.getDeliverNum())
                    .setRemark(row.getRemark()).setFactoryCode(promise.getFactoryCode());
            frozen.setTenantId(promise.getTenantId());
            result.add(frozen);
        }
        return result;
    }

    private void applyConfirmation(OmsDeliveryPromiseEntity promise, String role,
                                   OmsDeliveryPromiseDTO.ConfirmCommand command, MomUser user) {
        if ("SALES".equals(role)) {
            promise.setSalesApproved(command.getApproved()).setSalesConfirmedBy(user.getUsername())
                    .setSalesConfirmedByName(userName(user)).setSalesRemark(command.getRemark().trim())
                    .setSalesConfirmedTime(LocalDateTime.now());
        } else {
            promise.setPmcApproved(command.getApproved()).setPmcConfirmedBy(user.getUsername())
                    .setPmcConfirmedByName(userName(user)).setPmcRemark(command.getRemark().trim())
                    .setPmcConfirmedTime(LocalDateTime.now());
        }
    }

    private void copyOrderHeader(OmsOrderBaseTrackEntity source, OmsOrderBaseTrackEntity target) {
        target.setId(source.getId());
        target.setOrderCode(source.getOrderCode());
        target.setOrderReviewCode(source.getOrderReviewCode());
        target.setOrderTrackCode(source.getOrderTrackCode());
        target.setProductCode(source.getProductCode());
        target.setProductName(source.getProductName());
        target.setProductSpec(source.getProductSpec());
        target.setCustomerCode(source.getCustomerCode());
        target.setCustomerName(source.getCustomerName());
        target.setCustomerPartCode(source.getCustomerPartCode());
        target.setOrderType(source.getOrderType());
        target.setOrderState(source.getOrderState());
        target.setFactoryCode(source.getFactoryCode());
    }

    private OmsOrderBaseTrackEntity requireOrder(String orderCode, String factory, boolean lock) {
        require(StrUtil.isNotBlank(orderCode), "销售订单号不能为空");
        LambdaQueryWrapper<OmsOrderBaseTrackEntity> query = new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderCode.trim())
                .eq(OmsOrderBaseTrackEntity::getFactoryCode, factory);
        if (lock) {
            query.last("FOR UPDATE");
        }
        OmsOrderBaseTrackEntity order = trackService.getOne(query, false);
        require(order != null, "当前工厂不存在唯一的订单跟踪记录：" + orderCode);
        require(trackService.count(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                        .eq(OmsOrderBaseTrackEntity::getOrderCode, orderCode.trim())) == 1,
                "订单号跨工厂或重复，不能建立可信交期答复");
        return order;
    }

    private List<OmsOrderDetailsEntity> currentLines(String orderCode, String factory, boolean lock) {
        LambdaQueryWrapper<OmsOrderDetailsEntity> query = new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, orderCode)
                .eq(OmsOrderDetailsEntity::getFactoryCode, factory)
                .orderByAsc(OmsOrderDetailsEntity::getId);
        if (lock) {
            query.last("FOR UPDATE");
        }
        List<OmsOrderDetailsEntity> lines = detailsService.list(query);
        require(!lines.isEmpty(), "销售订单缺少交付行，不能发起交期答复");
        require(lines.stream().allMatch(line -> StrUtil.isNotBlank(line.getProductCode())
                        && StrUtil.isNotBlank(line.getProductName()) && StrUtil.isNotBlank(line.getProductUnit())
                        && line.getDeliverTime() != null && line.getDeliverNum() != null
                        && line.getDeliverNum().signum() > 0
                        && Objects.equals(line.getFactoryCode(), factory)),
                "销售订单存在产品、单位、交期、正数数量或工厂不完整的历史交付行，请先治理数据");
        return lines;
    }

    private OmsDeliveryPromiseEntity requirePromise(Long id, String factory, boolean lock) {
        require(id != null, "交期答复单ID不能为空");
        LambdaQueryWrapper<OmsDeliveryPromiseEntity> query = new LambdaQueryWrapper<OmsDeliveryPromiseEntity>()
                .eq(OmsDeliveryPromiseEntity::getId, id)
                .eq(OmsDeliveryPromiseEntity::getFactoryCode, factory);
        if (lock) {
            query.last("FOR UPDATE");
        }
        OmsDeliveryPromiseEntity promise = baseMapper.selectOne(query);
        require(promise != null, "交期答复单不存在或不属于当前工厂");
        return promise;
    }

    private OmsDeliveryPromiseOperationEntity replayOperation(String requestId, Long promiseId,
                                                              String type, String fingerprint) {
        Context context = context();
        baseMapper.lockKey("REQUEST:" + context.tenantId() + ":" + requestId);
        OmsDeliveryPromiseOperationEntity operation = operationMapper.selectOne(
                new LambdaQueryWrapper<OmsDeliveryPromiseOperationEntity>()
                        .eq(OmsDeliveryPromiseOperationEntity::getRequestId, requestId));
        if (operation == null) {
            require(baseMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryPromiseEntity>()
                            .eq(OmsDeliveryPromiseEntity::getRequestId, requestId)) == 0,
                    "操作请求号已用于交期答复提交");
            return null;
        }
        boolean compatibleType = Objects.equals(operation.getOperationType(), type)
                || (type.endsWith("_CONFIRM") && "STALE_DETECTED".equals(operation.getOperationType()));
        require(Objects.equals(operation.getPromiseId(), promiseId)
                        && compatibleType
                        && Objects.equals(operation.getRequestFingerprint(), fingerprint),
                "操作请求号已被不同的交期答复命令使用");
        requireFactory(operation.getFactoryCode(), context.factory());
        return operation;
    }

    private void audit(OmsDeliveryPromiseEntity promise, String requestId, String fingerprint,
                       String operationType, String before, String after, String content, MomUser user) {
        OmsDeliveryPromiseOperationEntity operation = new OmsDeliveryPromiseOperationEntity()
                .setRequestId(requestId).setRequestFingerprint(fingerprint).setPromiseId(promise.getId())
                .setOperationType(operationType).setBeforeStatus(before).setAfterStatus(after)
                .setContent(content).setOperatorCode(user.getUsername()).setOperatorName(userName(user))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(promise.getFactoryCode());
        operation.setTenantId(promise.getTenantId());
        require(operationMapper.insert(operation) == 1, "交期答复操作履历保存失败");
    }

    private String submitFingerprint(OmsDeliveryPromiseDTO.SubmitCommand command) {
        List<List<String>> lines = command.getLines().stream()
                .map(line -> List.of(String.valueOf(line.getSourceDetailId()), StrUtil.nullToEmpty(line.getProductCode()),
                        StrUtil.nullToEmpty(line.getProductName()), StrUtil.nullToEmpty(line.getProductSpec()),
                        StrUtil.nullToEmpty(line.getProductUnit()),
                        String.valueOf(line.getProposedDeliverTime()), decimal(line.getProposedDeliverNum()),
                        StrUtil.nullToEmpty(line.getRemark())))
                .sorted(Comparator.comparing(JSONUtil::toJsonStr)).toList();
        return hash(JSONUtil.toJsonStr(List.of(command.getOrderCode().trim(), command.getSourceType(),
                command.getCustomerRequestRef().trim(), command.getReason().trim(),
                command.getReplyDueTime(), lines)));
    }

    /**
     * 对客发送前只比较正式业务交付内容，忽略拆分行落库后生成的新主键。
     */
    private String scheduleSnapshot(List<OmsOrderDetailsEntity> lines) {
        List<List<String>> facts = lines.stream().map(line -> List.of(
                        StrUtil.nullToEmpty(line.getProductCode()), StrUtil.nullToEmpty(line.getProductName()),
                        StrUtil.nullToEmpty(line.getProductSpec()), StrUtil.nullToEmpty(line.getProductUnit()),
                        String.valueOf(line.getDeliverTime()), decimal(line.getDeliverNum()),
                        StrUtil.nullToEmpty(line.getRemark())))
                .sorted(Comparator.comparing(JSONUtil::toJsonStr)).toList();
        return JSONUtil.toJsonStr(facts);
    }

    private String frozenScheduleSnapshot(Long promiseId) {
        List<OmsDeliveryPromiseLineEntity> lines = lineMapper.selectList(
                new LambdaQueryWrapper<OmsDeliveryPromiseLineEntity>()
                        .eq(OmsDeliveryPromiseLineEntity::getPromiseId, promiseId)
                        .orderByAsc(OmsDeliveryPromiseLineEntity::getLineNo));
        List<List<String>> facts = lines.stream().map(line -> List.of(
                        StrUtil.nullToEmpty(line.getProductCode()), StrUtil.nullToEmpty(line.getProductName()),
                        StrUtil.nullToEmpty(line.getProductSpec()), StrUtil.nullToEmpty(line.getProductUnit()),
                        String.valueOf(line.getProposedDeliverTime()), decimal(line.getProposedDeliverNum()),
                        StrUtil.nullToEmpty(line.getRemark())))
                .sorted(Comparator.comparing(JSONUtil::toJsonStr)).toList();
        return JSONUtil.toJsonStr(facts);
    }

    private String confirmFingerprint(OmsDeliveryPromiseDTO.ConfirmCommand command, String role) {
        return hash(JSONUtil.toJsonStr(List.of(command.getPromiseId(), role, command.getApproved(),
                command.getExpectedFactHash(), command.getExpectedCandidateHash(), command.getRemark().trim())));
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("交期答复指纹生成失败", exception);
        }
    }

    private String userName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private String requiredText(String value, String label) {
        require(StrUtil.isNotBlank(value), label + "不能为空");
        return value.trim();
    }

    private void requireExternalRequestId(String requestId) {
        require(StrUtil.isNotBlank(requestId) && !requestId.startsWith("DP-SYS-"),
                "DP-SYS-前缀由系统内部履历保留，请更换业务请求号");
    }

    private void requireFactory(String actual, String expected) {
        require(StrUtil.isNotBlank(actual) && Objects.equals(actual, expected), "当前工厂无权操作该交期答复");
    }

    private Context context() {
        Long tenantId = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        MomUser user = SecurityUtils.getUser();
        require(tenantId != null && StrUtil.isNotBlank(factory) && user != null
                        && StrUtil.isNotBlank(user.getUsername()),
                "交期答复必须在已登录、明确租户和factory-code工厂上下文中操作");
        return new Context(tenantId, factory, user);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    private record Context(Long tenantId, String factory, MomUser user) {
    }
}
