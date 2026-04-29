package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.tpm.dto.TpmSparePartsInOrOutBillDto;
import com.kitex.mom.tpm.entity.TpmSparePartsInOrOutBillEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsInOrOutRecordEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsStockEntity;
import com.kitex.mom.tpm.enums.BillOperateTypeEnum;
import com.kitex.mom.tpm.enums.SparePartsBillStateEnum;
import com.kitex.mom.tpm.mapper.TpmSparePartsInOrOutBillMapper;
import com.kitex.mom.tpm.service.TpmSparePartsInOrOutBillService;
import com.kitex.mom.tpm.service.TpmSparePartsInOrOutRecordService;
import com.kitex.mom.tpm.service.TpmSparePartsStockService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 备件 入/出库单
 *
 * @author kitex
 * @date 2025-02-11 16:42:13
 */
@Service
@AllArgsConstructor
public class TpmSparePartsInOrOutBillServiceImpl extends ServiceImpl<TpmSparePartsInOrOutBillMapper, TpmSparePartsInOrOutBillEntity> implements TpmSparePartsInOrOutBillService {

    private final TpmSparePartsStockService tpmSparePartsStockService;

    private final TpmSparePartsInOrOutRecordService tpmSparePartsInOrOutRecordService;

    @Override
    public Boolean saveBill(TpmSparePartsInOrOutBillDto tpmSparePartsInOrOutBillDto) {
        List<TpmSparePartsInOrOutBillEntity> detailsList = tpmSparePartsInOrOutBillDto.getDetailsList();
        if (CollUtil.isEmpty(detailsList)) {
            throw new RuntimeException("明细数据不能为空！");
        }
        Long count = detailsList.stream().filter(item -> item.getOperateNum().compareTo(BigDecimal.ZERO) <= 0).count();
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("明细数量不能为0！");
        }
        detailsList.forEach(item -> {
            item.setOperateType(tpmSparePartsInOrOutBillDto.getOperateType());
            item.setOperateName(tpmSparePartsInOrOutBillDto.getOperateName());
            item.setState(SparePartsBillStateEnum.CREATE.getCode());
        });
        return this.saveBatch(detailsList);
    }

    /**
     * @param ids  操作单据 ids
     * @param type 操作类型 0 作废  1通过
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean handle(List<Long> ids, String type) {
        //获取单据信息
        List<TpmSparePartsInOrOutBillEntity> byIds = this.listByIds(ids);
        //作废逻辑
        if (YesOrNoStateEnum.NO.getCode().equals(type)) {
            byIds.forEach(item -> {
                item.setState(SparePartsBillStateEnum.CANCEL.getCode());
            });
            return this.updateBatchById(byIds);
        }
        //通过逻辑
        //出库流水记录列表 和 库存信息变化列表
        List<TpmSparePartsStockEntity> tpmSparePartsStockList = new ArrayList<>();
        List<TpmSparePartsInOrOutRecordEntity> recordList = new ArrayList<>();

        //查询所有库存信息
        List<TpmSparePartsStockEntity> stockList = tpmSparePartsStockService.list(new LambdaQueryWrapper<>());
        //批量处理看第一个元素是出库还是入库 ， 剩余元素全部一样的
        TpmSparePartsInOrOutBillEntity entity = byIds.get(0);
        //进行出库操作，根据备件、库位、库房查询库存信息，扣除库存，库存小于0时报错，库存不足
        if (BillOperateTypeEnum.OUT.getCode().equals(entity.getOperateType())) {
            for (TpmSparePartsInOrOutBillEntity outBill : byIds) {
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
                outBill.setState(SparePartsBillStateEnum.PASS.getCode());
                //创建出库流水
                recordList.add(buildRecord(outBill));
            }
            tpmSparePartsStockService.updateBatchById(tpmSparePartsStockList);
        }
        //进行出库操作，根据备件、库位、库房查询库存信息，查到就增加库存数量，差不到新增
        if (BillOperateTypeEnum.IN.getCode().equals(entity.getOperateType())) {
            for (TpmSparePartsInOrOutBillEntity inBill : byIds) {
                TpmSparePartsStockEntity stockEntity = stockList.stream().filter(item -> item.getSparePartsCode().equals(inBill.getSparePartsCode())
                        && item.getStoreroomCode().equals(inBill.getStoreroomCode())
                        && item.getLocationCode().equals(inBill.getLocationCode())).findFirst().orElse(new TpmSparePartsStockEntity());
                stockEntity.setSparePartsCode(inBill.getSparePartsCode());
                stockEntity.setStoreroomCode(inBill.getStoreroomCode());
                stockEntity.setLocationCode(inBill.getLocationCode());
                stockEntity.setInventoryNum(ObjectUtil.isNotEmpty(stockEntity.getInventoryNum()) ?
                        stockEntity.getInventoryNum().add(inBill.getOperateNum()) : inBill.getOperateNum());
                tpmSparePartsStockList.add(stockEntity);
                inBill.setState(SparePartsBillStateEnum.PASS.getCode());
                //创建入库流水
                recordList.add(buildRecord(inBill));
            }
            tpmSparePartsStockService.saveOrUpdateBatch(tpmSparePartsStockList);
        }
        tpmSparePartsInOrOutRecordService.saveBatch(recordList);
        return this.updateBatchById(byIds);
    }


    private TpmSparePartsInOrOutRecordEntity buildRecord(TpmSparePartsInOrOutBillEntity bill) {
        TpmSparePartsInOrOutRecordEntity record = new TpmSparePartsInOrOutRecordEntity();
        BeanUtils.copyProperties(bill, record, "id");
        return record;
    }

}