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
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
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
        /*this.factCheckingEvaluator = FactCheckingEvaluator.builder(chatClientBuilder)
                .evaluationPrompt(factCheckTemplate.getContentAsString(Charset.defaultCharset()))
                .build();*/
    }

    @Test
    @DisplayName("應回傳與基本地理問題相關的回應")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void evaluateChatControllerResponseRelevancy() {
        // Given
        String question = "印度的首都是哪裡？";

        // When
        // 1. 取得 AI 回應
        String aiResponse = chatController.chat(question);
        // 2. 建立 EvaluationRequest
        EvaluationRequest evaluationRequest = new EvaluationRequest(question, aiResponse);
        // 3. 评估回應是否與問題相關 - 使用 RelevancyEvaluator
        EvaluationResponse response = relevancyEvaluator.evaluate(evaluationRequest);
        // 4. 驗證回應是否符合要求 - 三個斷言全部都會執行，不管前面有沒有失敗。
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

}
