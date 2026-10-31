package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesMaterialReturnOperationEntity;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;

/** 本人退料审核/撤回的认证上下文及永久操作观察；不作为命令指定组织的入口。 */
public final class MesMaterialReturnActionDTO {
    private MesMaterialReturnActionDTO() { }

    public record Context(String version, String kind, String tenantId, String factoryCode,
                          String actorUserId, String actorCode) { }

    /** 缺失操作仅是当前观察，不能证明原命令或WMS动作已终止。 */
    public record Receipt(String version, Context context, String requestId,
                          MesMaterialReturnOperationEntity operation,
                          MesWarehouseStockFeedJudgeEntity request) { }
}
