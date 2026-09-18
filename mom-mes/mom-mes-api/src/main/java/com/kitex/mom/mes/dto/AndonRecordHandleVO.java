package com.kitex.mom.mes.dto;

import lombok.Data;

/**
 * 处理
 */
@Data
public class AndonRecordHandleVO {

    private Long id;

    private String requestId;

    private String rootCause;

    private String correctiveAction;

    private String result;
}
