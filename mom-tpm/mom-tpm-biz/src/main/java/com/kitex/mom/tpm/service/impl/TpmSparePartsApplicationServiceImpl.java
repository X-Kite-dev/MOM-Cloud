package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.tpm.dto.ApplyBySourceCodePageDto;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.BillOperateTypeEnum;
import com.kitex.mom.tpm.enums.SparePartsApplicationStateEnum;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        //审批数据赋值
        MomUser user = SecurityUtils.getUser();
        application.setApprovalPerson(user.getUsername());
        application.setApprovalPersonName(user.getName());
        application.setApprovalTime(LocalDateTime.now());
        //校验出库数据是否与申请数据一致
        List<TpmSparePartsApplicationDetailsEntity> detailsList = application.getDetailsList();
        Map<String, TpmSparePartsApplicationDetailsEntity> detailsEntityMap = detailsList.stream().collect(Collectors.toMap(TpmSparePartsApplicationDetailsEntity::getSparePartCode, Function.identity(), (k1, k2) -> k1));
        List<TpmSparePartsInOrOutBillEntity> outBillList = application.getOutBillList();
        // 将 outBillList 按 getSparePartsCode 分组，并计算每个 getSparePartsCode 的 operateNum 的和
        Map<String, BigDecimal> operateNumSumMap = outBillList.stream()
                .collect(Collectors.groupingBy(
                        TpmSparePartsInOrOutBillEntity::getSparePartsCode,
                        Collectors.reducing(BigDecimal.ZERO, TpmSparePartsInOrOutBillEntity::getOperateNum, BigDecimal::add)));

        List<String> errorList = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : operateNumSumMap.entrySet()) {
            if (ObjectUtil.isEmpty(detailsEntityMap.get(entry.getKey()))) {
                errorList.add("存在多余出库备件【" + entry.getKey() + "】!");
            }
        }
        detailsList.forEach(details -> {
            if (ObjectUtil.isEmpty(operateNumSumMap.get(details.getSparePartCode()))) {
                errorList.add("缺少备件【" + details.getSparePartCode() + "】的出库信息！");
            } else if (details.getQuantityRequested().compareTo(operateNumSumMap.get(details.getSparePartCode())) != 0) {
                errorList.add(String.format("备件【" + details.getSparePartCode() + "】申请数量与出库数量不一致，申请数量：%s，出库数量：%s",
                        details.getQuantityRequested(), operateNumSumMap.get(details.getSparePartCode())));
            }
        });
        if (CollUtil.isNotEmpty(errorList)) {
            throw new RuntimeException("审核失败！出库备件与需求备件不匹配: " + String.join(",", errorList));
        }
        //审批不通过
        if (handleType.equals(YesOrNoStateEnum.NO.getCode())) {
            //如果审批不通过，这条单据改为已驳回状态
            application.setApprovalStatus(SparePartsApplicationStateEnum.REJECT.getCode());
            return this.updateById(application);
        }
        //审批通过
        //出库流水记录列表 和 库存信息变化列表
        List<TpmSparePartsStockEntity> tpmSparePartsStockList = new ArrayList<>();
        List<TpmSparePartsInOrOutRecordEntity> recordList = new ArrayList<>();
        //查询所有库存信息
        List<TpmSparePartsStockEntity> stockList = tpmSparePartsStockService.list(new LambdaQueryWrapper<>());
        //1.出库-扣除库存
        //2.记录出入库流水记录
        for (TpmSparePartsInOrOutBillEntity outBill : application.getOutBillList()) {
            TpmSparePartsStockEntity stockEntity = stockList.stream().filter(item -> item.getSparePartsCode().equals(outBill.getSparePartsCode())
                    && item.getStoreroomCode().equals(outBill.getStoreroomCode())
                    && item.getLocationCode().equals(outBill.getLocationCode())).findFirst().orElse(null);
            if (ObjectUtil.isEmpty(stockEntity)) {
                throw new RuntimeException(String.format("未查询到位置:【%s %s】的%s备件库存信息", outBill.getStoreroomName(), outBill.getLocationName(), outBill.getSparePartsName()));
            }
            stockEntity.setInventoryNum(ObjectUtil.isNotEmpty(stockEntity.getInventoryNum()) ?
                    stockEntity.getInventoryNum().subtract(outBill.getOperateNum()) : BigDecimal.ZERO.subtract(outBill.getOperateNum()));
            if (stockEntity.getInventoryNum().compareTo(BigDecimal.ZERO) < 0) {
                throw new RuntimeException(String.format("位置:【%s %s】的%s备件库存不足", outBill.getStoreroomName(), outBill.getLocationName(), outBill.getSparePartsName()));
            }
            tpmSparePartsStockList.add(stockEntity);
            //创建出库流水
            recordList.add(buildRecord(outBill));
        }
        if (!tpmSparePartsStockService.updateBatchById(tpmSparePartsStockList)
                || !tpmSparePartsInOrOutRecordService.saveBatch(recordList)) {
            throw new RuntimeException("备件出库库存或流水保存失败，审批整体回滚");
        }
        application.setApprovalStatus(SparePartsApplicationStateEnum.PASS.getCode());
        if (!this.updateById(application)) throw new RuntimeException("备件出库审批状态保存失败，审批整体回滚");
        return Boolean.TRUE;
    }

    private TpmSparePartsInOrOutRecordEntity buildRecord(TpmSparePartsInOrOutBillEntity bill) {
        TpmSparePartsInOrOutRecordEntity record = new TpmSparePartsInOrOutRecordEntity();
        BeanUtils.copyProperties(bill, record, "id");
        return record;
    }


    /**
     * 申请退回审批
     *
     * @param handleType 0 审批不通过  1 审批通过
     */
    @Override
    public Boolean inApproval(TpmSparePartsApplicationEntity application, String handleType) {
        //审批数据赋值
        MomUser user = SecurityUtils.getUser();
        application.setApprovalPerson(user.getUsername());
        application.setApprovalPersonName(user.getName());
        application.setApprovalTime(LocalDateTime.now());
        //审批不通过
        if (handleType.equals(YesOrNoStateEnum.NO.getCode())) {
            //如果审批不通过，这条单据改为已驳回状态
            application.setApprovalStatus(SparePartsApplicationStateEnum.REJECT.getCode());
            return this.updateById(application);
        }

        //审批通过
        //出库流水记录列表 和 库存信息变化列表
        List<TpmSparePartsStockEntity> tpmSparePartsStockList = new ArrayList<>();
        List<TpmSparePartsInOrOutRecordEntity> recordList = new ArrayList<>();
        //查询所有库存信息
        List<TpmSparePartsStockEntity> stockList = tpmSparePartsStockService.list(new LambdaQueryWrapper<>());
        List<TpmSparePartsInOrOutBillEntity> outBillList = application.getOutBillList();

        for (TpmSparePartsInOrOutBillEntity inBill : outBillList) {
            TpmSparePartsStockEntity stockEntity = stockList.stream().filter(item -> item.getSparePartsCode().equals(inBill.getSparePartsCode())
                    && item.getStoreroomCode().equals(inBill.getStoreroomCode())
                    && item.getLocationCode().equals(inBill.getLocationCode())).findFirst().orElse(new TpmSparePartsStockEntity());
            stockEntity.setSparePartsCode(inBill.getSparePartsCode());
            stockEntity.setStoreroomCode(inBill.getStoreroomCode());
            stockEntity.setLocationCode(inBill.getLocationCode());
            stockEntity.setInventoryNum(ObjectUtil.isNotEmpty(stockEntity.getInventoryNum()) ?
                    stockEntity.getInventoryNum().add(inBill.getOperateNum()) : inBill.getOperateNum());
            tpmSparePartsStockList.add(stockEntity);
            //创建入库流水
            recordList.add(buildRecord(inBill));
        }
        tpmSparePartsStockService.saveOrUpdateBatch(tpmSparePartsStockList);
        tpmSparePartsInOrOutRecordService.saveBatch(recordList);
        application.setApprovalStatus(SparePartsApplicationStateEnum.PASS.getCode());
        this.updateById(application);
        return Boolean.TRUE;
    }


}
