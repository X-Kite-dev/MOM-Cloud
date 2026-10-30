package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesMobileExecutionDTO;
import com.kitex.mom.mes.entity.MesTerminalExecutionCommandEntity;

/**
 * 移动派工持久服务；不开放永久账的通用增删改接口。
 */
public interface MesTerminalExecutionService extends IService<MesTerminalExecutionCommandEntity> {
    /**
     * 获取当前实名、事实、原权限和跨端占用，不替代提交时领域门。
     */
    MesMobileExecutionDTO.View view(String arrangeId);

    /**
     * 登记完整原号，独立提交占用，沿原领域事务执行且永久冻结原结果。
     */
    MesMobileExecutionDTO.Receipt command(MesMobileExecutionDTO.Command command);

    /**
     * 只读查询当前实名拥有的原回执；不存在不证明原号未投递。
     */
    MesMobileExecutionDTO.Receipt receipt(String requestId);
}
