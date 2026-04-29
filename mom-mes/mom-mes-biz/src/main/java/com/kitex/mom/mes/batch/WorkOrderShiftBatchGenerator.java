package com.kitex.mom.mes.batch;

import org.springframework.stereotype.Component;

@Component
public class WorkOrderShiftBatchGenerator implements BatchGenerator {

    @Override
    public String generateBatchNumber(String workOrder, String shift) {
        return workOrder + "-" + shift;
    }

    @Override
    public String getGeneratorType() {
        return "WORK_ORDER_SHIFT";
    }

    @Override
    public String getGeneratorName() {
        return "工单+班次";
    }
}
