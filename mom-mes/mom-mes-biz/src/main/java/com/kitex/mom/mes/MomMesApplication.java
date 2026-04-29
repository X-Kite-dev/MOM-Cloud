package com.kitex.mom.mes;

import com.kitex.common.feign.annotation.EnableMomFeignClients;
import com.kitex.common.security.annotation.EnableMomResourceServer;
import com.kitex.common.swagger.annotation.EnableOpenApi;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 项目启动类
 */
@EnableOpenApi("mom-mes")
@EnableMomFeignClients
@EnableDiscoveryClient
@EnableMomResourceServer
@SpringBootApplication
@MapperScan("com.kitex.mom.mes.mapper")
public class MomMesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MomMesApplication.class, args);
    }

}
