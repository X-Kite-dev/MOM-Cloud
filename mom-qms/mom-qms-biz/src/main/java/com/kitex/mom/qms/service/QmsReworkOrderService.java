package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;

public interface QmsReworkOrderService extends IService<QmsReworkOrderEntity> {
    QmsReworkOrderEntity create(String inspectionType, Long handleRecordId);

    QmsReworkOrderEntity createManual(String inspectionType, Long handleRecordId);

    QmsReworkOrderEntity retryManual(Long id);

    QmsReworkOrderEntity retry(Long id);

    Long completeFromMes(QmsReworkCompletionDTO command);

    void retryResultSync(Long id);
}
