package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteAddDTO;
import com.kitex.mom.wms.dto.app.WmsLiveStockMoveDTO;
import com.kitex.mom.wms.dto.app.WmsMaterialMoveSiteAppDTO;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.mapper.WmsMaterialMoveSiteMapper;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.WmsMaterialMoveSiteService;
import com.kitex.mom.wms.vo.WmsMaterialMoveSiteExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 库内移动
 *
 * @author kitex
 * @date 2024-12-30 14:13:22
 */
@Service
@AllArgsConstructor
public class WmsMaterialMoveSiteServiceImpl extends ServiceImpl<WmsMaterialMoveSiteMapper, WmsMaterialMoveSiteEntity> implements WmsMaterialMoveSiteService {

    private final WmsLiveStockService wmsLiveStockService;

    private final RemoteWarehouseLocationService remoteWarehouseLocationService;

    /**
     * 新增库内移动
     *
     * @param wmsMaterialMoveSiteAddDTO 库内移动
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean materialMoveSiteSave(WmsMaterialMoveSiteAddDTO wmsMaterialMoveSiteAddDTO) {

        wmsMaterialMoveSiteAddDTO.getDetails().forEach(detail -> {
            WmsMaterialMoveSiteEntity wmsMaterialMoveSiteEntity = new WmsMaterialMoveSiteEntity();
            BeanUtils.copyProperties(detail, wmsMaterialMoveSiteEntity);
            wmsMaterialMoveSiteEntity.setWarehouseCode(detail.getNewWarehouseCode());
            wmsMaterialMoveSiteEntity.setWarehouseName(detail.getNewWarehouseName());
            this.save(wmsMaterialMoveSiteEntity);

            wmsLiveStockService.materialSaveOrUpdate(detail);
        });

        return Boolean.TRUE;
    }

    /**
     * app-库内移动
     *
     * @param wmsMaterialMoveSiteAddDTO 库内移动
     * @return R
     */
    @Override
    public Boolean appMaterialMoveSiteSave(WmsMaterialMoveSiteAppDTO wmsMaterialMoveSiteAddDTO) {
        if (wmsMaterialMoveSiteAddDTO.getOperateNum() == null || wmsMaterialMoveSiteAddDTO.getOperateNum() <= 0) {
            throw new CheckedException("操作数量必须大于0");
        }
        MdmWarehouseLocationEntity siteEntity = remoteWarehouseLocationService.getByCode(wmsMaterialMoveSiteAddDTO.getWarehouseCode()).getData();
        if (siteEntity == null || !Objects.equals(wmsMaterialMoveSiteAddDTO.getWarehouseCode(), siteEntity.getWarehouseCode())) {
            throw new CheckedException("库位不存在");
        }
        WmsMaterialMoveSiteEntity wmsMaterialMoveSiteEntity = new WmsMaterialMoveSiteEntity();
        BeanUtils.copyProperties(wmsMaterialMoveSiteAddDTO, wmsMaterialMoveSiteEntity);
        this.save(wmsMaterialMoveSiteEntity);

        WmsLiveStockMoveDTO wmsLiveStockMoveDTO = new WmsLiveStockMoveDTO();
        BeanUtils.copyProperties(wmsMaterialMoveSiteAddDTO, wmsLiveStockMoveDTO);
        wmsLiveStockMoveDTO.setNewWarehouseCode(wmsMaterialMoveSiteAddDTO.getWarehouseCode());
        wmsLiveStockMoveDTO.setNewWarehouseName(wmsMaterialMoveSiteAddDTO.getWarehouseName());
        wmsLiveStockMoveDTO.setOldWarehouseName(wmsMaterialMoveSiteAddDTO.getWarehouseName());
        wmsLiveStockMoveDTO.setOldWarehouseCode(wmsMaterialMoveSiteAddDTO.getWarehouseCode());
        wmsLiveStockService.billStockMove(wmsLiveStockMoveDTO);
        return Boolean.TRUE;
    }

    /**
     * 导出excel 表格
     *
     * @param wmsMaterialMoveSite 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<WmsMaterialMoveSiteExcelVO> export(WmsMaterialMoveSiteEntity wmsMaterialMoveSite, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(wmsMaterialMoveSite).in(ArrayUtil.isNotEmpty(ids), WmsMaterialMoveSiteEntity::getId, ids))
                .stream()
                .map(moveSite -> {
                    WmsMaterialMoveSiteExcelVO wmsMaterialMoveSiteExcelVO = new WmsMaterialMoveSiteExcelVO();
                    BeanUtils.copyProperties(moveSite, wmsMaterialMoveSiteExcelVO);
                    return wmsMaterialMoveSiteExcelVO;
                })
                .collect(Collectors.toList());
    }
}