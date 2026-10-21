package com.kitex.mom.mes.dto;

import lombok.Data;

/**
 * 工单内冻结的文件引用契约；不依赖MDM实体，内容字节版本另行治理。
 */
@Data
public class MesSopReferenceDTO {
    private Long id;
    private String name;
    private String fileName;
    private String url;
}
