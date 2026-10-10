package com.example.mcp.db;

import com.example.mcp.core.McpAgent;

import java.util.Arrays;
import java.util.Scanner;

/**
 * 把「只读数据库 MCP」接到 Claude 上——完整可用的自然语言查库。
 *
 * 自检（不需要 API Key，先确认 MCP 侧接好了）：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain -DappArgs=check
 *
 * 默认问题（需要 ANTHROPIC_API_KEY）：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain
 *
 * 自定义问题：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain \
 *       -DappArgs="9 月份领用数量最多的 5 个备件是什么"
 *
 * 交互模式（连接一次，连续提问）：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain -DappArgs=chat
 */
public final class DbAgentMain {

    /** 指向我们的数据库 MCP Server。换 server 只改这一行。 */
    private static final String SERVER = "com.example.mcp.db.DbMcpServer";

    private static final String DEFAULT_QUESTION =
            "9 月份领用数量最多的 5 个备件是什么？分别用在哪个部门？";

    public static void main(String[] args) {
        // pom 里的 ${appArgs} 为空时会传进来一个空字符串参数，先过滤掉，
        // 否则它会被当成"问题为空"而不是"没带参数"
        String[] effective = Arrays.stream(args)
                .filter(a -> !a.isBlank())
                .toArray(String[]::new);

        if (effective.length == 1 && effective[0].equals("check")) {
            dryRun();
            return;
        }

        if (effective.length == 1 && effective[0].equals("chat")) {
            runInteractive();
            return;
        }

        String question = effective.length > 0
                ? String.join(" ", effective)
                : DEFAULT_QUESTION;

        // try-with-resources 保证 MCP server 子进程被正确终止
        try (McpAgent agent = McpAgent.connect(SERVER)) {
            agent.ask(question);
        }
    }

    /**
     * 自检：只连 MCP，不调用模型。
     *
     * 建议第一次跑的时候先用这个。它能验证：
     *   - server 子进程能起来
     *   - 握手成功、工具清单能拿到
     *   - 工具 schema 能转成 Claude 格式而不报错
     *
     * 这些都不需要 API Key。确认没问题了再去跑真正的提问，
     * 免得把一个配置问题误判成"模型不好用"。
     */
    private static void dryRun() {
        System.out.println("目标数据源: " + Db.describeTarget());
        try (McpAgent agent = McpAgent.connect(SERVER)) {
            System.out.println();
            System.out.println("✅ 自检通过：MCP Server 连接正常，工具已全部加载。");
            System.out.println();
            System.out.println("下一步（需要 ANTHROPIC_API_KEY）：");
            System.out.println("  mvn -q exec:exec -DmainClass=" + DbAgentMain.class.getName());
            System.out.println("  mvn -q exec:exec -DmainClass=" + DbAgentMain.class.getName() + " -DappArgs=chat");
        }
    }

    /**
     * 交互模式。
     *
     * 注意这里只建立一次 MCP 连接，所有问题复用同一个 agent。
     * 现实中的对话产品都是这么做的——每次提问都重启一个 server 进程太浪费。
     *
     * 但要注意：ask() 每次都是从空的对话历史开始，
     * 所以它没有跨轮记忆。要做多轮对话，需要把 messages 提升为 agent 的字段。
     * （这是个很好的练习——自己动手改改看。）
     */
    private static void runInteractive() {
        try (McpAgent agent = McpAgent.connect(SERVER);
             Scanner scanner = new Scanner(System.in, "UTF-8")) {

            System.out.println();
            System.out.println("输入问题，回车执行。输入 exit 退出。");
            System.out.println();

            while (true) {
                System.out.print("> ");
                System.out.flush();

                if (!scanner.hasNextLine()) {
                    break;
                }
                String line = scanner.nextLine().strip();

                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }

                agent.ask(line);
                System.out.println();
            }
        }
    }
}
