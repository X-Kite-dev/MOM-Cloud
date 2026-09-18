package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.WmsAllocationBillAddDTO;
import com.kitex.mom.wms.dto.WmsAllocationCancelDTO;
import com.kitex.mom.wms.dto.WmsCrossWarehouseTransferDTO;
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

    Boolean cancel(WmsAllocationCancelDTO command);

    /** 读取跨仓可调库存并按需求顺序分配，绝不在建议阶段锁定或修改库存。 */
    List<WmsCrossWarehouseTransferDTO.Recommendation> transferRecommendations(
            WmsCrossWarehouseTransferDTO.Query query);

    /** 执行已审批的Agent调拨命令；执行前重新锁库并复核安全库存、预留、质量和在途。 */
    WmsCrossWarehouseTransferDTO.Receipt executeAgentTransfer(
            WmsCrossWarehouseTransferDTO.AgentCommand command);

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
