package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.pms.dto.PmsDeliveryCommitmentCancelDTO;
import com.kitex.mom.pms.dto.PmsDeliveryCommitmentCommandDTO;
import com.kitex.mom.pms.dto.PmsDeliveryCommitmentItemDTO;
import com.kitex.mom.pms.entity.*;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.mapper.*;
import com.kitex.mom.pms.service.PmsDeliveryCommitmentService;
import com.kitex.mom.pms.service.PmsPlanCommitmentEventOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PmsDeliveryCommitmentServiceImpl implements PmsDeliveryCommitmentService {
    private static final Set<String> SOURCES = Set.of("SUPPLIER_PORTAL", "EDI", "BUYER_CONFIRMED", "OFFLINE_DOCUMENT");
    private static final Set<String> ELIGIBLE_ORDER_STATUSES = Set.of(
            PmsOrderStatusEnum.CHECK_SUCCESS.getCode(), PmsOrderStatusEnum.RECEIVING.getCode(),
            PmsOrderStatusEnum.PART_RECEIVE.getCode());

    private final PmsDeliveryCommitmentMapper commitmentMapper;
    private final PmsDeliveryCommitmentItemMapper itemMapper;
    private final PmsDeliveryCommitmentOperationMapper operationMapper;
    private final PmsOrderMapper orderMapper;
    private final PmsOrderDetailMapper detailMapper;
    private final PmsPlanCommitmentEventOutboxService planCommitmentEventOutboxService;

    @Override
    public List<PmsDeliveryCommitmentEntity> listByOrder(Long orderId) {
        if (orderId == null) return List.of();
        return hydrate(commitmentMapper.selectList(new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                .eq(PmsDeliveryCommitmentEntity::getPmsOrderId, orderId)
                .orderByAsc(PmsDeliveryCommitmentEntity::getOrderDetailId)
                .orderByDesc(PmsDeliveryCommitmentEntity::getVersionNo)));
    }

    @Override
    public List<PmsDeliveryCommitmentEntity> listActiveByPlanNos(String factoryCode, Collection<String> planNos) {
        if (StrUtil.isBlank(factoryCode) || CollUtil.isEmpty(planNos)) return List.of();
        List<String> values = planNos.stream().filter(StrUtil::isNotBlank).distinct().toList();
        if (values.isEmpty()) return List.of();
        return hydrate(commitmentMapper.selectList(new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                .eq(PmsDeliveryCommitmentEntity::getFactoryCode, factoryCode)
                .in(PmsDeliveryCommitmentEntity::getPmsPlanNo, values)
                .eq(PmsDeliveryCommitmentEntity::getStatus, "ACTIVE")
                .orderByAsc(PmsDeliveryCommitmentEntity::getPmsPlanNo)
                .orderByAsc(PmsDeliveryCommitmentEntity::getOrderDetailId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsDeliveryCommitmentEntity replace(PmsDeliveryCommitmentCommandDTO command) {
        validateCommand(command);
        PmsDeliveryCommitmentOperationEntity duplicate = findOperation(command.getRequestId());
        if (duplicate != null) {
            require(Objects.equals(duplicate.getOrderDetailId(), command.getOrderDetailId())
                            && "REPLACE".equals(duplicate.getOperationType())
                            && Objects.equals(duplicate.getContent(), JSONUtil.toJsonStr(command)),
                    "请求号已被其他交期承诺操作或不同载荷使用");
            return getWithItems(duplicate.getCommitmentId());
        }
        PmsOrderDetailEntity detail = lockDetail(command.getOrderDetailId());
        duplicate = findOperation(command.getRequestId());
        if (duplicate != null) {
            require(Objects.equals(duplicate.getOrderDetailId(), command.getOrderDetailId())
                            && "REPLACE".equals(duplicate.getOperationType())
                            && Objects.equals(duplicate.getContent(), JSONUtil.toJsonStr(command)),
                    "请求号已被其他交期承诺操作或不同载荷使用");
            return getWithItems(duplicate.getCommitmentId());
        }
        PmsOrderEntity order = requireOrder(detail.getPmsOrderId());
        require(ELIGIBLE_ORDER_STATUSES.contains(order.getStatus()), "只有审核通过且未完成收货的采购订单可以登记交期承诺");
        require(Objects.equals(order.getFactoryCode(), detail.getFactoryCode()), "采购订单与明细工厂不一致");

        BigDecimal orderNum = decimal(detail.getOrderNum());
        BigDecimal arrivalNum = decimal(detail.getArrivalNum());
        BigDecimal outstanding = orderNum.subtract(arrivalNum).max(BigDecimal.ZERO);
        require(outstanding.compareTo(BigDecimal.ZERO) > 0, "采购订单行已无未到货数量");
        List<PmsDeliveryCommitmentItemDTO> normalized = normalize(command.getItems());
        BigDecimal total = normalized.stream().map(PmsDeliveryCommitmentItemDTO::getCommittedNum)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        require(total.compareTo(outstanding) <= 0, "承诺总量不能超过当前未到货数量" + outstanding.stripTrailingZeros().toPlainString());

        PmsDeliveryCommitmentEntity active = commitmentMapper.selectOne(new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                .eq(PmsDeliveryCommitmentEntity::getOrderDetailId, detail.getId())
                .eq(PmsDeliveryCommitmentEntity::getStatus, "ACTIVE").last("FOR UPDATE"));
        Integer nextVersion = commitmentMapper.selectList(new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                        .eq(PmsDeliveryCommitmentEntity::getOrderDetailId, detail.getId()))
                .stream().map(PmsDeliveryCommitmentEntity::getVersionNo).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
        MomUser user = SecurityUtils.getUser();
        Long newId = IdUtil.getSnowflakeNextId();
        if (active != null) {
            active.setStatus("SUPERSEDED").setSupersededById(newId);
            commitmentMapper.updateById(active);
        }
        PmsDeliveryCommitmentEntity created = new PmsDeliveryCommitmentEntity()
                .setCommitmentNo("PDC-" + newId).setPmsOrderId(order.getId()).setOrderDetailId(detail.getId())
                .setPmsOrderNo(order.getPmsOrderNo()).setPmsPlanNo(detail.getPmsPlanNo())
                .setSupplierCode(order.getSupplierCode()).setSupplierName(order.getSupplierName())
                .setMatCode(detail.getMatCode()).setMatName(detail.getMatName()).setMatUnit(detail.getMatUnit())
                .setVersionNo(nextVersion).setStatus("ACTIVE").setTotalCommittedNum(total)
                .setOrderNumSnapshot(orderNum).setArrivalNumBaseline(arrivalNum)
                .setSourceType(command.getSourceType()).setEvidenceRef(command.getEvidenceRef().trim())
                .setChangeReason(command.getReason().trim()).setConfirmerCode(user.getUsername())
                .setConfirmerName(user.getName()).setConfirmedTime(LocalDateTime.now()).setFactoryCode(detail.getFactoryCode());
        created.setId(newId);
        commitmentMapper.insert(created);
        List<PmsDeliveryCommitmentItemEntity> items = new ArrayList<>();
        for (int index = 0; index < normalized.size(); index++) {
            PmsDeliveryCommitmentItemDTO value = normalized.get(index);
            PmsDeliveryCommitmentItemEntity item = new PmsDeliveryCommitmentItemEntity()
                    .setCommitmentId(newId).setSequenceNo(index + 1).setCommittedNum(value.getCommittedNum())
                    .setCommittedDeliveryDate(value.getCommittedDeliveryTime().toLocalDate())
                    .setCommittedDeliveryTime(value.getCommittedDeliveryTime())
                    .setDeliveryTimePrecision(value.getDeliveryTimePrecision())
                    .setFactoryCode(detail.getFactoryCode());
            itemMapper.insert(item);
            items.add(item);
        }
        operation(created, detail.getId(), command.getRequestId(), "REPLACE",
                active == null ? null : "ACTIVE", "ACTIVE", JSONUtil.toJsonStr(command));
        created.setItems(items);
        planCommitmentEventOutboxService.enqueueActivated(created);
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean cancel(PmsDeliveryCommitmentCancelDTO command) {
        require(command != null && command.getCommitmentId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "取消交期承诺必须指定承诺、请求号和原因");
        require(command.getRequestId().length() <= 64 && command.getReason().length() <= 500, "请求号最多64位，取消原因最多500位");
        PmsDeliveryCommitmentOperationEntity duplicate = findOperation(command.getRequestId());
        if (duplicate != null) {
            require(Objects.equals(duplicate.getCommitmentId(), command.getCommitmentId())
                    && "CANCEL".equals(duplicate.getOperationType()), "请求号已被其他交期承诺操作使用");
            return Boolean.TRUE;
        }
        PmsDeliveryCommitmentEntity commitment = commitmentMapper.selectOne(new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                .eq(PmsDeliveryCommitmentEntity::getId, command.getCommitmentId()).last("FOR UPDATE"));
        duplicate = findOperation(command.getRequestId());
        if (duplicate != null) {
            require(Objects.equals(duplicate.getCommitmentId(), command.getCommitmentId())
                    && "CANCEL".equals(duplicate.getOperationType()), "请求号已被其他交期承诺操作使用");
            return Boolean.TRUE;
        }
        require(commitment != null && "ACTIVE".equals(commitment.getStatus()), "只有当前有效版本可以取消");
        MomUser user = SecurityUtils.getUser();
        commitment.setStatus("CANCELLED").setCancelledBy(user.getUsername()).setCancelledByName(user.getName())
                .setCancelledTime(LocalDateTime.now()).setCancelReason(command.getReason().trim());
        commitmentMapper.updateById(commitment);
        operation(commitment, commitment.getOrderDetailId(), command.getRequestId(), "CANCEL",
                "ACTIVE", "CANCELLED", command.getReason().trim());
        planCommitmentEventOutboxService.enqueueCancelled(commitment);
        return Boolean.TRUE;
    }

    private void validateCommand(PmsDeliveryCommitmentCommandDTO command) {
        require(command != null && command.getOrderDetailId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getSourceType()) && StrUtil.isNotBlank(command.getEvidenceRef())
                && StrUtil.isNotBlank(command.getReason()) && CollUtil.isNotEmpty(command.getItems()), "交期承诺参数不完整");
        require(command.getRequestId().length() <= 64 && command.getReason().length() <= 500
                && command.getEvidenceRef().length() <= 1000, "请求号最多64位、原因最多500位、证据引用最多1000位");
        require(SOURCES.contains(command.getSourceType()), "交期承诺来源无效");
        require(command.getItems().size() <= 20, "单次交期承诺最多拆分20个到货批次");
    }

    private List<PmsDeliveryCommitmentItemDTO> normalize(List<PmsDeliveryCommitmentItemDTO> source) {
        Map<LocalDateTime, BigDecimal> byTime = new LinkedHashMap<>();
        Map<LocalDateTime, String> precisionByTime = new LinkedHashMap<>();
        for (PmsDeliveryCommitmentItemDTO item : source) {
            require(item != null && (item.getCommittedDeliveryTime() != null
                            || item.getCommittedDeliveryDate() != null) && item.getCommittedNum() != null
                            && item.getCommittedNum().compareTo(BigDecimal.ZERO) > 0,
                    "每个承诺批次必须包含正数数量和承诺到货时刻");
            LocalDateTime deliveryTime = item.getCommittedDeliveryTime() == null
                    ? item.getCommittedDeliveryDate().atTime(LocalTime.of(23, 59))
                    : item.getCommittedDeliveryTime().truncatedTo(ChronoUnit.MINUTES);
            String precision = item.getCommittedDeliveryTime() == null ? "DATE_END" : "MINUTE";
            byTime.merge(deliveryTime, item.getCommittedNum(), BigDecimal::add);
            precisionByTime.merge(deliveryTime, precision,
                    (left, right) -> "MINUTE".equals(left) && "MINUTE".equals(right) ? "MINUTE" : "DATE_END");
        }
        return byTime.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            PmsDeliveryCommitmentItemDTO item = new PmsDeliveryCommitmentItemDTO();
            item.setCommittedDeliveryTime(entry.getKey());
            item.setCommittedDeliveryDate(entry.getKey().toLocalDate());
            item.setDeliveryTimePrecision(precisionByTime.get(entry.getKey()));
            item.setCommittedNum(entry.getValue());
            return item;
        }).toList();
    }

    private List<PmsDeliveryCommitmentEntity> hydrate(List<PmsDeliveryCommitmentEntity> commitments) {
        if (commitments.isEmpty()) return commitments;
        Map<Long, List<PmsDeliveryCommitmentItemEntity>> grouped = itemMapper.selectList(
                        new LambdaQueryWrapper<PmsDeliveryCommitmentItemEntity>()
                                .in(PmsDeliveryCommitmentItemEntity::getCommitmentId,
                                        commitments.stream().map(PmsDeliveryCommitmentEntity::getId).toList())
                                .orderByAsc(PmsDeliveryCommitmentItemEntity::getSequenceNo)).stream()
                .collect(Collectors.groupingBy(PmsDeliveryCommitmentItemEntity::getCommitmentId));
        commitments.forEach(item -> item.setItems(grouped.getOrDefault(item.getId(), List.of())));
        return commitments;
    }

    private PmsDeliveryCommitmentEntity getWithItems(Long id) {
        PmsDeliveryCommitmentEntity value = commitmentMapper.selectById(id);
        require(value != null, "交期承诺记录不存在");
        value.setItems(itemMapper.selectList(new LambdaQueryWrapper<PmsDeliveryCommitmentItemEntity>()
                .eq(PmsDeliveryCommitmentItemEntity::getCommitmentId, id)
                .orderByAsc(PmsDeliveryCommitmentItemEntity::getSequenceNo)));
        return value;
    }

    private PmsOrderDetailEntity lockDetail(Long id) {
        PmsOrderDetailEntity detail = detailMapper.selectOne(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getId, id).last("FOR UPDATE"));
        if (detail == null) throw new CheckedException("采购订单明细不存在");
        return detail;
    }

    private PmsOrderEntity requireOrder(Long id) {
        PmsOrderEntity order = orderMapper.selectById(id);
        if (order == null) throw new CheckedException("采购订单不存在");
        return order;
    }

    private PmsDeliveryCommitmentOperationEntity findOperation(String requestId) {
        return operationMapper.selectOne(new LambdaQueryWrapper<PmsDeliveryCommitmentOperationEntity>()
                .eq(PmsDeliveryCommitmentOperationEntity::getRequestId, requestId));
    }

    private void operation(PmsDeliveryCommitmentEntity commitment, Long detailId, String requestId,
                           String type, String before, String after, String content) {
        MomUser user = SecurityUtils.getUser();
        operationMapper.insert(new PmsDeliveryCommitmentOperationEntity()
                .setCommitmentId(commitment.getId()).setOrderDetailId(detailId).setRequestId(requestId)
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorCode(user.getUsername()).setOperatorName(user.getName()).setOperationTime(LocalDateTime.now())
                .setFactoryCode(commitment.getFactoryCode()));
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
