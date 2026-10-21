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
     * 报工主记录已保存并回读核验后，建立该模式的关联事实。
     *
     * <p>包装明细等引用 reportId 的记录必须在此阶段落库，不能只凭预分配主键
     * 引用尚不存在的报告。实现须加入外层报工事务；失败时报告及全部关联一起回滚。</p>
     *
     * @param params 已绑定真实报工主键的参数
     * @param result 当前模式结果，可补充装箱履历说明
     */
    default void persistAssociations(MesTaskReportVO params, MesTaskReportResult result) {
        // 数量、单件码和流转卡模式没有额外包装关联。
    }

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
