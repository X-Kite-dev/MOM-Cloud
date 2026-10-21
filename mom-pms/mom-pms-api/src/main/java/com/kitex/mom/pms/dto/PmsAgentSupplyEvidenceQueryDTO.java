package com.kitex.mom.pms.dto;

import lombok.Data;

import java.util.List;

@Data
public class PmsAgentSupplyEvidenceQueryDTO {
    private String factoryCode;
    private List<String> orderNos;
}
