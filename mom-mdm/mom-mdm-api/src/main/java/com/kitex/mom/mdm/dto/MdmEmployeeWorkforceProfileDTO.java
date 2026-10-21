package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmEmployeeCalendarExceptionEntity;
import com.kitex.mom.mdm.entity.MdmEmployeeShiftPatternEntity;
import com.kitex.mom.mdm.entity.MdmEmployeeSkillEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MdmEmployeeWorkforceProfileDTO {
    private String employeeCode;
    private String employeeName;
    private List<MdmEmployeeSkillEntity> skills = new ArrayList<>();
    private List<MdmEmployeeShiftPatternEntity> shiftPatterns = new ArrayList<>();
    private List<MdmEmployeeCalendarExceptionEntity> calendarExceptions = new ArrayList<>();
}
