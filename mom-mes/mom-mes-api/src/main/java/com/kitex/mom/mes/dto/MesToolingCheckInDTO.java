package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MesToolingCheckInDTO {
    private Long arrangeId;
    private List<String> toolingCodes = new ArrayList<>();
}
