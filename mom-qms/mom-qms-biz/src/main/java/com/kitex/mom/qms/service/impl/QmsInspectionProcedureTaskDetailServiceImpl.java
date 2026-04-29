package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskDetailEntity;
import com.kitex.mom.qms.mapper.QmsInspectionProcedureTaskDetailMapper;
import com.kitex.mom.qms.service.QmsInspectionProcedureTaskDetailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QmsInspectionProcedureTaskDetailServiceImpl extends ServiceImpl<QmsInspectionProcedureTaskDetailMapper, QmsInspectionProcedureTaskDetailEntity> implements QmsInspectionProcedureTaskDetailService {
}
