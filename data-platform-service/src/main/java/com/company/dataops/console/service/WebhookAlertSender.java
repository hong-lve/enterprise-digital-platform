package com.company.dataops.console.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Delivers alerts outside the portal - the in-app message
 * (MessageEntity/RealtimeAlertService) only reaches someone who happens to
 * be looking at the portal at that moment. Uses DingTalk's custom-robot
 * webhook shape ({"msgtype":"text","text":{"content":...}}) since that's
 * the realistic integration target for an internal Chinese-enterprise
 * platform like this one, and it's the only concrete, testable shape - no
 * other webhook precedent exists in this monorepo.
 *
 * Deliberately not implemented: DingTalk's HMAC-SHA256 "加签" signing mode
 * (needs a timestamp + sign query param computed from a shared secret). v1
 * assumes the webhook URL itself (embedded access_token, or a keyword
 * security check) is sufficient - a signed group would silently reject
 * this and only a WARN log would show it.
 */
@Component
public class WebhookAlertSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookAlertSender.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final String webhookUrl;

    public WebhookAlertSender(@Value("${platform.bigdata.alert-webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /** Never throws - same best-effort contract as RealtimeAlertService.send(). */
    public void send(String title, String content, String type, String linkUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            String prefix = "RECOVERY".equals(type) ? "【恢复】" : "【告警】";
            StringBuilder text = new StringBuilder(prefix).append(title);
            if (content != null && !content.isBlank()) {
                text.append('\n').append(content);
            }
            if (linkUrl != null && !linkUrl.isBlank()) {
                text.append("\n详情：").append(linkUrl);
            }
            Map<String, Object> textBody = new LinkedHashMap<>();
            textBody.put("content", text.toString());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "text");
            body.put("text", textBody);
            restTemplate.postForObject(webhookUrl, body, Map.class);
        } catch (Exception exception) {
            LOGGER.warn("Failed to deliver {} webhook alert '{}': {}", type, title, exception.getMessage());
        }
    }
}
