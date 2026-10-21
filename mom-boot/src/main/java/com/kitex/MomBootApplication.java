package com.kitex;

import com.kitex.common.security.annotation.EnableMomResourceServer;
import com.kitex.common.swagger.annotation.EnableOpenApi;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 单体版本启动器，只需要运行此模块则整个系统启动
 *
 * @author kitex
 */
@EnableOpenApi(value = "admin", isMicro = false)
@EnableMomResourceServer
@SpringBootApplication
@MapperScan({"com.kitex.**.mapper"})
public class MomBootApplication {


    public static void main(String[] args) {
        SpringApplication.run(MomBootApplication.class, args);
    }

}
