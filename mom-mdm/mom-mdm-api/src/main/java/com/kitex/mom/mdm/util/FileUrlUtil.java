package com.kitex.mom.mdm.util;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文件URL拼接工具类
 * 用于将数据库中的相对路径转换为完整的HTTP访问URL
 *
 * @author kitex
 * @date 2025-01-24
 */
@Slf4j
@Component
public class FileUrlUtil {

    /**
     * 文件服务基础URL，从配置文件读取
     */
    @Value("${file.service.base-url:http://localhost:9997/api}")
    private String fileServiceBaseUrl;

    /**
     * 将相对路径转换为完整的HTTP访问URL
     *
     * @param filePath 数据库中的文件路径（如：admin/sys-file/oss/file?fileName=xxx.png）
     * @return 完整的HTTP访问URL
     */
    public String buildFileUrl(String filePath) {
        if (StrUtil.isEmpty(filePath)) {
            return null;
        }

        // 如果已经是完整的URL，直接返回
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            return filePath;
        }

        // 确保filePath以/开头
        if (!filePath.startsWith("/")) {
            filePath = "/" + filePath;
        }

        // 拼接完整的URL
        String fullUrl = fileServiceBaseUrl + filePath;

        log.debug("文件URL拼接: {} -> {}", filePath, fullUrl);
        return fullUrl;
    }

    /**
     * 批量处理文件URL
     *
     * @param filePaths 文件路径数组
     * @return 处理后的URL数组
     */
    public String[] buildFileUrls(String[] filePaths) {
        if (filePaths == null || filePaths.length == 0) {
            return filePaths;
        }

        String[] result = new String[filePaths.length];
        for (int i = 0; i < filePaths.length; i++) {
            result[i] = buildFileUrl(filePaths[i]);
        }
        return result;
    }

    /**
     * 检查文件路径是否为有效的文件路径
     *
     * @param filePath 文件路径
     * @return 是否为有效路径
     */
    public boolean isValidFilePath(String filePath) {
        return StrUtil.isNotEmpty(filePath) &&
                (filePath.contains("fileName=") || filePath.startsWith("/"));
    }
}
