package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskDetailEntity;
import com.kitex.mom.qms.mapper.QmsInspectionProductTaskDetailMapper;
import com.kitex.mom.qms.service.QmsInspectionProductTaskDetailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QmsInspectionProductTaskDetailServiceImpl extends ServiceImpl<QmsInspectionProductTaskDetailMapper, QmsInspectionProductTaskDetailEntity> implements QmsInspectionProductTaskDetailService {
}
