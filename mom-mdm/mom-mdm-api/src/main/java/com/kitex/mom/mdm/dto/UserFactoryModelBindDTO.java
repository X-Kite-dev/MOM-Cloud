package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 用户工厂建模权限绑定DTO
 *
 * @author claude
 * @date 2025-07-30
 */
@Data
@Schema(description = "用户工厂建模权限绑定DTO")
public class UserFactoryModelBindDTO {

    /**
     * 用户列表
     */
    @Schema(description = "用户列表")
    private List<UserDTO> users;
    /**
     * 权限绑定列表
     */
    @Schema(description = "权限绑定列表")
    private List<FactoryModelDTO> factoryModels;

    @Data
    @Schema(description = "工厂建模权限DTO")
    public static class UserDTO {

        /**
         * 用户ID
         */
        @Schema(description = "用户ID")
        private Long userId;

        /**
         * 用户名
         */
        @Schema(description = "用户名")
        private String username;

        /**
         * 用户姓名
         */
        @Schema(description = "用户姓名")
        private String name;
    }

    @Data
    @Schema(description = "工厂建模权限DTO")
    public static class FactoryModelDTO {

        /**
         * 工厂建模节点ID
         */
        @Schema(description = "工厂建模节点ID")
        private Long id;

        /**
         * 工厂建模节点编码
         */
        @Schema(description = "工厂建模节点编码")
        private String code;

        /**
         * 工厂建模节点名称
         */
        @Schema(description = "工厂建模节点名称")
        private String name;

        /**
         * 节点类型
         */
        @Schema(description = "节点类型")
        private String type;

        /**
         * 节点级别
         */
        @Schema(description = "节点级别")
        private Integer level;

        /**
         * 权限类型
         */
        @Schema(description = "权限类型")
        private String permissionType;

        /**
         * 是否包含子节点权限
         */
        @Schema(description = "是否包含子节点权限")
        private Boolean includeChildren;
    }
}