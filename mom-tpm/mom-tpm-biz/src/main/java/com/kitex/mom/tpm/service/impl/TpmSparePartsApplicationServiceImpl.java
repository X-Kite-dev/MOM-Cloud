package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.tpm.dto.ApplyBySourceCodePageDto;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.BillOperateTypeEnum;
import com.kitex.mom.tpm.enums.SparePartsApplicationStateEnum;
import com.kitex.mom.tpm.enums.SparePartsApplicationSourceEnum;
import com.kitex.mom.tpm.mapper.TpmRepairSpareStockReceiptMapper;
import com.kitex.mom.tpm.mapper.TpmRepairTaskMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsApplicationMapper;
import com.kitex.mom.tpm.service.TpmSparePartsApplicationDetailsService;
import com.kitex.mom.tpm.service.TpmSparePartsApplicationService;
import com.kitex.mom.tpm.service.TpmSparePartsInOrOutRecordService;
import com.kitex.mom.tpm.service.TpmSparePartsStockService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 备件出入库申请主表
 *
 * @author kitex
 * @date 2025-03-18 10:32:15
 */
@Service
@AllArgsConstructor
public class TpmSparePartsApplicationServiceImpl extends ServiceImpl<TpmSparePartsApplicationMapper, TpmSparePartsApplicationEntity> implements TpmSparePartsApplicationService {


    private final TpmSparePartsApplicationDetailsService tpmSparePartsApplicationDetailsService;

    private final TpmSparePartsStockService tpmSparePartsStockService;

    private final TpmSparePartsInOrOutRecordService tpmSparePartsInOrOutRecordService;

    private final TpmRepairSpareStockReceiptMapper repairStockReceipts;

    private final TpmRepairTaskMapper repairTasks;

    private final ObjectMapper objectMapper;


    /**
     * 公用备件申请新增
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addApply(TpmSparePartsApplicationEntity entity) {
        this.save(entity);
        //保存子表
        entity.getDetailsList().forEach(item -> {
            item.setApplyCode(entity.getApplyCode());
        });
        tpmSparePartsApplicationDetailsService.saveBatch(entity.getDetailsList());
        return Boolean.TRUE;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addOutApplication(TpmSparePartsApplicationEntity entity) {
        MomUser user = SecurityUtils.getUser();
        if (CollUtil.isEmpty(entity.getDetailsList())) {
            throw new RuntimeException("请添加申请备件！");
        }
        //保存主表
        entity.setType(BillOperateTypeEnum.OUT.getCode());
        entity.setRequesterCode(user.getUsername());
        entity.setRequesterName(user.getName());
        entity.setRequestDate(LocalDate.now());
        entity.setApprovalStatus(SparePartsApplicationStateEnum.WAIT.getCode());
        this.save(entity);
        //保存子表
        entity.getDetailsList().forEach(item -> {
            item.setId(null);
            item.setApplyCode(entity.getApplyCode());
        });
        tpmSparePartsApplicationDetailsService.saveBatch(entity.getDetailsList());
        return Boolean.TRUE;
    }

    /**
     * 根据申请表ID获取备件申请信息
     */
    @Override
    public TpmSparePartsApplicationEntity getApplicationById(Long id) {
        TpmSparePartsApplicationEntity byId = this.getById(id);
        List<TpmSparePartsApplicationDetailsEntity> detailsEntityList = tpmSparePartsApplicationDetailsService.list(new LambdaQueryWrapper<TpmSparePartsApplicationDetailsEntity>()
                .eq(TpmSparePartsApplicationDetailsEntity::getApplyCode, byId.getApplyCode()));
        byId.setDetailsList(detailsEntityList);
        byId.setOutBillList(new ArrayList<>());
        return byId;
    }

    @Override
    public List<TpmSparePartsApplicationDetailsEntity> getApplyBySourceCode(String sourceCode, String source) {

        List<TpmSparePartsApplicationEntity> partsApplicationEntities = this.list(new LambdaQueryWrapper<TpmSparePartsApplicationEntity>()
                .eq(TpmSparePartsApplicationEntity::getType, BillOperateTypeEnum.OUT.getCode())
                .eq(TpmSparePartsApplicationEntity::getSourceCode, sourceCode)
                .eq(TpmSparePartsApplicationEntity::getSource, source));
        if (CollUtil.isEmpty(partsApplicationEntities)) {
            return new ArrayList<>();
        }
        Map<String, TpmSparePartsApplicationEntity> applyMap = partsApplicationEntities.stream().collect(Collectors.toMap(TpmSparePartsApplicationEntity::getApplyCode, Function.identity(), (k1, k2) -> k1));
        List<String> applyCodes = partsApplicationEntities.stream().map(TpmSparePartsApplicationEntity::getApplyCode).distinct().toList();
        List<TpmSparePartsApplicationDetailsEntity> detailsEntityList = tpmSparePartsApplicationDetailsService.list(new LambdaQueryWrapper<TpmSparePartsApplicationDetailsEntity>()
                .in(TpmSparePartsApplicationDetailsEntity::getApplyCode, applyCodes));

        detailsEntityList.forEach(item -> {
            TpmSparePartsApplicationEntity tpmSparePartsApplicationEntity = applyMap.get(item.getApplyCode());
            item.setApprovalStatus(tpmSparePartsApplicationEntity.getApprovalStatus());
        });
        return detailsEntityList;
    }

    @Override
    public Page<TpmSparePartsApplicationDetailsEntity> getApplyBySourceCodePage(SearchPage page, ApplyBySourceCodePageDto dto) {
        return tpmSparePartsApplicationDetailsService.getApplyBySourceCodePage(page, dto);
    }

    /**
     * 备件申请审批
     *
     * @param handleType 0 审批不通过  1 审批通过
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean outApproval(TpmSparePartsApplicationEntity application, String handleType) {
        return approve(application, handleType, BillOperateTypeEnum.OUT.getCode());
    }


    /**
     * 申请退回审批
     *
     * @param handleType 0 审批不通过  1 审批通过
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean inApproval(TpmSparePartsApplicationEntity application, String handleType) {
        return approve(application, handleType, BillOperateTypeEnum.IN.getCode());
    }

    /**
     * 始终以数据库锁定的申请头和明细为准；客户端只提供本次库位分配，不能覆盖来源任务或申请数量。
     */
    private Boolean approve(TpmSparePartsApplicationEntity command, String handleType, String movementType) {
        MomUser user = SecurityUtils.getUser();
        if (user == null || user.getId() == null || command == null || command.getId() == null)
            throw new CheckedException("备件审批必须由实名用户针对精确申请执行");
        if (!List.of(YesOrNoStateEnum.NO.getCode(), YesOrNoStateEnum.YES.getCode()).contains(handleType))
            throw new CheckedException("备件审批动作无效");
        TpmSparePartsApplicationEntity application = baseMapper.lockById(command.getId());
        Long tenantId = TenantContextHolder.getTenantId();
        String factoryCode = FactoryContextHolder.getFactoryCode();
        if (application == null || tenantId == null || !Objects.equals(application.getTenantId(), tenantId)
                || !Objects.equals(application.getFactoryCode(), factoryCode)
                || !Objects.equals(application.getType(), movementType)
                || !SparePartsApplicationStateEnum.WAIT.getCode().equals(application.getApprovalStatus())) {
            throw new CheckedException("当前租户工厂的待审批备件申请不存在，或已被其他人处理");
        }
        application.setApprovalPerson(user.getUsername());
        application.setApprovalPersonName(user.getName());
        application.setApprovalTime(LocalDateTime.now());
        if (YesOrNoStateEnum.NO.getCode().equals(handleType)) {
            application.setApprovalStatus(SparePartsApplicationStateEnum.REJECT.getCode());
            if (!this.updateById(application)) throw new CheckedException("备件申请驳回保存失败");
            return Boolean.TRUE;
        }

        List<TpmSparePartsApplicationDetailsEntity> details = applicationDetails(application);
        List<TpmSparePartsInOrOutBillEntity> allocations = command.getOutBillList();
        validateAllocations(details, allocations);
        RepairReceiptContext receiptContext = repairReceiptContext(application, movementType);
        List<TpmSparePartsStockEntity> stocks = tpmSparePartsStockService.list(
                new LambdaQueryWrapper<TpmSparePartsStockEntity>()
                        .eq(TpmSparePartsStockEntity::getFactoryCode, factoryCode)
                        .last("FOR UPDATE"));
        Map<String, TpmSparePartsStockEntity> stockByKey = stocks.stream().collect(Collectors.toMap(
                item -> stockKey(item.getSparePartsCode(), item.getStoreroomCode(), item.getLocationCode()),
                Function.identity(), (left, right) -> {
                    throw new CheckedException("同工厂备件库存存在重复物料库位，须先核清库存主数据");
                }));
        List<TpmSparePartsStockEntity> changedStocks = new ArrayList<>();
        List<TpmSparePartsInOrOutRecordEntity> records = new ArrayList<>();
        for (TpmSparePartsInOrOutBillEntity allocation : allocations) {
            String key = stockKey(allocation.getSparePartsCode(), allocation.getStoreroomCode(), allocation.getLocationCode());
            TpmSparePartsStockEntity stock = stockByKey.get(key);
            if (BillOperateTypeEnum.OUT.getCode().equals(movementType)) {
                if (stock == null) throw new CheckedException("未查询到备件【" + allocation.getSparePartsCode() + "】的指定库位库存");
                BigDecimal balance = Objects.requireNonNullElse(stock.getInventoryNum(), BigDecimal.ZERO)
                        .subtract(allocation.getOperateNum());
                if (balance.signum() < 0) throw new CheckedException("备件【" + allocation.getSparePartsCode() + "】指定库位库存不足");
                stock.setInventoryNum(balance);
            } else {
                if (stock == null) {
                    stock = new TpmSparePartsStockEntity();
                    stock.setTenantId(tenantId);
                    stock.setFactoryCode(factoryCode);
                    stock.setSparePartsCode(allocation.getSparePartsCode());
                    stock.setStoreroomCode(allocation.getStoreroomCode());
                    stock.setLocationCode(allocation.getLocationCode());
                    stock.setInventoryNum(BigDecimal.ZERO);
                    stockByKey.put(key, stock);
                }
                stock.setInventoryNum(Objects.requireNonNullElse(stock.getInventoryNum(), BigDecimal.ZERO)
                        .add(allocation.getOperateNum()));
            }
            changedStocks.add(stock);
            records.add(buildRecord(allocation, movementType, application, receiptContext));
        }
        boolean stockSaved = BillOperateTypeEnum.OUT.getCode().equals(movementType)
                ? tpmSparePartsStockService.updateBatchById(changedStocks)
                : tpmSparePartsStockService.saveOrUpdateBatch(changedStocks);
        if (!stockSaved || !tpmSparePartsInOrOutRecordService.saveBatch(records))
            throw new CheckedException("备件库存或流水保存失败，审批整体回滚");
        application.setApprovalStatus(SparePartsApplicationStateEnum.PASS.getCode());
        if (!this.updateById(application)) throw new CheckedException("备件审批状态保存失败，审批整体回滚");
        if (receiptContext != null) saveRepairReceipt(application, receiptContext, records);
        return Boolean.TRUE;
    }

    private List<TpmSparePartsApplicationDetailsEntity> applicationDetails(TpmSparePartsApplicationEntity application) {
        List<TpmSparePartsApplicationDetailsEntity> result = tpmSparePartsApplicationDetailsService.list(
                new LambdaQueryWrapper<TpmSparePartsApplicationDetailsEntity>()
                        .eq(TpmSparePartsApplicationDetailsEntity::getApplyCode, application.getApplyCode())
                        .orderByAsc(TpmSparePartsApplicationDetailsEntity::getId));
        if (result == null || result.isEmpty() || result.size() > 1000)
            throw new CheckedException("原备件申请明细为空或超过审批上限");
        return result;
    }

    private void validateAllocations(List<TpmSparePartsApplicationDetailsEntity> details,
                                     List<TpmSparePartsInOrOutBillEntity> allocations) {
        if (allocations == null || allocations.isEmpty() || allocations.size() > 1000)
            throw new CheckedException("备件库存分配明细为空或超过审批上限");
        Map<String, BigDecimal> requested = new HashMap<>();
        Map<String, String> units = new HashMap<>();
        for (TpmSparePartsApplicationDetailsEntity detail : details) {
            requireText(detail.getSparePartCode(), 100, "申请备件编码");
            requireText(detail.getUnit(), 100, "申请备件单位");
            if (detail.getQuantityRequested() == null || detail.getQuantityRequested().signum() <= 0)
                throw new CheckedException("申请备件数量必须大于零");
            String oldUnit = units.putIfAbsent(detail.getSparePartCode(), detail.getUnit());
            if (oldUnit != null && !oldUnit.equals(detail.getUnit()))
                throw new CheckedException("同一备件申请存在不同单位，不能自动合并审批");
            requested.merge(detail.getSparePartCode(), detail.getQuantityRequested(), BigDecimal::add);
        }
        Map<String, BigDecimal> actual = new HashMap<>();
        Set<String> stockKeys = new HashSet<>();
        for (TpmSparePartsInOrOutBillEntity allocation : allocations) {
            requireText(allocation.getSparePartsCode(), 100, "库存备件编码");
            requireText(allocation.getSparePartsUnit(), 100, "库存备件单位");
            requireText(allocation.getStoreroomCode(), 100, "库房编码");
            requireText(allocation.getLocationCode(), 100, "库位编码");
            if (allocation.getOperateNum() == null || allocation.getOperateNum().signum() <= 0)
                throw new CheckedException("备件库存操作数量必须大于零");
            if (!requested.containsKey(allocation.getSparePartsCode())
                    || !Objects.equals(units.get(allocation.getSparePartsCode()), allocation.getSparePartsUnit()))
                throw new CheckedException("库存分配包含申请外备件或单位不一致：" + allocation.getSparePartsCode());
            if (!stockKeys.add(stockKey(allocation.getSparePartsCode(), allocation.getStoreroomCode(), allocation.getLocationCode())))
                throw new CheckedException("同一备件库位只能填写一条库存分配");
            actual.merge(allocation.getSparePartsCode(), allocation.getOperateNum(), BigDecimal::add);
        }
        if (!requested.keySet().equals(actual.keySet()))
            throw new CheckedException("库存分配未完整覆盖原申请备件");
        requested.forEach((code, quantity) -> {
            if (quantity.compareTo(actual.get(code)) != 0)
                throw new CheckedException("备件【" + code + "】申请数量与库存操作数量不一致");
        });
    }

    private RepairReceiptContext repairReceiptContext(TpmSparePartsApplicationEntity application,
                                                       String movementType) {
        boolean repairIssue = BillOperateTypeEnum.OUT.getCode().equals(movementType)
                && SparePartsApplicationSourceEnum.FAULT.getCode().equals(application.getSource());
        boolean repairReturn = BillOperateTypeEnum.IN.getCode().equals(movementType)
                && SparePartsApplicationSourceEnum.FAULT_RETURN.getCode().equals(application.getSource());
        if (!repairIssue && !repairReturn) return null;
        TpmRepairTaskEntity task = repairTasks.selectOne(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                .eq(TpmRepairTaskEntity::getTenantId, application.getTenantId())
                .eq(TpmRepairTaskEntity::getFactoryCode, application.getFactoryCode())
                .eq(TpmRepairTaskEntity::getTaskCode, application.getSourceCode())
                .last("LIMIT 1"));
        if (task == null) throw new CheckedException("维修备件申请未匹配同租户同工厂的维修任务");
        return new RepairReceiptContext(IdWorker.getId(), task);
    }

    private TpmSparePartsInOrOutRecordEntity buildRecord(TpmSparePartsInOrOutBillEntity bill,
                                                           String movementType,
                                                           TpmSparePartsApplicationEntity application,
                                                           RepairReceiptContext context) {
        TpmSparePartsInOrOutRecordEntity record = new TpmSparePartsInOrOutRecordEntity();
        BeanUtils.copyProperties(bill, record, "id", "tenantId", "factoryCode", "repairSpareReceiptId");
        record.setTenantId(application.getTenantId());
        record.setFactoryCode(application.getFactoryCode());
        record.setOperateType(movementType);
        record.setRepairSpareReceiptId(context == null ? null : context.receiptId());
        return record;
    }

    private void saveRepairReceipt(TpmSparePartsApplicationEntity application,
                                   RepairReceiptContext context,
                                   List<TpmSparePartsInOrOutRecordEntity> records) {
        try {
            List<Map<String, Object>> items = records.stream()
                    .sorted(Comparator.comparing(TpmSparePartsInOrOutRecordEntity::getId))
                    .map(record -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("recordId", String.valueOf(record.getId()));
                        item.put("sparePartsCode", record.getSparePartsCode());
                        item.put("sparePartsName", record.getSparePartsName());
                        item.put("unit", record.getSparePartsUnit());
                        item.put("storeroomCode", record.getStoreroomCode());
                        item.put("locationCode", record.getLocationCode());
                        item.put("quantity", record.getOperateNum());
                        return item;
                    }).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contract", "TPM_REPAIR_SPARE_STOCK_V1");
            payload.put("tenantId", String.valueOf(application.getTenantId()));
            payload.put("factoryCode", application.getFactoryCode());
            payload.put("applicationId", String.valueOf(application.getId()));
            payload.put("applyCode", application.getApplyCode());
            payload.put("repairTaskId", String.valueOf(context.task().getId()));
            payload.put("repairTaskCode", context.task().getTaskCode());
            payload.put("movementType", application.getType().toUpperCase());
            payload.put("approvedBy", application.getApprovalPerson());
            payload.put("approvedTime", application.getApprovalTime());
            payload.put("items", items);
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > 1_000_000) throw new CheckedException("维修备件库存回执超过容量上限");
            var receipt = new TpmRepairSpareStockReceiptEntity()
                    .setApplicationId(application.getId()).setApplyCode(application.getApplyCode())
                    .setRepairTaskId(context.task().getId()).setRepairTaskCode(context.task().getTaskCode())
                    .setMovementType(application.getType().toUpperCase())
                    .setApprovedBy(application.getApprovalPerson()).setApprovedByName(application.getApprovalPersonName())
                    .setApprovedTime(application.getApprovalTime()).setPayloadJson(json)
                    .setPayloadFingerprint(sha256(json)).setFactoryCode(application.getFactoryCode());
            receipt.setId(context.receiptId());
            receipt.setTenantId(application.getTenantId());
            if (repairStockReceipts.insert(receipt) != 1)
                throw new CheckedException("维修备件库存回执保存失败");
        } catch (CheckedException known) {
            throw known;
        } catch (Exception invalid) {
            throw new CheckedException("维修备件库存回执无法冻结，审批整体回滚");
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void requireText(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new CheckedException(label + "为空或超过长度限制");
    }

    private String stockKey(String code, String storeroom, String location) {
        return code + '\u001f' + storeroom + '\u001f' + location;
    }

    private record RepairReceiptContext(Long receiptId, TpmRepairTaskEntity task) {
    }

}
