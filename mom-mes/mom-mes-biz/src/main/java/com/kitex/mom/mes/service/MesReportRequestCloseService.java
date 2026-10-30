package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesReportRequestCloseEntity;

/**
 * 原号封存领域服务，不提供可编辑封存账或强制冲销的CRUD入口。
 */
public interface MesReportRequestCloseService extends IService<MesReportRequestCloseEntity> {

    /**
     * 当前实际租户工厂下查询本人永久封存证据，缺失返回null。
     */
    MesReportRequestCloseEntity findOwn(String requestId);

    /**
     * 同原请求锁及事务内封存尚未成功的完整原命令，重试必须正文及原因相同。
     */
    MesReportRequestCloseEntity seal(MesReportCommandService.CapturedCommand captured, String reason);
}
