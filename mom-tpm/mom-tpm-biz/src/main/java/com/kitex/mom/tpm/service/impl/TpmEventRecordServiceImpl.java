package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmEventRecordEntity;
import com.kitex.mom.tpm.mapper.TpmEventRecordMapper;
import com.kitex.mom.tpm.service.TpmEventRecordService;
import org.springframework.stereotype.Service;

/**
 * TPM设备事件记录表
 *
 * @author kitex
 * @date 2025-03-18 10:07:01
 */
@Service
public class TpmEventRecordServiceImpl extends ServiceImpl<TpmEventRecordMapper, TpmEventRecordEntity> implements TpmEventRecordService {
}