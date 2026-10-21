package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.mdm.dto.MdmPlanResourceEventDTO;
import com.kitex.mom.mdm.entity.*;

import java.util.List;

public interface MdmPlanResourceEventOutboxService {
    MdmPlanResourceEventOutboxEntity enqueueDeviceRecovery(MdmDeviceRecoveryReceipt receipt, MdmDeviceEntity device,
                                                           Long taskId, String taskCode);

    void synchronizeToolingWindows(MdmToolingEntity tooling, List<MdmToolingUnavailableWindowEntity> windows);

    void synchronizeToolingMasterStatus(MdmToolingEntity tooling);

    void synchronizeEmployeeCalendar(MdmEmployeeEntity employee,
                                     List<MdmEmployeeCalendarExceptionEntity> exceptions);

    void synchronizeEmployeeQualifications(MdmEmployeeEntity employee,
                                           List<MdmEmployeeSkillEntity> qualifications);

    Page<MdmPlanResourceEventOutboxEntity> page(SearchPage page, MdmPlanResourceEventDTO.Query query);

    MdmPlanResourceEventDTO.Detail detail(Long outboxId);

    boolean retry(MdmPlanResourceEventDTO.ManualActionCommand command);

    int dispatchDue(int limit);
}
