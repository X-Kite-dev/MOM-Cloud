package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesTimingExceptionDetailDTO;
import com.kitex.mom.mes.entity.MesTimingExceptionEntity;

/**
 * 独立超时例外领域服务；观察只由服务器真实报工产生，不提供任意新增/编辑/删除。
 */
public interface MesTimingExceptionService extends IService<MesTimingExceptionEntity> {
    /** 在当前授权工厂按状态、工单及完整报工请求号查询超时观察，筛选项可省略。 */
    IPage<MesTimingExceptionEntity> pageCases(long current, long size, String status, String taskCode,
                                             String reportRequestId);

    MesTimingExceptionDetailDTO detail(Long id);

    MesTimingExceptionDetailDTO operation(String requestId);

    MesTimingExceptionDetailDTO action(String raw);
}

