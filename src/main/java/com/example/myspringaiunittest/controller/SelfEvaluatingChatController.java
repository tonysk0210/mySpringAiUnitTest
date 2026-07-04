package com.example.myspringaiunittest.controller;

import com.example.myspringaiunittest.advisor.PrettyLoggerAdvisor;
import com.example.myspringaiunittest.advisor.TokenUsageAuditAdvisor;
import com.example.myspringaiunittest.exception.InvalidAnswerException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

@RestController
@RequestMapping("/api")
public class SelfEvaluatingChatController {

    private final ChatClient chatClient;
    private final FactCheckingEvaluator factCheckingEvaluator;

    @Value("classpath:/promptTemplates/hrPolicy.st")
    Resource hrPolicyTemplate;

    @Autowired
    public SelfEvaluatingChatController(ChatClient.Builder chatClientBuilder,
                                        @Value("classpath:/promptTemplates/factcheck.st") Resource factCheckTemplate) throws IOException {
        this.chatClient = chatClientBuilder
                .defaultAdvisors(new TokenUsageAuditAdvisor(), new PrettyLoggerAdvisor())
                .defaultSystem("請用中文繁體回答")
                .build();
        this.factCheckingEvaluator = FactCheckingEvaluator.builder(chatClientBuilder)
                .evaluationPrompt(factCheckTemplate.getContentAsString(Charset.defaultCharset())).build();
    }

    /**
     * @Retryable(retryFor = InvalidAnswerException.class, maxAttempts = 3)
     * 執行流程
     * <p>
     * 第 1 次執行 chat()
     * ↓ AI 回答事實錯誤 → validateAnswer() 拋出 InvalidAnswerException
     * 第 2 次重試 chat()
     * ↓ AI 回答事實錯誤 → 再次拋出 InvalidAnswerException
     * 第 3 次重試 chat()
     * ↓
     * ├── AI 回答正確 → 回傳結果給使用者 ✅
     * └── 仍然錯誤   → 呼叫 @Recover 方法 → 回傳「很抱歉，我無法回答您的問題...」
     */
    @Retryable(retryFor = InvalidAnswerException.class, maxAttempts = 3)
    @GetMapping("/evaluate/chat")
    public String chat(@RequestParam("message") String message) {
        // 1. 取得 AI 的回應
        String aiResponse = chatClient.prompt().user(message)
                .call().content();

        // validateAnswer() 在 return aiResponse 之前被呼叫，確保 AI 的回答通過事實正確性檢查後才回傳給使用者，若不通過則拋出例外，使用者永遠不會收到事實錯誤的回答。
        // 缺點：每次請求都會額外呼叫一次 LLM 進行驗證，造成雙倍的 Token 消耗與延遲（使用者需等待兩次 LLM 回應才能收到結果）。
        // 2. 驗證 AI 的回答是否事實正確 - 使用 FactCheckingEvaluator
        validateAnswer(message, aiResponse); // validateAnswer(message, "中國的首都是台灣"); // testing purpose
        return aiResponse;
    }

    /**
     * 一個私有的驗證工具方法，用來確認 AI 的回答是否事實正確 - 使用 FactCheckingEvaluator
     */
    private void validateAnswer(String message, String answer) {
        // 1. 建立 EvaluationRequest - 評估請求
        EvaluationRequest evaluationRequest =
                new EvaluationRequest(message, List.of(), answer);
        // 2. 送給 FactCheckingEvaluator 進行評估
        EvaluationResponse evaluationResponse = factCheckingEvaluator.evaluate(evaluationRequest);
        // 3. 檢查評估結果
        if (!evaluationResponse.isPass()) {
            throw new InvalidAnswerException(message, answer);
        }
    }

    /**
     * 一個回復方法，用來處理 @Retryable 標記的例外 - 當 @Retryable 的所有重試次數都耗盡後，自動呼叫這個方法作為最後的處理。
     */
    @Recover
    public String recover(InvalidAnswerException exception) {
        return "很抱歉，我無法回答您的問題，請嘗試換個方式提問。";
    }

}
