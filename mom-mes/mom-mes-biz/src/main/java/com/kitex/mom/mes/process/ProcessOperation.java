package com.kitex.mom.mes.process;

import com.kitex.mom.mes.dto.MesTaskReportResult;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.enums.ReportModeEnum;

/**
 * 工序报工
 *
 * @author kitex
 * @since 2025-03-25
 */
public interface ProcessOperation {

    /**
     * 执行报工操作
     *
     * @param params 报工参数
     * @return 报工结果
     */
    MesTaskReportResult execute(MesTaskReportVO params);

    /**
     * 物料消耗
     */
    void use(MesTaskReportVO params);


    /**
     * 线边入库
     */
    void in(MesTaskReportVO params);

    /**
     * 获取报工方式编码
     *
     * @return 报工方式编码
     */
    ReportModeEnum getMode();

    default String getCode() {
        return getMode().getCode();
    }


    /**
     * 报工描述
     */
    default String getDescription() {
        return "报工描述";
    }

}
