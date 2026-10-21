package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesWarehouseFeedCommandEntity;

/**
 * 稳定投料原号只读回执；ABSENT不是可另号执行或删除pending的永久证明。
 */
public final class MesWarehouseFeedCommandDTO {
    private MesWarehouseFeedCommandDTO() {
    }

    /**
     * 实际服务端身份，用于客户端完整原文的期望比对。
     */
    public record Context(String tenantId, String factoryCode, String actorUserId, String commandVersion) {
    }

    /**
     * 成功含原命令和完整出生；作废不含实物，缺号不虚构原正文。
     */
    public record Receipt(String status, String requestId, String tenantId, String factoryCode,
                          String actorUserId, MesWarehouseFeedCommandEntity command,
                          MesWarehouseFeedBirthDTO birth) {
    }
}
