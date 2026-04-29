package com.kitex.mom.mes.util;

import cn.hutool.json.JSONUtil;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;

import java.util.List;

public class MesTaskUtils {


    /**
     * 获取当前任务的工艺路线
     */
    public static List<ProcessProcedureDTO> getCurrentRouteCode(MesTaskEntity task) {
        List<ProcessProcedureDTO> processList = JSONUtil.toList(task.getProcessRouteSnapshot(), ProcessProcedureDTO.class);
        return processList;
    }

    /**
     * 获取任务对应工序信息
     */
    public static ProcessProcedureDTO getCurrentProcess(MesTaskEntity task, String routeCode) {
        List<ProcessProcedureDTO> processList = getCurrentRouteCode(task);
        ProcessProcedureDTO progress = processList.stream()
                .filter(process -> process.getRouteCode().equals(routeCode))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("未找到对应的工序信息"));

        return progress;
    }

}
