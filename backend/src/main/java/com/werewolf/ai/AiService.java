package com.werewolf.ai;

import com.werewolf.ai.dto.AiContext;
import com.werewolf.ai.dto.AiDecision;
import com.werewolf.config.AiProperties;
import com.werewolf.game.domain.ActionType;
import com.werewolf.game.domain.PlayerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

/**
 * AI NPC 服务的统一入口。
 *
 * <p>职责：
 * <ol>
 *   <li>构建上下文（只含公开信息）；</li>
 *   <li>在 LLM 与规则 AI 之间路由，并在任何异常时回退规则 AI；</li>
 *   <li>提供“拟人化思考延迟”，让 AI 的行动节奏接近真人；</li>
 *   <li>统计调用次数，遵守单局调用上限（方案第十四节：控制 AI 成本）。</li>
 * </ol>
 */
@Service
public class AiService {

    private final java.util.concurrent.ThreadPoolExecutor decisions=new java.util.concurrent.ThreadPoolExecutor(2,4,30,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(64),r->{var thread=new Thread(r,"jiujiu-ai");thread.setDaemon(true);return thread;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    @jakarta.annotation.PreDestroy public void shutdown(){decisions.shutdownNow();}
    public boolean mayCallLlm(int used){return properties.isLlmUsable()&&used<properties.getMaxCallsPerGame();}
    /** Capture the authorized private view under the room lock; network work never reads live state. */
    public java.util.concurrent.CompletableFuture<AiDecision> request(AiRuntimeView runtime,PlayerState actor,List<ActionType> actions,int used){
        var allowed=List.copyOf(actions);var targets=List.copyOf(runtime.legalTargets(actor,actions));
        var context=contextBuilder.build(runtime.state(),actor,allowed,targets);
        if(!mayCallLlm(used))return java.util.concurrent.CompletableFuture.completedFuture(ruleAgent.decide(context,allowed,targets));
        try{return java.util.concurrent.CompletableFuture.supplyAsync(()->llmAgent.decide(context,allowed,targets),decisions).exceptionally(error->ruleAgent.decide(context,allowed,targets));}
        catch(java.util.concurrent.RejectedExecutionException error){return java.util.concurrent.CompletableFuture.completedFuture(ruleAgent.decide(context,allowed,targets));}
    }

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiProperties properties;
    private final AiContextBuilder contextBuilder;
    private final RuleAgent ruleAgent;
    private final LlmAgent llmAgent;

    public AiService(AiProperties properties, AiContextBuilder contextBuilder, RuleAgent ruleAgent,
                     LlmAgent llmAgent) {
        this.properties = properties;
        this.contextBuilder = contextBuilder;
        this.ruleAgent = ruleAgent;
        this.llmAgent = llmAgent;
    }

    /**
     * 让某个 AI 座位做出决策。
     *
     * @param runtime          只读对局视图
     * @param actor            AI 座位
     * @param allowedActions   引擎给出的可用行动
     * @param llmCallsUsed     本局已经用掉的 LLM 调用次数（超过上限强制走规则 AI）
     */
    public AiDecision decide(AiRuntimeView runtime, PlayerState actor,
                             List<ActionType> allowedActions, int llmCallsUsed) {
        List<Integer> targets = runtime.legalTargets(actor, allowedActions);
        AiContext ctx = contextBuilder.build(runtime.state(), actor, allowedActions, targets);

        boolean llmAllowed = properties.isLlmUsable()
                && llmCallsUsed < properties.getMaxCallsPerGame();
        if (!llmAllowed) {
            AiDecision rule = ruleAgent.decide(ctx, allowedActions, targets);
            String reason = !properties.isEnabled()
                    ? "AI 总开关关闭，使用规则 AI"
                    : (llmCallsUsed >= properties.getMaxCallsPerGame()
                        ? "已达单局 LLM 调用上限(" + properties.getMaxCallsPerGame() + ")，使用规则 AI"
                        : "未配置 LLM API Key，使用规则 AI");
            return AiDecision.rule(rule.action(), rule.targetSeatId(), rule.speech(), reason);
        }
        try {
            return llmAgent.decide(ctx, allowedActions, targets);
        } catch (Exception ex) {
            log.warn("AI 决策异常，回退规则 AI: seat={}, err={}", actor.getSeatId(), ex.toString());
            AiDecision rule = ruleAgent.decide(ctx, allowedActions, targets);
            return AiDecision.fallback(rule.action(), rule.targetSeatId(), rule.speech(),
                    "AI 决策异常，已回退规则 AI");
        }
    }

    /** 拟人化思考延迟（毫秒）。 */
    public long thinkDelay(Random random) {
        long min = Math.max(200, properties.getMinThinkMillis());
        long max = Math.max(min + 100, properties.getMaxThinkMillis());
        return min + (long) (random.nextDouble() * (max - min));
    }

    public boolean isLlmEnabled() {
        return properties.isLlmUsable();
    }

    public String providerDescription() {
        if (!properties.isEnabled()) {
            return "规则 AI（AI 总开关关闭）";
        }
        if (!properties.isLlmUsable()) {
            return "规则 AI（未配置 API Key，零外部依赖）";
        }
        return "LLM(" + properties.getModel() + " @ " + properties.getBaseUrl() + ") + 规则 AI 兜底";
    }
}
