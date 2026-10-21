package com.kitex.mom.wms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.wms.dto.WmsShipmentExceptionResolutionCommand;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.DeliverBaseSourceEnum;
import com.kitex.mom.wms.enums.DeliverReturnStateEnum;
import com.kitex.mom.wms.enums.DeliverStateEnum;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsDeliverGoodsDetailsService;
import com.kitex.mom.wms.service.WmsDeliverGoodsReturnService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 客户实收异常协调执行：数量守恒、异岗审批、退货/补发生成、结算可靠回传与结案对账。
 */
@Service
@RequiredArgsConstructor
public class WmsShipmentExceptionResolutionService {
    private static final String PENDING = "PENDING_APPROVAL", EXECUTING = "EXECUTING", CLOSED = "CLOSED", REJECTED = "REJECTED";
    private final WmsShipmentExceptionResolutionMapper mapper;
    private final WmsShipmentExceptionResolutionDetailMapper detailMapper;
    private final WmsShipmentExceptionResolutionOperationMapper operationMapper;
    private final WmsShipmentCustodyMapper custodyMapper;
    private final WmsShipmentReceiptDetailMapper receiptMapper;
    private final WmsOmsCustomerReceiptOutboxMapper receiptOutboxMapper;
    private final WmsOmsCustomerExceptionResolutionOutboxService settlementOutboxService;
    private final WmsOmsCustomerExceptionResolutionOutboxMapper settlementOutboxMapper;
    private final WmsOmsSalesReturnEventOutboxMapper returnOutboxMapper;
    private final WmsDeliverGoodsBaseMapper deliveryMapper;
    private final WmsDeliverGoodsDetailsMapper deliveryDetailMapper;
    private final WmsDeliverGoodsDetailsService deliveryDetailsService;
    private final WmsOutBillMapper outBillMapper;
    private final WmsDeliverGoodsReturnService returnService;

    public WmsShipmentExceptionResolutionEntity byCustody(Long custodyId) {
        require(custodyId != null, "物流保管链ID不能为空");
        WmsShipmentCustodyEntity custody = custodyMapper.selectById(custodyId);
        require(custody != null, "物流保管链不存在");
        requireFactory(custody.getFactoryCode());
        WmsShipmentExceptionResolutionEntity row = mapper.selectOne(new LambdaQueryWrapper<WmsShipmentExceptionResolutionEntity>().eq(WmsShipmentExceptionResolutionEntity::getCustodyId, custodyId), false);
        return row == null ? null : view(row.getId());
    }

    public WmsShipmentExceptionResolutionEntity view(Long id) {
        WmsShipmentExceptionResolutionEntity row = mapper.selectById(id);
        require(row != null, "客户异常处置单不存在");
        requireFactory(row.getFactoryCode());
        List<WmsShipmentExceptionResolutionDetailEntity> lines = detailMapper.selectList(new LambdaQueryWrapper<WmsShipmentExceptionResolutionDetailEntity>().eq(WmsShipmentExceptionResolutionDetailEntity::getCaseId, id).orderByAsc(WmsShipmentExceptionResolutionDetailEntity::getId));
        row.setDetails(lines);
        row.setOperations(operationMapper.selectList(new LambdaQueryWrapper<WmsShipmentExceptionResolutionOperationEntity>().eq(WmsShipmentExceptionResolutionOperationEntity::getCaseId, id).orderByAsc(WmsShipmentExceptionResolutionOperationEntity::getId)));
        Completion c = completion(row, lines);
        row.setReturnCompleted(c.returnDone()).setReissueCompleted(c.reissueDone()).setSettlementCompleted(c.settlementDone());
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsShipmentExceptionResolutionEntity apply(WmsShipmentExceptionResolutionCommand.Apply cmd) {
        require(cmd != null && cmd.getCustodyId() != null && CollUtil.isNotEmpty(cmd.getLines()), "异常处置申请缺少保管链或逐行方案");
        String request = required(cmd.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(cmd);
        WmsShipmentExceptionResolutionOperationEntity replay = replay(request, "APPLY", snapshot);
        if (replay != null) return view(replay.getCaseId());
        mapper.lockKey("CUSTODY:" + cmd.getCustodyId());
        WmsShipmentCustodyEntity custody = custodyMapper.selectOne(new LambdaQueryWrapper<WmsShipmentCustodyEntity>().eq(WmsShipmentCustodyEntity::getId, cmd.getCustodyId()).last("FOR UPDATE"), false);
        require(custody != null && "EXCEPTION".equals(custody.getState()), "仅客户实收异常的保管链可发起处置");
        requireFactory(custody.getFactoryCode());
        require(mapper.selectCount(new LambdaQueryWrapper<WmsShipmentExceptionResolutionEntity>().eq(WmsShipmentExceptionResolutionEntity::getCustodyId, custody.getId())) == 0, "该客户实收异常已有处置单");
        List<WmsShipmentReceiptDetailEntity> sources = receiptMapper.selectList(new LambdaQueryWrapper<WmsShipmentReceiptDetailEntity>().eq(WmsShipmentReceiptDetailEntity::getCustodyId, custody.getId()).orderByAsc(WmsShipmentReceiptDetailEntity::getId).last("FOR SHARE"));
        List<WmsShipmentReceiptDetailEntity> exceptions = sources.stream().filter(x -> exception(x).signum() > 0).toList();
        require(!exceptions.isEmpty(), "客户实收明细不存在异常量");
        Map<Long, WmsShipmentExceptionResolutionCommand.Line> input = cmd.getLines().stream().collect(Collectors.toMap(WmsShipmentExceptionResolutionCommand.Line::getReceiptDetailId, Function.identity(), (a, b) -> {
            throw new CheckedException("同一客户实收异常明细不能重复");
        }));
        require(input.size() == exceptions.size() && exceptions.stream().allMatch(x -> input.containsKey(x.getId())), "处置方案必须完整覆盖全部异常明细");
        List<WmsOmsCustomerReceiptOutboxEntity> receipts = receiptOutboxMapper.selectList(new LambdaQueryWrapper<WmsOmsCustomerReceiptOutboxEntity>().eq(WmsOmsCustomerReceiptOutboxEntity::getCustodyId, custody.getId()));
        require(!receipts.isEmpty() && receipts.stream().allMatch(x -> "SUCCEEDED".equals(x.getStatus())), "客户实收事实尚未全部进入OMS永久账，禁止先行处置");
        boolean needsReturn = false;
        BigDecimal refundAmount = BigDecimal.ZERO, concessionAmount = BigDecimal.ZERO, claimAmount = BigDecimal.ZERO;
        List<WmsShipmentExceptionResolutionDetailEntity> saved = new ArrayList<>();
        for (WmsShipmentReceiptDetailEntity source : exceptions) {
            WmsShipmentExceptionResolutionCommand.Line x = input.get(source.getId());
            BigDecimal total = exception(source), returned = nonnegative(x.getReturnNum(), "退回量"), reissue = nonnegative(x.getReissueNum(), "补发量"), refund = nonnegative(x.getRefundNum(), "退款量"), concession = nonnegative(x.getConcessionNum(), "让步量"), claim = nonnegative(x.getClaimNum(), "索赔量"), ra = nonnegative(x.getRefundAmount(), "退款金额"), ca = nonnegative(x.getConcessionAmount(), "让步金额"), cla = nonnegative(x.getClaimAmount(), "索赔金额");
            require(reissue.add(refund).add(concession).compareTo(total) == 0, "异常明细" + source.getId() + "的补发、退款和让步数量必须完整覆盖异常量");
            require(returned.compareTo(source.getRejectedNum().add(source.getDamagedNum())) <= 0, "退回量不能包含丢失量且不能超过拒收与破损量");
            require(claim.compareTo(total) <= 0, "索赔量不能超过异常量");
            require(claim.signum() == 0 || !"CUSTOMER".equals(source.getResponsibility()), "客户责任异常不能转为对外索赔");
            require(refund.signum() == 0 || ra.signum() > 0, "退款量大于零时退款金额必须为正");
            require(concession.signum() == 0 || ca.signum() > 0, "让步量大于零时让步金额必须为正");
            require(claim.signum() == 0 || cla.signum() > 0, "索赔量大于零时索赔金额必须为正");
            needsReturn |= returned.signum() > 0;
            refundAmount = refundAmount.add(ra);
            concessionAmount = concessionAmount.add(ca);
            claimAmount = claimAmount.add(cla);
            saved.add(new WmsShipmentExceptionResolutionDetailEntity().setReceiptDetailId(source.getId()).setExceptionNum(total).setReturnNum(returned).setReissueNum(reissue).setRefundNum(refund).setConcessionNum(concession).setClaimNum(claim).setRefundAmount(ra).setConcessionAmount(ca).setClaimAmount(cla).setRemark(StrUtil.trim(x.getRemark())));
        }
        if (needsReturn) {
            required(cmd.getReturnWarehouse(), 100, "退货仓库");
            required(cmd.getReturnWarehouseName(), 200, "退货仓库名称");
        }
        MomUser u = user();
        LocalDateTime now = LocalDateTime.now();
        WmsShipmentExceptionResolutionEntity row = new WmsShipmentExceptionResolutionEntity().setCaseNo("SHIP-EX-" + request).setCustodyId(custody.getId()).setState(PENDING).setReturnWarehouse(StrUtil.trim(cmd.getReturnWarehouse())).setReturnWarehouseName(StrUtil.trim(cmd.getReturnWarehouseName())).setApplicantCode(u.getUsername()).setApplicantName(name(u)).setAppliedTime(now).setReason(required(cmd.getReason(), 500, "申请原因")).setEvidence(required(cmd.getEvidence(), 500, "申请证据")).setRefundAmount(refundAmount).setConcessionAmount(concessionAmount).setClaimAmount(claimAmount).setFactoryCode(custody.getFactoryCode());
        require(mapper.insert(row) == 1, "客户异常处置申请保存失败");
        for (WmsShipmentExceptionResolutionDetailEntity x : saved) {
            x.setCaseId(row.getId());
            require(detailMapper.insert(x) == 1, "客户异常处置明细保存失败");
        }
        operation(row, request, "APPLY", "EXCEPTION", PENDING, cmd.getReason(), cmd.getEvidence(), snapshot, u, now);
        return view(row.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsShipmentExceptionResolutionEntity approve(Long id, WmsShipmentExceptionResolutionCommand.Decision cmd) {
        require(cmd != null, "审批命令不能为空");
        String request = required(cmd.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(cmd);
        WmsShipmentExceptionResolutionOperationEntity replay = replay(request, "APPROVE", snapshot);
        if (replay != null) return view(replay.getCaseId());
        WmsShipmentExceptionResolutionEntity row = lock(id);
        require(PENDING.equals(row.getState()), "仅待审批异常处置单可通过");
        MomUser u = user();
        require(!Objects.equals(row.getApplicantCode(), u.getUsername()), "异常处置申请人与审批人必须分离");
        List<WmsShipmentExceptionResolutionDetailEntity> lines = details(id, true);
        Map<Long, WmsShipmentReceiptDetailEntity> sources = receiptSources(lines, true);
        String before = row.getState();
        row.setApproverCode(u.getUsername()).setApproverName(name(u)).setApprovedTime(LocalDateTime.now()).setApprovalRemark(required(cmd.getRemark(), 500, "审批意见")).setState(EXECUTING);
        require(mapper.updateById(row) == 1, "客户异常处置审批状态保存失败");
        BigDecimal returnTotal = sum(lines, WmsShipmentExceptionResolutionDetailEntity::getReturnNum), reissueTotal = sum(lines, WmsShipmentExceptionResolutionDetailEntity::getReissueNum);
        if (returnTotal.signum() > 0) row.setReturnId(createReturn(row, lines, sources));
        if (reissueTotal.signum() > 0) row.setReissueDeliveryId(createReissue(row, lines, sources));
        require(mapper.updateById(row) == 1, "客户异常处置下游单据来源保存失败");
        operation(row, request, "APPROVE", before, EXECUTING, cmd.getRemark(), cmd.getEvidence(), snapshot, u, LocalDateTime.now());
        return view(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsShipmentExceptionResolutionEntity reject(Long id, WmsShipmentExceptionResolutionCommand.Decision cmd) {
        require(cmd != null, "驳回命令不能为空");
        String request = required(cmd.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(cmd);
        WmsShipmentExceptionResolutionOperationEntity replay = replay(request, "REJECT", snapshot);
        if (replay != null) return view(replay.getCaseId());
        WmsShipmentExceptionResolutionEntity row = lock(id);
        require(PENDING.equals(row.getState()), "仅待审批异常处置单可驳回");
        MomUser u = user();
        require(!Objects.equals(row.getApplicantCode(), u.getUsername()), "异常处置申请人与审批人必须分离");
        String before = row.getState();
        row.setState(REJECTED).setApproverCode(u.getUsername()).setApproverName(name(u)).setApprovedTime(LocalDateTime.now()).setApprovalRemark(required(cmd.getRemark(), 500, "驳回原因"));
        require(mapper.updateById(row) == 1, "客户异常处置驳回保存失败");
        operation(row, request, "REJECT", before, REJECTED, cmd.getRemark(), cmd.getEvidence(), snapshot, u, LocalDateTime.now());
        return view(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsShipmentExceptionResolutionEntity settle(Long id, WmsShipmentExceptionResolutionCommand.Settlement cmd) {
        require(cmd != null, "结算命令不能为空");
        String request = required(cmd.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(cmd);
        WmsShipmentExceptionResolutionOperationEntity replay = replay(request, "SETTLE", snapshot);
        if (replay != null) return view(replay.getCaseId());
        WmsShipmentExceptionResolutionEntity row = lock(id);
        require(EXECUTING.equals(row.getState()), "仅执行中的异常处置单可登记结算");
        require(row.getSettlementTime() == null, "异常处置结算已经登记");
        List<WmsShipmentExceptionResolutionDetailEntity> lines = details(id, true);
        BigDecimal refund = sum(lines, WmsShipmentExceptionResolutionDetailEntity::getRefundNum), concession = sum(lines, WmsShipmentExceptionResolutionDetailEntity::getConcessionNum), claim = sum(lines, WmsShipmentExceptionResolutionDetailEntity::getClaimNum);
        if (refund.signum() > 0) row.setRefundReference(required(cmd.getRefundReference(), 200, "退款凭证号"));
        if (concession.signum() > 0)
            row.setConcessionReference(required(cmd.getConcessionReference(), 200, "让步凭证号"));
        if (claim.signum() > 0) row.setClaimReference(required(cmd.getClaimReference(), 200, "索赔凭证号"));
        row.setSettlementEvidence(required(cmd.getEvidence(), 500, "结算证据")).setSettlementTime(LocalDateTime.now());
        require(mapper.updateById(row) == 1, "异常处置结算证据保存失败");
        Map<Long, WmsShipmentReceiptDetailEntity> sources = receiptSources(lines, true);
        Map<Long, List<WmsShipmentExceptionResolutionDetailEntity>> byOrder = lines.stream().filter(x -> x.getRefundNum().add(x.getConcessionNum()).signum() > 0).collect(Collectors.groupingBy(x -> sources.get(x.getReceiptDetailId()).getOrderDetailId(), LinkedHashMap::new, Collectors.toList()));
        for (List<WmsShipmentExceptionResolutionDetailEntity> group : byOrder.values()) {
            WmsShipmentReceiptDetailEntity source = sources.get(group.get(0).getReceiptDetailId());
            settlementOutboxService.enqueue(row, source, sum(group, WmsShipmentExceptionResolutionDetailEntity::getRefundNum), sum(group, WmsShipmentExceptionResolutionDetailEntity::getConcessionNum), sum(group, WmsShipmentExceptionResolutionDetailEntity::getRefundAmount), sum(group, WmsShipmentExceptionResolutionDetailEntity::getConcessionAmount));
        }
        MomUser u = user();
        operation(row, request, "SETTLE", EXECUTING, EXECUTING, "结算执行凭证", cmd.getEvidence(), snapshot, u, LocalDateTime.now());
        return view(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsShipmentExceptionResolutionEntity reconcile(Long id, WmsShipmentExceptionResolutionCommand.Reconcile cmd) {
        require(cmd != null, "结案对账命令不能为空");
        String request = required(cmd.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(cmd);
        WmsShipmentExceptionResolutionOperationEntity replay = replay(request, "RECONCILE", snapshot);
        if (replay != null) return view(replay.getCaseId());
        WmsShipmentExceptionResolutionEntity row = lock(id);
        require(EXECUTING.equals(row.getState()), "仅执行中的异常处置单可结案对账");
        List<WmsShipmentExceptionResolutionDetailEntity> lines = details(id, true);
        Completion completion = completion(row, lines);
        boolean done = completion.returnDone() && completion.reissueDone() && completion.settlementDone();
        String after = done ? CLOSED : EXECUTING;
        if (done) {
            row.setState(CLOSED).setCloseSummary(required(cmd.getSummary(), 500, "结案摘要")).setClosedTime(LocalDateTime.now());
            require(mapper.updateById(row) == 1, "异常处置结案保存失败");
        }
        MomUser u = user();
        operation(row, request, "RECONCILE", EXECUTING, after, done ? cmd.getSummary() : "下游执行尚未全部完成", required(cmd.getEvidence(), 500, "对账证据"), snapshot, u, LocalDateTime.now());
        return view(id);
    }

    public List<WmsOmsCustomerExceptionResolutionOutboxEntity> omsEvents(Long caseId) {
        view(caseId);
        return settlementOutboxService.listByCase(caseId);
    }

    public WmsOmsCustomerExceptionResolutionOutboxEntity retryOms(Long id) {
        return settlementOutboxService.retry(id);
    }

    public WmsOmsCustomerExceptionResolutionOutboxEntity reconcileOms(Long id) {
        return settlementOutboxService.reconcile(id);
    }

    private Long createReturn(WmsShipmentExceptionResolutionEntity row, List<WmsShipmentExceptionResolutionDetailEntity> lines, Map<Long, WmsShipmentReceiptDetailEntity> sources) {
        WmsShipmentCustodyEntity custody = custodyMapper.selectById(row.getCustodyId());
        WmsDeliverGoodsBaseEntity delivery = deliveryMapper.selectById(custody.getDeliveryId());
        require(delivery != null, "原发货单不存在");
        Map<Long, List<WmsShipmentExceptionResolutionDetailEntity>> groups = lines.stream().filter(x -> x.getReturnNum().signum() > 0).collect(Collectors.groupingBy(x -> sources.get(x.getReceiptDetailId()).getDeliveryDetailId(), LinkedHashMap::new, Collectors.toList()));
        List<WmsDeliverGoodsReturnDetailsEntity> returnLines = new ArrayList<>();
        for (List<WmsShipmentExceptionResolutionDetailEntity> group : groups.values()) {
            WmsShipmentReceiptDetailEntity source = sources.get(group.get(0).getReceiptDetailId());
            WmsDeliverGoodsDetailsEntity original = deliveryDetailMapper.selectById(source.getDeliveryDetailId());
            require(original != null, "原发货明细不存在");
            WmsDeliverGoodsReturnDetailsEntity item = new WmsDeliverGoodsReturnDetailsEntity();
            item.setBaseDetailsId(original.getId());
            item.setOrderCode(original.getOrderCode());
            item.setDeliverPlanCode(original.getDeliverPlanCode());
            item.setProductCode(original.getProductCode());
            item.setProductName(original.getProductName());
            item.setReturnNum(sum(group, WmsShipmentExceptionResolutionDetailEntity::getReturnNum));
            item.setRemark("客户异常处置" + row.getCaseNo());
            returnLines.add(item);
        }
        WmsDeliverGoodsReturnEntity ret = new WmsDeliverGoodsReturnEntity();
        ret.setOrderCode(returnLines.stream().map(WmsDeliverGoodsReturnDetailsEntity::getOrderCode).distinct().count() == 1 ? returnLines.get(0).getOrderCode() : "MULTI");
        ret.setDeliverCode(delivery.getDeliverCode());
        ret.setDeliverPlanCode(returnLines.stream().map(WmsDeliverGoodsReturnDetailsEntity::getDeliverPlanCode).distinct().count() == 1 ? returnLines.get(0).getDeliverPlanCode() : "MULTI");
        ret.setCustomerCode(delivery.getCustomerCode());
        ret.setCustomerName(delivery.getCustomerName());
        ret.setProductCode(returnLines.size() == 1 ? returnLines.get(0).getProductCode() : "MULTI");
        ret.setProductName(returnLines.size() == 1 ? returnLines.get(0).getProductName() : "多产品");
        ret.setReturnNum(returnLines.stream().map(WmsDeliverGoodsReturnDetailsEntity::getReturnNum).reduce(BigDecimal.ZERO, BigDecimal::add));
        ret.setHandlePerson(row.getApproverCode());
        ret.setHandlePersonName(row.getApproverName());
        ret.setReturnWarehouse(row.getReturnWarehouse());
        ret.setReturnWarehouseName(row.getReturnWarehouseName());
        ret.setReturnReason(row.getReason());
        ret.setExceptionResolutionCaseId(row.getId());
        ret.setState(DeliverReturnStateEnum.CREATE.getCode());
        ret.setFactoryCode(row.getFactoryCode());
        ret.setDetailsList(returnLines);
        require(Boolean.TRUE.equals(returnService.saveReturn(ret)), "客户异常处置退货申请创建失败");
        require(Boolean.TRUE.equals(returnService.pass(ret.getId())), "客户异常处置退货通知创建失败");
        return ret.getId();
    }

    private Long createReissue(WmsShipmentExceptionResolutionEntity row, List<WmsShipmentExceptionResolutionDetailEntity> lines, Map<Long, WmsShipmentReceiptDetailEntity> sources) {
        WmsShipmentCustodyEntity custody = custodyMapper.selectById(row.getCustodyId());
        WmsDeliverGoodsBaseEntity originalBase = deliveryMapper.selectById(custody.getDeliveryId());
        require(originalBase != null, "原发货单不存在");
        WmsDeliverGoodsBaseEntity base = new WmsDeliverGoodsBaseEntity();
        base.setDeliverCode(originalBase.getDeliverCode() + "-EX" + row.getId());
        base.setWarehouseCode(originalBase.getWarehouseCode());
        base.setWarehouseName(originalBase.getWarehouseName());
        base.setCustomerCode(originalBase.getCustomerCode());
        base.setCustomerName(originalBase.getCustomerName());
        base.setCustomerWarehouse(originalBase.getCustomerWarehouse());
        base.setCustomerAddress(originalBase.getCustomerAddress());
        base.setDeliverTime(originalBase.getDeliverTime());
        base.setDeliverCarLicense(originalBase.getDeliverCarLicense());
        base.setDeliverPerson(originalBase.getDeliverPerson());
        base.setDeliverPersonMsg(originalBase.getDeliverPersonMsg());
        base.setDeliverState(DeliverStateEnum.NOT_START.getCode());
        base.setReissueSign(YesOrNoStateEnum.YES.getCode());
        base.setIssued(YesOrNoStateEnum.NO.getCode());
        base.setSource(DeliverBaseSourceEnum.PLAN.getCode());
        base.setExceptionResolutionCaseId(row.getId());
        base.setOriginalDeliveryId(originalBase.getId());
        base.setFactoryCode(row.getFactoryCode());
        require(deliveryMapper.insert(base) == 1, "补发发货单保存失败");
        Map<Long, List<WmsShipmentExceptionResolutionDetailEntity>> groups = lines.stream().filter(x -> x.getReissueNum().signum() > 0).collect(Collectors.groupingBy(x -> sources.get(x.getReceiptDetailId()).getDeliveryDetailId(), LinkedHashMap::new, Collectors.toList()));
        for (List<WmsShipmentExceptionResolutionDetailEntity> group : groups.values()) {
            WmsShipmentReceiptDetailEntity source = sources.get(group.get(0).getReceiptDetailId());
            WmsDeliverGoodsDetailsEntity original = deliveryDetailMapper.selectById(source.getDeliveryDetailId());
            require(original != null, "原发货明细不存在");
            WmsDeliverGoodsDetailsEntity d = new WmsDeliverGoodsDetailsEntity();
            d.setDeliverCode(base.getDeliverCode());
            d.setOrderCode(original.getOrderCode());
            d.setDeliverPlanCode(original.getDeliverPlanCode());
            d.setCustomerCode(original.getCustomerCode());
            d.setCustomerName(original.getCustomerName());
            d.setProductCode(original.getProductCode());
            d.setProductName(original.getProductName());
            d.setProductSpec(original.getProductSpec());
            d.setProductUnit(original.getProductUnit());
            d.setDeliverNum(sum(group, WmsShipmentExceptionResolutionDetailEntity::getReissueNum));
            d.setDeliveredNum(BigDecimal.ZERO);
            d.setReturnNum(BigDecimal.ZERO);
            d.setDeliverState(DeliverStateEnum.NOT_START.getCode());
            require(deliveryDetailMapper.insert(d) == 1, "补发发货明细保存失败");
        }
        return base.getId();
    }

    private Completion completion(WmsShipmentExceptionResolutionEntity row, List<WmsShipmentExceptionResolutionDetailEntity> lines) {
        BigDecimal returnQty = sum(lines, WmsShipmentExceptionResolutionDetailEntity::getReturnNum), reissueQty = sum(lines, WmsShipmentExceptionResolutionDetailEntity::getReissueNum), settleQty = sum(lines, WmsShipmentExceptionResolutionDetailEntity::getRefundNum).add(sum(lines, WmsShipmentExceptionResolutionDetailEntity::getConcessionNum)), claimQty = sum(lines, WmsShipmentExceptionResolutionDetailEntity::getClaimNum);
        boolean returnDone = returnQty.signum() == 0;
        if (!returnDone && row.getReturnId() != null) {
            List<WmsOmsSalesReturnEventOutboxEntity> events = returnOutboxMapper.selectList(new LambdaQueryWrapper<WmsOmsSalesReturnEventOutboxEntity>().eq(WmsOmsSalesReturnEventOutboxEntity::getWmsReturnId, row.getReturnId()));
            BigDecimal returned = events.stream().filter(x -> "SUCCEEDED".equals(x.getStatus())).map(WmsOmsSalesReturnEventOutboxEntity::getReturnedNum).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            returnDone = !events.isEmpty() && events.stream().allMatch(x -> "SUCCEEDED".equals(x.getStatus())) && returned.compareTo(returnQty) >= 0;
        }
        boolean reissueDone = reissueQty.signum() == 0;
        if (!reissueDone && row.getReissueDeliveryId() != null) {
            List<WmsDeliverGoodsDetailsEntity> ds = deliveryDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>().eq(WmsDeliverGoodsDetailsEntity::getDeliverCode, Optional.ofNullable(deliveryMapper.selectById(row.getReissueDeliveryId())).map(WmsDeliverGoodsBaseEntity::getDeliverCode).orElse("")));
            List<WmsOutBillEntity> bills = outBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>().eq(WmsOutBillEntity::getDocumentId, row.getReissueDeliveryId()).eq(WmsOutBillEntity::getDocumentTable, DocumentEnum.DELIVER.getCode()));
            List<Long> billIds = bills.stream().map(WmsOutBillEntity::getId).toList();
            List<WmsShipmentCustodyEntity> custodies = billIds.isEmpty() ? List.of() : custodyMapper.selectList(new LambdaQueryWrapper<WmsShipmentCustodyEntity>().in(WmsShipmentCustodyEntity::getOutBillId, billIds));
            List<Long> custodyIds = custodies.stream().map(WmsShipmentCustodyEntity::getId).toList();
            List<WmsOmsCustomerReceiptOutboxEntity> receipts = custodyIds.isEmpty() ? List.of() : receiptOutboxMapper.selectList(new LambdaQueryWrapper<WmsOmsCustomerReceiptOutboxEntity>().in(WmsOmsCustomerReceiptOutboxEntity::getCustodyId, custodyIds));
            reissueDone = !ds.isEmpty() && ds.stream().allMatch(x -> x.getDeliverNum() != null && x.getDeliveredNum() != null && x.getDeliveredNum().compareTo(x.getDeliverNum()) >= 0) && !bills.isEmpty() && custodies.size() == bills.size() && custodies.stream().allMatch(x -> "SIGNED".equals(x.getState())) && !receipts.isEmpty() && receipts.stream().allMatch(x -> "SUCCEEDED".equals(x.getStatus()));
        }
        boolean settlementDone = settleQty.signum() == 0;
        if (!settlementDone) {
            List<WmsOmsCustomerExceptionResolutionOutboxEntity> events = settlementOutboxMapper.selectList(new LambdaQueryWrapper<WmsOmsCustomerExceptionResolutionOutboxEntity>().eq(WmsOmsCustomerExceptionResolutionOutboxEntity::getResolutionCaseId, row.getId()));
            BigDecimal settled = events.stream().filter(x -> "SUCCEEDED".equals(x.getStatus())).map(x -> x.getRefundNum().add(x.getConcessionNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
            settlementDone = !events.isEmpty() && events.stream().allMatch(x -> "SUCCEEDED".equals(x.getStatus())) && settled.compareTo(settleQty) == 0;
        }
        if (claimQty.signum() > 0)
            settlementDone = settlementDone && StrUtil.isNotBlank(row.getClaimReference()) && StrUtil.isNotBlank(row.getSettlementEvidence());
        return new Completion(returnDone, reissueDone, settlementDone);
    }

    private List<WmsShipmentExceptionResolutionDetailEntity> details(Long id, boolean lock) {
        return detailMapper.selectList(new LambdaQueryWrapper<WmsShipmentExceptionResolutionDetailEntity>().eq(WmsShipmentExceptionResolutionDetailEntity::getCaseId, id).orderByAsc(WmsShipmentExceptionResolutionDetailEntity::getId).last(lock ? "FOR UPDATE" : ""));
    }

    private Map<Long, WmsShipmentReceiptDetailEntity> receiptSources(List<WmsShipmentExceptionResolutionDetailEntity> lines, boolean lock) {
        List<Long> ids = lines.stream().map(WmsShipmentExceptionResolutionDetailEntity::getReceiptDetailId).toList();
        Map<Long, WmsShipmentReceiptDetailEntity> result = receiptMapper.selectList(new LambdaQueryWrapper<WmsShipmentReceiptDetailEntity>().in(WmsShipmentReceiptDetailEntity::getId, ids).last(lock ? "FOR SHARE" : "")).stream().collect(Collectors.toMap(WmsShipmentReceiptDetailEntity::getId, Function.identity()));
        require(result.size() == ids.size(), "客户实收异常来源明细缺失");
        return result;
    }

    private WmsShipmentExceptionResolutionEntity lock(Long id) {
        WmsShipmentExceptionResolutionEntity x = mapper.selectOne(new LambdaQueryWrapper<WmsShipmentExceptionResolutionEntity>().eq(WmsShipmentExceptionResolutionEntity::getId, id).last("FOR UPDATE"), false);
        require(x != null, "客户异常处置单不存在");
        requireFactory(x.getFactoryCode());
        return x;
    }

    private WmsShipmentExceptionResolutionOperationEntity replay(String request, String type, String snapshot) {
        WmsShipmentExceptionResolutionOperationEntity x = operationMapper.selectOne(new LambdaQueryWrapper<WmsShipmentExceptionResolutionOperationEntity>().eq(WmsShipmentExceptionResolutionOperationEntity::getRequestId, request), false);
        if (x != null)
            require(type.equals(x.getOperationType()) && snapshot.equals(x.getFactSnapshot()), "请求号已被不同异常处置事实使用");
        return x;
    }

    private void operation(WmsShipmentExceptionResolutionEntity row, String request, String type, String before, String after, String reason, String evidence, String snapshot, MomUser u, LocalDateTime now) {
        WmsShipmentExceptionResolutionOperationEntity x = new WmsShipmentExceptionResolutionOperationEntity().setTenantId(u.getTenantId()).setCaseId(row.getId()).setRequestId(request).setOperationType(type).setBeforeState(before).setAfterState(after).setOperatorCode(u.getUsername()).setOperatorName(name(u)).setOperatedTime(now).setReason(StrUtil.trim(reason)).setEvidence(required(evidence, 500, "操作证据")).setFactSnapshot(snapshot).setFactoryCode(row.getFactoryCode());
        require(operationMapper.insert(x) == 1, "异常处置永久操作履历保存失败");
    }

    private BigDecimal exception(WmsShipmentReceiptDetailEntity x) {
        return x.getRejectedNum().add(x.getDamagedNum()).add(x.getLostNum());
    }

    private BigDecimal nonnegative(BigDecimal x, String label) {
        BigDecimal v = x == null ? BigDecimal.ZERO : x;
        require(v.signum() >= 0, label + "不能为负");
        return v;
    }

    private <T> BigDecimal sum(Collection<T> rows, Function<T, BigDecimal> f) {
        return rows.stream().map(f).map(x -> x == null ? BigDecimal.ZERO : x).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String required(String v, int max, String label) {
        String x = StrUtil.trim(v);
        require(StrUtil.isNotBlank(x) && x.length() <= max, label + "不能为空且不能超过" + max + "字");
        return x;
    }

    private MomUser user() {
        MomUser u = SecurityUtils.getUser();
        require(u != null && StrUtil.isNotBlank(u.getUsername()), "无法识别当前操作人");
        return u;
    }

    private String name(MomUser u) {
        return StrUtil.blankToDefault(u.getName(), u.getUsername());
    }

    private void requireFactory(String f) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(f) && (StrUtil.isBlank(selected) || Objects.equals(selected, f)), "当前工厂无权操作客户异常处置");
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }

    private record Completion(boolean returnDone, boolean reissueDone, boolean settlementDone) {
    }
}
