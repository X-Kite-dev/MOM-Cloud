package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.WmsAllocationBillAddDTO;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.vo.WmsAllocationBillExcelVO;
import com.kitex.mom.wms.vo.WmsAllocationBillVO;

import java.util.List;

public interface WmsAllocationBillService extends IService<WmsAllocationBillEntity> {

    /**
     * 新增调拨单
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    Boolean saveAllocationBill(WmsAllocationBillAddDTO wmsAllocationBillAdd);

    /**
     * 调拨接收
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    Boolean updateAllocationBill(WmsAllocationBillAddDTO wmsAllocationBillAdd);

    /**
     * 通过id查询调拨单
     *
     * @param id id
     * @return R
     */
    WmsAllocationBillVO getDetails(Long id);

    /**
     * app-新增调拨单
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    Boolean appSaveAllocationBill(WmsAllocationBillAppDTO wmsAllocationBillAdd);

    /**
     * 导出excel 表格
     *
     * @param wmsAllocationBill 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    List<WmsAllocationBillExcelVO> export(WmsAllocationBillEntity wmsAllocationBill, Long[] ids);
}