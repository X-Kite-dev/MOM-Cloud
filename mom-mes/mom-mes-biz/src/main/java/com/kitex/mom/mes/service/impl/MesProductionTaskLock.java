package com.kitex.mom.mes.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台生产写锁：实际组织→同V1根互斥→准入冻结；不伪造登录身份。
 */
@Service
@RequiredArgsConstructor
public class MesProductionTaskLock {
    private final MesProductionTaskMutex mutex;
    private final MesInspectionRevisionHold holds;

    static String key(Long tenant, String factory, String code) {
        return MesProductionTaskMutex.key(tenant, factory, code);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lock(String code) {
        mutex.lock(code);
        holds.requireOpen(code);
    }
}
