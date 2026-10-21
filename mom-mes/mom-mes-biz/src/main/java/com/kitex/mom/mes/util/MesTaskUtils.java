package com.kitex.mom.mes.util;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;

import java.util.List;
import java.util.Objects;

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
        return getCurrentProcess(snapshot, routeCode, null);
    }

    /** 原冻结节点与主工序须同时匹配；带节点失败时不能降级到同码工序。 */
    public static ProcessProcedureDTO getCurrentProcess(String snapshot, String routeCode, String sourceRouteNodeCode) {
        if (StrUtil.isBlank(snapshot) || StrUtil.isBlank(routeCode)) {
            throw new CheckedException("冻结工艺快照或实际主工序缺失，不能猜测执行配置");
        }
        List<ProcessProcedureDTO> processList = JSONUtil.toList(snapshot, ProcessProcedureDTO.class);
        List<ProcessProcedureDTO> matches = processList.stream()
                .filter(Objects::nonNull)
                .filter(process -> sourceRouteNodeCode == null
                        ? Objects.equals(StrUtil.blankToDefault(process.getProcessCode(), process.getRouteCode()), routeCode)
                        : Objects.equals(process.getRouteNodeCode(), sourceRouteNodeCode))
                .toList();
        if (matches.size() != 1) {
            throw new CheckedException("工艺快照中缺少唯一对应节点/主工序，不能猜测执行配置："
                    + Objects.toString(sourceRouteNodeCode, "历史无节点") + "/" + routeCode);
        }
        if (!Objects.equals(StrUtil.blankToDefault(matches.get(0).getProcessCode(), matches.get(0).getRouteCode()), routeCode)) {
            throw new CheckedException("原冻结节点与实际任务主工序不一致：" + sourceRouteNodeCode + "/" + routeCode);
        }
        return matches.get(0);
    }

}
