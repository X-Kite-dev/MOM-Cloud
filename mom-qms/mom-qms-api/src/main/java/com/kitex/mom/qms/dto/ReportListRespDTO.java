package com.kitex.mom.qms.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 报表项列表
 *
 * @author kyer
 * @date 2023/03/10 10:46
 **/
@Data
public class ReportListRespDTO {

    /**
     * 数据
     */
    List<Map<String, Object>> records = Collections.emptyList();

    /**
     * 查询之后的动态列
     */
    List<String> tableColumns = Collections.emptyList();

}
