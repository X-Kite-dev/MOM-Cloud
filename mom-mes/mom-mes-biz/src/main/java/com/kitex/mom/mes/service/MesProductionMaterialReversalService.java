package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesProductionMaterialReversalDTO;
import com.kitex.mom.mes.entity.MesProductionMaterialOperationEntity;
import com.kitex.mom.mes.entity.MesProductionMaterialReversalEntity;

/** 误登记FEED的独立全额纠正，不撤销NG声明、不调用GOOD冲销或库存退料。 */
public interface MesProductionMaterialReversalService extends IService<MesProductionMaterialReversalEntity> {
    /** 独立事务执行严格原文；同原号只恢复原结果，禁止与其他生产写共事务。 */
    MesProductionMaterialReversalDTO.Receipt reverse(String raw);
    /** 仅本人原号恢复，不依据当前任务状态或余额猜首次结果。 */
    MesProductionMaterialReversalDTO.Receipt receipt(String requestId);
    /** 只读核完整原文；仅精确另一永久APPLIED原operation可证明本号不再执行，ABSENT不能清理。 */
    MesProductionMaterialReversalDTO.Resolution resolve(String raw);
    /** 在既有原不良查看授权下附加独立恢复结果，原FEED证据保持不变。 */
    MesProductionMaterialReversalDTO.Receipt attached(MesProductionMaterialOperationEntity original);
}
