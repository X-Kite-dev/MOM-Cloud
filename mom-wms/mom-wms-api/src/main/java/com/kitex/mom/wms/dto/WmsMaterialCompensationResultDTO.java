package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class WmsMaterialCompensationResultDTO {
    private String requestId;
    private String action;
    private Integer replayedBillCount;
    private Integer replayedDetailCount;
    private List<String> replayedBillNos = new ArrayList<>();
}
