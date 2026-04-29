package com.kitex.mom.mes.process;

import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesTaskReportEntity;

import java.util.Map;

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
    Map<String, Object> execute(MesTaskReportVO params);

    /**
     * 通用报工
     */
    MesTaskReportEntity report(MesTaskReportVO params);

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
    String getCode();


    /**
     * 报工描述
     */
    default String getDescription() {
        return "报工描述";
    }

    ;
}
