package com.example.myspringaiunittest;

import com.example.myspringaiunittest.advisor.PrettyLoggerAdvisor;
import com.example.myspringaiunittest.controller.ChatController;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
// JUnit 只為這個測試類別建立一個測試物件實例，而不是每個 @Test method 都建立一個新實例。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// 在測試環境中額外加入 properties，這些設定會進到 Spring Test 的 Environment，通常會覆蓋或補充 application.properties 裡的設定
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=${OPENAI_API_KEY:test-key}",
        "logging.level.org.springframework.ai=DEBUG"
})
class MySpringAiUnitTestApplicationTests {

    //  Spring Test framework 特別支援測試類別注入。
    @Autowired
    private ChatController chatController;
    @Autowired
    private ChatModel chatModel;

    private ChatClient chatClient;
    private RelevancyEvaluator relevancyEvaluator;
    private FactCheckingEvaluator factCheckingEvaluator;

    // 最低相關性分數門檻
    @Value("${test.relevancy.min-score:0.7}")
    private float minRelevancyScore;

    // 因為 factcheck.st 第 4 條指示明確告訴 LLM「沒有文件時要用自身知識判斷」，所以即使 {document} 是空的，LLM 仍能根據訓練資料中已知的事實回答 yes，而不是因為缺乏依據而回 no。
    @Value("classpath:/promptTemplates/factcheck.st")
    Resource factCheckTemplate;

    @Value("classpath:/promptTemplates/hrPolicy.st")
    Resource hrPolicyTemplate;

    // 初始化每個 test 前需要的測試狀態。
    @BeforeEach
    void setup() throws IOException {
        // 1. 建立 ChatClient
        ChatClient.Builder chatClientBuilder =
                ChatClient.builder(chatModel).defaultAdvisors(new PrettyLoggerAdvisor());
        this.chatClient = chatClientBuilder.build();

        // 2. 建立 RelevancyEvaluator 接收 chatClientBuilder 主要為了測試 RelevancyEvaluator
        this.relevancyEvaluator = new RelevancyEvaluator(chatClientBuilder);

        // 3. 建立 FactCheckingEvaluator
        this.factCheckingEvaluator = FactCheckingEvaluator.builder(chatClientBuilder)
                .evaluationPrompt(factCheckTemplate.getContentAsString(Charset.defaultCharset())) // 使用 factCheckTemplate，告訴 evaluator 如何判斷回答是否事實正確
                .build();
        /**
         * FactCheckingEvaluator 內部有一個寫死的英文預設 prompt，而 .evaluationPrompt(...) 是 builder 提供的方法，讓你把自己的字串直接替換掉那個內建 prompt，所以當 evaluate() 被呼叫時，送給 LLM 的指令就是你
         *   factcheck.st 裡的繁體中文內容，而不是原本的英文版。
         */
    }

    /**
     * RelevancyEvaluator- 測試 ChatController 的回應是否與問題相關，以及相關性分數是否超過門檻。
     */
    //@Test
    @DisplayName("應回傳與基本地理問題相關的回應")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void evaluateChatControllerResponseRelevancy() {
        // Given
        String question = "台灣的首都是哪裡？";

        // When
        // 1. 取得 AI 回應
        String aiResponse = chatController.chat(question);
        // 2. 建立 EvaluationRequest
        EvaluationRequest evaluationRequest = new EvaluationRequest(question, aiResponse);
        // 3. 评估回應是否與問題相關 - 使用 RelevancyEvaluator
        EvaluationResponse response = relevancyEvaluator.evaluate(evaluationRequest);
        // 4. 驗證 - 回應是否符合要求 - 三個斷言全部都會執行，不管前面有沒有失敗。
        Assertions.assertAll(
                // 斷言 1：回應不能是空白
                () -> assertThat(aiResponse).isNotBlank(),
                // 斷言 2：回應必須與問題相關
                () -> assertThat(response.isPass())
                        .withFailMessage("""
                                ========================================
                                此回答被認為與問題不相關。
                                問題："%s"
                                回應："%s"
                                ========================================
                                """, question, aiResponse)
                        .isTrue(),
                // 斷言 3：相關性分數必須超過門檻 0.7 （0.0 ~ 1.0）
                () -> assertThat(response.getScore())
                        .withFailMessage("""
                                ========================================
                                分數 %.2f 低於最低要求 %.2f。
                                問題："%s"
                                回應："%s"
                                ========================================
                                """, response.getScore(), minRelevancyScore, question, aiResponse)
                        .isGreaterThan(minRelevancyScore));
    }

    /**
     * FactCheckingEvaluator- 測試 ChatController 的回應是否為事實正確。
     */
    @Test
    @DisplayName("應回傳與重力相關問題的正確事實回應")
    @Timeout(value = 30)
    void evaluateFactAccuracyForGravityQuestion() {
        // Given
        String question = "誰發現了萬有引力定律？";

        // When
        // 1. 取得 AI 回應
        String aiResponse = chatController.chat(question);
        // 2. 建立 EvaluationRequest
        EvaluationRequest evaluationRequest = new EvaluationRequest(question, aiResponse);
        // 3. 评估回應是否為事實正確 - 使用 FactCheckingEvaluator
        EvaluationResponse response = factCheckingEvaluator.evaluate(evaluationRequest);

        // 4. 驗證 - 回應是否符合要求 - 兩個斷言全部都會執行，不管前面有沒有失敗。
        Assertions.assertAll(
                // 斷言 1：回應不能是空白
                () -> assertThat(aiResponse).isNotBlank(),
                // 斷言 2：回應必須是事實正確
                () -> assertThat(response.isPass())
                        .withFailMessage("""
                                ========================================
                                此回答被認為事實不正確。
                                問題：%s
                                回應：%s
                                上下文：%s
                                ========================================
                                """, question, aiResponse, "")
                        .isTrue());
    }

}
