package com.example.myspringaiunittest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry // 請幫我掃描並啟用所有 @Retryable 和 @Recover 的功能
@SpringBootApplication
public class MySpringAiUnitTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(MySpringAiUnitTestApplication.class, args);
    }

}
