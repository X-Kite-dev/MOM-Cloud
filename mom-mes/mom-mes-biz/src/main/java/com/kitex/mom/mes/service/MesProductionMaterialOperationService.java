package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesProductionMaterialOperationEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;

/** 独立生产耗料账。只在首次真实不良事务内登记，不能事后补耗料。 */
public interface MesProductionMaterialOperationService extends IService<MesProductionMaterialOperationEntity> {
    /** NONE保持声明；FEED明确本次新耗用，未知值拒绝而非默认。 */
    String mode(String requested);
    /** 原报工事务/父锁内执行，任何缺料/必要写/精度失败全部回滚。 */
    void recordFeed(MesTaskReportUnqualifiedEntity source, MesTaskArrangeEntity arrange, MesTaskEntity task);
    /** 只读取原账，不访问当前路线/MDM/参数、不重新扣料。 */
    MesProductionMaterialOperationEntity original(MesTaskReportUnqualifiedEntity source);
}
