package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesProductionNgCaptureDTO;
import com.kitex.mom.mes.entity.MesProductionNgCaptureEntity;
import com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.entity.MesCaseDispositionReceiptEntity;

/** 真实新不良实物隔离业务，原号查询与写入口分离。 */
public interface MesProductionNgCaptureService extends IService<MesProductionNgCaptureEntity> {
    /** 新生产声明、原耗用、隔离库存、入库事实和案件同一个实际事务。 */
    MesProductionNgCaptureDTO.Receipt capture(String raw);

    /** 移动端独立入口，只接受原派工mobileAllowed及MANUAL，不能借用工站通道。 */
    MesProductionNgCaptureDTO.Receipt captureMobile(String raw);

    /** 仅读取当前派工与真实物料单位；通道参数只能由控制器固定指定，不能来自HTTP正文。 */
    MesProductionNgCaptureDTO.Preparation prepare(String arrangeCode, boolean mobile);

    /** 仅原实名操作人读取永久结果，不受当前派工开完工状态影响。 */
    MesProductionNgCaptureDTO.Receipt receipt(String requestId);

    /** 完整原文作废：实际已提交则返回原ISOLATED；仅未执行原号可永久CANCELLED，不取消实物。 */
    MesProductionNgCaptureDTO.Receipt cancel(String cancellationRaw);

    /** 核不可变NG原计量、初始入库事实和当前根行原属性；余额由案件回执链另核。 */
    boolean validateOrigin(MesInventoryQualityCaseEntity qualityCase, MesWarehouseLiveStockEntity root);

    /** 在真实NG处置扣减/放行事务中写精确OUT/IN，绝不补旧TRANSFER历史流水。 */
    void dispositionFlows(MesInventoryQualityCaseEntity qualityCase, MesWarehouseLiveStockEntity root,
                          MesWarehouseLiveStockEntity released, MesCaseDispositionReceiptEntity receipt);
}
