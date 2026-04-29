package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.constant.enums.YesNoEnum;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.dto.WmsAllocationBillAddDTO;
import com.kitex.mom.wms.dto.WmsDetailDTO;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.dto.app.WmsLiveStockMoveDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.service.WmsAllocationBillService;
import com.kitex.mom.wms.service.WmsInoutFlowService;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.vo.WmsAllocationBillExcelVO;
import com.kitex.mom.wms.vo.WmsAllocationBillVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 调拨单
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@Service
@AllArgsConstructor
public class WmsAllocationBillServiceImpl extends ServiceImpl<WmsAllocationBillMapper, WmsAllocationBillEntity> implements WmsAllocationBillService {

    private final WmsInoutFlowMapper wmsInoutFlowMapper;

    private final WmsInoutFlowService wmsInoutFlowService;

    private final WmsLiveStockService wmsLiveStockService;

    private final RemoteWarehouseLocationService remoteWarehouseLocationService;

    /**
     * 新增调拨单
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveAllocationBill(WmsAllocationBillAddDTO wmsAllocationBillAdd) {
        WmsAllocationBillEntity wmsAllocationBill = new WmsAllocationBillEntity();
        BeanUtils.copyProperties(wmsAllocationBillAdd, wmsAllocationBill);
        wmsAllocationBill.setAllocationState(YesNoEnum.NO.getCode());
        this.save(wmsAllocationBill);
        // 记录出库流水
        wmsAllocationBillAdd.getDetails().forEach(detail -> {
            WmsInoutFlowEntity wmsInoutFlow = new WmsInoutFlowEntity();
            BeanUtils.copyProperties(detail, wmsInoutFlow);
            wmsInoutFlow.setWarehouseName(detail.getOldWarehouseName());
            wmsInoutFlow.setWarehouseCode(detail.getOldWarehouseCode());
            wmsInoutFlow.setSiteName(detail.getOldSiteName());
            wmsInoutFlow.setSiteCode(detail.getOldSiteCode());
            wmsInoutFlow.setBillNo(wmsAllocationBill.getBillNo());
            wmsInoutFlow.setOperateType(InOutEnum.OUT.getCode());
            wmsInoutFlow.setMatNum(detail.getOperateNum());
            wmsInoutFlow.setBillType(OutBillTypeEnum.ALLOCATION_OUT.getCode());
            wmsInoutFlow.setFactoryCode(wmsAllocationBill.getFactoryCode());
            wmsInoutFlowMapper.insert(wmsInoutFlow);
        });
        return Boolean.TRUE;
    }

    /**
     * 调拨接收
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateAllocationBill(WmsAllocationBillAddDTO wmsAllocationBillAdd) {
        MomUser user = SecurityUtils.getUser();
        WmsAllocationBillEntity wmsAllocationBill = new WmsAllocationBillEntity();
        BeanUtils.copyProperties(wmsAllocationBillAdd, wmsAllocationBill);
        wmsAllocationBill.setAllocationState(YesNoEnum.YES.getCode());
        wmsAllocationBill.setFinishTime(LocalDateTime.now());
        wmsAllocationBill.setFinishByName(user.getUsername());
        this.updateById(wmsAllocationBill);

        wmsAllocationBillAdd.getDetails().forEach(detail -> {

            // 实时库存更新逻辑
            wmsLiveStockService.materialSaveOrUpdate(detail);

            WmsInoutFlowEntity wmsInoutFlow = new WmsInoutFlowEntity();
            BeanUtils.copyProperties(detail, wmsInoutFlow);
            wmsInoutFlow.setWarehouseName(detail.getNewWarehouseName());
            wmsInoutFlow.setWarehouseCode(detail.getNewWarehouseCode());
            wmsInoutFlow.setSiteName(detail.getNewSiteName());
            wmsInoutFlow.setSiteCode(detail.getNewSiteCode());
            wmsInoutFlow.setBillId(wmsAllocationBill.getId());
            wmsInoutFlow.setBillNo(wmsAllocationBill.getBillNo());
            wmsInoutFlow.setOperateType(InOutEnum.IN.getCode());
            wmsInoutFlow.setBillType(InBillTypeEnum.ALLOCATION.getCode());
            wmsInoutFlowMapper.insert(wmsInoutFlow);
        });

        return Boolean.TRUE;
    }

    /**
     * 通过id查询调拨单
     *
     * @param id id
     * @return R
     */
    @Override
    public WmsAllocationBillVO getDetails(Long id) {
        WmsAllocationBillVO wmsAllocationBillVO = new WmsAllocationBillVO();
        WmsAllocationBillEntity byId = this.getById(id);
        BeanUtils.copyProperties(byId, wmsAllocationBillVO);
        List<WmsInoutFlowEntity> wmsInoutFlowEntities = wmsInoutFlowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>().eq(WmsInoutFlowEntity::getBillNo, wmsAllocationBillVO.getBillNo()));
        List<WmsDetailDTO> details = wmsInoutFlowEntities.stream()
                .map(wmsInoutFlowEntity -> {
                    WmsDetailDTO wmsDetailDTO = new WmsDetailDTO();
                    BeanUtils.copyProperties(wmsInoutFlowEntity, wmsDetailDTO);
                    wmsDetailDTO.setOldSiteCode(wmsInoutFlowEntity.getSiteCode());
                    wmsDetailDTO.setOldSiteName(wmsInoutFlowEntity.getSiteName());
                    wmsDetailDTO.setOldWarehouseCode(wmsInoutFlowEntity.getWarehouseCode());
                    wmsDetailDTO.setOldWarehouseName(wmsInoutFlowEntity.getWarehouseName());
                    return wmsDetailDTO;
                }).toList();
        wmsAllocationBillVO.setDetails(details);
        return wmsAllocationBillVO;
    }

    /**
     * app-新增调拨单
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    @Override
    public Boolean appSaveAllocationBill(WmsAllocationBillAppDTO wmsAllocationBillAdd) {
        if (wmsAllocationBillAdd.getOperateNum() == null || wmsAllocationBillAdd.getOperateNum() <= 0) {
            throw new CheckedException("操作数量必须大于0");
        }
        MdmWarehouseLocationEntity siteEntity = remoteWarehouseLocationService.getByCode(wmsAllocationBillAdd.getNewSiteCode()).getData();
        if (siteEntity == null || !Objects.equals(wmsAllocationBillAdd.getNewWarehouseCode(), siteEntity.getWarehouseCode())) {
            throw new CheckedException("库位不存在");
        }
        WmsAllocationBillEntity wmsAllocationBill = new WmsAllocationBillEntity();
        BeanUtils.copyProperties(wmsAllocationBillAdd, wmsAllocationBill);
        wmsAllocationBill.setAllocationState(YesNoEnum.YES.getCode());
        this.save(wmsAllocationBill);

        // 记录出库流水
        wmsAllocationBillAdd.setBillNo(wmsAllocationBill.getBillNo());
        wmsAllocationBillAdd.setFactoryCode(wmsAllocationBill.getFactoryCode());
        wmsInoutFlowService.inOutFlow(wmsAllocationBillAdd);

        WmsLiveStockMoveDTO wmsLiveStockMoveDTO = new WmsLiveStockMoveDTO();
        BeanUtils.copyProperties(wmsAllocationBillAdd, wmsLiveStockMoveDTO);
        wmsLiveStockService.billStockMove(wmsLiveStockMoveDTO);
        return Boolean.TRUE;
    }

    /**
     * 导出excel 表格
     *
     * @param wmsAllocationBill 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<WmsAllocationBillExcelVO> export(WmsAllocationBillEntity wmsAllocationBill, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(wmsAllocationBill).in(ArrayUtil.isNotEmpty(ids), WmsAllocationBillEntity::getId, ids))
                .stream()
                .map(allocationBill -> {
                    WmsAllocationBillExcelVO wmsAllocationBillExcelVO = new WmsAllocationBillExcelVO();
                    BeanUtils.copyProperties(allocationBill, wmsAllocationBillExcelVO);
                    return wmsAllocationBillExcelVO;
                })
                .collect(Collectors.toList());
    }
}