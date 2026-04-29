package com.kitex.mom.qms.dto.req;

import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 质检标准
 *
 * @author kyer
 * @date 2023/04/24 10:53
 **/
@Data
public class QualityStandardReq {

    @Schema(description = "质检标准")
    private QmsQualityStandardEntity standard;

    @Schema(description = "质检项")
    private List<QmsQualityStandardItemEntity> items;

}
