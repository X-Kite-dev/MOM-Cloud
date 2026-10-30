package com.kitex.mom.mdm.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class MdmToolingAvailabilityQueryDTO {
    private List<String> routeCodes = new ArrayList<>();
    /**
     * 精确节点查询；空时仅按路线内唯一主工序桥接旧请求。
     */
    private List<String> routeNodeCodes = new ArrayList<>();
    private List<String> processCodes = new ArrayList<>();
    private List<String> toolingCodes = new ArrayList<>();
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
