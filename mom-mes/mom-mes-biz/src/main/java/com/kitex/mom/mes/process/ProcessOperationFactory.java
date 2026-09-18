package com.kitex.mom.mes.process;

import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesTaskReportResult;
import com.kitex.mom.mes.enums.ReportModeEnum;
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

    private final Map<ReportModeEnum, ProcessOperation> operationMap = new ConcurrentHashMap<>();

    @Autowired
    public ProcessOperationFactory(List<ProcessOperation> operations) {
        for (ProcessOperation operation : operations) {
            ProcessOperation previous = operationMap.put(operation.getMode(), operation);
            if (previous != null) {
                throw new IllegalStateException("报工模式存在重复实现: " + operation.getCode());
            }
        }
    }

    /**
     * 根据报工方式编码获取对应的报工实现
     *
     * @param code 报工方式编码
     * @return 报工实现
     */
    public ProcessOperation getOperation(String code) {
        ReportModeEnum mode = ReportModeEnum.fromCode(code);
        ProcessOperation operation = operationMap.get(mode);
        if (operation == null) {
            throw new IllegalArgumentException("报工模式尚未配置执行器: " + mode.getCode());
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
    public MesTaskReportResult execute(String code, MesTaskReportVO params) {
        ProcessOperation operation = getOperation(code);
        MesTaskReportResult result = operation.execute(params);
        if (result == null || result.getReport() == null) {
            throw new IllegalStateException("报工执行器未返回报工记录: " + operation.getCode());
        }
        params.setReportId(result.getReport().getId());

        return result;
    }

    /**
     * 报工主记录持久化后执行公共物料与产出事务，确保所有流水都能关联 reportId。
     */
    public void applyInventory(String code, MesTaskReportVO params) {
        ProcessOperation operation = getOperation(code);
        operation.use(params);
        operation.in(params);
    }
}
