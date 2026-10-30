package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 领域回归保留真实身份校验，仅在既有远程边界提供已授权测试回执。 */
final class MesFactoryAccessTestSupport {

    private MesFactoryAccessTestSupport() {
    }

    static MesActualFactoryAccessService authorized() {
        var factories = mock(RemoteMdmFactoryAccessService.class);
        when(factories.check(any())).thenReturn(R.ok(true));
        return new MesActualFactoryAccessService(factories);
    }
}
