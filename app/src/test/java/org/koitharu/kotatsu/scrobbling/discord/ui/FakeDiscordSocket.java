package org.koitharu.kotatsu.scrobbling.discord.ui;

import com.my.kizzyrpc.entities.presence.Presence;
import com.my.kizzyrpc.websocket.DiscordWebSocket;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;

/** The dependency interface is sealed only in Kotlin metadata; Java permits a network-free fixture. */
public final class FakeDiscordSocket implements DiscordWebSocket {
    private final CoroutineContext context;
    private final List<Presence> presences = new ArrayList<>();
    private int connections;
    private int closes;

    public FakeDiscordSocket(CoroutineContext context) { this.context = context; }
    public List<Presence> getPresences() { return presences; }
    public int getConnections() { return connections; }
    public int getCloses() { return closes; }

    @Override public CoroutineContext getCoroutineContext() { return context; }
    @Override public Object connect(Continuation<? super Unit> continuation) {
        connections++;
        return Unit.INSTANCE;
    }
    @Override public Object sendActivity(Presence presence, Continuation<? super Unit> continuation) {
        presences.add(presence);
        return Unit.INSTANCE;
    }
    @Override public boolean isWebSocketConnected() { return closes == 0; }
    @Override public void close() { closes++; }
}
