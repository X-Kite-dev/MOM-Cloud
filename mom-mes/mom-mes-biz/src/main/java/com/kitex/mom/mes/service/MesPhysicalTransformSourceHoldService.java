package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesPhysicalTransformSourceHoldDTO;
import com.kitex.mom.mes.entity.MesPhysicalTransformCommandEntity;
import com.kitex.mom.mes.entity.MesPhysicalTransformSourceHoldEntity;

/** 永久来源占用领域入口；IService遵循持久服务规范，实际接口不暴露原账CRUD。 */
public interface MesPhysicalTransformSourceHoldService extends IService<MesPhysicalTransformSourceHoldEntity> {
    /** 核原command SHA，在服务器锁内重读完整来源并占用；不接受客户端证据包。 */
    MesPhysicalTransformSourceHoldDTO.Receipt acquire(String requestId, String rawJson);
    /** 本人按原hold SHA及原因取消，只释放活动占用，不删历史或重开原命令。 */
    MesPhysicalTransformSourceHoldDTO.Receipt cancel(String requestId, String rawJson);
    /** 独立当前事务本人恢复，不锁/补建/重投，不用当前源数量推断原结果。 */
    MesPhysicalTransformSourceHoldDTO.Receipt receipt(String requestId);
    /** 原command回执在其当前只读事务内附原hold事实，先核实际本人，不另拼旧快照。 */
    MesPhysicalTransformSourceHoldDTO.Receipt attachedReceipt(MesPhysicalTransformCommandEntity command);
}
