package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.*;

/**
 * MES已提交执行事实的ERP转交服务。
 */
public interface MesErpPostingOutboxService extends IService<MesErpPostingOutboxEntity> {
    void captureCompletion(MesTaskEntity task, MesTaskReportEntity report);

    void captureScrap(MesTaskEntity task, MesTaskReportUnqualifiedEntity report);

    void captureMaterial(MesTaskEntity task, MesTaskReportFeedEntity feed);
}
