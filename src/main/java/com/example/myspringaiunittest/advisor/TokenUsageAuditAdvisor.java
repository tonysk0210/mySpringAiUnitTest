package com.example.myspringaiunittest.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

// 在 ChatClient 呼叫 AI 之後，讀取這次回應的 token 使用量，並寫到 log 裡面。
@Slf4j
public class TokenUsageAuditAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {

        // 1. 先把 request 傳給下一個 advisor，最後真正送去 AI model，並取得回應。也就是說，這個 advisor 是在「AI 回應回來之後」才開始做 token usage logging。
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);

        // 2. 取得 AI 的回應內容，裡面包含 metadata，而 metadata 裡面就有 token usage 的資訊。
        ChatResponse chatResponse = chatClientResponse.chatResponse();

        // 3. 取得 token usage 的資訊。注意，metadata 或 usage 可能為 null，所以要先檢查。
        Usage usage = chatResponse.getMetadata().getUsage();

        // 4. 如果 usage 不為 null，就 log 它。
        if (usage != null) {
            log.info("單次 LLM 呼叫 Token 使用量（本次輸入 + 輸出）: {}", usage.toString());
        }
        return chatClientResponse;
    }

    @Override
    public String getName() {
        return "TokenUsageAuditAdvisor";
    } // 命名要唯一，否則會跟其他 advisor 搩上名

    @Override
    public int getOrder() {
        return -1;
    } // 優先順序，數字越小越先執行
}
