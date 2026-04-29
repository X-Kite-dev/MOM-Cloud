package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsPickingAreaEntity;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.mapper.WmsPickingAreaMapper;
import com.kitex.mom.wms.service.WmsPickingAreaService;
import com.kitex.mom.wms.vo.WmsPickingAreaExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 拣货区
 *
 * @author kitex
 * @date 2025-01-21 15:38:39
 */
@Service
@RequiredArgsConstructor
public class WmsPickingAreaServiceImpl extends ServiceImpl<WmsPickingAreaMapper, WmsPickingAreaEntity> implements WmsPickingAreaService {

    private final WmsInoutFlowMapper wmsInoutFlowMapper;
    private final RemoteDictService remoteDictService;

    /**
     * 将削减的库存物料放到拣货区
     * 如果有相同的物料 就合并数量
     *
     * @param stockOuted 削减的库存
     * @param outBillNo  出库单号
     * @return
     */
    @Override
    @Transactional
    public Boolean insertByStockList(List<WmsLiveStockEntity> stockOuted, String outBillNo) {
        List<WmsPickingAreaEntity> insertList = new ArrayList<>();
        for (WmsLiveStockEntity entity : stockOuted) {
            // 查找有没有相同的物料可以合并数量的
            WmsPickingAreaEntity one = getOne(new LambdaQueryWrapper<WmsPickingAreaEntity>()
                            .eq(WmsPickingAreaEntity::getBillNo, outBillNo)
                            .eq(WmsPickingAreaEntity::getWarehouseCode, entity.getWarehouseCode())
                            .eq(WmsPickingAreaEntity::getSiteCode, entity.getSiteCode())
                            .eq(WmsPickingAreaEntity::getMatCode, entity.getMatCode())
                            .eq(WmsPickingAreaEntity::getMatBatch, entity.getMatBatch())
                            .eq(StrUtil.isNotBlank(entity.getContainerCode()), WmsPickingAreaEntity::getContainerCode, entity.getContainerCode())
                            .isNull(StrUtil.isBlank(entity.getContainerCode()), WmsPickingAreaEntity::getContainerCode)
                            .eq(StrUtil.isNotBlank(entity.getSupplierCode()), WmsPickingAreaEntity::getSupplierCode, entity.getSupplierCode())
                            .isNull(StrUtil.isBlank(entity.getSupplierCode()), WmsPickingAreaEntity::getSupplierCode)
                    , false);
            if (one != null) {
                update(new LambdaUpdateWrapper<WmsPickingAreaEntity>()
                        .set(WmsPickingAreaEntity::getMatNum, one.getMatNum() + entity.getMatNum())
                );
            } else {
                WmsPickingAreaEntity pickingArea = new WmsPickingAreaEntity();
                BeanUtil.copyProperties(entity, pickingArea, "id");
                pickingArea.setBillNo(outBillNo);
                insertList.add(pickingArea);
            }
        }
        if (insertList.size() > 0) {
            saveBatch(insertList);
        }
        return Boolean.TRUE;
    }

    /**
     * 删除拣货区物料 记录出库流水
     *
     * @param outBill 出库单
     */
    @Override
    @Transactional
    public Boolean deleteByOutBill(WmsOutBillEntity outBill) {
        List<WmsPickingAreaEntity> list = list(new LambdaQueryWrapper<WmsPickingAreaEntity>()
                .eq(WmsPickingAreaEntity::getBillNo, outBill.getBillNo())
        );
        // 删除
        remove(new LambdaQueryWrapper<WmsPickingAreaEntity>()
                .eq(WmsPickingAreaEntity::getBillNo, outBill.getBillNo())
        );
        // 出库流水
        List<WmsInoutFlowEntity> inoutFlows = new ArrayList<>();
        for (WmsPickingAreaEntity pickingArea : list) {
            WmsInoutFlowEntity inoutFlow = new WmsInoutFlowEntity();
            BeanUtil.copyProperties(pickingArea, inoutFlow, "id");
            inoutFlow.setBillNo(outBill.getBillNo());
            inoutFlow.setBillType(outBill.getBillType());
            inoutFlow.setOperateType(InOutEnum.OUT.getCode());
            inoutFlows.add(inoutFlow);
        }
        wmsInoutFlowMapper.insert(inoutFlows);
        return Boolean.TRUE;
    }

    /**
     * 导出
     *
     * @param wrapper 查询条件
     * @return
     */
    @Override
    public List<WmsPickingAreaExcelVO> exportExcel(LambdaQueryWrapper<WmsPickingAreaEntity> wrapper) {
        List<WmsPickingAreaEntity> list = list(wrapper);
        // 获取单位字典
        List<SysDictItemEntity> material_unit = Optional.ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
        List<WmsPickingAreaExcelVO> vos = list.stream().map(it -> {
            WmsPickingAreaExcelVO vo = new WmsPickingAreaExcelVO();
            BeanUtil.copyProperties(it, vo);
            vo.setMatUnit(Optional.ofNullable(unitMap.get(it.getMatUnit())).orElse(it.getMatUnit()));
            return vo;
        }).toList();
        for (int i = 0; i < vos.size(); i++) {
            vos.get(i).setRowNo(i + 1);
        }
        return vos;
    }
}