package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EquipmentOverviewDto {


    @Schema(description = "设备总数")
    private Integer total;

    @Schema(description = "暂停中")
    private EquipmentDataInfoDto pause;

    @Schema(description = "维修中")
    private EquipmentDataInfoDto repairing;

    @Schema(description = "保养中")
    private EquipmentDataInfoDto maintenance;

    @Schema(description = "空闲")
    private EquipmentDataInfoDto idle;

    @Schema(description = "运行中")
    private EquipmentDataInfoDto running;

    @Schema(description = "已报废")
    private EquipmentDataInfoDto decommissioned;

    public static EquipmentOverviewDto.EquipmentDataInfoDto getEquipmentDataInfoDto(Integer count, BigDecimal percentage) {
        EquipmentOverviewDto.EquipmentDataInfoDto equipmentDataInfoDto = new EquipmentOverviewDto.EquipmentDataInfoDto();
        equipmentDataInfoDto.setCount(count);
        equipmentDataInfoDto.setPercentage(percentage);
        return equipmentDataInfoDto;
    }

    @Data
    private static class EquipmentDataInfoDto {

        @Schema(description = "数量")
        private Integer count;

        @Schema(description = "百分比")
        private BigDecimal percentage;
    }


}
