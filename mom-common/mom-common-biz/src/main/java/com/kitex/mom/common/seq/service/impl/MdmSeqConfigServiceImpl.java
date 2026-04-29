package com.kitex.mom.common.seq.service.impl;

import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.seq.entity.MdmSeqConfigEntity;
import com.kitex.mom.common.seq.mapper.MdmSeqConfigMapper;
import com.kitex.mom.common.seq.service.MdmSeqConfigService;
import org.springframework.stereotype.Service;

@Service
public class MdmSeqConfigServiceImpl extends ServiceImpl<MdmSeqConfigMapper, MdmSeqConfigEntity> implements MdmSeqConfigService {

    @Override
    public Boolean saveSeqConfig(MdmSeqConfigEntity seqConfig) {
        // 1. 判断是否存在重复业务号
        boolean exists = this.baseMapper
                .exists(Wrappers.<MdmSeqConfigEntity>lambdaQuery().eq(MdmSeqConfigEntity::getBizName, seqConfig.getBizName())
                        .ne(ObjUtil.isNotNull(seqConfig.getId()), MdmSeqConfigEntity::getId, seqConfig.getId()));
        if (exists) {
            throw new CheckedException(String.format("存在业务编码【%s】的序列配置", seqConfig.getBizName()));
        }
        return this.saveOrUpdate(seqConfig);
    }

}
