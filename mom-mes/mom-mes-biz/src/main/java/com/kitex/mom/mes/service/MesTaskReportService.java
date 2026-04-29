package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesTaskReportFeedVO;
import com.kitex.mom.mes.dto.MesTaskReportUnqualifiedVO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesTaskReportEntity;

/**
 * 生产报工
 *
 * @author kitex
 * @since 2025-05-06
 */
public interface MesTaskReportService extends IService<MesTaskReportEntity> {

    /**
     * 生产报工
     *
     * @param params
     */
    void report(MesTaskReportVO params);

    /**
     * 生产报工-不合格品填报
     *
     * @param params
     */
    void unqualified(MesTaskReportUnqualifiedVO params);

    /**
     * 生产报工-投料
     *
     * @param params
     */
    void feed(MesTaskReportFeedVO params);

}
