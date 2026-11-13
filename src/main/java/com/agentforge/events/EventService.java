package com.agentforge.events;

import com.agentforge.common.Db;
import com.agentforge.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persists every workflow event (the Decision Trace) and pushes it live to subscribed browsers
 * over Server-Sent Events. The radar and timeline are driven only by these real events.
 */
@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private final Db db;
    private final Map<Long, List<SseEmitter>> taskSubs = new ConcurrentHashMap<>();
    private final Map<Long, List<SseEmitter>> userSubs = new ConcurrentHashMap<>();

    public EventService(Db db) {
        this.db = db;
    }

    public Map<String, Object> emit(long taskId, Integer version, String type, String agent, String target, String summary, Object payload) {
        long id = db.insert("events", Db.row(
                "task_id", taskId, "version_no", version, "type", type, "agent", agent, "target", target,
                "summary", summary, "payload", payload == null ? null : Json.write(payload)));
        Map<String, Object> row = db.one("SELECT * FROM events WHERE id=?", id).orElseThrow();
        send(taskSubs.get(taskId), "agent-event", row);
        return row;
    }

    public List<Map<String, Object>> history(long taskId) {
        return db.list("SELECT * FROM events WHERE task_id=? ORDER BY id", taskId);
    }

    public void notifyUser(long userId, Long taskId, String kind, String message) {
        long id = db.insert("notifications", Db.row("user_id", userId, "task_id", taskId, "kind", kind, "message", message));
        Map<String, Object> row = db.one("SELECT * FROM notifications WHERE id=?", id).orElseThrow();
        send(userSubs.get(userId), "notification", row);
    }

    /** Pushes a lightweight status change to the owner's dashboard without creating a notification. */
    public void pushUser(long userId, String name, Object data) {
        send(userSubs.get(userId), name, data);
    }

    public SseEmitter subscribeTask(long taskId) {
        return register(taskSubs, taskId);
    }

    public SseEmitter subscribeUser(long userId) {
        return register(userSubs, userId);
    }

    private SseEmitter register(Map<Long, List<SseEmitter>> map, long key) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = map.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("ok", true)));
        } catch (Exception e) {
            remove.run();
        }
        return emitter;
    }

    private void send(List<SseEmitter> list, String name, Object data) {
        if (list == null) return;
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(name).data(data));
            } catch (Exception ex) {
                list.remove(e);
            }
        }
    }

    @Scheduled(fixedRate = 20000)
    public void heartbeat() {
        for (Map<Long, List<SseEmitter>> map : List.of(taskSubs, userSubs)) {
            for (List<SseEmitter> list : map.values()) {
                for (SseEmitter e : list) {
                    try {
                        e.send(SseEmitter.event().comment("ping"));
                    } catch (Exception ex) {
                        list.remove(e);
                    }
                }
            }
        }
        log.trace("SSE heartbeat sent");
    }
}
