package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesTaskInWmsReceiptDetailEntity;

import java.util.List;

/**
 * 成品入库到末工序报工来源的净额账服务。
 */
public interface MesInboundAllocationService extends IService<MesTaskInWmsReceiptDetailEntity> {
    /**
     * 查询尚未被成品实物红冲抵销的来源分配；锁定模式用于写事务和冲销审批。
     */
    List<MesTaskInWmsReceiptDetailEntity> activeByTask(String taskCode, String factoryCode, boolean lock);
}
