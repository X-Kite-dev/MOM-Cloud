package com.kitex.mom.mes.process;

import com.kitex.mom.mes.dto.MesTaskReportVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工序报工工厂类
 *
 * @author kitex
 * @since 2025-03-25
 */
@Component
public class ProcessOperationFactory {

    private final Map<String, ProcessOperation> operationMap = new ConcurrentHashMap<>();

    @Autowired
    public ProcessOperationFactory(List<ProcessOperation> operations) {
        for (ProcessOperation operation : operations) {
            operationMap.put(operation.getCode(), operation);
        }
    }

    /**
     * 根据报工方式编码获取对应的报工实现
     *
     * @param code 报工方式编码
     * @return 报工实现
     */
    public ProcessOperation getOperation(String code) {
        ProcessOperation operation = operationMap.get(code);
        if (operation == null) {
            throw new IllegalArgumentException("不支持的报工方式: " + code);
        }
        return operation;
    }

    /**
     * 执行报工操作
     *
     * @param code   报工方式编码
     * @param params 报工参数
     * @return 报工结果
     */
    public Map<String, Object> execute(String code, MesTaskReportVO params) {
        return getOperation(code).execute(params);
    }
} 