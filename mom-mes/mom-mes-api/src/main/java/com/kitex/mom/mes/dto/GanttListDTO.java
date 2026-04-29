package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import lombok.Data;

import java.util.List;

@Data
public class GanttListDTO {

    private Long id;

    private String title;

    private List<MesProduceTaskEntity> children;
}
