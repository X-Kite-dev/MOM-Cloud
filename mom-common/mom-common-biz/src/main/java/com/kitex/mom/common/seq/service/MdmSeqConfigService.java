package com.kitex.mom.common.seq.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.common.seq.entity.MdmSeqConfigEntity;

public interface MdmSeqConfigService extends IService<MdmSeqConfigEntity> {

    Boolean saveSeqConfig(MdmSeqConfigEntity mdmSeqConfig);

}
