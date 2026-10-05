package org.koitharu.kotatsu.scrobbling.discord.ui;

import com.my.kizzyrpc.entities.Payload;
import com.my.kizzyrpc.logger.NoOpLogger;
import com.my.kizzyrpc.websocket.DiscordWebSocketImpl;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.Job;

/** Java can call the superclass member extension, which Kotlin cannot qualify through super. */
final class DiscordTokenSocket extends DiscordWebSocketImpl {
    private final CoroutineContext context;
    private final Runnable onReady;

    DiscordTokenSocket(String token, CoroutineContext context, Runnable onReady) {
        super(token, NoOpLogger.INSTANCE);
        this.context = context;
        this.onReady = onReady;
    }

    @Override
    public CoroutineContext getCoroutineContext() {
        // The library getter creates a new Job each time; use one stable owned child instead.
        return context;
    }

    @Override
    public void handleDispatch(Payload payload) {
        super.handleDispatch(payload);
        if ("READY".equals(payload.getT()) || "RESUMED".equals(payload.getT())) {
            onReady.run();
        }
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            Job job = context.get(Job.Key);
            if (job != null) job.cancel(null);
        }
    }
}
