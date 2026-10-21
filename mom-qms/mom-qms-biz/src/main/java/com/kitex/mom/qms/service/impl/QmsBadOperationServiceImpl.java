package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.qms.entity.QmsBadOperationEntity;
import com.kitex.mom.qms.mapper.QmsBadOperationMapper;
import com.kitex.mom.qms.service.QmsBadOperationService;
import org.springframework.stereotype.Service;

@Service
public class QmsBadOperationServiceImpl extends ServiceImpl<QmsBadOperationMapper, QmsBadOperationEntity>
        implements QmsBadOperationService {
}
