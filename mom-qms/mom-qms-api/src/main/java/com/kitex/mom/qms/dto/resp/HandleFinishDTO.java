package com.kitex.mom.qms.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class HandleFinishDTO {

    private Long id;

    private Integer handleNum;

    private String handleResult;

    private String handleRemark;

}
