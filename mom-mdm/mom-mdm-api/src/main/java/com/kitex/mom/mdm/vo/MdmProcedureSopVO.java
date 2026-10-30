package com.kitex.mom.mdm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 工序-SOP VO
 *
 * @author kitex
 * @date 2025-03-18 09:44:18
 */
@Data
public class MdmProcedureSopVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 读取时附件业务属性指纹；保存时必须带回原集合，而非修改后的集合。
     */
    @Schema(description = "附件并发指纹")
    private String attachmentHash;

    /**
     * SOP所属路线节点，不能用主工序编码代替。
     */
    @Schema(description = "工艺路线节点编号")
    private String routeNodeCode;

    /**
     * 原始文件名
     */
    @Schema(description = "原始文件名")
    private String name;

    /**
     * 文件名
     */
    @Schema(description = "文件名")
    private String fileName;

    /**
     * url
     */
    @Schema(description = "url")
    private String url;

    /**
     * fileUrl
     */
    @Schema(description = "fileUrl")
    private String fileUrl;

}
