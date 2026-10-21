package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentLineEntity;
import com.kitex.mom.plan.mapper.PlnMpsRemainingCommitmentLineMapper;
import com.kitex.mom.plan.service.PlnMpsRemainingCommitmentLineService;
import org.springframework.stereotype.Service;

/** 账存储实现；不可变约束由领域命令与数据库双层保护，不注册通用写API。 */
@Service
public class PlnMpsRemainingCommitmentLineServiceImpl extends ServiceImpl<PlnMpsRemainingCommitmentLineMapper, PlnMpsRemainingCommitmentLineEntity>
        implements PlnMpsRemainingCommitmentLineService {
}

