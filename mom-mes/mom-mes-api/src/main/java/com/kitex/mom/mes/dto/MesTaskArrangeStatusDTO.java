package com.kitex.mom.mes.dto;

import lombok.Data;

/**
 * pad查询状态列表
 */
@Data
public class MesTaskArrangeStatusDTO {

    private String statusName;

    private Long num;

    public MesTaskArrangeStatusDTO(String statusName, Long num) {
        this.statusName = statusName;
        this.num = num;
    }

}
