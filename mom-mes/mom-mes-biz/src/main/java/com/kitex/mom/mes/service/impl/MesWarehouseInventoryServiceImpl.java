package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.constant.enums.YesNoEnum;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.mom.mes.entity.MesWarehouseInventoryEntity;
import com.kitex.mom.mes.entity.MesWarehouseInventoryRecordEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.mapper.MesWarehouseInventoryMapper;
import com.kitex.mom.mes.mapper.MesWarehouseInventoryRecordMapper;
import com.kitex.mom.mes.mapper.MesWarehouseLiveStockMapper;
import com.kitex.mom.mes.service.MesWarehouseInventoryService;
import com.kitex.mom.mes.vo.MesWarehouseInventoryExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 仓库盘点计划
 *
 * @author kitex
 * @date 2024-12-16 17:07:02
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseInventoryServiceImpl extends ServiceImpl<MesWarehouseInventoryMapper, MesWarehouseInventoryEntity> implements MesWarehouseInventoryService {

    private final MesWarehouseInventoryRecordMapper inventoryRecordMapper;

    private final MesWarehouseLiveStockMapper liveStockMapper;

    /**
     * 新增仓库盘点计划
     *
     * @param inventoryEntity 仓库盘点计划
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MesWarehouseInventoryEntity inventoryEntity) {
        inventoryEntity.setStatus(YesNoEnum.NO.getCode());
        inventoryEntity.setHandleState(YesNoEnum.NO.getCode());
        baseMapper.insert(inventoryEntity);
        for (MesWarehouseInventoryRecordEntity inventoryRecord : inventoryEntity.getDetailList()) {
            List<MesWarehouseLiveStockEntity> wmsLiveStockEntities = liveStockMapper.selectList(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                    .eq(MesWarehouseLiveStockEntity::getMatCode, inventoryRecord.getMatCode())
                    .eq(MesWarehouseLiveStockEntity::getSiteCode, inventoryRecord.getSiteCode()));

            BigDecimal matSum = BigDecimal.ZERO;
            if (!wmsLiveStockEntities.isEmpty()) {
                double matSumD = wmsLiveStockEntities.stream()
                        .map(MesWarehouseLiveStockEntity::getNum)
                        .filter(Objects::nonNull)
                        .mapToDouble(BigDecimal::doubleValue)
                        .sum();
                matSum = BigDecimal.valueOf(matSumD);
            }
            inventoryRecord.setCurrentNum(matSum);
            inventoryRecord.setInventoryCode(inventoryEntity.getCode());
            inventoryRecord.setStatus(YesNoEnum.NO.getCode());
            inventoryRecord.setPlStatus(YesNoEnum.NO.getCode());
            inventoryRecordMapper.insert(inventoryRecord);
        }

        return Boolean.TRUE;
    }

    /**
     * 盘点录入
     *
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean inventory(MesWarehouseInventoryEntity wmsInsideInventoryPlan) {
        wmsInsideInventoryPlan.setStatus(YesNoEnum.YES.getCode());
        baseMapper.updateById(wmsInsideInventoryPlan);
        for (MesWarehouseInventoryRecordEntity inventoryRecord : wmsInsideInventoryPlan.getDetailList()) {
            inventoryRecord.setStatus(YesNoEnum.YES.getCode());
            inventoryRecordMapper.updateById(inventoryRecord);
        }
        return Boolean.TRUE;
    }

    /**
     * 损益处理
     *
     * @param id 仓库盘点计划id
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean complete(Long id) {
        MesWarehouseInventoryEntity entity = baseMapper.selectById(id);
        baseMapper.update(new LambdaUpdateWrapper<MesWarehouseInventoryEntity>()
                .set(MesWarehouseInventoryEntity::getHandleState, YesNoEnum.YES.getCode())
                .eq(MesWarehouseInventoryEntity::getId, id));

        inventoryRecordMapper.update(new LambdaUpdateWrapper<MesWarehouseInventoryRecordEntity>()
                .set(MesWarehouseInventoryRecordEntity::getPlStatus, YesNoEnum.YES.getCode())
                .eq(MesWarehouseInventoryRecordEntity::getInventoryCode, entity.getCode()));
        return Boolean.TRUE;
    }

    /**
     * 损益处理
     *
     * @param id 仓库盘点计划详情id
     * @return R
     */
    @Override
    public Boolean detailComplete(Long id) {
        inventoryRecordMapper.update(new LambdaUpdateWrapper<MesWarehouseInventoryRecordEntity>()
                .set(MesWarehouseInventoryRecordEntity::getPlStatus, YesNoEnum.YES.getCode())
                .eq(MesWarehouseInventoryRecordEntity::getId, id));
        return Boolean.TRUE;
    }

    /**
     * 导出excel 表格
     *
     * @param wrapper 查询条件
     * @param ids     导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MesWarehouseInventoryExcelVO> export(LambdaQueryWrapper<MesWarehouseInventoryEntity> wrapper, Long[] ids) {
        List<MesWarehouseInventoryEntity> list = this.list(wrapper.in(ArrayUtil.isNotEmpty(ids), MesWarehouseInventoryEntity::getId, ids));
        List<MesWarehouseInventoryExcelVO> vos = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MesWarehouseInventoryExcelVO excelVO = new MesWarehouseInventoryExcelVO();
            BeanUtil.copyProperties(list.get(i), excelVO);
            excelVO.setRowNo(i + 1);
            excelVO.setStatus(StrUtil.equals(excelVO.getStatus(), "0") ? "执行中" : "已完成");
            excelVO.setHandleState(StrUtil.equals(excelVO.getHandleState(), "0") ? "待处理" : "已处理");
            vos.add(excelVO);
        }
        return vos;
    }

    /**
     * 通过id删除仓库盘点计划
     *
     * @param ids id列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        List<MesWarehouseInventoryEntity> entities = baseMapper.selectList(new LambdaQueryWrapper<MesWarehouseInventoryEntity>()
                .in(BaseEntity::getId, ids)
        );
        List<String> codes = entities.stream().map(MesWarehouseInventoryEntity::getCode).toList();
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        inventoryRecordMapper.delete(Wrappers.<MesWarehouseInventoryRecordEntity>lambdaQuery().in(MesWarehouseInventoryRecordEntity::getInventoryCode, codes));
        return Boolean.TRUE;
    }

    /**
     * 通过id查询仓库盘点计划
     *
     * @param id id
     * @return R
     */
    @Override
    public MesWarehouseInventoryEntity getDetails(Long id) {
        MesWarehouseInventoryEntity warehouseInventory = this.getById(id);
        List<MesWarehouseInventoryRecordEntity> detailList = inventoryRecordMapper.
                selectList(Wrappers.<MesWarehouseInventoryRecordEntity>lambdaQuery().eq(MesWarehouseInventoryRecordEntity::getInventoryCode, warehouseInventory.getCode()));
        warehouseInventory.setDetailList(detailList);
        return warehouseInventory;
    }
}