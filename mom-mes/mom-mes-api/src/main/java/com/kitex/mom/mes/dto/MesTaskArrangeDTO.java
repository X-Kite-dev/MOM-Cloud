package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import lombok.Data;

import java.util.List;

@Data
public class MesTaskArrangeDTO {

    private String code;

    private String shiftCode;

    private String shiftName;

    private List<MesTaskArrangeEntity> taskArrangeList;
}
