package com.kitex.mom.pms.vo;

import com.kitex.mom.pms.entity.PmsSupplierFileEntity;
import com.kitex.mom.pms.entity.PmsSupplierMatEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 供应商-供货VO
 *
 * @author kitex
 * @date 2025-03-20 09:33:46
 */
@Data
@Schema(description = "供应商-供货VO")
public class PmsSupplierDeliverVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 供应商id
     */
    @Schema(description = "供应商id")
    private Long id;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 供应商分类编码
     */
    @Schema(description = "供应商分类编码")
    private String supplierClassCode;

    /**
     * 供应商分类名称
     */
    @Schema(description = "供应商分类名称")
    private String supplierClassName;

    /**
     * 供应商类型
     */
    @Schema(description = "供应商类型")
    private String supplierType;

    /**
     * 简称
     */
    @Schema(description = "简称")
    private String shortName;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;

    /**
     * 创建人
     */
    @Schema(description = "创建人")
    private String createBy;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 供应商-供货物料列表
     */
    @Schema(description = "供应商-供货物料列表")
    private List<PmsSupplierMatEntity> matList;

    /**
     * 供应商-供货附件列表
     */
    @Schema(description = "供应商-供货附件列表")
    private List<PmsSupplierFileEntity> fileList;

}