package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskDetailEntity;
import com.kitex.mom.qms.mapper.QmsInspectionPurchaseTaskDetailMapper;
import com.kitex.mom.qms.service.QmsInspectionPurchaseTaskDetailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QmsInspectionPurchaseTaskDetailServiceImpl extends ServiceImpl<QmsInspectionPurchaseTaskDetailMapper, QmsInspectionPurchaseTaskDetailEntity> implements QmsInspectionPurchaseTaskDetailService {
}
