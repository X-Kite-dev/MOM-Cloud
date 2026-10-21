package com.kitex.mom.wms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsSalesShipmentCorrectionCommand;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 销售发运整单记账纠错：双人复核、精确库存恢复、计划重开、OMS可靠反向事件。
 */
@Service
@RequiredArgsConstructor
public class WmsSalesShipmentCorrectionService {
    private static final String PENDING = "PENDING", REJECTED = "REJECTED", EXECUTED = "EXECUTED";
    private final WmsSalesShipmentCorrectionMapper correctionMapper;
    private final WmsSalesShipmentCorrectionDetailMapper correctionDetailMapper;
    private final WmsOutBillMapper outBillMapper;
    private final WmsOutBillDetailMapper outBillDetailMapper;
    private final WmsOutNoticeMapper outNoticeMapper;
    private final WmsOutNoticeDetailMapper outNoticeDetailMapper;
    private final WmsInoutFlowMapper flowMapper;
    private final WmsDeliverGoodsBaseMapper deliveryMapper;
    private final WmsDeliverGoodsDetailsMapper deliveryDetailMapper;
    private final WmsDeliverGoodsPlanBaseMapper planMapper;
    private final WmsOmsDeliveryEventOutboxMapper deliveryOutboxMapper;
    private final WmsLiveStockService liveStockService;
    private final WmsSalesStockReservationService reservationService;
    private final WmsOmsShipmentCorrectionOutboxService correctionOutboxService;
    private final WmsShipmentCustodyService shipmentCustodyService;

    public Page<WmsSalesShipmentCorrectionEntity> page(Page<WmsSalesShipmentCorrectionEntity> page,
                                                       WmsSalesShipmentCorrectionEntity query) {
        String factory = FactoryContextHolder.getFactoryCode();
        return correctionMapper.selectPage(page, new LambdaQueryWrapper<WmsSalesShipmentCorrectionEntity>()
                .eq(StrUtil.isNotBlank(factory), WmsSalesShipmentCorrectionEntity::getFactoryCode, factory)
                .like(query != null && StrUtil.isNotBlank(query.getCorrectionNo()), WmsSalesShipmentCorrectionEntity::getCorrectionNo, query == null ? null : query.getCorrectionNo())
                .like(query != null && StrUtil.isNotBlank(query.getOutBillNo()), WmsSalesShipmentCorrectionEntity::getOutBillNo, query == null ? null : query.getOutBillNo())
                .eq(query != null && StrUtil.isNotBlank(query.getState()), WmsSalesShipmentCorrectionEntity::getState, query == null ? null : query.getState())
                .orderByDesc(WmsSalesShipmentCorrectionEntity::getCreateTime));
    }

    public WmsSalesShipmentCorrectionEntity get(Long id) {
        WmsSalesShipmentCorrectionEntity e = correctionMapper.selectById(id);
        require(e != null, "销售发运纠错单不存在");
        requireFactory(e.getFactoryCode());
        return e;
    }

    public WmsSalesShipmentCorrectionEntity findByBill(Long outBillId) {
        require(outBillId != null, "销售出库单ID不能为空");
        WmsSalesShipmentCorrectionEntity e = correctionMapper.selectOne(new LambdaQueryWrapper<WmsSalesShipmentCorrectionEntity>().eq(WmsSalesShipmentCorrectionEntity::getOutBillId, outBillId), false);
        if (e != null) requireFactory(e.getFactoryCode());
        return e;
    }

    public List<WmsSalesShipmentCorrectionDetailEntity> details(Long id) {
        get(id);
        return correctionDetailMapper.selectList(new LambdaQueryWrapper<WmsSalesShipmentCorrectionDetailEntity>().eq(WmsSalesShipmentCorrectionDetailEntity::getCorrectionId, id).orderByAsc(WmsSalesShipmentCorrectionDetailEntity::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsSalesShipmentCorrectionEntity apply(WmsSalesShipmentCorrectionCommand command) {
        require(command != null && command.getOutBillId() != null && StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500, "出库单和不超过500字的纠错原因必填");
        WmsOutBillEntity bill = lockBill(command.getOutBillId());
        validateBill(bill);
        WmsSalesShipmentCorrectionEntity existing = correctionMapper.selectOne(new LambdaQueryWrapper<WmsSalesShipmentCorrectionEntity>().eq(WmsSalesShipmentCorrectionEntity::getOutBillId, bill.getId()), false);
        require(existing == null, "该销售出库单已经存在纠错申请");
        List<WmsOutBillDetailEntity> details = lockBillDetails(bill.getId());
        ProofContext proof = proofContext(bill, details, false);
        MomUser user = user();
        LocalDateTime now = LocalDateTime.now();
        WmsSalesShipmentCorrectionEntity row = new WmsSalesShipmentCorrectionEntity()
                .setCorrectionNo("SC-" + bill.getId()).setRequestId("SHIP-CORRECTION:" + bill.getId())
                .setOutBillId(bill.getId()).setOutBillNo(bill.getBillNo()).setState(PENDING)
                .setReason(command.getReason().trim()).setTotalNum(proof.total())
                .setApplicantCode(user.getUsername()).setApplicantName(StrUtil.blankToDefault(user.getName(), user.getUsername()))
                .setAppliedTime(now).setFactoryCode(bill.getFactoryCode());
        require(correctionMapper.insert(row) == 1, "销售发运纠错申请保存失败");
        bill.setCorrectionState(PENDING);
        require(outBillMapper.updateById(bill) == 1, "出库单纠错状态保存失败");
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsSalesShipmentCorrectionEntity resubmit(Long id, WmsSalesShipmentCorrectionCommand command) {
        WmsSalesShipmentCorrectionEntity row = lockCorrection(id);
        require(REJECTED.equals(row.getState()), "仅已驳回纠错单可重新提交");
        require(command != null && StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500, "重新提交原因必填且最多500字");
        WmsOutBillEntity bill = lockBill(row.getOutBillId());
        validateBill(bill);
        proofContext(bill, lockBillDetails(bill.getId()), false);
        MomUser u = user();
        require(Objects.equals(row.getApplicantCode(), u.getUsername()), "仅原申请人可修改原因并重新提交");
        row.setState(PENDING).setReason(command.getReason().trim()).setAppliedTime(LocalDateTime.now()).setReviewerCode(null).setReviewerName(null).setReviewedTime(null).setRejectReason(null);
        require(correctionMapper.updateById(row) == 1, "纠错重新提交失败");
        bill.setCorrectionState(PENDING);
        require(outBillMapper.updateById(bill) == 1, "出库单纠错状态保存失败");
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsSalesShipmentCorrectionEntity reject(Long id, WmsSalesShipmentCorrectionCommand command) {
        WmsSalesShipmentCorrectionEntity row = lockCorrection(id);
        require(PENDING.equals(row.getState()), "仅待复核纠错单可驳回");
        require(command != null && StrUtil.isNotBlank(command.getRejectReason()) && command.getRejectReason().trim().length() <= 500, "驳回原因必填且最多500字");
        MomUser u = user();
        require(!Objects.equals(row.getApplicantCode(), u.getUsername()), "申请人与复核人必须分离");
        row.setState(REJECTED).setReviewerCode(u.getUsername()).setReviewerName(StrUtil.blankToDefault(u.getName(), u.getUsername())).setReviewedTime(LocalDateTime.now()).setRejectReason(command.getRejectReason().trim());
        require(correctionMapper.updateById(row) == 1, "纠错驳回保存失败");
        WmsOutBillEntity bill = lockBill(row.getOutBillId());
        bill.setCorrectionState(REJECTED);
        require(outBillMapper.updateById(bill) == 1, "出库单纠错状态保存失败");
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsSalesShipmentCorrectionEntity approve(Long id) {
        WmsSalesShipmentCorrectionEntity correction = lockCorrection(id);
        require(PENDING.equals(correction.getState()), "仅待复核纠错单可执行");
        MomUser reviewer = user();
        require(!Objects.equals(correction.getApplicantCode(), reviewer.getUsername()), "申请人与复核人必须分离");
        WmsOutBillEntity bill = lockBill(correction.getOutBillId());
        validateBill(bill);
        List<WmsOutBillDetailEntity> billDetails = lockBillDetails(bill.getId());
        ProofContext proof = proofContext(bill, billDetails, true);
        require(proof.total().compareTo(correction.getTotalNum()) == 0, "纠错申请后原发运数量证据发生变化");
        Map<Long, List<WmsLiveStockEntity>> restoredByPlan = new LinkedHashMap<>();
        for (WmsInoutFlowEntity source : proof.flows()) {
            Lineage line = proof.lineageByBillDetail().get(source.getBillDetailId());
            require(line != null, "发运证据无法对应原出库明细");
            WmsLiveStockEntity restored = liveStockService.restoreSalesShipment(source);
            WmsSalesShipmentCorrectionDetailEntity detail = new WmsSalesShipmentCorrectionDetailEntity().setCorrectionId(correction.getId()).setOutBillDetailId(source.getBillDetailId()).setSourceFlowId(source.getId()).setSourceStockId(source.getSourceStockId()).setRestoredStockId(restored.getId()).setDeliverDetailId(line.deliveryDetail().getId()).setDeliverPlanId(line.plan().getId()).setDeliverPlanCode(line.plan().getDeliverPlanCode()).setOrderCode(line.plan().getOrderCode()).setOrderDetailId(line.plan().getOrderCodeDetails()).setProductCode(source.getMatCode()).setWarehouseCode(source.getWarehouseCode()).setSiteCode(source.getSiteCode()).setContainerCode(source.getContainerCode()).setMatBatch(source.getMatBatch()).setCorrectedNum(BigDecimal.valueOf(source.getMatNum())).setFactoryCode(bill.getFactoryCode());
            require(correctionDetailMapper.insert(detail) == 1, "纠错精确恢复明细保存失败");
            restoredByPlan.computeIfAbsent(line.plan().getId(), k -> new ArrayList<>()).add(restored);
        }
        reopenNotice(bill, billDetails);
        Map<Long, BigDecimal> deliveryQty = new HashMap<>();
        Map<Long, BigDecimal> planQty = new HashMap<>();
        for (WmsOutBillDetailEntity d : billDetails) {
            Lineage l = proof.lineageByBillDetail().get(d.getId());
            BigDecimal q = qty(d.getMatNum());
            deliveryQty.merge(l.deliveryDetail().getId(), q, BigDecimal::add);
            planQty.merge(l.plan().getId(), q, BigDecimal::add);
        }
        Set<String> deliveryCodes = new HashSet<>();
        for (Map.Entry<Long, BigDecimal> e : deliveryQty.entrySet()) {
            WmsDeliverGoodsDetailsEntity d = lockDeliveryDetail(e.getKey());
            require(d.getDeliveredNum() != null && d.getReturnNum() != null && d.getReturnNum().signum() == 0 && d.getDeliveredNum().compareTo(e.getValue()) >= 0, "发货明细已发生退货、数量不足或缺失，不能按未离场纠错");
            d.setDeliveredNum(d.getDeliveredNum().subtract(e.getValue()));
            d.setDeliverState(d.getDeliveredNum().signum() == 0 ? DeliverStateEnum.NOT_START.getCode() : DeliverStateEnum.ING.getCode());
            require(deliveryDetailMapper.updateById(d) == 1, "发货明细纠错失败");
            deliveryCodes.add(d.getDeliverCode());
        }
        for (String code : deliveryCodes) reopenDelivery(code);
        for (Map.Entry<Long, BigDecimal> e : planQty.entrySet()) {
            WmsDeliverGoodsPlanBaseEntity p = proof.plans().get(e.getKey());
            require(p.getDeliveredNum() != null && p.getReturnNum() != null && p.getReturnNum().signum() == 0 && p.getDeliveredNum().compareTo(e.getValue()) >= 0, "发货计划已发生退货、数量不足或缺失，不能按未离场纠错");
            p.setDeliveredNum(p.getDeliveredNum().subtract(e.getValue()));
            p.setDeliverState(p.getDeliveredNum().signum() == 0 ? DeliverStateEnum.NOT_START.getCode() : DeliverStateEnum.ING.getCode());
            require(planMapper.updateById(p) == 1, "发货计划纠错失败");
            reservationService.restoreAfterShipmentCorrection(p.getId(), restoredByPlan.get(p.getId()), "CORR-RESERVE:" + correction.getId() + ":" + p.getId(), correction.getReason());
            if (p.getOrderCodeDetails() != null) correctionOutboxService.enqueue(correction, p, e.getValue());
        }
        LocalDateTime now = LocalDateTime.now();
        correction.setState(EXECUTED).setReviewerCode(reviewer.getUsername()).setReviewerName(StrUtil.blankToDefault(reviewer.getName(), reviewer.getUsername())).setReviewedTime(now).setExecutedTime(now).setRejectReason(null);
        require(correctionMapper.updateById(correction) == 1, "纠错执行状态保存失败");
        bill.setCorrectionState(EXECUTED);
        require(outBillMapper.updateById(bill) == 1, "出库单纠错完成状态保存失败");
        return correction;
    }

    private ProofContext proofContext(WmsOutBillEntity bill, List<WmsOutBillDetailEntity> billDetails, boolean lock) {
        require(CollUtil.isNotEmpty(billDetails), "销售出库单没有明细");
        List<WmsInoutFlowEntity> flows = flowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>().eq(WmsInoutFlowEntity::getBillId, bill.getId()).eq(WmsInoutFlowEntity::getStockProofType, "SALES_SHIPMENT").eq(WmsInoutFlowEntity::getOperateType, InOutEnum.OUT.getCode()).orderByAsc(WmsInoutFlowEntity::getId).last(lock ? "FOR UPDATE" : ""));
        require(!flows.isEmpty() && flows.stream().allMatch(f -> f.getBillDetailId() != null && f.getSourceStockId() != null && f.getMatNum() != null && f.getMatNum() > 0 && Objects.equals(f.getFactoryCode(), bill.getFactoryCode())), "该单缺少完整的新版精确库存证据；历史单禁止自动红冲");
        Map<Long, BigDecimal> proofQty = flows.stream().collect(Collectors.groupingBy(WmsInoutFlowEntity::getBillDetailId, Collectors.mapping(f -> BigDecimal.valueOf(f.getMatNum()), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        require(billDetails.stream().allMatch(d -> proofQty.containsKey(d.getId()) && proofQty.get(d.getId()).compareTo(qty(d.getMatNum())) == 0) && proofQty.size() == billDetails.size(), "精确库存证据与出库明细不守恒");
        Set<Long> noticeDetailIds = billDetails.stream().map(WmsOutBillDetailEntity::getNoticeDetailId).filter(Objects::nonNull).collect(Collectors.toSet());
        require(noticeDetailIds.size() > 0, "销售出库缺少通知来源");
        Map<Long, WmsOutNoticeDetailEntity> notices = outNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>().in(WmsOutNoticeDetailEntity::getId, noticeDetailIds).last(lock ? "FOR UPDATE" : "")).stream().collect(Collectors.toMap(WmsOutNoticeDetailEntity::getId, Function.identity()));
        require(notices.size() == noticeDetailIds.size(), "销售出库通知明细来源缺失");
        Set<Long> deliveryDetailIds = notices.values().stream().map(WmsOutNoticeDetailEntity::getDocumentId).collect(Collectors.toSet());
        Map<Long, WmsDeliverGoodsDetailsEntity> deliveryDetails = deliveryDetailMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>().in(WmsDeliverGoodsDetailsEntity::getId, deliveryDetailIds).last(lock ? "FOR UPDATE" : "")).stream().collect(Collectors.toMap(WmsDeliverGoodsDetailsEntity::getId, Function.identity()));
        require(deliveryDetails.size() == deliveryDetailIds.size(), "原发货明细来源缺失");
        Set<String> planCodes = deliveryDetails.values().stream().map(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode).collect(Collectors.toSet());
        Map<String, WmsDeliverGoodsPlanBaseEntity> byCode = planMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>().in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, planCodes).last(lock ? "FOR UPDATE" : "")).stream().collect(Collectors.toMap(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, Function.identity(), (a, b) -> {
            throw new CheckedException("发货计划号重复");
        }));
        require(byCode.size() == planCodes.size(), "原发货计划来源缺失");
        Map<Long, Lineage> lineage = new HashMap<>();
        Map<Long, WmsDeliverGoodsPlanBaseEntity> plans = new HashMap<>();
        for (WmsOutBillDetailEntity d : billDetails) {
            WmsOutNoticeDetailEntity n = notices.get(d.getNoticeDetailId());
            WmsDeliverGoodsDetailsEntity dd = deliveryDetails.get(n.getDocumentId());
            WmsDeliverGoodsPlanBaseEntity p = byCode.get(dd.getDeliverPlanCode());
            require(p != null && Objects.equals(d.getMatCode(), dd.getProductCode()) && Objects.equals(dd.getProductCode(), p.getProductCode()) && Objects.equals(p.getFactoryCode(), bill.getFactoryCode()), "出库、通知、发货明细和计划的产品或工厂不一致");
            if (lock && p.getOrderCodeDetails() != null) {
                WmsOmsDeliveryEventOutboxEntity original = deliveryOutboxMapper.selectOne(new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>().eq(WmsOmsDeliveryEventOutboxEntity::getWmsBillId, bill.getId()).eq(WmsOmsDeliveryEventOutboxEntity::getWmsPlanId, p.getId()), false);
                require(original != null && "SUCCEEDED".equals(original.getStatus()), "原OMS发运事件尚未成功确认，须先完成原事件再纠错");
            }
            lineage.put(d.getId(), new Lineage(dd, p));
            plans.put(p.getId(), p);
        }
        return new ProofContext(flows, lineage, plans, flows.stream().map(f -> BigDecimal.valueOf(f.getMatNum())).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private void reopenNotice(WmsOutBillEntity bill, List<WmsOutBillDetailEntity> details) {
        WmsOutNoticeEntity n = outNoticeMapper.selectOne(new LambdaQueryWrapper<WmsOutNoticeEntity>().eq(WmsOutNoticeEntity::getId, bill.getNoticeId()).last("FOR UPDATE"), false);
        require(n != null && n.getAllOutNum() != null && n.getAllNotOutNum() != null, "原出库通知单数量缺失");
        Map<Long, BigDecimal> byNotice = details.stream().collect(Collectors.groupingBy(WmsOutBillDetailEntity::getNoticeDetailId, Collectors.mapping(d -> qty(d.getMatNum()), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        for (Map.Entry<Long, BigDecimal> e : byNotice.entrySet()) {
            WmsOutNoticeDetailEntity d = outNoticeDetailMapper.selectOne(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>().eq(WmsOutNoticeDetailEntity::getId, e.getKey()).last("FOR UPDATE"), false);
            require(d != null && d.getOutNum() != null && d.getNotOutNum() != null && BigDecimal.valueOf(d.getOutNum()).compareTo(e.getValue()) >= 0, "通知明细已出库量不足");
            d.setOutNum(BigDecimal.valueOf(d.getOutNum()).subtract(e.getValue()).doubleValue());
            d.setNotOutNum(BigDecimal.valueOf(d.getNotOutNum()).add(e.getValue()).doubleValue());
            require(outNoticeDetailMapper.updateById(d) == 1, "通知明细纠错失败");
        }
        BigDecimal total = details.stream().map(d -> qty(d.getMatNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(BigDecimal.valueOf(n.getAllOutNum()).compareTo(total) >= 0, "通知单已出库总量不足");
        n.setAllOutNum(BigDecimal.valueOf(n.getAllOutNum()).subtract(total).doubleValue());
        n.setAllNotOutNum(BigDecimal.valueOf(n.getAllNotOutNum()).add(total).doubleValue());
        n.setState(n.getAllOutNum() <= 0 ? InOutNoticeStateEnum.READY.getCode() : InOutNoticeStateEnum.RUNNING.getCode());
        n.setCompleteTime(null);
        require(outNoticeMapper.updateById(n) == 1, "通知单纠错失败");
    }

    private void reopenDelivery(String code) {
        WmsDeliverGoodsBaseEntity b = deliveryMapper.selectOne(new LambdaQueryWrapper<WmsDeliverGoodsBaseEntity>().eq(WmsDeliverGoodsBaseEntity::getDeliverCode, code).last("FOR UPDATE"), false);
        require(b != null, "发货单不存在");
        List<WmsDeliverGoodsDetailsEntity> ds = deliveryDetailMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>().eq(WmsDeliverGoodsDetailsEntity::getDeliverCode, code));
        b.setDeliverState(ds.stream().allMatch(d -> d.getDeliveredNum() != null && d.getDeliveredNum().signum() == 0) ? DeliverStateEnum.NOT_START.getCode() : DeliverStateEnum.ING.getCode());
        require(deliveryMapper.updateById(b) == 1, "发货单纠错状态保存失败");
    }

    private WmsOutBillEntity lockBill(Long id) {
        WmsOutBillEntity b = outBillMapper.selectOne(new LambdaQueryWrapper<WmsOutBillEntity>().eq(WmsOutBillEntity::getId, id).last("FOR UPDATE"), false);
        require(b != null, "销售出库单不存在");
        requireFactory(b.getFactoryCode());
        return b;
    }

    private List<WmsOutBillDetailEntity> lockBillDetails(Long id) {
        return outBillDetailMapper.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>().eq(WmsOutBillDetailEntity::getBillId, id).orderByAsc(WmsOutBillDetailEntity::getId).last("FOR UPDATE"));
    }

    private WmsSalesShipmentCorrectionEntity lockCorrection(Long id) {
        WmsSalesShipmentCorrectionEntity e = correctionMapper.selectOne(new LambdaQueryWrapper<WmsSalesShipmentCorrectionEntity>().eq(WmsSalesShipmentCorrectionEntity::getId, id).last("FOR UPDATE"), false);
        require(e != null, "销售发运纠错单不存在");
        requireFactory(e.getFactoryCode());
        return e;
    }

    private WmsDeliverGoodsDetailsEntity lockDeliveryDetail(Long id) {
        WmsDeliverGoodsDetailsEntity d = deliveryDetailMapper.selectOne(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>().eq(WmsDeliverGoodsDetailsEntity::getId, id).last("FOR UPDATE"), false);
        require(d != null, "发货明细不存在");
        return d;
    }

    private void validateBill(WmsOutBillEntity b) {
        require(OutBillTypeEnum.SALES_OUT.getCode().equals(b.getBillType()) && DocumentEnum.DELIVER.getCode().equals(b.getDocumentTable()) && (InOutBillStateEnum.PASSED.getCode().equals(b.getState()) || InOutBillStateEnum.FINISHED.getCode().equals(b.getState())), "仅已通过/完成的正式销售出库单可申请未离场纠错");
        require(!EXECUTED.equals(b.getCorrectionState()), "销售出库单已经纠错完成");
        require(!shipmentCustodyService.blocksCorrection(b.getId()), "销售出库已经承运交接或离场，必须走物流异常/客户退货，禁止未离场纠错");
    }

    private MomUser user() {
        MomUser u = SecurityUtils.getUser();
        require(u != null && StrUtil.isNotBlank(u.getUsername()), "无法识别当前操作人");
        return u;
    }

    private BigDecimal qty(Double q) {
        require(q != null && Double.isFinite(q) && q > 0, "数量必须为正数");
        return BigDecimal.valueOf(q);
    }

    private void requireFactory(String f) {
        require(StrUtil.isNotBlank(f) && Objects.equals(f, FactoryContextHolder.getFactoryCode()), "当前工厂无权操作该销售发运纠错");
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }

    private record Lineage(WmsDeliverGoodsDetailsEntity deliveryDetail, WmsDeliverGoodsPlanBaseEntity plan) {
    }

    private record ProofContext(List<WmsInoutFlowEntity> flows, Map<Long, Lineage> lineageByBillDetail,
                                Map<Long, WmsDeliverGoodsPlanBaseEntity> plans, BigDecimal total) {
    }
}
