package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesPhysicalTransformExecutionDTO;
import com.kitex.mom.mes.entity.MesPhysicalTransformExecutionEntity;

/**
 * 正式实物转换执行服务；不提供通用CRUD或绕过来源占用的入口。
 */
public interface MesPhysicalTransformExecutionService extends IService<MesPhysicalTransformExecutionEntity> {
    /**
     * 同一永久原号执行一次精确库存转换；响应不明只能继续用同原号和同正文核账。
     */
    MesPhysicalTransformExecutionDTO.Receipt apply(String requestId, String rawJson);

    /**
     * 本人按永久原号恢复正式执行结果；ABSENT不证明原请求未到达。
     */
    MesPhysicalTransformExecutionDTO.Receipt receipt(String requestId);
}
