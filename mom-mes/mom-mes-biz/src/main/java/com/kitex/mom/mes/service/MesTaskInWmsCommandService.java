package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesTaskInWmsDTO;
import com.kitex.mom.mes.entity.MesTaskInWmsCommandEntity;

/** 入库出站边界的持久命令；不是跨域冻结或新的库存CRUD。 */
public interface MesTaskInWmsCommandService extends IService<MesTaskInWmsCommandEntity> {
    /** 独立提交完整原命令后，才可启动原入库业务事务。 */
    MesTaskInWmsCommandEntity register(MesTaskInWmsDTO command);
    /** 独立最新读取并核原实名/组织/全文，不触发业务。 */
    MesTaskInWmsCommandEntity original(MesTaskInWmsDTO command);
    /** 在任何远端业务写前独立提交；失败时调用者不得发出请求。 */
    void markOutbound(MesTaskInWmsDTO command);
    /** 仅仍未发送且无MES回执时保存不可重开的终态；已发送/竞争一律不取消。 */
    void cancelIfUnsent(MesTaskInWmsDTO command);
}
