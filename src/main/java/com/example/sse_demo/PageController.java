
package com.example.sse_demo;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller // 注意这里用@Controller而非@RestController，用于返回页面
public class PageController {
    
    /**
     * 访问根路径时，返回SSE客户端页面
     */
    @GetMapping("/")
    public String index() {
        // 返回src/main/resources/templates目录下的sse-client.html
        return "sse-client";
    }
}

