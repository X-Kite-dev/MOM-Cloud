package com.kitex.mom.mes.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class MdmBomChildListDTO extends MdmBomChildDTO {

    private List<MdmBomChildListDTO> children;

}
