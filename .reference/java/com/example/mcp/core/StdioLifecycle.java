package com.example.mcp.core;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/**
 * STDIO server 的生命周期管理。
 *
 * ══════════════════════════════════════════════════════════════════
 *  解决什么问题
 * ══════════════════════════════════════════════════════════════════
 *
 * MCP 官方 SDK 的 stdio server 有个容易被忽略的行为：
 * **主线程一旦挂住就永远不退出，即使 stdin 已经关闭。**
 *
 * 后果有两个：
 *
 *   1. 孤儿进程。客户端崩了、被 kill 了、没调 closeGracefully()，
 *      server 进程会一直赖在任务管理器里。跑几十次就堆一堆。
 *
 *   2. 退出码非零。客户端只能强杀进程，Windows 上强杀的退出码是 1，
 *      于是客户端每次都打印 "MCP server process failed with code 1" ——
 *      明明是一次正常关闭，看起来却像崩溃。
 *
 * 根因是：SDK 没有暴露"会话已关闭"的回调，
 * 而 server 的 main 通常写成 `new CountDownLatch(1).await()`（永久阻塞）。
 *
 * ══════════════════════════════════════════════════════════════════
 *  思路
 * ══════════════════════════════════════════════════════════════════
 *
 * 既然 SDK 不给回调，就自己造一个信号：
 * 把 System.in 包一层，一旦读到 EOF（-1）就释放 latch。
 *
 * stdin 关闭 == 客户端已断开，这是 stdio 传输下最可靠的断连信号。
 *
 * StdioServerTransportProvider 正好有个接收自定义流的构造函数，
 * 所以这个包装可以无侵入地塞进去。
 */
public final class StdioLifecycle {

    private StdioLifecycle() {
    }

    /**
     * 创建一个带断连感知的传输。
     */
    public static Handle create(McpJsonMapper jsonMapper) {
        CountDownLatch eof = new CountDownLatch(1);
        McpServerTransportProvider provider = new StdioServerTransportProvider(
                jsonMapper,
                new EofAwareInputStream(System.in, eof),
                System.out);
        return new Handle(provider, eof);
    }

    public record Handle(McpServerTransportProvider provider, CountDownLatch eof) {

        /**
         * 阻塞，直到客户端断开（stdin EOF）。
         *
         * 返回后应该调用 server.closeGracefully() 再让 main 结束，
         * 这样 JVM 会以退出码 0 正常退出。
         */
        public void awaitClientDisconnect() {
            try {
                eof.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 转发所有读取，顺手盯住 EOF。
     *
     * 注意要覆盖 read() 和 read(byte[], int, int) 两个重载——
     * 具体走哪个取决于调用方，漏掉一个就检测不到 EOF。
     */
    private static final class EofAwareInputStream extends FilterInputStream {

        private final CountDownLatch eof;

        EofAwareInputStream(InputStream in, CountDownLatch eof) {
            super(in);
            this.eof = eof;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b == -1) {
                eof.countDown();
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n == -1) {
                eof.countDown();
            }
            return n;
        }
    }
}
