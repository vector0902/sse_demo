
package com.example.sse_demo;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
public class SseController {

    // 存储所有活跃的SSE连接（线程安全的列表）
    // CopyOnWriteArrayList适合读多写少场景，避免并发问题
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // 线程池：用于异步发送事件，避免阻塞主线程
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 客户端订阅SSE的接口
     * 客户端通过访问该接口建立长连接，接收服务器推送的事件
     */
    @GetMapping(value = "/sse/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        // 创建SseEmitter实例，设置超时时间为无限（默认30秒会超时，这里设为Long.MAX_VALUE避免自动断开）
        long timeout = Long.MAX_VALUE;
        // timeout=20; // test

        SseEmitter emitter = new SseEmitter(timeout);

        // 将新连接加入活跃列表（后续推送消息时会遍历这个列表）
        emitters.add(emitter);
        log.debug("add {}", emitter);

        // 设置连接完成/超时的回调：从活跃列表中移除该连接，释放资源
        emitter.onCompletion(() -> {
            log.debug("remove {} on complete", emitter);
            emitters.remove(emitter);
        }); // 连接正常关闭时
        emitter.onTimeout(() -> {
            log.debug("remove {} on timeout", emitter);
            emitters.remove(emitter);
        }); // 连接超时关闭时

        // 发送初始连接成功消息（给客户端的"欢迎消息"）
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED") // 事件名称：客户端可通过"CONNECTED"事件监听
                    .data("You are successfully connected to SSE server!") // 消息内容
                    .reconnectTime(3000)); // 告诉客户端：如果断开连接，x秒后重连
        } catch (IOException e) {
            // 发送失败时，标记连接异常结束
            emitter.completeWithError(e);
        }

        return emitter; // 将emitter返回给客户端，保持连接
    }

    /**
     * 广播消息接口：向所有已连接的客户端推送消息
     * 可通过浏览器访问 http://localhost:8080/sse/broadcast?message=xxx 触发
     */
    @GetMapping("/sse/broadcast")
    public String broadcastMessage(@RequestParam String message) {
        // 用线程池异步执行广播，避免阻塞当前请求
        executor.execute(() -> {
            // 遍历所有活跃连接，逐个发送消息
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("BROADCAST") // 事件名称：客户端监听"BROADCAST"事件
                            .data(message) // 广播的消息内容
                            .id(String.valueOf(System.currentTimeMillis()))); // 消息ID（用于重连时定位）
                } catch (IOException e) {
                    // 发送失败（可能客户端已断开），从列表中移除并标记连接结束
                    emitters.remove(emitter);
                    emitter.completeWithError(e);
                }
            }
        });

        return "Broadcast message: " + message; // 给调用者的响应
    }

    /**
     * 模拟长时间任务：向客户端推送实时进度
     * 适合文件上传、数据处理等需要实时反馈进度的场景
     */
    @GetMapping("/sse/start-task")
    public String startTask() {
        // 异步执行任务，避免阻塞当前请求
        executor.execute(() -> {
            try {
                // 模拟任务进度：从0%到100%，每次增加10%
                for (int i = 0; i <= 100; i += 10) {
                    Thread.sleep(1000); // 休眠1秒，模拟处理耗时

                    // 向所有客户端推送当前进度
                    for (SseEmitter emitter : emitters) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("PROGRESS") // 事件名称：客户端监听"PROGRESS"事件
                                    .data(i + "% completed") // 进度数据
                                    .id("task-progress")); // 固定ID，标识这是任务进度消息
                        } catch (IOException e) {
                            // 发送失败，移除连接
                            emitters.remove(emitter);
                        }
                    }

                    // 任务完成时，发送结束消息
                    if (i == 100) {
                        for (SseEmitter emitter : emitters) {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("COMPLETE") // 事件名称：客户端监听"COMPLETE"事件
                                        .data("Task completed successfully!"));
                            } catch (IOException e) {
                                emitters.remove(emitter);
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                // 任务被中断时，恢复线程中断状态并退出
                Thread.currentThread().interrupt();
                // break;
            }
        });

        return "Task started!"; // 告诉调用者任务已启动
    }
}
