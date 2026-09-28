package io.github.jiemo9527.httpshare.server;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同步查阅：App 内打开某个共享目录时 publish()，打开 /showme 的网页通过长轮询收到同一路径。
 * 只下发路径（"/序号/相对路径"），网页再调用 /api/ls，所以权限与路径校验完全复用普通接口。
 *
 * 用长轮询而不是 SSE：实测 Cloudflare 临时隧道会缓冲 text/event-stream（首包都收不到），
 * 普通请求/响应在任何代理下都能即时返回。
 */
public final class ShowMe {

    private static final long WAIT_MS = 25_000;
    private static final int MAX_WAITERS = 32;

    private final Object lock = new Object();
    private String path = "";
    private String title = "";
    private long version;
    private int waiting;
    /** 客户端 id → 最后一次轮询时间，用于统计在看的网页数 */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /** p 为 "/序号/a/b"；传 null 表示 App 已离开浏览页 */
    public void publish(String p, String title) {
        synchronized (lock) {
            String np = p == null ? "" : p;
            String nt = title == null ? "" : title;
            if (np.equals(path) && nt.equals(this.title)) {
                return;
            }
            this.path = np;
            this.title = nt;
            version++;
            lock.notifyAll();
        }
    }

    /** 最近 40 秒内轮询过的网页数 */
    public int viewers() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Iterator<Long> it = seen.values().iterator(); it.hasNext(); ) {
            if (now - it.next() > 40_000) {
                it.remove();
            } else {
                n++;
            }
        }
        return n;
    }

    /** 版本与 v 不同立即返回，否则最多等待 25 秒；v=-1 表示首次请求 */
    JSONObject poll(String client, long v) throws Exception {
        if (client != null && client.length() <= 64) {
            seen.put(client, System.currentTimeMillis());
        }
        synchronized (lock) {
            if (v == version && waiting < MAX_WAITERS) {
                waiting++;
                try {
                    long end = System.currentTimeMillis() + WAIT_MS;
                    long left;
                    while (v == version && (left = end - System.currentTimeMillis()) > 0) {
                        lock.wait(left);
                    }
                } finally {
                    waiting--;
                }
            }
            return new JSONObject().put("p", path).put("title", title).put("v", version);
        }
    }

    /** 服务停止时唤醒所有等待者 */
    void shutdown() {
        synchronized (lock) {
            version++;
            path = "";
            title = "";
            lock.notifyAll();
        }
    }
}
