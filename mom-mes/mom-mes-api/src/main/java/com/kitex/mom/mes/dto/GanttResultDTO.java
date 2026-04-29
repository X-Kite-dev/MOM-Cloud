package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 甘特图
 *
 * @author kitex
 * @since 2025-05-
 */
@Data
public class GanttResultDTO {

    private Date ganttStart;

    private Date ganttEnd;

    private List<GanttListDTO> taskList;
}
