package com.kitex.mom.wms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsShipmentCustodyCommand;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 销售发运的承运保管链：仓库交接、门岗离场、客户实收与异常冻结。
 */
@Service
@RequiredArgsConstructor
public class WmsShipmentCustodyService {
    private static final String HANDOVER = "HANDOVER", IN_TRANSIT = "IN_TRANSIT", SIGNED = "SIGNED",
            EXCEPTION = "EXCEPTION", CANCELLED = "CANCELLED";
    private final WmsShipmentCustodyMapper custodyMapper;
    private final WmsShipmentCustodyOperationMapper operationMapper;
    private final WmsShipmentReceiptDetailMapper receiptDetailMapper;
    private final WmsOutBillMapper outBillMapper;
    private final WmsOutBillDetailMapper outBillDetailMapper;
    private final WmsOutNoticeDetailMapper noticeDetailMapper;
    private final WmsDeliverGoodsBaseMapper deliveryMapper;
    private final WmsDeliverGoodsDetailsMapper deliveryDetailMapper;
    private final WmsDeliverGoodsPlanBaseMapper planMapper;
    private final WmsInoutFlowMapper flowMapper;
    private final WmsOmsDeliveryEventOutboxMapper deliveryEventMapper;
    private final WmsSalesShipmentCorrectionMapper correctionMapper;
    private final WmsOmsCustomerReceiptOutboxService receiptOutboxService;

    public List<WmsShipmentCustodyEntity> listByDelivery(Long deliveryId) {
        require(deliveryId != null, "发货单ID不能为空");
        WmsDeliverGoodsBaseEntity d = deliveryMapper.selectById(deliveryId);
        require(d != null, "发货单不存在");
        requireFactory(d.getFactoryCode());
        List<Long> billIds = outBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                        .eq(WmsOutBillEntity::getDocumentId, deliveryId).eq(WmsOutBillEntity::getDocumentTable, DocumentEnum.DELIVER.getCode())
                        .eq(WmsOutBillEntity::getBillType, OutBillTypeEnum.SALES_OUT.getCode()).orderByAsc(WmsOutBillEntity::getId))
                .stream().map(WmsOutBillEntity::getId).toList();
        if (billIds.isEmpty()) return List.of();
        Map<Long, WmsShipmentCustodyEntity> existing = custodyMapper.selectList(new LambdaQueryWrapper<WmsShipmentCustodyEntity>()
                .in(WmsShipmentCustodyEntity::getOutBillId, billIds)).stream().collect(Collectors.toMap(WmsShipmentCustodyEntity::getOutBillId, Function.identity()));
        List<WmsShipmentCustodyEntity> result = new ArrayList<>();
        for (WmsOutBillEntity b : outBillMapper.selectBatchIds(billIds)) {
            WmsShipmentCustodyEntity c = existing.get(b.getId());
            if (c == null)
                c = new WmsShipmentCustodyEntity().setOutBillId(b.getId()).setOutBillNo(b.getBillNo()).setDeliveryId(deliveryId)
                        .setDeliveryCode(d.getDeliverCode()).setState("READY").setFactoryCode(b.getFactoryCode());
            result.add(c);
        }
        result.sort(Comparator.comparing(WmsShipmentCustodyEntity::getOutBillId));
        return result;
    }

    public List<WmsShipmentCustodyOperationEntity> operations(Long custodyId) {
        WmsShipmentCustodyEntity c = get(custodyId);
        return operationMapper.selectList(new LambdaQueryWrapper<WmsShipmentCustodyOperationEntity>().eq(WmsShipmentCustodyOperationEntity::getCustodyId, c.getId()).orderByAsc(WmsShipmentCustodyOperationEntity::getId));
    }

    public List<WmsShipmentReceiptDetailEntity> receiptDetails(Long custodyId) {
        WmsShipmentCustodyEntity c = get(custodyId);
        return receiptDetailMapper.selectList(new LambdaQueryWrapper<WmsShipmentReceiptDetailEntity>().eq(WmsShipmentReceiptDetailEntity::getCustodyId, c.getId()).orderByAsc(WmsShipmentReceiptDetailEntity::getId));
    }

    public WmsShipmentCustodyEntity get(Long id) {
        WmsShipmentCustodyEntity c = custodyMapper.selectById(id);
        require(c != null, "物流保管链不存在");
        requireFactory(c.getFactoryCode());
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsShipmentCustodyEntity handover(WmsShipmentCustodyCommand.Handover cmd) {
        require(cmd != null && cmd.getOutBillId() != null, "销售出库单ID不能为空");
        String request = required(cmd.getRequestId(), 64, "请求号");
        requireText(cmd.getCarrierCode(), 100, "承运商编码");
        requireText(cmd.getCarrierName(), 200, "承运商名称");
        requireText(cmd.getWaybillNo(), 100, "运单号");
        requireText(cmd.getVehiclePlate(), 50, "车牌");
        requireText(cmd.getDriverName(), 100, "司机姓名");
        requireText(cmd.getDriverPhone(), 50, "司机联系方式");
        requireText(cmd.getSealNo(), 100, "封签号");
        requireText(cmd.getEvidence(), 500, "交接证据");
        WmsOutBillEntity bill = lockBill(cmd.getOutBillId());
        LineageContext ctx = lineage(bill, true);
        String snapshot = JSONUtil.toJsonStr(cmd);
        WmsShipmentCustodyOperationEntity replay = replay(request, "HANDOVER", snapshot);
        if (replay != null) return get(replay.getCustodyId());
        WmsShipmentCustodyEntity c = custodyMapper.selectOne(new LambdaQueryWrapper<WmsShipmentCustodyEntity>().eq(WmsShipmentCustodyEntity::getOutBillId, bill.getId()).last("FOR UPDATE"), false);
        String before = "READY";
        if (c != null) {
            require(CANCELLED.equals(c.getState()), "当前物流保管链不是可重新交接状态");
            before = CANCELLED;
        }
        require(correctionMapper.selectCount(new LambdaQueryWrapper<WmsSalesShipmentCorrectionEntity>().eq(WmsSalesShipmentCorrectionEntity::getOutBillId, bill.getId()).in(WmsSalesShipmentCorrectionEntity::getState, "PENDING", "EXECUTED")) == 0, "该出库单存在待复核或已执行纠错，禁止承运交接");
        for (WmsDeliverGoodsPlanBaseEntity p : ctx.plans().values())
            if (p.getOrderCodeDetails() != null) {
                WmsOmsDeliveryEventOutboxEntity e = deliveryEventMapper.selectOne(new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>().eq(WmsOmsDeliveryEventOutboxEntity::getWmsBillId, bill.getId()).eq(WmsOmsDeliveryEventOutboxEntity::getWmsPlanId, p.getId()), false);
                require(e != null && "SUCCEEDED".equals(e.getStatus()), "原OMS发运事件尚未成功确认，禁止承运交接");
            }
        MomUser u = user();
        LocalDateTime now = LocalDateTime.now();
        WmsDeliverGoodsBaseEntity delivery = ctx.delivery();
        if (c == null)
            c = new WmsShipmentCustodyEntity().setCustodyNo("CUST-" + bill.getId()).setOutBillId(bill.getId()).setOutBillNo(bill.getBillNo()).setDeliveryId(delivery.getId()).setDeliveryCode(delivery.getDeliverCode()).setFactoryCode(bill.getFactoryCode());
        c.setState(HANDOVER).setSourceFingerprint(ctx.fingerprint()).setCarrierCode(cmd.getCarrierCode().trim()).setCarrierName(cmd.getCarrierName().trim()).setWaybillNo(cmd.getWaybillNo().trim()).setVehiclePlate(cmd.getVehiclePlate().trim()).setDriverName(cmd.getDriverName().trim()).setDriverPhone(cmd.getDriverPhone().trim()).setSealNo(cmd.getSealNo().trim()).setEstimatedArrivalTime(cmd.getEstimatedArrivalTime()).setHandoverBy(u.getUsername()).setHandoverByName(name(u)).setHandoverTime(now).setHandoverEvidence(cmd.getEvidence().trim()).setGateBy(null).setGateByName(null).setGatePassNo(null).setDepartedTime(null).setDepartureEvidence(null).setRecipientName(null).setRecipientContact(null).setReceiptTime(null).setReceiptEvidence(null).setCancelReason(null);
        require(c.getId() == null ? custodyMapper.insert(c) == 1 : custodyMapper.updateById(c) == 1, "承运交接保存失败");
        operation(c, request, "HANDOVER", before, HANDOVER, null, cmd.getEvidence(), snapshot, u, now);
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsShipmentCustodyEntity cancel(Long id, WmsShipmentCustodyCommand.Cancel cmd) {
        require(cmd != null, "取消交接命令不能为空");
        String request = required(cmd.getRequestId(), 64, "请求号");
        requireText(cmd.getReason(), 500, "取消原因");
        requireText(cmd.getEvidence(), 500, "取消证据");
        String snapshot = JSONUtil.toJsonStr(cmd);
        WmsShipmentCustodyOperationEntity replay = replay(request, "CANCEL", snapshot);
        if (replay != null) return get(replay.getCustodyId());
        WmsShipmentCustodyEntity c = lock(id);
        require(HANDOVER.equals(c.getState()), "仅已交接且未离场的发运可取消承运");
        MomUser u = user();
        require(!Objects.equals(c.getHandoverBy(), u.getUsername()), "交接人与取消确认人必须分离");
        String before = c.getState();
        c.setState(CANCELLED).setCancelReason(cmd.getReason().trim());
        require(custodyMapper.updateById(c) == 1, "取消交接保存失败");
        operation(c, request, "CANCEL", before, CANCELLED, cmd.getReason(), cmd.getEvidence(), snapshot, u, LocalDateTime.now());
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsShipmentCustodyEntity depart(Long id, WmsShipmentCustodyCommand.Depart cmd) {
        require(cmd != null, "离场命令不能为空");
        String request = required(cmd.getRequestId(), 64, "请求号");
        requireText(cmd.getGatePassNo(), 100, "门岗放行号");
        requireText(cmd.getEvidence(), 500, "离场证据");
        String snapshot = JSONUtil.toJsonStr(cmd);
        WmsShipmentCustodyOperationEntity replay = replay(request, "DEPART", snapshot);
        if (replay != null) return get(replay.getCustodyId());
        WmsShipmentCustodyEntity c = lock(id);
        require(HANDOVER.equals(c.getState()), "仅已承运交接的发运可门岗离场");
        MomUser u = user();
        require(!Objects.equals(c.getHandoverBy(), u.getUsername()), "仓库交接人与门岗放行人必须分离");
        WmsOutBillEntity bill = lockBill(c.getOutBillId());
        LineageContext ctx = lineage(bill, true);
        require(Objects.equals(ctx.fingerprint(), c.getSourceFingerprint()), "交接后出库来源事实发生变化，禁止离场");
        String before = c.getState();
        LocalDateTime now = LocalDateTime.now();
        c.setState(IN_TRANSIT).setGateBy(u.getUsername()).setGateByName(name(u)).setGatePassNo(cmd.getGatePassNo().trim()).setDepartedTime(now).setDepartureEvidence(cmd.getEvidence().trim());
        require(custodyMapper.updateById(c) == 1, "门岗离场保存失败");
        operation(c, request, "DEPART", before, IN_TRANSIT, null, cmd.getEvidence(), snapshot, u, now);
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsShipmentCustodyEntity receipt(Long id, WmsShipmentCustodyCommand.Receipt cmd) {
        require(cmd != null && cmd.getReceiptTime() != null && CollUtil.isNotEmpty(cmd.getLines()), "客户实收时间和逐行数量必填");
        String request = required(cmd.getRequestId(), 64, "请求号");
        requireText(cmd.getRecipientName(), 100, "客户收货人");
        requireText(cmd.getRecipientContact(), 100, "客户收货人联系方式");
        requireText(cmd.getEvidence(), 500, "客户实收证据");
        require(!cmd.getReceiptTime().isAfter(LocalDateTime.now().plusMinutes(5)), "客户实收时间不能晚于当前时间");
        String snapshot = JSONUtil.toJsonStr(cmd);
        WmsShipmentCustodyOperationEntity replay = replay(request, "RECEIPT", snapshot);
        if (replay != null) return get(replay.getCustodyId());
        WmsShipmentCustodyEntity c = lock(id);
        require(IN_TRANSIT.equals(c.getState()), "仅已离场在途的发运可登记客户实收");
        require(!cmd.getReceiptTime().isBefore(c.getDepartedTime()), "客户实收时间不能早于门岗离场时间");
        WmsOutBillEntity bill = lockBill(c.getOutBillId());
        LineageContext ctx = lineage(bill, true);
        require(Objects.equals(ctx.fingerprint(), c.getSourceFingerprint()), "离场后出库来源事实发生变化，禁止登记实收");
        Map<Long, WmsShipmentCustodyCommand.ReceiptLine> input = cmd.getLines().stream().collect(Collectors.toMap(WmsShipmentCustodyCommand.ReceiptLine::getOutBillDetailId, Function.identity(), (a, b) -> {
            throw new CheckedException("同一出库明细不能重复登记");
        }));
        require(input.size() == ctx.billDetails().size() && ctx.billDetails().stream().allMatch(d -> input.containsKey(d.getId())), "客户实收必须完整覆盖本次出库全部明细");
        boolean hasException = false;
        List<WmsShipmentReceiptDetailEntity> saved = new ArrayList<>();
        for (WmsOutBillDetailEntity d : ctx.billDetails()) {
            WmsShipmentCustodyCommand.ReceiptLine x = input.get(d.getId());
            BigDecimal shipped = qty(d.getMatNum()), signed = nonnegative(x.getSignedNum(), "签收量"), rejected = nonnegative(x.getRejectedNum(), "拒收量"), damaged = nonnegative(x.getDamagedNum(), "破损量"), lost = nonnegative(x.getLostNum(), "丢失量"), exception = rejected.add(damaged).add(lost);
            require(signed.add(exception).compareTo(shipped) == 0, "出库明细" + d.getId() + "的签收、拒收、破损和丢失数量不守恒");
            if (exception.signum() > 0) {
                hasException = true;
                requireText(x.getExceptionReason(), 500, "异常原因");
                require(Set.of("CUSTOMER", "CARRIER", "SHIPPER", "QUALITY", "UNKNOWN").contains(x.getResponsibility()), "异常责任方非法");
            }
            Lineage l = ctx.lineage().get(d.getId());
            WmsShipmentReceiptDetailEntity row = new WmsShipmentReceiptDetailEntity().setCustodyId(c.getId()).setOutBillDetailId(d.getId()).setDeliveryDetailId(l.deliveryDetail().getId()).setPlanId(l.plan().getId()).setPlanCode(l.plan().getDeliverPlanCode()).setOrderCode(l.plan().getOrderCode()).setOrderDetailId(l.plan().getOrderCodeDetails()).setProductCode(d.getMatCode()).setShippedNum(shipped).setSignedNum(signed).setRejectedNum(rejected).setDamagedNum(damaged).setLostNum(lost).setExceptionReason(StrUtil.isBlank(x.getExceptionReason()) ? null : x.getExceptionReason().trim()).setResponsibility(StrUtil.isBlank(x.getResponsibility()) ? null : x.getResponsibility()).setFactoryCode(c.getFactoryCode());
            require(receiptDetailMapper.insert(row) == 1, "客户实收明细保存失败");
            saved.add(row);
        }
        String after = hasException ? EXCEPTION : SIGNED;
        MomUser u = user();
        c.setState(after).setRecipientName(cmd.getRecipientName().trim()).setRecipientContact(cmd.getRecipientContact().trim()).setReceiptTime(cmd.getReceiptTime()).setReceiptEvidence(cmd.getEvidence().trim());
        require(custodyMapper.updateById(c) == 1, "客户实收状态保存失败");
        operation(c, request, "RECEIPT", IN_TRANSIT, after, null, cmd.getEvidence(), snapshot, u, LocalDateTime.now());
        Map<Long, List<WmsShipmentReceiptDetailEntity>> byPlan = saved.stream().collect(Collectors.groupingBy(WmsShipmentReceiptDetailEntity::getPlanId, LinkedHashMap::new, Collectors.toList()));
        for (List<WmsShipmentReceiptDetailEntity> rows : byPlan.values()) receiptOutboxService.enqueue(c, rows);
        return c;
    }

    public boolean blocksCorrection(Long outBillId) {
        WmsShipmentCustodyEntity c = custodyMapper.selectOne(new LambdaQueryWrapper<WmsShipmentCustodyEntity>().eq(WmsShipmentCustodyEntity::getOutBillId, outBillId), false);
        return c != null && !CANCELLED.equals(c.getState());
    }

    private LineageContext lineage(WmsOutBillEntity bill, boolean lock) {
        require(OutBillTypeEnum.SALES_OUT.getCode().equals(bill.getBillType()) && DocumentEnum.DELIVER.getCode().equals(bill.getDocumentTable()) && (InOutBillStateEnum.PASSED.getCode().equals(bill.getState()) || InOutBillStateEnum.FINISHED.getCode().equals(bill.getState())), "仅已通过/完成的正式销售出库可进入物流保管链");
        List<WmsOutBillDetailEntity> details = outBillDetailMapper.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>().eq(WmsOutBillDetailEntity::getBillId, bill.getId()).orderByAsc(WmsOutBillDetailEntity::getId).last(lock ? "FOR UPDATE" : ""));
        require(!details.isEmpty(), "销售出库明细为空");
        require(details.stream().allMatch(d -> d.getNoticeDetailId() != null), "出库明细存在缺失的通知来源");
        Set<Long> noticeIds = details.stream().map(WmsOutBillDetailEntity::getNoticeDetailId).collect(Collectors.toSet());
        Map<Long, WmsOutNoticeDetailEntity> notices = noticeDetailMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>().in(WmsOutNoticeDetailEntity::getId, noticeIds).last(lock ? "FOR UPDATE" : "")).stream().collect(Collectors.toMap(WmsOutNoticeDetailEntity::getId, Function.identity()));
        require(notices.size() == noticeIds.size() && noticeIds.size() > 0, "出库通知来源缺失");
        require(notices.values().stream().allMatch(n -> n.getDocumentId() != null), "出库通知明细缺少发货明细来源");
        Set<Long> ddIds = notices.values().stream().map(WmsOutNoticeDetailEntity::getDocumentId).collect(Collectors.toSet());
        Map<Long, WmsDeliverGoodsDetailsEntity> dds = deliveryDetailMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>().in(WmsDeliverGoodsDetailsEntity::getId, ddIds).last(lock ? "FOR UPDATE" : "")).stream().collect(Collectors.toMap(WmsDeliverGoodsDetailsEntity::getId, Function.identity()));
        require(dds.size() == ddIds.size(), "发货明细来源缺失");
        require(dds.values().stream().allMatch(d -> StrUtil.isNotBlank(d.getDeliverPlanCode())), "发货明细缺少计划号");
        Set<String> pc = dds.values().stream().map(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode).collect(Collectors.toSet());
        Map<String, WmsDeliverGoodsPlanBaseEntity> plansByCode = planMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>().in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, pc).last(lock ? "FOR UPDATE" : "")).stream().collect(Collectors.toMap(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, Function.identity(), (a, b) -> {
            throw new CheckedException("发货计划号重复");
        }));
        require(plansByCode.size() == pc.size(), "发货计划来源缺失");
        Map<Long, Lineage> lineage = new LinkedHashMap<>();
        Map<Long, WmsDeliverGoodsPlanBaseEntity> plans = new LinkedHashMap<>();
        for (WmsOutBillDetailEntity d : details) {
            WmsOutNoticeDetailEntity n = notices.get(d.getNoticeDetailId());
            require(n != null && n.getDocumentId() != null, "出库通知明细缺少发货明细来源");
            WmsDeliverGoodsDetailsEntity dd = dds.get(n.getDocumentId());
            require(dd != null && StrUtil.isNotBlank(dd.getDeliverPlanCode()), "发货明细或计划来源缺失");
            WmsDeliverGoodsPlanBaseEntity p = plansByCode.get(dd.getDeliverPlanCode());
            require(p != null && Objects.equals(d.getMatCode(), dd.getProductCode()) && Objects.equals(dd.getProductCode(), p.getProductCode()) && Objects.equals(p.getFactoryCode(), bill.getFactoryCode()), "出库、发货明细和计划的产品或工厂不一致");
            lineage.put(d.getId(), new Lineage(dd, p));
            plans.put(p.getId(), p);
        }
        List<WmsInoutFlowEntity> proofs = flowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>().eq(WmsInoutFlowEntity::getBillId, bill.getId()).eq(WmsInoutFlowEntity::getStockProofType, "SALES_SHIPMENT").orderByAsc(WmsInoutFlowEntity::getId).last(lock ? "FOR UPDATE" : ""));
        require(!proofs.isEmpty() && proofs.stream().allMatch(x -> x.getBillDetailId() != null && x.getSourceStockId() != null && x.getMatNum() != null && Double.isFinite(x.getMatNum()) && x.getMatNum() > 0), "销售出库精确库存证据缺少明细、库存行或正数量");
        Map<Long, BigDecimal> proofQty = proofs.stream().collect(Collectors.groupingBy(WmsInoutFlowEntity::getBillDetailId, Collectors.mapping(x -> BigDecimal.valueOf(x.getMatNum()), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        require(proofQty.size() == details.size() && details.stream().allMatch(d -> proofQty.getOrDefault(d.getId(), BigDecimal.ZERO).compareTo(qty(d.getMatNum())) == 0), "销售出库缺少完整精确库存证据，不能建立物流保管链");
        WmsDeliverGoodsBaseEntity delivery = deliveryMapper.selectById(bill.getDocumentId());
        require(delivery != null && Objects.equals(delivery.getFactoryCode(), bill.getFactoryCode()), "发货主单来源缺失或跨工厂");
        String fingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(bill.getId(), bill.getBillNo(), bill.getFactoryCode(), details.stream().map(d -> Arrays.asList(d.getId(), d.getMatCode(), qty(d.getMatNum()).stripTrailingZeros().toPlainString())).toList(), proofs.stream().map(p -> Arrays.asList(p.getId(), p.getBillDetailId(), p.getSourceStockId(), p.getMatBatch(), p.getWarehouseCode(), p.getSiteCode(), p.getContainerCode(), p.getMatNum())).toList())));
        return new LineageContext(delivery, details, lineage, plans, fingerprint);
    }

    private WmsShipmentCustodyOperationEntity replay(String request, String type, String snapshot) {
        WmsShipmentCustodyOperationEntity o = operationMapper.selectOne(new LambdaQueryWrapper<WmsShipmentCustodyOperationEntity>().eq(WmsShipmentCustodyOperationEntity::getRequestId, request), false);
        if (o != null) {
            require(Objects.equals(o.getOperationType(), type) && Objects.equals(o.getFactSnapshot(), snapshot), "请求号已被不同物流事实使用");
            requireFactory(o.getFactoryCode());
        }
        return o;
    }

    private void operation(WmsShipmentCustodyEntity c, String request, String type, String before, String after, String reason, String evidence, String snapshot, MomUser u, LocalDateTime now) {
        WmsShipmentCustodyOperationEntity o = new WmsShipmentCustodyOperationEntity().setCustodyId(c.getId()).setRequestId(request).setOperationType(type).setBeforeState(before).setAfterState(after).setOperatorCode(u.getUsername()).setOperatorName(name(u)).setOperatedTime(now).setReason(StrUtil.trim(reason)).setEvidence(StrUtil.trim(evidence)).setFactSnapshot(snapshot).setFactoryCode(c.getFactoryCode());
        require(operationMapper.insert(o) == 1, "物流保管操作履历保存失败");
    }

    private WmsOutBillEntity lockBill(Long id) {
        WmsOutBillEntity b = outBillMapper.selectOne(new LambdaQueryWrapper<WmsOutBillEntity>().eq(WmsOutBillEntity::getId, id).last("FOR UPDATE"), false);
        require(b != null, "销售出库单不存在");
        requireFactory(b.getFactoryCode());
        return b;
    }

    private WmsShipmentCustodyEntity lock(Long id) {
        WmsShipmentCustodyEntity c = custodyMapper.selectOne(new LambdaQueryWrapper<WmsShipmentCustodyEntity>().eq(WmsShipmentCustodyEntity::getId, id).last("FOR UPDATE"));
        require(c != null, "物流保管链不存在");
        requireFactory(c.getFactoryCode());
        return c;
    }

    private BigDecimal nonnegative(BigDecimal v, String label) {
        require(v != null && v.signum() >= 0, label + "不能为空且不能小于零");
        return v;
    }

    private BigDecimal qty(Double v) {
        require(v != null && Double.isFinite(v) && v > 0, "出库数量必须为正数");
        return BigDecimal.valueOf(v);
    }

    private String required(String v, int max, String label) {
        String s = StrUtil.trim(v);
        require(StrUtil.isNotBlank(s) && s.length() <= max, label + "不能为空且不能超过" + max + "字");
        return s;
    }

    private void requireText(String v, int max, String label) {
        required(v, max, label);
    }

    private String hash(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CheckedException("物流来源指纹生成失败");
        }
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
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(f) && (StrUtil.isBlank(current) || Objects.equals(current, f)), "当前工厂无权操作该物流保管链");
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }

    private record Lineage(WmsDeliverGoodsDetailsEntity deliveryDetail, WmsDeliverGoodsPlanBaseEntity plan) {
    }

    private record LineageContext(WmsDeliverGoodsBaseEntity delivery, List<WmsOutBillDetailEntity> billDetails,
                                  Map<Long, Lineage> lineage, Map<Long, WmsDeliverGoodsPlanBaseEntity> plans,
                                  String fingerprint) {
    }
}
