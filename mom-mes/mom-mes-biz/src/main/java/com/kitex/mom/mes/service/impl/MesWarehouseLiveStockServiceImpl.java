package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesLiveStockQueryDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.MesWarehouseInoutEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.enums.InOutEnum;
import com.kitex.mom.mes.enums.MesWarehouseMatDetailTypeEnum;
import com.kitex.mom.mes.enums.YesNoEnum;
import com.kitex.mom.mes.mapper.MesWarehouseInoutMapper;
import com.kitex.mom.mes.mapper.MesWarehouseLiveStockMapper;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockByMaterialExcelVO;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockDetailExcelVO;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 线边库实时库存
 *
 * @author kitex
 * @date 2025-02-20 13:54:13
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseLiveStockServiceImpl extends ServiceImpl<MesWarehouseLiveStockMapper, MesWarehouseLiveStockEntity> implements MesWarehouseLiveStockService {

    private final RemoteDictService remoteDictService;
    private final RemoteMaterialService remoteMaterialService;
    private final MesWarehouseInoutMapper mesWarehouseInoutMapper;

    @Override
    public List<MesWarehouseLiveStockDetailExcelVO> listDetailExcel(LambdaQueryWrapper<MesWarehouseLiveStockEntity> wrapper) {
        List<MesWarehouseLiveStockEntity> list = list(wrapper);
        // 获取单位字典
        List<SysDictItemEntity> material_unit = Optional.ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
        List<MesWarehouseLiveStockDetailExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MesWarehouseLiveStockEntity liveStock = list.get(i);
            MesWarehouseLiveStockDetailExcelVO excelVO = new MesWarehouseLiveStockDetailExcelVO();
            BeanUtil.copyProperties(liveStock, excelVO);
            excelVO.setRowNo(i + 1);
            excelVO.setStatus(Optional.ofNullable(YesNoEnum.getByCode(Integer.parseInt(liveStock.getStatus()))).orElse(YesNoEnum.NO).getDesc());
            excelVO.setMatUnit(Optional.ofNullable(unitMap.get(liveStock.getMatUnit())).orElse(liveStock.getMatUnit()));
            result.add(excelVO);
        }
        return result;
    }

    @Override
    public List<MesWarehouseLiveStockByMaterialExcelVO> listByMatExcel(QueryWrapper<MesWarehouseLiveStockEntity> wrapper) {
        List<MesWarehouseLiveStockEntity> list = list(wrapper);
        // 获取单位字典
        List<SysDictItemEntity> material_unit = Optional.ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
        List<MesWarehouseLiveStockByMaterialExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MesWarehouseLiveStockEntity liveStock = list.get(i);
            MesWarehouseLiveStockByMaterialExcelVO excelVO = new MesWarehouseLiveStockByMaterialExcelVO();
            BeanUtil.copyProperties(liveStock, excelVO);
            excelVO.setRowNo(i + 1);
            excelVO.setMatUnit(Optional.ofNullable(unitMap.get(liveStock.getMatUnit())).orElse(liveStock.getMatUnit()));
            result.add(excelVO);
        }
        return result;
    }

    /**
     * 线边库实时库存入库
     */
    @Override
    @Transactional
    public Boolean inStock(MesWarehouseLiveStockInoutDTO liveStock) {
        beforeInCheck(liveStock);
        LocalDateTime now = LocalDateTime.now();
        MesWarehouseLiveStockEntity liveStockEntity = new MesWarehouseLiveStockEntity();
        BeanUtil.copyProperties(liveStock, liveStockEntity);
        liveStockEntity.setInTime(now);
        liveStockEntity.setStatus(YesNoEnum.NO.getCode());
        save(liveStockEntity);
        // 入库流水
        MesWarehouseInoutEntity inout = new MesWarehouseInoutEntity();
        BeanUtil.copyProperties(liveStock, inout, "id");
        inout.setOperateNum(liveStock.getNum());
        inout.setOperateType(InOutEnum.IN.getCode());
        mesWarehouseInoutMapper.insert(inout);
        return Boolean.TRUE;
    }

    public void beforeInCheck(MesWarehouseLiveStockInoutDTO liveStock) {
        Optional.ofNullable(liveStock.getBizType()).orElseThrow(() -> new CheckedException("业务类型不能为空！"));
        Optional.ofNullable(liveStock.getMatCode()).orElseThrow(() -> new CheckedException("物料编码不能为空！"));
    }

    /**
     * 线边库实时库存出库
     */
    @Override
    @Transactional
    public Boolean outStock(MesWarehouseLiveStockInoutDTO liveStock) {
        beforeInCheck(liveStock);
        // 查询物料是否配置先入先出逻辑
        MdmMaterialEntity mdmMaterial = remoteMaterialService.getDetails(liveStock.getMatCode()).getData();
        // 决定排序字段
        String orderByField = Optional.ofNullable(mdmMaterial)
                .map(MdmMaterialEntity::getFifo)
                .filter(Boolean.TRUE::equals)
                .map(fifo -> mdmMaterial.getFifoField())
                .orElse("in_time");
        // 查找库存并扣除
        QueryWrapper<MesWarehouseLiveStockEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(MesWarehouseLiveStockEntity::getMatCode, liveStock.getMatCode())
                .eq(StrUtil.isNotBlank(liveStock.getMatBatch()), MesWarehouseLiveStockEntity::getMatBatch, liveStock.getMatBatch())
                .eq(StrUtil.isNotBlank(liveStock.getProduceTaskCode()), MesWarehouseLiveStockEntity::getProduceTaskCode, liveStock.getProduceTaskCode())
                .eq(StrUtil.isNotBlank(liveStock.getSiteCode()), MesWarehouseLiveStockEntity::getSiteCode, liveStock.getSiteCode())
                .eq(StrUtil.isNotBlank(liveStock.getSupplierCode()), MesWarehouseLiveStockEntity::getSupplierCode, liveStock.getSupplierCode())
                .eq(MesWarehouseLiveStockEntity::getStatus, YesNoEnum.NO.getCode());
        queryWrapper.orderByDesc(orderByField);
        queryWrapper.orderByDesc(!"in_time".equals(orderByField), "in_time");
        List<MesWarehouseLiveStockEntity> list = list(queryWrapper);
        if (list.size() == 0) {
            throw new CheckedException("库存中未查找到物料[" + liveStock.getMatCode() + "]");
        }
        double matSum = list.stream().map(MesWarehouseLiveStockEntity::getNum).filter(Objects::nonNull).mapToDouble(BigDecimal::doubleValue).reduce(Double::sum).orElse(0);
        BigDecimal outNum = liveStock.getNum();
        if (outNum.compareTo(BigDecimal.valueOf(matSum)) > 0) {
            throw new CheckedException("库存不足！");
        }
        for (MesWarehouseLiveStockEntity stock : list) {
            // 创建出库流水
            MesWarehouseInoutEntity inout = new MesWarehouseInoutEntity();
            BeanUtil.copyProperties(stock, inout, "id");
            inout.setBizType(liveStock.getBizType());
            inout.setMatDetailType(liveStock.getMatDetailType());
            inout.setOperateType(InOutEnum.OUT.getCode());
            inout.setOperateNum(outNum);

            if (stock.getNum().compareTo(outNum) > 0) {
                // 扣库存并结束
                update(new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                        .set(MesWarehouseLiveStockEntity::getNum, stock.getNum().subtract(outNum))
                        .eq(BaseEntity::getId, stock.getId())
                );
                mesWarehouseInoutMapper.insert(inout);
                break;
            } else if (stock.getNum().compareTo(outNum) == 0) {
                mesWarehouseInoutMapper.insert(inout);
                // 删除库存并结束
                removeById(stock.getId());
                break;
            } else {
                mesWarehouseInoutMapper.insert(inout);
                // 删除库存并继续
                removeById(stock.getId());
            }

            outNum = outNum.subtract(stock.getNum());
        }

        return Boolean.TRUE;
    }

    /**
     * 生产退料分页
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<MesWarehouseFeedDTO> getPageFeedBack(Page<MesWarehouseFeedDTO> pageParam, MesWarehouseFeedDTO query) {
        return baseMapper.getPageFeedBack(pageParam, query);
    }

    /**
     * 分页查询-线边库类型（原材料、在制品、半成品、成品）
     *
     * @param page         分页对象
     * @param mesLiveStock 实时库存
     * @return
     */
    @Override
    public Page<MesWarehouseLiveStockVO> getWmsLiveStockPageByMatType(Page page, MesLiveStockQueryDTO mesLiveStock) {
        if (MesWarehouseMatDetailTypeEnum.RAW.name().equals(mesLiveStock.getMatDetailType())) {
            return baseMapper.getWmsLiveStockPageByMatRaw(page, mesLiveStock);
        }
        return baseMapper.getWmsLiveStockPageByMatOther(page, mesLiveStock);
    }
}