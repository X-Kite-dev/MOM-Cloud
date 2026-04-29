package com.kitex.mom.tpm.dto;

import com.kitex.mom.tpm.entity.TpmSparePartsInOrOutBillEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class TpmSparePartsInOrOutBillDto {

    @Schema(description = "操作类型 IN 入库 OUT 出库")
    private String operateType;

    @Schema(description = "使用途径")
    private String operateName;

    @Schema(description = "操作明细")
    private List<TpmSparePartsInOrOutBillEntity> detailsList;

}
