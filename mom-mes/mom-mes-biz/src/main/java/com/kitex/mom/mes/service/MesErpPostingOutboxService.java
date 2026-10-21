package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesErpPostingOutboxEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesTaskReportFeedEntity;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;

/** MES已提交执行事实的ERP转交服务。 */
public interface MesErpPostingOutboxService extends IService<MesErpPostingOutboxEntity> {
    void captureCompletion(MesTaskEntity task, MesTaskReportEntity report);
    void captureScrap(MesTaskEntity task, MesTaskReportUnqualifiedEntity report);
    void captureMaterial(MesTaskEntity task, MesTaskReportFeedEntity feed);
}
