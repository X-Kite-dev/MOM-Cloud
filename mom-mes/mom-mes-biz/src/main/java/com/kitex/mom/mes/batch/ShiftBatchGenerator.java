package com.kitex.mom.mes.batch;

import org.springframework.stereotype.Component;

@Component
public class ShiftBatchGenerator implements BatchGenerator {

    @Override
    public String generateBatchNumber(String workOrder, String shift) {
        return shift;
    }

    @Override
    public String getGeneratorType() {
        return "SHIFT";
    }

    @Override
    public String getGeneratorName() {
        return "班次";
    }
}
