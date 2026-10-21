package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.List;

/**
 * 指定执行人对原冻结完整SOP清单逐项签收，原请求号用于未知结果恢复。
 */
@Data
public class MesSopAcknowledgeCommand {
    /**
     * 永久请求号，不可换载荷覆盖原签收。
     */
    private String requestId;
    /**
     * 页面读取的原清单指纹，正式版同时绑定原件字节和批准版。
     */
    private String expectedFingerprint;
    /**
     * 已逐项查阅的完整文件key集合，不允许漏项或重复替代。
     */
    private List<String> documentKeys;
    /**
     * 执行人签收说明，不能由模型自动代签。
     */
    private String reason;
}
