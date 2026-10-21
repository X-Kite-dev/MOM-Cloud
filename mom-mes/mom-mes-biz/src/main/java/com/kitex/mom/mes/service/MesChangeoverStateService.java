package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesChangeoverStateDTO;
import com.kitex.mom.mes.entity.MesChangeoverExecutionEntity;

/** 换型窗口恢复的严格只读入口；不暴露通用增删改控制器。 */
public interface MesChangeoverStateService extends IService<MesChangeoverExecutionEntity> {
    /**
     * 读取同一组织下设备进行中账、指定原账及当前本地绑定。
     *
     * @param deviceCode 规范设备编码
     * @param worksiteCode 规范工位编码
     * @param executionId 可选精确执行ID文本
     * @param requestId 可选开始原号；与ID同时给出时必须指向同一账
     * @return 一致本地观察，未找到不构成远端未执行证明
     */
    MesChangeoverStateDTO observe(String deviceCode, String worksiteCode, String executionId, String requestId);
}
