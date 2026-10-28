package nz.co.rsfa.endforms.admin;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Host of the private administration service ({@code ENGINE_ROLE=admin}, D-041): only {@code /admin/*}, and
 * {@code /} redirecting there. It has no {@code /generate}, {@code /v1} or demo page and never calls the engine
 * service; test PDFs are filled in-process with the same library. Identity comes from IAP ({@link AdminAccess}).
 */
public final class AdminServer {

    private final AdminApi api;
    private HttpServer server;
    private ExecutorService executor;

    public AdminServer(AdminApi api) {
        this.api = api;
    }

    public int start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/admin", api::handle);
        server.createContext("/", ex -> {
            try (ex) {
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                if (ex.getRequestURI().getPath().equals("/") && ex.getRequestMethod().equals("GET")) {
                    ex.getResponseHeaders().set("Location", "/admin/");
                    ex.sendResponseHeaders(302, -1);
                } else {
                    ex.sendResponseHeaders(404, -1);
                }
            }
        });
        executor = Executors.newFixedThreadPool(16);
        server.setExecutor(executor);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            executor.shutdownNow();
        }
    }
}
