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
     * 移动端扫码过站报工；服务端按移动端入口校验执行模板。
     */
    void reportMobile(MesTaskReportVO params);

    /**
     * 受控系统集成报工；调用方必须具备独立接口权限。
     */
    void reportIntegration(MesTaskReportVO params);

    /**
     * 仅供受控设备事件处理器调用，不能由普通前端伪造AUTO类型。
     */
    void reportAuto(MesTaskReportVO params);

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
