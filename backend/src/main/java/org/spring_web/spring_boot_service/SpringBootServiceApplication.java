package org.spring_web.spring_boot_service;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 课程设计智能答辩与评分系统 - 后端启动类。
 */

/**
 cd langchain_service
 .\.venv\Scripts\python.exe main.py
 npm run dev -- --port 3000
 */
@SpringBootApplication
@MapperScan("org.spring_web.spring_boot_service.mapper")
public class SpringBootServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootServiceApplication.class, args);
    }

}
