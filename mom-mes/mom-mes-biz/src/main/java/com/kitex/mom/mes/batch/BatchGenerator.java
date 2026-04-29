package com.kitex.mom.mes.batch;

public interface BatchGenerator {

    /**
     * 生成批次码
     *
     * @param workOrder
     * @param shift
     * @return
     */
    String generateBatchNumber(String workOrder, String shift);

    /**
     * 获取生成器类型名称
     *
     * @return 生成器类型名称
     */
    String getGeneratorType();

    /**
     * 获取生成器显示名称
     *
     * @return 生成器显示名称
     */
    String getGeneratorName();
}
