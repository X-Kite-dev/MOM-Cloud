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
        return getCurrentProcess(task.getProcessRouteSnapshot(), routeCode);
    }

    public static ProcessProcedureDTO getCurrentProcess(String snapshot, String routeCode) {
        List<ProcessProcedureDTO> processList = JSONUtil.toList(snapshot, ProcessProcedureDTO.class);
        List<ProcessProcedureDTO> matches = processList.stream()
                .filter(process -> java.util.Objects.equals(cn.hutool.core.util.StrUtil.blankToDefault(process.getProcessCode(), process.getRouteCode()), routeCode))
                .toList();
        if (matches.size() != 1)
            throw new com.kitex.common.core.exception.CheckedException("工艺快照中缺少唯一对应工序，不能猜测物料消耗：" + routeCode);
        return matches.get(0);
    }

}
