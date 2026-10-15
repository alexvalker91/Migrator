package alex.valker91.testmigrator;

import dev.langchain4j.model.output.TokenUsage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsTracker {
    private final Map<String, ModelStats> stats = new ConcurrentHashMap<>();
    private final long testStartTime = System.currentTimeMillis();

    public void record(String modelName, TokenUsage tokenUsage, long durationMs) {
        if (tokenUsage == null) return;
        stats.computeIfAbsent(modelName, k -> new ModelStats())
                .add(tokenUsage.inputTokenCount(), tokenUsage.outputTokenCount(), durationMs);
    }

    public void printDashboard() {
        long totalTestTime = (System.currentTimeMillis() - testStartTime) / 1000;
        int totalPrompt = 0;
        int totalCompletion = 0;
        int totalCalls = 0;

        for (ModelStats s : stats.values()) {
            totalPrompt += s.promptTokens.get();
            totalCompletion += s.completionTokens.get();
            totalCalls += s.calls.get();
        }
        int grandTotal = totalPrompt + totalCompletion;

        System.out.println("\n");
        System.out.println("╔════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                     LLM USAGE & PERFORMANCE DASHBOARD                      ║");
        System.out.println("╠════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Общее время теста: %-10s  │  Всего запросов к LLM: %-21d ║\n", totalTestTime + " сек", totalCalls);
        System.out.printf("║  Суммарный расход:  %-10s  │  Промпт: %-6d  Ответ: %-6d       ║\n", grandTotal + " токенов", totalPrompt, totalCompletion);
        System.out.println("╠════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║ MODEL                      CALLS   PROMPT TOK   COMPL TOK   TOTAL TOK   AVG TIME   ║");
        System.out.println("╟────────────────────────────────────────────────────────────────────────────╢");

        stats.forEach((model, s) -> {
            int prompt = s.promptTokens.get();
            int compl = s.completionTokens.get();
            int tot = prompt + compl;
            int calls = s.calls.get();
            long avgMs = calls > 0 ? s.totalDurationMs.get() / calls : 0;

            String shortModel = model.length() > 24 ? model.substring(0, 21) + "..." : model;
            System.out.printf("║ %-24s  %-6d  %-10d   %-9d   %-9d   %-6.1fs    ║\n",
                    shortModel, calls, prompt, compl, tot, avgMs / 1000.0);
        });

        System.out.println("╠════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║                        РАСПРЕДЕЛЕНИЕ РАСХОДА ТОКЕНОВ                       ║");
        System.out.println("╟────────────────────────────────────────────────────────────────────────────╢");

        // Визуальный прогресс-бар соотношения Input / Output
        if (grandTotal > 0) {
            int barLength = 40;
            int inBar = (int) Math.round(((double) totalPrompt / grandTotal) * barLength);
            int outBar = barLength - inBar;

            String progressBar = "█".repeat(Math.max(0, inBar)) + "░".repeat(Math.max(0, outBar));
            double inPct = ((double) totalPrompt / grandTotal) * 100.0;
            double outPct = 100.0 - inPct;

            System.out.printf("║  Prompt [█] vs Output [░]:                                                 ║\n");
            System.out.printf("║  [%s] %4.1f%% / %4.1f%%           ║\n", progressBar, inPct, outPct);
        }

        System.out.println("╚════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static class ModelStats {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger promptTokens = new AtomicInteger();
        final AtomicInteger completionTokens = new AtomicInteger();
        final AtomicLong totalDurationMs = new AtomicLong();

        void add(Integer inTok, Integer outTok, long ms) {
            calls.incrementAndGet();
            if (inTok != null) promptTokens.addAndGet(inTok);
            if (outTok != null) completionTokens.addAndGet(outTok);
            totalDurationMs.addAndGet(ms);
        }
    }
}
