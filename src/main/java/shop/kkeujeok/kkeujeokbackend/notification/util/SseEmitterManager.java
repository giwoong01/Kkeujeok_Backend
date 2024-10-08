package shop.kkeujeok.kkeujeokbackend.notification.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class SseEmitterManager {
    private static final String EMITTER_NAME = "notification";
    private static final String DUMMY_MESSAGE = "연결 성공";

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(Long memberId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.put(memberId, emitter);

        emitter.onCompletion(() -> emitters.remove(memberId));
        emitter.onTimeout(() -> emitters.remove(memberId));
        emitter.onError((e) -> emitters.remove(memberId));

        sendNotification(memberId, DUMMY_MESSAGE);

        return emitter;
    }

    public void sendNotification(Long memberId, String message) {
        SseEmitter emitter = emitters.get(memberId);

        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .data(message));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }
    }
}
