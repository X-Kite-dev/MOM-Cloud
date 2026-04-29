package com.kitex.mom.qms.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 质检状态列表
 */
@Data
@AllArgsConstructor
public class QmsInspectionStatusVO {

    private String code;

    private String name;

    private Integer num;
}
