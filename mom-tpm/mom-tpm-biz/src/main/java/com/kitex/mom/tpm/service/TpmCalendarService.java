package com.kitex.mom.tpm.service;

import com.kitex.mom.tpm.dto.TpmCalendarDto;
import com.kitex.mom.tpm.entity.TpmCheckTaskEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;

import java.util.List;

public interface TpmCalendarService {

    TpmCalendarDto getCalendarMsg(String date);

    List<TpmMaintenanceTaskEntity> getMaintenanceTaskByDay(String date);

    List<TpmRepairTaskEntity> getRepairTaskByDay(String date);

    List<TpmCheckTaskEntity> getCheckTaskByDay(String date, String type);
}
