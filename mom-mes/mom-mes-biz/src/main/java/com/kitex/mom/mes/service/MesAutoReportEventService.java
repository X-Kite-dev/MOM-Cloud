package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesAutoReportEventDTO;
import com.kitex.mom.mes.dto.MesAutoReportResultDTO;
import com.kitex.mom.mes.dto.MesAutoReportRetryDTO;
import com.kitex.mom.mes.entity.MesAutoReportEventEntity;

public interface MesAutoReportEventService extends IService<MesAutoReportEventEntity> {
    IPage<MesAutoReportEventEntity> queryPage(Page<MesAutoReportEventEntity> page, MesAutoReportEventEntity query);

    MesAutoReportResultDTO accept(MesAutoReportEventDTO command);

    MesAutoReportResultDTO receipt(MesAutoReportEventDTO command);

    MesAutoReportResultDTO retry(MesAutoReportRetryDTO command);
}
