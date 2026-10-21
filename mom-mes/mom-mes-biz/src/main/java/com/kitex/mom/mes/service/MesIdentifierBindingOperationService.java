package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesIdentifierBindingOperationEntity;
import java.util.List;

/** 节点操作永久账只读服务，追加仅在统一报工领域事务中进行。 */
public interface MesIdentifierBindingOperationService extends IService<MesIdentifierBindingOperationEntity> {
    /** 按精确实例返回完整操作链，超过安全上限明确报错而非截断证据。 */
    List<MesIdentifierBindingOperationEntity> history(Long bindingId);
}
