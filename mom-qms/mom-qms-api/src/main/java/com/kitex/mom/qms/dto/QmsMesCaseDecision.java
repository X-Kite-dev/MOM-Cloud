package com.kitex.mom.qms.dto;

import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import lombok.Data;

@Data
public class QmsMesCaseDecision {
    private QmsBadInfoEntity ncr;
    private QmsBadInfoDetailEntity detail;
}
