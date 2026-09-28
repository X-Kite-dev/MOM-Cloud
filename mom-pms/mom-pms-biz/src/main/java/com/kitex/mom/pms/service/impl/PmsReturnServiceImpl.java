package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.pms.dto.PmsSupplierReturnCommand;
import com.kitex.mom.pms.dto.PmsSupplierReturnEvent;
import com.kitex.mom.pms.entity.*;
import com.kitex.mom.pms.mapper.*;
import com.kitex.mom.pms.service.PmsReturnService;
import com.kitex.mom.wms.api.feign.RemoteWmsSupplierReturnService;
import com.kitex.mom.wms.dto.WmsSupplierReturnCommand;
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
 * 采购到货后受控退供。
 *
 * <p>草稿不改采购账；提交冻结订单行和精确库存事实；另一账号批准后才创建 WMS 出库通知；
 * 采购订单与采购计划到货数只在 WMS 已实际扣减库存并返回永久事件后冲减。</p>
 */
@Service
@RequiredArgsConstructor
public class PmsReturnServiceImpl extends ServiceImpl<PmsReturnMapper, PmsReturnEntity>
        implements PmsReturnService {
    /**
     * 尚未冲减采购到货账、但已经占用可退额度的状态。
     * COMPLETED 已经在完成事件中冲减 arrivalNum，不能再次计入占用量。
     */
    private static final Set<String> ACTIVE_STATUSES = Set.of("SUBMITTED", "WMS_PENDING");

    private final PmsReturnDetailMapper detailMapper;
    private final PmsReturnOperationMapper operationMapper;
    private final PmsSupplierReturnInboxMapper inboxMapper;
    private final PmsOrderMapper orderMapper;
    private final PmsOrderDetailMapper orderDetailMapper;
    private final PmsPlanMapper planMapper;
    private final RemoteWmsSupplierReturnService remoteWmsSupplierReturnService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(PmsReturnEntity request) {
        validateDraft(request, false);
        request.setId(null);
        request.setFactoryCode(currentFactory());
        request.setWorkflowStatus("DRAFT");
        request.setWarehouse(Boolean.TRUE);
        request.setWarehouseStatus(Boolean.FALSE);
        require(baseMapper.insert(request) == 1, "采购退供草稿保存失败");
        saveDetails(request);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(PmsReturnEntity request) {
        validateDraft(request, true);
        PmsReturnEntity current = requireReturn(request.getId(), true);
        require(Set.of("DRAFT", "REJECTED").contains(current.getWorkflowStatus()),
                "只有草稿或已驳回退供单可以修改");
        request.setFactoryCode(current.getFactoryCode());
        request.setWorkflowStatus("DRAFT");
        request.setApplicantCode(null);
        request.setApplicantName(null);
        request.setAppliedTime(null);
        request.setReviewerCode(null);
        request.setReviewerName(null);
        request.setReviewedTime(null);
        request.setReviewRemark(null);
        request.setSubmitRequestId(null);
        request.setReviewRequestId(null);
        request.setSourceFingerprint(null);
        require(baseMapper.updateById(request) == 1, "采购退供草稿修改失败");
        detailMapper.delete(Wrappers.<PmsReturnDetailEntity>lambdaQuery()
                .eq(PmsReturnDetailEntity::getReturnId, request.getId()));
        saveDetails(request);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        require(ids != null && ids.length > 0, "请选择采购退供草稿");
        List<PmsReturnEntity> rows = baseMapper.selectByIds(Arrays.asList(ids));
        require(rows.size() == ids.length && rows.stream().allMatch(row -> "DRAFT".equals(row.getWorkflowStatus())),
                "只能删除当前工厂下的采购退供草稿");
        baseMapper.deleteByIds(Arrays.asList(ids));
        detailMapper.delete(Wrappers.<PmsReturnDetailEntity>lambdaQuery()
                .in(PmsReturnDetailEntity::getReturnId, Arrays.asList(ids)));
        return Boolean.TRUE;
    }

    @Override
    public List<WmsSupplierReturnCommand.StockCandidate> eligibleStocks(
            WmsSupplierReturnCommand.StockQuery query) {
        require(query != null && query.getOrderDetailId() != null && StrUtil.isNotBlank(query.getOrderNo())
                        && StrUtil.isNotBlank(query.getMaterialCode()) && StrUtil.isNotBlank(query.getSupplierCode()),
                "查询可退库存必须提供采购订单、订单行、物料和供应商");
        PmsOrderDetailEntity orderDetail = orderDetailMapper.selectOne(
                new LambdaQueryWrapper<PmsOrderDetailEntity>()
                        .eq(PmsOrderDetailEntity::getId, query.getOrderDetailId())
                        .eq(PmsOrderDetailEntity::getFactoryCode, currentFactory()), false);
        require(orderDetail != null && Objects.equals(orderDetail.getOrderNo(), query.getOrderNo())
                        && Objects.equals(orderDetail.getMatCode(), query.getMaterialCode()),
                "采购订单行不存在、跨工厂或与物料不一致");
        PmsOrderEntity order = orderMapper.selectById(orderDetail.getPmsOrderId());
        require(order != null && Objects.equals(order.getSupplierCode(), query.getSupplierCode()),
                "采购订单供应商与库存查询条件不一致");
        R<List<WmsSupplierReturnCommand.StockCandidate>> response =
                remoteWmsSupplierReturnService.eligibleStocks(query);
        require(response != null && response.isOk() && response.getData() != null,
                "WMS可退库存查询失败");
        return response.getData();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsReturnEntity submit(PmsSupplierReturnCommand.Submit command) {
        require(command != null && command.getReturnId() != null && validRequest(command.getRequestId()),
                "提交退供必须提供申请和永久请求号");
        PmsReturnOperationEntity replay = operation(command.getRequestId());
        if (replay != null) {
            require(Objects.equals(replay.getReturnId(), command.getReturnId())
                    && "SUBMIT".equals(replay.getOperationType()), "提交请求号已被其他操作使用");
            return requireReturn(command.getReturnId(), false);
        }
        PmsReturnEntity request = requireReturn(command.getReturnId(), true);
        require(Set.of("DRAFT", "REJECTED").contains(request.getWorkflowStatus()),
                "只有草稿或已驳回退供单可以提交");
        List<PmsReturnDetailEntity> details = details(request.getId());
        require(!details.isEmpty(), "采购退供明细不能为空");
        validateBusinessFacts(request, details);
        refreshStockSnapshots(request, details);
        String fingerprint = fingerprint(request, details);
        MomUser user = currentUser();
        String before = request.getWorkflowStatus();
        request.setWorkflowStatus("SUBMITTED");
        request.setApplicantCode(user.getUsername());
        request.setApplicantName(user.getName());
        request.setAppliedTime(LocalDateTime.now());
        request.setSubmitRequestId(command.getRequestId().trim());
        request.setSourceFingerprint(fingerprint);
        request.setReviewerCode(null);
        request.setReviewerName(null);
        request.setReviewedTime(null);
        request.setReviewRemark(null);
        // 明细快照必须在主单进入冻结态前落库；数据库保护触发器会拒绝提交后的事实改写。
        for (PmsReturnDetailEntity detail : details)
            require(detailMapper.updateById(detail) == 1,
                    "采购退供库存快照保存失败");
        require(baseMapper.updateById(request) == 1, "采购退供提交状态保存失败");
        audit(request, command.getRequestId(), "SUBMIT", before, "SUBMITTED",
                "冻结订单到货与WMS精确库存事实", user);
        request.setPmsReturnDetailList(details);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsReturnEntity review(PmsSupplierReturnCommand.Review command) {
        require(command != null && command.getReturnId() != null && validRequest(command.getRequestId())
                        && Set.of("APPROVE", "REJECT").contains(
                        StrUtil.blankToDefault(command.getDecision(), "").toUpperCase(Locale.ROOT))
                        && StrUtil.isNotBlank(command.getRemark()) && command.getRemark().trim().length() <= 500,
                "退供复核必须提供申请、永久请求号、决定和1至500字意见");
        PmsReturnOperationEntity replay = operation(command.getRequestId());
        if (replay != null) {
            require(Objects.equals(replay.getReturnId(), command.getReturnId())
                    && "REVIEW".equals(replay.getOperationType()), "复核请求号已被其他操作使用");
            return requireReturn(command.getReturnId(), false);
        }
        PmsReturnEntity request = requireReturn(command.getReturnId(), true);
        require("SUBMITTED".equals(request.getWorkflowStatus()), "只有待复核退供申请可以处理");
        MomUser reviewer = currentUser();
        require(!Objects.equals(request.getApplicantCode(), reviewer.getUsername()), "申请人与复核人必须分离");
        List<PmsReturnDetailEntity> details = details(request.getId());
        String decision = command.getDecision().trim().toUpperCase(Locale.ROOT);
        String after;
        if ("REJECT".equals(decision)) {
            after = "REJECTED";
        } else {
            validateBusinessFacts(request, details);
            refreshStockSnapshotsAndRequireUnchanged(request, details);
            require(Objects.equals(request.getSourceFingerprint(), fingerprint(request, details)),
                    "订单、计划或库存事实已变化，旧退供申请不能批准");
            WmsSupplierReturnCommand.NoticeReceipt receipt = createWmsNotice(request, details, command.getRequestId());
            request.setWmsNoticeId(receipt.getNoticeId());
            request.setWmsNoticeStatus(receipt.getNoticeStatus());
            request.setWarehouseStatus(Boolean.TRUE);
            after = "WMS_PENDING";
        }
        request.setWorkflowStatus(after);
        request.setReviewerCode(reviewer.getUsername());
        request.setReviewerName(reviewer.getName());
        request.setReviewedTime(LocalDateTime.now());
        request.setReviewRequestId(command.getRequestId().trim());
        request.setReviewRemark(command.getRemark().trim());
        require(baseMapper.updateById(request) == 1, "采购退供复核结果保存失败");
        audit(request, command.getRequestId(), "REVIEW", "SUBMITTED", after,
                decision + "：" + command.getRemark().trim(), reviewer);
        request.setPmsReturnDetailList(details);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsReturnEntity cancel(PmsSupplierReturnCommand.Cancel command) {
        require(command != null && command.getReturnId() != null && validRequest(command.getRequestId())
                        && StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500,
                "撤回退供必须提供申请、永久请求号和1至500字原因");
        PmsReturnOperationEntity replay = operation(command.getRequestId());
        if (replay != null) {
            require(Objects.equals(replay.getReturnId(), command.getReturnId())
                    && "CANCEL".equals(replay.getOperationType()), "撤回请求号已被其他操作使用");
            return requireReturn(command.getReturnId(), false);
        }
        PmsReturnEntity request = requireReturn(command.getReturnId(), true);
        MomUser user = currentUser();
        require("SUBMITTED".equals(request.getWorkflowStatus()), "只有待复核退供申请可以撤回");
        require(Objects.equals(request.getApplicantCode(), user.getUsername()), "只有申请人可以撤回待复核退供申请");
        request.setWorkflowStatus("CANCELLED");
        require(baseMapper.updateById(request) == 1, "采购退供撤回失败");
        audit(request, command.getRequestId(), "CANCEL", "SUBMITTED", "CANCELLED",
                command.getReason().trim(), user);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsSupplierReturnEvent.Receipt receiveCompleted(PmsSupplierReturnEvent event) {
        require(event != null && StrUtil.isNotBlank(event.getEventId())
                        && Objects.equals(event.getFingerprint(), event.calculateFingerprint())
                        && event.getReturnId() != null && CollUtil.isNotEmpty(event.getLines()),
                "WMS采购退供完成事件不完整或指纹不一致");
        PmsSupplierReturnInboxEntity existing = inboxMapper.selectOne(
                new LambdaQueryWrapper<PmsSupplierReturnInboxEntity>()
                        .eq(PmsSupplierReturnInboxEntity::getEventId, event.getEventId()), false);
        if (existing != null) {
            require(Objects.equals(existing.getFingerprint(), event.getFingerprint())
                            && Objects.equals(existing.getReturnId(), event.getReturnId()),
                    "同一WMS退供事件不能变更载荷");
            return eventReceipt(event, existing.getStatus());
        }
        PmsReturnEntity request = requireReturn(event.getReturnId(), true);
        require("WMS_PENDING".equals(request.getWorkflowStatus()), "采购退供申请未处于等待WMS实物出库状态");
        require(Objects.equals(request.getWmsNoticeId(), event.getWmsNoticeId()), "WMS通知来源与采购退供申请不一致");
        List<PmsReturnDetailEntity> details = details(request.getId());
        Map<Long, PmsSupplierReturnEvent.Line> eventLines = event.getLines().stream()
                .collect(Collectors.toMap(PmsSupplierReturnEvent.Line::getReturnDetailId,
                        Function.identity(), (left, right) -> {
                            throw new CheckedException("WMS退供事件包含重复采购退供明细");
                        }));
        require(eventLines.size() == details.size(), "WMS实际退供明细未完整覆盖采购申请");
        Map<Long, BigDecimal> byOrderDetail = new LinkedHashMap<>();
        Map<String, BigDecimal> byPlan = new LinkedHashMap<>();
        for (PmsReturnDetailEntity detail : details) {
            PmsSupplierReturnEvent.Line line = eventLines.get(detail.getId());
            require(line != null && Objects.equals(line.getOrderDetailId(), detail.getOrderDetailId())
                            && Objects.equals(line.getLiveStockId(), detail.getLiveStockId())
                            && Objects.equals(line.getMaterialCode(), detail.getMatCode())
                            && line.getActualReturnQuantity() != null
                            && line.getActualReturnQuantity().compareTo(decimal(detail.getReturnNum())) == 0,
                    "WMS实际退供行与批准的订单行、库存行、物料或数量不一致");
            detail.setActualReturnNum(line.getActualReturnQuantity());
            require(detailMapper.updateById(detail) == 1, "采购退供实退数量保存失败");
            byOrderDetail.merge(detail.getOrderDetailId(), line.getActualReturnQuantity(), BigDecimal::add);
            if (StrUtil.isNotBlank(detail.getPlanNo())) {
                byPlan.merge(detail.getPlanNo(), line.getActualReturnQuantity(), BigDecimal::add);
            }
        }
        applyOrderArrival(byOrderDetail);
        applyPlanArrival(byPlan);
        request.setWorkflowStatus("COMPLETED");
        request.setWmsNoticeStatus("COMPLETED");
        request.setCompletedTime(event.getCompletedTime());
        require(baseMapper.updateById(request) == 1, "采购退供完成状态保存失败");
        PmsSupplierReturnInboxEntity inbox = new PmsSupplierReturnInboxEntity()
                .setEventId(event.getEventId()).setReturnId(event.getReturnId())
                .setWmsNoticeId(event.getWmsNoticeId()).setWmsOutBillId(event.getWmsOutBillId())
                .setFingerprint(event.getFingerprint()).setPayload(JSONUtil.toJsonStr(event))
                .setStatus("PROCESSED").setReceivedTime(LocalDateTime.now())
                .setProcessedTime(LocalDateTime.now()).setFactoryCode(event.getFactoryCode());
        require(inboxMapper.insert(inbox) == 1, "WMS采购退供完成事件收件账保存失败");
        audit(request, event.getEventId(), "WMS_COMPLETED", "WMS_PENDING", "COMPLETED",
                "WMS出库单" + event.getWmsOutBillNo() + "已实际扣减库存", null);
        return eventReceipt(event, "PROCESSED");
    }

    private void validateDraft(PmsReturnEntity request, boolean update) {
        require(request != null && (!update || request.getId() != null)
                        && StrUtil.isNotBlank(request.getReturnNo()) && request.getReturnNo().trim().length() <= 100
                        && StrUtil.isNotBlank(request.getOrderNo()) && StrUtil.isNotBlank(request.getSupplierCode())
                        && StrUtil.isNotBlank(request.getRemark()) && request.getRemark().trim().length() <= 500
                        && CollUtil.isNotEmpty(request.getPmsReturnDetailList()),
                "采购退供必须提供单号、采购订单、供应商、原因和精确库存明细");
        Set<Long> stockIds = new HashSet<>();
        Set<String> warehouses = new HashSet<>();
        for (PmsReturnDetailEntity detail : request.getPmsReturnDetailList()) {
            require(detail != null && detail.getOrderDetailId() != null && detail.getLiveStockId() != null
                            && StrUtil.isNotBlank(detail.getMatCode()) && StrUtil.isNotBlank(detail.getWarehouseCode())
                            && detail.getReturnNum() != null && detail.getReturnNum() > 0
                            && stockIds.add(detail.getLiveStockId()),
                    "每条退供明细必须指定唯一采购订单行、WMS库存行、物料、仓库和正数量");
            warehouses.add(detail.getWarehouseCode().trim());
        }
        require(warehouses.size() == 1, "一张采购退供单只能选择同一仓库库存，请按仓库拆单");
    }

    private void saveDetails(PmsReturnEntity request) {
        for (PmsReturnDetailEntity detail : request.getPmsReturnDetailList()) {
            detail.setId(null);
            detail.setReturnId(request.getId());
            detail.setFactoryCode(request.getFactoryCode());
            require(detailMapper.insert(detail) == 1, "采购退供明细保存失败");
        }
    }

    private void validateBusinessFacts(PmsReturnEntity request, List<PmsReturnDetailEntity> details) {
        String factory = currentFactory();
        Map<Long, BigDecimal> requested = details.stream().collect(Collectors.groupingBy(
                PmsReturnDetailEntity::getOrderDetailId, LinkedHashMap::new,
                Collectors.mapping(item -> decimal(item.getReturnNum()),
                        Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        for (Map.Entry<Long, BigDecimal> entry : requested.entrySet()) {
            PmsOrderDetailEntity orderDetail = orderDetailMapper.selectOne(
                    new LambdaQueryWrapper<PmsOrderDetailEntity>()
                            .eq(PmsOrderDetailEntity::getId, entry.getKey())
                            .eq(PmsOrderDetailEntity::getFactoryCode, factory).last("FOR UPDATE"), false);
            require(orderDetail != null && Objects.equals(orderDetail.getOrderNo(), request.getOrderNo()),
                    "采购退供订单行不存在、跨工厂或不属于所选订单");
            PmsOrderEntity order = orderMapper.selectById(orderDetail.getPmsOrderId());
            require(order != null && Objects.equals(order.getSupplierCode(), request.getSupplierCode()),
                    "采购退供供应商与原采购订单不一致");
            BigDecimal occupied = activeReturnQuantity(orderDetail.getId(), request.getId());
            BigDecimal available = decimal(orderDetail.getArrivalNum()).subtract(occupied).max(BigDecimal.ZERO);
            require(available.compareTo(entry.getValue()) >= 0,
                    "订单行[" + orderDetail.getId() + "]扣除其他退供申请后可退到货量不足");
            details.stream().filter(item -> Objects.equals(item.getOrderDetailId(), orderDetail.getId()))
                    .forEach(item -> {
                        require(Objects.equals(item.getMatCode(), orderDetail.getMatCode())
                                        && Objects.equals(item.getMatUnit(), orderDetail.getMatUnit()),
                                "退供库存物料或单位与采购订单行不一致");
                        item.setPlanNo(orderDetail.getPmsPlanNo());
                        item.setMatName(orderDetail.getMatName());
                        item.setMatSpec(orderDetail.getMatSpec());
                    });
        }
    }

    private BigDecimal activeReturnQuantity(Long orderDetailId, Long excludeReturnId) {
        List<PmsReturnDetailEntity> rows = detailMapper.selectList(
                new LambdaQueryWrapper<PmsReturnDetailEntity>()
                        .eq(PmsReturnDetailEntity::getOrderDetailId, orderDetailId)
                        .ne(excludeReturnId != null, PmsReturnDetailEntity::getReturnId, excludeReturnId));
        if (rows.isEmpty()) return BigDecimal.ZERO;
        Set<Long> ids = rows.stream().map(PmsReturnDetailEntity::getReturnId).collect(Collectors.toSet());
        Set<Long> active = baseMapper.selectList(new LambdaQueryWrapper<PmsReturnEntity>()
                        .in(PmsReturnEntity::getId, ids).in(PmsReturnEntity::getWorkflowStatus, ACTIVE_STATUSES))
                .stream().map(PmsReturnEntity::getId).collect(Collectors.toSet());
        return rows.stream().filter(row -> active.contains(row.getReturnId()))
                .map(row -> decimal(row.getReturnNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void refreshStockSnapshots(PmsReturnEntity request, List<PmsReturnDetailEntity> details) {
        for (PmsReturnDetailEntity detail : details) copyStockSnapshot(detail, currentStock(request, detail));
    }

    private void refreshStockSnapshotsAndRequireUnchanged(PmsReturnEntity request,
                                                          List<PmsReturnDetailEntity> details) {
        for (PmsReturnDetailEntity detail : details) {
            WmsSupplierReturnCommand.StockCandidate current = currentStock(request, detail);
            require(Objects.equals(detail.getStockFingerprint(), current.getStockFingerprint()),
                    "库存行[" + detail.getLiveStockId() + "]数量、预留、质量、效期或位置已变化");
            require(current.getReturnableQuantity().compareTo(decimal(detail.getReturnNum())) >= 0,
                    "库存行[" + detail.getLiveStockId() + "]当前可退数量不足");
        }
    }

    private WmsSupplierReturnCommand.StockCandidate currentStock(PmsReturnEntity request,
                                                                 PmsReturnDetailEntity detail) {
        List<WmsSupplierReturnCommand.StockCandidate> candidates = eligibleStocks(
                new WmsSupplierReturnCommand.StockQuery().setOrderNo(request.getOrderNo())
                        .setOrderDetailId(detail.getOrderDetailId()).setMaterialCode(detail.getMatCode())
                        .setSupplierCode(request.getSupplierCode())
                        .setWarehouseCode(detail.getWarehouseCode()).setBatchNo(detail.getMatBatch()));
        WmsSupplierReturnCommand.StockCandidate candidate = candidates.stream()
                .filter(item -> Objects.equals(item.getLiveStockId(), detail.getLiveStockId()))
                .findFirst().orElseThrow(() -> new CheckedException(
                        "库存行[" + detail.getLiveStockId() + "]不存在、已被预留或不再允许退供"));
        require(candidate.getReturnableQuantity().compareTo(decimal(detail.getReturnNum())) >= 0,
                "库存行[" + detail.getLiveStockId() + "]可退数量不足");
        return candidate;
    }

    private void copyStockSnapshot(PmsReturnDetailEntity detail, WmsSupplierReturnCommand.StockCandidate stock) {
        detail.setWarehouseCode(stock.getWarehouseCode());
        detail.setSourceReceiptId(stock.getSourceReceiptId());
        detail.setSourceReceiptDetailId(stock.getSourceReceiptDetailId());
        detail.setWarehouseName(stock.getWarehouseName());
        detail.setSiteCode(stock.getSiteCode());
        detail.setSiteName(stock.getSiteName());
        detail.setContainerCode(stock.getContainerCode());
        detail.setMatBatch(stock.getBatchNo());
        detail.setQualityStatus(stock.getQualityStatus());
        detail.setQualityResult(stock.getQualityResult());
        detail.setValidEndDate(stock.getValidEndDate());
        detail.setStockInTime(stock.getStockInTime());
        detail.setReturnableNumSnapshot(stock.getReturnableQuantity());
        detail.setStockFingerprint(stock.getStockFingerprint());
    }

    private WmsSupplierReturnCommand.NoticeReceipt createWmsNotice(PmsReturnEntity request,
                                                                   List<PmsReturnDetailEntity> details, String requestId) {
        WmsSupplierReturnCommand.CreateNotice command = new WmsSupplierReturnCommand.CreateNotice()
                .setRequestId(requestId.trim()).setReturnId(request.getId()).setReturnNo(request.getReturnNo())
                .setOrderNo(request.getOrderNo()).setSupplierCode(request.getSupplierCode())
                .setSupplierName(request.getSupplierName()).setReason(request.getRemark())
                .setFactoryCode(request.getFactoryCode()).setSourceFingerprint(request.getSourceFingerprint())
                .setLines(details.stream().map(detail -> new WmsSupplierReturnCommand.NoticeLine()
                        .setReturnDetailId(detail.getId()).setOrderDetailId(detail.getOrderDetailId())
                        .setPlanNo(detail.getPlanNo()).setLiveStockId(detail.getLiveStockId())
                        .setSourceReceiptId(detail.getSourceReceiptId())
                        .setSourceReceiptDetailId(detail.getSourceReceiptDetailId())
                        .setMaterialCode(detail.getMatCode()).setMaterialName(detail.getMatName())
                        .setMaterialSpec(detail.getMatSpec()).setMaterialUnit(detail.getMatUnit())
                        .setSupplierCode(request.getSupplierCode()).setWarehouseCode(detail.getWarehouseCode())
                        .setWarehouseName(detail.getWarehouseName()).setSiteCode(detail.getSiteCode())
                        .setSiteName(detail.getSiteName()).setContainerCode(detail.getContainerCode())
                        .setBatchNo(detail.getMatBatch()).setQualityStatus(detail.getQualityStatus())
                        .setQualityResult(detail.getQualityResult()).setValidEndDate(detail.getValidEndDate())
                        .setStockInTime(detail.getStockInTime()).setReturnQuantity(decimal(detail.getReturnNum()))
                        .setStockFingerprint(detail.getStockFingerprint())).toList());
        R<WmsSupplierReturnCommand.NoticeReceipt> response = remoteWmsSupplierReturnService.createNotice(command);
        WmsSupplierReturnCommand.NoticeReceipt receipt = response == null ? null : response.getData();
        require(response != null && response.isOk() && receipt != null
                        && Objects.equals(receipt.getReturnId(), request.getId())
                        && Objects.equals(receipt.getSourceFingerprint(), request.getSourceFingerprint()),
                "WMS未返回匹配的采购退供通知永久回执");
        return receipt;
    }

    private void applyOrderArrival(Map<Long, BigDecimal> returned) {
        for (Map.Entry<Long, BigDecimal> entry : returned.entrySet()) {
            PmsOrderDetailEntity detail = orderDetailMapper.selectOne(
                    new LambdaQueryWrapper<PmsOrderDetailEntity>()
                            .eq(PmsOrderDetailEntity::getId, entry.getKey()).last("FOR UPDATE"), false);
            require(detail != null && decimal(detail.getArrivalNum()).compareTo(entry.getValue()) >= 0,
                    "采购订单到货数量不足以冲减实际退供");
            detail.setArrivalNum(decimal(detail.getArrivalNum()).subtract(entry.getValue()).doubleValue());
            require(orderDetailMapper.updateById(detail) == 1, "采购订单实际退供数量回写失败");
        }
    }

    private void applyPlanArrival(Map<String, BigDecimal> returned) {
        for (Map.Entry<String, BigDecimal> entry : returned.entrySet()) {
            PmsPlanEntity plan = planMapper.selectOne(new LambdaQueryWrapper<PmsPlanEntity>()
                    .eq(PmsPlanEntity::getPmsPlanNo, entry.getKey())
                    .eq(PmsPlanEntity::getFactoryCode, currentFactory()).last("FOR UPDATE"), false);
            require(plan != null && decimal(plan.getPlanArrivalNum()).compareTo(entry.getValue()) >= 0,
                    "采购计划到货数量不足以冲减实际退供");
            plan.setPlanArrivalNum(decimal(plan.getPlanArrivalNum()).subtract(entry.getValue()).doubleValue());
            require(planMapper.updateById(plan) == 1, "采购计划实际退供数量回写失败");
        }
    }

    private String fingerprint(PmsReturnEntity request, List<PmsReturnDetailEntity> details) {
        List<List<Object>> facts = details.stream().sorted(Comparator.comparing(PmsReturnDetailEntity::getId))
                .map(detail -> Arrays.<Object>asList(detail.getId(), detail.getOrderDetailId(), detail.getPlanNo(),
                        detail.getLiveStockId(), detail.getMatCode(), detail.getMatUnit(),
                        detail.getSourceReceiptId(), detail.getSourceReceiptDetailId(),
                        number(decimal(detail.getReturnNum())), detail.getWarehouseCode(), detail.getSiteCode(),
                        detail.getContainerCode(), detail.getMatBatch(), detail.getStockFingerprint()))
                .toList();
        return hash(JSONUtil.toJsonStr(Arrays.asList(request.getId(), request.getReturnNo(), request.getOrderNo(),
                request.getSupplierCode(), request.getFactoryCode(), request.getRemark(), facts)));
    }

    private PmsReturnEntity requireReturn(Long id, boolean lock) {
        PmsReturnEntity request = baseMapper.selectOne(new LambdaQueryWrapper<PmsReturnEntity>()
                .eq(PmsReturnEntity::getId, id).eq(PmsReturnEntity::getFactoryCode, currentFactory())
                .last(lock ? "FOR UPDATE" : ""), false);
        require(request != null, "当前租户和工厂下不存在采购退供申请");
        return request;
    }

    private List<PmsReturnDetailEntity> details(Long returnId) {
        return detailMapper.selectList(new LambdaQueryWrapper<PmsReturnDetailEntity>()
                .eq(PmsReturnDetailEntity::getReturnId, returnId)
                .orderByAsc(PmsReturnDetailEntity::getId));
    }

    private PmsReturnOperationEntity operation(String requestId) {
        return operationMapper.selectOne(new LambdaQueryWrapper<PmsReturnOperationEntity>()
                .eq(PmsReturnOperationEntity::getRequestId, requestId.trim()), false);
    }

    private void audit(PmsReturnEntity request, String requestId, String type, String before, String after,
                       String detail, MomUser actor) {
        String operatorCode = actor == null ? "SYSTEM" : actor.getUsername();
        String operatorName = actor == null ? "WMS回写" : actor.getName();
        require(operationMapper.insert(new PmsReturnOperationEntity().setReturnId(request.getId())
                .setRequestId(requestId.trim()).setOperationType(type).setBeforeStatus(before)
                .setAfterStatus(after).setOperatorCode(operatorCode).setOperatorName(operatorName)
                .setOperationTime(LocalDateTime.now()).setDetail(detail)
                .setFactoryCode(request.getFactoryCode())) == 1, "采购退供操作履历保存失败");
    }

    private PmsSupplierReturnEvent.Receipt eventReceipt(PmsSupplierReturnEvent event, String status) {
        return new PmsSupplierReturnEvent.Receipt().setEventId(event.getEventId())
                .setReturnId(event.getReturnId()).setFingerprint(event.getFingerprint())
                .setStatus(status).setReceivedTime(LocalDateTime.now());
    }

    private MomUser currentUser() {
        return SecurityUtils.getUser();
    }

    private String currentFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory), "请求头必须提供当前工厂编码");
        return factory;
    }

    private boolean validRequest(String requestId) {
        return StrUtil.isNotBlank(requestId) && requestId.trim().length() <= 100;
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("采购退供事实指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
