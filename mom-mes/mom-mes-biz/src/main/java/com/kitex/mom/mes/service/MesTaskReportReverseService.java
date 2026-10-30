package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.MesTaskReportReverseVO;

public interface MesTaskReportReverseService {

    void reverse(MesTaskReportReverseVO command);

    /**
     * 对新版数量/包装数量报工执行有精确来源的部分数量纠正。
     */
    void reversePartial(MesTaskReportReverseVO command, Long reverseRequestId, String evidenceFingerprint);
}
