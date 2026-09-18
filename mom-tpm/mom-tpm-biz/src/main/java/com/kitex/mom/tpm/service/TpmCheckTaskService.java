package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.dto.DeviceChangeMoldDto;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import com.kitex.mom.tpm.dto.TpmCheckTaskDTO;
import com.kitex.mom.tpm.entity.TpmCheckTaskEntity;

import java.util.List;
import java.util.Map;

public interface TpmCheckTaskService extends IService<TpmCheckTaskEntity> {

    TpmCheckTaskEntity getTaskById(Long id);

    Boolean handle(TpmCheckTaskEntity tpmCheckTask);

    Boolean releaseBlock(TpmCheckTaskDTO.ReleaseCommand command);

    Boolean addTask(String deviceCode);

    Boolean assign(List<Long> ids, String handlePersonCode, String handlePersonName);

    Map<String, Object> start(DeviceCheckTaskStartDto startDto);

    Boolean changeMold(DeviceChangeMoldDto moldDto);


}
