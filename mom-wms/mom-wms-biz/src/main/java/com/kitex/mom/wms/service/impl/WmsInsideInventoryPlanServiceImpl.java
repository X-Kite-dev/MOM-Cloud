package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.constant.enums.YesNoEnum;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanDetailMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.WmsInsideInventoryPlanService;
import com.kitex.mom.wms.vo.WmsInsideInventoryPlanExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 仓库盘点计划
 *
 * @author kitex
 * @date 2024-12-16 17:07:02
 */
@Service
@RequiredArgsConstructor
public class WmsInsideInventoryPlanServiceImpl extends ServiceImpl<WmsInsideInventoryPlanMapper, WmsInsideInventoryPlanEntity> implements WmsInsideInventoryPlanService {

    private final WmsInsideInventoryPlanDetailMapper wmsInsideInventoryPlanDetailMapper;

    private final WmsLiveStockMapper wmsLiveStockMapper;

    /**
     * 新增仓库盘点计划
     *
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(WmsInsideInventoryPlanEntity wmsInsideInventoryPlan) {
        wmsInsideInventoryPlan.setStatus(YesNoEnum.NO.getCode());
        wmsInsideInventoryPlan.setHandleState(YesNoEnum.NO.getCode());
        baseMapper.insert(wmsInsideInventoryPlan);
        for (WmsInsideInventoryPlanDetailEntity wmsInsideInventoryPlanDetail : wmsInsideInventoryPlan.getWmsInsideInventoryPlanDetailList()) {
            List<WmsLiveStockEntity> wmsLiveStockEntities = wmsLiveStockMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                    .eq(WmsLiveStockEntity::getMatCode, wmsInsideInventoryPlanDetail.getMatCode())
                    .eq(WmsLiveStockEntity::getWarehouseCode, wmsInsideInventoryPlan.getWarehouseCode())
                    .eq(WmsLiveStockEntity::getSiteCode, wmsInsideInventoryPlanDetail.getSiteCode()));

            double matSum = 0.0;
            if (!wmsLiveStockEntities.isEmpty()) {
                matSum = wmsLiveStockEntities.stream()
                        .mapToDouble(WmsLiveStockEntity::getMatNum)
                        .sum();
            }
            wmsInsideInventoryPlanDetail.setCurrentNumber(matSum);
            wmsInsideInventoryPlanDetail.setPlanId(wmsInsideInventoryPlan.getId());
            wmsInsideInventoryPlanDetail.setWarehouseCode(wmsInsideInventoryPlan.getWarehouseCode());
            wmsInsideInventoryPlanDetail.setWarehouseName(wmsInsideInventoryPlan.getWarehouseName());
            wmsInsideInventoryPlanDetail.setStatus(YesNoEnum.NO.getCode());
            wmsInsideInventoryPlanDetail.setPlStatus(YesNoEnum.NO.getCode());
            wmsInsideInventoryPlanDetail.setFactoryCode(wmsInsideInventoryPlan.getFactoryCode());
            wmsInsideInventoryPlanDetailMapper.insert(wmsInsideInventoryPlanDetail);
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
    public Boolean inventory(WmsInsideInventoryPlanEntity wmsInsideInventoryPlan) {
        wmsInsideInventoryPlan.setStatus(YesNoEnum.YES.getCode());
        baseMapper.updateById(wmsInsideInventoryPlan);
        for (WmsInsideInventoryPlanDetailEntity wmsInsideInventoryPlanDetail : wmsInsideInventoryPlan.getWmsInsideInventoryPlanDetailList()) {
            wmsInsideInventoryPlanDetail.setStatus(YesNoEnum.YES.getCode());
            wmsInsideInventoryPlanDetailMapper.updateById(wmsInsideInventoryPlanDetail);
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
        baseMapper.update(new LambdaUpdateWrapper<WmsInsideInventoryPlanEntity>()
                .set(WmsInsideInventoryPlanEntity::getHandleState, YesNoEnum.YES.getCode())
                .eq(WmsInsideInventoryPlanEntity::getId, id));

        wmsInsideInventoryPlanDetailMapper.update(new LambdaUpdateWrapper<WmsInsideInventoryPlanDetailEntity>()
                .set(WmsInsideInventoryPlanDetailEntity::getPlStatus, YesNoEnum.YES.getCode())
                .eq(WmsInsideInventoryPlanDetailEntity::getPlanId, id));
        return Boolean.TRUE;
    }

    @Override
    public Boolean completeDetails(Long id) {
        wmsInsideInventoryPlanDetailMapper.update(new LambdaUpdateWrapper<WmsInsideInventoryPlanDetailEntity>()
                .set(WmsInsideInventoryPlanDetailEntity::getPlStatus, YesNoEnum.YES.getCode())
                .eq(WmsInsideInventoryPlanDetailEntity::getId, id));
        return Boolean.TRUE;
    }

    /**
     * app-盘点录入
     *
     * @param wmsInsideInventoryPlanDetail 仓库盘点计划详情
     * @return R
     */
    @Override
    public Boolean appInventory(WmsInsideInventoryPlanDetailEntity wmsInsideInventoryPlanDetail) {
        wmsInsideInventoryPlanDetail.setStatus(YesNoEnum.YES.getCode());
        return wmsInsideInventoryPlanDetailMapper.updateById(wmsInsideInventoryPlanDetail) > 0;
    }

    /**
     * app-盘点录入完成
     *
     * @param id 仓库盘点计划id
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean appInventorySuccess(Long id) {
        WmsInsideInventoryPlanEntity wmsInsideInventoryPlanEntity = this.getById(id);
        wmsInsideInventoryPlanEntity.setStatus(YesNoEnum.YES.getCode());
        this.updateById(wmsInsideInventoryPlanEntity);

        wmsInsideInventoryPlanDetailMapper.update(new LambdaUpdateWrapper<WmsInsideInventoryPlanDetailEntity>()
                .set(WmsInsideInventoryPlanDetailEntity::getInventoryNum, 0.0)
                .set(WmsInsideInventoryPlanDetailEntity::getStatus, YesNoEnum.YES.getCode())
                .isNull(WmsInsideInventoryPlanDetailEntity::getInventoryNum)
                .eq(WmsInsideInventoryPlanDetailEntity::getPlanId, id));

        return Boolean.TRUE;
    }

    /**
     * 分页查询
     *
     * @param page                   分页对象
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return
     */
    @Override
    public Page<WmsInsideInventoryPlanEntity> appPage(Page page, WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan) {
        return baseMapper.appPage(page, wmsInsideInventoryPlan);
    }

    /**
     * 损益处理
     *
     * @param id 仓库盘点计划详情id
     * @return R
     */
    @Override
    public Boolean detailComplete(Long id) {
        wmsInsideInventoryPlanDetailMapper.update(new LambdaUpdateWrapper<WmsInsideInventoryPlanDetailEntity>()
                .set(WmsInsideInventoryPlanDetailEntity::getPlStatus, YesNoEnum.YES.getCode())
                .eq(WmsInsideInventoryPlanDetailEntity::getId, id));
        return Boolean.TRUE;
    }

    /**
     * 导出excel 表格
     *
     * @param wmsInsideInventoryPlan 查询条件
     * @param ids                    导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<WmsInsideInventoryPlanExcelVO> export(WmsInsideInventoryPlanEntity wmsInsideInventoryPlan, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(wmsInsideInventoryPlan).in(ArrayUtil.isNotEmpty(ids), WmsInsideInventoryPlanEntity::getId, ids))
                .stream()
                .map(wmsInsideInventoryPlanEntity -> {
                    WmsInsideInventoryPlanExcelVO wmsInsideInventoryPlanExcelVO = new WmsInsideInventoryPlanExcelVO();
                    BeanUtil.copyProperties(wmsInsideInventoryPlanEntity, wmsInsideInventoryPlanExcelVO);
                    return wmsInsideInventoryPlanExcelVO;
                })
                .collect(Collectors.toList());
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
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        wmsInsideInventoryPlanDetailMapper.delete(Wrappers.<WmsInsideInventoryPlanDetailEntity>lambdaQuery().in(WmsInsideInventoryPlanDetailEntity::getPlanId, ids));
        return Boolean.TRUE;
    }

    /**
     * 通过id查询仓库盘点计划
     *
     * @param id id
     * @return R
     */
    @Override
    public WmsInsideInventoryPlanEntity getDetails(Long id) {
        WmsInsideInventoryPlanEntity wmsInsideInventoryPlanEntity = this.getById(id);
        List<WmsInsideInventoryPlanDetailEntity> wmsInsideInventoryPlanDetailEntities = wmsInsideInventoryPlanDetailMapper.
                selectList(Wrappers.<WmsInsideInventoryPlanDetailEntity>lambdaQuery().eq(WmsInsideInventoryPlanDetailEntity::getPlanId, id));
        wmsInsideInventoryPlanEntity.setWmsInsideInventoryPlanDetailList(wmsInsideInventoryPlanDetailEntities);
        return wmsInsideInventoryPlanEntity;
    }
}